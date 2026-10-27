package com.allvideodownloader.avd

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File

/**
 * `/api/system/open-path` on Android: reveal a download (or its folder).
 *
 * The desktop shells out to explorer/open/xdg-open. Android has no shell, so the
 * same intent is expressed with an `ACTION_VIEW`: a finished file opens in the
 * player, a folder opens in the file manager, and a SAF tree URI opens in
 * DocumentsUI.
 */
object FileOpener {

    fun open(context: Context, path: String): Boolean {
        if (path.isEmpty()) return false
        return try {
            when {
                path.startsWith("content://") -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(path))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(intent)
                    true
                }
                else -> {
                    var file = File(path)
                    // a stale path (renamed after merge, cleaned up…) → open the
                    // closest existing parent instead of dead-ending
                    while (!file.exists() && file.parentFile != null) file = file.parentFile!!
                    if (!file.exists()) return false
                    if (file.isDirectory) {
                        val intent = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(AvdFileProvider.uriFor(context, file), "resource/folder")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            // no folder viewer installed: open the file itself
                            val first = file.listFiles()?.filter { it.isFile }?.maxByOrNull { it.length() }
                                ?: return false
                            return open(context, first.absolutePath)
                        }
                        true
                    } else {
                        val uri = AvdFileProvider.uriFor(context, file)
                        val mime = MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(file.extension.lowercase())
                            ?: SafExport.mimeTypeOf(file.name)
                        val intent = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, mime)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(intent)
                        true
                    }
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
