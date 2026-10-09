package me.capcom.smsgateway.modules.gateway

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Immutable payloads live in app files, never in WorkManager's size-limited Data. */
class MmsOutbox(private val directory: File) {
    fun key(server: String, device: String, message: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$server\n$device\n$message".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun contains(key: String): Boolean = synchronized(lock) {
        file(key, "json").exists() || file(key, "done").exists()
    }

    fun enqueue(key: String, payload: String) = synchronized(lock) {
        if (!contains(key)) atomicWrite(file(key, "json"), payload)
    }

    fun pending(): List<String> = synchronized(lock) {
        directory.listFiles().orEmpty().filter { it.extension == "json" }
            .map { it.nameWithoutExtension }
            .filter { !file(it, "done").exists() && !file(it, "failed").exists() }
            .sorted()
    }

    fun read(key: String): String = synchronized(lock) { file(key, "json").readText() }

    fun complete(key: String) = synchronized(lock) {
        // A crash between these operations is harmless: the durable receipt wins.
        atomicWrite(file(key, "done"), "uploaded")
        file(key, "json").delete()
        file(key, "failed").delete()
        Unit
    }

    fun fail(key: String, reason: String) = synchronized(lock) {
        atomicWrite(file(key, "failed"), reason)
    }

    fun retryFailed() = synchronized(lock) {
        directory.listFiles().orEmpty().filter { it.extension == "failed" }.forEach { it.delete() }
    }

    /** Preserve unrelated permanent failures and each original immutable payload. */
    fun retryFailedMatching(eligible: (payload: String, reason: String) -> Boolean): Int = synchronized(lock) {
        var restored = 0
        directory.listFiles().orEmpty().filter { it.extension == "failed" }.forEach { failure ->
            val key = failure.nameWithoutExtension
            if (!key.matches(Regex("[a-f0-9]{64}"))) return@forEach
            val payload = file(key, "json")
            if (!payload.isFile || file(key, "done").exists()) return@forEach
            if (eligible(payload.readText(), failure.readText()) && failure.delete()) restored++
        }
        restored
    }

    private fun file(key: String, suffix: String): File {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return File(directory, "$key.$suffix")
    }

    private fun atomicWrite(target: File, value: String) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create MMS outbox")
        val temporary = File(directory, target.name + ".tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(value.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        if (!temporary.renameTo(target)) throw IOException("Cannot commit MMS outbox file")
    }

    companion object { private val lock = Any() }
}
