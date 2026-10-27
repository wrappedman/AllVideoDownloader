package com.allvideodownloader.avd.core

import java.util.Locale

/**
 * YouTube without yt-dlp.
 *
 * The desktop build shells out to yt-dlp; Android cannot (there is no Python
 * runtime and, since Android 10, no executing binaries from app storage). So
 * this object speaks YouTube's own player endpoint directly — the same
 * "Innertube" call the mobile apps make — and turns `streamingData` into the
 * quality ladder the UI already knows how to render.
 *
 * Same split as every other adapter: a **pure parser** ([parsePlayer]) that
 * takes a decoded payload and returns a card, and a thin fetcher ([card]) that
 * walks a client ladder. The parser is what the fixtures test.
 */
object Youtube {

    /**
     * Innertube hosts. Tests point these at a loopback fixture server; the app
     * never changes them.
     */
    @Volatile var apiBase: String = "https://www.youtube.com"
    @Volatile var watchBase: String = "https://www.youtube.com"

    /**
     * The player clients tried in order. Each one answers with the same JSON
     * schema, but they differ in how they sign the media URLs: the web clients
     * return `signatureCipher` (needs a JS interpreter we do not have), the
     * app/VR clients return a plain `url` we can hand to the downloader.
     */
    class Client(
        val name: String,
        val version: String,
        val number: Int,
        val userAgent: String,
        val extra: Map<String, Any?> = emptyMap(),
    )

    val CLIENTS: List<Client> = listOf(
        Client(
            "ANDROID_VR", "1.60.19", 28,
            "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; en_US) gzip",
            mapOf(
                "androidSdkVersion" to 32, "deviceMake" to "Oculus", "deviceModel" to "Quest 3",
                "osName" to "Android", "osVersion" to "12",
            ),
        ),
        Client(
            "ANDROID", "19.44.38", 3,
            "com.google.android.youtube/19.44.38 (Linux; U; Android 14) gzip",
            mapOf("androidSdkVersion" to 34, "osName" to "Android", "osVersion" to "14"),
        ),
        Client(
            "IOS", "20.10.4", 5,
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X)",
            mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.1.0.22B83"),
        ),
        Client("WEB", "2.20250101.00.00", 1, Net.UA),
    )

    // ── URL → video id ────────────────────────────────────────────────────
    private val ID = "[A-Za-z0-9_-]{11}"
    private val ID_PATTERNS = listOf(
        Regex("^https?://(?:[a-z0-9-]+\\.)*youtu\\.be/($ID)"),
        Regex("^https?://(?:[a-z0-9-]+\\.)*(?:youtube\\.com|youtube-nocookie\\.com)/(?:watch\\?(?:[^#]*&)?v=|shorts/|embed/|live/|v/|e/)($ID)"),
        Regex("^https?://(?:[a-z0-9-]+\\.)*youtube\\.com/attribution_link\\?[^#]*v=($ID)"),
    )

    fun videoId(url: String): String {
        val u = url.trim()
        for (p in ID_PATTERNS) p.find(u)?.let { return it.groupValues[1] }
        return ""
    }

    fun isYoutube(url: String): Boolean = videoId(url).isNotEmpty()

    // ── pure parser ───────────────────────────────────────────────────────

    /** Why the player refused, in words the card can print. */
    fun refusal(payload: Any?): String {
        val status = payload.map()["playabilityStatus"].map()
        val st = status["status"].str()
        if (st.isEmpty() || st == "OK") return ""
        val reason = status["reason"].str()
        val sub = status["errorScreen"].map()["playerErrorMessageRenderer"].map()["subreason"].map()
        val detailed = sub["simpleText"].str().ifEmpty {
            sub["runs"].list().joinToString("") { it.map()["text"].str() }
        }
        return listOf(st, reason, detailed).filter { it.isNotEmpty() }.joinToString(" — ")
    }

    /**
     * `streamingData` → formats the downloader can use.
     *
     * Only rows that already carry a plain `url` are kept: a `signatureCipher`
     * row needs YouTube's JS player to be deciphered, which no Android build of
     * this app can do, so offering it would only produce a 403 mid-download.
     */
    fun parsePlayer(payload: Any?, pageUrl: String): MutableMap<String, Any?>? {
        val root = payload.map()
        if (root.isEmpty()) return null
        val details = root["videoDetails"].map()
        val streaming = root["streamingData"].map()

        val muxed = ArrayList<MutableMap<String, Any?>>()
        val videoOnly = ArrayList<MutableMap<String, Any?>>()
        val audio = ArrayList<MutableMap<String, Any?>>()

        fun row(raw: Any?, adaptive: Boolean) {
            val f = raw.map()
            val url = f["url"].str()
            if (url.isEmpty()) return  // cipher-only row — see the doc comment
            val mime = f["mimeType"].str().lowercase(Locale.ROOT)
            val ext = when {
                "webm" in mime -> "webm"
                "3gpp" in mime -> "3gp"
                "mp4" in mime -> "mp4"
                else -> "mp4"
            }
            val height = f["height"].int()
            val size = f["contentLength"].str().toLongOrNull()
            val itag = f["itag"].int()
            val built = if (mime.startsWith("audio/")) {
                val kbps = maxOf(1, f["bitrate"].int() / 1000)
                Adapters.format(url, 0, "Audio ${kbps}k · $ext", size, ext).also {
                    it["file_type"] = "audio"
                    it["itag"] = itag
                    it["audio_only"] = true
                }
            } else {
                val label = when {
                    height > 0 && adaptive -> "${height}p · video only"
                    height > 0 -> "${height}p"
                    else -> ext.uppercase(Locale.ROOT)
                }
                Adapters.format(url, height, label, size, ext).also {
                    it["file_type"] = "video"
                    it["itag"] = itag
                    if (adaptive) it["video_only"] = true
                }
            }
            (if (mime.startsWith("audio/")) audio else if (adaptive) videoOnly else muxed).add(built)
        }

        for (f in streaming["formats"].list()) row(f, adaptive = false)
        for (f in streaming["adaptiveFormats"].list()) row(f, adaptive = true)

        if (muxed.isEmpty() && videoOnly.isEmpty() && audio.isEmpty()) return null

        muxed.sortWith(compareByDescending<MutableMap<String, Any?>> { it["height"].int() })
        videoOnly.sortWith(compareByDescending<MutableMap<String, Any?>> { it["height"].int() })
        audio.sortWith(compareByDescending<MutableMap<String, Any?>> { it["size"].num() })

        // Muxed rungs first (they play as-is), then video-only, then audio.
        val formats = (muxed + videoOnly + audio).toMutableList()

        val thumbs = details["thumbnail"].map()["thumbnails"].list()
            .map { it.map() }.filter { it["url"].str().isNotEmpty() }
        val thumbnail = (thumbs.maxByOrNull { it["width"].int() } ?: thumbs.lastOrNull())
            ?.get("url").str() ?: ""

        val title = details["title"].str()
        // every rung already knows the name it should be saved under
        val stem = title.replace(Regex("[<>:\"/\\\\|?*]"), "_").trim().take(120)
        if (stem.isNotEmpty()) {
            for (f in formats) {
                val tag = f["label"].str().replace(" · ", " ").replace(Regex("[^\\w.\\-+ ]"), "_")
                f["filename"] = "$stem $tag.${f["ext"].str().ifEmpty { "mp4" }}"
            }
        }
        val card = linkedMapOf<String, Any?>(
            "kind" to "webmedia",
            "url" to pageUrl,
            "file_type" to "video",
            "title" to title.ifEmpty { FileKind.guessFilename(pageUrl, "YouTube video") },
            "site" to "YouTube",
            "player" to "YouTube player API",
            "thumbnail" to thumbnail,
            "referer" to pageUrl,
            "found_on_page" to pageUrl,
            "formats" to formats,
            "formats_total" to formats.size,
            "duration" to details["lengthSeconds"].str().toIntOrNull(),
            "uploader" to details["author"].str(),
        )
        val mergeMissing = !Ffmpeg.find().available
        card["ffmpeg"] = !mergeMissing
        card["needs_ffmpeg_merge"] = mergeMissing && videoOnly.isNotEmpty()
        if (videoOnly.isNotEmpty() && mergeMissing) {
            card["merge_note"] = "Video-only rungs arrive without sound — Android cannot " +
                "merge streams (no ffmpeg), pick a rung without the “video only” tag for " +
                "picture + sound in one file."
        }
        return card
    }

    // ── fetcher ───────────────────────────────────────────────────────────

    private fun body(client: Client, id: String): String {
        val ctx = linkedMapOf<String, Any?>(
            "client" to (linkedMapOf<String, Any?>(
                "clientName" to client.name,
                "clientVersion" to client.version,
                "hl" to "en",
                "gl" to "US",
                "userAgent" to client.userAgent,
                "timeZone" to "UTC",
                "utcOffsetMinutes" to 0,
            ) + client.extra),
        )
        return Json.write(
            linkedMapOf<String, Any?>(
                "context" to ctx,
                "videoId" to id,
                "contentCheckOk" to true,
                "racyCheckOk" to true,
            ),
        )
    }

    private fun headers(client: Client): Map<String, String> = linkedMapOf(
        "User-Agent" to client.userAgent,
        "X-Youtube-Client-Name" to client.number.toString(),
        "X-Youtube-Client-Version" to client.version,
        "X-Youtube-Client-Format-Is-Hls" to "false",
        "Origin" to apiBase,
        "Referer" to "$watchBase/",
    )

    /**
     * The card for a YouTube link: a `webmedia` ladder when a client hands out
     * playable URLs, otherwise a `media_error` that says what YouTube answered.
     */
    fun card(url: String): MutableMap<String, Any?>? {
        val id = videoId(url)
        if (id.isEmpty()) return null
        var refusalReason = ""
        var reason = ""
        var sawCipherOnly = false
        val started = System.currentTimeMillis()
        for (client in CLIENTS) {
            // the ladder is a rescue, not a retry storm: four timeouts would make
            // "Grab it" feel broken on a slow connection
            if (System.currentTimeMillis() - started > 25_000) break
            val res = Net.postJson(
                "$apiBase/youtubei/v1/player?prettyPrint=false",
                body(client, id),
                headers(client),
                referer = "$watchBase/",
            )
            if (res.status == 0 || res.text.isEmpty()) {
                reason = if (reason.isEmpty()) "the player endpoint was unreachable" else reason
                continue
            }
            val payload = Json.parseOrNull(res.text)
            if (payload == null) {
                reason = "the player endpoint answered with something that is not JSON"
                continue
            }
            val refusal = refusal(payload)
            if (refusal.isNotEmpty()) {
                // a named refusal ("Video unavailable", "Sign in to confirm…") is
                // more useful than anything a later client says
                if (refusalReason.isEmpty()) refusalReason = refusal else reason = refusal
                continue
            }
            val parsed = parsePlayer(payload, url)
            if (parsed != null) {
                parsed["player_client"] = client.name
                return parsed
            }
            if (res.text.contains("signatureCipher") || res.text.contains("\"cipher\"")) sawCipherOnly = true
            reason = if (reason.isEmpty()) "${client.name} offered no playable URL" else reason
        }

        // Last resort: the watch page itself sometimes carries plain googlevideo
        // URLs (and always carries the title), even when the API refuses.
        pageFallback(url, id)?.let { return it }

        return errorCard(
            url,
            when {
                refusalReason.isNotEmpty() -> refusalReason
                sawCipherOnly ->
                    "YouTube returned signed (ciphered) URLs only. Deciphering them needs " +
                        "YouTube's JavaScript player, which this Android build cannot run."
                else -> reason.ifEmpty { "YouTube refused the request for this video." }
            },
        )
    }

    // `itag` → the rung it means, for URLs harvested from a page instead of the API.
    private val ITAG = mapOf(
        18 to 360, 22 to 720, 37 to 1080, 43 to 360, 44 to 480, 45 to 720, 46 to 1080,
        133 to 240, 134 to 360, 135 to 480, 136 to 720, 137 to 1080, 138 to 2160,
        160 to 144, 242 to 240, 243 to 360, 244 to 480, 247 to 720, 248 to 1080,
        271 to 1440, 272 to 2160, 313 to 2160, 394 to 144, 395 to 240, 396 to 360,
        397 to 480, 398 to 720, 399 to 1080,
    )
    // YouTube's CDN marks every media row with /videoplayback — that, not the
    // host name, is what identifies it (the host shard varies per region).
    private val GOOGLEVIDEO_RE = Regex(
        """"?(https?://[^"'\\\s<>]+/videoplayback\?[^"'\\\s<>]+)""",
        RegexOption.IGNORE_CASE,
    )

    private fun pageFallback(url: String, id: String): MutableMap<String, Any?>? {
        val (html, _, _) = Net.fetchText(
            "$watchBase/watch?v=$id&bpctr=9999999999&has_verified=1",
            referer = "$watchBase/",
            timeoutSec = 16,
        )
        if (html.isEmpty()) return null

        extractJsonObject(html, "ytInitialPlayerResponse")?.let { json ->
            parsePlayer(Json.parseOrNull(json), url)?.let { card ->
                card["player_client"] = "page"
                return card
            }
        }

        val found = LinkedHashMap<String, MutableMap<String, Any?>>()
        for (m in GOOGLEVIDEO_RE.findAll(html)) {
            val u = m.groupValues[1].replace("\\u0026", "&").replace("\\/", "/")
            val itag = Regex("[?&]itag=(\\d+)").find(u)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val mime = Regex("[?&]mime=([^&]+)").find(u)?.groupValues?.get(1).orEmpty()
                .let { UrlKit.unquote(it) }
            if (found.containsKey(u)) continue
            val isAudio = mime.startsWith("audio/")
            val height = ITAG[itag] ?: 0
            if (!isAudio && height == 0) continue          // unknown video rung
            val ext = if ("webm" in mime) "webm" else if ("mp4" in mime) "mp4" else "mp4"
            val f = if (isAudio) {
                Adapters.format(u, 0, "Audio · $ext", null, ext).also {
                    it["file_type"] = "audio"
                    it["audio_only"] = true
                }
            } else {
                Adapters.format(u, height, "${height}p", null, ext).also { it["file_type"] = "video" }
            }
            f["itag"] = itag
            found[u] = f
        }
        if (found.isEmpty()) return null
        val formats = found.values.sortedByDescending { it["height"].int() }.toMutableList()
        return linkedMapOf<String, Any?>(
            "kind" to "webmedia",
            "url" to url,
            "file_type" to "video",
            "title" to pageTitle(html).ifEmpty { FileKind.guessFilename(url, "YouTube video") },
            "site" to "YouTube",
            "player" to "YouTube watch page",
            "thumbnail" to "",
            "referer" to "$watchBase/",
            "found_on_page" to url,
            "formats" to formats,
            "formats_total" to formats.size,
            "player_client" to "page",
            "ffmpeg" to Ffmpeg.find().available,
            "needs_ffmpeg_merge" to false,
        )
    }

    private fun pageTitle(html: String): String {
        val m = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html) ?: return ""
        return m.groupValues[1].replace("&amp;", "&").replace(" - YouTube", "").trim()
    }

    /** The `{…}` payload assigned to `name` in the page's inline scripts. */
    fun extractJsonObject(html: String, name: String): String? {
        val at = html.indexOf("$name = ")
        if (at < 0) return null
        val start = html.indexOf('{', at)
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        var i = start
        while (i < html.length) {
            val c = html[i]
            when {
                esc -> esc = false
                c == '\\' && inStr -> esc = true
                c == '"' -> inStr = !inStr
                inStr -> {}
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return html.substring(start, i + 1)
                }
            }
            i++
        }
        return null
    }

    private fun errorCard(url: String, reason: String): MutableMap<String, Any?> = linkedMapOf(
        "kind" to "media_error",
        "url" to url,
        "extractor" to "youtube",
        "file_type" to "video",
        "site" to "YouTube",
        "title" to FileKind.guessFilename(url, "YouTube video"),
        "error" to reason,
        "hints" to mutableListOf(
            "YouTube gates its player per client and per region, so a refusal here usually " +
                "means “try another client”, not “no video”: retry once, and if it repeats the " +
                "video is likely age/region-locked or bot-checked.",
            "This build asks YouTube directly (no yt-dlp binary exists for Android), so it " +
                "cannot solve a bot check or decipher a signature-protected URL.",
            "Anything the page hands out as a plain file or <code>.m3u8</code> still downloads " +
                "here — open the video in your browser and paste the media link if the extractor " +
                "keeps being refused.",
        ),
        "candidates" to ArrayList<Any?>(),
    )
}
