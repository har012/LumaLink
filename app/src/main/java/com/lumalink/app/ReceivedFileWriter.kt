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
import java.io.InputStream

/** Call on an I/O executor, only after file hash verification. */
class ReceivedFileWriter(private val context: Context) {
    fun save(bytes: ByteArray, filename: String): String {
        return save({ bytes.inputStream() }, filename)
    }
    fun save(source: File, filename: String, progress: (Long) -> Unit = {}): String = save({ source.inputStream() }, filename, progress)
    private fun save(open: () -> InputStream, filename: String, progress: (Long) -> Unit = {}): String {
        val destination = FileDestination.forName(filename)
        return if (Build.VERSION.SDK_INT >= 29) saveMediaStore(open, destination, progress)
        else saveLegacy(open, destination, progress)
    }

    @RequiresApi(29)
    private fun saveMediaStore(open: () -> InputStream, file: FileDestination, progress: (Long) -> Unit): String {
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
            resolver.openOutputStream(uri, "w")?.use { out -> open().use { copy(it, out, progress) } } ?: error("Cannot write received file")
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
    private fun saveLegacy(open: () -> InputStream, destination: FileDestination, progress: (Long) -> Unit): String {
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
            FileOutputStream(file).use { out -> open().use { copy(it, out, progress) }; out.fd.sync() }
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
        runCatching { MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(destination.mime), null) }
        return "${destination.folder}/LumaLink/${file.name}"
    }
    private fun copy(input: InputStream, output: java.io.OutputStream, progress: (Long) -> Unit) {
        val buffer = ByteArray(32 * 1024); var copied = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Saving cancelled")
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count); copied += count; progress(copied)
        }
    }
}
