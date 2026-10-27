package com.allvideodownloader.avd.core

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * URL helpers mirroring the Python `urllib.parse` functions the backend used
 * (`urlparse`, `urljoin`, `unquote`, `urlunparse`). Android's `java.net.URI`
 * cannot answer the questions this engine asks (netloc, relative resolution
 * against odd schemes), so the three functions the algorithm needs live here.
 */
object UrlKit {

    data class Parsed(
        val scheme: String,
        val netloc: String,
        val path: String,
        val query: String,
        val fragment: String,
    ) {
        fun withPath(newPath: String): Parsed = copy(path = newPath)
        val host: String
            get() = netloc.substringAfterLast('@').substringBefore(':').lowercase(Locale.ROOT)
    }

    private val SCHEME_RE = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*):")
    private val NETLOC_RE = Regex("^//([^/?#]*)")

    fun parse(url: String): Parsed {
        var rest = url.trim()
        var scheme = ""
        SCHEME_RE.find(rest)?.let {
            scheme = it.groupValues[1].lowercase(Locale.ROOT)
            rest = rest.substring(it.value.length)
        }
        var netloc = ""
        NETLOC_RE.find(rest)?.let {
            netloc = it.groupValues[1]
            rest = rest.substring(it.value.length)
        }
        var fragment = ""
        val hash = rest.indexOf('#')
        if (hash >= 0) {
            fragment = rest.substring(hash + 1)
            rest = rest.substring(0, hash)
        }
        var query = ""
        val q = rest.indexOf('?')
        if (q >= 0) {
            query = rest.substring(q + 1)
            rest = rest.substring(0, q)
        }
        return Parsed(scheme, netloc, rest, query, fragment)
    }

    fun unparse(p: Parsed): String = buildString {
        if (p.scheme.isNotEmpty()) append(p.scheme).append(':')
        if (p.netloc.isNotEmpty()) append("//").append(p.netloc)
        append(p.path)
        if (p.query.isNotEmpty()) append('?').append(p.query)
        if (p.fragment.isNotEmpty()) append('#').append(p.fragment)
    }

    /** RFC 3986 §5.2 relative resolution (the same rules as Python's `urljoin`). */
    fun join(baseIn: String, refIn: String): String {
        val ref = refIn.trim()
        if (ref.isEmpty()) return baseIn
        val r = parse(ref)
        if (r.scheme.isNotEmpty()) return unparse(r)
        if (baseIn.isEmpty()) return ref
        val b = parse(baseIn)
        val netloc = if (r.netloc.isNotEmpty()) r.netloc else b.netloc
        var path: String
        if (r.path.isEmpty()) {
            path = b.path
        } else if (r.path.startsWith("/")) {
            path = removeDotSegments(r.path)
        } else if (b.netloc.isNotEmpty() && b.path.isEmpty()) {
            path = removeDotSegments("/" + r.path)
        } else {
            val baseDir = b.path.substringBeforeLast('/', "")
            path = removeDotSegments(baseDir + "/" + r.path)
        }
        val query = if (r.path.isEmpty() && r.query.isEmpty()) b.query else r.query
        return unparse(Parsed(if (b.scheme.isNotEmpty()) b.scheme else r.scheme, netloc, path, query, r.fragment))
    }

    private fun removeDotSegments(path: String): String {
        val out = ArrayList<String>()
        val trailing = path.endsWith("/") || path.endsWith("/.") || path.endsWith("/..")
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(seg)
            }
        }
        val joined = "/" + out.joinToString("/")
        return if (trailing && out.isNotEmpty()) "$joined/" else joined
    }

    /** Percent-decoding exactly like Python's `unquote` ('+' stays a '+'). */
    fun unquote(s: String?): String {
        val src = s ?: return ""
        if (!src.contains('%')) return src
        val out = StringBuilder()
        val bytes = ByteArrayOutputStream()
        fun flush() {
            if (bytes.size() > 0) {
                out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                bytes.reset()
            }
        }
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '%' && i + 2 < src.length) {
                val hex = src.substring(i + 1, i + 3)
                val v = hex.toIntOrNull(16)
                if (v != null) {
                    bytes.write(v)
                    i += 3
                    continue
                }
            }
            flush()
            out.append(c)
            i++
        }
        flush()
        return out.toString()
    }

    /** `scheme://host/path` without the last segment — used for "same folder" tests. */
    fun dirOf(url: String): String = try {
        val p = parse(url)
        val idx = p.path.lastIndexOf('/')
        p.netloc + if (idx >= 0) p.path.substring(0, idx) else ""
    } catch (_: Exception) {
        ""
    }

    fun fileName(url: String): String {
        val path = try {
            unquote(parse(url).path)
        } catch (_: Exception) {
            ""
        }
        val trimmed = path.trimEnd('/', ';')
        return if (trimmed.isEmpty()) "" else trimmed.substringAfterLast('/')
    }
}

/**
 * Android port of `webfinder/core/filekind.py` — the URL → file-kind taxonomy.
 */
object FileKind {
    val VIDEO_EXTS = setOf(
        ".mp4", ".mkv", ".avi", ".mov", ".webm", ".flv", ".m4v", ".ts", ".m2ts",
        ".wmv", ".3gp", ".mpg", ".mpeg",
    )
    val ARCHIVE_EXTS = setOf(".zip", ".rar", ".7z", ".tar", ".gz", ".tgz", ".bz2", ".xz", ".iso")
    val AUDIO_EXTS = setOf(".mp3", ".flac", ".m4a", ".opus", ".wav", ".aac", ".ogg")
    val STREAM_EXTS = setOf(".m3u8", ".mpd")

    val EXT_KIND: Map<String, String> = buildMap {
        for (e in VIDEO_EXTS) put(e, "video")
        for (e in AUDIO_EXTS) put(e, "audio")
        for (e in ARCHIVE_EXTS) put(e, "archive")
        put(".torrent", "torrent")
    }

    private val TORRENT_RE = Regex("[?&]([^=]*)\\.torrent|\\.torrent($|[?#])", RegexOption.IGNORE_CASE)
    private val MAGNET_RE = Regex("^magnet:\\?|xt=urn:btih", RegexOption.IGNORE_CASE)
    private val EXT_RE = Regex("(\\.[A-Za-z0-9]{1,6})$")

    /**
     * Last path extension, tolerating file-host decorative suffixes.
     *
     * `/get_file/9/<hash>/…/clip_720m.mp4/?download=true` is still an mp4 — the
     * trailing slash and query are KVS/hotlink-guard noise, not part of the name.
     */
    fun urlExtension(url: String): String {
        val path = try {
            UrlKit.unquote(UrlKit.parse(url).path).trimEnd('/', ';')
        } catch (_: Exception) {
            return ""
        }
        return EXT_RE.find(path)?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: ""
    }

    fun classify(url: String, snippet: String = "", title: String = ""): String {
        if (MAGNET_RE.containsMatchIn(url)) return "magnet"
        val ext = urlExtension(url)
        val hay = "$title $snippet".lowercase(Locale.ROOT)
        if (ext in STREAM_EXTS || ".m3u8" in hay || ".mpd" in hay) return "video"
        EXT_KIND[ext]?.let { return it }
        if (TORRENT_RE.containsMatchIn(url) || ext.contains("torrent")) return "torrent"
        if ("magnet link" in hay || "xt=urn:btih" in hay) return "magnet"
        return "link"
    }

    fun isVideoUrl(url: String): Boolean {
        val ext = urlExtension(url)
        return ext in VIDEO_EXTS || ext in STREAM_EXTS
    }

    fun guessFilename(url: String, fallback: String = "download"): String {
        var name = try {
            UrlKit.unquote(UrlKit.parse(url).path.trimEnd('/').substringAfterLast('/'))
        } catch (_: Exception) {
            ""
        }
        name = name.replace(Regex("[^\\w.\\-()+ ]"), "_").trim('.')
        return name.ifEmpty { fallback }
    }

    /** Human size parsing for directory listings ("83.85 مگابایت", "176 MB"). */
    private val SIZE_RE = Regex("([\\d.,]+)\\s*(kb|mb|gb|tb)", RegexOption.IGNORE_CASE)
    fun humanSizeLocal(text: String): Long? {
        val m = SIZE_RE.find(text) ?: return null
        val num = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2].lowercase(Locale.ROOT)) {
            "kb" -> 1024L
            "mb" -> 1024L * 1024
            "gb" -> 1024L * 1024 * 1024
            "tb" -> 1024L * 1024 * 1024 * 1024
            else -> return null
        }
        return (num * mult).toLong()
    }
}
