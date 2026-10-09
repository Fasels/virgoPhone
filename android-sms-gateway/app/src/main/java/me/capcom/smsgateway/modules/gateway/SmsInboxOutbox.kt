package me.capcom.smsgateway.modules.gateway

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** SMS receive payloads survive WorkManager failures and retain their original server/device route. */
class SmsInboxOutbox(private val directory: File) {
    fun key(server: String, device: String, message: String): String = sha("$server\n$device\n$message")

    fun enqueue(key: String, payload: String) = synchronized(lock) {
        if (!file(key, "json").exists() && !file(key, "done").exists()) atomicWrite(file(key, "json"), payload)
    }

    fun pending(): List<String> = synchronized(lock) {
        directory.listFiles().orEmpty().filter { it.extension == "json" }
            .map { it.nameWithoutExtension }
            .filter { !file(it, "done").exists() && !file(it, "blocked").exists() }
            .sorted()
    }

    fun read(key: String): String = synchronized(lock) { file(key, "json").readText() }

    fun complete(key: String) = synchronized(lock) {
        atomicWrite(file(key, "done"), "uploaded")
        file(key, "json").delete()
        file(key, "blocked").delete()
        Unit
    }

    fun block(key: String, reason: String) = synchronized(lock) { atomicWrite(file(key, "blocked"), reason) }

    fun retryBlocked() = synchronized(lock) {
        directory.listFiles().orEmpty().filter { it.extension == "blocked" }.forEach { it.delete() }
    }

    /** Old WorkManager input had no original route. Retain it without guessing which station owns it. */
    fun retainLegacy(payload: String) = synchronized(lock) {
        val key = sha(payload)
        if (!file(key, "legacy-unbound").exists()) atomicWrite(file(key, "legacy-unbound"), payload)
    }

    private fun file(key: String, suffix: String): File {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return File(directory, "$key.$suffix")
    }

    private fun atomicWrite(target: File, value: String) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create SMS inbox outbox")
        val temporary = File(directory, target.name + ".tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(value.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        if (!temporary.renameTo(target)) throw IOException("Cannot commit SMS inbox outbox")
    }

    companion object {
        private val lock = Any()
        private fun sha(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
