package com.musico.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object Persistence {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Loads JSON from [file]. Returns null when missing.
     * An undecodable file is moved to `<name>.corrupt` and null is returned.
     */
    inline fun <reified T> load(file: File): T? {
        if (!file.exists()) return null
        return try {
            val text = file.readText(Charsets.UTF_8)
            json.decodeFromString<T>(text)
        } catch (_: Exception) {
            val backup = File(file.parentFile, "${file.name}.corrupt")
            runCatching {
                if (backup.exists()) backup.delete()
                if (!file.renameTo(backup)) {
                    file.copyTo(backup, overwrite = true)
                    file.delete()
                }
            }
            null
        }
    }

    /**
     * Atomically writes [value] as JSON to [file] via temp file + move/rename.
     */
    inline fun <reified T> save(value: T, file: File) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val temp = File(parent, "${file.name}.tmp.${System.nanoTime()}")
        try {
            val encoded = json.encodeToString(value)
            temp.writeText(encoded, Charsets.UTF_8)
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Exception) {
                try {
                    Files.move(
                        temp.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: Exception) {
                    if (file.exists()) file.delete()
                    if (!temp.renameTo(file)) {
                        temp.copyTo(file, overwrite = true)
                        temp.delete()
                    }
                }
            }
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            throw t
        }
    }
}
