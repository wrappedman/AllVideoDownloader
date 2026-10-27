package com.allvideodownloader.avd

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * A tiny, dependency-free file provider.
 *
 * `androidx.core.content.FileProvider` would need an AndroidX dependency and this
 * build has no Gradle/Maven access, so the same job is done in ~60 lines: expose
 * finished downloads over `content://` so the system player or a file manager can
 * open them (Android 7+ forbids `file://` URIs in intents).
 */
class AvdFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun roots(): List<File> {
        val ctx = context ?: return emptyList()
        return listOfNotNull(
            ctx.getExternalFilesDir(null),
            ctx.filesDir,
            ctx.cacheDir,
        )
    }

    private fun resolve(uri: Uri): File? {
        val path = uri.path?.removePrefix("/") ?: return null
        for (root in roots()) {
            val candidate = File(root, path).canonicalFile
            if (candidate.absolutePath.startsWith(root.canonicalFile.absolutePath)) {
                if (candidate.exists()) return candidate
            }
        }
        return null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = resolve(uri) ?: throw FileNotFoundException("No such file: $uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? {
        val file = resolve(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols, 1)
        val row = cursor.newRow()
        for (col in cols) {
            when (col) {
                OpenableColumns.DISPLAY_NAME -> row.add(file.name)
                OpenableColumns.SIZE -> row.add(file.length())
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String =
        SafExport.mimeTypeOf(uri.lastPathSegment ?: "")

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        fun uriFor(context: Context, file: File): Uri {
            val root = listOfNotNull(context.getExternalFilesDir(null), context.filesDir, context.cacheDir)
                .map { it.canonicalFile }
                .firstOrNull { file.canonicalFile.absolutePath.startsWith(it.absolutePath) }
            val rel = root?.let { file.canonicalFile.relativeTo(it).path } ?: file.name
            return Uri.parse("content://com.allvideodownloader.avd.fileprovider/$rel")
        }
    }
}
