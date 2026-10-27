package com.allvideodownloader.avd.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/** A response snapshot — the subset of `httpx.Response` the engine actually used. */
class HttpResult(
    val status: Int,
    val finalUrl: String,
    val headers: MutableMap<String, String>,
    val text: String,
    val bytes: ByteArray,
) {
    fun header(name: String): String =
        headers[name.lowercase(Locale.ROOT)] ?: ""
}

/**
 * Android port of `webfinder/core/net.py` — HTTP with browser-grade realism.
 *
 * Tube sites and their CDNs check the whole header set, not just the UA, and
 * answer anything else with a Cloudflare interstitial or a bare 403. The same
 * browser header set the desktop build sends is sent here.
 *
 * One deliberate difference: the desktop's second chance is `curl_cffi` (a real
 * Chrome TLS fingerprint). Android's TLS stack cannot impersonate a JA3 hash, so
 * [hasImpersonation] reports `false` and every caller degrades to the plain
 * fetch exactly as the desktop does when curl_cffi is not installed.
 */
object Net {

    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val HTML_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9," +
        "image/avif,image/webp,image/apng,*/*;q=0.8"
    private const val FILE_ACCEPT = "*/*"

    private val BLOCK_MARKERS = Regex(
        "just a moment|attention required|cf-browser-verification|cf_chl_" +
            "|enable javascript and cookies to continue|checking your browser" +
            "|access denied|request blocked|unusual traffic|captcha-delivery" +
            "|ddos-guard|please verify you are a human",
        RegexOption.IGNORE_CASE,
    )

    /** TLS impersonation is a desktop-only capability (see the class comment). */
    fun hasImpersonation(): Boolean = false

    fun browserHeaders(referer: String = "", accept: String = ""): MutableMap<String, String> {
        val isDocument = accept.isEmpty() || accept == HTML_ACCEPT
        val h = linkedMapOf(
            "User-Agent" to UA,
            "Accept" to accept.ifEmpty { HTML_ACCEPT },
            "Accept-Language" to "en-US,en;q=0.9",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to if (isDocument) "document" else "empty",
            "Sec-Fetch-Mode" to if (isDocument) "navigate" else "cors",
            "Sec-Fetch-Site" to if (referer.isNotEmpty()) "same-origin" else "none",
            "Sec-Fetch-User" to "?1",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
            "Accept-Encoding" to "identity",
        )
        if (referer.isNotEmpty()) {
            h["Referer"] = referer
            h["Sec-Fetch-Site"] = "cross-site"
        }
        return h
    }

    fun fileHeaders(referer: String = ""): MutableMap<String, String> {
        val h = browserHeaders(referer, FILE_ACCEPT)
        h["Sec-Fetch-Dest"] = "video"
        h["Sec-Fetch-Mode"] = "no-cors"
        h["Range"] = "bytes=0-"
        return h
    }

    fun looksBlocked(text: String, status: Int = 200): Boolean {
        if (status == 401 || status == 403 || status == 429 || status == 503) return true
        val head = text.take(6000)
        if (head.isEmpty()) return status >= 400
        if (BLOCK_MARKERS.containsMatchIn(head)) return true
        return status == 200 && head.length < 900 && !head.lowercase(Locale.ROOT).contains("<title>")
    }

    // ── proxy (Settings → Downloads → Network proxy) ──────────────────────
    fun proxy(): Proxy? {
        val raw = Config.settings().getString("proxy").trim()
        if (raw.isEmpty()) return null
        return runCatching {
            val noScheme = raw.substringAfter("://")
            val host = noScheme.substringBeforeLast(':')
            val port = noScheme.substringAfterLast(':').toIntOrNull() ?: 8080
            val type = if (raw.startsWith("socks", true)) Proxy.Type.SOCKS else Proxy.Type.HTTP
            Proxy(type, InetSocketAddress(host, port))
        }.getOrNull()
    }

    private fun applyProxy(conn: HttpURLConnection, proxy: Proxy?) {
        if (proxy != null) {
            // Proxy was supplied at construction; nothing else to do, but keep the
            // parameter for signature parity with the Python version's callers.
        }
    }

    private val TLS: SSLContext? by lazy {
        runCatching {
            // Parity with yt-dlp's / httpx's `nocheckcertificate=True`: some tube
            // CDNs serve expired or mismatched certificates. Media metadata is
            // untrusted input anyway; content is the user's own download.
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            SSLContext.getInstance("TLS").apply { init(null, trustAll, java.security.SecureRandom()) }
        }.getOrNull()
    }

    private fun open(url: String, headers: Map<String, String>, timeoutSec: Int): HttpURLConnection {
        val proxy = proxy()
        val conn = if (proxy != null) URL(url).openConnection(proxy) as HttpURLConnection
        else URL(url).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) {
            TLS?.let { conn.sslSocketFactory = it.socketFactory }
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        conn.instanceFollowRedirects = false
        conn.connectTimeout = minOf(8000, timeoutSec * 1000)
        conn.readTimeout = timeoutSec * 1000
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        applyProxy(conn, proxy)
        return conn
    }

    private fun headerMap(conn: HttpURLConnection): MutableMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in conn.headerFields) {
            if (k == null) continue
            out[k.lowercase(Locale.ROOT)] = (v ?: emptyList()).joinToString(", ")
        }
        return out
    }

    private fun readAll(stream: InputStream?, max: Long = 32L * 1024 * 1024): ByteArray {
        if (stream == null) return ByteArray(0)
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        try {
            while (true) {
                val n = stream.read(chunk)
                if (n <= 0) break
                buf.write(chunk, 0, n)
                total += n
                if (total > max) break
            }
        } catch (_: Exception) {
            // truncated body — keep what arrived (Python's httpx raises; callers
            // here already treat a partial page as "nothing useful")
        }
        return buf.toByteArray()
    }

    /** GET with manual redirect following (http→https hops need it). */
    fun get(url: String, headers: Map<String, String>, timeoutSec: Int = 15,
            maxBytes: Long = 32L * 1024 * 1024): HttpResult {
        var current = url
        val hdrs = LinkedHashMap(headers)
        var i = 0
        while (i < 8) {
            val conn = open(current, hdrs, timeoutSec)
            try {
                conn.requestMethod = "GET"
                val status = conn.responseCode
                val loc = conn.getHeaderField("Location")
                if (status in 301..308 && !loc.isNullOrEmpty()) {
                    current = UrlKit.join(current, loc)
                    i++
                    continue
                }
                val bytes = readAll(if (status >= 400) conn.errorStream else conn.inputStream, maxBytes)
                return HttpResult(status, current, headerMap(conn), bytes.toString(Charsets.UTF_8), bytes)
            } finally {
                runCatching { conn.disconnect() }
            }
        }
        return HttpResult(508, current, LinkedHashMap(), "", ByteArray(0))
    }

    /**
     * GET a page like a browser. Never throws: an empty text means "we got
     * nothing", which every caller already handles as "no candidates".
     */
    fun fetchText(
        url: String,
        referer: String = "",
        headers: Map<String, String>? = null,
        timeoutSec: Int = 15,
    ): Triple<String, String, MutableMap<String, String>> {
        val hdrs = browserHeaders(referer)
        headers?.let { hdrs.putAll(it) }
        return try {
            val r = get(url, hdrs, timeoutSec)
            if (looksBlocked(r.text, r.status)) Triple("", r.finalUrl, r.headers) else Triple(r.text, r.finalUrl, r.headers)
        } catch (_: Exception) {
            Triple("", url, LinkedHashMap())
        }
    }

    /** Manifest fetch with Referer (raises when totally unreachable, like the original). */
    fun fetchManifestText(url: String, referer: String = "", timeoutSec: Int = 20): String {
        val hdrs = fileHeaders(referer)
        hdrs["Range"] = "bytes=0-524287"
        return try {
            val r = get(url, hdrs, timeoutSec, maxBytes = 8L * 1024 * 1024)
            if (r.status >= 400) throw RuntimeException("HTTP ${r.status}")
            r.text
        } catch (e: Exception) {
            throw RuntimeException("Manifest unreachable: ${(e.message ?: "").take(160)}", e)
        }
    }

    /** HEAD, falling back to a 2-byte ranged GET — the analyzer's URL probe. */
    fun probe(url: String): HttpResult {
        val hdrs = browserHeaders()
        hdrs["Range"] = "bytes=0-1"
        return try {
            val r = get(url, hdrs, 15, maxBytes = 64)
            if (r.status >= 400) throw RuntimeException("HTTP ${r.status}")
            r
        } catch (e: Exception) {
            throw RuntimeException("Host unreachable: ${(e.message ?: "").take(160)}", e)
        }
    }

    // ── container sniffing: the truth behind lying Content-Types ──────────
    fun sniffContainer(head: ByteArray): String {
        fun at(i: Int) = if (i < head.size) head[i] else 0
        if (head.size >= 8 && at(4) == 'f'.code.toByte() && at(5) == 't'.code.toByte() &&
            at(6) == 'y'.code.toByte() && at(7) == 'p'.code.toByte()
        ) return "mp4"
        if (head.size >= 4 && at(0) == 0x1a.toByte() && at(1) == 0x45.toByte() &&
            at(2) == 0xdf.toByte() && at(3) == 0xa3.toByte()
        ) return "mkv"
        if (head.size >= 3 && at(0) == 'F'.code.toByte() && at(1) == 'L'.code.toByte() &&
            at(2) == 'V'.code.toByte()
        ) return "flv"
        if (head.size >= 12 && at(0) == 'R'.code.toByte() && at(1) == 'I'.code.toByte() &&
            at(2) == 'F'.code.toByte() && at(3) == 'F'.code.toByte() &&
            at(8) == 'A'.code.toByte() && at(9) == 'V'.code.toByte() &&
            at(10) == 'I'.code.toByte() && at(11) == ' '.code.toByte()
        ) return "avi"
        if (head.size >= 4 && ((at(0) == 0x00.toByte() && at(1) == 0x00.toByte() &&
                    at(2) == 0x01.toByte() && at(3) == 0xba.toByte()) ||
                (at(0) == 0x00.toByte() && at(1) == 0x00.toByte() &&
                    at(2) == 0x01.toByte() && at(3) == 0xb3.toByte()))
        ) return "mpeg"
        if (head.size >= 4 && at(0) == 0.toByte() && at(1) == 0.toByte() && at(2) == 0.toByte()) return "mp4"
        return ""
    }

    private val counter = AtomicInteger(0)
    /** Unique-ish id generator (Python used `uuid4().hex[:12]`). */
    fun jobId(): String {
        val rnd = java.security.SecureRandom()
        val b = ByteArray(6)
        rnd.nextBytes(b)
        counter.incrementAndGet()
        return b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
