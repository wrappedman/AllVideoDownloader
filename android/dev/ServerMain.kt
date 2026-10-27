// Boots the shipping ApiServer on a desktop JVM so a browser/DOM can drive the
// real frontend against the real API. Used by dev/ui_test.mjs.
package dev

import com.allvideodownloader.avd.api.ApiServer
import com.allvideodownloader.avd.core.AppConfig
import com.allvideodownloader.avd.core.Config
import java.io.File

fun main(args: Array<String>) {
    val webDir = File(args.getOrElse(0) { "app/src/main/assets/web" })
    val dataDir = File(args.getOrElse(1) { "build/ui-test-data" })
    dataDir.mkdirs()
    AppConfig.configDir = File(dataDir, "config")
    AppConfig.defaultDownloadDir = File(dataDir, "downloads").absolutePath
    Config.settings().update(linkedMapOf("download_dir" to AppConfig.defaultDownloadDir))

    val server = ApiServer(0) { rel ->
        val f = File(webDir, rel)
        if (f.isFile) f.readBytes() else null
    }
    val port = server.start()
    println("LISTENING $port")
    System.out.flush()
    // keep the JVM alive until the harness kills it
    java.util.concurrent.CountDownLatch(1).await()
}
