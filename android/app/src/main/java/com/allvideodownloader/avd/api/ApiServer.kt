package com.allvideodownloader.avd.api

import com.allvideodownloader.avd.core.Adapters
import com.allvideodownloader.avd.core.AppConfig
import com.allvideodownloader.avd.core.Config
import com.allvideodownloader.avd.core.Cookies
import com.allvideodownloader.avd.core.DownloadJob
import com.allvideodownloader.avd.core.DownloadManager
import com.allvideodownloader.avd.core.Ffmpeg
import com.allvideodownloader.avd.core.Json
import com.allvideodownloader.avd.core.Media
import com.allvideodownloader.avd.core.Net
import com.allvideodownloader.avd.core.Sites
import com.allvideodownloader.avd.core.YtCli
import com.allvideodownloader.avd.core.asMap
import com.allvideodownloader.avd.core.bool
import com.allvideodownloader.avd.core.int
import com.allvideodownloader.avd.core.list
import com.allvideodownloader.avd.core.map
import com.allvideodownloader.avd.core.str
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Android port of `webfinder/app.py` + `webfinder/api/__init__.py`.
 *
 * The desktop backend is FastAPI on a loopback port; the Android build keeps the
 * same shape — a loopback HTTP server that serves the *unchanged* `web/` frontend
 * from the APK's assets and answers the same REST + WebSocket contract. That is
 * what lets the original UI (three views, quality chips, download rows, settings,
 * live progress) run here without rewriting a single line of JavaScript.
 *
 * Implemented with plain sockets on purpose: no third-party HTTP/WebSocket
 * library is available without a Gradle/Maven dependency fetch, and the route
 * surface is small and fixed.
 */
class ApiServer(
    private val portRequested: Int = 0,
    private val assets: ((String) -> ByteArray?) = { null },
) {
    @Volatile private var server: ServerSocket? = null
    @Volatile var port: Int = 0
        private set
    @Volatile var running: Boolean = false
        private set

    private val wsClients = CopyOnWriteArrayList<WsClient>()
    private val manager: DownloadManager get() = DownloadManager.instance

    fun start(): Int {
        val socket = ServerSocket(portRequested, 50, InetAddress.getByName("127.0.0.1"))
        server = socket
        port = socket.localPort
        running = true
        manager.emit = { event -> broadcast(event) }
        Thread({
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                Thread({ serve(client) }, "avd-http").also { it.isDaemon = true; it.start() }
            }
        }, "avd-http-accept").also { it.isDaemon = true; it.start() }
        return port
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        for (c in wsClients) runCatching { c.close() }
        wsClients.clear()
    }

    // ── HTTP plumbing ─────────────────────────────────────────────────────
    private class Request(
        val method: String,
        val path: String,
        val query: MutableMap<String, String>,
        val headers: MutableMap<String, String>,
        val body: String,
        val socket: Socket,
    )

    private class Response(val status: Int, val reason: String, val body: ByteArray, val contentType: String)

    private fun serve(socket: Socket) {
        try {
            val input = socket.getInputStream().buffered()
            val request = readRequest(input, socket) ?: return
            if (request.headers["upgrade"].equals("websocket", true)) {
                upgradeToWebSocket(request)
                return
            }
            val response = try {
                route(request)
            } catch (e: ApiError) {
                jsonError(e.status, e.message ?: "error")
            } catch (e: Exception) {
                jsonError(500, (e.message ?: e.javaClass.simpleName).take(300))
            }
            write(socket.getOutputStream(), request, response)
        } catch (_: Exception) {
            // a dead client is not an app error
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readRequest(input: InputStream, socket: Socket): Request? {
        val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase(Locale.ROOT)
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = LinkedHashMap<String, String>()
        if (target.contains('?')) {
            for (pair in target.substringAfter('?').split('&')) {
                if (pair.isEmpty()) continue
                val k = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
                val v = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
                query[k] = v
            }
        }
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase(Locale.ROOT)] = line.substring(idx + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) {
            val buf = CharArray(length)
            var read = 0
            while (read < length) {
                val n = reader.read(buf, read, length - read)
                if (n <= 0) break
                read += n
            }
            String(String(buf, 0, read).toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        } else ""
        return Request(method, path, query, headers, body, socket)
    }

    private fun write(out: OutputStream, request: Request, response: Response) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(response.status).append(' ').append(response.reason).append("\r\n")
        head.append("Content-Type: ").append(response.contentType).append("\r\n")
        head.append("Content-Length: ").append(response.body.size).append("\r\n")
        head.append("Access-Control-Allow-Origin: *\r\n")
        head.append("Access-Control-Allow-Methods: *\r\n")
        head.append("Access-Control-Allow-Headers: *\r\n")
        head.append("Cache-Control: no-cache, no-store\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(response.body)
        out.flush()
    }

    private fun json(value: Any?, status: Int = 200): Response =
        Response(status, if (status == 200) "OK" else "Error",
            Json.write(value).toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")

    private fun jsonError(status: Int, detail: String): Response =
        json(linkedMapOf("detail" to detail), status)

    private class ApiError(val status: Int, message: String) : RuntimeException(message)

    private fun body(request: Request): MutableMap<String, Any?> =
        Json.parseOrNull(request.body).asMap() ?: LinkedHashMap()

    // ── routing (mirrors router = APIRouter(prefix="/api")) ───────────────
    private fun route(request: Request): Response {
        val path = request.path
        if (path == "/" || path == "/index.html") {
            val index = assets("index.html") ?: return Response(500, "Error",
                "frontend missing".toByteArray(), "text/plain")
            // The frontend injects the pywebview bridge shim itself; on Android the
            // native bridge is installed by MainActivity before page load.
            return Response(200, "OK", String(index, Charsets.UTF_8)
                .replace("</head>", bridgeShim() + "</head>").toByteArray(Charsets.UTF_8), "text/html; charset=utf-8")
        }
        if (path.startsWith("/static/")) {
            val rel = path.removePrefix("/static/")
            if (rel.split("/").any { it == ".." }) {
                return Response(404, "Not Found", ByteArray(0), "text/plain")
            }
            val bytes = assets(rel) ?: return Response(404, "Not Found", ByteArray(0), "text/plain")
            return Response(200, "OK", bytes, contentType(rel))
        }
        if (path == "/api/meta") return json(meta())
        if (path == "/api/settings") {
            return if (request.method == "PUT") {
                val patch = body(request)["patch"].map()
                val allowed = Config.settings().snapshot().keys
                val filtered = patch.filterKeys { it in allowed }
                val merged = Config.settings().update(filtered)
                if (filtered.keys.any { it == "ytdlp_browser_cookies" || it == "cookies_file" }) Cookies.reset()
                DownloadManager.instance.rebuildSemaphore()
                json(merged)
            } else json(Config.settings().snapshot())
        }
        if (path == "/api/cookies/status") return json(Cookies.source())
        if (path == "/api/cookies/probe") return json(Cookies.probe())
        if (path == "/api/cookies/use-best") return json(Cookies.useBest())
        if (path == "/api/sites") {
            return json(linkedMapOf<String, Any?>(
                "count" to Sites.RECIPES.size,
                "impersonation" to Net.hasImpersonation(),
                "families" to Sites.FAMILIES.keys.sorted(),
                "embed_hosts" to Sites.EMBED_HOSTS.map { it.label },
                "adapters" to Adapters.catalog(),
                "recipes" to Sites.catalog(),
            ))
        }
        if (path == "/api/scan") {
            val url = body(request)["url"].str()
            return json(linkedMapOf("candidates" to Media.scanPage(url, 24)))
        }
        if (path == "/api/analyze") {
            val url = body(request)["url"].str()
            return try {
                json(Media.analyze(url))
            } catch (e: Exception) {
                jsonError(502, (e.message ?: "").take(300))
            }
        }
        if (path == "/api/analyze/download") {
            val payload = body(request)
            val plan = Media.planDownload(
                payload["url"].str(),
                payload["title"].str(),
                payload["format"].asMap(),
            )
            if (payload["referer"].str().isNotEmpty()) {
                val meta = plan["meta"].asMap() ?: LinkedHashMap<String, Any?>().also { plan["meta"] = it }
                meta["referer"] = payload["referer"].str()
            }
            if (plan["kind"].str() == "magnet") {
                return jsonError(400, "Magnet links can only be copied — open them with your torrent client.")
            }
            val job = manager.enqueue(
                url = plan["url"].str(),
                kind = plan["kind"].str(),
                filename = plan["filename"].str(),
                title = plan["title"].str(),
                fileType = plan["file_type"].str().ifEmpty { "link" },
                meta = plan["meta"].asMap(),
            )
            return json(linkedMapOf("job_id" to job.id))
        }
        if (path == "/api/downloads" && request.method == "GET") {
            return json(linkedMapOf("jobs" to manager.listJobs()))
        }
        if (path == "/api/downloads" && request.method == "POST") {
            val payload = body(request)
            val url = payload["url"].str().trim()
            val low = url.lowercase(Locale.ROOT)
            if (!low.startsWith("http://") && !low.startsWith("https://") &&
                !low.startsWith("magnet:") && !low.startsWith("ftp://")
            ) throw ApiError(400, "Only http/https/ftp URLs can be downloaded.")
            if (low.startsWith("magnet:")) {
                throw ApiError(400, "Magnet links are not direct files — copy the link and open it in your torrent client.")
            }
            var kind = payload["kind"].str()
            var meta = payload["meta"].asMap() ?: LinkedHashMap()
            if (kind.isEmpty()) {
                val plan = Media.planDownload(url, payload["title"].str(), meta["fmt"].asMap())
                kind = plan["kind"].str()
                meta = LinkedHashMap(plan["meta"].asMap() ?: LinkedHashMap()).also { it.putAll(meta) }
            }
            val job = manager.enqueue(
                url = url, kind = kind, filename = payload["filename"].str(),
                title = payload["title"].str(), meta = meta,
            )
            return json(job.toDict())
        }
        if (path == "/api/downloads/clear") return json(linkedMapOf("removed" to manager.clearFinished()))
        if (path.startsWith("/api/downloads/")) {
            val rest = path.removePrefix("/api/downloads/")
            val id = rest.substringBefore('/')
            val action = rest.substringAfter('/', "")
            if (id.isNotEmpty() && action.isEmpty() && request.method == "DELETE") {
                jobOr404(id)
                return json(linkedMapOf("ok" to manager.remove(id)))
            }
            val job = jobOr404(id)
            return when (action) {
                "pause" -> json(linkedMapOf("ok" to manager.pause(job.id)))
                "resume" -> json(linkedMapOf("ok" to manager.resume(job.id)))
                "cancel" -> json(linkedMapOf("ok" to manager.cancel(job.id)))
                "retry" -> json(linkedMapOf("ok" to manager.retry(job.id)))
                else -> jsonError(404, "Unknown download action")
            }
        }
        if (path == "/api/system/open-path") {
            val target = request.query["path"].orEmpty().ifEmpty { Config.settings().getString("download_dir") }
            val ok = OpenPathBridge.open(target)
            return if (ok) json(linkedMapOf("ok" to true, "opened" to target))
            else jsonError(500, "No file manager available to launch")
        }
        if (path == "/api/system/selfupdate") {
            // The desktop pip-upgrades its bundled yt-dlp here. On Android the
            // engine is a user-supplied binary, so this re-detects it and reports
            // honestly instead of pretending to update anything.
            val engine = YtCli.detectFresh()
            return json(linkedMapOf<String, Any?>(
                "ok" to engine["use_cli"],
                "version" to engine["version"],
                "restart_needed" to false,
                "detail" to if (engine["use_cli"].bool())
                    "yt-dlp ${engine["version"].str()} is active."
                else
                    "No yt-dlp binary is installed on this device. Android cannot pip-update it: " +
                        "push a yt-dlp build into the app's files folder (or a Python that can run it) " +
                        "and press Update again.",
            ))
        }
        if (path == "/api/system/exit") {
            ExitBridge.exit()
            return json(linkedMapOf("ok" to true, "msg" to "All Video Downloader is closing."))
        }
        return jsonError(404, "Not Found")
    }

    private fun jobOr404(id: String): DownloadJob =
        manager.jobs[id] ?: throw ApiError(404, "Unknown download id")

    private fun meta(): MutableMap<String, Any?> {
        val ff = Ffmpeg.find()
        val engine = YtCli.detect()
        val sites = linkedMapOf<String, Any?>("recipes" to Sites.RECIPES.size)
        return linkedMapOf(
            "app" to AppConfig.APP_NAME,
            "version" to AppConfig.APP_VERSION,
            "platform" to "Android",
            "python" to "",
            "system" to linkedMapOf<String, Any?>(
                "ffmpeg" to linkedMapOf(
                    "ffmpeg" to ff.ffmpeg, "ffprobe" to ff.ffprobe, "available" to ff.available,
                ),
                "yt_dlp" to engine["version"],
                "yt_dlp_engine" to linkedMapOf<String, Any?>(
                    "use_cli" to engine["use_cli"], "exe" to engine["exe"],
                    "module_version" to engine["module_version"],
                    "cli_version" to engine["cli_version"],
                    "label" to engine["label"],
                ),
                "desktop" to false,
                "cookies" to Cookies.source(),
                "tls_impersonation" to Net.hasImpersonation(),
                "sites" to sites,
            ),
            "config_file" to AppConfig.configFile.absolutePath,
        )
    }

    private fun contentType(name: String): String = when (name.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    // ── WebSocket (the WsHub equivalent) ─────────────────────────────────
    private class WsClient(val socket: Socket, val out: OutputStream) {
        fun send(text: String) {
            synchronized(out) {
                val payload = text.toByteArray(Charsets.UTF_8)
                out.write(0x81)
                when {
                    payload.size < 126 -> out.write(payload.size)
                    payload.size < 65536 -> {
                        out.write(126)
                        out.write((payload.size shr 8) and 0xff)
                        out.write(payload.size and 0xff)
                    }
                    else -> {
                        out.write(127)
                        for (i in 7 downTo 0) out.write(((payload.size.toLong() shr (8 * i)) and 0xff).toInt())
                    }
                }
                out.write(payload)
                out.flush()
            }
        }

        fun close() {
            runCatching { out.close() }
            runCatching { socket.close() }
        }
    }

    private fun broadcast(event: MutableMap<String, Any?>) {
        if (wsClients.isEmpty()) return
        val payload = Json.write(event)
        for (client in wsClients) {
            try {
                client.send(payload)
            } catch (_: Exception) {
                wsClients.remove(client)
                client.close()
            }
        }
    }

    private fun upgradeToWebSocket(request: Request) {
        val key = request.headers["sec-websocket-key"] ?: return
        val accept = com.allvideodownloader.avd.core.Util.base64Encode(
            MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.ISO_8859_1)),
        )
        val out = request.socket.getOutputStream()
        val head = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.flush()
        val client = WsClient(request.socket, out)
        wsClients.add(client)
        try {
            client.send(Json.write(linkedMapOf<String, Any?>(
                "type" to "bootstrap", "payload" to linkedMapOf("jobs" to manager.listJobs()),
            )))
            // keep-alive: the frontend sends "ping" every 25 s
            val input = request.socket.getInputStream().buffered()
            while (true) {
                val frame = readFrame(input) ?: break
                when (frame.first) {
                    0x1 -> {
                        val text = String(frame.second, Charsets.UTF_8)
                        if (text == "ping") client.send("""{"type":"pong"}""")
                    }
                    0x8 -> break
                    0x9 -> sendControl(out, 0xA, frame.second)
                }
            }
        } catch (_: Exception) {
            // client vanished
        } finally {
            wsClients.remove(client)
            client.close()
        }
    }

    private fun sendControl(out: OutputStream, opcode: Int, data: ByteArray) {
        synchronized(out) {
            out.write(0x80 or opcode)
            out.write(data.size)
            out.write(data)
            out.flush()
        }
    }

    private fun readFrame(input: InputStream): Pair<Int, ByteArray>? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) return null
        val opcode = b0 and 0x0f
        val masked = (b1 and 0x80) != 0
        var length = (b1 and 0x7f).toLong()
        if (length == 126L) {
            length = ((input.read() shl 8) or input.read()).toLong()
        } else if (length == 127L) {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or input.read().toLong()
            length = v
        }
        val mask = ByteArray(4)
        if (masked) {
            var read = 0
            while (read < 4) {
                val n = input.read(mask, read, 4 - read)
                if (n <= 0) return null
                read += n
            }
        }
        val data = ByteArray(length.toInt())
        var read = 0
        while (read < data.size) {
            val n = input.read(data, read, data.size - read)
            if (n <= 0) return null
            read += n
        }
        if (masked) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        return opcode to data
    }

    // ── native bridges the Android layer plugs in ────────────────────────
    object OpenPathBridge {
        /** Opens a download / folder with the platform file manager. */
        var opener: ((String) -> Boolean)? = null
        fun open(path: String): Boolean = opener?.invoke(path) ?: false
    }

    object ExitBridge {
        var exit: () -> Unit = {}
    }

    /**
     * The `window.pywebview.api` shim, so the frontend's existing desktop bridge
     * (folder picker, window controls) works without touching its JavaScript.
     */
    private fun bridgeShim(): String = """
        <script>
        // Android: emulate the pywebview bridge the unchanged frontend talks to.
        // `window.__AVD_NATIVE__` is injected by MainActivity (addJavascriptInterface).
        (function () {
          const native = () => window.__AVD_NATIVE__ || null;
          const call = (name, arg) => new Promise((resolve) => {
            try {
              const n = native();
              if (!n) return resolve(null);
              const out = n[name](arg === undefined ? "" : String(arg));
              resolve(out === undefined || out === null || out === "" ? null
                      : (typeof out === "string" ? JSON.parse(out) : out));
            } catch (e) { resolve(null); }
          });
          // A SAF folder picker finishes long after the click: the native side
          // resolves the pending promise through this hook.
          window.__AVD_PICK_RESOLVE__ = null;
          window.__AVD_PICK_FOLDER__ = function (uri) {
            const r = window.__AVD_PICK_RESOLVE__;
            window.__AVD_PICK_RESOLVE__ = null;
            if (r) r(uri || null);
          };
          window.pywebview = { api: {
            minimize: () => call("minimize"),
            toggle_maximize: () => call("toggle_maximize"),
            close: () => call("close"),
            pick_folder: () => new Promise((resolve) => {
              window.__AVD_PICK_RESOLVE__ = resolve;
              const n = native();
              if (!n) { window.__AVD_PICK_RESOLVE__ = null; return resolve(null); }
              try { n.pick_folder(""); } catch (e) { window.__AVD_PICK_RESOLVE__ = null; resolve(null); }
            }),
          }};
        })();
        </script>
    """.trimIndent()
}
