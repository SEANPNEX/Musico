package com.musico.extensions

import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

class ExtensionPackageLoader(
    private val runtime: QuickJsExtensionRuntime,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun loadFromFile(file: File, sourceUrl: String? = null): StagedExtensionPackage {
        val packages = loadPackagesFromFile(file = file, sourceUrl = sourceUrl)
        require(packages.size == 1) {
            "Archive contains ${packages.size} extensions; expected a single extension package."
        }
        return packages.first()
    }

    suspend fun loadPackagesFromFile(file: File, sourceUrl: String? = null): List<StagedExtensionPackage> {
        val bytes = file.inputStream().use { readBoundedBytes(it, MAX_UNCOMPRESSED_BYTES) }
        return loadPackagesFromBytes(bytes = bytes, fileNameHint = file.name, sourceUrl = sourceUrl)
    }

    suspend fun loadFromStream(
        input: InputStream,
        fileNameHint: String? = null,
        sourceUrl: String? = null,
    ): StagedExtensionPackage {
        val packages = loadPackagesFromStream(input = input, fileNameHint = fileNameHint, sourceUrl = sourceUrl)
        require(packages.size == 1) {
            "Archive contains ${packages.size} extensions; expected a single extension package."
        }
        return packages.first()
    }

    suspend fun loadPackagesFromStream(
        input: InputStream,
        fileNameHint: String? = null,
        sourceUrl: String? = null,
    ): List<StagedExtensionPackage> {
        val bytes = readBoundedBytes(input, MAX_UNCOMPRESSED_BYTES)
        return loadPackagesFromBytes(bytes = bytes, fileNameHint = fileNameHint, sourceUrl = sourceUrl)
    }

    suspend fun loadFromBytes(
        bytes: ByteArray,
        fileNameHint: String? = null,
        sourceUrl: String? = null,
    ): StagedExtensionPackage {
        val packages = loadPackagesFromBytes(bytes = bytes, fileNameHint = fileNameHint, sourceUrl = sourceUrl)
        require(packages.size == 1) {
            "Archive contains ${packages.size} extensions; expected a single extension package."
        }
        return packages.first()
    }

    suspend fun loadPackagesFromBytes(
        bytes: ByteArray,
        fileNameHint: String? = null,
        sourceUrl: String? = null,
    ): List<StagedExtensionPackage> {
        require(bytes.isNotEmpty()) { "Extension package is empty." }
        require(bytes.size <= MAX_UNCOMPRESSED_BYTES) {
            "Extension package exceeds maximum size of ${MAX_UNCOMPRESSED_BYTES / (1024 * 1024)} MB."
        }

        val isZipMagic = bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() &&
            bytes[3] == 0x04.toByte()

        val lowerHint = fileNameHint?.trim()?.lowercase().orEmpty()
        val treatAsBundle = when {
            isZipMagic -> true
            lowerHint.endsWith(".musicoext") || lowerHint.endsWith(".zip") -> true
            lowerHint.endsWith(".musico.js") || lowerHint.endsWith(".js") -> false
            else -> false
        }

        return if (treatAsBundle) {
            loadZipPackages(bytes = bytes, sourceUrl = sourceUrl, allowNestedPacks = true)
        } else {
            listOf(loadSingleFile(bytes = bytes, sourceUrl = sourceUrl))
        }
    }

    private suspend fun loadZipPackages(
        bytes: ByteArray,
        sourceUrl: String?,
        allowNestedPacks: Boolean,
    ): List<StagedExtensionPackage> {
        val entries = linkedMapOf<String, ByteArray>()
        var entryCount = 0
        var totalUncompressed = 0L

        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                try {
                    entryCount += 1
                    require(entryCount <= MAX_ZIP_ENTRIES) {
                        "Extension archive exceeds maximum of $MAX_ZIP_ENTRIES entries."
                    }

                    val rawName = entry.name
                    val normalizedName = sanitizeZipEntryPath(rawName, isDirectory = entry.isDirectory)
                    if (entry.isDirectory || isIgnoredArchiveMetadata(normalizedName)) {
                        continue
                    }

                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val read = zis.read(buf)
                        if (read < 0) break
                        totalUncompressed += read
                        require(totalUncompressed <= MAX_UNCOMPRESSED_BYTES) {
                            "Extension archive uncompressed size exceeds ${MAX_UNCOMPRESSED_BYTES / (1024 * 1024)} MB limit."
                        }
                        out.write(buf, 0, read)
                    }
                    entries[normalizedName] = out.toByteArray()
                } finally {
                    runCatching { zis.closeEntry() }
                }
            }
        }

        if (entries.containsKey(MANIFEST_FILE_NAME)) {
            return listOf(parseBundleFromEntries(entries = entries, sourceUrl = sourceUrl))
        }

        if (!allowNestedPacks) {
            throw IllegalArgumentException("Extension bundle is missing $MANIFEST_FILE_NAME.")
        }

        val sortedPaths = entries.keys.sortedBy { it.lowercase() }
        val claimedPrefixes = mutableListOf<String>()
        val packages = mutableListOf<StagedExtensionPackage>()

        for (path in sortedPaths) {
            if (!path.endsWith("/$MANIFEST_FILE_NAME", ignoreCase = true)) continue
            if (isUnderClaimedPrefix(path, claimedPrefixes)) continue
            val prefix = path.dropLast(MANIFEST_FILE_NAME.length)
            claimedPrefixes.add(prefix)

            val subEntries = linkedMapOf<String, ByteArray>()
            for ((k, v) in entries) {
                if (k.startsWith(prefix, ignoreCase = true)) {
                    val rel = k.substring(prefix.length)
                    if (rel.isNotEmpty()) {
                        subEntries[rel] = v
                    }
                }
            }
            packages.add(parseBundleFromEntries(entries = subEntries, sourceUrl = null))
        }

        for (path in sortedPaths) {
            if (isUnderClaimedPrefix(path, claimedPrefixes)) continue
            val lower = path.lowercase()
            when {
                lower.endsWith(".musicoext") -> {
                    val nested = loadZipPackages(
                        bytes = entries.getValue(path),
                        sourceUrl = null,
                        allowNestedPacks = false,
                    )
                    packages.addAll(nested)
                }
                lower.endsWith(".musico.js") -> {
                    packages.add(loadSingleFile(bytes = entries.getValue(path), sourceUrl = null))
                }
            }
        }

        require(packages.isNotEmpty()) {
            "Archive does not contain a valid Musico extension (manifest.json, .musicoext, or .musico.js)."
        }

        val seenIds = HashSet<String>(packages.size)
        for (pkg in packages) {
            val key = pkg.manifest.id.lowercase()
            require(seenIds.add(key)) {
                "Archive contains duplicate extension id '${pkg.manifest.id}'."
            }
        }

        return if (packages.size == 1 && sourceUrl != null) {
            listOf(packages.first().copy(sourceUrl = sourceUrl))
        } else {
            packages
        }
    }

    private suspend fun parseBundleFromEntries(
        entries: Map<String, ByteArray>,
        sourceUrl: String?,
    ): StagedExtensionPackage {
        val manifestBytes = entries[MANIFEST_FILE_NAME]
            ?: throw IllegalArgumentException("Extension bundle is missing $MANIFEST_FILE_NAME.")
        val rawManifest = json.decodeFromString<ExtensionManifest>(manifestBytes.toString(Charsets.UTF_8))
        val manifest = ExtensionManifestValidator.validate(rawManifest)

        val entryBytes = entries[manifest.entry]
            ?: throw IllegalArgumentException("Extension bundle is missing entry script '${manifest.entry}'.")
        val scriptSource = entryBytes.toString(Charsets.UTF_8)
        require(scriptSource.isNotBlank()) { "Extension entry script '${manifest.entry}' is empty." }

        runtime.verifyBundleExports(manifest, scriptSource)

        val iconBytes = entries[ICON_FILE_NAME]?.takeIf { it.isNotEmpty() && it.size <= MAX_ICON_BYTES }

        return StagedExtensionPackage(
            manifest = manifest,
            scriptSource = scriptSource,
            iconBytes = iconBytes,
            format = ExtensionPackageFormat.BUNDLE,
            sourceUrl = sourceUrl,
        )
    }

    private suspend fun loadSingleFile(
        bytes: ByteArray,
        sourceUrl: String?,
    ): StagedExtensionPackage {
        val scriptSource = bytes.toString(Charsets.UTF_8)
        require(scriptSource.isNotBlank()) { "Extension script is empty." }
        val manifest = runtime.inspectSingleFile(scriptSource)
        return StagedExtensionPackage(
            manifest = manifest,
            scriptSource = scriptSource,
            iconBytes = null,
            format = ExtensionPackageFormat.SINGLE_FILE,
            sourceUrl = sourceUrl,
        )
    }

    companion object {
        const val MANIFEST_FILE_NAME = "manifest.json"
        const val ICON_FILE_NAME = "icon.png"
        const val MAX_ZIP_ENTRIES = 512
        const val MAX_UNCOMPRESSED_BYTES = 32 * 1024 * 1024
        const val MAX_ICON_BYTES = 2 * 1024 * 1024

        private fun isUnderClaimedPrefix(path: String, claimedPrefixes: List<String>): Boolean =
            claimedPrefixes.any { prefix -> path.startsWith(prefix, ignoreCase = true) }

        private fun isIgnoredArchiveMetadata(normalizedPath: String): Boolean {
            if (normalizedPath.equals("__MACOSX", ignoreCase = true) ||
                normalizedPath.startsWith("__MACOSX/", ignoreCase = true)
            ) {
                return true
            }
            val fileName = normalizedPath.substringAfterLast('/')
            return fileName.equals(".DS_Store", ignoreCase = true) || fileName.startsWith("._")
        }

        internal fun sanitizeZipEntryPath(rawName: String, isDirectory: Boolean): String {
            require(!rawName.contains('\u0000')) { "Unsafe ZIP entry name: $rawName" }
            require(!rawName.contains('\\')) { "Unsafe ZIP path separator in entry: $rawName" }
            require(!rawName.startsWith("/")) { "Absolute ZIP entry path not allowed: $rawName" }
            // Check Windows drive prefix e.g. C:...
            require(!(rawName.length >= 2 && rawName[1] == ':' && rawName[0].isLetter())) {
                "Absolute ZIP entry path not allowed: $rawName"
            }

            val trimmed = if (isDirectory) rawName.trimEnd('/') else rawName
            val stripped = trimmed.removePrefix("./")
            require(stripped.isNotEmpty()) { "Empty ZIP entry path: $rawName" }
            val segments = stripped.split('/')
            for (seg in segments) {
                require(seg.isNotEmpty() && seg != "." && seg != "..") {
                    "Path traversal or invalid segment in ZIP entry: $rawName"
                }
            }
            return segments.joinToString("/")
        }

        private fun readBoundedBytes(input: InputStream, maxBytes: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                require(total <= maxBytes) {
                    "Extension package exceeds maximum size of ${maxBytes / (1024 * 1024)} MB."
                }
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
