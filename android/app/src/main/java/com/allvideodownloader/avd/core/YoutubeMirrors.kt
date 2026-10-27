package com.allvideodownloader.avd.core

import java.util.Locale

/**
 * The escape hatch for "Sign in to confirm you're not a bot".
 *
 * YouTube decides that from the *IP the request comes from* — a home line is
 * usually fine, a VPN or a datacentre is not — and no amount of header fiddling
 * changes it. Public front-ends (Invidious, Piped) fetch the video server-side
 * from their own addresses, so the app can borrow their clean connection and
 * still get a plain quality ladder.
 *
 * Same split as everywhere else: [parseInvidious] / [parsePiped] are pure
 * parsers (fixture-tested, no network), [card] is the thin fetcher that walks
 * [instances] and gives up quickly.
 */
object YoutubeMirrors {

    /** Invidious API hosts, tried in order. Overridable in tests. */
    @Volatile var instances: List<String> = listOf(
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://inv.tux.pizza",
        "https://invidious.jing.rocks",
        "https://iv.ggtyler.dev",
        "https://invidious.f5.si",
        "https://yewtu.be",
    )

    /** Piped API hosts, tried after the Invidious ones. */
    @Volatile var pipedInstances: List<String> = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
        "https://pipedapi.reallyaweso.me",
    )

    private const val PER_INSTANCE_MS = 7_000L
    private const val TOTAL_MS = 22_000L

    // ── shared helpers ────────────────────────────────────────────────────

    private fun rungLabel(height: Int, ext: String, videoOnly: Boolean): String = when {
        height > 0 && videoOnly -> "${height}p · video only"
        height > 0 -> "${height}p"
        else -> ext.uppercase(Locale.ROOT)
    }

    private fun nameThem(formats: List<MutableMap<String, Any?>>, title: String) {
        val stem = title.replace(Regex("[<>:\"/\\\\|?*]"), "_").trim().take(120)
        if (stem.isEmpty()) return
        for (f in formats) {
            val tag = f["label"].str().replace(" · ", " ").replace(Regex("[^\\w.\\-+ ]"), "_")
            f["filename"] = "$stem $tag.${f["ext"].str().ifEmpty { "mp4" }}"
        }
    }

    private fun card(url: String, title: String, uploader: String, thumb: String,
                     formats: List<MutableMap<String, Any?>>, route: String): MutableMap<String, Any?>? {
        if (formats.isEmpty()) return null
        // one playable file first, then the manifest, then the pieces that need
        // a merge, then audio on its own
        val muxed = formats.filter {
            it["video_only"] != true && it["audio_only"] != true && it["ext"].str() !in FileKind.STREAM_EXTS
        }.sortedByDescending { it["height"].int() }
        val manifests = formats.filter {
            it["video_only"] != true && it["audio_only"] != true && it["ext"].str() in FileKind.STREAM_EXTS
        }
        val videoOnly = formats.filter { it["video_only"] == true }.sortedByDescending { it["height"].int() }
        val audio = formats.filter { it["audio_only"] == true }
        val ordered = (muxed + manifests + videoOnly + audio).toMutableList()
        nameThem(ordered, title)
        val noFfmpeg = !Ffmpeg.find().available
        val out = linkedMapOf<String, Any?>(
            "kind" to "webmedia",
            "url" to url,
            "file_type" to "video",
            "title" to title.ifEmpty { FileKind.guessFilename(url, "YouTube video") },
            "site" to "YouTube",
            "player" to "YouTube via $route",
            "thumbnail" to thumb,
            "referer" to url,
            "found_on_page" to url,
            "formats" to ordered,
            "formats_total" to ordered.size,
            "uploader" to uploader,
            "player_client" to route,
            "ffmpeg" to !noFfmpeg,
            "needs_ffmpeg_merge" to (noFfmpeg && videoOnly.isNotEmpty()),
        )
        if (noFfmpeg && videoOnly.isNotEmpty()) {
            out["merge_note"] = "Video-only rungs arrive without sound — Android cannot merge " +
                "streams (no ffmpeg), pick a rung without the “video only” tag for picture + sound."
        }
        return out
    }

    private fun heightOf(vararg hints: String): Int {
        for (h in hints) {
            val q = Embed.qualityOf("", h)
            if (q.height > 0) return q.height
            val m = Regex("(\\d{3,4})p?").find(h)
            if (m != null && m.groupValues[1].toIntOrNull() in listOf(144, 180, 240, 270, 360, 480, 540, 720, 1080, 1440, 2160)) {
                return m.groupValues[1].toInt()
            }
        }
        return 0
    }

    // ── Invidious ─────────────────────────────────────────────────────────

    /**
     * `/api/v1/videos/{id}` → card.
     *
     * `formatStreams` are the progressive (picture + sound) rungs; `adaptiveFormats`
     * only exist when the instance has them enabled, and `hlsUrl` is a manifest we
     * can hand to the HLS capture path.
     */
    fun parseInvidious(payload: Any?, pageUrl: String): MutableMap<String, Any?>? {
        val root = payload.map()
        if (root.isEmpty()) return null
        if (root["error"].str().isNotEmpty()) return null
        val title = root["title"].str()
        val formats = ArrayList<MutableMap<String, Any?>>()

        for (raw in root["formatStreams"].list()) {
            val f = raw.map()
            val u = f["url"].str()
            if (u.isEmpty()) continue
            val ext = f["container"].str().ifEmpty { FileKind.urlExtension(u).trimStart('.').ifEmpty { "mp4" } }
            val h = heightOf(f["resolution"].str(), f["qualityLabel"].str(), f["quality"].str())
            val size = f["size"].str().let { if (it.isNotEmpty()) Adapters.sizeBytes(it) else null }
            formats.add(Adapters.format(u, h, rungLabel(h, ext, videoOnly = false), size, ext))
        }

        for (raw in root["adaptiveFormats"].list()) {
            val f = raw.map()
            val u = f["url"].str()
            if (u.isEmpty()) continue
            val type = f["type"].str().lowercase(Locale.ROOT)
            val ext = f["container"].str().ifEmpty {
                if ("webm" in type) "webm" else if ("audio" in type) "m4a" else "mp4"
            }
            if (type.startsWith("audio") || f["audioQuality"].str().isNotEmpty()) {
                val kbps = maxOf(1, f["bitrate"].int() / 1000)
                val size = f["size"].str().let { if (it.isNotEmpty()) Adapters.sizeBytes(it) else null }
                formats.add(Adapters.format(u, 0, "Audio ${kbps}k · $ext", size, ext).also {
                    it["file_type"] = "audio"
                    it["audio_only"] = true
                })
            } else {
                val h = heightOf(f["resolution"].str(), f["qualityLabel"].str(), f["quality"].str())
                val size = f["size"].str().let { if (it.isNotEmpty()) Adapters.sizeBytes(it) else null }
                formats.add(Adapters.format(u, h, rungLabel(h, ext, videoOnly = true), size, ext).also {
                    it["video_only"] = true
                })
            }
        }

        val hls = root["hlsUrl"].str()
        if (hls.isNotEmpty()) {
            formats.add(Adapters.format(hls, 0, "HLS (auto)", null, "m3u8"))
        }
        val thumb = root["videoThumbnails"].list().map { it.map() }
            .maxByOrNull { it["width"].int() }?.get("url").str() ?: ""
        return card(pageUrl, title, root["author"].str(), thumb, formats, "Invidious")
    }

    // ── Piped ─────────────────────────────────────────────────────────────

    /** `/streams/{id}` → card: `videoStreams` (adaptive) + `audioStreams`. */
    fun parsePiped(payload: Any?, pageUrl: String): MutableMap<String, Any?>? {
        val root = payload.map()
        if (root.isEmpty()) return null
        if (root["error"].str().isNotEmpty() || root["message"].str().isNotEmpty()) return null
        val formats = ArrayList<MutableMap<String, Any?>>()

        for (raw in root["videoStreams"].list()) {
            val f = raw.map()
            val u = f["url"].str()
            if (u.isEmpty()) continue
            val mime = f["mimeType"].str().lowercase(Locale.ROOT)
            val ext = if ("webm" in mime) "webm" else "mp4"
            val h = heightOf(f["quality"].str(), f["qualityLabel"].str())
            val size = f["contentLength"].long().takeIf { it > 0 }
            val videoOnly = f["videoOnly"].bool()
            formats.add(Adapters.format(u, h, rungLabel(h, ext, videoOnly), size, ext).also {
                if (videoOnly) it["video_only"] = true
            })
        }
        for (raw in root["audioStreams"].list()) {
            val f = raw.map()
            val u = f["url"].str()
            if (u.isEmpty()) continue
            val kbps = maxOf(1, f["bitrate"].int() / 1000)
            val ext = if ("webm" in f["mimeType"].str().lowercase(Locale.ROOT)) "webm" else "m4a"
            val size = f["contentLength"].long().takeIf { it > 0 }
            formats.add(Adapters.format(u, 0, "Audio ${kbps}k · $ext", size, ext).also {
                it["file_type"] = "audio"
                it["audio_only"] = true
            })
        }
        return card(pageUrl, root["title"].str(), root["uploader"].str(), root["thumbnailUrl"].str(),
            formats, "Piped")
    }

    // ── fetcher ───────────────────────────────────────────────────────────

    /**
     * A mirror's JSON endpoint — read with [Net.get] rather than [Net.fetchText],
     * because that one applies the "short page without a <title> is a block
     * screen" rule, which every small JSON body would trip.
     */
    private fun getJson(url: String): Any? = try {
        val headers = Net.browserHeaders(accept = "application/json")
        val r = Net.get(url, headers, timeoutSec = 7)
        if (r.status !in 200..299 || r.text.isEmpty()) null else Json.parseOrNull(r.text)
    } catch (_: Exception) {
        null
    }

    /**
     * Try every mirror until one returns a usable ladder. Never throws and never
     * blocks for long: the user is waiting on a button press.
     */
    fun card(url: String, id: String): MutableMap<String, Any?>? {
        if (id.isEmpty()) return null
        val started = System.currentTimeMillis()
        for (host in instances) {
            if (System.currentTimeMillis() - started > TOTAL_MS) return null
            val payload = getJson("${host.trimEnd('/')}/api/v1/videos/$id")
            parseInvidious(payload, url)?.let { return it }
        }
        for (host in pipedInstances) {
            if (System.currentTimeMillis() - started > TOTAL_MS) return null
            val payload = getJson("${host.trimEnd('/')}/streams/$id")
            parsePiped(payload, url)?.let { return it }
        }
        return null
    }
}
