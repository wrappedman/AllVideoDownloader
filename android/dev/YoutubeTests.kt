// The user's second report: a YouTube link produced a PAGE card listing
// "persist_identity", "generate_204", "m_base" — junk from the page scripts —
// instead of the video's qualities. This fixture suite pins both halves:
//   * a YouTube link now goes through YouTube's own player API and comes back
//     with a real quality ladder that downloads, and
//   * a page whose scripts only mention non-media paths yields no junk rows.
//
// The player API is served by a local HTTP server that speaks the same JSON
// schema, because this sandbox has no route to youtube.com.
package dev

import com.allvideodownloader.avd.core.AppConfig
import com.allvideodownloader.avd.core.Config
import com.allvideodownloader.avd.core.DownloadManager
import com.allvideodownloader.avd.core.Json
import com.allvideodownloader.avd.core.Media
import com.allvideodownloader.avd.core.Youtube
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

private val MUX360 = ByteArray(60_000).also { SecureRandom().nextBytes(it) }
private val MUX720 = ByteArray(120_000).also { SecureRandom().nextBytes(it) }
private val ADP1080 = ByteArray(200_000).also { SecureRandom().nextBytes(it) }
private val AUDIO140 = ByteArray(30_000).also { SecureRandom().nextBytes(it) }

private const val WATCH = "https://www.youtube.com/watch?v="

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
    val tmp = File(System.getProperty("java.io.tmpdir"), "avd-yt-tests-" + ProcessHandle.current().pid())
    val downloads = File(tmp, "downloads").apply { mkdirs() }
    AppConfig.configDir = File(tmp, "config")
    AppConfig.defaultDownloadDir = downloads.absolutePath
    Config.settings().update(linkedMapOf("download_dir" to downloads.absolutePath))

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { ex -> serve(ex) }
    server.executor = java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
        Thread(r, "yt-http").apply { isDaemon = true }
    }
    server.start()
    Youtube.apiBase = "http://127.0.0.1:${server.address.port}"
    Youtube.watchBase = Youtube.apiBase

    // ── 1. link shapes ─────────────────────────────────────────────────────
    println("== link shapes")
    eq(Youtube.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), "dQw4w9WgXcQ", "watch?v=")
    eq(Youtube.videoId("https://youtu.be/dQw4w9WgXcQ?t=42"), "dQw4w9WgXcQ", "youtu.be short link")
    eq(Youtube.videoId("https://m.youtube.com/shorts/dQw4w9WgXcQ"), "dQw4w9WgXcQ", "shorts link")
    eq(Youtube.videoId("https://www.youtube.com/embed/dQw4w9WgXcQ"), "dQw4w9WgXcQ", "embed link")
    eq(Youtube.videoId("https://www.youtube.com/watch?v=short"), "", "not a video id")
    eq(Youtube.videoId("https://example.com/watch?v=dQw4w9WgXcQ"), "", "another host is not YouTube")

    // ── 2. the ladder the player API hands back ────────────────────────────
    println("== player api")
    check(Json.parseOrNull(playerJson("http://x", "dQw4w9WgXcQ")) != null, "fixture player payload is valid json")
    val card = Media.analyze(WATCH + "dQw4w9WgXcQ")
    eq(card["kind"].str(), "webmedia", "a YouTube link becomes a webmedia card")
    eq(card["site"].str(), "YouTube", "the card is labelled YouTube")
    eq(card["title"].str(), "Fixture Clip", "the real video title is kept")
    val formats = card["formats"].list().map { it.map() }
    check(formats.size == 4, "every rung is offered (${formats.size})")
    eq(formats.map { it["label"].str() },
        listOf("720p", "360p", "1080p · video only", "Audio 128k · mp4"),
        "muxed first, then video-only, then audio")
    val muxed = formats.filter { it["video_only"] != true && it["audio_only"] != true }
    check(muxed.map { it["height"].int() } == listOf(720, 360), "muxed rungs keep their height")
    check(formats.first { it["height"].int() == 1080 }["video_only"] == true,
        "the adaptive rung is marked video-only")
    check(formats.first { it["height"].int() == 1080 }["label"].str().contains("video only"),
        "its chip says so")
    check(formats.last()["file_type"].str() == "audio", "the audio rung is offered as audio")
    check(formats.all { it["filename"].str().startsWith("Fixture Clip") },
        "every rung knows the name it will be saved under")
    eq(card["player_client"].str(), "ANDROID_VR", "the first client that works is reported")

    // ── 3. a chosen quality really downloads ───────────────────────────────
    println("== download the chosen rung")
    val chosen = formats.first { it["label"].str() == "720p" }
    val plan = Media.planDownload(WATCH + "dQw4w9WgXcQ", fmtIn = chosen)
    eq(plan["kind"].str(), "http", "the rung plans a plain http download")
    eq(plan["filename"].str(), chosen["filename"].str(), "the planned file keeps the rung's name")
    val job = DownloadManager.instance.enqueue(
        plan["url"].str(), plan["kind"].str(), plan["filename"].str(),
        title = "Fixture Clip", fileType = "video", meta = plan["meta"].map(),
    )
    val done = waitFor(job, 40_000)
    eq(done.status, "done", "the chosen quality downloads (${done.error})")
    eq(File(done.filepath).length(), MUX720.size.toLong(), "and it is exactly that rung's file")

    val audioFmt = formats.last()
    val aplan = Media.planDownload(WATCH + "dQw4w9WgXcQ", fmtIn = audioFmt)
    val ajob = DownloadManager.instance.enqueue(
        aplan["url"].str(), aplan["kind"].str(), aplan["filename"].str(), title = "Fixture Clip",
    )
    val adone = waitFor(ajob, 30_000)
    eq(adone.status, "done", "the audio rung downloads too (${adone.error})")
    eq(File(adone.filepath).length(), AUDIO140.size.toLong(), "audio file is complete")

    // ── 4. refusals are explained, not turned into junk rows ───────────────
    println("== refusals")
    val gone = Media.analyze(WATCH + "aaaaaaaaaaa")
    eq(gone["kind"].str(), "media_error", "an unavailable video is a media_error card")
    check(gone["error"].str().contains("Video unavailable"), "the player's own reason is shown: ${gone["error"].str()}")
    eq(gone["candidates"].list().size, 0, "and it invents no candidates")

    val ciphered = Media.analyze(WATCH + "bbbbbbbbbbb")
    eq(ciphered["kind"].str(), "media_error", "a cipher-only answer is a media_error card")
    check(ciphered["error"].str().contains("ciphered"), "the cipher limitation is named: ${ciphered["error"].str()}")

    // ── 5. the watch page is a last resort ────────────────────────────────
    println("== watch page fallback")
    val scraped = Media.analyze(WATCH + "ccccccccccc")
    eq(scraped["kind"].str(), "webmedia", "a page carrying plain stream urls still yields a ladder")
    val sf = scraped["formats"].list().map { it.map() }
    check(sf.any { it["height"].int() == 1080 }, "the 1080 rung was recovered from the page")
    check(sf.all { it["url"].str().contains("videoplayback") }, "all rungs are the page's own urls")
    eq(scraped["player_client"].str(), "page", "the card says which route produced it")

    // ── 6. script noise never becomes a "file" ────────────────────────────
    println("== junk page")
    val junk = Media.analyze(Youtube.apiBase + "/v/junk")
    eq(junk["kind"].str(), "media_error", "a page of script noise is an error card, not a file list")
    eq(junk["candidates"].list().size, 0, "persist_identity / generate_204 are not offered as files")
    eq(junk["error"].str().isNotEmpty(), true, "the card still explains itself")

    server.stop(0)
    println()
    if (failures.isEmpty()) {
        println("ALL $passed YOUTUBE CHECKS PASSED")
        System.exit(0)
    }
    println("$passed passed, ${failures.size} FAILED")
    failures.forEach { println("  - $it") }
    System.exit(1)
}

// ── fixtures ──────────────────────────────────────────────────────────────
private fun muxed(itag: Int, height: Int, url: String, audio: Boolean = true): String {
    val codecs = if (audio) "avc1.42001E, mp4a.40.2" else "avc1.42001E"
    return """{"itag":$itag,"url":"$url","mimeType":"video/mp4; codecs=\"$codecs\"",""" +
        """"bitrate":1000000,"width":${height * 16 / 9},"height":$height,""" +
        """"contentLength":"12345","qualityLabel":"${height}p"}"""
}

private fun adaptiveVideo(itag: Int, height: Int, url: String): String =
    """{"itag":$itag,"url":"$url","mimeType":"video/mp4; codecs=\"avc1.640028\"",""" +
        """"bitrate":4000000,"width":${height * 16 / 9},"height":$height,""" +
        """"contentLength":"99999","qualityLabel":"${height}p","fps":30}"""

private fun adaptiveAudio(itag: Int, url: String): String =
    """{"itag":$itag,"url":"$url","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"",""" +
        """"bitrate":128000,"contentLength":"30000","audioQuality":"AUDIO_QUALITY_MEDIUM"}"""

private fun cipherOnly(itag: Int, height: Int): String =
    """{"itag":$itag,"signatureCipher":"s=ABCDEF&sp=sig&url=https%3A%2F%2Fexample.invalid%2Fc$itag",""" +
        """"mimeType":"video/mp4","bitrate":900000,"height":$height,"qualityLabel":"${height}p"}"""

private fun playerJson(base: String, id: String): String {
    if (id == "aaaaaaaaaaa") {
        return """{"playabilityStatus":{"status":"ERROR","reason":"Video unavailable"},"videoDetails":{}}"""
    }
    if (id == "bbbbbbbbbbb") {
        return """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"$id","title":"Ciphered"},""" +
            """"streamingData":{"formats":[${cipherOnly(22, 720)}],""" +
            """"adaptiveFormats":[${cipherOnly(137, 1080)}]}}"""
    }
    if (id == "ccccccccccc") {
        return """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you're not a bot"}}"""
    }
    return """{"playabilityStatus":{"status":"OK","reason":""},""" +
        """"videoDetails":{"videoId":"$id","title":"Fixture Clip","lengthSeconds":"212","author":"Fixture Author",""" +
        """"thumbnail":{"thumbnails":[{"url":"$base/thumb.jpg","width":480,"height":270}]}},""" +
        """"streamingData":{"formats":[${muxed(18, 360, "$base/media/mux360.mp4")},""" +
        """${muxed(22, 720, "$base/media/mux720.mp4")}],""" +
        """"adaptiveFormats":[${adaptiveVideo(137, 1080, "$base/media/adp1080.mp4")},""" +
        """${adaptiveAudio(140, "$base/media/audio140.m4a")}]}}"""
}

private fun watchPage(base: String): String = """<!DOCTYPE html><html><head>
<title>Scraped Clip - YouTube</title></head><body><script>
var ytInitialPlayerResponse = {"playabilityStatus":{"status":"LOGIN_REQUIRED"}};
</script>
<script>
var cfg = {"url":"$base/media/videoplayback?itag=137&mime=video%2Fmp4&clen=200000"};
var cfg2 = {"url":"$base/media/videoplayback?itag=18&mime=video%2Fmp4&clen=60000"};
</script></body></html>"""

private fun refusedPage(id: String): String = """<!DOCTYPE html><html><head>
<title>Refused - YouTube</title></head><body><script>
var ytInitialPlayerResponse = {"playabilityStatus":{"status":"ERROR","reason":"Video unavailable"}};
</script></body></html>"""

// What the real failing page looked like: every value is a script path, none is media.
private const val JUNK_PAGE = """<!DOCTYPE html><html><head><title>YouTube</title></head><body>
<script>
var ytcfg = {
  "persist_identity": "/persist_identity",
  "m_base": "m_base",
  "video_url": "/generate_204",
  "media_source": "/generate_204?x=1",
  "video_stream": "/m_base"
};
</script></body></html>"""

private fun serve(ex: HttpExchange) {
    val path = ex.requestURI.path
    val base = "http://127.0.0.1:${ex.localAddress.port}"
    fun send(bytes: ByteArray, type: String) {
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
    fun sendText(text: String, type: String = "text/html; charset=utf-8") = send(text.toByteArray(), type)

    when {
        path == "/youtubei/v1/player" -> {
            val bodyText = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val payload = Json.parse(bodyText).map()
            val id = payload["videoId"].str()
            val client = payload["context"].map()["client"].map()["clientName"].str()
            // the fixture behaves like the real endpoint: the web client ciphers,
            // the app clients hand out plain urls
            val json = if (client == "WEB") {
                """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"$id","title":"Fixture Clip"},""" +
                    """"streamingData":{"formats":[${cipherOnly(22, 720)}]}}"""
            } else {
                playerJson(base, id)
            }
            sendText(json, "application/json")
        }
        path == "/watch" -> {
            // a refused video has a refused page too: only the scraped fixture
            // actually carries playable urls
            val id = ex.requestURI.query.orEmpty().split('&')
                .firstOrNull { it.startsWith("v=") }?.removePrefix("v=").orEmpty()
            sendText(if (id == "ccccccccccc") watchPage(base) else refusedPage(id))
        }
        path == "/v/junk" -> sendText(JUNK_PAGE)
        path == "/media/mux360.mp4" -> send(MUX360, "video/mp4")
        path == "/media/mux720.mp4" -> send(MUX720, "video/mp4")
        path == "/media/adp1080.mp4" -> send(ADP1080, "video/mp4")
        path == "/media/audio140.m4a" -> send(AUDIO140, "audio/mp4")
        path == "/media/videoplayback" -> send(ADP1080, "video/mp4")
        path == "/thumb.jpg" -> send(ByteArray(64), "image/jpeg")
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
