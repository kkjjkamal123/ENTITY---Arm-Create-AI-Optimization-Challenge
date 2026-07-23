package com.entity.bench

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Where .gguf files live for the bench app. Mirrors the chat app's ModelStore so the two
 * apps enumerate and name models identically - the Models screen is shared code in all but
 * package name.
 */
object ModelStore {
    fun dirs(ctx: Context): List<File> =
        listOfNotNull(ctx.getExternalFilesDir("models"), File(ctx.filesDir, "models"))
            .onEach { if (!it.exists()) it.mkdirs() }

    fun scan(ctx: Context): List<File> =
        dirs(ctx)
            .flatMap { it.listFiles { f -> f.extension == "gguf" }?.toList() ?: emptyList() }
            .distinctBy { it.name }
            .sortedBy { it.name }

    fun sizeLabel(bytes: Long): String =
        if (bytes >= 1_000_000_000L) String.format("%.2f GB", bytes / 1e9)
        else String.format("%.0f MB", bytes / 1e6)

    /** Display name of a picked document, forced to end in .gguf so scan() finds it. */
    fun safeName(cr: ContentResolver, uri: Uri): String {
        var name: String? = null
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) name = c.getString(i)
                }
            }
        }
        var clean = (name ?: "model-${System.currentTimeMillis()}").substringAfterLast('/').trim()
        if (!clean.endsWith(".gguf", ignoreCase = true)) clean += ".gguf"
        return clean
    }

    /** Size of a picked document, or -1 when the provider does not report one. */
    fun sizeOf(cr: ContentResolver, uri: Uri): Long {
        var size = -1L
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0 && !c.isNull(i)) size = c.getLong(i)
                }
            }
        }
        return size
    }
}
