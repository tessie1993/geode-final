package dev.geode.data

import java.io.File
import java.io.IOException

/** Diagnostic IO, bounded independently of the UI lifecycle. */
class CrashReportStore(
    directory: File,
) {
    private val file = File(directory, "crash-latest.txt")

    fun read(): String? {
        if (!file.isFile) return null
        return file.inputStream().use { stream ->
            val buffer = ByteArray(MAX_BYTES)
            var count = 0
            while (count < buffer.size) {
                val read = stream.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            String(buffer, 0, count, Charsets.UTF_8)
        }
    }

    fun dismiss() {
        if (file.exists() && !file.delete()) throw IOException("Could not delete crash report")
    }

    private companion object {
        const val MAX_BYTES = 64 * 1024
    }
}
