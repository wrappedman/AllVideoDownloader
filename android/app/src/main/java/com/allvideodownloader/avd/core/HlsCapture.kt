package com.allvideodownloader.avd.core

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Native HLS capture — the Android stand-in for `ffmpeg -c copy` on a playlist.
 *
 * The desktop hands every `.m3u8` to FFmpeg. FFmpeg binaries cannot be bundled in
 * this build (no NDK toolchain), so when no user-supplied binary exists this class
 * performs the *same lossless operation* without re-encoding: it resolves the
 * variant playlist, downloads each segment in order, decrypts AES-128 segments,
 * and concatenates them into a single file — which is exactly what
 * `ffmpeg -i playlist -c copy out.ts` produces. MPEG-TS segments concatenate
 * verbatim; fMP4 segments keep their `EXT-X-MAP` initialisation segment at the
 * front so the result is a playable `.mp4`.
 *
 * DASH (`.mpd`) has no equivalent without a muxer, so it still requires FFmpeg and
 * says so in plain words (the desktop says the same when FFmpeg is missing).
 */
object HlsCapture {

    private val STREAM_INF = Regex("#EXT-X-STREAM-INF:([^\\n]*)", RegexOption.IGNORE_CASE)

    fun capture(job: DownloadJob, checkFlags: (DownloadJob) -> Unit, publish: (DownloadJob) -> Unit) {
        val referer = job.meta["referer"].str()
        val source = job.meta["variant_url"].str().ifEmpty { job.url }
        if (source.lowercase(Locale.ROOT).contains(".mpd") || source.lowercase(Locale.ROOT).contains("dash")) {
            throw RuntimeException(
                "DASH (.mpd) needs FFmpeg to mux its separate audio and video tracks. " +
                    "Push an Android ffmpeg build into the app's files folder (Settings → FFmpeg path) — " +
                    "HLS streams are captured without it.",
            )
        }

        var text = Net.fetchManifestText(source, referer)
        // master playlist → pick the variant the user chose, else the tallest
        if (text.contains("#EXT-X-STREAM-INF", true)) {
            val variants = Media.parseM3u8Master(text, source)
            val chosen = variants.firstOrNull() ?: throw RuntimeException("This master playlist lists no variant.")
            text = Net.fetchManifestText(chosen["url"].str(), referer)
        }

        val lines = text.split("\n").map { it.trim() }
        val segments = ArrayList<String>()
        var keyUri = ""
        var keyIv = ""
        var initSegment = ""
        var totalDuration = 0.0
        var duration = 0.0
        var endList = false
        var encryptedMp4 = false

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("#EXT-X-KEY", true) -> {
                    val method = attr(line, "METHOD")
                    if (method.equals("AES-128", true)) {
                        keyUri = attr(line, "URI")
                        keyIv = attr(line, "IV")
                    } else if (method.equals("SAMPLE-AES", true)) {
                        encryptedMp4 = true
                    }
                }
                line.startsWith("#EXT-X-MAP", true) -> initSegment = attr(line, "URI")
                line.startsWith("#EXTINF:", true) -> {
                    duration = line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
                    totalDuration += duration
                }
                line.startsWith("#EXT-X-ENDLIST", true) -> endList = true
                line.isEmpty() || line.startsWith("#") -> { /* comment */ }
                else -> segments.add(UrlKit.join(source, line))
            }
            i++
        }
        if (segments.isEmpty()) throw RuntimeException("This playlist lists no media segments.")
        if (encryptedMp4) {
            throw RuntimeException("This stream uses SAMPLE-AES encryption, which needs FFmpeg to capture.")
        }

        val container = Config.settings().getString("default_container", "mp4").lowercase(Locale.ROOT)
        val isFmp4 = initSegment.isNotEmpty() || segments.first().lowercase(Locale.ROOT).contains(".m4s") ||
            segments.first().lowercase(Locale.ROOT).contains(".mp4")
        val ext = when {
            isFmp4 -> "mp4"
            container == "ts" -> "ts"
            else -> "ts"
        }
        var outPath = File(job.filepath)
        if (outPath.extension.lowercase(Locale.ROOT) != ext) {
            outPath = File(outPath.absolutePath.substringBeforeLast('.', outPath.absolutePath) + ".$ext")
        }
        job.filepath = outPath.absolutePath
        job.filename = outPath.name
        outPath.parentFile?.mkdirs()
        outPath.delete()

        job.unit = "time"
        job.total = totalDuration.toInt().toLong()
        job.done = 0
        job.progressPct = 0.0
        publish(job)

        val keyBytes: ByteArray? = if (keyUri.isNotEmpty()) {
            Net.get(UrlKit.join(source, keyUri), Net.fileHeaders(referer), 20, 4096).bytes
        } else null
        if (keyUri.isNotEmpty() && (keyBytes == null || keyBytes.isEmpty())) {
            throw RuntimeException("The stream's AES key could not be fetched — try again or use FFmpeg.")
        }

        var elapsedSeconds = 0.0
        val started = System.currentTimeMillis()
        FileOutputStream(outPath, true).use { out: OutputStream ->
            if (initSegment.isNotEmpty()) {
                val initUrl = UrlKit.join(source, initSegment)
                out.write(Net.get(initUrl, Net.fileHeaders(referer), 30, 8L * 1024 * 1024).bytes)
            }
            for ((index, seg) in segments.withIndex()) {
                checkFlags(job)
                val resp = Net.get(seg, Net.fileHeaders(referer), 30, 512L * 1024 * 1024)
                if (resp.status >= 400) throw RuntimeException("Segment ${index + 1} answered HTTP ${resp.status}")
                var data = resp.bytes
                if (keyBytes != null) data = decrypt(data, keyBytes, keyIv, index)
                out.write(data)
                out.flush()
                elapsedSeconds += if (duration > 0) duration else 0.0
                job.done = elapsedSeconds.toLong()
                if (totalDuration > 0) {
                    job.progressPct = Math.round(minOf(99.0, elapsedSeconds / totalDuration * 100) * 10) / 10.0
                }
                job.speed = Math.round(elapsedSeconds / maxOf(0.5, (System.currentTimeMillis() - started) / 1000.0) * 10) / 10.0
                publish(job)
            }
        }
        if (!endList) {
            job.meta["note"] = "The playlist was still growing (live stream) — captured up to this point."
        }
        job.unit = "bytes"
        job.done = outPath.length()
        job.total = outPath.length()
        job.progressPct = 100.0
        publish(job)
    }

    private fun attr(line: String, name: String): String {
        val m = Regex("$name=(\"[^\"]*\"|[^,]*)", RegexOption.IGNORE_CASE).find(line) ?: return ""
        return m.groupValues[1].trim().trim('"')
    }

    /** AES-128-CBC, the HLS default (per-segment IV = media sequence when absent). */
    private fun decrypt(data: ByteArray, key: ByteArray, ivHex: String, index: Int): ByteArray {
        return try {
            val iv = if (ivHex.isNotEmpty()) {
                val clean = ivHex.removePrefix("0x").removePrefix("0X")
                val bytes = ByteArray(16)
                for (j in 0 until minOf(16, clean.length / 2)) {
                    bytes[j] = clean.substring(j * 2, j * 2 + 2).toInt(16).toByte()
                }
                bytes
            } else {
                ByteArray(16).also { b ->
                    var v = index.toLong()
                    for (j in 15 downTo 12) {
                        b[j] = (v and 0xff).toByte()
                        v = v shr 8
                    }
                }
            }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(data)
        } catch (e: Exception) {
            throw RuntimeException("An encrypted segment could not be decrypted: ${e.message}")
        }
    }
}
