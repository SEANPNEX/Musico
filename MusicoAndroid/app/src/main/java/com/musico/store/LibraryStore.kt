package com.musico.store

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.musico.cloud.BilibiliClient
import com.musico.cloud.CloudExporter
import com.musico.cloud.CloudHttpClient
import com.musico.cloud.CloudPlaylistResult
import com.musico.cloud.CloudSource
import com.musico.cloud.CloudTrackMetadata
import com.musico.cloud.UrlConnectionCloudHttpClient
import com.musico.cloud.YouTubeClient
import com.musico.core.ArtworkCache
import com.musico.core.FolderScanner
import com.musico.core.MetadataReading
import com.musico.core.Persistence
import com.musico.core.ScannedFile
import com.musico.core.TrackMetadata
import com.musico.model.Album
import com.musico.model.LibraryRoot
import com.musico.model.Playlist
import com.musico.model.RootKind
import com.musico.model.SearchField
import com.musico.model.Track
import com.musico.model.TrackMetadataOverride
import com.musico.model.TrackSortOrder
import com.musico.model.sortTracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.text.Normalizer
import java.util.UUID
import kotlin.math.abs

sealed class LibraryException(message: String) : Exception(message) {
    data object DuplicateRoot : LibraryException("That folder is already in your library.")
    data object NotAFolder : LibraryException("That location could not be opened as a folder.")
    data object UnsupportedCloudUrl : LibraryException("Enter a valid YouTube or Bilibili playlist, collection, or video URL.")
}

data class LibraryUiState(
    val roots: List<LibraryRoot> = emptyList(),
    val availableRootIds: Set<String> = emptySet(),
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<String> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val favourites: Set<String> = emptySet(),
    val favouritesSortOrder: TrackSortOrder = TrackSortOrder.NAME_ASC,
    val folderSortOrders: Map<String, TrackSortOrder> = emptyMap(),
    val folderTrackOrders: Map<String, List<String>> = emptyMap(),
    val isScanning: Boolean = false,
    val scanDone: Int = 0,
    val scanTotal: Int = 0,
)

class LibraryStore(
    private val directory: File,
    private val appFolderDir: File,
    private val reader: MetadataReading,
    val artwork: ArtworkCache,
    private val context: Context? = null,
    private val scanBatchSize: Int = 100,
    private val cloudHttpClient: CloudHttpClient = UrlConnectionCloudHttpClient(),
    private val youTubeClient: YouTubeClient = YouTubeClient(cloudHttpClient),
    private val bilibiliClient: BilibiliClient = BilibiliClient(cloudHttpClient),
) {
    @Serializable
    private data class LibraryFile(
        val roots: List<LibraryRoot> = emptyList(),
        val tracks: List<Track> = emptyList(),
        val readerVersion: Int? = null,
        val trackUris: Map<String, String> = emptyMap(),
        val metadataOverrides: Map<String, TrackMetadataOverride> = emptyMap(),
        val folderSortOrders: Map<String, TrackSortOrder> = emptyMap(),
        val folderTrackOrders: Map<String, List<String>> = emptyMap(),
    )

    @Serializable
    private data class PlaylistFile(
        val playlists: List<Playlist> = emptyList(),
        val favourites: Set<String> = emptySet(),
        val favouritesSortOrder: TrackSortOrder = TrackSortOrder.NAME_ASC,
    )

    var onTracksRemoved: ((Set<String>) -> Unit)? = null
    var onFolderAdded: ((folderName: String, trackIds: List<String>) -> Unit)? = null
    var onTrackUpdated: ((Track) -> Unit)? = null

    private val libraryFile = File(directory, "library.json")
    private val playlistsFile = File(directory, "playlists.json")
    private val batchSize = scanBatchSize.coerceAtLeast(1)

    private var rereadAll = false
    private var byId: Map<String, Track> = emptyMap()
    private val trackUris = mutableMapOf<String, String>()
    private val metadataOverrides = mutableMapOf<String, TrackMetadataOverride>()
    private val folderSortOrders = mutableMapOf<String, TrackSortOrder>()
    private val folderTrackOrders = mutableMapOf<String, List<String>>()

    private var rescanRequested = false
    private var scanningJob: Job? = null

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        val loadedLib = Persistence.load<LibraryFile>(libraryFile)
        rereadAll = loadedLib != null && loadedLib.readerVersion != READER_VERSION

        val savedAppRoot = loadedLib?.roots?.firstOrNull { it.kind == RootKind.APP_FOLDER }
        val userRoots = loadedLib?.roots?.filter { it.kind != RootKind.APP_FOLDER }.orEmpty()
        val appRoot = LibraryRoot(
            id = LibraryRoot.APP_FOLDER_ID,
            name = APP_FOLDER_NAME,
            kind = RootKind.APP_FOLDER,
            treeUri = null,
            excludedPaths = savedAppRoot?.excludedPaths.orEmpty(),
        )
        val initialRoots = listOf(appRoot) + userRoots
        makeAppFolderVisible(appFolderDir)

        if (loadedLib != null) {
            trackUris.putAll(loadedLib.trackUris)
            metadataOverrides.putAll(loadedLib.metadataOverrides)
            folderSortOrders.putAll(loadedLib.folderSortOrders)
            folderTrackOrders.putAll(loadedLib.folderTrackOrders)
        }

        val initialAvailable = computeAvailableRootIds(initialRoots)
        val initialTracks = loadedLib?.tracks.orEmpty().map { applyMetadataOverride(it) }
        val loadedPlaylists = Persistence.load<PlaylistFile>(playlistsFile)

        val (albums, artists, idMap) = buildIndices(initialTracks)
        byId = idMap

        _state.value = LibraryUiState(
            roots = initialRoots,
            availableRootIds = initialAvailable,
            tracks = initialTracks,
            albums = albums,
            artists = artists,
            playlists = loadedPlaylists?.playlists.orEmpty(),
            favourites = loadedPlaylists?.favourites.orEmpty(),
            favouritesSortOrder = loadedPlaylists?.favouritesSortOrder ?: TrackSortOrder.NAME_ASC,
            folderSortOrders = folderSortOrders.toMap(),
            folderTrackOrders = folderTrackOrders.toMap(),
        )
    }

    private fun computeAvailableRootIds(roots: List<LibraryRoot>): Set<String> {
        val available = mutableSetOf<String>()
        for (root in roots) {
            if (checkRootAvailable(root)) {
                available.add(root.id)
            }
        }
        return available
    }

    private fun checkRootAvailable(root: LibraryRoot): Boolean {
        if (root.kind.isCloud) {
            return true
        }
        if (root.kind == RootKind.APP_FOLDER) {
            return appFolderDir.exists() && appFolderDir.isDirectory && appFolderDir.canRead()
        }
        val uriStr = root.treeUri ?: return false
        fileFromUriString(uriStr)?.let { file ->
            return file.exists() && file.isDirectory && file.canRead()
        }
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return false
        val ctx = context ?: return false
        val doc = runCatching { DocumentFile.fromTreeUri(ctx, uri) }.getOrNull() ?: return false
        return doc.exists() && doc.isDirectory && doc.canRead()
    }

    private fun saveLibrary() {
        val current = _state.value
        runCatching {
            Persistence.save(
                LibraryFile(
                    roots = current.roots,
                    tracks = current.tracks,
                    readerVersion = if (rereadAll) null else READER_VERSION,
                    trackUris = trackUris.toMap(),
                    metadataOverrides = metadataOverrides.toMap(),
                    folderSortOrders = folderSortOrders.toMap(),
                    folderTrackOrders = folderTrackOrders.toMap(),
                ),
                libraryFile,
            )
        }
    }

    private fun savePlaylists() {
        val current = _state.value
        runCatching {
            Persistence.save(
                PlaylistFile(
                    playlists = current.playlists,
                    favourites = current.favourites,
                    favouritesSortOrder = current.favouritesSortOrder,
                ),
                playlistsFile,
            )
        }
    }

    private fun fileFromUriString(uriStr: String?): File? {
        if (uriStr.isNullOrBlank()) return null
        if (uriStr.startsWith("file:")) {
            return runCatching { File(java.net.URI(uriStr)) }.getOrNull()
        }
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
        if (uri.scheme == "file" && uri.path != null) {
            return File(uri.path!!)
        }
        return null
    }

    private fun canonicalUriKey(uri: Uri): String {
        if (uri.scheme == "file" && uri.path != null) {
            return runCatching { File(uri.path!!).canonicalPath }.getOrElse { uri.toString() }
        }
        return uri.toString()
    }

    private fun canonicalUriKey(uriStr: String): String {
        fileFromUriString(uriStr)?.let { file ->
            return runCatching { file.canonicalPath }.getOrElse { uriStr }
        }
        return uriStr
    }

    private fun resolvePickedRootName(uri: Uri): String? {
        if (uri.scheme == "file") {
            val dir = uri.path?.let { File(it) } ?: return null
            if (!dir.exists() || !dir.isDirectory || !dir.canRead()) return null
            return dir.name
        }
        val ctx = context ?: return null
        val doc = runCatching { DocumentFile.fromTreeUri(ctx, uri) }.getOrNull() ?: return null
        if (!doc.exists() || !doc.isDirectory || !doc.canRead()) return null
        return doc.name ?: uri.lastPathSegment ?: "Music Folder"
    }

    private fun takePersistableReadPermission(uri: Uri) {
        if (uri.scheme != "content") return
        val ctx = context ?: return
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    private fun releasePersistableReadPermission(uriStr: String?) {
        if (uriStr == null) return
        val ctx = context ?: return
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return
        if (uri.scheme != "content") return
        runCatching {
            ctx.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    suspend fun addLocalFolder(
        dir: File,
        cloudSourceRoot: LibraryRoot? = null,
        trackedSubfolderName: String? = null,
    ): Result<Unit> {
        if (!dir.exists() || !dir.isDirectory || !dir.canRead()) {
            return Result.failure(LibraryException.NotAFolder)
        }
        val uriString = dir.toURI().toString()
        val candidateKey = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
        return addPickedRootInternal(
            name = dir.name,
            treeUriString = uriString,
            candidateKey = candidateKey,
            treeUri = runCatching { Uri.fromFile(dir) }.getOrNull(),
            cloudSourceRoot = cloudSourceRoot,
            trackedSubfolderName = trackedSubfolderName,
        )
    }

    suspend fun addPickedRoot(
        treeUri: Uri,
        cloudSourceRoot: LibraryRoot? = null,
        trackedSubfolderName: String? = null,
    ): Result<Unit> {
        val name = resolvePickedRootName(treeUri)
            ?: return Result.failure(LibraryException.NotAFolder)
        return addPickedRootInternal(
            name = name,
            treeUriString = treeUri.toString(),
            candidateKey = canonicalUriKey(treeUri),
            treeUri = treeUri,
            cloudSourceRoot = cloudSourceRoot,
            trackedSubfolderName = trackedSubfolderName,
        )
    }

    private suspend fun addPickedRootInternal(
        name: String,
        treeUriString: String,
        candidateKey: String,
        treeUri: Uri?,
        cloudSourceRoot: LibraryRoot? = null,
        trackedSubfolderName: String? = null,
    ): Result<Unit> {
        val appCanonical = runCatching { appFolderDir.canonicalPath }.getOrElse { appFolderDir.absolutePath }
        val duplicateIndex = _state.value.roots.indexOfFirst { root ->
            when (root.kind) {
                RootKind.APP_FOLDER -> appCanonical == candidateKey
                RootKind.PICKED -> root.treeUri?.let { canonicalUriKey(it) == candidateKey } == true
                RootKind.CLOUD_YOUTUBE, RootKind.CLOUD_BILIBILI -> false
            }
        }
        if (duplicateIndex >= 0) {
            if (cloudSourceRoot != null) {
                val updatedRoots = _state.value.roots.toMutableList()
                val existing = updatedRoots[duplicateIndex]
                val updatedRoot = existing.copy(
                    sourceUrl = cloudSourceRoot.sourceUrl ?: existing.sourceUrl,
                    remoteId = cloudSourceRoot.remoteId ?: existing.remoteId,
                    linkedCloudKind = cloudSourceRoot.effectiveCloudKind ?: existing.linkedCloudKind,
                    cookie = cloudSourceRoot.cookie ?: existing.cookie,
                    lastSyncedEpochMs = System.currentTimeMillis(),
                    trackedSubfolderName = trackedSubfolderName ?: existing.trackedSubfolderName,
                )
                updatedRoots[duplicateIndex] = updatedRoot
                _state.value = _state.value.copy(roots = updatedRoots)
                saveLibrary()
                rescan()
                notifyFolderAdded(updatedRoot, trackedSubfolderName)
                return Result.success(Unit)
            }
            return Result.failure(LibraryException.DuplicateRoot)
        }

        if (treeUri != null) {
            takePersistableReadPermission(treeUri)
        }
        val manifest = if (cloudSourceRoot == null && treeUri != null) {
            resolveManifestForPickedUri(treeUri, trackedSubfolderName)
        } else {
            null
        }
        val manifestKind = manifest?.cloudKind?.let { raw ->
            runCatching { RootKind.valueOf(raw) }.getOrNull()
        }
        val root = LibraryRoot(
            id = UUID.randomUUID().toString(),
            name = name,
            kind = RootKind.PICKED,
            treeUri = treeUriString,
            sourceUrl = cloudSourceRoot?.sourceUrl ?: manifest?.sourceURL,
            remoteId = cloudSourceRoot?.remoteId ?: manifest?.remoteID,
            cookie = cloudSourceRoot?.cookie,
            lastSyncedEpochMs = if (cloudSourceRoot != null) {
                System.currentTimeMillis()
            } else {
                manifest?.lastSyncedEpochMs?.takeIf { it > 0L }
            },
            linkedCloudKind = cloudSourceRoot?.effectiveCloudKind ?: manifestKind,
            trackedSubfolderName = trackedSubfolderName,
        )
        val current = _state.value
        _state.value = current.copy(
            roots = current.roots + root,
            availableRootIds = current.availableRootIds + root.id,
        )
        saveLibrary()
        rescan()
        notifyFolderAdded(root, trackedSubfolderName)
        return Result.success(Unit)
    }

    private fun notifyFolderAdded(root: LibraryRoot, trackedSubfolderName: String?) {
        val sub = trackedSubfolderName?.trim()?.takeIf { it.isNotEmpty() }
        val folderName = sub ?: root.name
        val subPath = sub?.let { LibraryRoot.normalizeFolderPath(it) }.orEmpty()
        val trackIds = tracksUnder(root.id, subPath).map { it.id }
        onFolderAdded?.invoke(folderName, trackIds)
    }

    private fun resolveManifestForPickedUri(treeUri: Uri, subfolderName: String?): com.musico.cloud.CloudFolderManifest? {
        if (treeUri.scheme == "file" && treeUri.path != null) {
            val base = File(treeUri.path!!)
            val target = subfolderName?.takeIf { it.isNotBlank() }?.let { File(base, it) } ?: base
            return CloudExporter.loadManifest(target)
        }
        val ctx = context ?: return null
        val rootDoc = runCatching { DocumentFile.fromTreeUri(ctx, treeUri) }.getOrNull() ?: return null
        val targetDoc = subfolderName?.takeIf { it.isNotBlank() }?.let { rootDoc.findFile(it) } ?: rootDoc
        return CloudExporter.loadManifest(ctx, targetDoc)
    }

    suspend fun fetchCloudPlaylist(
        sourceUrl: String,
        cookie: String? = null,
        kindHint: RootKind? = null,
    ): CloudPlaylistResult = withContext(Dispatchers.IO) {
        when (kindHint) {
            RootKind.CLOUD_BILIBILI -> bilibiliClient.fetchPlaylist(sourceUrl, cookie)
            RootKind.CLOUD_YOUTUBE -> youTubeClient.fetchPlaylist(sourceUrl, cookie)
            else -> {
                if (sourceUrl.contains("bilibili", ignoreCase = true) || sourceUrl.contains("b23.tv", ignoreCase = true)) {
                    bilibiliClient.fetchPlaylist(sourceUrl, cookie)
                } else {
                    youTubeClient.fetchPlaylist(sourceUrl, cookie)
                }
            }
        }
    }

    fun markRootCloudSynced(rootId: String, syncedEpochMs: Long = System.currentTimeMillis()) {
        val current = _state.value
        val index = current.roots.indexOfFirst { it.id == rootId }
        if (index < 0) return
        val updated = current.roots.toMutableList()
        updated[index] = updated[index].copy(lastSyncedEpochMs = syncedEpochMs)
        _state.value = current.copy(roots = updated)
        saveLibrary()
    }

    suspend fun relocateRoot(rootId: String, treeUri: Uri): Result<Unit> {
        val current = _state.value
        val index = current.roots.indexOfFirst { it.id == rootId && it.kind == RootKind.PICKED }
        if (index < 0) return Result.success(Unit)

        val name = resolvePickedRootName(treeUri)
            ?: return Result.failure(LibraryException.NotAFolder)

        val oldUri = current.roots[index].treeUri
        if (oldUri != treeUri.toString()) {
            releasePersistableReadPermission(oldUri)
        }
        takePersistableReadPermission(treeUri)

        val updatedRoots = current.roots.toMutableList()
        updatedRoots[index] = updatedRoots[index].copy(
            name = name,
            treeUri = treeUri.toString(),
        )
        _state.value = current.copy(
            roots = updatedRoots,
            availableRootIds = current.availableRootIds + rootId,
        )
        saveLibrary()
        rescan()
        return Result.success(Unit)
    }

    suspend fun addCloudRoot(sourceUrl: String, cookie: String? = null): Result<Unit> {
        val trimmedUrl = sourceUrl.trim()
        if (trimmedUrl.isEmpty()) {
            return Result.failure(LibraryException.UnsupportedCloudUrl)
        }
        val cleanCookie = cookie?.trim()?.takeIf { it.isNotEmpty() }

        val isBilibili = BilibiliClient.canHandle(trimmedUrl)
        val isYouTube = YouTubeClient.canHandle(trimmedUrl)
        if (!isBilibili && !isYouTube) {
            return Result.failure(LibraryException.UnsupportedCloudUrl)
        }

        val playlist = try {
            withContext(Dispatchers.IO) {
                if (isBilibili && !isYouTube) {
                    bilibiliClient.fetchPlaylist(trimmedUrl, cleanCookie)
                } else if (isYouTube && !isBilibili) {
                    youTubeClient.fetchPlaylist(trimmedUrl, cleanCookie)
                } else {
                    if (trimmedUrl.contains("bilibili", ignoreCase = true) || trimmedUrl.contains("b23.tv", ignoreCase = true)) {
                        bilibiliClient.fetchPlaylist(trimmedUrl, cleanCookie)
                    } else {
                        youTubeClient.fetchPlaylist(trimmedUrl, cleanCookie)
                    }
                }
            }
        } catch (err: Exception) {
            return Result.failure(err)
        }

        val kind = if (playlist.source == CloudSource.YOUTUBE) {
            RootKind.CLOUD_YOUTUBE
        } else {
            RootKind.CLOUD_BILIBILI
        }

        val duplicate = _state.value.roots.any {
            it.kind == kind && it.remoteId == playlist.remoteId
        }
        if (duplicate) {
            return Result.failure(LibraryException.DuplicateRoot)
        }

        val nowMs = System.currentTimeMillis()
        val root = LibraryRoot(
            id = UUID.randomUUID().toString(),
            name = playlist.title,
            kind = kind,
            sourceUrl = trimmedUrl,
            remoteId = playlist.remoteId,
            cookie = cleanCookie,
            lastSyncedEpochMs = nowMs,
        )

        val newTracks = withContext(Dispatchers.IO) {
            buildCloudTracks(
                root = root,
                items = playlist.tracks,
                existingByRel = emptyMap(),
                nowMs = nowMs,
            )
        }

        val current = _state.value
        _state.value = current.copy(
            roots = current.roots + root,
            availableRootIds = current.availableRootIds + root.id,
        )
        publish(current.tracks + newTracks)
        onFolderAdded?.invoke(root.name, newTracks.map { it.id })
        return Result.success(Unit)
    }

    fun removeRoot(rootId: String) {
        val current = _state.value
        val index = current.roots.indexOfFirst { it.id == rootId }
        if (index < 0) return
        val root = current.roots[index]
        if (root.kind == RootKind.APP_FOLDER) return

        if (root.kind == RootKind.PICKED) {
            releasePersistableReadPermission(root.treeUri)
        }

        val updatedRoots = current.roots.toMutableList().apply { removeAt(index) }
        val removedIds = current.tracks.filter { it.rootId == rootId }.map { it.id }.toSet()
        val keptTracks = current.tracks.filter { it.rootId != rootId }
        for (id in removedIds) {
            trackUris.remove(id)
            metadataOverrides.remove(id)
        }

        val (albums, artists, idMap) = buildIndices(keptTracks)
        byId = idMap

        _state.value = current.copy(
            roots = updatedRoots,
            availableRootIds = current.availableRootIds - rootId,
            tracks = keptTracks,
            albums = albums,
            artists = artists,
        )
        forget(removedIds)
        saveLibrary()
    }

    /** Excludes a subfolder from the library without deleting files on disk. */
    fun removeSubfolder(rootId: String, path: String) {
        val norm = LibraryRoot.normalizeFolderPath(path)
        if (norm.isEmpty()) return
        val current = _state.value
        val index = current.roots.indexOfFirst { it.id == rootId }
        if (index < 0) return

        val existingExcluded = current.roots[index].excludedPaths
            .map { LibraryRoot.normalizeFolderPath(it) }
            .filter { it.isNotEmpty() }
        val alreadyCovered = existingExcluded.any { ex -> norm == ex || norm.startsWith("$ex/") }
        val nextExcluded = if (alreadyCovered) {
            existingExcluded
        } else {
            (existingExcluded.filterNot { ex -> ex.startsWith("$norm/") } + norm).sorted()
        }

        val updatedRoots = current.roots.toMutableList()
        updatedRoots[index] = updatedRoots[index].copy(excludedPaths = nextExcluded)

        val removedIds = current.tracks
            .filter { it.rootId == rootId && LibraryRoot.folderContains(norm, it.relativePath) }
            .map { it.id }
            .toSet()

        if (removedIds.isEmpty()) {
            _state.value = current.copy(roots = updatedRoots)
            saveLibrary()
            return
        }

        val keptTracks = current.tracks.filter { it.id !in removedIds }
        for (id in removedIds) {
            trackUris.remove(id)
            metadataOverrides.remove(id)
        }
        val (albums, artists, idMap) = buildIndices(keptTracks)
        byId = idMap

        _state.value = current.copy(
            roots = updatedRoots,
            tracks = keptTracks,
            albums = albums,
            artists = artists,
        )
        forget(removedIds)
        saveLibrary()
        val keysToKeep = keptTracks.mapNotNull { it.artworkKey }.toSet() +
            metadataOverrides.values.mapNotNull { it.artworkKey }
        artwork.prune(keysToKeep)
    }

    /** Clears excluded subfolders on [rootId] and rescans so its songs return. */
    suspend fun restoreExcludedFolders(rootId: String) {
        val current = _state.value
        val index = current.roots.indexOfFirst { it.id == rootId }
        if (index < 0 || current.roots[index].excludedPaths.isEmpty()) return
        val updatedRoots = current.roots.toMutableList()
        updatedRoots[index] = updatedRoots[index].copy(excludedPaths = emptyList())
        _state.value = current.copy(roots = updatedRoots)
        saveLibrary()
        rescan()
    }

    suspend fun rescan() {
        if (_state.value.isScanning) {
            rescanRequested = true
            while (_state.value.isScanning) {
                delay(50)
            }
            return
        }

        _state.value = _state.value.copy(isScanning = true)
        try {
            coroutineScope {
                scanningJob = launch {
                    do {
                        rescanRequested = false
                        scanOnce()
                    } while (rescanRequested)
                }
                scanningJob?.join()
                scanningJob = null
            }
        } finally {
            _state.value = _state.value.copy(isScanning = false)
        }
    }

    private suspend fun scanOnce() {
        _state.value = _state.value.copy(scanDone = 0, scanTotal = 0)

        val currentRoots = _state.value.roots
        val availableNow = computeAvailableRootIds(currentRoots)
        _state.value = _state.value.copy(availableRootIds = availableNow)

        val currentTracks = _state.value.tracks
        val kept = currentTracks.filter { it.rootId !in availableNow }.toMutableList()
        val pending = mutableListOf<PendingScan>()
        val removed = mutableSetOf<String>()
        val reread = rereadAll
        val updatedRoots = currentRoots.toMutableList()
        var rootsChanged = false
        var cloudTracksChanged = false

        for ((rootIndex, root) in currentRoots.withIndex()) {
            if (root.id !in availableNow) continue
            val existingTracks = currentTracks.filter { it.rootId == root.id }

            if (root.kind.isCloud) {
                val url = root.sourceUrl ?: root.remoteId
                if (url.isNullOrBlank()) {
                    kept.addAll(existingTracks)
                    continue
                }
                val syncResult = withContext(Dispatchers.IO) {
                    runCatching {
                        when (root.kind) {
                            RootKind.CLOUD_YOUTUBE -> youTubeClient.fetchPlaylist(url, root.cookie)
                            RootKind.CLOUD_BILIBILI -> bilibiliClient.fetchPlaylist(url, root.cookie)
                            else -> null
                        }
                    }
                }
                val playlist = syncResult.getOrNull()
                if (playlist == null || (playlist.tracks.isEmpty() && existingTracks.isNotEmpty())) {
                    // Retain existing cloud tracks on transient network errors.
                    kept.addAll(existingTracks)
                    continue
                }

                val existingByRel = LinkedHashMap<String, Track>()
                for (t in existingTracks) {
                    existingByRel.putIfAbsent(t.relativePath, t)
                }
                val syncNow = System.currentTimeMillis()
                val syncedTracks = withContext(Dispatchers.IO) {
                    buildCloudTracks(
                        root = root,
                        items = playlist.tracks,
                        existingByRel = existingByRel,
                        nowMs = syncNow,
                    )
                }
                val seenRels = syncedTracks.map { it.relativePath }.toSet()
                for ((rel, oldTrack) in existingByRel) {
                    if (rel !in seenRels) {
                        removed.add(oldTrack.id)
                    }
                }
                if (syncedTracks != existingTracks) {
                    cloudTracksChanged = true
                }
                kept.addAll(syncedTracks)
                updatedRoots[rootIndex] = root.copy(
                    name = playlist.title.ifBlank { root.name },
                    lastSyncedEpochMs = syncNow,
                )
                rootsChanged = true
                continue
            }

            val scanned: List<ScannedFile>? = withContext(Dispatchers.IO) {
                scanRoot(root)
            }

            // Treat unreachable/suddenly empty picked root as temporarily unreadable to avoid purging playlists.
            if (scanned == null || (scanned.isEmpty() && existingTracks.isNotEmpty() && root.kind == RootKind.PICKED)) {
                kept.addAll(existingTracks)
                continue
            }

            val existingByRel = LinkedHashMap<String, Track>()
            for (t in existingTracks) {
                existingByRel.putIfAbsent(t.relativePath, t)
            }

            val seen = mutableSetOf<String>()
            for (file in scanned) {
                if (root.isExcluded(file.relativePath)) continue
                if (!seen.add(file.relativePath)) continue
                val old = existingByRel[file.relativePath]
                if (old != null && file.uriString != null) {
                    trackUris[old.id] = file.uriString
                }
                if (!reread &&
                    old != null &&
                    old.fileSize == file.fileSize &&
                    abs(old.modifiedEpochMs - file.modifiedEpochMs) < 1L
                ) {
                    kept.add(applyMetadataOverride(old))
                } else {
                    pending.add(PendingScan(root = root, file = file, old = old))
                }
            }

            for ((rel, track) in existingByRel) {
                if (rel !in seen) {
                    removed.add(track.id)
                }
            }
        }

        _state.value = _state.value.copy(scanDone = 0, scanTotal = pending.size)
        val nowMs = System.currentTimeMillis()

        for ((index, item) in pending.withIndex()) {
            val fileUri = item.file.uriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?: resolveFileUri(item.root, item.file.relativePath)
            val localFile = resolveLocalFile(item.root, item.file.relativePath)

            val (metadata, artworkKey) = withContext(Dispatchers.IO) {
                val meta = when {
                    fileUri != null -> reader.read(fileUri, item.file.relativePath.substringAfterLast('/'))
                    localFile != null -> reader.read(localFile)
                    else -> TrackMetadata()
                }
                val artKey = meta.artwork?.let { artwork.store(it) }
                meta to artKey
            }

            val track = applyMetadataOverride(
                makeTrack(
                    root = item.root,
                    file = item.file,
                    metadata = metadata,
                    artworkKey = artworkKey,
                    dateAddedEpochMs = item.old?.dateAddedEpochMs ?: nowMs,
                )
            )
            if (item.file.uriString != null) {
                trackUris[track.id] = item.file.uriString
            }
            kept.add(track)
            _state.value = _state.value.copy(scanDone = index + 1, scanTotal = pending.size)

            if ((index + 1) % batchSize == 0 && index + 1 < pending.size) {
                val notReached = pending.subList(index + 1, pending.size).mapNotNull { it.old }
                publish(kept + notReached)
            }
        }

        if (rootsChanged) {
            _state.value = _state.value.copy(roots = updatedRoots)
        }

        val activeRoots = _state.value.roots.associateBy { it.id }
        val finalTracks = kept.filter { track ->
            val r = activeRoots[track.rootId] ?: return@filter false
            !r.isExcluded(track.relativePath)
        }
        val finalIds = finalTracks.map { it.id }.toSet()
        val finalRemoved = removed.filter { it !in finalIds }.toSet()

        if (reread) rereadAll = false
        if (pending.isEmpty() && finalRemoved.isEmpty() && !cloudTracksChanged && finalTracks.size == _state.value.tracks.size) {
            if (reread || rootsChanged) saveLibrary()
            return
        }

        publish(finalTracks)
        for (id in finalRemoved) {
            trackUris.remove(id)
            metadataOverrides.remove(id)
        }
        forget(finalRemoved)

        val keysToKeep = _state.value.tracks.mapNotNull { it.artworkKey }.toSet() +
            metadataOverrides.values.mapNotNull { it.artworkKey }
        withContext(Dispatchers.IO) {
            artwork.prune(keysToKeep)
        }
    }

    private suspend fun buildCloudTracks(
        root: LibraryRoot,
        items: List<CloudTrackMetadata>,
        existingByRel: Map<String, Track>,
        nowMs: Long,
    ): List<Track> {
        val artKeysByUrl = HashMap<String, String?>()
        val seenRel = HashSet<String>()
        val result = ArrayList<Track>(items.size)
        val sourceSlug = root.kind.name.lowercase()
        val artHeaders = buildMap {
            if (root.kind == RootKind.CLOUD_BILIBILI) {
                put("Referer", "https://www.bilibili.com/")
                put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            }
        }

        for ((index, meta) in items.withIndex()) {
            val relPath = "$sourceSlug/${meta.remoteTrackId}"
            if (root.isExcluded(relPath)) continue
            if (!seenRel.add(relPath)) continue
            val old = existingByRel[relPath]
            val artUrl = meta.artworkUrl

            val artworkKey = when {
                old != null &&
                    old.remoteArtworkUrl == artUrl &&
                    old.artworkKey != null &&
                    artwork.file(old.artworkKey) != null -> old.artworkKey
                !artUrl.isNullOrBlank() -> artKeysByUrl.getOrPut(artUrl) {
                    runCatching {
                        val resp = cloudHttpClient.get(artUrl, headers = artHeaders)
                        if (resp.statusCode in 200..299 && resp.bodyBytes.isNotEmpty()) {
                            artwork.store(resp.bodyBytes)
                        } else {
                            null
                        }
                    }.getOrNull()
                }
                else -> old?.artworkKey
            }

            val cloudSource = if (root.kind == RootKind.CLOUD_YOUTUBE) {
                CloudSource.YOUTUBE
            } else {
                CloudSource.BILIBILI
            }

            result.add(
                applyMetadataOverride(
                    Track(
                        id = Track.makeId(root.id, relPath),
                        rootId = root.id,
                        relativePath = relPath,
                        title = meta.title.ifBlank { meta.remoteTrackId },
                        artist = meta.artist.ifBlank { root.name },
                        album = meta.album.ifBlank { root.name },
                        albumArtist = meta.artist.ifBlank { root.name },
                        trackNumber = meta.trackNumber ?: (index + 1),
                        discNumber = null,
                        durationSec = meta.durationSec,
                        fileSize = 0L,
                        modifiedEpochMs = 0L,
                        hasEmbeddedLyrics = false,
                        dateAddedEpochMs = old?.dateAddedEpochMs ?: nowMs,
                        artworkKey = artworkKey,
                        cloudSource = cloudSource,
                        remoteTrackId = meta.remoteTrackId,
                        remoteArtworkUrl = artUrl,
                    )
                )
            )
        }
        return result
    }

    private fun scanRoot(root: LibraryRoot): List<ScannedFile>? {
        if (root.kind == RootKind.APP_FOLDER) {
            return FolderScanner.scan(appFolderDir)
        }
        val uriStr = root.treeUri ?: return null
        fileFromUriString(uriStr)?.let { dir ->
            return FolderScanner.scan(dir)
        }
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
        val ctx = context ?: return null
        return FolderScanner.scanTree(ctx, uri)
    }

    private fun resolveFileUri(root: LibraryRoot, relativePath: String): Uri? {
        val local = resolveLocalFile(root, relativePath) ?: return null
        return runCatching { Uri.fromFile(local) }.getOrNull()
    }

    private fun resolveLocalFile(root: LibraryRoot, relativePath: String): File? {
        if (root.kind == RootKind.APP_FOLDER) {
            return FolderScanner.fileFor(appFolderDir, relativePath)
        }
        val baseDir = fileFromUriString(root.treeUri) ?: return null
        return FolderScanner.fileFor(baseDir, relativePath)
    }

    fun localFileFor(track: Track): File? {
        if (track.isCloud) return null
        val current = _state.value
        if (track.rootId !in current.availableRootIds) return null
        val root = current.roots.firstOrNull { it.id == track.rootId } ?: return null
        if (root.kind.isCloud) return null
        return resolveLocalFile(root, track.relativePath)
    }

    private fun publish(list: List<Track>) {
        val current = _state.value
        val activeRoots = current.roots.associateBy { it.id }
        val filtered = list.filter { track ->
            val r = activeRoots[track.rootId] ?: return@filter false
            !r.isExcluded(track.relativePath)
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        val (albums, artists, idMap) = buildIndices(filtered)
        byId = idMap
        _state.value = _state.value.copy(
            tracks = filtered,
            albums = albums,
            artists = artists,
        )
        saveLibrary()
    }

    private fun makeTrack(
        root: LibraryRoot,
        file: ScannedFile,
        metadata: TrackMetadata,
        artworkKey: String?,
        dateAddedEpochMs: Long,
    ): Track {
        val fileName = file.relativePath.substringAfterLast('/')
        val stem = fileName.substringBeforeLast('.', fileName)
        val parentFolder = file.relativePath.substringBeforeLast('/', "")
            .substringAfterLast('/')
        val artist = metadata.artist ?: "Unknown Artist"
        val album = metadata.album ?: if (parentFolder.isEmpty()) "Unknown Album" else parentFolder

        return Track(
            id = Track.makeId(root.id, file.relativePath),
            rootId = root.id,
            relativePath = file.relativePath,
            title = metadata.title ?: stem,
            artist = artist,
            album = album,
            albumArtist = metadata.albumArtist ?: artist,
            genre = metadata.genre.orEmpty(),
            trackNumber = metadata.trackNumber,
            discNumber = metadata.discNumber,
            durationSec = metadata.durationSec,
            fileSize = file.fileSize,
            modifiedEpochMs = file.modifiedEpochMs,
            hasEmbeddedLyrics = metadata.lyrics != null,
            dateAddedEpochMs = dateAddedEpochMs,
            artworkKey = artworkKey,
        )
    }

    private fun applyMetadataOverride(track: Track): Track {
        val ov = metadataOverrides[track.id] ?: return track
        val nextArtist = ov.artist ?: track.artist
        val nextAlbumArtist = if (ov.artist != null && track.albumArtist == track.artist) {
            ov.artist
        } else {
            track.albumArtist
        }
        return track.copy(
            title = ov.title ?: track.title,
            artist = nextArtist,
            album = ov.album ?: track.album,
            albumArtist = nextAlbumArtist,
            genre = ov.genre ?: track.genre,
            artworkKey = ov.artworkKey ?: track.artworkKey,
        )
    }

    fun updateTrackMetadata(
        trackId: String,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        genre: String? = null,
        artworkKey: String? = null,
    ): Track? {
        val current = _state.value
        val existing = current.tracks.firstOrNull { it.id == trackId } ?: return null
        val prevOverride = metadataOverrides[trackId]
        val nextOverride = TrackMetadataOverride(
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: prevOverride?.title,
            artist = artist?.trim()?.takeIf { it.isNotEmpty() } ?: prevOverride?.artist,
            album = album?.trim()?.takeIf { it.isNotEmpty() } ?: prevOverride?.album,
            genre = genre?.trim() ?: prevOverride?.genre,
            artworkKey = artworkKey ?: prevOverride?.artworkKey,
        )
        metadataOverrides[trackId] = nextOverride
        val updatedTrack = applyMetadataOverride(existing)
        val nextTracks = current.tracks.map { if (it.id == trackId) updatedTrack else it }
        val (albums, artists, idMap) = buildIndices(nextTracks)
        byId = idMap
        _state.value = current.copy(
            tracks = nextTracks,
            albums = albums,
            artists = artists,
        )
        saveLibrary()
        onTrackUpdated?.invoke(updatedTrack)
        return updatedTrack
    }

    private fun forget(ids: Set<String>) {
        if (ids.isEmpty()) return
        val current = _state.value
        val nextPlaylists = current.playlists.map { pl ->
            pl.copy(trackIds = pl.trackIds.filter { it !in ids })
        }
        val nextFavourites = current.favourites - ids
        _state.value = current.copy(
            playlists = nextPlaylists,
            favourites = nextFavourites,
        )
        savePlaylists()
        onTracksRemoved?.invoke(ids)
    }

    private fun buildIndices(trackList: List<Track>): Triple<List<Album>, List<String>, Map<String, Track>> {
        val idMap = LinkedHashMap<String, Track>(trackList.size)
        val groups = LinkedHashMap<String, MutableList<Track>>()
        val artistSet = LinkedHashSet<String>()

        for (t in trackList) {
            idMap.putIfAbsent(t.id, t)
            val key = "${t.albumArtist}\u001F${t.album}"
            groups.getOrPut(key) { mutableListOf() }.add(t)
            artistSet.add(t.artist)
        }

        val albums = groups.map { (key, members) ->
            val sorted = members.sortedWith(
                compareBy<Track> { it.discNumber ?: 1 }
                    .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
            Album(
                id = key,
                title = sorted[0].album,
                artist = sorted[0].albumArtist,
                trackIds = sorted.map { it.id },
                artworkKey = sorted.firstNotNullOfOrNull { it.artworkKey },
            )
        }.sortedWith(
            compareBy<Album, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.artist }
        )

        val artists = artistSet.sortedWith(String.CASE_INSENSITIVE_ORDER)
        return Triple(albums, artists, idMap)
    }

    fun track(id: String): Track? = byId[id]

    fun tracks(ids: List<String>): List<Track> = ids.mapNotNull { byId[it] }

    fun rootFor(track: Track): LibraryRoot? =
        _state.value.roots.firstOrNull { it.id == track.rootId }

    fun uriFor(track: Track): Uri? {
        if (track.isCloud) return null
        val current = _state.value
        if (track.rootId !in current.availableRootIds) return null
        val root = current.roots.firstOrNull { it.id == track.rootId } ?: return null
        if (root.kind.isCloud) return null
        if (root.kind == RootKind.APP_FOLDER) {
            return Uri.fromFile(FolderScanner.fileFor(appFolderDir, track.relativePath))
        }
        val treeUriStr = root.treeUri ?: return null
        val treeUri = runCatching { Uri.parse(treeUriStr) }.getOrNull() ?: return null
        if (treeUri.scheme == "file" && treeUri.path != null) {
            return Uri.fromFile(FolderScanner.fileFor(File(treeUri.path!!), track.relativePath))
        }
        val cached = trackUris[track.id] ?: return null
        return runCatching { Uri.parse(cached) }.getOrNull()
    }

    fun isAvailable(root: LibraryRoot): Boolean = root.id in _state.value.availableRootIds

    fun isAvailable(track: Track): Boolean = track.rootId in _state.value.availableRootIds

    /** Returns true if [uri] matches or resides inside an already-indexed local root. */
    fun isUriInsideIndexedRoot(uri: Uri): Boolean {
        if (uri.scheme == "file") {
            val candidate = uri.path?.let { runCatching { File(it).canonicalPath }.getOrNull() }
                ?: return false
            val appPath = runCatching { appFolderDir.canonicalPath }.getOrNull()
            if (appPath != null && (candidate == appPath || candidate.startsWith("$appPath${File.separator}"))) {
                return true
            }
            return _state.value.roots.any { root ->
                if (root.kind != RootKind.PICKED) return@any false
                val rUri = root.treeUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return@any false
                if (rUri.scheme != "file" || rUri.path == null) return@any false
                val rootPath = runCatching { File(rUri.path!!).canonicalPath }.getOrNull() ?: return@any false
                candidate == rootPath || candidate.startsWith("$rootPath${File.separator}")
            }
        }
        val candidateKey = canonicalUriKey(uri)
        val candidateDocId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        return _state.value.roots.any { root ->
            if (root.kind != RootKind.PICKED) return@any false
            val rStr = root.treeUri ?: return@any false
            val rUri = runCatching { Uri.parse(rStr) }.getOrNull() ?: return@any false
            if (canonicalUriKey(rUri) == candidateKey) return@any true
            if (candidateDocId != null && rUri.scheme == "content" && rUri.authority == uri.authority) {
                val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(rUri) }.getOrNull()
                if (rootDocId != null && (candidateDocId == rootDocId || candidateDocId.startsWith("$rootDocId/"))) {
                    return@any true
                }
            }
            false
        }
    }

    suspend fun embeddedLyrics(track: Track): String? {
        if (!track.hasEmbeddedLyrics) return null
        val uri = uriFor(track) ?: return null
        return reader.read(uri, track.fileName).lyrics
    }

    suspend fun readSiblingLyrics(track: Track): ByteArray? = withContext(Dispatchers.IO) {
        if (track.isCloud) return@withContext null
        val current = _state.value
        if (track.rootId !in current.availableRootIds) return@withContext null
        val root = current.roots.firstOrNull { it.id == track.rootId } ?: return@withContext null
        if (root.kind.isCloud) return@withContext null

        val stem = track.fileName.substringBeforeLast('.', track.fileName)
        val folderPath = track.folderPath

        if (root.kind == RootKind.APP_FOLDER) {
            val dir = if (folderPath.isEmpty()) appFolderDir else File(appFolderDir, folderPath)
            return@withContext readLocalSibling(dir, stem)
        }

        val treeUriStr = root.treeUri ?: return@withContext null
        val treeUri = runCatching { Uri.parse(treeUriStr) }.getOrNull() ?: return@withContext null
        if (treeUri.scheme == "file" && treeUri.path != null) {
            val base = File(treeUri.path!!)
            val dir = if (folderPath.isEmpty()) base else File(base, folderPath)
            return@withContext readLocalSibling(dir, stem)
        }

        val ctx = context ?: return@withContext null
        readSafSibling(ctx, treeUri, folderPath, stem)
    }

    private fun readLocalSibling(dir: File, stem: String): ByteArray? {
        for (ext in listOf("lrc", "txt")) {
            val candidate = File(dir, "$stem.$ext")
            if (candidate.exists() && candidate.isFile) {
                return runCatching { candidate.readBytes() }.getOrNull()
            }
        }
        return null
    }

    private fun readSafSibling(
        ctx: Context,
        treeUri: Uri,
        folderPath: String,
        stem: String,
    ): ByteArray? {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        var currentDocId = rootDocId

        if (folderPath.isNotEmpty()) {
            for (segment in folderPath.split('/')) {
                if (segment.isEmpty()) continue
                currentDocId = findChildDocId(ctx, treeUri, currentDocId, segment, wantDir = true) ?: return null
            }
        }

        for (ext in listOf("lrc", "txt")) {
            val targetName = "$stem.$ext"
            val docId = findChildDocId(ctx, treeUri, currentDocId, targetName, wantDir = false)
            if (docId != null) {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                val bytes = runCatching {
                    ctx.contentResolver.openInputStream(docUri)?.use { it.readBytes() }
                }.getOrNull()
                if (bytes != null) return bytes
            }
        }
        return null
    }

    private fun findChildDocId(
        ctx: Context,
        treeUri: Uri,
        parentDocId: String,
        targetName: String,
        wantDir: Boolean,
    ): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val cursor = runCatching {
            ctx.contentResolver.query(childrenUri, projection, null, null, null)
        }.getOrNull() ?: return null

        cursor.use { c ->
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                val id = if (idIdx >= 0) c.getString(idIdx) else null
                val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else null
                if (id == null || name == null) continue
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                if (isDir == wantDir && name.equals(targetName, ignoreCase = true)) {
                    return id
                }
            }
        }
        return null
    }

    fun albumsByArtist(artist: String): List<Album> =
        _state.value.albums.filter { album ->
            album.artist == artist || album.trackIds.any { byId[it]?.artist == artist }
        }

    fun tracksByArtist(artist: String): List<Track> =
        _state.value.tracks.filter { it.artist == artist }

    fun folderKey(rootId: String, path: String): String =
        "$rootId\u001F${LibraryRoot.normalizeFolderPath(path)}"

    fun folderSortOrder(rootId: String, path: String): TrackSortOrder {
        val key = folderKey(rootId, path)
        return folderSortOrders[key]
            ?: if (folderTrackOrders.containsKey(key)) TrackSortOrder.CUSTOM else TrackSortOrder.NAME_ASC
    }

    fun setFolderSortOrder(rootId: String, path: String, sortOrder: TrackSortOrder) {
        val key = folderKey(rootId, path)
        folderSortOrders[key] = sortOrder
        _state.value = _state.value.copy(folderSortOrders = folderSortOrders.toMap())
        saveLibrary()
    }

    fun setFolderTrackOrder(rootId: String, path: String, orderedTrackIds: List<String>) {
        val key = folderKey(rootId, path)
        folderTrackOrders[key] = orderedTrackIds
        folderSortOrders[key] = TrackSortOrder.CUSTOM
        _state.value = _state.value.copy(
            folderSortOrders = folderSortOrders.toMap(),
            folderTrackOrders = folderTrackOrders.toMap(),
        )
        saveLibrary()
    }

    fun moveFolderTrack(
        rootId: String,
        path: String,
        fromIndex: Int,
        toIndex: Int,
        currentOrderedIds: List<String>? = null,
    ) {
        val baseIds = currentOrderedIds ?: folderContents(rootId, path).second.map { it.id }
        if (fromIndex !in baseIds.indices || toIndex !in baseIds.indices || fromIndex == toIndex) return
        val updated = baseIds.toMutableList()
        val item = updated.removeAt(fromIndex)
        updated.add(toIndex, item)
        setFolderTrackOrder(rootId, path, updated)
    }

    private fun orderDirectTracks(
        rootId: String,
        path: String,
        direct: List<Track>,
        playCounts: (String) -> Int,
    ): List<Track> {
        val key = folderKey(rootId, path)
        val explicitSort = folderSortOrders[key]
        val customIds = folderTrackOrders[key]
        return when {
            explicitSort == TrackSortOrder.CUSTOM || (explicitSort == null && customIds != null) -> {
                if (customIds.isNullOrEmpty()) {
                    direct.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.fileName })
                } else {
                    val rank = HashMap<String, Int>(customIds.size)
                    customIds.forEachIndexed { idx, id -> rank.putIfAbsent(id, idx) }
                    val inCustom = direct.filter { it.id in rank }.sortedBy { rank[it.id] }
                    val remaining = direct.filter { it.id !in rank }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.fileName })
                    inCustom + remaining
                }
            }
            explicitSort != null -> sortTracks(direct, explicitSort, playCounts)
            else -> direct.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.fileName })
        }
    }

    /**
     * Direct children of a folder: sorted subfolder names, then ordered direct tracks.
     */
    fun folderContents(
        rootId: String,
        path: String,
        playCounts: (String) -> Int = { 0 },
    ): Pair<List<String>, List<Track>> {
        val normPath = LibraryRoot.normalizeFolderPath(path)
        val prefix = if (normPath.isEmpty()) "" else "$normPath/"
        val folders = linkedSetOf<String>()
        val direct = mutableListOf<Track>()
        for (track in _state.value.tracks) {
            if (track.rootId != rootId || !track.relativePath.startsWith(prefix)) continue
            val rest = track.relativePath.substring(prefix.length)
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                folders.add(rest.substring(0, slash))
            } else {
                direct.add(track)
            }
        }
        return folders.sortedWith(String.CASE_INSENSITIVE_ORDER) to
            orderDirectTracks(rootId, normPath, direct, playCounts)
    }

    /** Every song in a folder and its subfolders, respecting persisted sort/custom order when set. */
    fun tracksUnder(
        rootId: String,
        path: String,
        playCounts: (String) -> Int = { 0 },
    ): List<Track> {
        val normPath = LibraryRoot.normalizeFolderPath(path)
        val prefix = if (normPath.isEmpty()) "" else "$normPath/"
        val matching = _state.value.tracks.filter {
            it.rootId == rootId && it.relativePath.startsWith(prefix)
        }
        val key = folderKey(rootId, normPath)
        val explicitSort = folderSortOrders[key]
        val hasCustom = explicitSort == TrackSortOrder.CUSTOM ||
            (explicitSort == null && folderTrackOrders.containsKey(key))

        if (hasCustom) {
            val (subfolders, direct) = folderContents(rootId, normPath, playCounts)
            val subTracks = subfolders.flatMap { sub ->
                val subPath = if (normPath.isEmpty()) sub else "$normPath/$sub"
                tracksUnder(rootId, subPath, playCounts)
            }
            return direct + subTracks
        }
        if (explicitSort != null) {
            return sortTracks(matching, explicitSort, playCounts)
        }
        return matching.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.relativePath })
    }

    fun search(query: String, field: SearchField = SearchField.KEYWORD): List<Track> {
        val needle = foldForSearch(query.trim())
        if (needle.isEmpty()) return emptyList()
        return _state.value.tracks.filter { track ->
            when (field) {
                SearchField.KEYWORD ->
                    foldForSearch(track.title).contains(needle) ||
                        foldForSearch(track.artist).contains(needle) ||
                        foldForSearch(track.album).contains(needle) ||
                        foldForSearch(track.genre).contains(needle)
                SearchField.ARTIST -> foldForSearch(track.artist).contains(needle)
                SearchField.ALBUM -> foldForSearch(track.album).contains(needle)
                SearchField.GENRE -> foldForSearch(track.genre).contains(needle)
            }
        }
    }

    fun isFavourite(id: String): Boolean = id in _state.value.favourites

    fun toggleFavourite(id: String) {
        val current = _state.value
        val nextFavs = if (id in current.favourites) {
            current.favourites - id
        } else {
            current.favourites + id
        }
        _state.value = current.copy(favourites = nextFavs)
        savePlaylists()
    }

    fun createPlaylist(name: String): String {
        val playlist = Playlist(id = UUID.randomUUID().toString(), name = name, trackIds = emptyList())
        val current = _state.value
        _state.value = current.copy(playlists = current.playlists + playlist)
        savePlaylists()
        return playlist.id
    }

    private inline fun updatePlaylist(id: String, block: (Playlist) -> Playlist) {
        val current = _state.value
        val index = current.playlists.indexOfFirst { it.id == id }
        if (index < 0) return
        val next = current.playlists.toMutableList()
        next[index] = block(next[index])
        _state.value = current.copy(playlists = next)
        savePlaylists()
    }

    fun renamePlaylist(id: String, name: String) {
        updatePlaylist(id) { it.copy(name = name) }
    }

    fun deletePlaylist(id: String) {
        val current = _state.value
        _state.value = current.copy(playlists = current.playlists.filter { it.id != id })
        savePlaylists()
    }

    fun addToPlaylist(trackIds: List<String>, playlistId: String) {
        if (trackIds.isEmpty()) return
        updatePlaylist(playlistId) { it.copy(trackIds = it.trackIds + trackIds) }
    }

    fun removeFromPlaylist(playlistId: String, index: Int) {
        updatePlaylist(playlistId) { pl ->
            if (index !in pl.trackIds.indices) pl
            else pl.copy(trackIds = pl.trackIds.toMutableList().apply { removeAt(index) })
        }
    }

    fun setFavouritesSortOrder(sortOrder: TrackSortOrder) {
        _state.value = _state.value.copy(favouritesSortOrder = sortOrder)
        savePlaylists()
    }

    fun setPlaylistSortOrder(
        playlistId: String,
        sortOrder: TrackSortOrder,
        playCounts: (String) -> Int = { 0 },
    ) {
        updatePlaylist(playlistId) { pl ->
            if (sortOrder == TrackSortOrder.CUSTOM) {
                pl.copy(sortOrder = TrackSortOrder.CUSTOM)
            } else {
                val sortedIds = sortTracks(tracks(pl.trackIds), sortOrder, playCounts).map { it.id }
                pl.copy(trackIds = sortedIds, sortOrder = sortOrder)
            }
        }
    }

    fun reorderPlaylistTracks(playlistId: String, orderedTrackIds: List<String>) {
        updatePlaylist(playlistId) { pl ->
            pl.copy(trackIds = orderedTrackIds, sortOrder = TrackSortOrder.CUSTOM)
        }
    }

    fun movePlaylistTrack(playlistId: String, fromIndex: Int, toIndex: Int) {
        updatePlaylist(playlistId) { pl ->
            if (fromIndex !in pl.trackIds.indices || toIndex !in pl.trackIds.indices || fromIndex == toIndex) {
                pl
            } else {
                val updated = pl.trackIds.toMutableList()
                val item = updated.removeAt(fromIndex)
                updated.add(toIndex, item)
                pl.copy(trackIds = updated, sortOrder = TrackSortOrder.CUSTOM)
            }
        }
    }

    private data class PendingScan(
        val root: LibraryRoot,
        val file: ScannedFile,
        val old: Track?,
    )

    companion object {
        private const val READER_VERSION = 3
        const val APP_FOLDER_NAME = "Musico Folder"
        private val combiningMarks = "\\p{InCombiningDiacriticalMarks}+".toRegex()

        private fun foldForSearch(input: String): String {
            val nfd = Normalizer.normalize(input, Normalizer.Form.NFD)
            return combiningMarks.replace(nfd, "").lowercase()
        }

        private fun makeAppFolderVisible(folder: File) {
            runCatching {
                if (!folder.exists()) folder.mkdirs()
                val entries = folder.list() ?: return
                if (entries.isEmpty()) {
                    val note = File(folder, "Put your music in this folder.txt")
                    note.writeText(
                        "Copy music files or folders here, then open Musico and pull down to rescan on the Folders tab.\n",
                        Charsets.UTF_8,
                    )
                }
            }
        }
    }
}
