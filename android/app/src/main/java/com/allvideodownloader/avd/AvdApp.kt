package com.allvideodownloader.avd

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.allvideodownloader.avd.api.ApiServer
import com.allvideodownloader.avd.core.AppConfig
import com.allvideodownloader.avd.core.Config
import com.allvideodownloader.avd.core.DownloadManager
import com.allvideodownloader.avd.core.StorageExporter
import java.io.File

/**
 * Process-wide Android wiring.
 *
 * The Python backend assumed a user home directory (`~/.avd`, `~/Downloads`);
 * on Android those two paths are app storage, so they are injected here *before*
 * anything reads the settings — exactly what `config.py` does with its paths,
 * just pointed at `filesDir` / external files.
 */
class AvdApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        // settings.json lives in the app's private files dir (= ~/.avd)
        AppConfig.configDir = File(filesDir, "config")
        // downloads: app-specific external storage — a real, resumable path that
        // needs no permission and is visible over USB/MTP. The user can pick a
        // different folder in Settings (SAF) which then exports finished files.
        val downloadDir = outerDownloadsDir()
        AppConfig.defaultDownloadDir = downloadDir.absolutePath
        StorageExporter.stagingDir = File(cacheDir, "downloads")

        Config.settings()   // load + migrate now, so /api/settings is instant

        StorageExporter.exporter = { file, treeUri -> SafExport.export(this, file, treeUri) }
        ApiServer.ExitBridge.exit = { }
        ApiServer.OpenPathBridge.opener = { path -> FileOpener.open(this, path) }

        createNotificationChannel()
        DownloadManager.instance// touch the singleton early
    }

    private fun outerDownloadsDir(): File {
        val base = getExternalFilesDir(null) ?: filesDir
        val dir = File(base, "All Video Downloader")
        dir.mkdirs()
        return dir
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps downloads running while the app is in the background"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "avd.downloads"
        lateinit var instance: AvdApp
            private set
    }
}
