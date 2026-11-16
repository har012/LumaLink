package com.lumalink.app

import org.junit.Assert.*
import org.junit.Test

class FileDestinationTest {
    @Test fun routesCommonTypesAndUppercaseExtensions() {
        for ((name, folder) in mapOf("photo.JPG" to "Pictures", "clip.mp4" to "Movies", "song.FLAC" to "Music",
            "report.pdf" to "Download", "archive.zip" to "Download", "unknown" to "Download")) {
            assertEquals(folder, FileDestination.forName(name).folder)
        }
        assertEquals("image/jpeg", FileDestination.forName("photo.JPG").mime)
        assertEquals("application/octet-stream", FileDestination.forName("unknown.weird").mime)
    }
    @Test fun removesPathsControlCharactersAndInvalidNames() {
        assertEquals("photo.png", FileDestination.forName("../../photo.png").name)
        assertEquals("photo.png", FileDestination.forName("C:\\temp\\photo.png").name)
        assertEquals("received.bin", FileDestination.forName("..").name)
        assertEquals("x_y.txt", FileDestination.forName("x\ny.txt").name)
        assertEquals("Download", FileDestination.forName("photo.jpg.exe").folder)
        assertTrue(FileDestination.forName("a".repeat(200)).name.length <= 100)
    }
}
