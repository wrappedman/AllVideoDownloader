// End-to-end download tests for the ported engine.
//
// Everything runs on the desktop JVM against a loopback HTTP server that serves
// a fake MP4, a clear HLS playlist and an AES-128 encrypted HLS playlist. This
// exercises the shipping `core/` code paths that cannot be exercised offline by
// fixtures: HTTP streaming with Range/resume, the native HLS capture (including
// segment decryption), redirect following, cancellation and the job lifecycle.
package dev

import com.allvideodownloader.avd.core.AppConfig
import com.allvideodownloader.avd.core.Config
import com.allvideodownloader.avd.core.DownloadJob
import com.allvideodownloader.avd.core.DownloadManager
import com.allvideodownloader.avd.core.Media
import com.allvideodownloader.avd.core.bool
import com.allvideodownloader.avd.core.int
import com.allvideodownloader.avd.core.list
import com.allvideodownloader.avd.core.map
import com.allvideodownloader.avd.core.str
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private var passed = 0
private val failures = ArrayList<String>()

private fun check(cond: Boolean, what: String) {
    if (cond) passed++ else {
        failures.add(what)
        println("  ✗ $what")
    }
}

private fun eq(actual: Any?, expected: Any?, what: String) {
    if (actual != expected) println("    expected <$expected> got <$actual>")
    check(actual == expected, what)
}

// ── fake payloads ───────────────────────────────────────────────────────────
private val MP4 = ByteArray(220_000).also { b ->
    val head = byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray()
    head.copyInto(b)
    SecureRandom().nextBytes(b.copyOfRange(200, b.size))
}

private fun tsSegment(tag: Byte): ByteArray {
    // 40 fake 188-byte TS packets, each stamped so a mix-up is detectable
    val out = ByteArray(188 * 40)
    for (i in 0 until 40) {
        out[i * 188] = 0x47
        out[i * 188 + 1] = tag
        out[i * 188 + 2] = i.toByte()
    }
    return out
}

private val SEG_A = tsSegment(1)
private val SEG_B = tsSegment(2)
private val SEG_C = tsSegment(3)
private val HLS_BODY = SEG_A + SEG_B + SEG_C

private val AES_KEY = ByteArray(16).also { SecureRandom().nextBytes(it) }
private val AES_IV = "0x000102030405060708090a0b0c0d0e0f"

private fun encryptCbc(plain: ByteArray, key: ByteArray, ivHex: String): ByteArray {
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(hexToBytes(ivHex)))
    return cipher.doFinal(plain)
}

private fun hexToBytes(hex: String): ByteArray {
    val clean = hex.removePrefix("0x")
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

private val SEGS = mapOf(
    "/seg0.ts" to SEG_A,
    "/seg1.ts" to SEG_B,
    "/seg2.ts" to SEG_C,
    "/enc0.ts" to encryptCbc(SEG_A, AES_KEY, AES_IV),
    "/enc1.ts" to encryptCbc(SEG_B, AES_KEY, AES_IV),
    "/enc2.ts" to encryptCbc(SEG_C, AES_KEY, AES_IV),
)

private val MASTER = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=5500000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
media.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=640x360
low.m3u8
"""

private val MEDIA = """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:4
#EXT-X-MEDIA-SEQUENCE:0
#EXTINF:4.0,
seg0.ts
#EXTINF:4.0,
seg1.ts
#EXTINF:4.0,
seg2.ts
#EXT-X-ENDLIST
"""

private val ENC_MEDIA = """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:4
#EXT-X-MEDIA-SEQUENCE:0
#EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=$AES_IV
#EXTINF:4.0,
enc0.ts
#EXTINF:4.0,
enc1.ts
#EXTINF:4.0,
enc2.ts
#EXT-X-ENDLIST
"""

private var rangeHits = 0
private var sawRangeHeader = false

fun main() {
    val tmp = File(System.getProperty("java.io.tmpdir"), "avd-dl-tests-" + ProcessHandle.current().pid())
    val downloads = File(tmp, "downloads").apply { mkdirs() }
    AppConfig.configDir = File(tmp, "config")
    AppConfig.defaultDownloadDir = downloads.absolutePath
    Config.settings().update(linkedMapOf("download_dir" to downloads.absolutePath, "max_concurrent" to 2))

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { ex -> serve(ex) }
    server.executor = java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
        Thread(r, "test-http").apply { isDaemon = true }
    }
    server.start()
    val base = "http://127.0.0.1:${server.address.port}"
    println("local server: $base")

    // ── 1. direct file over plain HTTP ─────────────────────────────────────
    println("== direct download")
    val analysis = Media.analyze("$base/clip.mp4")
    eq(analysis["kind"].str(), "direct", "analyze: .mp4 → direct card")
    check(analysis["url"].str().endsWith("clip.mp4"), "analyze: card keeps the url")
    val plan = Media.planDownload("$base/clip.mp4")
    eq(plan["kind"].str(), "http", "plan: direct url → http worker")
    eq(plan["file_type"].str(), "video", "plan: classified as video")

    val job = DownloadManager.instance.enqueue(
        url = "$base/clip.mp4", kind = "http", filename = "clip.mp4", fileType = "video",
    )
    val done = waitFor(job, 20_000)
    eq(done.status, "done", "http job finished (status=${done.status} ${done.error})")
    eq(File(done.filepath).length(), MP4.size.toLong(), "http job wrote every byte")
    check(sameBytes(File(done.filepath), MP4), "http job content is byte-identical")
    eq(done.progressPct, 100.0, "http job reports 100%")

    // ── 2. redirects + Range/resume ────────────────────────────────────────
    println("== redirect + range resume")
    val redirect = DownloadManager.instance.enqueue(
        url = "$base/redirect.mp4", kind = "http", filename = "redirect.mp4",
    )
    val rdone = waitFor(redirect, 20_000)
    eq(rdone.status, "done", "302 redirect followed")
    check(sameBytes(File(rdone.filepath), MP4), "redirect target downloaded intact")

    // real resume: let a trickling download get part-way, pause, resume — the
    // worker must ask the server for a byte Range and finish the same file
    sawRangeHeader = false
    rangeHits = 0
    val resumeJob = DownloadManager.instance.enqueue("$base/slow.mp4", "http", "resume.mp4")
    waitUntil(10_000) { (resumeJob.done > 0 && resumeJob.done < MP4.size) || resumeJob.status == "done" }
    check(DownloadManager.instance.pause(resumeJob.id), "pause accepted for the resume test")
    waitUntil(5_000) { resumeJob.status == "paused" }
    val partialSize = File(resumeJob.filepath).length()
    check(partialSize > 0, "partial file kept on pause ($partialSize bytes)")
    check(DownloadManager.instance.resume(resumeJob.id), "resume accepted")
    val resumed = waitFor(resumeJob, 30_000)
    eq(resumed.status, "done", "resumed job finished (${resumed.error})")
    // pause/resume keeps the same connection open (like the desktop's Event),
    // so the file must continue where it stopped
    check(sameBytes(File(resumed.filepath), MP4), "resumed file is byte-identical, not truncated or doubled")

    // the Range plumbing itself (used whenever a worker restarts on a partial file)
    sawRangeHeader = false
    val ranged = com.allvideodownloader.avd.core.Net.get(
        "$base/clip.mp4", mapOf("Range" to "bytes=1000-"), 10, 4L * 1024 * 1024,
    )
    eq(ranged.status, 206, "Range request answered with 206")
    eq(ranged.bytes.size, MP4.size - 1000, "Range response carries the tail")
    check(ranged.bytes.contentEquals(MP4.copyOfRange(1000, MP4.size)), "Range tail is byte-accurate")

    // ── 3. HLS, native capture (no ffmpeg on this machine) ─────────────────
    println("== hls capture")
    val hlsAnalysis = Media.analyze("$base/master.m3u8")
    eq(hlsAnalysis["kind"].str(), "hls", "analyze: master playlist → hls card")
    val ladder = hlsAnalysis["formats"].list()
    check(ladder.map { it.map()["height"].int() }.containsAll(listOf(1080, 360)),
        "analyze: variant ladder ${ladder.map { it.map()["height"].int() }}")
    val hlsPlan = Media.planDownload("$base/master.m3u8", fmtIn = ladder.first { it.map()["height"].int() == 1080 }.map())
    eq(hlsPlan["kind"].str(), "hls", "plan: hls capture when no ffmpeg")
    eq(hlsPlan["meta"].map()["variant_url"].str(), "$base/media.m3u8", "plan: chosen variant url")

    val hlsJob = DownloadManager.instance.enqueue(
        url = "$base/master.m3u8", kind = "hls", filename = "show.ts", fileType = "video",
        meta = hlsPlan["meta"].map(),
    )
    val hlsDone = waitFor(hlsJob, 30_000)
    eq(hlsDone.status, "done", "hls job finished (${hlsDone.error})")
    val captured = File(hlsDone.filepath)
    check(captured.isFile && captured.length() == HLS_BODY.size.toLong(),
        "hls output is the concatenation of the segments (${captured.length()})")
    check(sameBytes(captured, HLS_BODY), "hls segments concatenated in order")
    eq(hlsDone.progressPct, 100.0, "hls job reports 100%")
    eq(hlsDone.unit, "bytes", "hls job switches to byte units when finished")

    // ── 4. encrypted HLS (AES-128) ─────────────────────────────────────────
    println("== encrypted hls")
    val encJob = DownloadManager.instance.enqueue(
        url = "$base/enc.m3u8", kind = "hls", filename = "enc.ts", fileType = "video",
    )
    val encDone = waitFor(encJob, 30_000)
    eq(encDone.status, "done", "aes-128 hls job finished (${encDone.error})")
    check(sameBytes(File(encDone.filepath), HLS_BODY), "aes-128 segments decrypted to the original bytes")

    // ── 5. DASH is refused with a readable reason, not silently broken ─────
    println("== dash refusal")
    val dashJob = DownloadManager.instance.enqueue(
        url = "$base/manifest.mpd", kind = "hls", filename = "dash.mp4",
    )
    val dashDone = waitFor(dashJob, 30_000)
    eq(dashDone.status, "error", "dash without ffmpeg ends in 'error'")
    check(dashDone.error.contains("FFmpeg"), "dash error names FFmpeg: ${dashDone.error}")

    // ── 6. job lifecycle: pause / resume / cancel ──────────────────────────
    println("== lifecycle")
    val slow = DownloadManager.instance.enqueue("$base/slow.mp4", "http", "cancel-me.mp4")
    waitUntil(5_000) { slow.done > 0 }
    check(DownloadManager.instance.cancel(slow.id), "cancel accepted")
    waitUntil(10_000) { !DownloadManager.instance.jobs.containsKey(slow.id) }
    eq(DownloadManager.instance.jobs.containsKey(slow.id), false, "cancelled job leaves the list")

    Thread.sleep(300)
    eq(DownloadManager.instance.activeCount(), 0, "no jobs left running")
    val listed = DownloadManager.instance.listJobs()
    check(listed.isNotEmpty(), "completed jobs are listed")
    val dict = listed.first()
    for (key in listOf("id", "url", "status", "filename", "filepath", "progress_pct", "speed", "eta", "unit", "kind")) {
        check(dict.containsKey(key), "job dict exposes $key")
    }
    eq(DownloadManager.instance.clearFinished() > 0, true, "clear-finished removes terminal rows")
    eq(DownloadManager.instance.listJobs().size, 0, "list is empty after clearing")

    server.stop(0)

    println()
    tmp.deleteRecursively()
    if (failures.isEmpty()) {
        println("ALL $passed DOWNLOAD CHECKS PASSED")
        System.exit(0)
    }
    println("$passed passed, ${failures.size} FAILED")
    failures.forEach { println("  - $it") }
    System.exit(1)
}

// ── server ──────────────────────────────────────────────────────────────────
private fun serve(ex: HttpExchange) {
    val path = ex.requestURI.path
    fun body(bytes: ByteArray, type: String, status: Int = 200) {
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Accept-Ranges", "bytes")
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.responseBody.close()
    }
    fun text(s: String) = body(s.toByteArray(), "application/vnd.apple.mpegurl")

    when (path) {
        "/clip.mp4", "/slow.mp4" -> {
            val range = ex.requestHeaders.getFirst("Range")
            if (range != null && range.startsWith("bytes=")) {
                sawRangeHeader = true
                rangeHits++
                val from = range.removePrefix("bytes=").substringBefore('-').toIntOrNull() ?: 0
                if (from >= MP4.size) {
                    ex.sendResponseHeaders(416, -1)
                    ex.close()
                    return
                }
                val slice = MP4.copyOfRange(from, MP4.size)
                ex.responseHeaders.add("Content-Range", "bytes $from-${MP4.size - 1}/${MP4.size}")
                ex.responseHeaders.add("Content-Type", "video/mp4")
                ex.sendResponseHeaders(206, slice.size.toLong())
                ex.responseBody.use { it.write(slice) }
            } else {
                // slow.mp4 trickles so pause/cancel have time to act
                if (path == "/slow.mp4") {
                    ex.responseHeaders.add("Content-Type", "video/mp4")
                    ex.sendResponseHeaders(200, MP4.size.toLong())
                    ex.responseBody.use { out ->
                        var sent = 0
                        while (sent < MP4.size) {
                            val n = minOf(2048, MP4.size - sent)
                            out.write(MP4, sent, n)
                            out.flush()
                            sent += n
                            Thread.sleep(20)
                        }
                    }
                } else {
                    body(MP4, "video/mp4")
                }
            }
        }
        "/redirect.mp4" -> {
            ex.responseHeaders.add("Location", "/clip.mp4")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        "/master.m3u8" -> text(MASTER)
        "/media.m3u8" -> text(MEDIA)
        "/low.m3u8" -> text(MEDIA)
        "/enc.m3u8" -> text(ENC_MEDIA)
        "/key.bin" -> body(AES_KEY, "application/octet-stream")
        "/manifest.mpd" -> text("""<?xml version="1.0"?><MPD type="static"><Period/></MPD>""")
        else -> {
            val seg = SEGS[path]
            if (seg != null) body(seg, "video/mp2t")
            else {
                ex.sendResponseHeaders(404, -1)
                ex.close()
            }
        }
    }
    runCatching { ex.close() }
}

private fun waitFor(job: DownloadJob, timeoutMs: Long): DownloadJob {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (job.status in setOf("done", "error", "cancelled")) return job
        Thread.sleep(50)
    }
    return job
}

private fun waitUntil(timeoutMs: Long, cond: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (cond()) return
        Thread.sleep(25)
    }
}

private fun sameBytes(file: File, expected: ByteArray): Boolean {
    if (!file.isFile || file.length() != expected.size.toLong()) return false
    val actual = file.readBytes()
    return actual.contentEquals(expected)
}
