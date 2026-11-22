package com.musico.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.Normalizer

data class ScannedFile(
    val relativePath: String,
    val fileSize: Long,
    val modifiedEpochMs: Long,
    val uriString: String? = null,
)

object FolderScanner {
    val AUDIO_EXTENSIONS: Set<String> = setOf(
        "mp3", "m4a", "aac", "flac", "wav", "aif", "aiff", "caf", "alac", "ogg", "opus", "webm",
    )

    fun isAudioFileName(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.lastIndex) return false
        return name.substring(dot + 1).lowercase() in AUDIO_EXTENSIONS
    }

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
        for (entry in entries) {
            val name = entry.name
            if (name.startsWith(".")) continue
            val rel = normalizePath(if (prefix.isEmpty()) name else "$prefix/$name")
            if (entry.isDirectory) {
                val children = entry.listFiles() ?: continue
                scanLocalRecursive(children, rel, out)
            } else if (entry.isFile && isAudioFileName(name)) {
                out.add(
                    ScannedFile(
                        relativePath = rel,
                        fileSize = entry.length(),
                        modifiedEpochMs = entry.lastModified(),
                        uriString = runCatching { Uri.fromFile(entry)?.toString() }.getOrNull(),
                    )
                )
            }
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
                    out.add(
                        ScannedFile(
                            relativePath = rel,
                            fileSize = size,
                            modifiedEpochMs = mod,
                            uriString = docUri.toString(),
                        )
                    )
                }
            }
        }

        for ((subId, subRel) in subDirs) {
            scanTreeViaResolver(context, treeUri, subId, subRel, out)
        }
        return true
    }

    private fun scanDocFileRecursive(dir: DocumentFile, prefix: String, out: MutableList<ScannedFile>) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            val rel = normalizePath(if (prefix.isEmpty()) name else "$prefix/$name")
            if (child.isDirectory) {
                scanDocFileRecursive(child, rel, out)
            } else if (child.isFile && isAudioFileName(name)) {
                out.add(
                    ScannedFile(
                        relativePath = rel,
                        fileSize = child.length(),
                        modifiedEpochMs = child.lastModified(),
                        uriString = child.uri.toString(),
                    )
                )
            }
        }
    }

    fun fileFor(rootDir: File, relativePath: String): File = File(rootDir, relativePath)
}
