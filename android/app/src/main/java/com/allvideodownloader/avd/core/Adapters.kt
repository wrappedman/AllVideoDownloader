package com.allvideodownloader.avd.core

import java.util.Locale
import java.util.regex.Pattern

/**
 * Android port of `webfinder/core/adapters.py` — the sites whose stream only
 * exists behind a JSON endpoint (Aparat, Filimo, and YouTube's player API,
 * which on desktop was handled by the yt-dlp binary Android cannot run).
 *
 * Same split as the desktop module: a **pure parser** (no network, fixture
 * testable) and a thin fetcher. Everything else stays in the recipe table.
 */
object Adapters {

    class Adapter(
        val key: String,
        val site: String,
        val match: Pattern,
        val priority: Boolean = false,
        val note: String = "",
    )

    private val APARAT_RE = Pattern.compile(
        "^https?://(?:www\\.|m\\.)?aparat\\.com/" +
            "(?:v/|video/video/embed/(?:videohash|vt/frame/showvideo/yes/videohash)/" +
            "|embed/videohash/)(?<id>[A-Za-z0-9]{3,20})",
        Pattern.CASE_INSENSITIVE,
    )

    private val FILIMO_RE = Pattern.compile(
        "^https?://(?:www\\.)?filimo\\.com/" +
            "(?:n|m|w|v|film|serial|movie/movie/watch|movie/movie/download_manager" +
            "|api/fa/v1/partner/TV/watch)/(?:uid/)?(?<id>[A-Za-z0-9]{4,16})",
        Pattern.CASE_INSENSITIVE,
    )

    private val YOUTUBE_RE = Pattern.compile(
        "^https?://(?:[a-z0-9-]+\\.)*(?:youtu\\.be|youtube\\.com|youtube-nocookie\\.com)/",
        Pattern.CASE_INSENSITIVE,
    )

    val APARAT = Adapter(
        "aparat", "Aparat", APARAT_RE, priority = true,
        note = "public videohash API → every CDN profile (144p…1080p) with sizes",
    )
    val FILIMO = Adapter(
        "filimo", "Filimo", FILIMO_RE, priority = true,
        note = "TV partner API → signed HLS manifest",
    )

    val YOUTUBE = Adapter(
        "youtube", "YouTube", YOUTUBE_RE, priority = true,
        note = "player API → muxed + adaptive mp4/webm ladder (desktop used yt-dlp for this)",
    )

    val ADAPTERS = listOf(APARAT, FILIMO, YOUTUBE)
    private val BY_KEY = ADAPTERS.associateBy { it.key }

    // ── helpers ───────────────────────────────────────────────────────────
    private val PERSIAN_UNITS = listOf("کیلو" to 1024L, "مگا" to 1024L * 1024, "گیگا" to 1024L * 1024 * 1024)

    /** Parse '83.85 مگابایت' / '176 MB' / '8,62 GB' into bytes. */
    fun sizeBytes(text: String?): Long? {
        if (text.isNullOrEmpty()) return null
        val m = Regex("(\\d+(?:[.,]\\d+)?)").find(text) ?: return null
        val num = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return null
        val low = text.lowercase(Locale.ROOT)
        var mult = 1L
        var matched = false
        for ((word, value) in PERSIAN_UNITS) {
            if (text.contains(word)) {
                mult = value
                matched = true
                break
            }
        }
        if (!matched) {
            mult = when {
                "tb" in low -> 1024L * 1024 * 1024 * 1024
                "gb" in low -> 1024L * 1024 * 1024
                "mb" in low -> 1024L * 1024
                "kb" in low -> 1024L
                else -> 1L
            }
        }
        return if (num > 0) (num * mult).toLong() else null
    }

    private fun extOf(url: String): String {
        val ext = FileKind.urlExtension(url).trimStart('.').lowercase(Locale.ROOT)
        return ext.ifEmpty { "mp4" }
    }

    fun format(url: String, height: Int = 0, label: String = "", size: Long? = null, ext: String = ""): MutableMap<String, Any?> {
        val e = ext.ifEmpty { extOf(url) }
        return linkedMapOf(
            "height" to height,
            "label" to label.ifEmpty { if (height > 0) "${height}p" else e.uppercase(Locale.ROOT) },
            "codecs" to mutableListOf(e),
            "fps" to 0,
            "format" to "url",
            "url" to url,
            "size" to size,
            "ext" to e,
        )
    }

    fun dedupe(formats: List<MutableMap<String, Any?>>): MutableList<MutableMap<String, Any?>> {
        val seen = HashSet<String>()
        val out = ArrayList<MutableMap<String, Any?>>()
        for (f in formats) {
            val u = f["url"].str()
            if (!seen.add(u)) continue
            out.add(f)
        }
        out.sortWith(compareByDescending<MutableMap<String, Any?>> { it["height"].int() }
            .thenBy { it["size"].num() })
        return out
    }

    private fun baseCard(kind: String, url: String, title: String, site: String,
                         thumbnail: String = "", referer: String = "", player: String = "",
                         note: String = ""): MutableMap<String, Any?> {
        val card = linkedMapOf<String, Any?>(
            "kind" to kind,
            "url" to url,
            "file_type" to "video",
            "title" to title.ifEmpty { FileKind.guessFilename(url, "video") },
            "site" to site,
            "player" to player.ifEmpty { "$site API" },
            "thumbnail" to thumbnail,
            "referer" to referer.ifEmpty { url },
            "found_on_page" to referer.ifEmpty { url },
        )
        if (note.isNotEmpty()) card["access_note"] = note
        return card
    }

    // ── Aparat ────────────────────────────────────────────────────────────
    val APARAT_ENDPOINTS = listOf(
        "https://www.aparat.com/etc/api/video/videohash/{id}",
        "https://www.aparat.com/api/fa/v1/video/video/show/videohash/{id}?pr=1&mf=1&referer=direct",
    )

    /** Aparat's videohash JSON → a full mp4 quality ladder. */
    fun parseAparat(payload: Any?, pageUrl: String): MutableMap<String, Any?>? {
        var video = payload.asMap()?.get("video")
        if (video.asMap() == null) {
            val data = payload.asMap()?.get("data")
            video = data.asMap()?.get("attributes")
        }
        val v = video.asMap()
        if (v == null || v.isEmpty()) return null

        val formats = ArrayList<MutableMap<String, Any?>>()
        for (entryRaw in v["file_link_all"].list()) {
            val entry = entryRaw.asMap() ?: continue
            val profile = entry["profile"].str().ifEmpty { entry["text"].str() }
            val q = Embed.qualityOf(profile)
            val size = sizeBytes(entry["size"].str())
            var urls = entry["urls"]
            val urlList = if (urls is List<*>) urls.list() else mutableListOf(urls)
            for (raw in urlList) {
                val s = raw.str()
                if (!s.startsWith("http")) continue
                formats.add(format(s, height = q.height, label = q.label.ifEmpty { profile }, size = size))
            }
        }
        if (formats.isEmpty()) {
            val single = v["file_link"].str()
            if (single.isNotEmpty()) {
                val q = Embed.qualityOf(single)
                formats.add(format(single, height = q.height, label = q.label))
            }
        }
        if (formats.isEmpty()) return null
        val deduped = dedupe(formats)

        val title = v["title"].str().trim().ifEmpty { FileKind.guessFilename(pageUrl, "Aparat video") }
        val thumb = v["big_poster"].str().ifEmpty { v["small_poster"].str() }
        val card = baseCard(
            "webmedia", pageUrl, title, "Aparat", thumbnail = thumb, referer = pageUrl,
            player = "Aparat API",
            note = "Aparat publishes these CDN files for guests; if a link expires, analyze the page again.",
        )
        val cost = v["cost_type"].asMap()
        if (cost != null && cost["type"].str().lowercase(Locale.ROOT) !in listOf("", "free")) {
            card["access_note"] = "This Aparat video is paywalled (${cost["type"].str()}). " +
                "The CDN links are signed for the current visitor — if they refuse to play, " +
                "add a logged-in cookies.txt in Settings → Video & capture."
        } else if (v.containsKey("can_download") && v["can_download"] == false) {
            card["access_note"] = "The uploader switched Aparat's own Download button off. " +
                "The public stream files are still listed here, so you can grab the quality you want."
        }
        card["formats"] = deduped
        card["formats_total"] = deduped.size
        card["duration"] = v["duration"].int()
        val uploader = v["sender_name"].str().ifEmpty { v["username"].str() }
        if (uploader.isNotEmpty()) card["uploader"] = uploader
        return card
    }

    // ── Filimo ────────────────────────────────────────────────────────────
    const val FILIMO_ENDPOINT = "https://www.filimo.com/api/fa/v1/partner/TV/watch/uid/{id}"
    val FILIMO_HEADERS = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Useragent" to """{"an":"Filimo","os":"tv"}""",
    )

    /** Filimo's TV-partner JSON → the signed HLS manifest for this item. */
    fun parseFilimo(payload: Any?, pageUrl: String): MutableMap<String, Any?>? {
        val data = payload.asMap()?.get("data").asMap() ?: return null
        val attrs = data["attributes"].asMap()
        if (attrs == null || attrs.isEmpty()) return null

        val streams = ArrayList<Pair<String, String>>()
        val rawSrc = attrs["movie_src"]
        val groups: List<Any?> = if (rawSrc is List<*>) rawSrc.list() else mutableListOf(rawSrc)
        for (group in groups) {
            val items: List<Any?> = if (group is List<*>) group.list() else mutableListOf(group)
            for (item in items) {
                val im = item.asMap() ?: continue
                val src = im["src"]
                if (src is String) streams.add(src to im["type"].str())
            }
        }
        for (key in listOf("movie_src_url", "src", "hls_url", "m3u8")) {
            val value = attrs[key].str()
            if (value.startsWith("http")) streams.add(value to "")
        }
        if (streams.isEmpty()) return null

        fun isManifest(pair: Pair<String, String>): Boolean {
            val (url, kind) = pair
            return "mpegurl" in kind.lowercase(Locale.ROOT) ||
                ".m3u8" in url.lowercase(Locale.ROOT) || ".mpd" in url.lowercase(Locale.ROOT)
        }

        val manifest = streams.firstOrNull { isManifest(it) }
        val mp4s = streams.filter { !isManifest(it) && it.first.startsWith("http") }

        var title = attrs["movie_title"].str().ifEmpty { attrs["movie_title_en"].str() }.trim()
        if (title.isEmpty()) title = FileKind.guessFilename(pageUrl, "Filimo video")
        val thumb = attrs["pic"].str()

        var note = ""
        val pseudo = attrs["moviePseudo"].asMap()
        if (pseudo != null && pseudo["type"].str().lowercase(Locale.ROOT) == "error") {
            note = "Filimo marks this title as subscriber-only. The manifest below is the real, " +
                "time-signed stream: to download it, add a logged-in cookies.txt in Settings → " +
                "Video & capture (the API itself answered anonymously)."
        }
        val watch = attrs["watch_action"].asMap()
        if (note.isEmpty() && watch != null && watch["type"].str().lowercase(Locale.ROOT) == "login") {
            note = "Filimo wants a signed-in session for playback. The stream URL is valid; " +
                "add a logged-in cookies.txt in Settings → Video & capture to download it."
        }

        val card = baseCard(
            "webmedia", pageUrl, title, "Filimo", thumbnail = thumb, referer = pageUrl,
            player = "Filimo API", note = note,
        )
        card["duration"] = attrs["duration"].int()
        if (attrs["movie_title_en"].str().isNotEmpty() && attrs["movie_title"].str().isNotEmpty()) {
            card["title_en"] = attrs["movie_title_en"].str()
        }
        if (manifest != null) {
            card["kind"] = "hls"
            card["url"] = manifest.first
            card["stream"] = manifest.first
            card["formats"] = ArrayList<Any?>()
            card["formats_total"] = 0
            if (mp4s.isNotEmpty()) {
                card["also_found"] = mp4s.take(8).map {
                    linkedMapOf<String, Any?>(
                        "url" to it.first,
                        "filename" to FileKind.guessFilename(it.first, "video.mp4"),
                        "file_type" to "video",
                        "size_bytes" to null,
                    )
                }
            }
            return card
        }
        val formats = dedupe(mp4s.map { format(it.first, height = Embed.qualityOf(it.first).height) })
        if (formats.isEmpty()) return null
        card["formats"] = formats
        card["formats_total"] = formats.size
        return card
    }

    // ── fetchers + registry ───────────────────────────────────────────────
    private fun fetchJson(url: String, headers: Map<String, String> = emptyMap()): Any? {
        val (text, _, _) = Net.fetchText(
            url,
            headers = mapOf("Accept" to "application/json, text/plain, */*") + headers,
            timeoutSec = 16,
        )
        if (text.isEmpty()) return null
        var body = text.trim()
        if (body.isEmpty() || (body[0] != '[' && body[0] != '{')) {
            val candidates = listOf(body.indexOf('{'), body.indexOf('[')).filter { it >= 0 }
            if (candidates.isEmpty()) return null
            body = body.substring(candidates.min())
        }
        return Json.parseOrNull(body)
    }

    fun forUrl(url: String): Pair<Adapter, java.util.regex.Matcher>? {
        for (a in ADAPTERS) {
            val m = a.match.matcher(url)
            if (m.find()) return a to m
        }
        return null
    }

    fun adapterForRecipe(key: String): Adapter? = BY_KEY[key]

    /** Try the API bridge for this URL. Never throws — analysis must not die here. */
    fun resolve(url: String, priorityOnly: Boolean = false): MutableMap<String, Any?>? {
        val (adapter, match) = forUrl(url) ?: return null
        if (priorityOnly && !adapter.priority) return null
        return try {
            when (adapter.key) {
                "aparat" -> {
                    val id = match.group("id")
                    var card: MutableMap<String, Any?>? = null
                    for (template in APARAT_ENDPOINTS) {
                        card = parseAparat(fetchJson(template.replace("{id}", id)), url)
                        if (card != null) break
                    }
                    card?.let { it["adapter"] = adapter.key }
                    card
                }
                "youtube" -> {
                    val card = Youtube.card(url)
                    card?.let { it["adapter"] = adapter.key }
                    // a refusal is only final when nothing else can try the URL
                    if (card != null && card["kind"].str() == "media_error" && Ytdlp.available()) null else card
                }
                "filimo" -> {
                    val card = parseFilimo(fetchJson(FILIMO_ENDPOINT.replace("{id}", match.group("id")), FILIMO_HEADERS), url)
                    card?.let { it["adapter"] = adapter.key }
                    card
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun catalog(): List<MutableMap<String, Any?>> = ADAPTERS.map {
        linkedMapOf<String, Any?>("key" to it.key, "site" to it.site,
            "priority" to it.priority, "note" to it.note)
    }
}
