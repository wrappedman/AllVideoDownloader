package com.allvideodownloader.avd.core

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Android port of `webfinder/core/ffmpeg_tools.py`.
 *
 * The desktop app ships without FFmpeg and looks it up (Settings → PATH → app
 * folder). Android cannot run a desktop FFmpeg build, and no Android FFmpeg
 * binary can be bundled here (NDK toolchain not available in this build
 * environment), so:
 *
 *  * if the user pushes an Android `ffmpeg`/`ffprobe` into the app's files
 *    directory (or sets `ffmpeg_path`), every desktop code path works unchanged
 *    — `-c copy` remux with `-progress` parsing included;
 *  * if not, HLS/DASH capture falls back to [HlsCapture], a native
 *    segment-level downloader that produces the same lossless `-c copy` result
 *    (no re-encode) for standard HLS, so the feature is not lost.
 */
object Ffmpeg {

    val RECODE_CONTAINERS = setOf("mp4", "mkv", "mov")
    private val TIME_RE = Regex("out_time_(?:us|ms)=(\\d+)")

    data class Binaries(val ffmpeg: String, val ffprobe: String, val available: Boolean)

    fun find(): Binaries {
        val cfg = Config.settings().getString("ffmpeg_path").trim()
        fun locate(binary: String): String {
            if (cfg.isNotEmpty()) {
                val p = File(cfg)
                val cand = if (p.extension.isEmpty()) File(p, binary) else p
                if (cand.isFile && cand.canExecute()) return cand.absolutePath
            }
            for (f in listOf(File(AppConfig.configDir.parentFile, binary),
                        File(AppConfig.configDir.parentFile, "bin/$binary"))) {
                if (f.isFile && f.canExecute()) return f.absolutePath
            }
            for (dir in (System.getenv("PATH") ?: "").split(":")) {
                val f = File(dir, binary)
                if (f.isFile && f.canExecute()) return f.absolutePath
            }
            return ""
        }
        val ffmpeg = locate("ffmpeg")
        val ffprobe = locate("ffprobe")
        return Binaries(ffmpeg, ffprobe, ffmpeg.isNotEmpty() && ffprobe.isNotEmpty())
    }

    /** {duration, bit_rate, size, streams} for any URL ffmpeg can open. */
    fun probe(url: String, headers: Map<String, String> = emptyMap()): MutableMap<String, Any?> {
        val bins = find()
        if (bins.ffprobe.isEmpty()) return LinkedHashMap()
        val args = mutableListOf(
            bins.ffprobe, "-v", "quiet", "-print_format", "json",
            "-show_format", "-show_streams",
        )
        for ((k, v) in headers) {
            args.add("-${k.lowercase(Locale.ROOT)}"); args.add(v)
        }
        args.add(url)
        return try {
            val (rc, out, _) = YtCli.run(args, 25)
            if (rc != 0 || out.isBlank()) return LinkedHashMap()
            val data = Json.parseOrNull(out).asMap() ?: return LinkedHashMap()
            val fmt = data["format"].asMap() ?: LinkedHashMap()
            val streams = data["streams"].list().mapNotNull { s ->
                val m = s.asMap() ?: return@mapNotNull null
                linkedMapOf<String, Any?>(
                    "type" to m["codec_type"].str(), "codec" to m["codec_name"].str(),
                    "width" to m["width"].int(), "height" to m["height"].int(),
                )
            }
            linkedMapOf(
                "duration" to fmt["duration"].num(),
                "bit_rate" to fmt["bit_rate"].num().toLong(),
                "size" to fmt["size"].num().toLong(),
                "streams" to streams,
            )
        } catch (_: Exception) {
            LinkedHashMap()
        }
    }

    /**
     * Capture an HLS/DASH stream to the job's file, updating progress as it runs.
     * Mirrors `ffmpeg_tools.capture_stream` argument for argument.
     */
    fun captureStream(job: DownloadJob, checkFlags: (DownloadJob) -> Unit, publish: (DownloadJob) -> Unit) {
        val bins = find()
        if (!bins.available) {
            throw RuntimeException(
                "FFmpeg was not found on this device. Push an Android ffmpeg/ffprobe build into the " +
                    "app's files folder (or set Settings → FFmpeg path) to capture streams with it.",
            )
        }
        val container = Config.settings().getString("default_container", "mp4").lowercase(Locale.ROOT).trimStart('.')
        var outPath = File(job.filepath)
        if (outPath.extension.lowercase(Locale.ROOT) != container) {
            outPath = File(outPath.absolutePath.substringBeforeLast('.', outPath.absolutePath) + ".$container")
            job.filepath = outPath.absolutePath
            job.filename = outPath.name
        }
        job.filepath = outPath.absolutePath

        val headers = job.meta["headers"].asMap() ?: LinkedHashMap()
        val source = job.meta["variant_url"].str().ifEmpty { job.url }
        val info = probe(source, headers.mapValues { it.value.str() })
        val duration = info["duration"].int()
        job.unit = "time"
        job.total = duration.toLong()
        job.done = 0

        val reencode = job.meta["reencode"].bool()
        val args = mutableListOf(
            bins.ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error",
            "-progress", "pipe:1", "-nostats", "-y",
            "-user_agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AVD/2.2",
        )
        for ((k, v) in headers) {
            args.add("-${k.lowercase(Locale.ROOT)}"); args.add(v.str())
        }
        if (job.meta["referer"].str().isNotEmpty()) {
            args.add("-headers"); args.add("Referer: ${job.meta["referer"].str()}\r\n")
        }
        args.add("-i"); args.add(source)
        if (reencode) {
            args.addAll(listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-c:a", "aac", "-b:a", "192k"))
        } else if (container == "mp4") {
            args.addAll(listOf("-c", "copy", "-bsf:a", "aac_adtstoasc", "-movflags", "+faststart"))
        } else {
            args.addAll(listOf("-c", "copy"))
        }
        args.add(outPath.absolutePath)

        val t0 = System.currentTimeMillis()
        var lastEmit = 0L
        val env = ProcessBuilder(args).also { pb ->
            val px = Config.settings().getString("proxy").trim()
            if (px.isNotEmpty()) {
                pb.environment()["http_proxy"] = px
                pb.environment()["https_proxy"] = px
            }
        }
        val proc = env.start()
        job.process = proc
        try {
            BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    if (job.cancelRequested) throw InterruptedException("cancelled")
                    val line = reader.readLine() ?: break
                    val m = TIME_RE.find(line) ?: continue
                    val seconds = m.groupValues[1].toLong() / 1_000_000.0
                    job.done = seconds.toLong()
                    if (duration > 0) {
                        job.progressPct = Math.round(minOf(100.0, seconds / duration * 100) * 10) / 10.0
                        job.eta = maxOf(0.0, duration - seconds)
                    }
                    job.speed = Math.round(seconds / maxOf(0.5, (System.currentTimeMillis() - t0) / 1000.0) * 10) / 10.0
                    if (System.currentTimeMillis() - lastEmit >= 400) {
                        lastEmit = System.currentTimeMillis()
                        checkFlags(job)
                        publish(job)
                    }
                }
            }
            val ret = proc.waitFor(30, TimeUnit.SECONDS)
            if (!ret) proc.destroyForcibly()
            val code = if (ret) proc.exitValue() else -1
            if (code != 0 && !job.cancelRequested) {
                val err = proc.errorStream.bufferedReader().readText().takeLast(400)
                throw RuntimeException("FFmpeg exited with $code. $err")
            }
        } finally {
            job.process = null
        }
        if (!outPath.exists()) throw RuntimeException("FFmpeg finished but no output file was produced.")
        job.unit = "bytes"
        job.done = outPath.length()
        job.total = outPath.length()
        job.progressPct = 100.0
        publish(job)
    }
}
