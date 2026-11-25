package com.musico.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.musico.cloud.CloudExporter
import com.musico.cloud.CloudFolderManifest
import kotlinx.serialization.json.Json
import java.io.File
import java.text.Normalizer

data class ScannedFile(
    val relativePath: String,
    val fileSize: Long,
    val modifiedEpochMs: Long,
    val uriString: String? = null,
    val siblingArtworkUriString: String? = null,
)

data class LocalCloudDiscovery(
    val existingSubfolders: Set<String>,
    val manifests: Map<String, CloudFolderManifest>,
)

object FolderScanner {
    val AUDIO_EXTENSIONS: Set<String> = setOf(
        "mp3", "m4a", "aac", "flac", "wav", "aif", "aiff", "caf", "alac", "ogg", "opus", "webm",
    )

    private val ARTWORK_EXTENSION_PRIORITY = mapOf(
        "jpg" to 0,
        "jpeg" to 1,
        "png" to 2,
        "webp" to 3,
    )

    fun isAudioFileName(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.lastIndex) return false
        return name.substring(dot + 1).lowercase() in AUDIO_EXTENSIONS
    }

    private fun artworkPriority(name: String): Int? {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.lastIndex) return null
        return ARTWORK_EXTENSION_PRIORITY[name.substring(dot + 1).lowercase()]
    }

    private fun stemLower(name: String): String =
        normalizePath(name.substringBeforeLast('.', name)).lowercase()

    fun normalizePath(path: String): String =
        Normalizer.normalize(path, Normalizer.Form.NFC)

    /**
     * Recursively scans a local directory [rootDir].
     * Returns null if [rootDir] does not exist, is not a directory, or cannot be listed.
     */
    fun scan(rootDir: File): List<ScannedFile>? {
        if (!rootDir.exists() || !rootDir.isDirectory || !rootDir.canRead()) return null
        val topEntries = rootDir.listFiles() ?: return null
        val results = mutableListOf<ScannedFile>()
        scanLocalRecursive(topEntries, prefix = "", out = results)
        return results.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.relativePath })
    }

    private fun scanLocalRecursive(entries: Array<File>, prefix: String, out: MutableList<ScannedFile>) {
        val bestArtworkByStem = HashMap<String, Pair<Int, String>>()
        val audioEntries = mutableListOf<Pair<File, String>>()
        for (entry in entries) {
            val name = entry.name
            if (name.startsWith(".")) continue
            val rel = normalizePath(if (prefix.isEmpty()) name else "$prefix/$name")
            if (entry.isDirectory) {
                val children = entry.listFiles() ?: continue
                scanLocalRecursive(children, rel, out)
            } else if (entry.isFile) {
                if (isAudioFileName(name)) {
                    audioEntries.add(entry to rel)
                } else {
                    val prio = artworkPriority(name)
                    if (prio != null) {
                        val uriStr = runCatching { Uri.fromFile(entry)?.toString() }.getOrNull()
                            ?: entry.toURI().toString()
                        val key = stemLower(name)
                        val prev = bestArtworkByStem[key]
                        if (prev == null || prio < prev.first) {
                            bestArtworkByStem[key] = prio to uriStr
                        }
                    }
                }
            }
        }
        for ((entry, rel) in audioEntries) {
            out.add(
                ScannedFile(
                    relativePath = rel,
                    fileSize = entry.length(),
                    modifiedEpochMs = entry.lastModified(),
                    uriString = runCatching { Uri.fromFile(entry)?.toString() }.getOrNull(),
                    siblingArtworkUriString = bestArtworkByStem[stemLower(entry.name)]?.second,
                )
            )
        }
    }

    /**
     * Recursively scans an Android SAF [treeUri].
     * Returns null if the tree cannot be opened or listed.
     */
    fun scanTree(context: Context, treeUri: Uri): List<ScannedFile>? {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (!rootDoc.exists() || !rootDoc.isDirectory || !rootDoc.canRead()) return null

        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        if (rootDocId != null) {
            val results = mutableListOf<ScannedFile>()
            val listed = scanTreeViaResolver(context, treeUri, rootDocId, prefix = "", out = results)
            if (listed) {
                return results.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.relativePath })
            }
        }

        val results = mutableListOf<ScannedFile>()
        scanDocFileRecursive(rootDoc, prefix = "", out = results)
        return results.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.relativePath })
    }

    private fun scanTreeViaResolver(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
        prefix: String,
        out: MutableList<ScannedFile>,
    ): Boolean {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val cursor = try {
            context.contentResolver.query(childrenUri, projection, null, null, null)
        } catch (_: Exception) {
            return false
        } ?: return false

        val subDirs = mutableListOf<Pair<String, String>>()
        val bestArtworkByStem = HashMap<String, Pair<Int, String>>()
        val audioRows = mutableListOf<Triple<String, String, Triple<Long, Long, Uri>>>()
        cursor.use { c ->
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (c.moveToNext()) {
                val docId = if (idIdx >= 0) c.getString(idIdx) else null
                val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                if (docId == null || name == null || name.startsWith(".")) continue
                val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else null
                val rel = normalizePath(if (prefix.isEmpty()) name else "$prefix/$name")
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    subDirs.add(docId to rel)
                } else if (isAudioFileName(name)) {
                    val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                    val mod = if (modIdx >= 0 && !c.isNull(modIdx)) c.getLong(modIdx) else 0L
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    audioRows.add(Triple(name, rel, Triple(size, mod, docUri)))
                } else {
                    val prio = artworkPriority(name)
                    if (prio != null) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString()
                        val key = stemLower(name)
                        val prev = bestArtworkByStem[key]
                        if (prev == null || prio < prev.first) {
                            bestArtworkByStem[key] = prio to docUri
                        }
                    }
                }
            }
        }
        for ((name, rel, meta) in audioRows) {
            out.add(
                ScannedFile(
                    relativePath = rel,
                    fileSize = meta.first,
                    modifiedEpochMs = meta.second,
                    uriString = meta.third.toString(),
                    siblingArtworkUriString = bestArtworkByStem[stemLower(name)]?.second,
                )
            )
        }

        for ((subId, subRel) in subDirs) {
            scanTreeViaResolver(context, treeUri, subId, subRel, out)
        }
        return true
    }

    private fun scanDocFileRecursive(dir: DocumentFile, prefix: String, out: MutableList<ScannedFile>) {
        val bestArtworkByStem = HashMap<String, Pair<Int, String>>()
        val audioDocs = mutableListOf<Triple<DocumentFile, String, String>>()
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            val rel = normalizePath(if (prefix.isEmpty()) name else "$prefix/$name")
            if (child.isDirectory) {
                scanDocFileRecursive(child, rel, out)
            } else if (child.isFile) {
                if (isAudioFileName(name)) {
                    audioDocs.add(Triple(child, name, rel))
                } else {
                    val prio = artworkPriority(name)
                    if (prio != null) {
                        val key = stemLower(name)
                        val prev = bestArtworkByStem[key]
                        if (prev == null || prio < prev.first) {
                            bestArtworkByStem[key] = prio to child.uri.toString()
                        }
                    }
                }
            }
        }
        for ((child, name, rel) in audioDocs) {
            out.add(
                ScannedFile(
                    relativePath = rel,
                    fileSize = child.length(),
                    modifiedEpochMs = child.lastModified(),
                    uriString = child.uri.toString(),
                    siblingArtworkUriString = bestArtworkByStem[stemLower(name)]?.second,
                )
            )
        }
    }

    fun fileFor(rootDir: File, relativePath: String): File = File(rootDir, relativePath)

    private val manifestJson = Json { ignoreUnknownKeys = true }
    private const val SAF_MANIFEST_FILE_NAME = "musico-cloud.json"

    /**
     * Discovers existing local subfolder paths and any cloud tracking manifests inside [rootDir].
     */
    fun discoverCloudInfo(rootDir: File): LocalCloudDiscovery? {
        if (!rootDir.exists() || !rootDir.isDirectory || !rootDir.canRead()) return null
        val existingSubfolders = linkedSetOf<String>()
        val manifests = linkedMapOf<String, CloudFolderManifest>()
        CloudExporter.loadManifest(rootDir)?.let { manifests[""] = it }
        discoverLocalCloudRecursive(rootDir, prefix = "", existingSubfolders, manifests)
        return LocalCloudDiscovery(
            existingSubfolders = existingSubfolders,
            manifests = manifests,
        )
    }

    private fun discoverLocalCloudRecursive(
        dir: File,
        prefix: String,
        existingSubfolders: MutableSet<String>,
        manifests: MutableMap<String, CloudFolderManifest>,
    ) {
        val children = dir.listFiles() ?: return
        for (entry in children) {
            val name = entry.name
            if (name.startsWith(".")) continue
            if (!entry.isDirectory) continue
            val rel = normalizePath(if (prefix.isEmpty()) name else "$prefix/$name")
            existingSubfolders.add(rel)
            CloudExporter.loadManifest(entry)?.let { manifests[rel] = it }
            discoverLocalCloudRecursive(entry, rel, existingSubfolders, manifests)
        }
    }

    /**
     * Discovers existing SAF subfolder paths and any cloud tracking manifests inside [treeUri].
     */
    fun discoverCloudInfo(context: Context, treeUri: Uri): LocalCloudDiscovery? {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (!rootDoc.exists() || !rootDoc.isDirectory || !rootDoc.canRead()) return null

        val existingSubfolders = linkedSetOf<String>()
        val manifests = linkedMapOf<String, CloudFolderManifest>()
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        if (rootDocId != null) {
            val listed = discoverSafViaResolver(
                context = context,
                treeUri = treeUri,
                parentDocId = rootDocId,
                prefix = "",
                existingSubfolders = existingSubfolders,
                manifests = manifests,
            )
            if (listed) {
                return LocalCloudDiscovery(
                    existingSubfolders = existingSubfolders,
                    manifests = manifests,
                )
            }
        }
        discoverDocFileCloudRecursive(context, rootDoc, prefix = "", existingSubfolders, manifests)
        return LocalCloudDiscovery(
            existingSubfolders = existingSubfolders,
            manifests = manifests,
        )
    }

    private fun discoverSafViaResolver(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
        prefix: String,
        existingSubfolders: MutableSet<String>,
        manifests: MutableMap<String, CloudFolderManifest>,
    ): Boolean {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val cursor = try {
            context.contentResolver.query(childrenUri, projection, null, null, null)
        } catch (_: Exception) {
            return false
        } ?: return false

        val subDirs = mutableListOf<Pair<String, String>>()
        var dotManifestDocId: String? = null
        var safManifestDocId: String? = null

        cursor.use { c ->
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (c.moveToNext()) {
                val docId = if (idIdx >= 0) c.getString(idIdx) else null
                val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                if (docId == null || name == null) continue
                val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else null

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (name.startsWith(".")) continue
                    val rel = normalizePath(if (prefix.isEmpty()) name else "$prefix/$name")
                    existingSubfolders.add(rel)
                    subDirs.add(docId to rel)
                } else if (name == CloudExporter.MANIFEST_FILE_NAME) {
                    dotManifestDocId = docId
                } else if (name == SAF_MANIFEST_FILE_NAME) {
                    safManifestDocId = docId
                }
            }
        }

        val chosenDocId = dotManifestDocId ?: safManifestDocId
        if (chosenDocId != null) {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, chosenDocId)
            runCatching {
                context.contentResolver.openInputStream(docUri)?.use { input ->
                    val text = input.readBytes().toString(Charsets.UTF_8)
                    manifestJson.decodeFromString<CloudFolderManifest>(text)
                }
            }.getOrNull()?.let { manifests[prefix] = it }
        }

        for ((subId, subRel) in subDirs) {
            discoverSafViaResolver(context, treeUri, subId, subRel, existingSubfolders, manifests)
        }
        return true
    }

    private fun discoverDocFileCloudRecursive(
        context: Context,
        dir: DocumentFile,
        prefix: String,
        existingSubfolders: MutableSet<String>,
        manifests: MutableMap<String, CloudFolderManifest>,
    ) {
        CloudExporter.loadManifest(context, dir)?.let { manifests[prefix] = it }
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            if (child.isDirectory) {
                val rel = normalizePath(if (prefix.isEmpty()) name else "$prefix/$name")
                existingSubfolders.add(rel)
                discoverDocFileCloudRecursive(context, child, rel, existingSubfolders, manifests)
            }
        }
    }
}
