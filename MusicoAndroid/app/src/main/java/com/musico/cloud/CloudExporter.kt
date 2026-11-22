package com.musico.cloud

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.musico.model.LibraryRoot
import com.musico.model.RootKind
import com.musico.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class CloudFolderManifest(
    val sourceURL: String,
    val remoteID: String? = null,
    val cloudKind: String,
    val lastSyncedEpochMs: Long = 0L,
    val tracks: Map<String, String> = emptyMap(),
)

data class CloudExportSummary(
    val savedCount: Int,
    val failedCount: Int,
    val targetTreeUri: Uri? = null,
    val targetDir: File? = null,
    val savedFolderName: String? = null,
    val errors: List<String> = emptyList(),
)

data class CloudUpdateSummary(
    val addedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val totalRemoteCount: Int,
    val errors: List<String> = emptyList(),
)

fun interface CloudStreamDownloader {
    suspend fun download(
        url: String,
        headers: Map<String, String>,
        output: OutputStream,
    )
}

class UrlConnectionCloudStreamDownloader(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : CloudStreamDownloader {

    override suspend fun download(
        url: String,
        headers: Map<String, String>,
        output: OutputStream,
    ) = withContext(Dispatchers.IO) {
        var currentUrl = url
        for (hop in 0 until MAX_REDIRECTS) {
            currentCoroutineContext().ensureActive()
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                useCaches = false
                instanceFollowRedirects = false
                for ((k, v) in headers) {
                    setRequestProperty(k, v)
                }
            }
            var redirected = false
            try {
                val code = conn.responseCode
                if (isRedirect(code)) {
                    val location = conn.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        currentUrl = URL(URL(currentUrl), location).toString()
                        redirected = true
                        continue
                    }
                }
                if (code !in 200..299) {
                    throw IOException("HTTP $code downloading stream")
                }
                val buffer = ByteArray(16 * 1024)
                conn.inputStream.use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) {
                            output.write(buffer, 0, read)
                        }
                    }
                }
                output.flush()
                return@withContext
            } finally {
                if (redirected || true) {
                    conn.disconnect()
                }
            }
        }
        throw IOException("Too many redirects downloading $url")
    }

    private fun isRedirect(code: Int): Boolean =
        code == HttpURLConnection.HTTP_MOVED_PERM ||
            code == HttpURLConnection.HTTP_MOVED_TEMP ||
            code == HttpURLConnection.HTTP_SEE_OTHER ||
            code == 307 ||
            code == 308

    companion object {
        private const val MAX_REDIRECTS = 6
    }
}

class CloudExporter(
    private val context: Context?,
    private val cloudResolver: CloudStreamResolver,
    private val downloader: CloudStreamDownloader = UrlConnectionCloudStreamDownloader(),
) {
    suspend fun exportPlaylist(
        tracks: List<Track>,
        root: LibraryRoot?,
        parentDir: File,
        folderName: String,
        onProgress: (done: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
    ): CloudExportSummary = withContext(Dispatchers.IO) {
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw IOException("Could not create parent folder: ${parentDir.absolutePath}")
        }
        if (!parentDir.isDirectory) {
            throw IOException("Destination is not a directory: ${parentDir.absolutePath}")
        }

        val safeFolder = sanitizeFileName(folderName, "Cloud Playlist")
        val initialTarget = File(parentDir, safeFolder)
        val targetDir = if (initialTarget.exists() && !initialTarget.isDirectory) {
            val existingParentNames = parentDir.list()?.toSet().orEmpty()
            File(parentDir, uniqueFileName(existingParentNames, safeFolder, ""))
        } else {
            initialTarget
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Could not create folder: ${targetDir.absolutePath}")
        }

        val existingNames = targetDir.list()?.toMutableSet() ?: mutableSetOf()
        val trackedMap = (loadManifest(targetDir)?.tracks ?: emptyMap()).toMutableMap()
        val errors = mutableListOf<String>()
        var savedCount = 0
        var failedCount = 0

        for ((index, track) in tracks.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress(index, tracks.size, track.title)
            try {
                val savedName = downloadToFileDir(track, root, targetDir, existingNames)
                existingNames.add(savedName)
                val trackKey = track.remoteTrackId ?: track.relativePath
                if (trackKey.isNotEmpty()) {
                    trackedMap[trackKey] = savedName
                }
                savedCount += 1
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: Exception) {
                failedCount += 1
                errors.add("${track.title}: ${err.message ?: err.javaClass.simpleName}")
            } finally {
                onProgress(index + 1, tracks.size, track.title)
            }
        }

        val sourceUrl = root?.sourceUrl?.trim()
        val cloudKind = root?.effectiveCloudKind
        if (!sourceUrl.isNullOrEmpty() && cloudKind != null) {
            runCatching {
                saveManifest(
                    targetDir,
                    CloudFolderManifest(
                        sourceURL = sourceUrl,
                        remoteID = root.remoteId,
                        cloudKind = cloudKind.name,
                        lastSyncedEpochMs = System.currentTimeMillis(),
                        tracks = trackedMap,
                    ),
                )
            }
        }

        CloudExportSummary(
            savedCount = savedCount,
            failedCount = failedCount,
            targetTreeUri = null,
            targetDir = targetDir,
            savedFolderName = targetDir.name,
            errors = errors,
        )
    }

    suspend fun exportPlaylist(
        tracks: List<Track>,
        root: LibraryRoot?,
        parentTreeUri: Uri,
        folderName: String,
        onProgress: (done: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
    ): CloudExportSummary {
        if (parentTreeUri.scheme == "file") {
            val path = parentTreeUri.path ?: throw IOException("Invalid file URI: $parentTreeUri")
            return exportPlaylist(tracks, root, File(path), folderName, onProgress)
        }
        return withContext(Dispatchers.IO) {
            val ctx = context ?: throw IOException("Android context unavailable for SAF export.")
            val parentDoc = DocumentFile.fromTreeUri(ctx, parentTreeUri)
                ?: throw IOException("Could not open selected folder.")
            if (!parentDoc.exists() || !parentDoc.isDirectory || !parentDoc.canWrite()) {
                throw IOException("Selected folder is not writable.")
            }

            val safeFolder = sanitizeFileName(folderName, "Cloud Playlist")
            val existingSub = parentDoc.findFile(safeFolder)
            val subDirDoc = when {
                existingSub != null && existingSub.isDirectory -> existingSub
                existingSub != null -> {
                    val parentNames = parentDoc.listFiles().mapNotNull { it.name }.toSet()
                    val uniqueFolder = uniqueFileName(parentNames, safeFolder, "")
                    parentDoc.createDirectory(uniqueFolder)
                        ?: throw IOException("Could not create folder $uniqueFolder.")
                }
                else -> parentDoc.createDirectory(safeFolder)
                    ?: throw IOException("Could not create folder $safeFolder.")
            }

            val existingNames = subDirDoc.listFiles().mapNotNullTo(mutableSetOf()) { it.name }
            val trackedMap = (loadManifest(ctx, subDirDoc)?.tracks ?: emptyMap()).toMutableMap()
            val errors = mutableListOf<String>()
            var savedCount = 0
            var failedCount = 0

            for ((index, track) in tracks.withIndex()) {
                currentCoroutineContext().ensureActive()
                onProgress(index, tracks.size, track.title)
                try {
                    val savedName = downloadToDocumentDir(ctx, track, root, subDirDoc, existingNames)
                    existingNames.add(savedName)
                    val trackKey = track.remoteTrackId ?: track.relativePath
                    if (trackKey.isNotEmpty()) {
                        trackedMap[trackKey] = savedName
                    }
                    savedCount += 1
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (err: Exception) {
                    failedCount += 1
                    errors.add("${track.title}: ${err.message ?: err.javaClass.simpleName}")
                } finally {
                    onProgress(index + 1, tracks.size, track.title)
                }
            }

            val sourceUrl = root?.sourceUrl?.trim()
            val cloudKind = root?.effectiveCloudKind
            if (!sourceUrl.isNullOrEmpty() && cloudKind != null) {
                runCatching {
                    saveManifest(
                        ctx,
                        subDirDoc,
                        CloudFolderManifest(
                            sourceURL = sourceUrl,
                            remoteID = root.remoteId,
                            cloudKind = cloudKind.name,
                            lastSyncedEpochMs = System.currentTimeMillis(),
                            tracks = trackedMap,
                        ),
                    )
                }
            }

            CloudExportSummary(
                savedCount = savedCount,
                failedCount = failedCount,
                targetTreeUri = subDirDoc.uri,
                targetDir = null,
                savedFolderName = subDirDoc.name ?: safeFolder,
                errors = errors,
            )
        }
    }

    suspend fun updateLocalFolder(
        root: LibraryRoot,
        targetDir: File,
        fetchPlaylist: suspend (sourceUrl: String, cookie: String?, kindHint: RootKind?) -> CloudPlaylistResult,
        onProgress: (done: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
    ): CloudUpdateSummary = withContext(Dispatchers.IO) {
        val existingManifest = loadManifest(targetDir)
        val rawSource = (existingManifest?.sourceURL ?: root.sourceUrl ?: root.remoteId ?: "").trim()
        if (rawSource.isEmpty()) throw IOException("No cloud tracking source URL is associated with this folder.")
        val kindHint = root.effectiveCloudKind
            ?: existingManifest?.cloudKind?.let { raw -> runCatching { RootKind.valueOf(raw) }.getOrNull() }

        val playlist = fetchPlaylist(rawSource, root.cookie, kindHint)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Could not access target folder: ${targetDir.absolutePath}")
        }

        val cloudKind = if (playlist.source == CloudSource.YOUTUBE) RootKind.CLOUD_YOUTUBE else RootKind.CLOUD_BILIBILI
        val lookupRoot = root.copy(kind = cloudKind, sourceUrl = rawSource, remoteId = playlist.remoteId)
        val existingNames = targetDir.list()?.toMutableSet() ?: mutableSetOf()
        val trackedMap = (existingManifest?.tracks ?: emptyMap()).toMutableMap()
        val sourceSlug = cloudKind.name.lowercase()
        val nowMs = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        var addedCount = 0
        var skippedCount = 0
        var failedCount = 0

        for ((index, meta) in playlist.tracks.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress(index, playlist.tracks.size, meta.title)

            val recordedName = trackedMap[meta.remoteTrackId]
            if (recordedName != null && File(targetDir, recordedName).exists()) {
                skippedCount += 1
                onProgress(index + 1, playlist.tracks.size, meta.title)
                continue
            }

            val relPath = "$sourceSlug/${meta.remoteTrackId}"
            val tempTrack = Track(
                id = Track.makeId(root.id, relPath),
                rootId = root.id,
                relativePath = relPath,
                title = meta.title.ifBlank { meta.remoteTrackId },
                artist = meta.artist.ifBlank { root.name },
                album = meta.album.ifBlank { playlist.title.ifBlank { root.name } },
                albumArtist = meta.artist.ifBlank { root.name },
                trackNumber = meta.trackNumber ?: (index + 1),
                durationSec = meta.durationSec,
                fileSize = 0L,
                modifiedEpochMs = 0L,
                dateAddedEpochMs = nowMs,
                cloudSource = playlist.source,
                remoteTrackId = meta.remoteTrackId,
                remoteArtworkUrl = meta.artworkUrl,
            )

            try {
                val savedName = downloadToFileDir(tempTrack, lookupRoot, targetDir, existingNames)
                existingNames.add(savedName)
                trackedMap[meta.remoteTrackId] = savedName
                addedCount += 1
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: Exception) {
                failedCount += 1
                errors.add("${meta.title}: ${err.message ?: err.javaClass.simpleName}")
            } finally {
                onProgress(index + 1, playlist.tracks.size, meta.title)
            }
        }

        saveManifest(
            targetDir,
            CloudFolderManifest(
                sourceURL = rawSource,
                remoteID = playlist.remoteId,
                cloudKind = cloudKind.name,
                lastSyncedEpochMs = System.currentTimeMillis(),
                tracks = trackedMap,
            ),
        )

        CloudUpdateSummary(
            addedCount = addedCount,
            skippedCount = skippedCount,
            failedCount = failedCount,
            totalRemoteCount = playlist.tracks.size,
            errors = errors,
        )
    }

    suspend fun updateLocalFolder(
        root: LibraryRoot,
        treeUri: Uri,
        fetchPlaylist: suspend (sourceUrl: String, cookie: String?, kindHint: RootKind?) -> CloudPlaylistResult,
        onProgress: (done: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
    ): CloudUpdateSummary {
        if (treeUri.scheme == "file") {
            val path = treeUri.path ?: throw IOException("Invalid file URI: $treeUri")
            val baseDir = File(path)
            val targetDir = root.trackedSubfolderName?.takeIf { it.isNotBlank() }?.let { File(baseDir, it) } ?: baseDir
            return updateLocalFolder(root, targetDir, fetchPlaylist, onProgress)
        }
        return withContext(Dispatchers.IO) {
            val ctx = context ?: throw IOException("Android context unavailable for SAF export.")
            val rootDoc = DocumentFile.fromTreeUri(ctx, treeUri)
                ?: throw IOException("Could not open saved folder.")
            val targetDoc = root.trackedSubfolderName?.takeIf { it.isNotBlank() }?.let { subName ->
                rootDoc.findFile(subName)?.takeIf { it.isDirectory }
                    ?: rootDoc.createDirectory(subName)
                    ?: throw IOException("Could not open subfolder $subName.")
            } ?: rootDoc
            if (!targetDoc.exists() || !targetDoc.isDirectory || !targetDoc.canWrite()) {
                throw IOException("Saved folder is not writable. Re-select it to grant write permission.")
            }

            val existingManifest = loadManifest(ctx, targetDoc)
            val rawSource = (existingManifest?.sourceURL ?: root.sourceUrl ?: root.remoteId ?: "").trim()
            if (rawSource.isEmpty()) throw IOException("No cloud tracking source URL is associated with this folder.")
            val kindHint = root.effectiveCloudKind
                ?: existingManifest?.cloudKind?.let { raw -> runCatching { RootKind.valueOf(raw) }.getOrNull() }

            val playlist = fetchPlaylist(rawSource, root.cookie, kindHint)
            val cloudKind = if (playlist.source == CloudSource.YOUTUBE) RootKind.CLOUD_YOUTUBE else RootKind.CLOUD_BILIBILI
            val lookupRoot = root.copy(kind = cloudKind, sourceUrl = rawSource, remoteId = playlist.remoteId)
            val existingNames = targetDoc.listFiles().mapNotNullTo(mutableSetOf()) { it.name }
            val trackedMap = (existingManifest?.tracks ?: emptyMap()).toMutableMap()
            val sourceSlug = cloudKind.name.lowercase()
            val nowMs = System.currentTimeMillis()
            val errors = mutableListOf<String>()
            var addedCount = 0
            var skippedCount = 0
            var failedCount = 0

            for ((index, meta) in playlist.tracks.withIndex()) {
                currentCoroutineContext().ensureActive()
                onProgress(index, playlist.tracks.size, meta.title)

                val recordedName = trackedMap[meta.remoteTrackId]
                if (recordedName != null && recordedName in existingNames) {
                    skippedCount += 1
                    onProgress(index + 1, playlist.tracks.size, meta.title)
                    continue
                }

                val relPath = "$sourceSlug/${meta.remoteTrackId}"
                val tempTrack = Track(
                    id = Track.makeId(root.id, relPath),
                    rootId = root.id,
                    relativePath = relPath,
                    title = meta.title.ifBlank { meta.remoteTrackId },
                    artist = meta.artist.ifBlank { root.name },
                    album = meta.album.ifBlank { playlist.title.ifBlank { root.name } },
                    albumArtist = meta.artist.ifBlank { root.name },
                    trackNumber = meta.trackNumber ?: (index + 1),
                    durationSec = meta.durationSec,
                    fileSize = 0L,
                    modifiedEpochMs = 0L,
                    dateAddedEpochMs = nowMs,
                    cloudSource = playlist.source,
                    remoteTrackId = meta.remoteTrackId,
                    remoteArtworkUrl = meta.artworkUrl,
                )

                try {
                    val savedName = downloadToDocumentDir(ctx, tempTrack, lookupRoot, targetDoc, existingNames)
                    existingNames.add(savedName)
                    trackedMap[meta.remoteTrackId] = savedName
                    addedCount += 1
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (err: Exception) {
                    failedCount += 1
                    errors.add("${meta.title}: ${err.message ?: err.javaClass.simpleName}")
                } finally {
                    onProgress(index + 1, playlist.tracks.size, meta.title)
                }
            }

            saveManifest(
                ctx,
                targetDoc,
                CloudFolderManifest(
                    sourceURL = rawSource,
                    remoteID = playlist.remoteId,
                    cloudKind = cloudKind.name,
                    lastSyncedEpochMs = System.currentTimeMillis(),
                    tracks = trackedMap,
                ),
            )

            CloudUpdateSummary(
                addedCount = addedCount,
                skippedCount = skippedCount,
                failedCount = failedCount,
                totalRemoteCount = playlist.tracks.size,
                errors = errors,
            )
        }
    }

    suspend fun exportTrack(
        track: Track,
        root: LibraryRoot?,
        targetDir: File,
    ): String = withContext(Dispatchers.IO) {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Could not create destination folder: ${targetDir.absolutePath}")
        }
        if (!targetDir.isDirectory) {
            throw IOException("Destination is not a directory: ${targetDir.absolutePath}")
        }
        val existingNames = targetDir.list()?.toSet().orEmpty()
        downloadToFileDir(track, root, targetDir, existingNames)
    }

    suspend fun exportTrack(
        track: Track,
        root: LibraryRoot?,
        targetTreeUri: Uri,
    ): String {
        if (targetTreeUri.scheme == "file") {
            val path = targetTreeUri.path ?: throw IOException("Invalid file URI: $targetTreeUri")
            return exportTrack(track, root, File(path))
        }
        return withContext(Dispatchers.IO) {
            val ctx = context ?: throw IOException("Android context unavailable for SAF export.")
            val dirDoc = DocumentFile.fromTreeUri(ctx, targetTreeUri)
                ?: throw IOException("Could not open selected folder.")
            if (!dirDoc.exists() || !dirDoc.isDirectory || !dirDoc.canWrite()) {
                throw IOException("Selected folder is not writable.")
            }
            val existingNames = dirDoc.listFiles().mapNotNullTo(mutableSetOf()) { it.name }
            downloadToDocumentDir(ctx, track, root, dirDoc, existingNames)
        }
    }

    private suspend fun downloadToFileDir(
        track: Track,
        root: LibraryRoot?,
        targetDir: File,
        existingNames: Set<String>,
    ): String {
        return withStreamRetry(track, root) { stream ->
            val (ext, _) = extensionAndMimeFor(stream)
            val baseName = sanitizeFileName(track.title, track.remoteTrackId ?: "track")
            val fileName = uniqueFileName(existingNames, baseName, ext)
            val targetFile = File(targetDir, fileName)
            val partFile = File(targetDir, ".$fileName.part")
            try {
                FileOutputStream(partFile).use { out ->
                    downloader.download(stream.url, stream.headers, out)
                }
                if (!partFile.renameTo(targetFile)) {
                    partFile.copyTo(targetFile, overwrite = true)
                    partFile.delete()
                }
                fileName
            } catch (t: Throwable) {
                runCatching { partFile.delete() }
                runCatching { targetFile.delete() }
                throw t
            }
        }
    }

    private suspend fun downloadToDocumentDir(
        ctx: Context,
        track: Track,
        root: LibraryRoot?,
        dirDoc: DocumentFile,
        existingNames: Set<String>,
    ): String {
        return withStreamRetry(track, root) { stream ->
            val (ext, mimeType) = extensionAndMimeFor(stream)
            val baseName = sanitizeFileName(track.title, track.remoteTrackId ?: "track")
            val fileName = uniqueFileName(existingNames, baseName, ext)
            val doc = dirDoc.createFile(mimeType, fileName)
                ?: throw IOException("Could not create file $fileName in destination folder.")
            try {
                val out = ctx.contentResolver.openOutputStream(doc.uri)
                    ?: throw IOException("Could not open output stream for $fileName.")
                out.use { streamOut ->
                    downloader.download(stream.url, stream.headers, streamOut)
                }
                doc.name ?: fileName
            } catch (t: Throwable) {
                runCatching { doc.delete() }
                throw t
            }
        }
    }

    private suspend fun <T> withStreamRetry(
        track: Track,
        root: LibraryRoot?,
        block: suspend (ResolvedStream) -> T,
    ): T {
        val firstStream = cloudResolver.resolve(track, root, forceRefresh = false)
        return try {
            block(firstStream)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            cloudResolver.invalidate(track, root)
            val refreshed = cloudResolver.resolve(track, root, forceRefresh = true)
            block(refreshed)
        }
    }

    companion object {
        const val MANIFEST_FILE_NAME = ".musico-cloud.json"
        private const val SAF_MANIFEST_FILE_NAME = "musico-cloud.json"
        private val manifestJson = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
        private val INVALID_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")
        private val MULTI_WHITESPACE = Regex("\\s+")
        private const val MAX_BASENAME_LENGTH = 120

        fun loadManifest(dir: File): CloudFolderManifest? {
            val file = listOf(MANIFEST_FILE_NAME, SAF_MANIFEST_FILE_NAME)
                .map { File(dir, it) }
                .firstOrNull { it.exists() && it.isFile } ?: return null
            return runCatching {
                manifestJson.decodeFromString<CloudFolderManifest>(file.readText(Charsets.UTF_8))
            }.getOrNull()
        }

        fun saveManifest(dir: File, manifest: CloudFolderManifest) {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, MANIFEST_FILE_NAME)
            val tmp = File(dir, ".$MANIFEST_FILE_NAME.tmp")
            tmp.writeText(manifestJson.encodeToString(manifest), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }

        fun loadManifest(context: Context, dirDoc: DocumentFile): CloudFolderManifest? {
            val doc = dirDoc.listFiles().firstOrNull {
                it.name == MANIFEST_FILE_NAME || it.name == SAF_MANIFEST_FILE_NAME
            } ?: return null
            return runCatching {
                context.contentResolver.openInputStream(doc.uri)?.use { input ->
                    val text = input.readBytes().toString(Charsets.UTF_8)
                    manifestJson.decodeFromString<CloudFolderManifest>(text)
                }
            }.getOrNull()
        }

        fun saveManifest(context: Context, dirDoc: DocumentFile, manifest: CloudFolderManifest) {
            val payload = manifestJson.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            dirDoc.listFiles()
                .filter { it.name == MANIFEST_FILE_NAME || it.name == SAF_MANIFEST_FILE_NAME }
                .forEach { runCatching { it.delete() } }
            val created = dirDoc.createFile("application/octet-stream", MANIFEST_FILE_NAME)
                ?: dirDoc.createFile("application/octet-stream", SAF_MANIFEST_FILE_NAME)
                ?: throw IOException("Could not create cloud manifest file.")
            context.contentResolver.openOutputStream(created.uri)?.use { out ->
                out.write(payload)
                out.flush()
            } ?: throw IOException("Could not write cloud manifest file.")
        }

        fun sanitizeFileName(raw: String, fallback: String = "track"): String {
            val safeFallback = cleanSegment(fallback).ifBlank { "track" }
            val cleaned = cleanSegment(raw)
            if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
                return safeFallback
            }
            return cleaned
        }

        private fun cleanSegment(raw: String): String {
            var s = INVALID_FILENAME_CHARS.replace(raw, "_")
            s = MULTI_WHITESPACE.replace(s, " ")
            s = s.trim { it.isWhitespace() || it == '.' }
            if (s.length > MAX_BASENAME_LENGTH) {
                s = s.substring(0, MAX_BASENAME_LENGTH).trim { it.isWhitespace() || it == '.' }
            }
            return s
        }

        fun uniqueFileName(existingNames: Set<String>, baseName: String, ext: String): String {
            val cleanBase = sanitizeFileName(baseName, "track")
            val cleanExt = ext.trim().trimStart('.').lowercase()
            val suffix = if (cleanExt.isNotEmpty()) ".$cleanExt" else ""
            val lowerExisting = existingNames.mapTo(HashSet(existingNames.size)) { it.lowercase() }

            val first = "$cleanBase$suffix"
            if (first.lowercase() !in lowerExisting) {
                return first
            }

            var index = 2
            while (true) {
                val candidate = "$cleanBase ($index)$suffix"
                if (candidate.lowercase() !in lowerExisting) {
                    return candidate
                }
                index += 1
            }
        }

        internal fun extensionAndMimeFor(stream: ResolvedStream): Pair<String, String> {
            val rawMime = stream.mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            return when {
                rawMime.contains("webm") -> "webm" to "audio/webm"
                rawMime.contains("opus") -> "opus" to "audio/opus"
                rawMime.contains("ogg") -> "ogg" to "audio/ogg"
                rawMime.contains("flac") -> "flac" to "audio/flac"
                rawMime.contains("mpeg") || rawMime.contains("mp3") -> "mp3" to "audio/mpeg"
                rawMime.contains("aac") -> "aac" to "audio/aac"
                rawMime.contains("wav") -> "wav" to "audio/wav"
                rawMime.contains("mp4") || rawMime.contains("m4a") -> "m4a" to "audio/mp4"
                else -> extensionFromUrl(stream.url)
            }
        }

        private fun extensionFromUrl(url: String): Pair<String, String> {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return when {
                path.endsWith(".webm") -> "webm" to "audio/webm"
                path.endsWith(".opus") -> "opus" to "audio/opus"
                path.endsWith(".ogg") -> "ogg" to "audio/ogg"
                path.endsWith(".flac") -> "flac" to "audio/flac"
                path.endsWith(".mp3") -> "mp3" to "audio/mpeg"
                path.endsWith(".aac") -> "aac" to "audio/aac"
                path.endsWith(".wav") -> "wav" to "audio/wav"
                else -> "m4a" to "audio/mp4"
            }
        }
    }
}
