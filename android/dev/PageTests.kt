// "It found the link but shows no qualities / I cannot download it" — the exact
// path a user takes on a tube-style page, exercised end to end:
//   page HTML → Media.analyze → quality chips → planDownload → finished file.
package dev

import com.allvideodownloader.avd.core.AppConfig
import com.allvideodownloader.avd.core.Config
import com.allvideodownloader.avd.core.DownloadManager
import com.allvideodownloader.avd.core.Media
import com.allvideodownloader.avd.core.asMap
import com.allvideodownloader.avd.core.int
import com.allvideodownloader.avd.core.list
import com.allvideodownloader.avd.core.map
import com.allvideodownloader.avd.core.str
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.SecureRandom

private var passed = 0
private val failures = ArrayList<String>()
private fun check(cond: Boolean, what: String) {
    if (cond) passed++ else { failures.add(what); println("  ✗ $what") }
}
private fun eq(actual: Any?, expected: Any?, what: String) {
    if (actual != expected) println("    expected <$expected> got <$actual>")
    check(actual == expected, what)
}

private val MEDIA_720 = ByteArray(90_000).also { SecureRandom().nextBytes(it) }
private val MEDIA_1080 = ByteArray(150_000).also { SecureRandom().nextBytes(it) }
private val MEDIA_480 = ByteArray(40_000).also { SecureRandom().nextBytes(it) }
private val SEG = ByteArray(188 * 8).also { SecureRandom().nextBytes(it) }

// A page shaped like the real tube sites the app targets: a player config with
// three mp4 rungs and an HLS master, plus anchor noise that must not win.
private val PAGE = """
<!DOCTYPE html><html><head>
<title>My clip - XVideos.com</title>
<meta property="og:image" content="/cdn/thumb.jpg">
<script>
  var html5player = {
    video_url:      '/media/480.mp4',
    video_url_high: '/media/1080.mp4?e=1',
    video_url_low:  '/media/720.mp4?e=1',
    video_hls:      '/hls/master.m3u8'
  };
  var flashvars = { license_code: '0123456789' };
</script>
</head><body>
<a href="/tags/hd">more hd</a>
<a href="/download/other.mp4">download</a>
<video poster="/cdn/thumb.jpg"></video>
<script src="https://ads.example/exoclick.js"></script>
</body></html>
""".trimIndent()

// Real tubes hide the rung in the JS key, not in the URL: the CDN hands out
// opaque hashes, so "1080p/720p" appear nowhere except `video_url_high`/`_low`.
private val OPAQUE_PAGE = """
<!DOCTYPE html><html><head><title>Opaque slugs</title></head><body>
<script>
  var html5player = {
    video_url:      '/cdn/0c11aa.mp4',
    video_url_high: '/cdn/a8f3c1.mp4',
    video_url_low:  '/cdn/b7e2d9.mp4'
  };
</script></body></html>
""".trimIndent()

// Two slots that mean the same rung and no size to tell them apart.
private val TWIN_PAGE = """
<!DOCTYPE html><html><head><title>Twin labels</title></head><body>
<script>
  var html5player = { video_url_high: '/cdn/a8f3c1.mp4', video_url_hd: '/cdn/b7e2d9.mp4' };
</script></body></html>
""".trimIndent()

private val MASTER = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=1280x720\nmedia.m3u8\n"
private val MEDIA = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.0,\nseg0.ts\n#EXT-X-ENDLIST\n"

fun main() {
    try {
        run()
    } catch (t: Throwable) {
        t.printStackTrace()
        println("ABORTED: ${t.message}")
        System.exit(1)
    }
}

private fun run() {
    val tmp = File(System.getProperty("java.io.tmpdir"), "avd-page-tests-" + ProcessHandle.current().pid())
    val downloads = File(tmp, "downloads").apply { mkdirs() }
    AppConfig.configDir = File(tmp, "config")
    AppConfig.defaultDownloadDir = downloads.absolutePath
    Config.settings().update(linkedMapOf("download_dir" to downloads.absolutePath))

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { ex -> serve(ex) }
    server.executor = java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
        Thread(r, "page-http").apply { isDaemon = true }
    }
    server.start()
    val base = "http://127.0.0.1:${server.address.port}"

    // ── 1. the page yields a quality ladder ────────────────────────────────
    println("== analyze page")
    val card = Media.analyze("$base/v/123/my-clip")
    eq(card["kind"].str(), "webmedia", "page with a player config becomes a webmedia card")
    eq(card["site"].str(), "", "an IP host is not dressed up as a site name")
    eq(card["referer"].str(), "$base/v/123/my-clip", "page kept as referer")
    val formats = card["formats"].list().map { it.map() }
    check(formats.size >= 3, "quality ladder offered (${formats.size} rungs)")
    val heights = formats.map { it["height"].int() }.sortedDescending()
    check(heights.containsAll(listOf(1080, 720, 480)), "heights present: $heights")
    check(formats.all { it["label"].str().isNotEmpty() }, "every chip is labelled")
    check(formats.all { it["url"].str().startsWith("http") }, "every chip resolves to an absolute url")
    check(formats.all { it["format"].str().isNotEmpty() }, "every chip carries a download plan")
    check(formats.none { it["url"].str().contains("exoclick") }, "ad creatives never become rungs")

    // ── 2. every rung plans to a real download ─────────────────────────────
    println("== plan + download")
    for (fmt in formats) {
        val plan = Media.planDownload("$base/v/123/my-clip", fmtIn = fmt)
        val kind = plan["kind"].str()
        check(kind == "http" || kind == "hls", "rung ${fmt["label"].str()} plans kind=$kind")
        check(plan["url"].str().startsWith("http"), "rung ${fmt["label"].str()} has a concrete url")

        val job = DownloadManager.instance.enqueue(
            url = plan["url"].str(),
            kind = kind,
            filename = plan["filename"].str(),
            title = fmt["label"].str(),
            fileType = "video",
            meta = plan["meta"].map(),
        )
        val done = waitFor(job, 40_000)
        eq(done.status, "done", "rung ${fmt["label"].str()} downloads (${done.error})")
        check(File(done.filepath).length() > 0, "rung ${fmt["label"].str()} wrote bytes")
        if (fmt["height"].int() == 1080) {
            check(File(done.filepath).length() == MEDIA_1080.size.toLong(),
                "1080 rung is the exact file the page named")
        }
    }

    // ── 1b. opaque CDN slugs still name their rung ─────────────────────────
    println("== opaque cdn slugs")
    val opaque = Media.analyze("$base/v/456/opaque")
    eq(opaque["kind"].str(), "webmedia", "an opaque-slug page is a webmedia card")
    val of = opaque["formats"].list().map { it.map() }
    eq(of.size, 3, "all three slots became rungs")
    val oLabels = of.map { it["label"].str() }
    check(oLabels.all { it.isNotEmpty() }, "opaque rungs are labelled: $oLabels")
    eq(oLabels.distinct().size, oLabels.size, "no two opaque rungs share a label: $oLabels")
    eq(oLabels, listOf("High", "720p", "480p"), "the ladder runs best → worst")
    for (fmt in of) {
        val plan = Media.planDownload("$base/v/456/opaque", fmtIn = fmt)
        val job = DownloadManager.instance.enqueue(
            plan["url"].str(), plan["kind"].str(), plan["filename"].str(),
            title = fmt["label"].str(), fileType = "video", meta = plan["meta"].map(),
        )
        val done = waitFor(job, 40_000)
        eq(done.status, "done", "opaque rung ${fmt["label"].str()} downloads (${done.error})")
        check(File(done.filepath).length() > 0, "opaque rung ${fmt["label"].str()} wrote bytes")
    }
    val a8 = of.first { it["url"].str().endsWith("a8f3c1.mp4") }
    check(a8["height"].int() == 720, "the HD slot carries what the engine knows: ${a8["height"].int()}")

    println("== twin labels")
    val twin = Media.analyze("$base/v/789/twin")
    val tLabels = twin["formats"].list().map { it.map()["label"].str() }
    eq(tLabels.size, 2, "both twin slots are offered")
    eq(tLabels.distinct().size, 2, "twin slots are told apart instead of reading the same: $tLabels")

    // ── 2b. a page with nothing playable explains itself ───────────────────
    println("== dead-end page")
    val dead = Media.analyze("$base/empty-page")
    eq(dead["kind"].str(), "media_error", "a page with no media becomes a media_error card")
    check(dead["error"].str().isNotEmpty(), "the card carries a reason: ${dead["error"].str()}")
    check(dead["extractor"].str().isNotEmpty(), "the card still names what was scanned")
    val hints = dead["hints"].list().map { it.str() }
    check(hints.isNotEmpty(), "the card carries guidance")
    check(hints.any { it.contains("yt-dlp") }, "guidance names the missing engine")
    check(hints.any { it.contains(".m3u8") }, "guidance offers the route that works on a phone")
    eq(dead["candidates"].list().size, 0, "no phantom candidates are advertised")

    // ── 3. the "direct" card of a raw file still offers a download ──────────
    println("== direct file")
    val direct = Media.analyze("$base/media/1080.mp4")
    eq(direct["kind"].str(), "direct", "raw mp4 → direct card")
    val dplan = Media.planDownload(direct["url"].str())
    eq(dplan["kind"].str(), "http", "direct card plans an http download")
    val djob = DownloadManager.instance.enqueue(dplan["url"].str(), "http", dplan["filename"].str())
    val ddone = waitFor(djob, 30_000)
    eq(ddone.status, "done", "direct file downloads")
    eq(File(ddone.filepath).length(), MEDIA_1080.size.toLong(), "direct file is complete")

    server.stop(0)
    println()
    if (failures.isEmpty()) {
        println("ALL $passed PAGE CHECKS PASSED")
        System.exit(0)
    }
    println("$passed passed, ${failures.size} FAILED")
    failures.forEach { println("  - $it") }
    System.exit(1)
}

private fun serve(ex: HttpExchange) {
    val path = ex.requestURI.path
    fun send(bytes: ByteArray, type: String) {
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
    when {
        path == "/v/123/my-clip" -> send(PAGE.toByteArray(), "text/html; charset=utf-8")
        path == "/empty-page" -> send(
            ("<html><head><title>Nothing here</title></head><body><p>no video</p>" +
                "<a href=\"/about\">about</a></body></html>").toByteArray(),
            "text/html; charset=utf-8",
        )
        path == "/media/1080.mp4" -> send(MEDIA_1080, "video/mp4")
        path == "/media/720.mp4" -> send(MEDIA_720, "video/mp4")
        path == "/media/480.mp4" -> send(MEDIA_720, "video/mp4")
        path == "/v/456/opaque" -> send(OPAQUE_PAGE.toByteArray(), "text/html; charset=utf-8")
        path == "/v/789/twin" -> send(TWIN_PAGE.toByteArray(), "text/html; charset=utf-8")
        path == "/cdn/a8f3c1.mp4" -> send(MEDIA_1080, "video/mp4")
        path == "/cdn/b7e2d9.mp4" -> send(MEDIA_720, "video/mp4")
        path == "/cdn/0c11aa.mp4" -> send(MEDIA_480, "video/mp4")
        path == "/hls/master.m3u8" -> send(MASTER.toByteArray(), "application/vnd.apple.mpegurl")
        path == "/hls/media.m3u8" -> send(MEDIA.toByteArray(), "application/vnd.apple.mpegurl")
        path.startsWith("/hls/seg") -> send(SEG, "video/mp2t")
        path == "/cdn/thumb.jpg" -> send(ByteArray(64), "image/jpeg")
        else -> { ex.sendResponseHeaders(404, -1); ex.close() }
    }
    runCatching { ex.close() }
}

private fun waitFor(job: com.allvideodownloader.avd.core.DownloadJob, timeoutMs: Long): com.allvideodownloader.avd.core.DownloadJob {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (job.status in setOf("done", "error", "cancelled")) return job
        Thread.sleep(50)
    }
    return job
}
