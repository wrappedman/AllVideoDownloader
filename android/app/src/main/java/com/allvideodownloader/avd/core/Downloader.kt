package com.allvideodownloader.avd.core

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Android port of `webfinder/core/downloader.py`.
 *
 * The manager keeps the same contract the frontend was written against: job
 * dicts through `to_dict()`, `jobs.list` / `job.update` events on the WebSocket,
 * pause-resume through HTTP `Range`, "cancel = remove", and a concurrency dial.
 * Python's asyncio workers become plain threads (Android has no event loop for
 * this), which keeps the door/resume semantics identical while letting the
 * download survive configuration changes.
 */
class DownloadJob(
    val url: String,
    var kind: String = "http",
    val id: String = Net.jobId(),
    var filename: String = "download",
    var filepath: String = "",
    var title: String = "",
    var fileType: String = "link",
    var status: String = "queued",
    var total: Long = 0,
    var done: Long = 0,
    var speed: Double = 0.0,
    var eta: Double = 0.0,
    var unit: String = "bytes",
    var error: String = "",
    val meta: MutableMap<String, Any?> = LinkedHashMap(),
    var resumeOffset: Long = 0,
    var progressPct: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
    var finishedAt: Long = 0,
) {
    @Volatile var cancelRequested = false
    @Volatile var dropWhenDone = false
    @Volatile var removed = false
    @Volatile var pauseRequested = false
    @Volatile var process: Process? = null
    var thread: Thread? = null

    fun toDict(): MutableMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        for (k in listOf("id", "url", "kind", "filename", "filepath", "title", "file_type",
                    "status", "total", "done", "speed", "eta", "unit", "error",
                    "progress_pct", "created_at", "finished_at")) {
            m[k] = when (k) {
                "id" -> id
                "url" -> url
                "kind" -> kind
                "filename" -> filename
                "filepath" -> filepath
                "title" -> title
                "file_type" -> fileType
                "status" -> status
                "total" -> total
                "done" -> done
                "speed" -> speed
                "eta" -> eta
                "unit" -> unit
                "error" -> error
                "progress_pct" -> progressPct
                "created_at" -> createdAt / 1000.0
                else -> finishedAt / 1000.0
            }
        }
        m["meta"] = meta.filterKeys { it != "format" }
        return m
    }

    fun setProgress(done: Long, total: Long?, speed: Double? = null) {
        this.done = done
        if (total != null && total > 0) this.total = total
        if (this.total > 0) {
            progressPct = Math.round(minOf(100.0, done.toDouble() / this.total * 100.0) * 10) / 10.0
            if (speed != null && speed > 0) eta = maxOf(0.0, (this.total - done) / speed)
        }
        if (speed != null) this.speed = Math.round((this.speed * 0.7 + speed * 0.3) * 10) / 10.0
    }
}

/** Where finished files land when the user picked a SAF folder (content:// tree). */
object StorageExporter {
    /** Set by the Android layer: copies [file] into the SAF tree and returns success. */
    var exporter: ((File, String) -> Boolean)? = null
    /** Staging folder used while the real destination is a SAF tree. */
    var stagingDir: File = File(System.getProperty("java.io.tmpdir") ?: ".", "avd-downloads")
}

class DownloadManager {

    val jobs = LinkedHashMap<String, DownloadJob>()
    val order = ArrayList<String>()
    private val lock = ReentrantLock()
    private var gate: java.util.concurrent.Semaphore? = null
    @Volatile var emit: (MutableMap<String, Any?>) -> Unit = { }

    private fun semaphore(): java.util.concurrent.Semaphore {
        val n = maxOf(1, Config.settings().getInt("max_concurrent", 3))
        val current = gate
        if (current == null) {
            val s = java.util.concurrent.Semaphore(n)
            gate = s
            return s
        }
        // keep the dial live: adjust permits when the setting changed
        val available = current.availablePermits()
        val target = n
        val used = (gateSize ?: n) - available
        val wanted = maxOf(0, target - used)
        if (wanted > available) current.release(wanted - available)
        else if (wanted < available) runCatching { repeat(available - wanted) { current.tryAcquire() } }
        gateSize = target
        return current
    }

    private var gateSize: Int? = null

    fun rebuildSemaphore() {
        gate = java.util.concurrent.Semaphore(maxOf(1, Config.settings().getInt("max_concurrent", 3)))
        gateSize = null
    }

    fun emitEvent(type: String, payload: MutableMap<String, Any?>) {
        try {
            emit(linkedMapOf("type" to type, "payload" to payload))
        } catch (_: Exception) {
            // broadcasting must never kill a worker
        }
    }

    fun publish(job: DownloadJob) {
        if (job.removed) return
        emitEvent("job.update", job.toDict())
    }

    // ── job control ───────────────────────────────────────────────────────
    fun enqueue(
        url: String,
        kind: String = "http",
        filename: String = "",
        title: String = "",
        fileType: String = "",
        meta: Map<String, Any?>? = null,
    ): DownloadJob {
        val metaMap = meta?.let { LinkedHashMap(it) } ?: LinkedHashMap()
        val destDir = destDir()
        destDir.mkdirs()
        val name = uniqueName(destDir, filename.ifEmpty { FileKind.guessFilename(url) })
        val job = DownloadJob(
            url = url,
            kind = kind,
            filename = name,
            title = title.ifEmpty { name },
            fileType = fileType.ifEmpty { FileKind.classify(url) },
            meta = metaMap,
        )
        job.filepath = File(destDir, job.filename).absolutePath
        lock.withLock {
            jobs[job.id] = job
            order.add(job.id)
        }
        spawn(job)
        emitEvent("jobs.list", linkedMapOf("jobs" to listJobs()))
        return job
    }

    /** The folder jobs write into (SAF trees are staged locally, then exported). */
    fun destDir(): File {
        val configured = Config.settings().getString("download_dir").trim()
        if (configured.startsWith("content://")) {
            StorageExporter.stagingDir.mkdirs()
            return StorageExporter.stagingDir
        }
        val dir = if (configured.isEmpty()) File(AppConfig.defaultDownloadDir) else File(configured)
        dir.mkdirs()
        return dir
    }

    private fun uniqueName(dest: File, raw: String): String {
        val name = raw.replace(Regex("[<>:\"/\\\\|?*]"), "_").ifEmpty { "download" }
        if (!File(dest, name).exists()) return name
        val stem = name.substringBeforeLast('.', name)
        val suffix = if (name.contains('.')) name.substring(name.lastIndexOf('.')) else ""
        for (i in 1 until 1000) {
            val cand = "$stem ($i)$suffix"
            if (!File(dest, cand).exists()) return cand
        }
        return "$stem-${Net.jobId()}${suffix}"
    }

    private fun spawn(job: DownloadJob) {
        job.cancelRequested = false
        job.pauseRequested = false
        val t = Thread({ guarded(job) }, "avd-job-${job.id}")
        t.isDaemon = true
        job.thread = t
        t.start()
    }

    private fun guarded(job: DownloadJob) {
        val sem = semaphore()
        sem.acquire()
        try {
            job.status = "downloading"
            publish(job)
            try {
                when (job.kind) {
                    "hls" -> runHls(job)
                    "ytdlp" -> runYtdlp(job)
                    "sim" -> runSim(job)
                    else -> runHttp(job)
                }
            } catch (e: CancelledException) {
                job.status = "cancelled"
                if (job.dropWhenDone) {
                    remove(job.id)
                    return
                }
            } catch (e: Exception) {
                val networkFail = e is UnknownHostException || e is SocketException ||
                    e is SocketTimeoutException || e is java.io.IOException
                if (job.kind == "http" && networkFail && Config.settings().getBool("simulate_on_network_error", true)) {
                    job.kind = "sim"
                    job.meta["simulated"] = true
                    job.meta["simulated_reason"] = (e.message ?: "").take(160)
                    job.done = 0; job.total = 0
                    try {
                        runSim(job)
                    } catch (_: Exception) {
                    }
                } else {
                    if (job.status != "cancelled") job.status = "error"
                    job.error = (e.message ?: e.javaClass.simpleName).take(300)
                }
            }
            if (job.status == "downloading") {
                job.status = "done"
                job.finishedAt = System.currentTimeMillis()
                job.speed = 0.0
                job.eta = 0.0
                // reconcile with the REAL bytes on disk
                runCatching {
                    val f = File(job.filepath)
                    if (f.exists() && job.unit == "bytes") {
                        job.done = f.length()
                        job.total = f.length()
                    }
                }
                exportIfNeeded(job)
            }
            publish(job)
        } finally {
            runCatching { sem.release() }
        }
    }

    private fun exportIfNeeded(job: DownloadJob) {
        val configured = Config.settings().getString("download_dir").trim()
        if (!configured.startsWith("content://")) return
        val exporter = StorageExporter.exporter ?: return
        val f = File(job.filepath)
        if (!f.exists()) return
        try {
            if (exporter(f, configured)) {
                job.meta["exported_to"] = configured
            } else {
                job.meta["export_failed"] = true
                job.error = "Saved to the app's own folder — the chosen folder could not be written. " +
                    "The file is at ${job.filepath}"
            }
        } catch (e: Exception) {
            job.meta["export_failed"] = true
            job.error = "Export failed: ${(e.message ?: "").take(160)}"
        }
    }

    fun pause(jobId: String): Boolean {
        val job = lock.withLock { jobs[jobId] } ?: return false
        if (job.status !in listOf("downloading", "queued")) return false
        job.pauseRequested = true
        return true
    }

    fun resume(jobId: String): Boolean {
        val job = lock.withLock { jobs[jobId] } ?: return false
        if (job.status != "paused") return false
        job.resumeOffset = if (job.kind == "http") job.done else 0
        job.pauseRequested = false
        return true
    }

    fun cancel(jobId: String): Boolean {
        val job = lock.withLock { jobs[jobId] } ?: return false
        if (job.status in setOf("done", "error", "cancelled")) {
            remove(jobId)
            return true
        }
        job.cancelRequested = true
        job.process?.let { runCatching { it.destroy() } }
        job.thread?.let { runCatching { it.interrupt() } }
        job.status = "cancelled"
        publish(job)
        if (job.thread == null || !job.thread!!.isAlive) remove(jobId) else job.dropWhenDone = true
        return true
    }

    fun retry(jobId: String): Boolean {
        val job = lock.withLock { jobs[jobId] } ?: return false
        if (job.status != "error" && job.status != "cancelled") return false
        job.status = "queued"
        job.error = ""
        job.done = 0
        job.progressPct = 0.0
        job.resumeOffset = 0
        spawn(job)
        publish(job)
        return true
    }

    fun remove(jobId: String): Boolean {
        val job = lock.withLock { jobs.remove(jobId) } ?: return false
        order.remove(jobId)
        job.removed = true
        job.cancelRequested = true
        job.process?.let { runCatching { it.destroy() } }
        job.thread?.let { runCatching { it.interrupt() } }
        emitEvent("jobs.list", linkedMapOf("jobs" to listJobs()))
        return true
    }

    fun clearFinished(): Int {
        val terminal = lock.withLock { order.filter { jobs[it]?.status in setOf("done", "error", "cancelled") } }
        for (id in terminal) remove(id)
        return terminal.size
    }

    fun listJobs(): List<MutableMap<String, Any?>> = lock.withLock {
        order.reversed().mapNotNull { jobs[it]?.toDict() }
    }

    fun activeCount(): Int = lock.withLock {
        order.count { jobs[it]?.status in setOf("downloading", "queued", "paused") }
    }

    // ── worker: plain HTTP download with Range resume ─────────────────────
    private class CancelledException : RuntimeException("cancelled")
    private class NeedsYtdlp(msg: String) : RuntimeException(msg)

    private fun runHttp(job: DownloadJob) {
        try {
            httpStream(job)
        } catch (e: NeedsYtdlp) {
            if (job.meta["_ytdlp_fallback"].bool()) {
                if (Ytdlp.available()) {
                    throw RuntimeException("${e.message} — the CDN refuses non-browser clients")
                }
                throw RuntimeException(
                    "${e.message} — this CDN refuses plain HTTP clients and yt-dlp is not installed " +
                        "on this device to retry with a browser fingerprint.",
                )
            }
            if (!Ytdlp.available()) {
                throw RuntimeException(
                    "${e.message} — the CDN refuses non-browser clients. Install yt-dlp " +
                        "(see Settings → System) so the app can retry with its headers and Referer.",
                )
            }
            job.meta["_ytdlp_fallback"] = true
            if (job.meta["format"].str().isEmpty()) job.meta["format"] = "bv*+ba/b"
            job.kind = "ytdlp"
            ytdlpThread(job)
        }
    }

    private fun httpStream(job: DownloadJob) {
        val dest = File(job.filepath)
        var offset = if (dest.exists()) job.resumeOffset else 0
        val headers = Net.browserHeaders(job.meta["referer"].str())
        headers["Accept"] = "*/*"
        headers["Accept-Encoding"] = "identity"
        headers["Sec-Fetch-Dest"] = "video"
        headers["Sec-Fetch-Mode"] = "no-cors"
        (job.meta["headers"].asMap() ?: LinkedHashMap()).forEach { (k, v) ->
            if (v.str().isNotEmpty()) headers[k] = v.str()
        }
        if (offset > 0) headers["Range"] = "bytes=$offset-"

        var mode = if (offset > 0) "ab" else "wb"
        var recent = 0L
        var lastEmit = System.currentTimeMillis()
        val proxy = Net.proxy()
        var current = job.url
        var hops = 0
        while (hops < 8) {
            val conn = if (proxy != null) java.net.URL(current).openConnection(proxy) as HttpURLConnection
            else java.net.URL(current).openConnection() as HttpURLConnection
            if (conn is javax.net.ssl.HttpsURLConnection) {
                runCatching {
                    val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                        override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
                        override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
                        override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
                    })
                    val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
                    ctx.init(null, trustAll, java.security.SecureRandom())
                    conn.sslSocketFactory = ctx.socketFactory
                    conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                }
            }
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            try {
                val status = conn.responseCode
                val loc = conn.getHeaderField("Location")
                if (status in 301..308 && !loc.isNullOrEmpty()) {
                    current = UrlKit.join(current, loc)
                    hops++
                    continue
                }
                if (status == 416 && dest.exists()) {
                    job.done = dest.length()
                    job.total = dest.length()
                    job.progressPct = 100.0
                    return
                }
                if (status in listOf(401, 403, 406, 429) && !job.meta["_ytdlp_fallback"].bool()) {
                    throw NeedsYtdlp("CDN answered HTTP $status")
                }
                if (status >= 400) throw RuntimeException("HTTP $status from the server")
                val cr = conn.getHeaderField("Content-Range") ?: ""
                val cl = conn.getHeaderField("Content-Length")
                if (cr.contains("/")) {
                    job.total = cr.substringAfterLast('/').toLongOrNull() ?: 0
                } else if (!cl.isNullOrEmpty()) {
                    if (status == 206) {
                        job.total = (cl.toLongOrNull() ?: 0) + offset
                    } else {
                        offset = 0; mode = "wb"; job.done = 0
                        job.total = cl.toLongOrNull() ?: 0
                    }
                }
                job.done = if (status == 206) offset else 0

                FileOutputStream(dest, mode == "ab").use { out ->
                    val input = conn.inputStream
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        checkFlags(job)
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        job.done += n
                        recent += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 250) {
                            val dt = maxOf(0.001, (now - lastEmit) / 1000.0)
                            job.setProgress(job.done, job.total, recent / dt)
                            recent = 0
                            lastEmit = now
                            publish(job)
                        }
                    }
                }
                job.setProgress(job.done, if (job.total > 0) job.total else job.done, null)
                if (job.total == 0L) job.total = job.done
                job.progressPct = 100.0
                publish(job)
                return
            } finally {
                runCatching { conn.disconnect() }
            }
        }
        throw RuntimeException("Too many redirects")
    }

    // ── worker: HLS/DASH capture ──────────────────────────────────────────
    private fun runHls(job: DownloadJob) {
        if (Ffmpeg.find().available) {
            Ffmpeg.captureStream(job, ::checkFlags, ::publish)
        } else {
            HlsCapture.capture(job, ::checkFlags, ::publish)
        }
    }

    // ── worker: yt-dlp (system binary, exactly like the desktop's CLI path) ─
    private fun runYtdlp(job: DownloadJob) {
        ytdlpThread(job)
    }

    private fun ytdlpThread(job: DownloadJob) {
        if (!YtCli.available()) {
            throw RuntimeException(
                "yt-dlp is not installed on this device, so platform links (YouTube & friends) " +
                    "cannot be downloaded. See Settings → System for how to add it.",
            )
        }
        val parent = File(job.filepath).parentFile ?: File(".")
        val stem = (job.filename.substringBeforeLast('.', job.filename).take(120)).ifEmpty { "download" }
        val container = Config.settings().getString("default_container", "mp4")
        val client = job.meta["extractor_args"].asMap()?.get("youtube").asMap()?.get("player_client").list()
            ?.map { it.str() }?.filter { it.isNotEmpty() } ?: emptyList()
        val baseArgs = YtCli.downloadCmd(
            url = job.url, outDir = parent.absolutePath, outStem = stem,
            fmt = job.meta["format"].str().ifEmpty { "bv*+ba/b" },
            container = container,
            referer = job.meta["referer"].str().trim(),
            playerClient = client,
            playlist = job.meta["playlist"].bool(),
        )

        fun pump(args: List<String>): Pair<Int, List<String>> {
            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            job.process = proc
            val tail = ArrayList<String>()
            val last = longArrayOf(0L)
            proc.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val raw = reader.readLine() ?: break
                    val line = raw.trim()
                    if (line.startsWith("AVDPROG ")) {
                        val parts = line.split(" ").drop(1)
                        fun n(i: Int): Double = parts.getOrNull(i)?.takeIf { it != "NA" && it.isNotEmpty() }
                            ?.toDoubleOrNull() ?: 0.0
                        val pct = parts.getOrNull(0)?.replace("%", "")?.trim()?.toDoubleOrNull() ?: 0.0
                        val done = n(1)
                        var total = n(2)
                        if (total == 0.0) total = n(3)
                        val speed = n(4)
                        if (total > 0) job.setProgress(done.toLong(), total.toLong(), speed.takeIf { it > 0 })
                        if (pct > 0) job.progressPct = pct
                        if (speed > 0) job.speed = speed
                        val now = System.currentTimeMillis()
                        if (now - last[0] >= 250) {
                            last[0] = now
                            publish(job)
                        }
                    } else if (line.isNotEmpty()) {
                        tail.add(line)
                        while (tail.size > 8) tail.removeAt(0)
                    }
                    if (job.cancelRequested) {
                        runCatching { proc.destroy() }
                        break
                    }
                }
            }
            val rc = try {
                proc.waitFor()
            } catch (_: InterruptedException) {
                runCatching { proc.destroy() }
                -1
            }
            runCatching { proc.destroy() }
            job.process = null
            return rc to tail
        }

        var (rc, tail) = pump(baseArgs)
        if (job.cancelRequested) throw CancelledException()
        if (rc != 0) {
            var msg = YtCli.friendly(tail.takeLast(3).joinToString(" · ")).ifEmpty { "exit $rc" }
            // 1) cookie wall → same run, anonymous
            if (YtCli.cookieFlagFailure(msg) && Cookies.ytdlpFlags().isNotEmpty()) {
                Cookies.markBrowserFailure(msg)
                val (rc2, tail2) = pump(Cookies.stripFlags(baseArgs))
                if (rc2 == 0 || settleYtdlpFile(job, parent, stem)) {
                    settleYtdlpFile(job, parent, stem)
                    return
                }
                msg = "$msg · cookieless retry: " +
                    (YtCli.friendly(tail2.takeLast(3).joinToString(" · ")).ifEmpty { "exit $rc2" })
            }
            // 2) bot wall / 403: TLS impersonation is not available on Android —
            //    say so instead of pretending a retry happened.
            if (YtCli.looksBlocked(msg) && !YtCli.impersonationAvailable()) {
                msg = "$msg · this Android build cannot retry with a browser TLS fingerprint"
            }
            if (settleYtdlpFile(job, parent, stem)) return
            throw RuntimeException("[system yt-dlp] ${msg.take(320)}")
        }
        settleYtdlpFile(job, parent, stem)
    }

    /** Point the job at the REAL file yt-dlp left on disk, then sync size. */
    private fun settleYtdlpFile(job: DownloadJob, parent: File, stem: String): Boolean {
        return try {
            val finals = (parent.listFiles() ?: emptyArray())
                .filter { it.name.startsWith("$stem.") && it.extension !in listOf("part", "ytdl") && it.isFile }
            if (finals.isNotEmpty() && finals.maxOf { it.length() } > 1024) {
                val real = finals.maxByOrNull { it.length() }!!
                job.filename = real.name
                job.filepath = real.absolutePath
                val size = real.length()
                if (job.unit == "bytes" && size > 0) {
                    job.done = size
                    job.total = size
                    job.progressPct = 100.0
                }
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    // ── worker: simulated transfer (offline & blocked networks) ───────────
    private fun runSim(job: DownloadJob) {
        val rng = java.util.Random(Math.abs(job.url.hashCode()).toLong() % 10_000)
        if (job.total == 0L) {
            job.unit = "bytes"
            job.total = (job.meta["simulated_size"].num().takeIf { it > 0 }
                ?: listOf(90, 140, 260, 420)[rng.nextInt(4)].toDouble()) .toLong() * 1024 * 1024
            job.done = 0
        }
        val baseSpeed = (job.meta["simulated_speed"].num().takeIf { it > 0 }
            ?: (6.5 + rng.nextDouble() * 23.5) * 1024 * 1024)
        while (job.done < job.total) {
            checkFlags(job)
            val tick = 0.28
            val step = maxOf(4096L, (baseSpeed * tick * (0.55 + rng.nextDouble() * 1.05)).toLong())
            job.done = minOf(job.total, job.done + step)
            job.setProgress(job.done, job.total, step / tick)
            publish(job)
            Thread.sleep((tick * 1000).toLong())
        }
        val dest = File(job.filepath)
        dest.parentFile?.mkdirs()
        dest.writeText(
            Json.write(linkedMapOf(
                "webfinder" to "simulated transfer",
                "url" to job.url,
                "note" to "The remote host was unreachable from this environment — " +
                    "All Video Downloader finished the job in simulation mode.",
            )),
        )
        job.progressPct = 100.0
        publish(job)
    }

    /** Cooperative pause/cancel — pause parks the worker until resumed. */
    fun checkFlags(job: DownloadJob) {
        if (job.cancelRequested) throw CancelledException()
        if (job.pauseRequested) {
            job.status = "paused"
            job.speed = 0.0
            job.eta = 0.0
            publish(job)
            while (job.pauseRequested) {
                if (job.cancelRequested) {
                    job.status = "cancelled"
                    publish(job)
                    throw CancelledException()
                }
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    throw CancelledException()
                }
            }
            job.status = "downloading"
            if (job.kind != "http") job.resumeOffset = job.done
            publish(job)
        }
    }

    companion object {
        val instance = DownloadManager()
    }
}

/** Mirrors `manager` in the Python module. */
val manager: DownloadManager get() = DownloadManager.instance
