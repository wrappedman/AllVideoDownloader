package com.allvideodownloader.avd.core

import java.util.Locale
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Android port of `webfinder/core/media.py` — the universal link engine.
 *
 * `analyze(url)` is the heart of the app: ANY link that contains a video becomes
 * a downloadable job with a quality ladder you can pick from.
 *
 * ```
 * media    → yt-dlp extractors (via the CLI bridge): metadata + real ladders
 * hls      → .m3u8 / .mpd manifests: variant ladder parsed locally
 * direct   → a real file, verified by content-type AND container magic bytes
 * page     → ordinary web page: deep-scanned; found video is re-analyzed
 * webmedia → the page's own PLAYER CONFIG lists the files (quality chips)
 * magnet   → recognised and explained
 * ```
 *
 * The resolution order, every rescue pass and every honest error message is the
 * desktop pipeline's; the only stage that changes is the yt-dlp call itself,
 * which cannot be an embedded Python module on Android and therefore uses the
 * same "drive a yt-dlp binary" path the desktop app already prefers when the
 * user's own CLI is fresher than the bundled module (see [YtCli]).
 */
object Media {

    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0 Safari/537.36 AVD/2.2"

    private val QUALITY_ORDER = listOf(4320, 2880, 2160, 1440, 1080, 720, 480, 360, 240, 144)

    private val STREAM_EXTS = listOf("m3u8", "mpd")
    private val PLAYER_ORIGINS = setOf("og", "video", "embed", "recipe")
    private val PREVIEW_RE = Pattern.compile(
        "thumb|poster|sprite|preview|trailer|sample|placeholder|logo|banner",
        Pattern.CASE_INSENSITIVE,
    )
    private val LABELLED_QUALITIES = setOf("High", "Low", "HD", "SD", "Alt", "Alt HD", "HLS", "FLV", "DASH")
    private val PLAYER_FAMILIES = setOf(
        "html5player", "kt_player", "jwplayer", "videojs", "plyr",
        "clappr", "flowplayer", "dplayer", "shaka",
    )

    fun friendlyErr(msg: String): String = YtCli.friendly(msg).take(400)

    fun findExtractor(url: String): String = YtCli.findExtractor(url)

    // ── manifest ladders ──────────────────────────────────────────────────
    private val M3U8_STREAM_RE = Pattern.compile(
        "#EXT-X-STREAM-INF:([^\\n]*)\\n\\s*([^#\\n][^\\n]*)", Pattern.CASE_INSENSITIVE,
    )

    /** Variant ladder from a master playlist: RESOLUTION/BANDWIDTH per entry. */
    fun parseM3u8Master(text: String, base: String): List<MutableMap<String, Any?>> {
        val out = ArrayList<MutableMap<String, Any?>>()
        val m = M3U8_STREAM_RE.matcher(text)
        var i = 0
        while (m.find()) {
            val attrs = m.group(1)
            val uri = m.group(2)
            val hm = Regex("RESOLUTION=(\\d+)x(\\d+)").find(attrs)
            val bw = Regex("BANDWIDTH=(\\d+)").find(attrs)
            out.add(linkedMapOf(
                "idx" to i,
                "url" to UrlKit.join(base, uri.trim()),
                "height" to (hm?.groupValues?.get(2)?.toIntOrNull() ?: 0),
                "width" to (hm?.groupValues?.get(1)?.toIntOrNull() ?: 0),
                "bandwidth" to (bw?.groupValues?.get(1)?.toLongOrNull() ?: 0L),
            ))
            i++
        }
        out.sortWith(compareByDescending<MutableMap<String, Any?>> { it["height"].int() }
            .thenByDescending { it["bandwidth"].num() })
        return out
    }

    /** Video representations from a DASH MPD (AdaptationSet → Representation). */
    fun parseMpdMaster(text: String, base: String): List<MutableMap<String, Any?>> {
        val out = ArrayList<MutableMap<String, Any?>>()
        val cleaned = text.replaceFirst(
            Regex("""\sxmlns(:[^=]+)?=["'][^"']*["']"""),
            "",
        )
        val doc = try {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = false
            runCatching { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            f.newDocumentBuilder().parse(cleaned.byteInputStream())
        } catch (_: Exception) {
            return out
        }

        fun children(node: Node, tag: String): List<Element> {
            val list = ArrayList<Element>()
            val kids = node.childNodes
            for (i in 0 until kids.length) {
                val k = kids.item(i)
                if (k is Element && k.tagName == tag) list.add(k)
            }
            return list
        }

        fun walk(node: Node) {
            for (aset in children(node, "AdaptationSet")) {
                val kind = (aset.getAttribute("contentType").ifEmpty { aset.getAttribute("mimeType") })
                    .lowercase(Locale.ROOT)
                if (kind.startsWith("audio")) continue
                for (rep in children(aset, "Representation")) {
                    var w = rep.getAttribute("width").toIntOrNull() ?: 0
                    if (w == 0) w = aset.getAttribute("width").toIntOrNull() ?: 0
                    var h = rep.getAttribute("height").toIntOrNull() ?: 0
                    if (h == 0) h = aset.getAttribute("height").toIntOrNull() ?: 0
                    val bw = rep.getAttribute("bandwidth").toLongOrNull() ?: 0L
                    val rid = rep.getAttribute("id")
                    var ouri = ""
                    val bu = children(rep, "BaseURL")
                    if (bu.isNotEmpty()) {
                        val t = (bu[0].textContent ?: "").trim()
                        if (t.isNotEmpty()) ouri = UrlKit.join(base, t)
                    }
                    if (ouri.isEmpty()) continue
                    out.add(linkedMapOf(
                        "idx" to out.size, "url" to ouri, "height" to h, "width" to w,
                        "bandwidth" to bw, "rep_id" to rid,
                    ))
                }
            }
            for (period in children(node, "Period")) walk(period)
        }
        walk(doc.documentElement)
        out.sortWith(compareByDescending<MutableMap<String, Any?>> { it["height"].int() }
            .thenByDescending { it["bandwidth"].num() })
        return out
    }

    /** UI-ready quality picker for a raw manifest (auto + one entry per rung). */
    fun streamFormats(ladder: List<MutableMap<String, Any?>>): List<MutableMap<String, Any?>> {
        val formats = ArrayList<MutableMap<String, Any?>>()
        formats.add(linkedMapOf(
            "height" to 0, "label" to "Best (auto)", "codecs" to ArrayList<String>(),
            "fps" to 0, "format" to "bv*+ba/b", "url" to "", "size" to null,
        ))
        for (v in ladder) {
            val h = v["height"].int()
            val idx = v["idx"].int()
            var sel = "$idx/${idx + 1}"
            val rep = v["rep_id"].str()
            if (rep.isNotEmpty()) sel = "$rep/$sel"
            sel += "/best" + if (h > 0) "[height<=$h]" else ""
            val bw = v["bandwidth"].long()
            val label = (if (h > 0) "${h}p" else "") +
                (if (bw > 0) " · ${bw / 1000} kbps" else "")
            formats.add(linkedMapOf(
                "height" to h, "label" to label.ifEmpty { "variant" },
                "codecs" to ArrayList<String>(), "fps" to 0, "format" to sel,
                "url" to v["url"], "size" to null,
            ))
        }
        return formats
    }

    fun fetchManifest(url: String, referer: String = ""): String = Net.fetchManifestText(url, referer)

    /** HLS/DASH → probe info when available + a locally parsed variant ladder. */
    fun streamResult(url: String, referer: String = ""): MutableMap<String, Any?> {
        var ladder: List<MutableMap<String, Any?>> = emptyList()
        try {
            val text = fetchManifest(url, referer)
            if (url.lowercase(Locale.ROOT).endsWith(".mpd") || "<mpd" in text.take(600).lowercase(Locale.ROOT)) {
                ladder = parseMpdMaster(text, url)
            } else if ("#EXTM3U" in text.take(40) || "#EXT-X-STREAM-INF" in text.take(6000)) {
                ladder = parseM3u8Master(text, url)
            }
        } catch (_: Exception) {
            ladder = emptyList()
        }
        val info = Ffmpeg.probe(url, if (referer.isNotEmpty()) mapOf("Referer" to referer) else emptyMap())
        return linkedMapOf(
            "kind" to "hls", "url" to url, "file_type" to "video",
            "title" to FileKind.guessFilename(url, "stream"),
            "ffmpeg" to Ffmpeg.find().available,
            "duration" to info["duration"], "streams" to info["streams"],
            "bit_rate" to info["bit_rate"],
            "formats" to streamFormats(ladder),
            "single_stream" to (ladder.isEmpty()),
        )
    }

    // ── quality ladders built from a page's own player config ─────────────
    /**
     * Rung order when the resolution itself is unknown: a slot named "High"
     * belongs above a slot named "Low", and the player's unnamed base file
     * sits between them.
     */
    private fun labelRank(label: String): Int {
        val l = label.lowercase(Locale.ROOT)
        return when {
            "4k" in l || "uhd" in l || "2160" in l -> 60
            "1080" in l || "fhd" in l -> 50
            "high" in l || "hd" in l -> 45
            "720" in l -> 40
            "540" in l || "480" in l -> 30
            "360" in l || "270" in l || "240" in l || "144" in l -> 10
            "low" in l || "sd" in l -> 20
            else -> 25
        }
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var u = -1
        do { v /= 1024; u++ } while (v >= 1024 && u < units.size - 1)
        return if (v >= 100) "${v.toInt()} ${units[u]}" else String.format(Locale.ROOT, "%.1f %s", v, units[u])
    }

    private fun sameVideo(vids: List<MutableMap<String, Any?>>): List<MutableMap<String, Any?>> {
        if (vids.size < 2) return vids
        val top = vids.maxByOrNull { it["score"].int() } ?: return vids
        val home = UrlKit.dirOf(top["url"].str())
        return vids.filter { c ->
            UrlKit.dirOf(c["url"].str()) == home ||
                c["height"].int() > 0 ||
                c["quality"].str() in LABELLED_QUALITIES ||
                c["ext"].str() in STREAM_EXTS
        }
    }

    private fun playerFormats(cands: List<MutableMap<String, Any?>>): MutableList<MutableMap<String, Any?>> {
        val forms = ArrayList<MutableMap<String, Any?>>()
        val seen = HashSet<String>()
        for (c in cands) {
            val u = c["url"].str()
            if (u.isEmpty() || !seen.add(u)) continue
            val ext = (c["ext"].str().ifEmpty { FileKind.urlExtension(u) }.ifEmpty { "mp4" })
                .lowercase(Locale.ROOT).trimStart('.')
            val h = c["height"].int()
            val label = c["quality"].str().ifEmpty { if (h > 0) "${h}p" else ext.uppercase(Locale.ROOT) }
            forms.add(linkedMapOf(
                "height" to h, "label" to label, "codecs" to mutableListOf(ext), "fps" to 0,
                "format" to "url", "url" to u, "size" to c["size_bytes"],
                "ext" to ext, "source" to c["source"].str(),
            ))
        }
        // two rungs must never look identical: fall back to the size, then to a
        // number, so "MP4 | MP4" becomes something the user can actually choose
        val used = HashMap<String, Int>()
        for (f in forms) {
            val base = f["label"].str().ifEmpty { f["ext"].str().uppercase(Locale.ROOT) }
            val n = (used[base.lowercase(Locale.ROOT)] ?: 0) + 1
            used[base.lowercase(Locale.ROOT)] = n
            if (n > 1) {
                val size = f["size"].num().toLong()
                f["label"] = if (size > 0) "$base · ${humanSize(size)}" else "$base · $n"
            }
        }
        forms.sortWith(Comparator { a, b ->
            val sa = if (a["ext"].str() in STREAM_EXTS) 0 else 1
            val sb = if (b["ext"].str() in STREAM_EXTS) 0 else 1
            if (sa != sb) return@Comparator sa - sb
            // a word-only rung ("High", "HD") has no height, so the label decides
            // the order; identical words are settled by the real resolution
            val ra = labelRank(a["label"].str())
            val rb = labelRank(b["label"].str())
            if (ra != rb) return@Comparator rb - ra
            b["height"].int() - a["height"].int()
        })
        return forms
    }

    /**
     * A download card built from ≥2 file variants found in a player config.
     *
     * Only *player-derived* candidates qualify: a directory listing full of
     * different movies must never be sold as "qualities of one video".
     */
    fun filesCard(
        pageUrl: String,
        cands: List<MutableMap<String, Any?>>,
        title: String = "",
        site: String = "",
        thumbnail: String = "",
        player: String = "",
        referer: String = "",
        note: String = "",
    ): MutableMap<String, Any?>? {
        var vids = cands.filter { c ->
            c["file_type"].str() in listOf("video", "audio") &&
                (c["origin"].str().ifEmpty { c["source"].str() }) in PLAYER_ORIGINS &&
                !PREVIEW_RE.matcher(c["url"].str()).find() &&
                c["quality"].str() != "Preview"
        }
        vids = sameVideo(vids)
        val forms = playerFormats(vids)
        if (forms.size < 2) return null
        val used = forms.map { it["url"].str() }.toSet()
        val rest = cands.filter { it["url"].str() !in used }.take(12)
        val payload = linkedMapOf<String, Any?>(
            "kind" to "webmedia", "url" to pageUrl, "file_type" to "video",
            "title" to title.ifEmpty { FileKind.guessFilename(pageUrl, "video") },
            "site" to site, "player" to player.ifEmpty { "embedded player" },
            "thumbnail" to thumbnail, "referer" to referer.ifEmpty { pageUrl },
            "found_on_page" to pageUrl, "formats" to forms,
            "formats_total" to forms.size,
            "ffmpeg" to Ffmpeg.find().available,
            "needs_ffmpeg_merge" to !Ffmpeg.find().available,
        )
        if (note.isNotEmpty()) payload["recovered_after_block"] = note
        if (rest.isNotEmpty()) payload["also_found"] = rest
        return payload
    }

    /** Player-config rescue for a capped extractor map. */
    fun betterFromPage(url: String, media: Map<String, Any?>): MutableMap<String, Any?>? {
        val recipe = Sites.recipeFor(url) ?: return null
        if (recipe.family !in PLAYER_FAMILIES) return null
        val best = media["formats"].list().maxOfOrNull { it.asMap()?.get("height").int() ?: 0 } ?: 0
        if (best >= 720) return null
        val info = LinkedHashMap<String, Any?>()
        val cands = try {
            scanPage(url, 24, info = info)
        } catch (_: Exception) {
            return null
        }
        val card = filesCard(
            url, cands,
            title = media["title"].str().ifEmpty { info["title"].str() },
            site = recipe.name,
            thumbnail = info["thumbnail"].str(),
            player = info["player"].str(),
        ) ?: return null
        val top = card["formats"].list().maxOfOrNull { it.asMap()?.get("height").int() ?: 0 } ?: 0
        if (top <= best) return null
        card["upgraded_from_extractor"] = "${media["extractor"].str().ifEmpty { "yt-dlp" }} offered " +
            "only up to ${best}p, so the app read the page's own player config and found ${top}p"
        return card
    }

    /** Ask a site's own API (Aparat, Filimo…) for the stream it publishes. */
    fun adapterCard(url: String, refererIn: String = ""): MutableMap<String, Any?>? {
        val card = Adapters.resolve(url) ?: return null
        var out = card
        val site = out["site"].str().ifEmpty { Sites.siteName(url) }
        if (out["kind"].str() == "hls" && out["formats"].list().isEmpty()) {
            val stream = out.remove("stream")?.str()?.ifEmpty { out["url"].str() } ?: url
            val referer = out["referer"].str().ifEmpty { refererIn.ifEmpty { url } }
            val base = try {
                streamResult(stream, referer)
            } catch (_: Exception) {
                linkedMapOf<String, Any?>(
                    "kind" to "hls", "url" to stream, "file_type" to "video",
                    "title" to out["title"].str().ifEmpty { FileKind.guessFilename(stream, "stream") },
                    "formats" to mutableListOf(linkedMapOf<String, Any?>(
                        "height" to 0, "label" to "Best (auto)",
                        "codecs" to ArrayList<String>(), "fps" to 0,
                        "format" to "bv*+ba/b", "url" to "", "size" to null,
                    )),
                    "single_stream" to true,
                )
            }
            val merged = LinkedHashMap(base)
            for ((k, v) in out) {
                if (k == "kind" || k == "url") continue
                if (v == null || (v is String && v.isEmpty()) ||
                    (v is Collection<*> && v.isEmpty()) || (v is Map<*, *> && v.isEmpty())
                ) continue
                merged[k] = v
            }
            merged["kind"] = "hls"
            merged["url"] = stream
            merged["referer"] = referer
            merged["found_on_page"] = out["found_on_page"].str().ifEmpty { url }
            merged["formats_total"] = merged["formats"].list().size
            out = merged
        }
        if (out["site"].str().isEmpty()) out["site"] = site
        if (out["kind"].str() == "webmedia") {
            if (out["referer"].str().isEmpty()) out["referer"] = refererIn.ifEmpty { url }
            if (out["found_on_page"].str().isEmpty()) out["found_on_page"] = url
            if (!out.containsKey("formats_total")) out["formats_total"] = out["formats"].list().size
            if (!out.containsKey("ffmpeg")) out["ffmpeg"] = Ffmpeg.find().available
            if (!out.containsKey("needs_ffmpeg_merge")) out["needs_ffmpeg_merge"] = !Ffmpeg.find().available
        }
        return out
    }

    // ── the analyzer ──────────────────────────────────────────────────────
    fun analyze(urlIn: String, depth: Int = 0, refererIn: String = ""): MutableMap<String, Any?> {
        var url = urlIn.trim()
        if (!Regex("^[a-z]+://", RegexOption.IGNORE_CASE).containsMatchIn(url) &&
            !url.startsWith("magnet:")
        ) url = "https://$url"

        if (url.lowercase(Locale.ROOT).startsWith("magnet:")) {
            return linkedMapOf(
                "kind" to "magnet", "url" to url,
                "title" to magnetTitle(url), "file_type" to "magnet",
            )
        }

        val ext = FileKind.urlExtension(url)
        if (ext in FileKind.STREAM_EXTS) return streamResult(url, refererIn)

        // 0) Sites whose stream only exists behind a JSON endpoint (Aparat, Filimo,
        //    YouTube — the last one is what the desktop build used yt-dlp for)
        adapterCard(url, refererIn)?.let { return it }

        // 1) yt-dlp extractor registry — YouTube & friends
        val isFile = ext in FileKind.VIDEO_EXTS || ext in FileKind.AUDIO_EXTS
        var media: MutableMap<String, Any?>? = null
        var ytErr = ""
        if (!isFile) {
            val r = tryYtdlp(url)
            media = r.first
            ytErr = r.second
        }
        if (media != null && media["formats"].list().isNotEmpty()) {
            val upgraded = try {
                betterFromPage(url, media)
            } catch (_: Exception) {
                null
            }
            return upgraded ?: media
        }

        // 1b) A registered extractor exists but extraction failed
        val ie = findExtractor(url)
        if (ie.isNotEmpty()) {
            val pageInfo = LinkedHashMap<String, Any?>()
            val cand = try {
                scanPage(url, 24, info = pageInfo)
            } catch (_: Exception) {
                ArrayList()
            }
            val vids = cand.filter {
                FileKind.classify(it["url"].str()) == "video" ||
                    FileKind.urlExtension(it["url"].str()) in FileKind.VIDEO_EXTS ||
                    FileKind.urlExtension(it["url"].str()) in FileKind.STREAM_EXTS
            }
            val emb = cand.filter { it["file_type"].str() == "embed" }
            val card = try {
                filesCard(
                    url, cand,
                    title = pageInfo["title"].str(),
                    site = pageInfo["site"].str(),
                    thumbnail = pageInfo["thumbnail"].str(),
                    player = pageInfo["player"].str(),
                    note = "$ie extractor refused the page, so the app served the files " +
                        "the page's own player config exposes",
                )
            } catch (_: Exception) {
                null
            }
            if (card != null) {
                card["blocked_extractor"] = ie
                if (ytErr.isNotEmpty()) card["extractor_error"] = friendlyErr(ytErr)
                return card
            }
            val targets = vids + emb
            for (t in targets.take(4)) {
                val sub = try {
                    analyze(t["url"].str(), 2, url)
                } catch (_: Exception) {
                    continue
                }
                if (sub["kind"].str() !in listOf("page", "magnet", "media_error")) {
                    sub["recovered_after_block"] = "$ie extractor refused the page, so the app " +
                        "served the file the page itself links"
                    sub["found_on_page"] = url
                    sub["referer"] = url
                    val rest = targets.filter { it !== t }.take(11)
                    if (rest.isNotEmpty()) sub["also_found"] = rest
                    return sub
                }
            }
            val hints = engineHints(pageInfo, ytErr)
            if (pageInfo["site"].str().isNotEmpty()) {
                hints.add("<b>${pageInfo["site"].str()}</b> is in the built-in recipe list, so this " +
                    "refusal came from the site itself (region/age wall, removed video, or a " +
                    "player change) — not from a missing algorithm.")
            }
            hints.add("Bot walls (Cloudflare “Just a moment”, 403 to non-browsers) can be defeated " +
                "by TLS impersonation, which this Android build does not have — the page scan " +
                "and the player-config engine below still work without it.")
            return linkedMapOf(
                "kind" to "media_error", "url" to url, "extractor" to ie,
                "file_type" to "video", "site" to pageInfo["site"].str(),
                "title" to pageInfo["title"].str().ifEmpty { FileKind.guessFilename(url, ie) },
                "error" to friendlyErr(ytErr.ifEmpty { "yt-dlp returned no info" }),
                "hints" to hints,
                "candidates" to (if ((vids + emb).isNotEmpty()) (vids + emb) else cand),
            )
        }

        // 2) Probe the URL itself — content-type AND magic bytes
        var final = url
        var ctype = ""
        var size: Long? = null
        var acceptsRanges = false
        try {
            val resp = Net.probe(url)
            ctype = resp.header("content-type").lowercase(Locale.ROOT)
            val cl = resp.header("content-length")
            val crange = resp.header("content-range")
            if (crange.contains("/")) size = crange.substringAfterLast('/').toLongOrNull()
            if (size == null && cl.isNotEmpty() && cl.toLongOrNull() != null && cl.toLong() > 1) size = cl.toLong()
            final = resp.finalUrl
            acceptsRanges = "206" == resp.status.toString() || resp.header("accept-ranges").contains("bytes")

            if ("mpegurl" in ctype || ".m3u8" in final || "dash" in ctype || ".mpd" in final) {
                return streamResult(final)
            }
            var headBytes = ByteArray(0)
            if (ext !in FileKind.EXT_KIND && ("octet-stream" in ctype || ctype.isEmpty() || "html" in ctype)) {
                headBytes = try {
                    val hr = Net.get(final, Net.browserHeaders().also { it["Range"] = "bytes=0-63" }, 15, 128)
                    hr.bytes.copyOfRange(0, minOf(64, hr.bytes.size))
                } catch (_: Exception) {
                    ByteArray(0)
                }
            }
            val sniffed = if (headBytes.size >= 12) Net.sniffContainer(headBytes) else ""
            if (sniffed.isNotEmpty() && "html" !in ctype) {
                var name = FileKind.guessFilename(final, "video.$sniffed")
                if (!name.contains('.')) name += ".$sniffed"
                return linkedMapOf(
                    "kind" to "direct", "url" to final, "title" to UrlKit.unquote(name),
                    "filename" to UrlKit.unquote(name), "file_type" to "video",
                    "content_type" to "video/$sniffed (magic-sniffed)",
                    "size" to size, "accepts_ranges" to acceptsRanges,
                )
            }
        } catch (e: Exception) {
            // The host refused a plain probe. Before declaring it unreachable, give
            // the page path and the built-in recipes a chance.
            val pageInfo = LinkedHashMap<String, Any?>()
            val cand = scanPage(url, 24, info = pageInfo)
            val card = filesCard(
                url, cand,
                title = pageInfo["title"].str(), site = pageInfo["site"].str(),
                thumbnail = pageInfo["thumbnail"].str(), player = pageInfo["player"].str(),
                referer = refererIn,
            )
            if (card != null) {
                if (refererIn.isNotEmpty()) card["referer"] = refererIn
                return card
            }
            if (cand.isNotEmpty()) {
                return linkedMapOf(
                    "kind" to "page", "url" to url, "title" to pageInfo["title"].str(),
                    "site" to pageInfo["site"].str(), "candidates" to cand,
                    "content_type" to "text/html", "size" to null,
                )
            }
            throw RuntimeException("Host unreachable: ${(e.message ?: "").take(160)}", e)
        }

        // HTML is never "a file at the end of the URL", whatever the extension says
        val looksLikeFile = "html" !in ctype &&
            (ext.isNotEmpty() || (size != null && (size > 1_000_000 || "octet-stream" in ctype)))
        if (looksLikeFile) {
            val name = UrlKit.unquote(FileKind.guessFilename(final, "file"))
            return linkedMapOf(
                "kind" to "direct", "url" to final,
                "title" to name, "filename" to name,
                "file_type" to FileKind.classify(final), "content_type" to ctype,
                "size" to size, "accepts_ranges" to acceptsRanges,
            )
        }

        // 3) Ordinary page — deep-scan for the video it actually hosts
        val pageInfo = LinkedHashMap<String, Any?>()
        val candidates = scanPage(final, 24, info = pageInfo)
        val vids = candidates.filter {
            FileKind.classify(it["url"].str()) == "video" ||
                FileKind.urlExtension(it["url"].str()) in FileKind.VIDEO_EXTS ||
                FileKind.urlExtension(it["url"].str()) in FileKind.STREAM_EXTS
        }
        val emb = candidates.filter { it["file_type"].str() == "embed" }

        val card = filesCard(
            final, candidates,
            title = pageInfo["title"].str(), site = pageInfo["site"].str(),
            thumbnail = pageInfo["thumbnail"].str(), player = pageInfo["player"].str(),
            referer = refererIn,
        )
        if (card != null) return card

        val targets = (vids + emb).toMutableList()
        if (targets.isNotEmpty() && depth < 2) {
            targets.sortWith(compareByDescending<MutableMap<String, Any?>> { it["size_bytes"].long() }
                .thenBy { if (FileKind.urlExtension(it["url"].str()) in FileKind.STREAM_EXTS) 0 else 1 })
            for (target in targets.take(3)) {
                val sub = try {
                    _referrer.set(final)
                    try {
                        analyze(target["url"].str(), depth + 1)
                    } finally {
                        _referrer.remove()
                    }
                } catch (_: Exception) {
                    continue
                }
                if (sub["kind"].str() !in listOf("page", "magnet")) {
                    sub["found_on_page"] = final
                    sub["referer"] = final
                    val rest = targets.filter { it !== target }.take(11)
                    if (rest.isNotEmpty()) sub["also_found"] = rest
                    return sub
                }
            }
        }
        // Nothing playable anywhere on the page: say why, instead of showing an
        // empty "found it" card the user cannot act on.
        if (candidates.isEmpty()) {
            return linkedMapOf(
                "kind" to "media_error", "url" to final,
                "extractor" to findExtractor(final).ifEmpty {
                    pageInfo["site"].str().ifEmpty { "page scan" }
                },
                "file_type" to "video", "site" to pageInfo["site"].str(),
                "title" to pageInfo["title"].str().ifEmpty { FileKind.guessFilename(final, "video") },
                "error" to friendlyErr(ytErr).ifEmpty {
                    "No downloadable file was found on this page."
                },
                "hints" to engineHints(pageInfo, ytErr),
                "candidates" to candidates,
            )
        }
        return linkedMapOf(
            "kind" to "page", "url" to final, "title" to pageInfo["title"].str(),
            "site" to pageInfo["site"].str(), "size" to size,
            "content_type" to ctype, "candidates" to candidates,
        )
    }

    /**
     * Why a page produced no playable file, in terms of *this* platform.
     *
     * Android cannot `pip install` and — from Android 10 — cannot execute a
     * binary that lives in app storage either, so "just install yt-dlp" is only
     * true on old devices. The hints say what actually works here: the page's own
     * player config, or a direct file link copied from a browser.
     */
    private fun engineHints(pageInfo: Map<String, Any?>, ytErr: String): MutableList<String> {
        val hints = ArrayList<String>()
        val eLow = ytErr.lowercase(Locale.ROOT)
        val engine = YtCli.detect()
        if (eLow.contains("cookie") || eLow.contains("could not copy")) {
            hints.add("The cookie source could not be read — open Settings → Video &amp; capture " +
                "and point <b>Cookies file</b> at an exported <code>cookies.txt</code>. " +
                "The app already retried this extraction without cookies.")
        }
        if (!engine["use_cli"].bool()) {
            hints.add("No yt-dlp binary was found on this device, so extractor-only sites " +
                "(YouTube &amp; friends) cannot be resolved here. Android 10+ also refuses to " +
                "execute binaries stored in app storage, so installing one only works on older " +
                "systems — the engines that do work on a phone are the page's own player config " +
                "(below) and direct <code>.mp4</code> / <code>.m3u8</code> links.")
        } else {
            hints.add("yt-dlp ${engine["version"].str()} is installed but this extraction failed — " +
                "the site may have changed its player, or it is blocking non-browser clients.")
        }
        if (pageInfo["site"].str().isNotEmpty()) {
            hints.add("<b>${pageInfo["site"].str()}</b> is in the built-in recipe list, so this " +
                "refusal came from the site itself (region/age wall, removed video, or a " +
                "player change) — not from a missing algorithm.")
        }
        hints.add("Bot walls (Cloudflare “Just a moment”, 403 to non-browsers) need TLS " +
            "impersonation, which this Android build does not have — the page scan below still " +
            "works without it.")
        hints.add("If the page plays the video in your browser, open it there, long-press the " +
            "player (or use the browser's download list) to copy the media link, then paste that " +
            "here — a direct file or manifest always downloads.")
        return hints
    }

    private fun magnetTitle(url: String): String {
        val m = Regex("dn=([^&]+)").find(url) ?: return "Magnet link"
        return UrlKit.unquote(m.groupValues[1]).replace("+", " ")
    }

    // ── yt-dlp bridge (CLI, exactly as the desktop drives a system binary) ─
    private val _referrer = ThreadLocal<String?>()

    private fun tryYtdlp(url: String): Pair<MutableMap<String, Any?>?, String> =
        Ytdlp.extract(url, referrer = _referrer.get() ?: "")

    // ── page scanning ─────────────────────────────────────────────────────
    private val OG_RE = Pattern.compile(
        """<meta[^>]+(?:property|name|itemprop)=["'](?:og:(?:video|audio|image)(?::(?:secure_url|url))?""" +
            """|twitter:player:stream|contentUrl|video_src)["'][^>]+content=["']([^"']+)["']""",
        Pattern.CASE_INSENSITIVE,
    )
    private val VIDSRC_RE = Pattern.compile(
        """<(?:video|source|audio)[^>]+(?:data-(?:src|video|hls|dash|mp4|url|file|source|stream)""" +
            """|src)=["']([^"']+)["']""",
        Pattern.CASE_INSENSITIVE,
    )
    private val IFRAME_RE = Pattern.compile("""<iframe[^>]+(?:data-)?src=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)

    private const val HREF_EXTS = "mp4|mkv|webm|m4v|avi|mov|mp3|flac|m4a|zip|rar|7z|gz|tar|exe|msi" +
        "|iso|pdf|epub|torrent|m3u8|mpd|ts|m4s"
    private val MEDIA_HREF_RE = Pattern.compile(
        """href=["']([^"']+?\.(?:$HREF_EXTS)(?:/[^"']*)?(?:\?[^"']*)?)["']""",
        Pattern.CASE_INSENSITIVE,
    )

    /**
     * Deep-scan one page for hidden direct media files: anchors, og:/<video>,
     * directory listings, iframe players — and the player configuration every
     * tube site keeps in its HTML.
     */
    fun scanPage(
        url: String,
        limit: Int = 12,
        wanted: Pattern? = null,
        referer: String = "",
        info: MutableMap<String, Any?>? = null,
    ): List<MutableMap<String, Any?>> {
        val (text, finalIn, _) = Net.fetchText(url, referer = referer, timeoutSec = 14)
        if (text.isEmpty()) return emptyList()
        val base = finalIn.ifEmpty { url }
        val seen = HashSet<String>()
        val out = ArrayList<MutableMap<String, Any?>>()

        if (info != null) {
            val meta = Embed.pageMeta(text, base)
            val recipe = Sites.recipeFor(base)
            if (!info.containsKey("title")) info["title"] = meta["title"]
            if (!info.containsKey("thumbnail")) info["thumbnail"] = meta["thumbnail"]
            info["site"] = recipe?.name ?: Sites.siteName(base)
            info["recipe"] = recipe?.key ?: ""
            info["player"] = Sites.detectFamily(text)
            info["page_url"] = base
        }

        val assetNoise = Regex("\\.(js|css|png|jpe?g|svg|ico|woff2?)(\\?|#|$)", RegexOption.IGNORE_CASE)
        val realFile = Regex(
            "\\.(mp4|mkv|webm|mov|avi|flv|m4v|zip|rar|7z|t?gz|epub|pdf|djvu|mp3|flac|ogg|m4a|wav|opus|aac)$",
            RegexOption.IGNORE_CASE,
        )

        fun push(full: String, size: Long? = null, origin: String = "anchor", item: MutableMap<String, Any?>? = null) {
            if (!full.startsWith("http://") && !full.startsWith("https://")) return
            if (full in seen) return
            if (wanted != null && !wanted.matcher(full).find()) return
            if (assetNoise.containsMatchIn(full) && !realFile.containsMatchIn(full)) return
            seen.add(full)
            val entry = item ?: linkedMapOf<String, Any?>(
                "url" to full,
                "filename" to UrlKit.unquote(FileKind.guessFilename(full, "file")),
                "file_type" to FileKind.classify(full),
                "size_bytes" to size,
            )
            entry["origin"] = origin
            out.add(entry)
        }

        OG_RE.matcher(text).let { m -> while (m.find()) push(UrlKit.join(base, Embed.unescapeHtml(m.group(1))), origin = "og") }
        VIDSRC_RE.matcher(text).let { m -> while (m.find()) push(UrlKit.join(base, Embed.unescapeHtml(m.group(1))), origin = "video") }

        IFRAME_RE.matcher(text).let { m ->
            while (m.find()) {
                val src = UrlKit.join(base, Embed.unescapeHtml(m.group(1)))
                if (!src.startsWith("http") || src in seen) continue
                if (!Sites.isEmbedUrl(src)) continue
                seen.add(src)
                val parsed = UrlKit.parse(src)
                val player = Sites.embedHostFor(src)
                out.add(linkedMapOf<String, Any?>(
                    "url" to src,
                    "filename" to (if (player.isNotEmpty()) "$player · " else "embed · ") +
                        parsed.netloc + parsed.path.take(36),
                    "file_type" to "embed", "size_bytes" to null, "origin" to "embed",
                    "player" to player, "site" to player,
                ))
            }
        }

        // player configs / escaped JSON / recipe families (before plain anchors so
        // a page full of links cannot crowd out the actual video)
        val embedded = try {
            Sites.candidates(text, base, maxOf(limit * 3, 24))
        } catch (_: Exception) {
            emptyList()
        }
        for (c in embedded) push(c["url"].str(), c["size_bytes"].long(), c["source"].str().ifEmpty { "embed" }, c)

        // plain file anchors + directory listings (name <tab> size)
        MEDIA_HREF_RE.matcher(text).let { m ->
            while (m.find()) {
                val href = Embed.unescapeHtml(m.group(1))
                if (href.startsWith("data:") || href.startsWith("javascript:")) continue
                val tail = text.substring(m.end(), minOf(text.length, m.end() + 260))
                val sm = Regex("(\\d+(?:[.,]\\d+)\\s?\\s?(?:KB|MB|GB|TB))", RegexOption.IGNORE_CASE).find(tail)
                val size = if (sm != null) FileKind.humanSizeLocal(sm.groupValues[1]) else null
                push(UrlKit.join(base, href), size, "anchor")
            }
        }
        return out.take(limit)
    }

    // ── download planning ─────────────────────────────────────────────────
    /** Return the enqueue kwargs for any URL (used by result cards & direct link). */
    fun planDownload(urlIn: String, titleIn: String = "", fmtIn: MutableMap<String, Any?>? = null): MutableMap<String, Any?> {
        var url = urlIn
        var title = titleIn
        var fmt = fmtIn
        // a `webmedia` chip carries the exact file it names — never re-analyze the page
        if (fmt != null && fmt["format"].str() == "url" && fmt["url"].str().isNotEmpty()) {
            url = fmt["url"].str()
            if (title.isEmpty()) title = fmt["label"].str()
        }
        val ext = FileKind.urlExtension(url)
        if (ext in FileKind.STREAM_EXTS) {
            fmt = fmt ?: LinkedHashMap()
            var meta: MutableMap<String, Any?> = LinkedHashMap()
            var kind = "hls"
            val haveYt = Ytdlp.available()
            val spec = fmt["format"].str()
            if (spec.isNotEmpty() && spec != "bv*+ba/b") {
                if (haveYt) {
                    kind = "ytdlp"
                    meta = linkedMapOf("format" to spec, "quality" to fmt["label"].str())
                } else if (fmt["url"].str().isNotEmpty()) {
                    kind = "hls"
                    meta = linkedMapOf("variant_url" to fmt["url"].str(), "quality" to fmt["label"].str())
                }
            } else if (haveYt) {
                kind = "ytdlp"
                meta = linkedMapOf("format" to "bv*+ba/b", "quality" to "Best (auto)")
            }
            return linkedMapOf(
                "url" to url, "kind" to kind,
                "title" to title.ifEmpty { FileKind.guessFilename(url) },
                "file_type" to "video", "meta" to meta,
            )
        }
        if (url.lowercase(Locale.ROOT).startsWith("magnet:")) {
            return linkedMapOf(
                "url" to url, "kind" to "magnet", "title" to magnetTitle(url),
                "file_type" to "magnet", "meta" to LinkedHashMap<String, Any?>(),
            )
        }
        var media: MutableMap<String, Any?>? = null
        // a URL that IS the file goes straight to the http worker
        if (ext !in FileKind.VIDEO_EXTS && ext !in FileKind.AUDIO_EXTS &&
            (ext.isEmpty() || findExtractor(url).isNotEmpty())
        ) {
            media = tryYtdlp(url).first
        }
        if (media != null) {
            val quality = Config.settings().get("default_quality")?.str() ?: "1080"
            var chosen = media["formats"].list().mapNotNull { it.asMap() }
                .firstOrNull { quality == "best" || it["height"].str() == quality }
            if (chosen == null) {
                chosen = media["formats"].list().mapNotNull { it.asMap() }.firstOrNull { it["height"].int() != 0 }
                    ?: media["formats"].list().mapNotNull { it.asMap() }.firstOrNull()
            }
            val fmtSpec = fmt?.get("format").str()?.ifEmpty { null } ?: chosen?.get("format").str()?.ifEmpty { null } ?: "bv*+ba/b"
            val container = Config.settings().getString("default_container", "mp4")
            return linkedMapOf(
                "url" to url, "kind" to "ytdlp", "title" to media["title"].str(),
                "file_type" to "video",
                "filename" to media["title"].str().replace(Regex("[<>:\"/\\\\|?*]"), "_").take(150) + ".$container",
                "meta" to linkedMapOf<String, Any?>(
                    "format" to fmtSpec,
                    "quality" to (chosen?.get("label").str() ?: "best"),
                    "extractor" to media["extractor"].str(),
                    "extractor_args" to media["extractor_args"],
                ),
            )
        }
        if (fmt != null && fmt["format"].str().isNotEmpty() && fmt["format"].str() != "url") {
            return linkedMapOf(
                "url" to url, "kind" to "ytdlp",
                "title" to title.ifEmpty { FileKind.guessFilename(url) },
                "file_type" to "video",
                "meta" to linkedMapOf<String, Any?>("format" to fmt["format"].str(), "quality" to fmt["label"].str()),
            )
        }
        val plan = linkedMapOf<String, Any?>(
            "url" to url, "kind" to "http",
            "title" to title.ifEmpty { FileKind.guessFilename(url) },
            "file_type" to fmt?.get("file_type").str().ifEmpty { FileKind.classify(url) },
            "meta" to LinkedHashMap<String, Any?>(),
        )
        // an adapter that already knows the name (YouTube's title + rung) keeps it;
        // otherwise the downloader derives one from the URL as before
        val given = fmt?.get("filename").str() ?: ""
        if (given.isNotEmpty()) plan["filename"] = given
        return plan
    }
}
