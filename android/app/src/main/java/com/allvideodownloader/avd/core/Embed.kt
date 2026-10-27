package com.allvideodownloader.avd.core

import java.util.Locale
import java.util.regex.Pattern

/**
 * Android port of `webfinder/core/embed.py` — media extraction from page source
 * and player configs.
 *
 * Most video sites do not expose an `og:video` tag or a plain file anchor; what
 * they expose is a **player configuration** somewhere in the HTML. Every dialect
 * the desktop build understands is understood here, with the same scoring, so
 * the 1080p stream outranks ads, sprites and posters exactly as before.
 */
object Embed {

    // ── URL shapes worth pulling out of a document ────────────────────────
    private const val EXTS = "mp4|m3u8|mpd|webm|mkv|mov|m4v|flv|f4v|m4s|ts|m2ts|3gp|ogv" +
        "|mp3|m4a|aac|ogg|opus|wav|flac"

    private const val TRAIL = """[^\s"'<>\\`{}|^]"""

    private const val ABS_URL = """(?:https?:)?//$TRAIL+?\.(?:$EXTS)(?:/$TRAIL*)?(?:\?$TRAIL*)?"""
    private const val REL_URL = """(?<![\w:/])(?:/$TRAIL+?\.(?:$EXTS))(?:/$TRAIL*)?(?:\?$TRAIL*)?"""

    /** Any media file URL — absolute, protocol-relative or rooted. */
    val MEDIA_URL_RE: Pattern = Pattern.compile("(?<url>$ABS_URL|$REL_URL)", Pattern.CASE_INSENSITIVE)

    /** Media served through a query parameter (no extension in the path). */
    val QUERY_EXT_RE: Pattern = Pattern.compile(
        "(?<url>(?:https?:)?//[^\\s\"'<>\\\\]{10,}?[?&](?:type|format|ext|mime|filetype)=" +
            "(?:video%2F|audio%2F)?(?:$EXTS)\\b[^\\s\"'<>\\\\]*)",
        Pattern.CASE_INSENSITIVE,
    )

    private val KV_RE = Pattern.compile(
        "(?<k>[A-Za-z_$][\\w$.$\"\\[\\]']{0,40}?)\\s*(?::|=>|=)\\s*" +
            "(?<q>[\"'])(?<v>[^\"'\\s<>\\\\]{6,900})\\k<q>",
    )

    private val VIDEO_KEY_RE = Pattern.compile(
        "video|hls|dash|stream|media|playback|movie|clip|source|file|mp4|m3u8|mpd" +
            "|manifest|contenturl|embedurl|playurl|redirect",
        Pattern.CASE_INSENSITIVE,
    )

    val ASSET_EXT_RE = Pattern.compile(
        "\\.(?:jpe?g|png|gif|webp|avif|svg|ico|bmp|tiff|css|js|mjs|jsx|json|xml|txt|html?|woff2?|ttf|eot|map|vtt|srt)(?:$|[?#])",
        Pattern.CASE_INSENSITIVE,
    )

    private val AD_HOST_RE = Pattern.compile(
        "(?:^|\\.)(?:doubleclick|googlesyndication|googleadservices|adservice|adsystem" +
            "|adnxs|exoclick|exosrv|juicyads|trafficjunky|trafficfactory|popads|popcash" +
            "|propellerads|adsterra|tsyndicate|realsrv|adspy|clickadu|hilltopads|adcash" +
            "|zergnet|outbrain|taboola|mgid|revcontent|adroll|smartadserver|smartfloralab" +
            "|dreamserve|onclickads|popmyads|adnium|poptm|trafficstars)\\.",
        Pattern.CASE_INSENSITIVE,
    )

    private val LOW_VALUE_RE = Pattern.compile(
        "thumb|poster|sprite|preview|trailer|sample|placeholder|logo|banner",
        Pattern.CASE_INSENSITIVE,
    )

    private val ATTR_PREFIX_RE = Pattern.compile(
        """(?:href|src|poster|action|data-\w+)\s*=\s*["']\s*$""",
        Pattern.CASE_INSENSITIVE,
    )

    private val HEIGHT_RE = Pattern.compile(
        "(?<![0-9A-Za-z])(2160|1440|1080|720|540|480|360|270|240|180|144)(?:[pP]|[mM]|[iI])?(?![0-9A-Za-z])",
        Pattern.CASE_INSENSITIVE,
    )

    private val DIM_RE = Pattern.compile(
        "(?<!\\d)(\\d{3,4})\\s*x\\s*(2160|1440|1080|720|540|480|360|240)(?!\\d)",
        Pattern.CASE_INSENSITIVE,
    )

    private val WORD_QUALITY: List<Pair<Pattern, Int>> = listOf(
        Pattern.compile("(?<![a-z])4k(?![a-z])|uhd", Pattern.CASE_INSENSITIVE) to 2160,
        Pattern.compile("(?<![a-z])qhd(?![a-z])", Pattern.CASE_INSENSITIVE) to 1440,
        Pattern.compile("(?<![a-z])fhd(?![a-z])", Pattern.CASE_INSENSITIVE) to 1080,
        Pattern.compile("(?<![a-z])hd(?![a-z])", Pattern.CASE_INSENSITIVE) to 720,
        Pattern.compile("(?<![a-z])sd(?![a-z])", Pattern.CASE_INSENSITIVE) to 480,
    )

    private val TITLE_RE = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val OG_TITLE_RE = Pattern.compile("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)""", Pattern.CASE_INSENSITIVE)
    private val OG_IMAGE_RE = Pattern.compile("""<meta[^>]+property=["']og:image(?::secure_url)?["'][^>]+content=["']([^"']+)""", Pattern.CASE_INSENSITIVE)

    private val META_STREAM_RE = Pattern.compile(
        """<meta\b[^>]*?(?:property|name|itemprop)\s*=\s*["']""" +
            """(?:og:video(?::(?:url|secure_url))?|twitter:player:stream|twitter:player|contentUrl|video_src)""" +
            """["'][^>]*?content\s*=\s*["']([^"']+)["']""",
        Pattern.CASE_INSENSITIVE,
    )

    private val META_STREAM_REV = Pattern.compile(
        """<meta\b[^>]*?content\s*=\s*["']([^"']+)["'][^>]*?(?:property|name|itemprop)\s*=\s*["']""" +
            """(?:og:video(?::(?:url|secure_url))?|twitter:player:stream|twitter:player|contentUrl|video_src)""" +
            """["']""",
        Pattern.CASE_INSENSITIVE,
    )

    private val LINK_STREAM_RE = Pattern.compile(
        """<link\b[^>]*?(?:rel|as|itemprop)\s*=\s*["'](?:video_src|preload|video|contentUrl)["']""" +
            """[^>]*?href\s*=\s*["']([^"']+)["']""",
        Pattern.CASE_INSENSITIVE,
    )

    private val MEDIA_ATTR_RE = Pattern.compile(
        """<(?:video|source|audio)\b[^>]*?""" +
            """(?:data-(?:src|video|hls|dash|mp4|url|file|source|stream|video-src)|src)""" +
            """\s*=\s*["']([^"']{8,})["']""",
        Pattern.CASE_INSENSITIVE,
    )

    // ── normalisation of the escaping layers sites hide URLs behind ───────
    private val ESCAPES: List<Pair<Pattern, String>> = listOf(
        Pattern.compile("\\\\/") to "/",
        Pattern.compile("\\\\u002[fF]") to "/",
        Pattern.compile("\\\\u003[aA]") to ":",
        Pattern.compile("\\\\u0026") to "&",
        Pattern.compile("\\\\u003[dD]") to "=",
        Pattern.compile("\\\\u0022") to "\"",
        Pattern.compile("\\\\u0027") to "'",
        Pattern.compile("&#x2[fF];|&#47;") to "/",
        Pattern.compile("&#x3[aA];|&#58;") to ":",
        Pattern.compile("&amp;") to "&",
    )

    fun normalize(text: String?): String {
        var out = text ?: ""
        for ((pat, repl) in ESCAPES) out = pat.matcher(out).replaceAll(java.util.regex.Matcher.quoteReplacement(repl))
        return out
    }

    // ── KVS / kt_player: the platform under a large share of tube sites ───
    private val KVS_LICENSE_RE = Pattern.compile("""license_code\s*[:=]\s*['"]\$?(?<code>\d{4,})['"]""", Pattern.CASE_INSENSITIVE)
    private val KVS_STREAM_RE = Pattern.compile("""(?<key>video_(?:url|alt_url\d*))\s*:\s*['"](?<val>[^'"]{4,900})['"]""", Pattern.CASE_INSENSITIVE)
    private val KVS_PREFIX_RE = Pattern.compile("function/[0-9]+/\\s*$", Pattern.CASE_INSENSITIVE)
    private const val KVS_TEXT_MAX = 40

    fun kvsLicenseCode(text: String?): String =
        KVS_LICENSE_RE.matcher(text ?: "").let { if (it.find()) it.group("code") else "" }

    fun kvsLicenseToken(licenseCode: String?): List<Int> {
        val code = (licenseCode ?: "").replace("$", "").trim()
        val values = code.filter { it.isDigit() }.map { it - '0' }
        if (values.isEmpty()) return emptyList()
        val mod = code.replace("0", "1")
        val center = mod.length / 2
        val front = mod.substring(0, center + 1).toIntOrNull() ?: return emptyList()
        val back = mod.substring(center).toIntOrNull() ?: return emptyList()
        val modStr = (4 * Math.abs(front - back)).toString().take(center + 1)
        val out = ArrayList<Int>()
        for ((i, ch) in modStr.withIndex()) {
            val cur = ch - '0'
            for (off in 0..3) {
                if (i + off < values.size) out.add((values[i + off] + cur) % 10)
            }
        }
        return out
    }

    /** Undo KVS's permuted path segment (`function/0/…`); unchanged otherwise. */
    fun kvsRealUrl(videoUrl: String?, licenseCode: String = ""): String {
        val url = (videoUrl ?: "").trim()
        if (!url.lowercase(Locale.ROOT).startsWith("function/")) return url
        val inner = if (url.startsWith("function/0/")) url.substring("function/0/".length)
        else url.split("/", limit = 3).lastOrNull() ?: url
        val parts = UrlKit.parse(inner)
        val pathParts = parts.path.split("/").toMutableList()
        val token = if (licenseCode.isNotEmpty()) kvsLicenseToken(licenseCode) else emptyList()
        if (token.size >= 32 && pathParts.size > 3 && pathParts[3].length >= 32) {
            val length = 32
            val hash = pathParts[3].substring(0, length)
            val indices = (0 until length).toMutableList()
            var accum = 0
            for (src in length - 1 downTo 0) {
                accum += token[src]
                val dest = (src + accum) % length
                val tmp = indices[src]
                indices[src] = indices[dest]
                indices[dest] = tmp
            }
            pathParts[3] = indices.joinToString("") { hash[it].toString() } + pathParts[3].substring(length)
        }
        return UrlKit.unparse(parts.withPath(pathParts.joinToString("/")))
    }

    /** The human label a KVS page gives this slot (`video_url_text: '720p'`). */
    fun kvsLabel(text: String, key: String, url: String): String {
        val m = Pattern.compile(
            """\b${Pattern.quote(key)}_text\s*:\s*['"]([^'"]{1,$KVS_TEXT_MAX})['"]""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(text)
        if (m.find()) return m.group(1).trim()
        val postfix = Pattern.compile("""\bpostfix\s*:\s*['"]([^'"]{0,20})['"]""", Pattern.CASE_INSENSITIVE).matcher(text)
        val found = Regex("(\\d{3,4})[pP]").find(url) ?: Regex("_(\\d{3,4})m?\\.").find(url)
        if (found != null) return "${found.groupValues[1]}p"
        return if (postfix.find()) postfix.group(1) else ""
    }

    // ── packed / obfuscated player scripts (Dean Edwards packer) ──────────
    private val PACKED_RE = Pattern.compile(
        """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[dr]\s*\)\s*\{.*?\}\s*""" +
            """\(\s*'(?<p>(?:\\.|[^'\\])*)'\s*,\s*(?<a>\d+)\s*,\s*(?<c>\d+)\s*,\s*""" +
            """'(?<k>(?:\\.|[^'\\])*)'\s*\.split\(\s*'\|'\s*\)\s*,\s*(?<d>\d+)\s*,\s*\{\s*\}\s*\)\s*\)""",
        Pattern.DOTALL,
    )

    private const val BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private val ATOB_RE = Pattern.compile(
        """(?:atob|base64decode|Base64\.decode|b64decode)\s*\(\s*["'](?<v>[A-Za-z0-9+/=]{40,4000})["']\s*\)""",
        Pattern.CASE_INSENSITIVE,
    )

    private val PCT_TOKEN_RE = Pattern.compile("[A-Za-z0-9._~%:/?#\\[\\]@!$&'()*+,;=-]{12,2400}")

    fun baseConvert(num: Int, base: Int): String {
        var n = num
        val b = base.coerceIn(2, 62)
        if (n <= 0) return "0"
        val sb = StringBuilder()
        while (n > 0) {
            sb.insert(0, BASE62[n % b])
            n /= b
        }
        return sb.toString()
    }

    /** Decode `eval(function(p,a,c,k,e,d)…)` blobs; returns "" when absent. */
    fun unpackPacked(text: String?, limit: Int = 900_000): String {
        val src = text ?: ""
        if (!src.replace(" ", "").contains("eval(function(p,a,c,k,e") || src.length > limit) return ""
        val chunks = ArrayList<String>()
        val m = PACKED_RE.matcher(src)
        while (m.find()) {
            val payload = m.group("p")
            var words = m.group("k").split("|")
            val count = m.group("c").toIntOrNull() ?: continue
            val base = m.group("a").toIntOrNull() ?: continue
            var decoded = payload
            for (i in count - 1 downTo 0) {
                if (i >= words.size || words[i].isEmpty()) continue
                decoded = Regex("\\b${Pattern.quote(baseConvert(i, base))}\\b").replace(decoded, java.util.regex.Matcher.quoteReplacement(words[i]))
            }
            chunks.add(decoded.replace("\\'", "'").replace("\\\"", "\""))
        }
        return chunks.joinToString("\n")
    }

    /** Decode `atob("…")` blobs — some players keep the config base64-encoded. */
    fun decodeAtob(text: String?, limit: Int = 400_000): String {
        val src = text ?: ""
        if (src.length > limit) return ""
        val out = ArrayList<String>()
        val m = ATOB_RE.matcher(src)
        while (m.find()) {
            val decoded = try {
                String(Util.base64Decode(m.group("v")), Charsets.UTF_8)
            } catch (_: Exception) {
                continue
            }
            if (decoded.lowercase(Locale.ROOT).contains(".mp4") || decoded.contains(".m3u8") ||
                decoded.contains(".mpd") || decoded.contains("http")
            ) out.add(decoded)
        }
        return out.joinToString("\n")
    }

    /** URL-decode percent-encoded media links (embed params, JSON payloads). */
    fun decodePercent(text: String?, limit: Int = 400_000): String {
        val src = text ?: ""
        if (src.length > limit || !src.contains('%')) return ""
        val out = ArrayList<String>()
        val m = PCT_TOKEN_RE.matcher(src)
        while (m.find()) {
            val token = m.group()
            if (!token.contains("%3") && !token.contains("%2")) continue
            val decoded = try {
                UrlKit.unquote(token)
            } catch (_: Exception) {
                continue
            }
            val at = decoded.lowercase(Locale.ROOT).indexOf("http")
            if (at < 0) continue
            val hm = MEDIA_URL_RE.matcher(decoded.substring(at))
            while (hm.find()) {
                val u = hm.group("url")
                out.add(if (u.startsWith("//")) "https:$u" else u)
            }
        }
        return out.distinct().joinToString("\n")
    }

    // ── quality inference ────────────────────────────────────────────────
    data class Quality(val height: Int, val label: String)

    fun qualityOf(url: String, label: String = ""): Quality {
        val blob = UrlKit.unquote("$label $url")
        val path = try {
            UrlKit.unquote(UrlKit.parse(url).path)
        } catch (_: Exception) {
            ""
        }
        val hay = "$path $url"
        DIM_RE.matcher(hay).let { m ->
            var found: Quality? = null
            while (m.find()) {
                found = Quality(m.group(2).toInt(), "${m.group(2)}p")
                break
            }
            if (found != null) return found
        }
        HEIGHT_RE.matcher(hay).let { m ->
            if (m.find()) {
                val h = m.group(1).toInt()
                return Quality(h, "${h}p")
            }
        }
        if (label.isNotEmpty()) {
            HEIGHT_RE.matcher(label).let { m ->
                if (m.find()) {
                    val h = m.group(1).toInt()
                    return Quality(h, "${h}p")
                }
            }
        }
        for ((pat, h) in WORD_QUALITY) {
            if (pat.matcher(blob).find() && !HEIGHT_RE.matcher(hay).find()) {
                return Quality(h, if (h >= 2160) "2160p" else "${h}p")
            }
        }
        if (label.isNotEmpty()) return Quality(0, label)
        return Quality(0, "")
    }

    fun ext(url: String): String = try {
        val byPath = FileKind.urlExtension(UrlKit.parse(url).path)
        (if (byPath.isNotEmpty()) byPath else FileKind.urlExtension(url)).trimStart('.').lowercase(Locale.ROOT)
    } catch (_: Exception) {
        ""
    }

    private fun reject(url: String): Boolean {
        val low = url.lowercase(Locale.ROOT)
        if (!low.startsWith("http://") && !low.startsWith("https://")) return true
        if (ASSET_EXT_RE.matcher(low).find()) return true
        val host = try {
            UrlKit.parse(url).netloc
        } catch (_: Exception) {
            return true
        }
        return AD_HOST_RE.matcher(host).find()
    }

    private val SCORE_BASE = mapOf(
        "m3u8" to 62, "mpd" to 58, "mp4" to 52, "webm" to 46, "mkv" to 44,
        "mov" to 40, "m4v" to 40, "f4v" to 36, "flv" to 34, "m4s" to 24, "ts" to 20,
        "mp3" to 30, "m4a" to 30,
    )
    private val SCORE_ORIGIN = mapOf("recipe" to 8, "embed" to 4, "og" to 10, "video" to 10)

    fun score(url: String, origin: String, label: String = ""): Int {
        val e = ext(url)
        var score = (SCORE_BASE[e] ?: 26) + qualityOf(url, label).height / 100
        if (LOW_VALUE_RE.matcher(url).find()) score -= 12
        runCatching { if (UrlKit.parse(url).scheme == "http") score -= 2 }
        score += SCORE_ORIGIN[origin] ?: 0
        return score
    }

    /** Turn a raw URL found in a document into an analyzer candidate. */
    fun candidate(rawIn: String?, baseUrl: String, origin: String = "embed",
                  label: String = "", licenseCode: String = ""): MutableMap<String, Any?>? {
        var raw = (rawIn ?: "").trim().trim('"', '\'')
        if (raw.isEmpty() || raw.length < 8) return null
        if (raw.startsWith("data:") || raw.startsWith("blob:") || raw.startsWith("javascript:") ||
            raw.startsWith("mailto:") || raw.startsWith("#")
        ) return null
        if (raw.lowercase(Locale.ROOT).startsWith("function/")) {
            raw = kvsRealUrl(raw, licenseCode)
            if (raw.lowercase(Locale.ROOT).startsWith("function/")) return null
        }
        var full = UrlKit.join(baseUrl, raw)
        full = Regex("[),;\\]}\"]+$").replace(full, "") // sentence punctuation
        if (reject(full)) return null
        val q = qualityOf(full, label)
        var qlabel = q.label
        if (qlabel.isEmpty() && label.isNotEmpty()) qlabel = label
        val e = ext(full)
        if (qlabel.isEmpty() && (e == "m3u8" || e == "mpd")) {
            qlabel = if (e == "m3u8") "HLS (auto)" else "DASH (auto)"
        }
        val kind = if (FileKind.classify(full) == "audio") "audio" else "video"
        var name = UrlKit.unquote(FileKind.guessFilename(full, "video.${e.ifEmpty { "mp4" }}"))
        if (!name.contains('.') && e.isNotEmpty()) name = "$name.$e"
        return linkedMapOf(
            "url" to full,
            "filename" to name,
            "file_type" to kind,
            "size_bytes" to null,
            "height" to q.height,
            "quality" to qlabel,
            "source" to origin,
            "score" to score(full, origin, label),
            "ext" to e,
        )
    }

    // ── label discovery: "quality: 1080p" sitting next to the URL ─────────
    private val NEAR_LABEL_RE = Pattern.compile(
        """["']?(?:label|quality|resolution|res|name|title|height)["']?\s*:\s*["']?([^"',}\]\n]{1,24}?)["']?\s*[,}\]\n]""",
        Pattern.CASE_INSENSITIVE,
    )
    private val NEAR_RES_RE = Pattern.compile("^(?:2160|1440|1080|720|540|480|360|270|240|180|144)\\s*[pP]?$")

    private fun asLabel(raw: String): String {
        val cand = (raw).trim()
        if (NEAR_RES_RE.matcher(cand).matches()) return if (cand.endsWith("p", true)) cand else "${cand}p"
        if (Regex("(?:4K|UHD|FHD|HD|SD|High|Low|Medium)", RegexOption.IGNORE_CASE).matches(cand)) return cand
        return ""
    }

    fun labelNear(text: String?, url: String, window: Int = 240): String {
        val src = text ?: ""
        val idx = src.indexOf(url)
        if (idx < 0) return ""
        var after = src.substring(idx, minOf(src.length, idx + window))
        val cut = after.indexOf('}')
        if (cut >= 0) after = after.substring(0, cut + 1)
        NEAR_LABEL_RE.matcher(after).let { m ->
            while (m.find()) {
                val label = asLabel(m.group(1))
                if (label.isNotEmpty()) return label
            }
        }
        val before = src.substring(maxOf(0, idx - window), idx)
        val bm = NEAR_LABEL_RE.matcher(before)
        val hits = ArrayList<String>()
        while (bm.find()) hits.add(bm.group(1))
        for (h in hits.reversed()) {
            val label = asLabel(h)
            if (label.isNotEmpty()) return label
        }
        return ""
    }

    /** All media URLs in a document, de-duplicated and best-first. */
    fun extract(text: String?, baseUrl: String, limit: Int = 48, origin: String = "embed"): List<MutableMap<String, Any?>> {
        val norm = normalize(text)
        val extra = listOf(unpackPacked(norm), decodeAtob(norm), decodePercent(norm))
            .filter { it.isNotEmpty() }.joinToString("\n")
        val scan = if (extra.isNotEmpty()) "$norm\n$extra" else norm
        val found = LinkedHashMap<String, MutableMap<String, Any?>>()

        fun add(url: String?, originArg: String = origin, label: String = "", licenseCode: String = "") {
            val c = candidate(url, baseUrl, originArg, label, licenseCode) ?: return
            val key = c["url"].str()
            val old = found[key]
            if (old == null || old["score"].int() < c["score"].int()) found[key] = c
        }

        // 1) KVS flashvars first: their "function/0/…" form must be decrypted
        //    before the plain URL pass would harvest the scrambled one.
        val licenseCode = kvsLicenseCode(scan)
        val kvsValues = HashSet<String>()
        KVS_STREAM_RE.matcher(scan).let { m ->
            while (m.find()) {
                val value = m.group("val")
                kvsValues.add(value)
                add(value, origin, kvsLabel(scan, m.group("key"), value), licenseCode)
            }
        }

        // 2) every plain media URL, absolute / protocol-relative / rooted
        for (pat in listOf(MEDIA_URL_RE, QUERY_EXT_RE)) {
            val m = pat.matcher(scan)
            while (m.find()) {
                val before = scan.substring(maxOf(0, m.start() - 40), m.start())
                if (ATTR_PREFIX_RE.matcher(before).find()) continue
                if (KVS_PREFIX_RE.matcher(before).find()) continue
                val raw = m.group("url")
                if (raw in kvsValues) continue
                add(raw)
            }
        }

        // 3) key/value pairs from JS objects and JSON (escaped or not)
        KV_RE.matcher(scan).let { m ->
            while (m.find()) {
                val key = m.group("k") ?: ""
                val value = m.group("v") ?: ""
                if (value in kvsValues) continue
                if (!VIDEO_KEY_RE.matcher(key).find() || ASSET_EXT_RE.matcher(value.lowercase(Locale.ROOT)).find()) continue
                val urlShaped = Regex("\\.(?:$EXTS)(?:$|[?#/])", RegexOption.IGNORE_CASE).containsMatchIn(value)
                if (!urlShaped && !(value.startsWith("http") || value.startsWith("//") ||
                        value.startsWith("/") || value.startsWith("function/"))
                ) continue
                add(value, origin, labelNear(scan, value), licenseCode)
            }
        }

        // 4) meta / link / <video>-attribute announcements
        META_STREAM_RE.matcher(norm).let { m -> while (m.find()) add(m.group(1)) }
        META_STREAM_REV.matcher(norm).let { m -> while (m.find()) add(m.group(1)) }
        LINK_STREAM_RE.matcher(norm).let { m -> while (m.find()) add(m.group(1)) }
        MEDIA_ATTR_RE.matcher(norm).let { m -> while (m.find()) add(m.group(1)) }

        // 5) a quality word sitting beside an otherwise unlabelled URL
        for (c in found.values) {
            if (c["height"].int() != 0 || c["quality"].str().isNotEmpty()) continue
            val label = labelNear(scan, c["url"].str())
            if (label.isNotEmpty()) {
                val q = qualityOf(c["url"].str(), label)
                c["height"] = q.height
                c["quality"] = q.label.ifEmpty { label }
            }
        }

        return found.values.sortedByDescending { it["score"].int() }.take(limit)
    }

    // ── page metadata (title / poster) ────────────────────────────────────
    fun pageMeta(html: String?, baseUrl: String): MutableMap<String, Any?> {
        val text = html ?: ""
        var title = ""
        OG_TITLE_RE.matcher(text).let { if (it.find()) title = it.group(1) }
        if (title.isEmpty()) TITLE_RE.matcher(text).let { if (it.find()) title = it.group(1).replace(Regex("\\s+"), " ") }
        title = title.trim().take(180)
        var thumb = ""
        OG_IMAGE_RE.matcher(text).let { m ->
            if (m.find()) {
                thumb = runCatching { UrlKit.join(baseUrl, m.group(1).trim()) }.getOrDefault("")
            }
        }
        return linkedMapOf("title" to cleanTitle(title), "thumbnail" to thumb)
    }

    private val SITE_SUFFIX_RE = Regex(
        "\\s*[|\\-–—·:]\\s*(?:watch|free|hd|official|home|video|videos|tube|porn|sex|xxx)\\b.*$",
        RegexOption.IGNORE_CASE,
    )

    /** Drop the trailing " - SiteName / Free HD Porn" noise from <title>. */
    fun cleanTitle(title: String?): String {
        val t = (title ?: "").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return ""
        for (sep in listOf(" | ", " – ", " — ", " - ", " · ")) {
            if (sep in t) {
                val first = t.substringBefore(sep).trim()
                if (first.length >= 6) return first.take(180)
            }
        }
        return t.take(180)
    }

    fun unescapeHtml(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
}
