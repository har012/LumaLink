package com.lumalink.app

import java.io.*
import java.util.UUID
import java.util.zip.CRC32

/** Private append-only journal. Restore valid prefixes; never trust an incomplete tail. */
internal class OpticalCheckpoint(private val file: File) {
    companion object { private val guard = Any() }
    private inline fun <T> locked(block: () -> T): T = synchronized(guard, block)
    private var generation: String? = null
    fun exists() = file.isFile
    fun reset(): Unit = locked {
        file.parentFile?.mkdirs()
        generation = UUID.randomUUID().toString()
        val temporary = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(FileOutputStream(temporary)).use { it.writeUTF("LLJ1"); it.writeUTF(generation!!) }
        java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        Unit
    }
    fun append(packet: String): Unit = locked {
        val storedGeneration = DataInputStream(FileInputStream(file)).use { require(it.readUTF() == "LLJ1"); it.readUTF() }
        require(generation == storedGeneration) { "Checkpoint replaced by a new reception" }
        val bytes = packet.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4096 && file.length() + bytes.size + 8 <= 64_000_000) { "Reception checkpoint limit reached" }
        DataOutputStream(FileOutputStream(file, true)).use {
            it.writeInt(bytes.size); it.write(bytes); it.writeInt(CRC32().apply { update(bytes) }.value.toInt())
        }
    }
    fun restore(receiver: Receiver): Int = locked {
        var records = 0
        RandomAccessFile(file, "rw").use { input ->
            require(input.readUTF() == "LLJ1")
            generation = input.readUTF(); UUID.fromString(generation)
            require(input.length() <= 64_000_000)
            var validEnd = input.filePointer
            while (input.filePointer < input.length()) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                try {
                    require(records++ < 120_000)
                    val length = input.readInt(); require(length in 1..4096)
                    val bytes = ByteArray(length).also(input::readFully)
                    require(input.readInt() == CRC32().apply { update(bytes) }.value.toInt())
                    receiver.accept(bytes.toString(Charsets.UTF_8))
                    require(receiver.state != Receiver.State.FAILED)
                    validEnd = input.filePointer
                } catch (_: EOFException) { input.setLength(validEnd); break }
                catch (_: IllegalArgumentException) { input.setLength(validEnd); break }
            }
        }
        receiver.solved
    }
    fun identity(): String? = locked { generation }
    fun clear(expected: String?): Unit = locked {
        if (expected == null || !file.exists()) return@locked
        val stored = runCatching { DataInputStream(FileInputStream(file)).use { require(it.readUTF() == "LLJ1"); it.readUTF() } }.getOrNull()
        if (stored == expected) file.delete()
    }
    fun discard(): Unit = locked { file.delete(); generation = null }
}
