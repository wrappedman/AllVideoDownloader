package com.allvideodownloader.avd.core

import java.io.File
import java.util.Locale
import java.util.regex.Pattern

/**
 * Android port of `webfinder/core/cookies.py`.
 *
 * The policy is unchanged — **cookies.txt wins, browser source second, anonymous
 * otherwise** — and so is the failure memory that stops the app paying the same
 * timeout on every request.
 *
 * One Android difference, stated plainly instead of faked: the app cannot read a
 * desktop browser's cookie database (sandboxed storage), so the browser source is
 * reported as unavailable and every engine runs from the exported file or
 * anonymously. That is exactly what the desktop does when its browser DB cannot
 * be read (Chrome 127+ App-Bound Encryption) — the same code path, same message.
 */
object Cookies {

    private val COOKIE_ERR = Pattern.compile(
        "could not copy|cookie database|app[- ]bound|permission denied.*cookie" +
            "|cookie.*(?:locked|in use)" +
            "|unable to find .{0,30}cookies database|unable to find .{0,20}cookies" +
            "|cookies .{0,40}could not be decrypted|could not decrypt .{0,20}cookies" +
            "|failed to decrypt with|dpapi" +
            "|(?:could not|unable to|failed to) (?:read|load|extract|decrypt) (?:the )?cookies?" +
            "|cookies? (?:file (?:is )?(?:empty|invalid)|not found|is empty)" +
            "|invalid netscape cookies\\.txt",
        Pattern.CASE_INSENSITIVE,
    )

    const val EXPLAIN = "The browser's cookie database is not readable from an Android app " +
        "(apps are sandboxed). Export a cookies.txt from your desktop browser and set " +
        "Settings → Video & capture → Cookies file. Until then the app runs logged out."

    const val ANDROID_NO_BROWSER = "browser cookie stores are not readable from an Android app"

    @Volatile
    private var browserFailure: String = ""

    fun browser(): String = Config.settings().getString("ytdlp_browser_cookies").trim().lowercase(Locale.ROOT)

    fun cookieFile(): String {
        val raw = Config.settings().getString("cookies_file").trim().trim('"')
        if (raw.isEmpty()) return ""
        return expandHome(raw)
    }

    private fun expandHome(raw: String): String {
        if (!raw.startsWith("~")) return raw
        val home = AppConfig.configDir.parentFile?.absolutePath ?: "."
        return home + raw.substring(1)
    }

    fun fileUsable(): Boolean {
        val path = cookieFile()
        return path.isNotEmpty() && File(path).isFile
    }

    fun activeBrowser(): String = ""    // see the class comment

    fun source(): MutableMap<String, Any?> {
        val path = cookieFile()
        val failed = browserFailure()
        val active = activeBrowser()
        return linkedMapOf(
            "browser" to browser(),
            "active_browser" to active,
            "file" to path,
            "file_ok" to fileUsable(),
            "mode" to (if (fileUsable()) "file" else if (active.isNotEmpty()) "browser" else ""),
            "browser_failed" to failed.isNotEmpty(),
            "error" to failed,
            "note" to if (browser().isNotEmpty() && active.isEmpty()) ANDROID_NO_BROWSER else "",
        )
    }

    fun browserFailure(): String = browserFailure

    fun isCookieError(msg: String?): Boolean = COOKIE_ERR.matcher(msg ?: "").find()

    /** Remember that the browser DB is unreadable; true when it *was* that failure. */
    fun markBrowserFailure(msg: String = ""): Boolean {
        if (browser().isEmpty() || !isCookieError(msg)) return false
        if (browserFailure.isEmpty()) {
            browserFailure = (msg.ifEmpty { EXPLAIN }).trim().take(300)
        }
        return true
    }

    fun reset() {
        browserFailure = ""
    }

    // ── flags for the yt-dlp CLI ──────────────────────────────────────────
    fun ytdlpFlags(): List<String> {
        if (fileUsable()) return listOf("--cookies", cookieFile())
        return emptyList()
    }

    private val FLAG_WITH_VALUE = listOf("--cookies-from-browser", "--cookies", "--cookies-file")

    /** Remove every cookie flag (and its value) — the anonymous retry. */
    fun stripFlags(args: List<String>): List<String> {
        val out = ArrayList<String>()
        var skip = false
        for (a in args) {
            if (skip) {
                skip = false
                continue
            }
            if (a in FLAG_WITH_VALUE) {
                skip = true
                continue
            }
            out.add(a)
        }
        return out
    }

    fun usedSource(): String {
        if (fileUsable()) return "cookies.txt (${File(cookieFile()).name})"
        if (browserFailure.isNotEmpty()) return "anonymous (browser cookies unreadable)"
        return "anonymous"
    }

    // ── diagnostics: the "Check cookies" button ───────────────────────────
    fun countNetscape(path: String): Int {
        val text = try {
            File(path).readText()
        } catch (_: Exception) {
            return 0
        }
        var n = 0
        for (line in text.split("\n")) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("#") && !t.startsWith("#HttpOnly_")) continue
            if (t.split("\t").size >= 7) n++
        }
        return n
    }

    /** Read the configured source locally (no network) and say exactly what is wrong. */
    fun probe(): MutableMap<String, Any?> {
        val path = cookieFile()
        val browserName = browser()
        if (path.isEmpty() && browserName.isEmpty()) {
            return linkedMapOf(
                "ok" to false, "mode" to "",
                "message" to "No cookie source configured — the app is running anonymously. " +
                    "That is fine for public videos; set a cookies.txt for private or age-gated ones.",
            )
        }
        if (path.isNotEmpty()) {
            val f = File(path)
            if (!f.isFile) {
                return linkedMapOf("ok" to false, "mode" to "file",
                    "message" to "cookies.txt was not found at $path — export it again and update the path.")
            }
            val n = countNetscape(path)
            if (n == 0) {
                return linkedMapOf("ok" to false, "mode" to "file",
                    "message" to "The file at $path is not a Netscape cookies.txt (no cookie rows found). " +
                        "Export it with a “Get cookies.txt” extension.")
            }
            return linkedMapOf("ok" to true, "mode" to "file",
                "message" to "$n cookies readable from ${f.name}.")
        }
        // a browser source is configured but Android cannot read it
        return linkedMapOf(
            "ok" to false, "mode" to "browser",
            "message" to "You picked “$browserName”, but $ANDROID_NO_BROWSER. " +
                "Export a cookies.txt instead and set Settings → Video & capture → Cookies file.",
        )
    }

    /** Pick the source that actually reads — on Android that is the file, else none. */
    fun useBest(): MutableMap<String, Any?> {
        if (fileUsable() && countNetscape(cookieFile()) > 0) {
            Config.settings().update(linkedMapOf("ytdlp_browser_cookies" to ""))
            reset()
            return linkedMapOf("ok" to true, "browser" to "",
                "message" to "cookies.txt already works — browser cookies were switched off.")
        }
        return linkedMapOf("ok" to false, "browser" to "",
            "message" to "No readable cookie source: $ANDROID_NO_BROWSER. " +
                "Set a cookies.txt file in Settings → Video & capture.")
    }
}
