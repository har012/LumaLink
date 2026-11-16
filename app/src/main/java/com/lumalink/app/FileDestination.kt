package com.lumalink.app

import java.util.Locale

/** Extension-based routing; file bytes are never changed or inferred as trusted media. */
data class FileDestination(val name: String, val mime: String, val folder: String) {
    companion object {
        fun forName(input: String): FileDestination {
            val name = input.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_").trim().trim('.').take(100)
                .ifBlank { "received.bin" }
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val mime = when (extension) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "bmp" -> "image/bmp"
                "heic", "heif" -> "image/heif"
                "avif" -> "image/avif"
                "mp4", "m4v" -> "video/mp4"
                "mov" -> "video/quicktime"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "3gp" -> "video/3gpp"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "aac" -> "audio/aac"
                "wav" -> "audio/wav"
                "ogg", "oga" -> "audio/ogg"
                "opus" -> "audio/opus"
                "flac" -> "audio/flac"
                "pdf" -> "application/pdf"
                "txt" -> "text/plain"
                "zip" -> "application/zip"
                else -> "application/octet-stream"
            }
            val folder = when (mime.substringBefore('/')) {
                "image" -> "Pictures"
                "video" -> "Movies"
                "audio" -> "Music"
                else -> "Download"
            }
            return FileDestination(name, mime, folder)
        }
    }
}
