package com.lumalink.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream

/** Call on an I/O executor, only after file hash verification. */
class ReceivedFileWriter(private val context: Context) {
    fun save(bytes: ByteArray, filename: String): String {
        val destination = FileDestination.forName(filename)
        return if (Build.VERSION.SDK_INT >= 29) saveMediaStore(bytes, destination)
        else saveLegacy(bytes, destination)
    }

    @RequiresApi(29)
    private fun saveMediaStore(bytes: ByteArray, file: FileDestination): String {
        val resolver = context.contentResolver
        val collection = when (file.folder) {
            "Pictures" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "Movies" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "Music" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, file.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${file.folder}/LumaLink")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("Cannot create received file")
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes) } ?: error("Cannot write received file")
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
                "Cannot publish received file"
            }
            val savedName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else file.name
            } ?: file.name
            return "${file.folder}/LumaLink/$savedName"
        } catch (failure: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(bytes: ByteArray, destination: FileDestination): String {
        check(Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) { "Shared storage is unavailable" }
        val directory = File(Environment.getExternalStoragePublicDirectory(destination.folder), "LumaLink")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create destination folder" }
        // createNewFile reserves a unique path without overwriting an existing file.
        val original = destination.name
        val dot = original.lastIndexOf('.').takeIf { it > 0 } ?: original.length
        var target: File? = null
        for (index in 0..999) {
            val name = if (index == 0) original else "${original.take(dot)} ($index)${original.substring(dot)}"
            val candidate = File(directory, name)
            if (candidate.createNewFile()) { target = candidate; break }
        }
        val file = target ?: error("Too many files with this name")
        try {
            FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
        runCatching { MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(destination.mime), null) }
        return "${destination.folder}/LumaLink/${file.name}"
    }
}
