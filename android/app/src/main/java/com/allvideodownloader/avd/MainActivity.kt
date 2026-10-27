package com.allvideodownloader.avd

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.allvideodownloader.avd.api.ApiServer
import java.io.File

/**
 * The Android counterpart of `webfinder/desktop/window.py`.
 *
 * The desktop shell is pywebview/WebView2 pointed at the FastAPI backend. Here a
 * WebView points at the same backend running on loopback inside the app, so the
 * *unchanged* `web/` frontend (all three views, every interaction) is what the
 * user sees. The pywebview JS bridge is emulated so the frontend's window/folder
 * calls keep working:
 *
 * ```
 * minimize / toggle_maximize  → no-ops (the system owns the window frame)
 * close                       → finishes the activity
 * pick_folder                 → Storage Access Framework folder picker
 * ```
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var server: ApiServer? = null
    private var port = 0
    private var pendingShareUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        webView = WebView(this)
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = webView.settings.userAgentString
        }
        webView.setBackgroundColor(0xFF0B0B12.toInt())
        webView.addJavascriptInterface(NativeBridge(), "__AVD_NATIVE__")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                // keep the app's own loopback UI inside the WebView; hand external
                // links (thumbnails, "open in browser") to the system browser
                return if (url.startsWith("http://127.0.0.1")) {
                    false
                } else {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (pendingShareUrl != null) handOffShare()
            }
        }

        pendingShareUrl = sharedText(intent)
        startBackend()
        DownloadService.ensureRunning(this)
        askNotificationPermission()
    }

    private fun startBackend() {
        val server = ApiServer(0) { rel -> readAsset(rel) }
        this.server = server
        // native exit hook: the sidebar's red Exit stops the app like the desktop
        ApiServer.ExitBridge.exit = {
            runOnUiThread {
                runCatching { this.server?.stop() }
                stopService(Intent(this, DownloadService::class.java))
                finishAffinity()
            }
        }
        port = try {
            server.start()
        } catch (e: Exception) {
            0
        }
        if (port == 0) return
        webView.loadUrl("http://127.0.0.1:$port/")
    }

    /** Serve the bundled `web/` folder (copied verbatim from the repo root). */
    private fun readAsset(rel: String): ByteArray? {
        val clean = rel.trimStart('/').substringBefore('#').substringBefore('?')
        return try {
            assets.open("web/$clean").use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    // ── the pywebview-compatible bridge ──────────────────────────────────
    inner class NativeBridge {
        @JavascriptInterface
        fun minimize(arg: String): String = ""

        @JavascriptInterface
        fun toggle_maximize(arg: String): String = ""

        @JavascriptInterface
        fun close(arg: String): String {
            runOnUiThread {
                runCatching { server?.stop() }
                stopService(Intent(this@MainActivity, DownloadService::class.java))
                finishAffinity()
            }
            return "true"
        }

        @JavascriptInterface
        fun pick_folder(arg: String): String {
            runOnUiThread { openFolderPicker() }
            return ""
        }

        @JavascriptInterface
        fun app_version(arg: String): String = com.allvideodownloader.avd.core.AppConfig.APP_VERSION
    }

    private fun openFolderPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                )
            }
            startActivityForResult(intent, REQ_PICK_FOLDER)
        } catch (_: Exception) {
            resolvePickedFolder(null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_FOLDER) return
        val uri = if (resultCode == RESULT_OK) data?.data else null
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        resolvePickedFolder(uri?.toString())
    }

    private fun resolvePickedFolder(uri: String?) {
        val js = "window.__AVD_PICK_FOLDER__ && window.__AVD_PICK_FOLDER__(" +
            (if (uri == null) "null" else "'" + uri.replace("'", "\\'") + "'") + ")"
        runOnUiThread { webView.evaluateJavascript(js, null) }
    }

    // ── shared-link intent (ACTION_SEND text/plain) ──────────────────────
    private fun sharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val url = sharedText(intent) ?: return
        pendingShareUrl = url
        handOffShare()
    }

    private fun handOffShare() {
        val url = pendingShareUrl ?: return
        if (port == 0) return
        pendingShareUrl = null
        val escaped = url.replace("\\", "\\\\").replace("'", "\\'")
        webView.evaluateJavascript(
            "(function(){var i=document.getElementById('direct-url');" +
                "if(i){i.value='$escaped';var b=document.getElementById('btn-analyze');" +
                "if(b){b.click();}}})()",
            null,
        )
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        runCatching { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7) }
    }

    override fun onBackPressed() {
        // The UI is a single-page app: back leaves the app. Downloads keep running
        // in the foreground service, exactly like the desktop app keeps working
        // while its window is minimised.
        finish()
    }

    override fun onDestroy() {
        if (isFinishing) {
            runCatching { server?.stop() }
        }
        runCatching { webView.destroy() }
        super.onDestroy()
    }

    companion object {
        private const val REQ_PICK_FOLDER = 41
    }
}
