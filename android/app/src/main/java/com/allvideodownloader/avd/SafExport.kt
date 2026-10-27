package com.allvideodownloader.avd

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Writes finished downloads into a folder the user picked with the system picker.
 *
 * The desktop app can write straight into any path. Android grants access to a
 * user-chosen folder as a *document tree* (SAF), which is not appendable — so
 * jobs are staged in the app's cache (keeping pause/resume and Range semantics)
 * and copied into the picked folder when they finish. That is the smallest sound
 * adaptation of the original "download folder" setting.
 */
object SafExport {

    fun export(context: Context, file: File, treeUri: String): Boolean {
        return try {
            val tree = Uri.parse(treeUri)
            val resolver = context.contentResolver
            val parentDoc = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
            val mime = mimeTypeOf(file.name)
            val docUri = DocumentsContract.createDocument(resolver, parentDoc, mime, file.name)
                ?: DocumentsContract.createDocument(resolver, parentDoc, mime, uniqueName(file)) ?: return false
            resolver.openOutputStream(docUri, "w")?.use { out ->
                file.inputStream().use { input -> input.copyTo(out, 256 * 1024) }
            } ?: return false
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun uniqueName(file: File): String {
        val stem = file.name.substringBeforeLast('.', file.name)
        val ext = if (file.name.contains('.')) file.name.substring(file.name.lastIndexOf('.')) else ""
        return "$stem-${System.currentTimeMillis()}$ext"
    }

    fun mimeTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts" -> "video/mp2t"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "flv" -> "video/x-flv"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }
}
