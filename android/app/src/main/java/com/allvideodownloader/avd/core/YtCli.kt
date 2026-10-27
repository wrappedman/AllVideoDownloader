package com.allvideodownloader.avd.core

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Android port of `webfinder/core/ytcli.py` — the *system* yt-dlp bridge.
 *
 * The desktop app prefers driving a real `yt-dlp` binary over its bundled pip
 * module whenever that binary is fresher. On Android that is not an alternative
 * but the only route (a Python runtime cannot be embedded here without an NDK
 * toolchain), so this is the yt-dlp engine of the Android build:
 *
 * ```
 * $APP_FILES/yt-dlp            (push one with: adb push yt-dlp …/files/)
 * $APP_FILES/bin/yt-dlp
 * $APP_FILES/yt-dlp.py + python3    (module form, when a python exists)
 * AVD_YTDLP_BIN (env override, dev builds)
 * ```
 *
 * When none is present [available] reports `false`, `/api/meta` says so, and the
 * analyzer degrades to the page/player-config engine exactly as the desktop does
 * when yt-dlp is not installed at all. Nothing is faked.
 */
object YtCli {

    private val ERR_MARKERS = Pattern.compile("could not copy|cookie database|browser cookies", Pattern.CASE_INSENSITIVE)
    private val BLOCK_MARKERS = Pattern.compile(
        "\\b403\\b|\\b429\\b|forbidden|cloudflare|just a moment|attention required" +
            "|access denied|captcha|ddos|unusual traffic|blocked by|request blocked" +
            "|sign in to confirm",
        Pattern.CASE_INSENSITIVE,
    )
    private val ANSI_RE = Pattern.compile("\u001b\\[[0-9;]*m|\\[[0-9;]*m")
    private val ERR_PREFIX_RE = Pattern.compile("^(?:\\s*ERROR:\\s*)+", Pattern.CASE_INSENSITIVE)

    /** Strip ANSI colors and yt-dlp's habit of repeating 'ERROR: ERROR:'. */
    fun friendly(msg: String?): String {
        if (msg.isNullOrEmpty()) return ""
        var out = ANSI_RE.matcher(msg).replaceAll("")
        var prev: String? = null
        while (prev != out) {
            prev = out
            out = ERR_PREFIX_RE.matcher(out.trim()).replaceFirst("").trim()
        }
        out = Regex("(?:\\[Errno \\d+\\]\\s*)+").replace(out, "")
        return out.replace(Regex("\\s+"), " ").trim()
    }

    private fun vparse(v: String): List<Int> {
        val nums = Regex("\\d+").findAll(v).map { it.value.toInt() }.take(4).toMutableList()
        while (nums.size < 4) nums.add(0)
        return nums
    }

    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (i in a.indices) {
            if (a[i] != b.getOrElse(i) { 0 }) return a[i] - b.getOrElse(i) { 0 }
        }
        return 0
    }

    @Volatile
    private var cached: MutableMap<String, Any?>? = null

    /** Which engine is live: a real binary, or nothing. */
    fun detect(): MutableMap<String, Any?> {
        cached?.let { return it }
        val exe = locate()
        var cliVersion = ""
        if (exe.isNotEmpty()) {
            cliVersion = try {
                val p = ProcessBuilder(exe, "--version").redirectErrorStream(true).start()
                p.waitFor(15, TimeUnit.SECONDS)
                p.inputStream.bufferedReader().readText().trim()
            } catch (_: Exception) {
                ""
            }
        }
        val result = linkedMapOf<String, Any?>(
            "exe" to exe,
            "module_version" to "",
            "cli_version" to cliVersion,
            "use_cli" to exe.isNotEmpty(),
            "version" to cliVersion.ifEmpty { null },
            "label" to if (exe.isNotEmpty()) "system CLI $cliVersion" else "not installed",
        )
        cached = result
        return result
    }

    fun detectFresh(): MutableMap<String, Any?> {
        cached = null
        return detect()
    }

    fun useCli(): Boolean = detect()["use_cli"].bool()

    fun available(): Boolean = useCli()

    fun versionOrNull(): String? = (detect()["version"] as? String)?.takeIf { it.isNotEmpty() }

    private fun locate(): String {
        val env = System.getenv("AVD_YTDLP_BIN")
        if (!env.isNullOrEmpty() && File(env).canExecute()) return env
        val candidates = listOf(
            File(AppConfig.configDir.parentFile, "yt-dlp"),
            File(AppConfig.configDir.parentFile, "bin/yt-dlp"),
        )
        for (f in candidates) if (f.isFile && f.canExecute()) return f.absolutePath
        for (dir in (System.getenv("PATH") ?: "").split(":")) {
            val f = File(dir, "yt-dlp")
            if (f.isFile && f.canExecute()) return f.absolutePath
        }
        return ""
    }

    fun impersonationAvailable(): Boolean = false   // curl_cffi is a desktop dependency
    fun impersonateFlags(): List<String> = emptyList()

    fun looksBlocked(msg: String?): Boolean = BLOCK_MARKERS.matcher(msg ?: "").find()

    fun cookieFlagFailure(msg: String?): Boolean = ERR_MARKERS.matcher(msg ?: "").find()

    fun commonFlags(referer: String = "", playerClient: List<String> = emptyList()): MutableList<String> {
        val flags = mutableListOf(
            "--no-warnings", "--no-check-certificates",
            "--socket-timeout", "30", "--retries", "3",
        )
        val px = Config.settings().getString("proxy").trim()
        if (px.isNotEmpty()) {
            flags.add("--proxy"); flags.add(px)
        }
        if (referer.isNotEmpty()) {
            flags.add("--referer"); flags.add(referer)
        }
        if (playerClient.isNotEmpty()) {
            flags.add("--extractor-args"); flags.add("youtube:player_client=" + playerClient.joinToString(","))
        }
        return flags
    }

    /**
     * `yt-dlp -J` — returns (info, error) with the cookie-failure retry.
     *
     * Runs on the calling thread; callers hand it to a worker thread (the desktop
     * does the same through `asyncio.to_thread`).
     */
    fun extract(
        url: String,
        flat: Boolean = false,
        playlistItems: String? = null,
        noplaylist: Boolean = true,
        referer: String = "",
        playerClient: List<String> = emptyList(),
    ): Pair<MutableMap<String, Any?>?, String> {
        val d = detect()
        val exe = d["exe"].str()
        if (exe.isEmpty()) return null to "yt-dlp is not installed on this device."
        val args = mutableListOf(exe, "-J")
        args.addAll(commonFlags(referer, playerClient))
        if (flat) args.add("--flat-playlist")
        if (!playlistItems.isNullOrEmpty()) {
            args.add("--playlist-items"); args.add(playlistItems)
        }
        if (noplaylist) args.add("--no-playlist")
        val cookies = Cookies.ytdlpFlags()
        var err = "yt-dlp CLI returned nothing"
        for (attempt in 0..1) {
            val cmd = ArrayList(args)
            if (attempt == 0) cmd.addAll(cookies)
            cmd.add(url)
            val (rc, out, errText) = run(cmd, 180)
            if (rc == 0 && out.trim().isNotEmpty()) {
                val parsed = Json.parseOrNull(out)
                if (parsed.asMap() != null) return parsed.asMap() to ""
                return null to "yt-dlp CLI emitted non-JSON output"
            }
            val full = errText.trim()
            val last = full.split("\n").lastOrNull()?.let { friendly(it) }?.take(260)
                ?: "yt-dlp CLI exited $rc"
            err = last
            val cookieFailure = Cookies.isCookieError(last)
            if (cookieFailure) Cookies.markBrowserFailure(full.ifEmpty { last })
            if (attempt == 0 && cookies.isNotEmpty() && cookieFailure) continue
            if (cookieFailure) return null to "$last · ${Cookies.EXPLAIN}"
            return null to last
        }
        return null to err
    }

    data class RunResult(val rc: Int, val out: String, val err: String)

    fun run(cmd: List<String>, timeoutSec: Int = 180): Triple<Int, String, String> {
        return try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(false)
            val p = pb.start()
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { r ->
                        val buf = CharArray(8192)
                        while (true) {
                            val n = r.read(buf)
                            if (n <= 0) break
                            out.append(buf, 0, n)
                        }
                    }
                }
            }
            val tErr = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8)).use { r ->
                        val buf = CharArray(8192)
                        while (true) {
                            val n = r.read(buf)
                            if (n <= 0) break
                            err.append(buf, 0, n)
                        }
                    }
                }
            }
            tOut.start(); tErr.start()
            val finished = p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                runCatching { p.destroyForcibly() }
                return Triple(-1, out.toString(), "yt-dlp CLI timed out ($timeoutSec s)")
            }
            tOut.join(2000); tErr.join(2000)
            Triple(p.exitValue(), out.toString(), err.toString())
        } catch (e: Exception) {
            Triple(-2, "", "yt-dlp CLI could not run: ${e.message}")
        }
    }

    /** Flags for a background download driven by the CLI. */
    fun downloadCmd(
        url: String,
        outDir: String,
        outStem: String,
        fmt: String,
        container: String,
        referer: String = "",
        playerClient: List<String> = emptyList(),
        playlist: Boolean = false,
    ): MutableList<String> {
        val d = detect()
        val args = mutableListOf(
            d["exe"].str(), "--newline",
            "--progress-template", "download:$PROGRESS_TEMPLATE",
            "-f", fmt, "--merge-output-format", container,
            "--concurrent-fragments", "4", "--windows-filenames",
            "-P", outDir, "-o", "$outStem.%(ext)s",
        )
        args.addAll(commonFlags(referer, playerClient))
        if (!playlist) args.add("--no-playlist")
        args.addAll(Cookies.ytdlpFlags())
        args.add(url)
        return args
    }

    const val PROGRESS_TEMPLATE = "AVDPROG %(progress._percent_str)s %(progress.downloaded_bytes)s " +
        "%(progress.total_bytes)s %(progress.total_bytes_estimate)s %(progress.speed)s"

    /** No extractor registry is reachable without the Python module (see class doc). */
    fun findExtractor(url: String): String = ""
}

/**
 * Android port of the yt-dlp half of `webfinder/core/media.py`
 * (`_ytdlp_extract`, `_merge_infos`, `_attempt_extract`) — the same client
 * ladder, the same playlist handling and the same format-id-accurate picker.
 */
object Ytdlp {

    private val QUALITY_ORDER = listOf(4320, 2880, 2160, 1440, 1080, 720, 480, 360, 240, 144)

    fun available(): Boolean = YtCli.available()

    private fun clientLadder(): List<List<String>> = listOf(
        emptyList(),                      // bare CLI behaviour first
        listOf("android", "tv"),          // rescue: web map capped?
        listOf("ios", "mweb"),            // rescue: still capped?
    )

    private fun mergeInfos(infos: List<Pair<List<String>, MutableMap<String, Any?>>>):
        Pair<MutableMap<String, Any?>, List<String>> {
        if (infos.isEmpty()) return LinkedHashMap<String, Any?>() to emptyList()
        val sorted = infos.sortedByDescending { it.second["formats"].list().size }
        val win = sorted.first().first
        val seen = HashSet<String>()
        val merged = ArrayList<Any?>()
        for ((_, cand) in sorted) {
            for (f in cand["formats"].list()) {
                val fid = f.asMap()?.get("format_id").str() ?: ""
                if (seen.add(fid)) merged.add(f)
            }
        }
        val info = LinkedHashMap(sorted.first().second)
        info["formats"] = merged
        return info to win
    }

    fun extract(url: String, referrer: String = ""): Pair<MutableMap<String, Any?>?, String> {
        if (!YtCli.available()) return null to "yt-dlp is not installed on this device."
        val infos = ArrayList<Pair<List<String>, MutableMap<String, Any?>>>()
        val errors = ArrayList<String>()
        for (client in clientLadder()) {
            val (info, err) = YtCli.extract(url, referer = referrer, playerClient = client)
            if (info == null) {
                if (err.isNotEmpty()) errors.add(err.take(220))
                continue
            }
            infos.add(client to info)
            val fmts = info["formats"].list()
            val maxH = fmts.maxOfOrNull { it.asMap()?.get("height").int() ?: 0 } ?: 0
            if (fmts.isNotEmpty() && maxH > 480) break
            if (info["_type"].str() == "playlist") break
        }
        if (infos.isEmpty()) {
            return null to (errors.distinct().joinToString(" · ").ifEmpty { "yt-dlp returned nothing" })
        }
        var (info, win) = mergeInfos(infos)
        var err = ""

        // ── playlist / channel / gallery URLs ────────────────────────────────
        var playlistMeta: MutableMap<String, Any?>? = null
        if (info["_type"].str() == "playlist") {
            val entries = info["entries"].list().mapNotNull { it.asMap() }
            if (entries.isEmpty()) return null to "Extractor returned a playlist with no playable entry."
            playlistMeta = linkedMapOf(
                "total" to (info["playlist_count"].int().takeIf { it > 0 } ?: entries.size),
                "entries" to entries.drop(1).take(24).map {
                    linkedMapOf<String, Any?>(
                        "title" to (it["title"].str().ifEmpty { it["id"].str() }.ifEmpty { "untitled" }).take(160),
                        "url" to (if (it["url"].str().isNotEmpty()) it["url"].str() else it["webpage_url"].str()),
                        "duration" to it["duration"].int(),
                    )
                },
            )
            val firstUrl = entries[0]["url"].str().ifEmpty { entries[0]["webpage_url"].str() }
            if (firstUrl.isEmpty()) return null to "Playlist entries carry no playable URLs."
            val (info2, err2) = YtCli.extract(firstUrl, flat = false, noplaylist = true, referer = referrer)
            if (info2 == null || info2["_type"].str() == "playlist") {
                return null to err2.ifEmpty { "First playlist entry could not be extracted." }
            }
            info = info2
            win = emptyList()
            err = ""
        }

        // ── format ladder with real format IDs ──────────────────────────────
        class Entry(
            val height: Int,
            var label: String = "",
            val exts: MutableSet<String> = LinkedHashSet(),
            var hasVideo: Boolean = false,
            var fps: Int = 0,
            val vidIds: MutableList<Pair<Double, String>> = ArrayList(),
            val muxIds: MutableList<Pair<Double, String>> = ArrayList(),
            val audIds: MutableList<Pair<Double, String>> = ArrayList(),
            var muxed: Boolean = false,
            var abr: Double = 0.0,
        )

        val formats = LinkedHashMap<Int, Entry>()
        val rawFormats = info["formats"].list().mapNotNull { it.asMap() }
        for (f in rawFormats) {
            val vcodec = f["vcodec"].str().ifEmpty { "none" }
            val acodec = f["acodec"].str().ifEmpty { "none" }
            val h = f["height"].int()
            if (vcodec == "none" && acodec == "none") continue
            if (vcodec == "none") {
                val e = formats.getOrPut(0) { Entry(0, "Audio only") }
                e.exts.add(f["ext"].str().ifEmpty { "m4a" })
                e.audIds.add(f["abr"].num() to f["format_id"].str())
                continue
            }
            if (h == 0) continue
            val nearest = QUALITY_ORDER.minByOrNull { Math.abs(it - h) } ?: h
            val badge = if (nearest >= 2160) " · 4K" else if (nearest == 1080) " · FHD" else ""
            val e = formats.getOrPut(nearest) {
                val entry = Entry(nearest, "${nearest}p$badge", hasVideo = true)
                entry.fps = f["fps"].int()
                entry
            }
            e.exts.add(f["ext"].str().ifEmpty { "mp4" })
            val pair = f["tbr"].num() to f["format_id"].str()
            if (acodec != "none") {
                e.muxIds.add(pair)
                e.muxed = true
            } else {
                e.vidIds.add(pair)
            }
        }

        val chosen = ArrayList<MutableMap<String, Any?>>()
        for (q in QUALITY_ORDER) {
            val e = formats[q] ?: continue
            fun idFor(entries: List<Pair<Double, String>>): String? =
                entries.filter { it.second.isNotEmpty() }.maxByOrNull { it.first }?.second

            val bestV = idFor(e.vidIds)
            val bestM = idFor(e.muxIds)
            val audSrc = if (e.audIds.isNotEmpty()) e.audIds else (formats[0]?.audIds ?: emptyList())
            val bestA = idFor(audSrc)
            val generic = "bestvideo[height<=$q]+bestaudio/best[height<=$q]"
            val exact = when {
                bestV != null -> bestV + (if (bestA != null) "+$bestA" else "") + (if (bestM != null) "/$bestM" else "")
                bestM != null -> bestM
                else -> generic
            }
            chosen.add(linkedMapOf(
                "height" to q,
                "label" to e.label.ifEmpty { "${q}p" },
                "codecs" to e.exts.sorted().take(3),
                "fps" to e.fps,
                "format" to "$exact/$generic",
                "size" to null,
            ))
        }
        formats[0]?.let { e ->
            chosen.add(linkedMapOf(
                "height" to 0, "label" to "Audio only (m4a)",
                "codecs" to e.exts.sorted().take(3), "fps" to 0,
                "format" to "bestaudio/best", "size" to null,
            ))
        }

        val media = linkedMapOf<String, Any?>(
            "kind" to "media", "url" to url, "file_type" to "video",
            "extractor" to info["extractor_key"].str().ifEmpty { "yt-dlp" },
            "title" to info["title"].str().ifEmpty { "Untitled" },
            "uploader" to info["uploader"].str().ifEmpty { info["channel"].str() },
            "duration" to info["duration"].int(),
            "view_count" to info["view_count"].long(),
            "thumbnail" to info["thumbnail"].str(),
            "webpage" to info["webpage_url"].str().ifEmpty { url },
            "formats" to chosen,
            "formats_total" to rawFormats.size,
            "needs_ffmpeg_merge" to !Ffmpeg.find().available,
            "ffmpeg" to Ffmpeg.find().available,
        )
        if (win.isNotEmpty()) media["extractor_args"] = linkedMapOf("youtube" to linkedMapOf("player_client" to win))
        if (playlistMeta != null) media["playlist"] = playlistMeta
        return media to err
    }
}
