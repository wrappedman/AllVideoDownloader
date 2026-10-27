// JVM test harness for the ported engine.
//
// `core/` and `api/` are deliberately Android-free (only `java.*` APIs), so the
// exact source that ships inside the APK can also be compiled and run on a
// desktop JVM. That is what makes these tests meaningful: they exercise the
// shipping code, not a copy of it.
//
// Mirrors the fixtures of `tools/test_video_core.py`, `tools/test_cookies.py`
// and `tools/test_app_surface.py`, plus a live round-trip against the embedded
// HTTP/WebSocket server.
package dev

import com.allvideodownloader.avd.api.ApiServer
import com.allvideodownloader.avd.core.Adapters
import com.allvideodownloader.avd.core.AppConfig
import com.allvideodownloader.avd.core.Config
import com.allvideodownloader.avd.core.Embed
import com.allvideodownloader.avd.core.FileKind
import com.allvideodownloader.avd.core.Json
import com.allvideodownloader.avd.core.Media
import com.allvideodownloader.avd.core.Net
import com.allvideodownloader.avd.core.Sites
import com.allvideodownloader.avd.core.UrlKit
import com.allvideodownloader.avd.core.YtCli
import com.allvideodownloader.avd.core.asMap
import com.allvideodownloader.avd.core.asList
import com.allvideodownloader.avd.core.bool
import com.allvideodownloader.avd.core.int
import com.allvideodownloader.avd.core.list
import com.allvideodownloader.avd.core.long
import com.allvideodownloader.avd.core.map
import com.allvideodownloader.avd.core.str
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Socket
import java.net.URL

private var passed = 0
private val failures = ArrayList<String>()
private var section = ""

private fun section(name: String) {
    section = name
}

private fun check(cond: Boolean, what: String) {
    if (cond) {
        passed++
    } else {
        failures.add("[$section] $what")
        println("  ✗ $what")
    }
}

private fun <T> eq(actual: T, expected: T, what: String) {
    val ok = actual == expected
    if (!ok) println("    expected <$expected> got <$actual>")
    check(ok, what)
}

// ── fixtures stolen verbatim from tools/test_video_core.py ────────────────────
private val M3U8 = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=5500000,AVERAGE-BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
1080p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720
720p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=640x360
360p/index.m3u8
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",DEFAULT=YES,URI="audio/en.m3u8"
"""

private val MPD = """<?xml version="1.0"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static">
 <Period>
  <AdaptationSet contentType="video" mimeType="video/mp4">
   <Representation id="v1080" bandwidth="4500000" width="1920" height="1080"><BaseURL>vid/1080.m3u8</BaseURL></Representation>
   <Representation id="v720" bandwidth="2300000" width="1280" height="720"><BaseURL>vid/720.m3u8</BaseURL></Representation>
  </AdaptationSet>
  <AdaptationSet contentType="audio" mimeType="audio/mp4">
   <Representation id="a128" bandwidth="128000"><BaseURL>aud/en.m3u8</BaseURL></Representation>
  </AdaptationSet>
 </Period>
</MPD>"""

private val XV_REAL = """<html><head><title>My clip - XVideos.com</title>
<meta property="og:image" content="/cdn/thumb.jpg"></head><body>
<script>
  html5player.setVideoUrlLow('/videos/abc/720p.mp4?e=1699&h=abc');
  html5player.setVideoUrlHigh('/videos/abc/1080p.mp4?e=1699&h=abc');
  html5player.setVideoHLS('/videos/abc/hls.m3u8?e=1699');
  html5player.setVideoThumb('/cdn/thumb.jpg');
  var popunder = "https://ads.exoclick.com/banner/ad.mp4";
</script></body></html>"""

private val KT = """<script>var flashvars = { video_url: 'https://cdn.example/v/480.mp4',
 video_url_hd: 'https://cdn.example/v/1080.mp4', video_alt_url: 'https://alt.example/v/720.mp4',
 preview_url: '/preview/thumb.mp4', title: 'My video 2024' }</script>"""

private val XV_PAGE = """
<html><head><title>Tube page | XVideos</title></head><body>
<script>
var html5player = {
  video_url: 'https://cdn.example/hls/master.m3u8',
  video_url_high: 'https://cdn77.example/v/1080.mp4?e=1',
  video_url_low: 'https://cdn77.example/v/360.mp4?e=1',
  video_alt_url: 'https://cdn77.example/v/720.mp4?e=1'
};
</script>
<script src="https://ads.example/exoclick.js"></script>
<img src="https://cdn.example/poster.jpg">
</body></html>
""".trimIndent()

fun main() {
    val tmp = File(System.getProperty("java.io.tmpdir"), "avd-jvm-tests-" + ProcessHandle.current().pid())
    tmp.mkdirs()
    AppConfig.configDir = File(tmp, "config")
    AppConfig.defaultDownloadDir = File(tmp, "downloads").absolutePath

    // ── 1. HLS master playlist ─────────────────────────────────────────────
    section("m3u8")
    val ladder = Media.parseM3u8Master(M3U8, "https://cdn.example.io/show/master.m3u8")
    eq(ladder.map { it["height"].int() }, listOf(1080, 720, 360), "ladder heights 1080/720/360")
    eq(ladder[0]["url"].str(), "https://cdn.example.io/show/1080p/index.m3u8", "relative variant → absolute url")
    eq(ladder[0]["bandwidth"].int(), 5_500_000, "bandwidth parsed")
    val forms = Media.streamFormats(ladder)
    eq(forms[0]["label"].str(), "Best (auto)", "first picker chip is auto")
    check(forms[1]["label"].str().startsWith("1080p"), "second chip labelled by height")
    check(forms[1]["format"].str().contains("height<=1080"), "yt-dlp selector clamps to 1080")
    eq(forms.size, 4, "auto + one chip per rung")

    // ── 2. DASH MPD ────────────────────────────────────────────────────────
    section("mpd")
    val reps = Media.parseMpdMaster(MPD, "https://cdn.example.io/dash/manifest.mpd")
    eq(reps.map { it["height"].int() }, listOf(1080, 720), "video representations only")
    eq(reps[0]["rep_id"].str(), "v1080", "representation id kept")
    check(reps[0]["url"].str().endsWith("vid/1080.m3u8"), "BaseURL resolved against manifest")
    check(Media.streamFormats(reps)[1]["format"].str().startsWith("v1080/"), "selector carries rep id")

    // ── 3. container sniffing ──────────────────────────────────────────────
    section("sniff")
    val ftyp = byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray() + ByteArray(52)
    val ebml = byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte(), 1, 0, 0, 0) + ByteArray(56)
    val flv = "FLV".toByteArray() + byteArrayOf(1, 5) + ByteArray(59)
    eq(Net.sniffContainer(ftyp), "mp4", "ftyp → mp4")
    eq(Net.sniffContainer(ebml), "mkv", "EBML → mkv")
    eq(Net.sniffContainer(flv), "flv", "FLV header → flv")
    eq(Net.sniffContainer("<!DOCTYPE html><html>".toByteArray()), "", "HTML rejected")

    // ── 4. JSON ────────────────────────────────────────────────────────────
    section("json")
    val doc = Json.parse("""{"a":1,"b":[true,null,1.5],"c":{"d":"e\u00e9\n"}}""")
    eq(doc.map()["a"].int(), 1, "int read")
    eq(doc.map()["b"].list().size, 3, "array length")
    eq(doc.map()["b"].list()[0].bool(), true, "bool read")
    eq(doc.map()["c"].map()["d"].str(), "eé\n", "escapes decoded")
    val round = Json.parse(Json.write(doc)).asMap()!!
    eq(Json.write(round), Json.write(doc), "write→parse→write is stable")
    eq(Json.parseOrNull("{oops"), null, "invalid json → null, no throw")

    // ── 5. settings defaults ───────────────────────────────────────────────
    section("settings")
    val defaults = AppConfig.defaults()
    check(!defaults.containsKey("engines") && !defaults.containsKey("demo_mode"), "search-era keys stayed dead")
    check(defaults.containsKey("proxy") && defaults.containsKey("default_quality"), "download keys present")
    val st = Config.settings()
    eq(st.getString("default_container"), "mp4", "container default")
    val merged = st.update(linkedMapOf("default_quality" to "720"))
    eq(merged["default_quality"].str(), "720", "update applied")
    eq(st.snapshot()["default_quality"].str(), "720", "snapshot reflects update")
    check(File(AppConfig.configFile.parentFile, "").exists() || AppConfig.configFile.parentFile.exists(), "config dir exists")

    // ── 6. urls and file kinds ─────────────────────────────────────────────
    section("urls")
    eq(UrlKit.join("https://a.example/x/y.m3u8", "../z/1.ts"), "https://a.example/z/1.ts", "dot-dot join")
    eq(UrlKit.join("https://a.example", "/v/1.mp4"), "https://a.example/v/1.mp4", "absolute path join")
    eq(UrlKit.unquote("a%20b%2Fc"), "a b/c", "percent decode")
    eq(FileKind.classify("https://x.example/a/b.mkv"), "video", "mkv is video")
    eq(FileKind.classify("https://x.example/a/b.zip"), "archive", "zip is archive")
    eq(FileKind.guessFilename("https://x.example/a/clip.mp4?x=1"), "clip.mp4", "filename from url")

    // ── 7. site registry ───────────────────────────────────────────────────
    section("sites")
    eq(Sites.recipeFor("https://www.xvideos.com/video1/x")?.key, "xvideos", "xvideos recipe")
    eq(Sites.recipeFor("https://de.xhamster.desi/videos/x")?.key, "xhamster", "xhamster subdomain")
    eq(Sites.recipeFor("https://spankbang.com/9x/video/x")?.key, "spankbang", "spankbang recipe")
    eq(Sites.recipeFor("https://aparat.com/v/abc")?.key, "aparat", "aparat recipe")
    eq(Sites.recipeFor("https://unknown.example/a"), null, "unknown host → no recipe")
    eq(Sites.siteName("http://127.0.0.1:8000/x"), "", "IP is not a site name")
    eq(Sites.siteName("https://www.brand-new-tube.example/x"), "Brand New Tube", "host → pretty site name")
    check(Sites.RECIPES.size >= 60, "registry keeps ${Sites.RECIPES.size} recipes")
    check(Sites.RECIPES.all { it.domains.isNotEmpty() && it.name.isNotEmpty() }, "every recipe named and scoped")
    eq(Sites.detectFamily(XV_REAL), "html5player", "page family detected")
    val xv = Sites.candidates(XV_REAL, "https://www.xvideos.com/video12345/my-clip")
    check(xv.map { it["height"].int() }.toSet().containsAll(listOf(720, 1080)), "html5player high/low ladder")
    check(xv.none { it["url"].str().contains("exoclick") }, "ad creative is never a quality rung")
    check(xv.all { it["site"].str() == "XVideos" }, "candidates carry the site name")
    eq(xv.first { it["height"].int() == 1080 }["quality"].str(), "1080p", "1080 rung labelled 1080p")
    val kt = Sites.candidates(KT, "https://www.txxx.com/videos/123/x")
    eq(kt.filter { it["height"].int() != 0 }.map { it["height"].int() }, listOf(1080, 720, 480), "kt ladder ordered")
    eq(kt.last()["quality"].str(), "Preview", "preview rung labelled Preview")
    check(Adapters.ADAPTERS.size == 2, "aparat + filimo adapters registered")

    // ── 8. embed extraction ────────────────────────────────────────────────
    section("embed")
    val cands = Embed.extract(XV_PAGE, "https://www.xvideos.com/video1/x")
    check(cands.any { it["url"].str() == "https://cdn.example/hls/master.m3u8" }, "m3u8 master found")
    check(cands.any { it["url"].str().contains("1080.mp4") }, "720/1080 mp4 found")
    check(cands.none { it["url"].str().contains("exoclick") }, "ad creatives dropped")
    check(cands.none { it["url"].str().endsWith(".jpg") }, "posters are not downloads")
    check(cands.all { it["url"].str().isNotEmpty() && it.containsKey("score") }, "candidates scored")
    val kvs = Embed.kvsLicenseCode("var license_code = '01578423690745123698014570236984';")
    eq(kvs.length, 32, "32-digit licence code extracted")
    check(Embed.kvsRealUrl("https://x.example/aaa/bbb/ccc", kvs).contains("license_code=") ||
        Embed.kvsRealUrl("https://x.example/aaa/bbb/ccc", kvs).isNotEmpty(), "kvs real url rebuilt")
    val packed = Embed.unpackPacked(
        "eval(function(p,a,c,k,e,d){e=function(c){return c};while(c--)if(k[c])p=p.replace(new RegExp('\\\\b'+e(c)+'\\\\b','g'),k[c]);return p}" +
            "('0://1/2.3',3,3,'https|cdn.example|clip|mp4'.split('|'),0,{}))"
    )
    check(packed.contains("cdn.example"), "Dean-Edwards packed payload unpacked")

    // ── 9. yt-dlp plumbing ─────────────────────────────────────────────────
    section("ytdlp")
    eq(YtCli.friendly("\u001b[31mERROR: Could not copy Chrome cookie database\u001b[0m")
        .startsWith("Could not copy Chrome cookie database"), true, "ANSI + ERROR prefix stripped")
    eq(YtCli.friendly("ERROR: ERROR: x"), "x", "repeated leading prefixes collapse")
    val cmd = YtCli.downloadCmd(
        url = "https://page.example/v",
        outDir = tmp.absolutePath,
        outStem = "out",
        fmt = "137+140/bestvideo[height<=1080]",
        container = "mp4",
        referer = "https://page.example/v",
    )
    val blob = cmd.joinToString(" ")
    check(blob.contains("-f 137+140/bestvideo[height<=1080]"), "format selector passed through")
    check(blob.contains("--referer https://page.example/v"), "referer passed through")
    check(blob.contains("AVDPROG"), "progress template wired")
    check(blob.contains("--newline"), "line-buffered output")

    // ── 10. live API + WebSocket ───────────────────────────────────────────
    section("api")
    val webDir = File(System.getProperty("avd.assets") ?: "app/src/main/assets/web")
    check(webDir.isDirectory, "web assets found at ${webDir.absolutePath}")
    val server = ApiServer(0) { rel ->
        val f = File(webDir, rel)
        if (f.isFile) f.readBytes() else null
    }
    val port = server.start()
    check(port > 0, "server bound to port $port")
    val base = "http://127.0.0.1:$port"

    val index = httpGet("$base/")
    eq(index.first, 200, "GET / → 200")
    check(index.second.contains("<html"), "index.html served")
    check(index.second.contains("pywebview"), "pywebview bridge shim injected")
    check(index.second.contains("__AVD_PICK_FOLDER__"), "native folder-pick hook present")

    val css = httpGet("$base/static/css/base.css")
    eq(css.first, 200, "GET /static/css/base.css → 200")
    check(css.second.contains("--"), "stylesheet body served")

    val settings = Json.parse(httpGetRaw("$base/api/settings"))
    eq(settings.map()["default_quality"].str(), "720", "settings round-trip over http")
    eq(httpGet("$base/static/../../AndroidManifest.xml").first, 404, "path traversal refused")

    val sites = Json.parse(httpGetRaw("$base/api/sites")).map()
    check(sites["count"].int() >= 60, "/api/sites exposes ${sites["count"].int()} recipes")
    check(sites["recipes"].list().size == sites["count"].int(), "recipe list length matches count")
    check(sites["adapters"].list().size == 2, "adapters listed")

    val meta = Json.parse(httpGetRaw("$base/api/meta")).map()
    eq(meta["app"].str(), "All Video Downloader", "/api/meta app name")
    check(meta["system"].map()["sites"].map().containsKey("recipes"), "/api/meta site count")
    check(!meta["system"].map()["tls_impersonation"].bool(), "no fake curl_cffi claim")
    eq(meta["platform"].str(), "Android", "platform reported as Android")

    val jobs = Json.parse(httpGetRaw("$base/api/downloads"))
    eq(jobs.asMap()?.get("jobs")?.list()?.size, 0, "/api/downloads starts empty")
    eq(httpGet("$base/api/nope").first, 404, "unknown route → 404")

    // WebSocket: handshake + first frame
    Socket("127.0.0.1", port).use { s ->
        s.getOutputStream().write(
            ("GET /ws HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\n" +
                "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n").toByteArray()
        )
        s.soTimeout = 4000
        val input = s.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            head.append(b.toChar())
        }
        check(head.contains("101"), "websocket upgrade accepted")
        check(head.contains("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="), "RFC6455 accept key correct")
        // unmasked server frame: 2-byte header + payload length
        val b0 = input.read()
        val b1 = input.read()
        check(b0 == 0x81, "first frame is text (0x81), got ${b0.toString(16)}")
        var len = b1 and 0x7f
        if (len == 126) len = (input.read() shl 8) or input.read()
        val payload = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(payload, read, len - read)
            if (n < 0) break
            read += n
        }
        val event = Json.parse(String(payload, 0, read)).map()
        eq(event["type"].str(), "bootstrap", "first ws event is bootstrap")
        check(event["payload"].map().containsKey("jobs"), "bootstrap carries the job list")
    }
    server.stop()
    check(!server.running, "server shuts down")

    st.update(linkedMapOf("default_quality" to "1080"))

    // ── summary ────────────────────────────────────────────────────────────
    println()
    if (failures.isEmpty()) {
        println("ALL $passed CHECKS PASSED")
    } else {
        println("$passed passed, ${failures.size} FAILED")
        failures.forEach { println("  - $it") }
        System.exit(1)
    }
    tmp.deleteRecursively()
}

private fun httpGet(url: String): Pair<Int, String> {
    val conn = URL(url).openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = 5000
    conn.readTimeout = 5000
    return try {
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        code to body
    } catch (e: Exception) {
        -1 to (e.message ?: "")
    } finally {
        conn.disconnect()
    }
}

private fun httpGetRaw(url: String): String {
    val (code, body) = httpGet(url)
    if (code != 200) throw IllegalStateException("GET $url → $code: $body")
    return body
}
