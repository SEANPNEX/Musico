package com.musico.store

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.musico.cloud.BilibiliClient
import com.musico.cloud.CloudErrorCode
import com.musico.cloud.CloudExporter
import com.musico.cloud.CloudFolderManifest
import com.musico.cloud.CloudHttpClient
import com.musico.cloud.CloudPlaylistResult
import com.musico.cloud.CloudSource
import com.musico.cloud.CloudSourceException
import com.musico.cloud.CloudTrackMetadata
import com.musico.cloud.GoogleDriveClient
import com.musico.cloud.UrlConnectionCloudHttpClient
import com.musico.cloud.YouTubeClient
import com.musico.cloud.extractCloudSourceInput
import com.musico.core.ArtworkCache
import com.musico.core.CloudThumbnailCache
import com.musico.core.FolderScanner
import com.musico.core.LocalCloudDiscovery
import com.musico.core.LrcParser
import com.musico.core.LyricsNameMatcher
import com.musico.core.MetadataReading
import com.musico.core.Persistence
import com.musico.core.ScannedFile
import com.musico.core.TrackMetadata
import com.musico.extensions.CloudProviderAvailability
import com.musico.extensions.CloudProviderIds
import com.musico.extensions.CloudProviderRegistry
import com.musico.extensions.CloudSourceProvider
import com.musico.model.Album
import com.musico.model.CloudTrackingInfo
import com.musico.model.LibraryRoot
import com.musico.model.Playlist
import com.musico.model.RootKind
import com.musico.model.SearchField
import com.musico.model.Track
import com.musico.model.TrackMetadataOverride
import com.musico.model.TrackSortOrder
import com.musico.model.sortTracks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.text.Normalizer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

sealed class LibraryException(message: String) : Exception(message) {
    data object DuplicateRoot : LibraryException("That folder is already in your library.")
    data object NotAFolder : LibraryException("That location could not be opened as a folder.")
    data object UnsupportedCloudUrl : LibraryException("Enter a valid YouTube, Bilibili, or Google Drive URL.")
    data class ExtensionUnavailable(val providerId: String) : LibraryException("Extension unavailable: $providerId")
}

data class CloudSyncProgress(
    val rootId: String,
    val completedFolders: Int,
    val totalFolders: Int,
    val tracksFound: Int,
)

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
    val cloudSyncProgress: Map<String, CloudSyncProgress> = emptyMap(),
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
    private val googleDriveClient: GoogleDriveClient = GoogleDriveClient(cloudHttpClient),
    val providerRegistry: CloudProviderRegistry = CloudProviderRegistry(
        youTubeClient = youTubeClient,
        bilibiliClient = bilibiliClient,
        googleDriveClient = googleDriveClient,
    ),
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
        val ephemeralTracks: Map<String, Track> = emptyMap(),
    )

    @Serializable
    private data class PlaylistFile(
        val playlists: List<Playlist> = emptyList(),
        val favourites: Set<String> = emptySet(),
        val favouritesSortOrder: TrackSortOrder = TrackSortOrder.NAME_ASC,
    )

    var onTracksRemoved: ((Set<String>) -> Unit)? = null
    var onTracksRemapped: ((Map<String, String>) -> Unit)? = null
    var onFolderAdded: ((folderName: String, trackIds: List<String>) -> Unit)? = null
    var onTrackUpdated: ((Track) -> Unit)? = null
    var referencedQueueTrackIdsProvider: (() -> Set<String>)? = null

    private val libraryFile = File(directory, "library.json")
    private val playlistsFile = File(directory, "playlists.json")
    private val batchSize = scanBatchSize.coerceAtLeast(1)

    private var rereadAll = false
    private var byId: Map<String, Track> = emptyMap()
    private val ephemeralTracks = LinkedHashMap<String, Track>()
    private val persistedEphemeralIds = HashSet<String>()
    private val trackUris = mutableMapOf<String, String>()
    private val metadataOverrides = mutableMapOf<String, TrackMetadataOverride>()
    private val folderSortOrders = mutableMapOf<String, TrackSortOrder>()
    private val folderTrackOrders = mutableMapOf<String, List<String>>()

    private var rescanRequested = false
    private var scanningJob: Job? = null
    private val stateLock = Any()
    private val cloudSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeCloudSyncJobs = ConcurrentHashMap<String, Job>()
    private val cloudPriorityPaths = ConcurrentHashMap<String, String>()
    private val knownCloudSubfolders = ConcurrentHashMap<String, Set<String>>()

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        val loadedLib = Persistence.load<LibraryFile>(libraryFile)
        rereadAll = loadedLib != null && loadedLib.readerVersion != READER_VERSION

        val savedAppRoot = loadedLib?.roots?.firstOrNull { it.kind == RootKind.APP_FOLDER }
        val userRoots = loadedLib?.roots
            ?.filter { it.kind != RootKind.APP_FOLDER }
            .orEmpty()
            .map { migrateLegacySubfolderTracking(it) }
        val appRoot = migrateLegacySubfolderTracking(
            LibraryRoot(
                id = LibraryRoot.APP_FOLDER_ID,
                name = APP_FOLDER_NAME,
                kind = RootKind.APP_FOLDER,
                treeUri = null,
                sourceUrl = savedAppRoot?.sourceUrl,
                remoteId = savedAppRoot?.remoteId,
                cookie = savedAppRoot?.cookie,
                lastSyncedEpochMs = savedAppRoot?.lastSyncedEpochMs,
                excludedPaths = savedAppRoot?.excludedPaths.orEmpty(),
                linkedCloudKind = savedAppRoot?.linkedCloudKind,
                linkedProviderId = savedAppRoot?.linkedProviderId,
                streamQualityId = savedAppRoot?.streamQualityId,
                downloadQualityId = savedAppRoot?.downloadQualityId,
                trackedSubfolderName = savedAppRoot?.trackedSubfolderName,
                trackedSubfolders = savedAppRoot?.trackedSubfolders.orEmpty(),
            )
        )
        val initialRoots = listOf(appRoot) + userRoots
        makeAppFolderVisible(appFolderDir)

        if (loadedLib != null) {
            trackUris.putAll(loadedLib.trackUris)
            metadataOverrides.putAll(loadedLib.metadataOverrides)
            folderSortOrders.putAll(loadedLib.folderSortOrders)
            folderTrackOrders.putAll(loadedLib.folderTrackOrders)
            for ((eid, etrack) in loadedLib.ephemeralTracks) {
                ephemeralTracks[eid] = etrack.copy(isEphemeral = true)
                persistedEphemeralIds.add(eid)
            }
            migrateCloudFolderKeys(initialRoots)
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
            val pid = root.resolvedProviderId ?: return false
            return providerRegistry.availabilityFor(pid) == CloudProviderAvailability.AVAILABLE
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

    fun refreshAvailability() {
        synchronized(stateLock) {
            val cur = _state.value
            _state.value = cur.copy(availableRootIds = computeAvailableRootIds(cur.roots))
        }
    }

    fun cloudProviderAvailability(root: LibraryRoot): CloudProviderAvailability {
        if (!root.kind.isCloud) return CloudProviderAvailability.AVAILABLE
        return providerRegistry.availabilityFor(root.resolvedProviderId)
    }

    fun compatibleProvidersForRoot(root: LibraryRoot): List<CloudSourceProvider> {
        val url = root.sourceUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: root.remoteId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return emptyList()
        return providerRegistry.compatibleProvidersForUrl(
            rawInput = url,
            excludingProviderId = root.resolvedProviderId,
        )
    }

    fun bestCompatibleProviderForRoot(root: LibraryRoot): CloudSourceProvider? =
        compatibleProvidersForRoot(root).firstOrNull()

    fun supportsTreeCrawling(root: LibraryRoot): Boolean {
        if (!root.kind.isCloud) return false
        return providerRegistry.providerForRoot(root)?.supportsTree == true
    }

    private fun saveLibrary() {
        val snapshot = synchronized(stateLock) {
            val current = _state.value
            val referenced = referencedQueueTrackIdsProvider?.invoke()
            val persistedEphemeral = if (referenced != null) {
                ephemeralTracks.filterKeys { it in referenced }
            } else {
                ephemeralTracks.toMap()
            }
            persistedEphemeralIds.clear()
            persistedEphemeralIds.addAll(persistedEphemeral.keys)
            LibraryFile(
                roots = current.roots,
                tracks = current.tracks,
                readerVersion = if (rereadAll) null else READER_VERSION,
                trackUris = trackUris.toMap(),
                metadataOverrides = metadataOverrides.toMap(),
                folderSortOrders = folderSortOrders.toMap(),
                folderTrackOrders = folderTrackOrders.toMap(),
                ephemeralTracks = persistedEphemeral,
            )
        }
        runCatching {
            Persistence.save(snapshot, libraryFile)
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

    private fun migrateLegacySubfolderTracking(root: LibraryRoot): LibraryRoot {
        val sub = root.trackedSubfolderName?.let { LibraryRoot.normalizeFolderPath(it) }?.takeIf { it.isNotEmpty() }
            ?: return root
        val url = root.sourceUrl?.takeIf { it.isNotBlank() }
        val kind = root.effectiveCloudKind
        val pid = root.effectiveProviderId
        val nextSubs = if (url != null && kind != null && !root.trackedSubfolders.containsKey(sub)) {
            root.trackedSubfolders + (
                sub to CloudTrackingInfo(
                    sourceUrl = url,
                    remoteId = root.remoteId,
                    cloudKind = kind,
                    providerId = if (kind == RootKind.CLOUD_EXTENSION) pid else root.linkedProviderId,
                    cookie = root.cookie,
                    lastSyncedEpochMs = root.lastSyncedEpochMs,
                    streamQualityId = root.streamQualityId,
                    downloadQualityId = root.downloadQualityId,
                )
                )
        } else {
            root.trackedSubfolders
        }
        return root.copy(
            sourceUrl = null,
            remoteId = null,
            cookie = null,
            lastSyncedEpochMs = null,
            linkedCloudKind = null,
            linkedProviderId = null,
            streamQualityId = null,
            downloadQualityId = null,
            trackedSubfolderName = null,
            trackedSubfolders = nextSubs,
        )
    }

    private fun joinFolderPaths(parent: String, child: String): String {
        val p = LibraryRoot.normalizeFolderPath(parent)
        val c = LibraryRoot.normalizeFolderPath(child)
        return when {
            p.isEmpty() -> c
            c.isEmpty() -> p
            else -> "$p/$c"
        }
    }

    private fun unexcludePath(excludedPaths: List<String>, relPath: String): List<String> {
        val norm = LibraryRoot.normalizeFolderPath(relPath)
        if (norm.isEmpty() || excludedPaths.isEmpty()) return excludedPaths
        return excludedPaths.filterNot { raw ->
            val ex = LibraryRoot.normalizeFolderPath(raw)
            ex == norm || norm.startsWith("$ex/") || ex.startsWith("$norm/")
        }
    }

    private fun fileCanonicalForRoot(root: LibraryRoot): String? {
        if (root.kind == RootKind.APP_FOLDER) {
            return runCatching { appFolderDir.canonicalPath }.getOrElse { appFolderDir.absolutePath }
        }
        if (root.kind != RootKind.PICKED) return null
        val file = fileFromUriString(root.treeUri) ?: return null
        return runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
    }

    private fun safRelativePath(parentDocId: String, childDocId: String): String? {
        if (parentDocId == childDocId) return ""
        if (parentDocId.endsWith(":") && childDocId.startsWith(parentDocId)) {
            return LibraryRoot.normalizeFolderPath(childDocId.substring(parentDocId.length))
        }
        if (childDocId.startsWith("$parentDocId/")) {
            return LibraryRoot.normalizeFolderPath(childDocId.substring(parentDocId.length + 1))
        }
        return null
    }

    private fun findContainingRoot(
        candidateKey: String,
        treeUriString: String,
        treeUri: Uri?,
        subNorm: String?,
    ): Pair<Int, String>? {
        val baseFile = fileFromUriString(treeUriString) ?: treeUri?.let {
            if (it.scheme == "file" && it.path != null) File(it.path!!) else null
        }
        val targetFile = baseFile?.let { base ->
            if (subNorm != null) File(base, subNorm) else base
        }
        val targetCanonical = targetFile?.let { f ->
            runCatching { f.canonicalPath }.getOrElse { f.absolutePath }
        }
        val candidateDocId = if (treeUri != null && treeUri.scheme == "content") {
            runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        } else {
            null
        }

        val roots = _state.value.roots
        for ((index, root) in roots.withIndex()) {
            if (root.kind.isCloud) continue

            if (targetCanonical != null) {
                val rootCanonical = fileCanonicalForRoot(root)
                if (rootCanonical != null) {
                    if (targetCanonical == rootCanonical) {
                        return index to ""
                    }
                    val prefix = if (rootCanonical.endsWith(File.separator)) rootCanonical else "$rootCanonical${File.separator}"
                    if (targetCanonical.startsWith(prefix)) {
                        val rel = targetCanonical.substring(prefix.length).replace(File.separatorChar, '/')
                        return index to LibraryRoot.normalizeFolderPath(rel)
                    }
                }
            }

            if (root.kind == RootKind.PICKED && treeUri != null && treeUri.scheme == "content" && candidateDocId != null) {
                val rootUri = root.treeUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                if (rootUri != null && rootUri.scheme == "content" && rootUri.authority == treeUri.authority) {
                    val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(rootUri) }.getOrNull()
                    if (rootDocId != null) {
                        val baseRel = safRelativePath(rootDocId, candidateDocId)
                        if (baseRel != null) {
                            return index to joinFolderPaths(baseRel, subNorm.orEmpty())
                        }
                    }
                }
            }

            if (root.kind == RootKind.PICKED && root.treeUri != null && canonicalUriKey(root.treeUri) == candidateKey) {
                return index to subNorm.orEmpty()
            }
        }
        return null
    }

    private fun findContainedChildRoots(
        treeUriString: String,
        treeUri: Uri?,
    ): List<Pair<LibraryRoot, String>> {
        val result = mutableListOf<Pair<LibraryRoot, String>>()
        val parentDir = fileFromUriString(treeUriString) ?: treeUri?.let {
            if (it.scheme == "file" && it.path != null) File(it.path!!) else null
        }

        if (parentDir != null) {
            val parentCanonical = runCatching { parentDir.canonicalPath }.getOrElse { parentDir.absolutePath }
            val prefix = if (parentCanonical.endsWith(File.separator)) parentCanonical else "$parentCanonical${File.separator}"
            for (root in _state.value.roots) {
                if (root.kind != RootKind.PICKED) continue
                val childCanonical = fileCanonicalForRoot(root) ?: continue
                if (childCanonical.startsWith(prefix)) {
                    val rel = LibraryRoot.normalizeFolderPath(
                        childCanonical.substring(prefix.length).replace(File.separatorChar, '/')
                    )
                    if (rel.isNotEmpty()) {
                        result.add(root to rel)
                    }
                }
            }
            return result
        }

        if (treeUri != null && treeUri.scheme == "content") {
            val parentDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return emptyList()
            for (root in _state.value.roots) {
                if (root.kind != RootKind.PICKED) continue
                val childUri = root.treeUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: continue
                if (childUri.scheme != "content" || childUri.authority != treeUri.authority) continue
                val childDocId = runCatching { DocumentsContract.getTreeDocumentId(childUri) }.getOrNull() ?: continue
                val rel = safRelativePath(parentDocId, childDocId)
                if (!rel.isNullOrEmpty()) {
                    result.add(root to rel)
                }
            }
        }
        return result
    }

    private fun migrateContainedChildRoots(
        newRootId: String,
        contained: List<Pair<LibraryRoot, String>>,
        migratedSubfolders: MutableMap<String, CloudTrackingInfo>,
        migratedExcluded: MutableList<String>,
    ) {
        if (contained.isEmpty()) return
        val containedIds = contained.map { it.first.id }.toSet()
        val idMap = HashMap<String, String>()
        val currentState = _state.value

        synchronized(stateLock) {
            for ((childRoot, childRel) in contained) {
                childRoot.trackingFor("")?.let { rootTracking ->
                    migratedSubfolders.putIfAbsent(childRel, rootTracking)
                }
                for ((subKey, info) in childRoot.trackedSubfolders) {
                    val mergedKey = joinFolderPaths(childRel, subKey)
                    if (mergedKey.isNotEmpty()) {
                        migratedSubfolders.putIfAbsent(mergedKey, info)
                    }
                }
                for (ex in childRoot.excludedPaths) {
                    val mergedEx = joinFolderPaths(childRel, ex)
                    if (mergedEx.isNotEmpty()) {
                        migratedExcluded.add(mergedEx)
                    }
                }

                val childTracks = currentState.tracks.filter { it.rootId == childRoot.id }
                for (t in childTracks) {
                    val newRel = joinFolderPaths(childRel, t.relativePath)
                    val newId = Track.makeId(newRootId, newRel)
                    idMap[t.id] = newId
                    metadataOverrides.remove(t.id)?.let { ov -> metadataOverrides[newId] = ov }
                    trackUris.remove(t.id)
                }

                val oldPrefix = "${childRoot.id}\u001F"
                for (key in folderSortOrders.keys.filter { it.startsWith(oldPrefix) }.toList()) {
                    val order = folderSortOrders.remove(key) ?: continue
                    val oldSub = key.substring(oldPrefix.length)
                    val newKey = "$newRootId\u001F${joinFolderPaths(childRel, oldSub)}"
                    folderSortOrders.putIfAbsent(newKey, order)
                }
                for (key in folderTrackOrders.keys.filter { it.startsWith(oldPrefix) }.toList()) {
                    val ids = folderTrackOrders.remove(key) ?: continue
                    val oldSub = key.substring(oldPrefix.length)
                    val newKey = "$newRootId\u001F${joinFolderPaths(childRel, oldSub)}"
                    folderTrackOrders.putIfAbsent(newKey, ids.map { idMap[it] ?: it })
                }

                releasePersistableReadPermission(childRoot.treeUri)
            }

            val nextRoots = currentState.roots.filterNot { it.id in containedIds }
            val nextTracks = currentState.tracks.filterNot { it.rootId in containedIds }
            val nextPlaylists = if (idMap.isEmpty()) currentState.playlists else {
                currentState.playlists.map { pl ->
                    pl.copy(trackIds = pl.trackIds.map { idMap[it] ?: it })
                }
            }
            val nextFavourites = if (idMap.isEmpty()) currentState.favourites else {
                currentState.favourites.mapTo(mutableSetOf()) { idMap[it] ?: it }
            }
            val (albums, artists, newById) = buildIndices(nextTracks)
            byId = newById
            _state.value = currentState.copy(
                roots = nextRoots,
                availableRootIds = currentState.availableRootIds - containedIds,
                tracks = nextTracks,
                albums = albums,
                artists = artists,
                playlists = nextPlaylists,
                favourites = nextFavourites,
                folderSortOrders = folderSortOrders.toMap(),
                folderTrackOrders = folderTrackOrders.toMap(),
            )
        }
        if (idMap.isNotEmpty()) {
            savePlaylists()
        }
    }

    private suspend fun addPickedRootInternal(
        name: String,
        treeUriString: String,
        candidateKey: String,
        treeUri: Uri?,
        cloudSourceRoot: LibraryRoot? = null,
        trackedSubfolderName: String? = null,
    ): Result<Unit> {
        val subNorm = trackedSubfolderName?.let { LibraryRoot.normalizeFolderPath(it) }?.takeIf { it.isNotEmpty() }
        val containing = findContainingRoot(candidateKey, treeUriString, treeUri, subNorm)

        if (containing != null) {
            val (existingIndex, relPath) = containing
            val existing = _state.value.roots[existingIndex]

            if (cloudSourceRoot == null) {
                if (relPath.isNotEmpty() && existing.isExcluded(relPath)) {
                    val updatedRoot = existing.copy(
                        excludedPaths = unexcludePath(existing.excludedPaths, relPath),
                    )
                    synchronized(stateLock) {
                        val updatedRoots = _state.value.roots.toMutableList()
                        updatedRoots[existingIndex] = updatedRoot
                        _state.value = _state.value.copy(roots = updatedRoots)
                    }
                    saveLibrary()
                    rescan()
                    return Result.success(Unit)
                }
                return Result.failure(LibraryException.DuplicateRoot)
            }

            val cloudKind = cloudSourceRoot.effectiveCloudKind
            val cloudProviderId = cloudSourceRoot.effectiveProviderId ?: cloudKind?.builtinProviderId
            val explicitTrackingProviderId = cloudSourceRoot.providerId?.trim()?.takeIf { it.isNotEmpty() }
                ?: cloudSourceRoot.linkedProviderId?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (cloudKind == RootKind.CLOUD_EXTENSION) cloudProviderId else null
            val trackingInfo = cloudSourceRoot.sourceUrl?.takeIf { it.isNotBlank() }?.let { url ->
                if (cloudKind != null) {
                    CloudTrackingInfo(
                        sourceUrl = url,
                        remoteId = cloudSourceRoot.remoteId,
                        cloudKind = cloudKind,
                        providerId = explicitTrackingProviderId,
                        cookie = cloudSourceRoot.cookie,
                        lastSyncedEpochMs = System.currentTimeMillis(),
                        streamQualityId = cloudSourceRoot.streamQualityId,
                        downloadQualityId = cloudSourceRoot.downloadQualityId,
                    )
                } else null
            }

            val updatedRoot = if (relPath.isEmpty()) {
                existing.copy(
                    sourceUrl = cloudSourceRoot.sourceUrl ?: existing.sourceUrl,
                    remoteId = cloudSourceRoot.remoteId ?: existing.remoteId,
                    linkedCloudKind = cloudKind ?: existing.linkedCloudKind,
                    linkedProviderId = cloudProviderId ?: existing.linkedProviderId,
                    cookie = cloudSourceRoot.cookie ?: existing.cookie,
                    lastSyncedEpochMs = System.currentTimeMillis(),
                    streamQualityId = cloudSourceRoot.streamQualityId ?: existing.streamQualityId,
                    downloadQualityId = cloudSourceRoot.downloadQualityId ?: existing.downloadQualityId,
                    trackedSubfolderName = null,
                )
            } else {
                val nextSubs = if (trackingInfo != null) {
                    existing.trackedSubfolders + (relPath to trackingInfo)
                } else {
                    existing.trackedSubfolders
                }
                existing.copy(
                    excludedPaths = unexcludePath(existing.excludedPaths, relPath),
                    trackedSubfolders = nextSubs,
                )
            }

            synchronized(stateLock) {
                val updatedRoots = _state.value.roots.toMutableList()
                updatedRoots[existingIndex] = updatedRoot
                _state.value = _state.value.copy(roots = updatedRoots)
            }
            saveLibrary()
            rescan()
            notifyFolderAddedForPath(updatedRoot, relPath)
            return Result.success(Unit)
        }

        if (treeUri != null) {
            takePersistableReadPermission(treeUri)
        }

        val newRootId = UUID.randomUUID().toString()
        val contained = if (subNorm == null) findContainedChildRoots(treeUriString, treeUri) else emptyList()
        val migratedSubfolders = linkedMapOf<String, CloudTrackingInfo>()
        val migratedExcluded = mutableListOf<String>()
        migrateContainedChildRoots(
            newRootId = newRootId,
            contained = contained,
            migratedSubfolders = migratedSubfolders,
            migratedExcluded = migratedExcluded,
        )

        val localBaseDir = fileFromUriString(treeUriString) ?: treeUri?.let {
            if (it.scheme == "file" && it.path != null) File(it.path!!) else null
        }
        val manifest = if (cloudSourceRoot == null) {
            when {
                localBaseDir != null -> {
                    val target = if (subNorm != null) File(localBaseDir, subNorm) else localBaseDir
                    CloudExporter.loadManifest(target)
                }
                treeUri != null -> resolveManifestForPickedUri(treeUri, subNorm)
                else -> null
            }
        } else {
            null
        }
        val manifestKind = manifest?.cloudKind?.let { raw ->
            runCatching { RootKind.valueOf(raw) }.getOrNull()
        }

        val cloudKind = cloudSourceRoot?.effectiveCloudKind ?: manifestKind
        val cloudProviderId = cloudSourceRoot?.effectiveProviderId
            ?: manifest?.providerId?.trim()?.takeIf { it.isNotEmpty() }
            ?: cloudKind?.builtinProviderId
        val trackingUrl = cloudSourceRoot?.sourceUrl ?: manifest?.sourceURL
        val trackingRemoteId = cloudSourceRoot?.remoteId ?: manifest?.remoteID
        val trackingSyncedMs = if (cloudSourceRoot != null) {
            System.currentTimeMillis()
        } else {
            manifest?.lastSyncedEpochMs?.takeIf { it > 0L }
        }

        val root = if (subNorm != null) {
            if (!trackingUrl.isNullOrBlank() && cloudKind != null) {
                val explicitChildPid = cloudSourceRoot?.providerId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: cloudSourceRoot?.linkedProviderId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: manifest?.providerId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: if (cloudKind == RootKind.CLOUD_EXTENSION) cloudProviderId else null
                migratedSubfolders[subNorm] = CloudTrackingInfo(
                    sourceUrl = trackingUrl,
                    remoteId = trackingRemoteId,
                    cloudKind = cloudKind,
                    providerId = explicitChildPid,
                    cookie = cloudSourceRoot?.cookie,
                    lastSyncedEpochMs = trackingSyncedMs,
                    streamQualityId = cloudSourceRoot?.streamQualityId ?: manifest?.streamQualityId,
                    downloadQualityId = cloudSourceRoot?.downloadQualityId ?: manifest?.downloadQualityId,
                )
            }
            LibraryRoot(
                id = newRootId,
                name = name,
                kind = RootKind.PICKED,
                treeUri = treeUriString,
                excludedPaths = migratedExcluded.distinct(),
                trackedSubfolders = migratedSubfolders,
            )
        } else {
            LibraryRoot(
                id = newRootId,
                name = name,
                kind = RootKind.PICKED,
                treeUri = treeUriString,
                sourceUrl = trackingUrl,
                remoteId = trackingRemoteId,
                cookie = cloudSourceRoot?.cookie,
                lastSyncedEpochMs = trackingSyncedMs,
                excludedPaths = migratedExcluded.distinct(),
                linkedCloudKind = cloudKind,
                linkedProviderId = cloudProviderId,
                streamQualityId = cloudSourceRoot?.streamQualityId ?: manifest?.streamQualityId,
                downloadQualityId = cloudSourceRoot?.downloadQualityId ?: manifest?.downloadQualityId,
                trackedSubfolders = migratedSubfolders,
            )
        }

        synchronized(stateLock) {
            val current = _state.value
            _state.value = current.copy(
                roots = current.roots + root,
                availableRootIds = current.availableRootIds + root.id,
            )
        }
        saveLibrary()
        rescan()
        notifyFolderAddedForPath(root, subNorm.orEmpty())
        return Result.success(Unit)
    }

    private fun notifyFolderAddedForPath(root: LibraryRoot, relPath: String) {
        val norm = LibraryRoot.normalizeFolderPath(relPath)
        val folderName = if (norm.isEmpty()) root.name else norm.substringAfterLast('/')
        val trackIds = tracksUnder(root.id, norm).map { it.id }
        onFolderAdded?.invoke(folderName, trackIds)
    }

    private fun resolveManifestForPickedUri(treeUri: Uri, subfolderName: String?): CloudFolderManifest? {
        if (treeUri.scheme == "file" && treeUri.path != null) {
            val base = File(treeUri.path!!)
            val target = subfolderName?.takeIf { it.isNotBlank() }?.let { File(base, it) } ?: base
            return CloudExporter.loadManifest(target)
        }
        val ctx = context ?: return null
        val rootDoc = runCatching { DocumentFile.fromTreeUri(ctx, treeUri) }.getOrNull() ?: return null
        var targetDoc = rootDoc
        if (!subfolderName.isNullOrBlank()) {
            for (seg in subfolderName.split('/')) {
                if (seg.isEmpty()) continue
                targetDoc = targetDoc.findFile(seg) ?: return null
            }
        }
        return CloudExporter.loadManifest(ctx, targetDoc)
    }

    fun classifyCloudUrl(rawInput: String): Pair<String, RootKind>? {
        val classified = runCatching { providerRegistry.classifyUrl(rawInput) }.getOrNull() ?: return null
        return classified.normalizedUrl to classified.provider.rootKind
    }

    fun cloudTracking(rootId: String, path: String = ""): CloudTrackingInfo? {
        val root = _state.value.roots.firstOrNull { it.id == rootId } ?: return null
        return root.trackingFor(path)
    }

    suspend fun updateCloudTrackingAddress(
        rootId: String,
        path: String = "",
        newSourceUrl: String,
        cookie: String? = null,
        providerIdHint: String? = null,
        streamQualityId: String? = null,
        downloadQualityId: String? = null,
    ): Result<CloudTrackingInfo> {
        val classified = try {
            withContext(Dispatchers.IO) {
                providerRegistry.classifyUrl(newSourceUrl, providerIdHint)
            } ?: return Result.failure(LibraryException.UnsupportedCloudUrl)
        } catch (err: Exception) {
            return Result.failure(err)
        }
        val cleanedUrl = classified.normalizedUrl
        val newProvider = classified.provider
        val newCloudKind = newProvider.rootKind
        val newProviderId = newProvider.providerId
        val cleanCookie = if (newProvider.supportsCookie) {
            cookie?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val validQualityIds = newProvider.availableQualities.mapTo(HashSet()) { it.id }
        val cleanStreamQuality = streamQualityId?.trim()?.takeIf { it.isNotEmpty() && it in validQualityIds }
        val cleanDownloadQuality = downloadQualityId?.trim()?.takeIf { it.isNotEmpty() && it in validQualityIds }
        val normPath = LibraryRoot.normalizeFolderPath(path)

        var updatedRoot: LibraryRoot? = null
        var updatedInfo: CloudTrackingInfo? = null
        var needsCloudResync = false

        synchronized(stateLock) {
            val current = _state.value
            val index = current.roots.indexOfFirst { it.id == rootId }
            if (index < 0) return Result.failure(LibraryException.NotAFolder)
            val root = current.roots[index]
            val prevTracking = root.trackingFor(normPath)
            val preservedRemoteId = if (prevTracking?.sourceUrl == cleanedUrl && prevTracking.resolvedProviderId == newProviderId) {
                prevTracking.remoteId
            } else {
                classified.remoteId
            }
            val info = CloudTrackingInfo(
                sourceUrl = cleanedUrl,
                remoteId = preservedRemoteId,
                cloudKind = newCloudKind,
                providerId = newProviderId,
                cookie = cleanCookie,
                lastSyncedEpochMs = prevTracking?.lastSyncedEpochMs,
                streamQualityId = cleanStreamQuality,
                downloadQualityId = cleanDownloadQuality,
            )

            val nextRoot = when {
                root.kind.isCloud -> {
                    needsCloudResync = root.kind != newCloudKind ||
                        root.resolvedProviderId != newProviderId ||
                        root.sourceUrl != cleanedUrl ||
                        root.cookie != cleanCookie ||
                        root.id !in current.availableRootIds
                    root.copy(
                        kind = newCloudKind,
                        sourceUrl = cleanedUrl,
                        remoteId = preservedRemoteId,
                        providerId = newProviderId,
                        cookie = cleanCookie,
                        streamQualityId = cleanStreamQuality,
                        downloadQualityId = cleanDownloadQuality,
                    )
                }
                normPath.isEmpty() -> root.copy(
                    sourceUrl = cleanedUrl,
                    remoteId = preservedRemoteId,
                    linkedCloudKind = newCloudKind,
                    linkedProviderId = newProviderId,
                    cookie = cleanCookie,
                    streamQualityId = cleanStreamQuality,
                    downloadQualityId = cleanDownloadQuality,
                    trackedSubfolderName = null,
                )
                else -> {
                    val clearLegacy = root.trackedSubfolderName?.let {
                        LibraryRoot.normalizeFolderPath(it) == normPath
                    } == true
                    val nextSubs = root.trackedSubfolders + (normPath to info)
                    if (clearLegacy) {
                        root.copy(
                            sourceUrl = null,
                            remoteId = null,
                            linkedCloudKind = null,
                            linkedProviderId = null,
                            cookie = null,
                            lastSyncedEpochMs = null,
                            streamQualityId = null,
                            downloadQualityId = null,
                            trackedSubfolderName = null,
                            trackedSubfolders = nextSubs,
                        )
                    } else {
                        root.copy(trackedSubfolders = nextSubs)
                    }
                }
            }

            val nextRoots = current.roots.toMutableList()
            nextRoots[index] = nextRoot
            val nextAvailable = if (nextRoot.kind.isCloud) {
                current.availableRootIds + nextRoot.id
            } else {
                current.availableRootIds
            }
            _state.value = current.copy(
                roots = nextRoots,
                availableRootIds = nextAvailable,
            )
            updatedRoot = nextRoot
            updatedInfo = info
        }

        saveLibrary()
        val finalRoot = updatedRoot!!
        val finalInfo = updatedInfo!!
        if (!finalRoot.kind.isCloud) {
            withContext(Dispatchers.IO) {
                rewriteManifestOnDisk(finalRoot, normPath, finalInfo)
            }
        } else if (needsCloudResync) {
            resyncReboundCloudRoot(finalRoot, newProvider)
        }
        return Result.success(finalInfo)
    }

    private suspend fun resyncReboundCloudRoot(
        root: LibraryRoot,
        provider: CloudSourceProvider,
    ) {
        if (provider.supportsTree) {
            val signal = CompletableDeferred<Result<Unit>>()
            launchCloudTreeSync(
                provider = provider,
                root = root,
                isNewRoot = false,
                firstBatchSignal = signal,
            )
            runCatching { signal.await() }
            return
        }

        val url = root.sourceUrl ?: root.remoteId ?: return
        val effectiveCookie = if (provider.supportsCookie) {
            root.cookie?.trim()?.takeIf { it.isNotEmpty() }
                ?: providerRegistry.extensionCookieFor(provider.providerId)
        } else {
            null
        }
        val playlist = try {
            withContext(Dispatchers.IO) {
                provider.fetchCollection(url, effectiveCookie)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return
        }

        val existingTracks = synchronized(stateLock) {
            _state.value.tracks.filter { it.rootId == root.id }
        }
        val existingByRel = LinkedHashMap<String, Track>(existingTracks.size)
        val existingByRemoteId = LinkedHashMap<String, Track>(existingTracks.size)
        for (t in existingTracks) {
            existingByRel.putIfAbsent(t.relativePath, t)
            t.remoteTrackId?.takeIf { it.isNotBlank() }?.let { rid ->
                existingByRemoteId.putIfAbsent(rid, t)
            }
        }

        val syncNow = System.currentTimeMillis()
        val syncedTracks = withContext(Dispatchers.IO) {
            buildCloudTracks(
                root = root,
                provider = provider,
                items = playlist.tracks,
                existingByRel = existingByRel,
                nowMs = syncNow,
                existingByRemoteId = existingByRemoteId,
            )
        }

        val idRemap = LinkedHashMap<String, String>()
        val matchedOldIds = HashSet<String>()
        for (newTrack in syncedTracks) {
            val matchedOld = existingByRel[newTrack.relativePath]
                ?: newTrack.remoteTrackId?.let { existingByRemoteId[it] }
            if (matchedOld != null) {
                matchedOldIds.add(matchedOld.id)
                if (matchedOld.id != newTrack.id) {
                    idRemap[matchedOld.id] = newTrack.id
                }
            }
        }
        val removedIds = existingTracks
            .filter { it.id !in matchedOldIds && it.id !in syncedTracks.mapTo(HashSet()) { s -> s.id } }
            .mapTo(HashSet()) { it.id }

        var playlistsUpdated = false
        synchronized(stateLock) {
            val current = _state.value
            for ((oldId, newId) in idRemap) {
                metadataOverrides.remove(oldId)?.let { ov ->
                    metadataOverrides.putIfAbsent(newId, ov)
                }
            }
            for (rid in removedIds) {
                trackUris.remove(rid)
                metadataOverrides.remove(rid)
            }

            val finalSyncedTracks = syncedTracks.map { applyMetadataOverride(it) }
            val nextRoots = current.roots.map { r ->
                if (r.id == root.id) {
                    r.copy(
                        name = r.name.ifBlank { playlist.title },
                        remoteId = playlist.remoteId,
                        lastSyncedEpochMs = syncNow,
                    )
                } else {
                    r
                }
            }
            val activeRoots = nextRoots.associateBy { it.id }
            val combined = (current.tracks.filter { it.rootId != root.id } + finalSyncedTracks)
                .filter { track ->
                    val r = activeRoots[track.rootId] ?: return@filter false
                    !r.isExcluded(track.relativePath)
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

            var nextPlaylists = current.playlists
            var nextFavs = current.favourites
            if (idRemap.isNotEmpty()) {
                nextPlaylists = current.playlists.map { pl ->
                    val mapped = pl.trackIds.map { idRemap[it] ?: it }
                    if (mapped != pl.trackIds) {
                        playlistsUpdated = true
                        pl.copy(trackIds = mapped)
                    } else {
                        pl
                    }
                }
                val mappedFavs = current.favourites.mapTo(HashSet()) { idRemap[it] ?: it }
                if (mappedFavs != current.favourites) {
                    playlistsUpdated = true
                    nextFavs = mappedFavs
                }
                val rootPrefix = "${root.id}\u001F"
                for (key in folderTrackOrders.keys.filter { it.startsWith(rootPrefix) }.toList()) {
                    val order = folderTrackOrders[key] ?: continue
                    val mappedOrder = order.map { idRemap[it] ?: it }
                    if (mappedOrder != order) {
                        folderTrackOrders[key] = mappedOrder
                    }
                }
            }

            val (albums, artists, idMap) = buildIndices(combined)
            byId = idMap
            _state.value = current.copy(
                roots = nextRoots,
                availableRootIds = current.availableRootIds + root.id,
                tracks = combined,
                albums = albums,
                artists = artists,
                playlists = nextPlaylists,
                favourites = nextFavs,
                folderTrackOrders = folderTrackOrders.toMap(),
            )
        }

        if (playlistsUpdated) {
            savePlaylists()
        }
        if (idRemap.isNotEmpty()) {
            onTracksRemapped?.invoke(idRemap)
        }
        if (removedIds.isNotEmpty()) {
            forget(removedIds)
        }
        saveLibrary()
    }

    private fun rewriteManifestOnDisk(
        root: LibraryRoot,
        normPath: String,
        info: CloudTrackingInfo,
    ) {
        val baseFile: File? = when {
            root.kind == RootKind.APP_FOLDER -> appFolderDir
            else -> fileFromUriString(root.treeUri)
        }
        if (baseFile != null) {
            var targetDir: File = baseFile
            if (normPath.isNotEmpty()) {
                for (seg in normPath.split('/')) {
                    if (seg.isNotEmpty()) targetDir = File(targetDir, seg)
                }
            }
            if (!targetDir.exists() || !targetDir.isDirectory) return
            val existing = CloudExporter.loadManifest(targetDir)
            runCatching {
                CloudExporter.saveManifest(
                    targetDir,
                    CloudFolderManifest(
                        sourceURL = info.sourceUrl,
                        remoteID = info.remoteId,
                        cloudKind = info.cloudKind.name,
                        providerId = info.resolvedProviderId,
                        lastSyncedEpochMs = info.lastSyncedEpochMs ?: existing?.lastSyncedEpochMs ?: 0L,
                        streamQualityId = info.streamQualityId,
                        downloadQualityId = info.downloadQualityId,
                        tracks = existing?.tracks ?: emptyMap(),
                    ),
                )
            }
            return
        }

        val ctx = context ?: return
        val treeUri = root.treeUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return
        var targetDoc = runCatching { DocumentFile.fromTreeUri(ctx, treeUri) }.getOrNull() ?: return
        if (normPath.isNotEmpty()) {
            for (seg in normPath.split('/')) {
                if (seg.isEmpty()) continue
                targetDoc = targetDoc.findFile(seg)?.takeIf { it.isDirectory }
                    ?: targetDoc.listFiles().firstOrNull { it.isDirectory && it.name.equals(seg, ignoreCase = true) }
                    ?: return
            }
        }
        if (!targetDoc.exists() || !targetDoc.isDirectory || !targetDoc.canWrite()) return
        val existing = CloudExporter.loadManifest(ctx, targetDoc)
        runCatching {
            CloudExporter.saveManifest(
                ctx,
                targetDoc,
                CloudFolderManifest(
                    sourceURL = info.sourceUrl,
                    remoteID = info.remoteId,
                    cloudKind = info.cloudKind.name,
                    providerId = info.resolvedProviderId,
                    lastSyncedEpochMs = info.lastSyncedEpochMs ?: existing?.lastSyncedEpochMs ?: 0L,
                    streamQualityId = info.streamQualityId,
                    downloadQualityId = info.downloadQualityId,
                    tracks = existing?.tracks ?: emptyMap(),
                ),
            )
        }
    }

    suspend fun fetchCloudPlaylist(
        sourceUrl: String,
        cookie: String? = null,
        kindHint: RootKind? = null,
        providerIdHint: String? = null,
    ): CloudPlaylistResult = withContext(Dispatchers.IO) {
        val classified = runCatching { providerRegistry.classifyUrl(sourceUrl, providerIdHint) }.getOrNull()
        val provider = classified?.provider
            ?: providerRegistry.providerForKind(kindHint, providerIdHint)
            ?: throw CloudSourceException(
                CloudErrorCode.EXTENSION_UNAVAILABLE,
                "Cloud provider unavailable for $sourceUrl",
            )
        val cleanUrl = classified?.normalizedUrl ?: extractCloudSourceInput(sourceUrl)
        val effectiveCookie = if (provider.supportsCookie) {
            cookie?.trim()?.takeIf { it.isNotEmpty() }
                ?: providerRegistry.extensionCookieFor(provider.providerId)
        } else {
            null
        }
        provider.fetchCollection(cleanUrl, effectiveCookie)
    }

    fun markRootCloudSynced(
        rootId: String,
        syncedEpochMs: Long = System.currentTimeMillis(),
        subfolderPath: String? = null,
    ) {
        val normSub = subfolderPath?.let { LibraryRoot.normalizeFolderPath(it) }?.takeIf { it.isNotEmpty() }
        synchronized(stateLock) {
            val current = _state.value
            val index = current.roots.indexOfFirst { it.id == rootId }
            if (index < 0) return
            val root = current.roots[index]
            val updatedRoot = if (normSub == null) {
                root.copy(lastSyncedEpochMs = syncedEpochMs)
            } else {
                val existingSub = root.trackedSubfolders[normSub]
                if (existingSub != null) {
                    root.copy(
                        trackedSubfolders = root.trackedSubfolders + (normSub to existingSub.copy(lastSyncedEpochMs = syncedEpochMs)),
                    )
                } else if (root.trackedSubfolderName?.let { LibraryRoot.normalizeFolderPath(it) } == normSub) {
                    root.copy(lastSyncedEpochMs = syncedEpochMs)
                } else {
                    root
                }
            }
            val updated = current.roots.toMutableList()
            updated[index] = updatedRoot
            _state.value = current.copy(roots = updated)
        }
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

        synchronized(stateLock) {
            val latest = _state.value
            val idx = latest.roots.indexOfFirst { it.id == rootId }
            if (idx >= 0) {
                val updatedRoots = latest.roots.toMutableList()
                updatedRoots[idx] = updatedRoots[idx].copy(
                    name = name,
                    treeUri = treeUri.toString(),
                )
                _state.value = latest.copy(
                    roots = updatedRoots,
                    availableRootIds = latest.availableRootIds + rootId,
                )
            }
        }
        saveLibrary()
        rescan()
        return Result.success(Unit)
    }

    fun prioritizeCloudFolder(rootId: String, path: String) {
        val norm = LibraryRoot.normalizeFolderPath(path)
        if (norm.isEmpty()) {
            cloudPriorityPaths.remove(rootId)
        } else {
            cloudPriorityPaths[rootId] = norm
        }
    }

    suspend fun awaitCloudSync(rootId: String? = null) {
        if (rootId != null) {
            activeCloudSyncJobs[rootId]?.join()
        } else {
            activeCloudSyncJobs.values.toList().forEach { it.join() }
        }
    }

    suspend fun addCloudRoot(
        sourceUrl: String,
        cookie: String? = null,
        providerIdHint: String? = null,
    ): Result<Unit> {
        val classified = try {
            withContext(Dispatchers.IO) {
                providerRegistry.classifyUrl(sourceUrl, providerIdHint)
            } ?: return Result.failure(LibraryException.UnsupportedCloudUrl)
        } catch (err: Exception) {
            return Result.failure(err)
        }
        val provider = classified.provider
        val trimmedUrl = classified.normalizedUrl
        val cleanCookie = if (provider.supportsCookie) {
            cookie?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }

        if (provider.supportsTree) {
            val draftRoot = LibraryRoot(
                id = UUID.randomUUID().toString(),
                name = provider.displayName,
                kind = provider.rootKind,
                sourceUrl = trimmedUrl,
                remoteId = classified.remoteId,
                cookie = cleanCookie,
                providerId = provider.providerId,
            )
            val firstBatchSignal = CompletableDeferred<Result<Unit>>()
            launchCloudTreeSync(
                provider = provider,
                root = draftRoot,
                isNewRoot = true,
                firstBatchSignal = firstBatchSignal,
            )
            return firstBatchSignal.await()
        }

        val playlist = try {
            val effectiveCookie = if (provider.supportsCookie) {
                cleanCookie ?: providerRegistry.extensionCookieFor(provider.providerId)
            } else {
                null
            }
            withContext(Dispatchers.IO) {
                provider.fetchCollection(trimmedUrl, effectiveCookie)
            }
        } catch (err: Exception) {
            return Result.failure(err)
        }

        val duplicate = _state.value.roots.any {
            it.kind == provider.rootKind &&
                it.resolvedProviderId == provider.providerId &&
                it.remoteId == playlist.remoteId
        }
        if (duplicate) {
            return Result.failure(LibraryException.DuplicateRoot)
        }

        val nowMs = System.currentTimeMillis()
        val root = LibraryRoot(
            id = UUID.randomUUID().toString(),
            name = playlist.title,
            kind = provider.rootKind,
            sourceUrl = trimmedUrl,
            remoteId = playlist.remoteId,
            cookie = cleanCookie,
            lastSyncedEpochMs = nowMs,
            providerId = provider.providerId,
        )

        val newTracks = withContext(Dispatchers.IO) {
            buildCloudTracks(
                root = root,
                provider = provider,
                items = playlist.tracks,
                existingByRel = emptyMap(),
                nowMs = nowMs,
            )
        }

        synchronized(stateLock) {
            val current = _state.value
            _state.value = current.copy(
                roots = current.roots + root,
                availableRootIds = current.availableRootIds + root.id,
            )
        }
        publish(_state.value.tracks + newTracks)
        onFolderAdded?.invoke(root.name, newTracks.map { it.id })
        return Result.success(Unit)
    }

    fun removeRoot(rootId: String) {
        activeCloudSyncJobs.remove(rootId)?.cancel()
        cloudPriorityPaths.remove(rootId)
        knownCloudSubfolders.remove(rootId)

        var removedIds: Set<String> = emptySet()
        synchronized(stateLock) {
            val current = _state.value
            val index = current.roots.indexOfFirst { it.id == rootId }
            if (index < 0) return
            val root = current.roots[index]
            if (root.kind == RootKind.APP_FOLDER) return

            if (root.kind == RootKind.PICKED) {
                releasePersistableReadPermission(root.treeUri)
            }

            val updatedRoots = current.roots.toMutableList().apply { removeAt(index) }
            removedIds = current.tracks.filter { it.rootId == rootId }.map { it.id }.toSet()
            val keptTracks = current.tracks.filter { it.rootId != rootId }
            for (id in removedIds) {
                trackUris.remove(id)
                metadataOverrides.remove(id)
            }
            val rootPrefix = "$rootId\u001F"
            folderSortOrders.keys.removeAll { it.startsWith(rootPrefix) }
            folderTrackOrders.keys.removeAll { it.startsWith(rootPrefix) }

            val (albums, artists, idMap) = buildIndices(keptTracks)
            byId = idMap

            _state.value = current.copy(
                roots = updatedRoots,
                availableRootIds = current.availableRootIds - rootId,
                tracks = keptTracks,
                albums = albums,
                artists = artists,
                folderSortOrders = folderSortOrders.toMap(),
                folderTrackOrders = folderTrackOrders.toMap(),
                cloudSyncProgress = current.cloudSyncProgress - rootId,
            )
        }
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

    private fun launchCloudTreeSync(
        provider: CloudSourceProvider,
        root: LibraryRoot,
        isNewRoot: Boolean,
        firstBatchSignal: CompletableDeferred<Result<Unit>>? = null,
    ) {
        activeCloudSyncJobs.remove(root.id)?.cancel()
        val sourceUrl = root.sourceUrl ?: root.remoteId
        if (sourceUrl.isNullOrBlank()) {
            firstBatchSignal?.complete(Result.failure(LibraryException.UnsupportedCloudUrl))
            return
        }

        val job = cloudSyncScope.launch {
            var resolvedRoot = root
            var hasInitialized = false
            val existingTracks = synchronized(stateLock) {
                _state.value.tracks.filter { it.rootId == root.id }
            }
            val existingByRel = LinkedHashMap<String, Track>(existingTracks.size).apply {
                for (t in existingTracks) putIfAbsent(t.relativePath, t)
            }
            val seenRel = HashSet<String>()
            val crawledAllTracks = ArrayList<Track>()
            val syncNow = System.currentTimeMillis()

            try {
                val effectiveCookie = if (provider.supportsCookie) {
                    root.cookie?.trim()?.takeIf { it.isNotEmpty() }
                        ?: providerRegistry.extensionCookieFor(provider.providerId)
                } else {
                    null
                }
                provider.crawlProgressive(
                    sourceUrl = sourceUrl,
                    cookie = effectiveCookie,
                    priorityPathProvider = { cloudPriorityPaths[root.id] },
                ) { batch ->
                    if (!isActive) throw CancellationException()
                    val isFirstBatch = !hasInitialized
                    if (!hasInitialized) {
                        if (isNewRoot) {
                            val duplicate = synchronized(stateLock) {
                                _state.value.roots.any {
                                    it.kind == provider.rootKind &&
                                        it.resolvedProviderId == provider.providerId &&
                                        it.remoteId == batch.rootRemoteId
                                }
                            }
                            if (duplicate) {
                                firstBatchSignal?.complete(Result.failure(LibraryException.DuplicateRoot))
                                throw CancellationException("Duplicate root")
                            }
                        }
                        resolvedRoot = root.copy(
                            name = batch.rootTitle.ifBlank { root.name },
                            remoteId = batch.rootRemoteId,
                            providerId = provider.providerId,
                            lastSyncedEpochMs = if (batch.isComplete) System.currentTimeMillis() else root.lastSyncedEpochMs,
                        )
                        synchronized(stateLock) {
                            val cur = _state.value
                            val updatedRoots = if (isNewRoot) {
                                cur.roots + resolvedRoot
                            } else {
                                if (cur.roots.none { it.id == resolvedRoot.id }) {
                                    throw CancellationException("Root removed")
                                }
                                cur.roots.map { if (it.id == resolvedRoot.id) resolvedRoot else it }
                            }
                            _state.value = cur.copy(
                                roots = updatedRoots,
                                availableRootIds = cur.availableRootIds + resolvedRoot.id,
                            )
                        }
                        hasInitialized = true
                    } else {
                        val stillExists = synchronized(stateLock) {
                            _state.value.roots.any { it.id == resolvedRoot.id }
                        }
                        if (!stillExists) throw CancellationException("Root removed")
                    }

                    val batchTracks = buildCloudTracks(
                        root = resolvedRoot,
                        provider = provider,
                        items = batch.batchTracks,
                        existingByRel = existingByRel,
                        nowMs = syncNow,
                    )
                    for (t in batchTracks) {
                        if (seenRel.add(t.relativePath)) {
                            crawledAllTracks.add(t)
                        }
                    }

                    var removedOnComplete: Set<String> = emptySet()
                    var completeTrackIds: List<String>? = null

                    synchronized(stateLock) {
                        val cur = _state.value
                        if (cur.roots.none { it.id == resolvedRoot.id }) {
                            throw CancellationException("Root removed")
                        }

                        if (batch.isComplete) {
                            knownCloudSubfolders.remove(resolvedRoot.id)
                        } else {
                            knownCloudSubfolders[resolvedRoot.id] = batch.knownFolderPaths
                        }

                        val nextProgress = if (batch.isComplete) {
                            cur.cloudSyncProgress - resolvedRoot.id
                        } else {
                            cur.cloudSyncProgress + (
                                resolvedRoot.id to CloudSyncProgress(
                                    rootId = resolvedRoot.id,
                                    completedFolders = batch.completedFolders,
                                    totalFolders = batch.totalDiscoveredFolders,
                                    tracksFound = crawledAllTracks.size,
                                )
                            )
                        }

                        val nextRoots = if (batch.isComplete) {
                            val doneAt = System.currentTimeMillis()
                            cur.roots.map {
                                if (it.id == resolvedRoot.id) {
                                    it.copy(
                                        name = batch.rootTitle.ifBlank { it.name },
                                        remoteId = batch.rootRemoteId,
                                        providerId = provider.providerId,
                                        lastSyncedEpochMs = doneAt,
                                    )
                                } else it
                            }
                        } else {
                            cur.roots
                        }

                        val otherTracks = cur.tracks.filter { it.rootId != resolvedRoot.id }
                        val rootTracksNext = if (!batch.isComplete) {
                            // Merge without deleting existing tracks until the full crawl finishes.
                            val mergedByRel = LinkedHashMap<String, Track>()
                            for (t in cur.tracks) {
                                if (t.rootId == resolvedRoot.id) mergedByRel[t.relativePath] = t
                            }
                            for (t in batchTracks) {
                                mergedByRel[t.relativePath] = t
                            }
                            mergedByRel.values.toList()
                        } else {
                            if (crawledAllTracks.isEmpty() && existingByRel.isNotEmpty()) {
                                existingByRel.values.toList()
                            } else {
                                removedOnComplete = existingByRel
                                    .filterKeys { it !in seenRel }
                                    .values
                                    .mapTo(mutableSetOf()) { it.id }
                                crawledAllTracks.toList()
                            }
                        }

                        val activeRootMap = nextRoots.associateBy { it.id }
                        val combined = (otherTracks + rootTracksNext).filter { t ->
                            val r = activeRootMap[t.rootId] ?: return@filter false
                            !r.isExcluded(t.relativePath)
                        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

                        val (albums, artists, idMap) = buildIndices(combined)
                        byId = idMap
                        _state.value = cur.copy(
                            roots = nextRoots,
                            tracks = combined,
                            albums = albums,
                            artists = artists,
                            cloudSyncProgress = nextProgress,
                        )
                        if (batch.isComplete) {
                            completeTrackIds = combined.filter { it.rootId == resolvedRoot.id }.map { it.id }
                        }
                    }

                    if (isFirstBatch || batchTracks.isNotEmpty() || batch.isComplete) {
                        saveLibrary()
                    }
                    if (removedOnComplete.isNotEmpty()) {
                        for (id in removedOnComplete) {
                            trackUris.remove(id)
                            metadataOverrides.remove(id)
                        }
                        forget(removedOnComplete)
                    }
                    if (firstBatchSignal != null && !firstBatchSignal.isCompleted) {
                        firstBatchSignal.complete(Result.success(Unit))
                    }
                    if (batch.isComplete && isNewRoot) {
                        onFolderAdded?.invoke(resolvedRoot.name, completeTrackIds.orEmpty())
                    }
                }
            } catch (cancel: CancellationException) {
                knownCloudSubfolders.remove(resolvedRoot.id)
                synchronized(stateLock) {
                    val cur = _state.value
                    _state.value = cur.copy(cloudSyncProgress = cur.cloudSyncProgress - resolvedRoot.id)
                }
                if (firstBatchSignal != null && !firstBatchSignal.isCompleted) {
                    firstBatchSignal.complete(Result.failure(cancel))
                }
                throw cancel
            } catch (err: Exception) {
                knownCloudSubfolders.remove(resolvedRoot.id)
                synchronized(stateLock) {
                    val cur = _state.value
                    _state.value = cur.copy(cloudSyncProgress = cur.cloudSyncProgress - resolvedRoot.id)
                }
                if (firstBatchSignal != null && !firstBatchSignal.isCompleted) {
                    firstBatchSignal.complete(Result.failure(err))
                }
            }
        }
        activeCloudSyncJobs[root.id] = job
        job.invokeOnCompletion { activeCloudSyncJobs.remove(root.id, job) }
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
        val progressiveDriveRootIds = mutableSetOf<String>()
        var rootsChanged = false
        var cloudTracksChanged = false

        for ((rootIndex, root) in currentRoots.withIndex()) {
            if (root.id !in availableNow) continue
            val existingTracks = currentTracks.filter { it.rootId == root.id }

            if (root.kind.isCloud) {
                val url = root.sourceUrl ?: root.remoteId
                val provider = providerRegistry.providerForRoot(root)
                if (url.isNullOrBlank() || provider == null) {
                    kept.addAll(existingTracks)
                    continue
                }
                if (provider.supportsTree) {
                    val signal = CompletableDeferred<Result<Unit>>()
                    launchCloudTreeSync(
                        provider = provider,
                        root = root,
                        isNewRoot = false,
                        firstBatchSignal = signal,
                    )
                    val firstRes = signal.await()
                    if (firstRes.isSuccess) {
                        progressiveDriveRootIds.add(root.id)
                        updatedRoots[rootIndex] = _state.value.roots.firstOrNull { it.id == root.id } ?: root
                    } else {
                        kept.addAll(existingTracks)
                    }
                    continue
                }
                val syncResult = withContext(Dispatchers.IO) {
                    val effectiveCookie = if (provider.supportsCookie) {
                        root.cookie?.trim()?.takeIf { it.isNotEmpty() }
                            ?: providerRegistry.extensionCookieFor(provider.providerId)
                    } else {
                        null
                    }
                    runCatching {
                        provider.fetchCollection(url, effectiveCookie)
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
                        provider = provider,
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
                    providerId = provider.providerId,
                    lastSyncedEpochMs = syncNow,
                )
                rootsChanged = true
                continue
            }

            val (scanned, discovery) = withContext(Dispatchers.IO) {
                scanRoot(root) to discoverLocalCloudInfo(root)
            }

            // Treat unreachable/suddenly empty picked root as temporarily unreadable to avoid purging playlists.
            if (scanned == null || (scanned.isEmpty() && existingTracks.isNotEmpty() && root.kind == RootKind.PICKED)) {
                kept.addAll(existingTracks)
                continue
            }

            if (discovery != null) {
                val syncedRoot = reconcileLocalCloudTracking(root, scanned, discovery)
                if (syncedRoot != root) {
                    updatedRoots[rootIndex] = syncedRoot
                    rootsChanged = true
                }
            }
            val effectiveRoot = updatedRoots[rootIndex]

            val existingByRel = LinkedHashMap<String, Track>()
            for (t in existingTracks) {
                existingByRel.putIfAbsent(t.relativePath, t)
            }

            val seen = mutableSetOf<String>()
            for (file in scanned) {
                if (effectiveRoot.isExcluded(file.relativePath)) continue
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
                    pending.add(PendingScan(root = effectiveRoot, file = file, old = old))
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
                val resolvedArtBytes = meta.artwork ?: item.file.siblingArtworkUriString?.let { artUriStr ->
                    readImageBytesFromUriString(artUriStr)
                }
                val artKey = resolvedArtBytes?.let { artwork.store(it) }
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
                publishWithProgressiveDrive(kept + notReached, progressiveDriveRootIds)
            }
        }

        var hadStateChange = false
        synchronized(stateLock) {
            val latest = _state.value
            val finalRoots = if (rootsChanged) {
                val updatedMap = updatedRoots.associateBy { it.id }
                latest.roots.map { existing ->
                    if (existing.id in progressiveDriveRootIds) existing else (updatedMap[existing.id] ?: existing)
                }
            } else {
                latest.roots
            }
            val activeRoots = finalRoots.associateBy { it.id }
            val liveDriveTracks = if (progressiveDriveRootIds.isEmpty()) {
                emptyList()
            } else {
                latest.tracks.filter { it.rootId in progressiveDriveRootIds }
            }
            val finalTracks = (kept.filter { it.rootId !in progressiveDriveRootIds } + liveDriveTracks)
                .filter { track ->
                    val r = activeRoots[track.rootId] ?: return@filter false
                    !r.isExcluded(track.relativePath)
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

            val finalIds = finalTracks.map { it.id }.toSet()
            val finalRemoved = removed.filter { it !in finalIds }.toSet()
            if (reread) rereadAll = false

            if (pending.isEmpty() && finalRemoved.isEmpty() && !cloudTracksChanged && finalTracks.size == latest.tracks.size) {
                if (rootsChanged) {
                    _state.value = latest.copy(roots = finalRoots)
                }
                if (reread || rootsChanged) {
                    hadStateChange = false
                }
            } else {
                hadStateChange = true
                val (albums, artists, idMap) = buildIndices(finalTracks)
                byId = idMap
                _state.value = latest.copy(
                    roots = finalRoots,
                    tracks = finalTracks,
                    albums = albums,
                    artists = artists,
                )
            }
        }

        val finalRemoved = removed.filter { it !in byId.keys }.toSet()
        if (!hadStateChange && finalRemoved.isEmpty()) {
            if (reread || rootsChanged) saveLibrary()
            return
        }

        saveLibrary()
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

    private fun publishWithProgressiveDrive(list: List<Track>, progressiveDriveRootIds: Set<String>) {
        synchronized(stateLock) {
            val current = _state.value
            val liveDriveTracks = if (progressiveDriveRootIds.isEmpty()) {
                emptyList()
            } else {
                current.tracks.filter { it.rootId in progressiveDriveRootIds }
            }
            val merged = list.filter { it.rootId !in progressiveDriveRootIds } + liveDriveTracks
            val activeRoots = current.roots.associateBy { it.id }
            val filtered = merged.filter { track ->
                val r = activeRoots[track.rootId] ?: return@filter false
                !r.isExcluded(track.relativePath)
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            val (albums, artists, idMap) = buildIndices(filtered)
            byId = idMap
            _state.value = current.copy(
                tracks = filtered,
                albums = albums,
                artists = artists,
            )
        }
        saveLibrary()
    }

    private fun discoverLocalCloudInfo(root: LibraryRoot): LocalCloudDiscovery? {
        if (root.kind == RootKind.APP_FOLDER) {
            return FolderScanner.discoverCloudInfo(appFolderDir)
        }
        val uriStr = root.treeUri ?: return null
        fileFromUriString(uriStr)?.let { dir ->
            return FolderScanner.discoverCloudInfo(dir)
        }
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
        val ctx = context ?: return null
        return FolderScanner.discoverCloudInfo(ctx, uri)
    }

    private fun reconcileLocalCloudTracking(
        root: LibraryRoot,
        scanned: List<ScannedFile>,
        discovery: LocalCloudDiscovery,
    ): LibraryRoot {
        var nextRoot = migrateLegacySubfolderTracking(root)
        val existingDirs = HashSet<String>(discovery.existingSubfolders)
        for (file in scanned) {
            var dir = LibraryRoot.normalizeFolderPath(file.relativePath.substringBeforeLast('/', ""))
            while (dir.isNotEmpty()) {
                existingDirs.add(dir)
                dir = dir.substringBeforeLast('/', "")
            }
        }

        val nextSubs = LinkedHashMap<String, CloudTrackingInfo>()
        for ((subKey, info) in nextRoot.trackedSubfolders) {
            val normKey = LibraryRoot.normalizeFolderPath(subKey)
            if (normKey.isNotEmpty() && normKey in existingDirs) {
                nextSubs[normKey] = info
            }
        }

        for ((rawPath, manifest) in discovery.manifests) {
            val normPath = LibraryRoot.normalizeFolderPath(rawPath)
            val parsedKind = runCatching { RootKind.valueOf(manifest.cloudKind) }.getOrNull() ?: continue
            val url = manifest.sourceURL.trim().takeIf { it.isNotEmpty() } ?: continue
            val pid = manifest.providerId?.trim()?.takeIf { it.isNotEmpty() } ?: parsedKind.builtinProviderId
            if (normPath.isEmpty()) {
                if (nextRoot.sourceUrl.isNullOrBlank() && nextSubs.isEmpty()) {
                    nextRoot = nextRoot.copy(
                        sourceUrl = url,
                        remoteId = manifest.remoteID ?: nextRoot.remoteId,
                        linkedCloudKind = parsedKind,
                        linkedProviderId = pid,
                        lastSyncedEpochMs = manifest.lastSyncedEpochMs.takeIf { it > 0L } ?: nextRoot.lastSyncedEpochMs,
                        streamQualityId = nextRoot.streamQualityId ?: manifest.streamQualityId,
                        downloadQualityId = nextRoot.downloadQualityId ?: manifest.downloadQualityId,
                    )
                }
            } else if (!nextRoot.isExcluded(normPath) && !nextSubs.containsKey(normPath)) {
                nextSubs[normPath] = CloudTrackingInfo(
                    sourceUrl = url,
                    remoteId = manifest.remoteID,
                    cloudKind = parsedKind,
                    providerId = if (parsedKind == RootKind.CLOUD_EXTENSION) pid else manifest.providerId,
                    lastSyncedEpochMs = manifest.lastSyncedEpochMs.takeIf { it > 0L },
                    streamQualityId = manifest.streamQualityId,
                    downloadQualityId = manifest.downloadQualityId,
                )
            }
        }

        return if (nextSubs != nextRoot.trackedSubfolders) {
            nextRoot.copy(trackedSubfolders = nextSubs)
        } else {
            nextRoot
        }
    }

    private suspend fun buildCloudTracks(
        root: LibraryRoot,
        provider: CloudSourceProvider?,
        items: List<CloudTrackMetadata>,
        existingByRel: Map<String, Track>,
        nowMs: Long,
        existingByRemoteId: Map<String, Track> = emptyMap(),
    ): List<Track> {
        val artKeysByUrl = HashMap<String, String?>()
        val seenRel = HashSet<String>()
        val result = ArrayList<Track>(items.size)
        val sourceSlug = provider?.cloudSourceSlug ?: when (root.kind) {
            RootKind.CLOUD_GOOGLE_DRIVE -> CloudSource.GOOGLE_DRIVE
            RootKind.CLOUD_EXTENSION -> root.resolvedProviderId ?: root.kind.name.lowercase()
            else -> root.kind.name.lowercase()
        }
        val cloudSource = provider?.cloudSourceSlug ?: when (root.kind) {
            RootKind.CLOUD_YOUTUBE -> CloudSource.YOUTUBE
            RootKind.CLOUD_BILIBILI -> CloudSource.BILIBILI
            RootKind.CLOUD_GOOGLE_DRIVE -> CloudSource.GOOGLE_DRIVE
            RootKind.CLOUD_EXTENSION -> root.resolvedProviderId ?: CloudSource.YOUTUBE
            else -> CloudSource.YOUTUBE
        }
        val artHeaders = provider?.artworkHeaders() ?: buildMap {
            if (root.kind == RootKind.CLOUD_BILIBILI) {
                put("Referer", "https://www.bilibili.com/")
                put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            }
        }

        for ((index, meta) in items.withIndex()) {
            val relPath = meta.relativePath
                ?.let { LibraryRoot.normalizeFolderPath(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: "$sourceSlug/${meta.remoteTrackId}"
            if (root.isExcluded(relPath)) continue
            if (!seenRel.add(relPath)) continue
            val old = existingByRel[relPath] ?: existingByRemoteId[meta.remoteTrackId]
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

    private fun readImageBytesFromUriString(uriStr: String): ByteArray? {
        fileFromUriString(uriStr)?.let { file ->
            val raw = runCatching { if (file.isFile && file.canRead()) file.readBytes() else null }.getOrNull()
            return raw?.let { com.musico.core.EmbeddedArtworkExtractor.normalizeImageBytes(it) }
        }
        val ctx = context ?: return null
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
        val raw = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        return com.musico.core.EmbeddedArtworkExtractor.normalizeImageBytes(raw)
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
        synchronized(stateLock) {
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
        }
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

    suspend fun applyFallbackMetadataForSavedFile(
        savedFileName: String,
        sourceTrack: Track,
        targetDir: File? = null,
        targetUri: Uri? = null,
    ) {
        val targetCanonical = targetDir?.let { dir ->
            runCatching { File(dir, savedFileName).canonicalPath }.getOrNull()
        }
        val candidate = _state.value.tracks
            .filter { !it.isCloud && it.fileName.equals(savedFileName, ignoreCase = true) }
            .firstOrNull { local ->
                when {
                    targetCanonical != null ->
                        runCatching { localFileFor(local)?.canonicalPath }.getOrNull() == targetCanonical
                    targetUri != null -> {
                        val root = rootFor(local)
                        val rootUri = root?.treeUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                        rootUri != null && (canonicalUriKey(rootUri) == canonicalUriKey(targetUri) || isUriInsideIndexedRoot(targetUri))
                    }
                    else -> true
                }
            } ?: return

        val needTitle = sourceTrack.title.isNotBlank() &&
            candidate.title.equals(candidate.fileName.substringBeforeLast('.', candidate.fileName), ignoreCase = true)
        val needArtist = candidate.artist == "Unknown Artist" &&
            sourceTrack.artist.isNotBlank() &&
            sourceTrack.artist != "Unknown Artist"
        val needAlbum = candidate.album == "Unknown Album" &&
            sourceTrack.album.isNotBlank() &&
            sourceTrack.album != "Unknown Album"
        val needGenre = candidate.genre.isBlank() && sourceTrack.genre.isNotBlank()
        val artUrl = sourceTrack.remoteArtworkUrl
            ?.takeIf { CloudThumbnailCache.isAllowedUrl(it) }
        val resolvedArtKey = if (candidate.artworkKey == null) {
            sourceTrack.artworkKey ?: artUrl?.let { url ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        val providerHeaders = providerRegistry.providerForTrack(sourceTrack, null)
                            ?.artworkHeaders()
                            .orEmpty()
                        val resolved = CloudThumbnailCache.fetchImageBytesWithClient(
                            client = cloudHttpClient,
                            url = url,
                            headers = providerHeaders,
                        )
                        resolved?.bytes?.let { artwork.store(it) }
                    }.getOrNull()
                }
            }
        } else {
            null
        }

        if (needTitle || needArtist || needAlbum || needGenre || resolvedArtKey != null) {
            updateTrackMetadata(
                trackId = candidate.id,
                title = if (needTitle) sourceTrack.title else null,
                artist = if (needArtist) sourceTrack.artist else null,
                album = if (needAlbum) sourceTrack.album else null,
                genre = if (needGenre) sourceTrack.genre else null,
                artworkKey = resolvedArtKey,
            )
        }
    }

    private fun forget(ids: Set<String>) {
        if (ids.isEmpty()) return
        val current = _state.value
        val nextPlaylists = current.playlists.map { pl ->
            pl.copy(trackIds = pl.trackIds.filter { it !in ids })
        }
        val nextFavourites = current.favourites - ids
        var ordersChanged = false
        for (key in folderTrackOrders.keys.toList()) {
            val oldOrder = folderTrackOrders[key] ?: continue
            val pruned = oldOrder.filter { it !in ids }
            if (pruned.size != oldOrder.size) {
                folderTrackOrders[key] = pruned
                ordersChanged = true
            }
        }
        _state.value = current.copy(
            playlists = nextPlaylists,
            favourites = nextFavourites,
            folderTrackOrders = if (ordersChanged) folderTrackOrders.toMap() else current.folderTrackOrders,
        )
        savePlaylists()
        if (ordersChanged) {
            saveLibrary()
        }
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

    fun registerEphemeralTrack(track: Track) {
        registerEphemeralTracks(listOf(track))
    }

    fun registerEphemeralTracks(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        synchronized(stateLock) {
            for (t in tracks) {
                val normalized = t.copy(isEphemeral = true)
                ephemeralTracks[normalized.id] = normalized
            }
        }
        if (referencedQueueTrackIdsProvider == null) {
            saveLibrary()
        }
    }

    fun pruneEphemeralTracks(referencedIds: Set<String>) {
        var changed = false
        synchronized(stateLock) {
            val iter = ephemeralTracks.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (entry.key !in referencedIds) {
                    iter.remove()
                    changed = true
                }
            }
            val nextPersisted = ephemeralTracks.keys.filterTo(HashSet()) { it in referencedIds }
            if (nextPersisted != persistedEphemeralIds) {
                changed = true
            }
        }
        if (changed) {
            saveLibrary()
        }
    }

    fun ephemeralTrackIds(): Set<String> = synchronized(stateLock) { ephemeralTracks.keys.toSet() }

    fun track(id: String): Track? = byId[id] ?: synchronized(stateLock) { ephemeralTracks[id] }

    fun tracks(ids: List<String>): List<Track> = ids.mapNotNull { track(it) }

    fun rootFor(track: Track): LibraryRoot? =
        if (track.isEphemeral || Track.isEphemeralId(track.id)) null
        else _state.value.roots.firstOrNull { it.id == track.rootId }

    fun appFolderDirectory(): File = appFolderDir

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

    fun isAvailable(track: Track): Boolean {
        if (track.isEphemeral || Track.isEphemeralId(track.id)) {
            val pid = CloudProviderIds.fromLegacySlug(track.cloudSource)
                ?: Track.extensionIdFromEphemeralId(track.id)?.let { CloudProviderIds.extensionProviderId(it) }
                ?: return false
            return providerRegistry.availabilityFor(pid) == CloudProviderAvailability.AVAILABLE
        }
        return track.rootId in _state.value.availableRootIds
    }

    /** Returns true if [uri] matches or resides inside an already-indexed local root. */
    fun isDirectoryInsideIndexedRoot(dir: File): Boolean {
        val candidate = runCatching { dir.canonicalPath }.getOrNull() ?: return false
        return isPathInsideIndexedRoot(candidate)
    }

    private fun isPathInsideIndexedRoot(candidateCanonicalPath: String): Boolean =
        _state.value.roots.any { root ->
            val rootPath = fileCanonicalForRoot(root) ?: return@any false
            candidateCanonicalPath == rootPath || candidateCanonicalPath.startsWith("$rootPath${File.separator}")
        }

    fun isUriInsideIndexedRoot(uri: Uri): Boolean {
        if (uri.scheme == "file") {
            val candidate = uri.path?.let { runCatching { File(it).canonicalPath }.getOrNull() }
                ?: return false
            return isPathInsideIndexedRoot(candidate)
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

    suspend fun readFolderMatchedLyrics(track: Track): ByteArray? = withContext(Dispatchers.IO) {
        if (track.isCloud) return@withContext null
        val current = _state.value
        if (track.rootId !in current.availableRootIds) return@withContext null
        val root = current.roots.firstOrNull { it.id == track.rootId } ?: return@withContext null
        if (root.kind.isCloud) return@withContext null

        val stem = track.fileName.substringBeforeLast('.', track.fileName)
        val folderPath = track.folderPath

        if (root.kind == RootKind.APP_FOLDER) {
            val dir = if (folderPath.isEmpty()) appFolderDir else File(appFolderDir, folderPath)
            return@withContext readLocalFolderMatch(dir, stem, track.title, track.artist)
        }

        val treeUriStr = root.treeUri ?: return@withContext null
        val treeUri = runCatching { Uri.parse(treeUriStr) }.getOrNull() ?: return@withContext null
        if (treeUri.scheme == "file" && treeUri.path != null) {
            val base = File(treeUri.path!!)
            val dir = if (folderPath.isEmpty()) base else File(base, folderPath)
            return@withContext readLocalFolderMatch(dir, stem, track.title, track.artist)
        }

        val ctx = context ?: return@withContext null
        readSafFolderMatch(ctx, treeUri, folderPath, stem, track.title, track.artist)
    }

    fun canWriteSidecarLyrics(track: Track): Boolean {
        if (track.isCloud) return false
        val current = _state.value
        if (track.rootId !in current.availableRootIds) return false
        val root = current.roots.firstOrNull { it.id == track.rootId } ?: return false
        if (root.kind.isCloud) return false

        val folderPath = track.folderPath
        if (root.kind == RootKind.APP_FOLDER) {
            val dir = if (folderPath.isEmpty()) appFolderDir else File(appFolderDir, folderPath)
            return dir.exists() && dir.isDirectory && dir.canWrite()
        }

        val treeUriStr = root.treeUri ?: return false
        val treeUri = runCatching { Uri.parse(treeUriStr) }.getOrNull() ?: return false
        if (treeUri.scheme == "file" && treeUri.path != null) {
            val base = File(treeUri.path!!)
            val dir = if (folderPath.isEmpty()) base else File(base, folderPath)
            return dir.exists() && dir.isDirectory && dir.canWrite()
        }

        val ctx = context ?: return false
        val dirDoc = resolveSafFolderDoc(ctx, treeUri, folderPath) ?: return false
        return dirDoc.canWrite()
    }

    suspend fun writeSidecarLyrics(track: Track, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (LrcParser.parse(text).lines.isEmpty()) {
                throw IllegalArgumentException("Lyrics text is empty or unreadable.")
            }
            if (track.isCloud) throw IllegalStateException("Cloud tracks do not have a local song folder.")
            val current = _state.value
            if (track.rootId !in current.availableRootIds) {
                throw IllegalStateException("Song folder is currently unavailable.")
            }
            val root = current.roots.firstOrNull { it.id == track.rootId }
                ?: throw IllegalStateException("Song folder could not be found.")
            if (root.kind.isCloud) throw IllegalStateException("Cloud tracks do not have a local song folder.")

            val stem = track.fileName.substringBeforeLast('.', track.fileName)
            val targetName = "$stem.lrc"
            val folderPath = track.folderPath

            if (root.kind == RootKind.APP_FOLDER) {
                val dir = if (folderPath.isEmpty()) appFolderDir else File(appFolderDir, folderPath)
                writeLocalSidecar(dir, targetName, text)
                return@runCatching
            }

            val treeUriStr = root.treeUri ?: throw IllegalStateException("Folder URI is missing.")
            val treeUri = Uri.parse(treeUriStr)
            if (treeUri.scheme == "file" && treeUri.path != null) {
                val base = File(treeUri.path!!)
                val dir = if (folderPath.isEmpty()) base else File(base, folderPath)
                writeLocalSidecar(dir, targetName, text)
                return@runCatching
            }

            val ctx = context ?: throw IllegalStateException("Android context unavailable.")
            val dirDoc = resolveSafFolderDoc(ctx, treeUri, folderPath)
                ?: throw IllegalStateException("Could not open song folder.")
            if (!dirDoc.canWrite()) {
                throw IllegalStateException("Song folder is read-only.")
            }
            dirDoc.listFiles()
                .firstOrNull { !it.isDirectory && it.name.equals(targetName, ignoreCase = true) }
                ?.delete()
            val created = dirDoc.createFile("application/octet-stream", targetName)
                ?: throw IllegalStateException("Could not create .lrc file in song folder.")
            ctx.contentResolver.openOutputStream(created.uri, "wt")?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: throw IllegalStateException("Could not open output stream for .lrc file.")
        }
    }

    private fun writeLocalSidecar(dir: File, targetName: String, text: String) {
        if (!dir.exists()) dir.mkdirs()
        val dest = File(dir, targetName)
        val temp = File(dir, "$targetName.tmp.${System.nanoTime()}")
        try {
            temp.writeText(text, Charsets.UTF_8)
            if (!temp.renameTo(dest)) {
                temp.copyTo(dest, overwrite = true)
                temp.delete()
            }
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            throw t
        }
    }

    private fun readLocalFolderMatch(
        dir: File,
        audioStem: String,
        trackTitle: String,
        trackArtist: String,
    ): ByteArray? {
        val children = dir.listFiles() ?: return null
        val exactNames = setOf(
            "$audioStem.lrc".lowercase(),
            "$audioStem.txt".lowercase(),
        )
        data class Candidate(
            val fileName: String,
            val isLrc: Boolean,
            val isTimed: Boolean,
            val score: Double,
            val bytes: ByteArray,
        )

        val candidates = ArrayList<Candidate>()
        for (child in children) {
            if (!child.isFile || child.isHidden) continue
            val name = child.name
            if (name.lowercase() in exactNames) continue
            val ext = child.extension.lowercase()
            if (ext != "lrc" && ext != "txt") continue
            val score = LyricsNameMatcher.matchScore(child.nameWithoutExtension, audioStem, trackTitle, trackArtist)
            if (score < LyricsNameMatcher.MINIMUM_MATCH_SCORE) continue
            val bytes = runCatching { child.readBytes() }.getOrNull() ?: continue
            val decoded = LrcParser.decode(bytes) ?: continue
            val parsed = LrcParser.parse(decoded)
            if (parsed.lines.isEmpty()) continue
            candidates.add(Candidate(name, ext == "lrc", parsed.isTimed, score, bytes))
        }
        return candidates.sortedWith { a, b ->
            if (kotlin.math.abs(a.score - b.score) > 0.001) b.score.compareTo(a.score)
            else if (a.isTimed != b.isTimed) if (b.isTimed) 1 else -1
            else if (a.isLrc != b.isLrc) if (b.isLrc) 1 else -1
            else a.fileName.lowercase().compareTo(b.fileName.lowercase())
        }.firstOrNull()?.bytes
    }

    private fun readSafFolderMatch(
        ctx: Context,
        treeUri: Uri,
        folderPath: String,
        audioStem: String,
        trackTitle: String,
        trackArtist: String,
    ): ByteArray? {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        var currentDocId = rootDocId
        if (folderPath.isNotEmpty()) {
            for (segment in folderPath.split('/')) {
                if (segment.isEmpty()) continue
                currentDocId = findChildDocId(ctx, treeUri, currentDocId, segment, wantDir = true) ?: return null
            }
        }
        val exactNames = setOf(
            "$audioStem.lrc".lowercase(),
            "$audioStem.txt".lowercase(),
        )
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )

        data class RawSafChild(val docId: String, val name: String, val isLrc: Boolean, val score: Double)

        val matched = ArrayList<RawSafChild>()
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
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                if (name.lowercase() in exactNames) continue
                val dot = name.lastIndexOf('.')
                if (dot <= 0 || dot >= name.length - 1) continue
                val stem = name.substring(0, dot)
                val ext = name.substring(dot + 1).lowercase()
                if (ext != "lrc" && ext != "txt") continue
                val score = LyricsNameMatcher.matchScore(stem, audioStem, trackTitle, trackArtist)
                if (score >= LyricsNameMatcher.MINIMUM_MATCH_SCORE) {
                    matched.add(RawSafChild(id, name, ext == "lrc", score))
                }
            }
        }

        if (matched.isEmpty()) return null
        data class ParsedSafChild(val name: String, val isLrc: Boolean, val isTimed: Boolean, val score: Double, val bytes: ByteArray)

        val parsedCandidates = ArrayList<ParsedSafChild>(matched.size)
        for (item in matched) {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, item.docId)
            val bytes = runCatching {
                ctx.contentResolver.openInputStream(docUri)?.use { it.readBytes() }
            }.getOrNull() ?: continue
            val decoded = LrcParser.decode(bytes) ?: continue
            val parsed = LrcParser.parse(decoded)
            if (parsed.lines.isEmpty()) continue
            parsedCandidates.add(ParsedSafChild(item.name, item.isLrc, parsed.isTimed, item.score, bytes))
        }

        return parsedCandidates.sortedWith { a, b ->
            if (kotlin.math.abs(a.score - b.score) > 0.001) b.score.compareTo(a.score)
            else if (a.isTimed != b.isTimed) if (b.isTimed) 1 else -1
            else if (a.isLrc != b.isLrc) if (b.isLrc) 1 else -1
            else a.name.lowercase().compareTo(b.name.lowercase())
        }.firstOrNull()?.bytes
    }

    private fun resolveSafFolderDoc(ctx: Context, treeUri: Uri, folderPath: String): DocumentFile? {
        var current = DocumentFile.fromTreeUri(ctx, treeUri) ?: return null
        if (folderPath.isNotEmpty()) {
            for (segment in folderPath.split('/')) {
                if (segment.isEmpty()) continue
                current = current.findFile(segment)?.takeIf { it.isDirectory }
                    ?: current.listFiles().firstOrNull { it.isDirectory && it.name.equals(segment, ignoreCase = true) }
                    ?: return null
            }
        }
        return current
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

    private fun migrateCloudFolderKeys(roots: List<LibraryRoot>) {
        val cloudRoots = roots.filter { it.kind.isCloud }
        if (cloudRoots.isEmpty()) return
        val legacySubfolders = listOf(
            "cloud_youtube",
            "cloud_bilibili",
            "cloud_google_drive",
            "youtube",
            "bilibili",
            "gdrive",
            "google_drive",
        )
        for (root in cloudRoots) {
            val rootKey = "${root.id}\u001F"
            for (sub in legacySubfolders) {
                val legacyKey = "${root.id}\u001F$sub"
                folderTrackOrders.remove(legacyKey)?.let { legacyOrder ->
                    if (!folderTrackOrders.containsKey(rootKey)) {
                        folderTrackOrders[rootKey] = legacyOrder
                    }
                }
                folderSortOrders.remove(legacyKey)?.let { legacySort ->
                    if (!folderSortOrders.containsKey(rootKey)) {
                        folderSortOrders[rootKey] = legacySort
                    }
                }
            }
        }
    }

    private fun isFlatCloudRoot(root: LibraryRoot?): Boolean {
        if (root == null || !root.kind.isCloud) return false
        val provider = providerRegistry.providerForRoot(root)
        if (provider != null) {
            if (provider.usesFlatCloudListing) return true
            if (root.kind == RootKind.CLOUD_GOOGLE_DRIVE) {
                if (knownCloudSubfolders[root.id]?.isNotEmpty() == true) return false
                val driveTracks = _state.value.tracks.filter { it.rootId == root.id }
                if (driveTracks.isEmpty()) return false
                return driveTracks.all { it.relativePath.startsWith("gdrive/", ignoreCase = true) }
            }
            return false
        }
        if (root.kind == RootKind.CLOUD_GOOGLE_DRIVE) {
            if (knownCloudSubfolders[root.id]?.isNotEmpty() == true) return false
            val driveTracks = _state.value.tracks.filter { it.rootId == root.id }
            if (driveTracks.isEmpty()) return false
            return driveTracks.all { it.relativePath.startsWith("gdrive/", ignoreCase = true) }
        }
        if (root.kind == RootKind.CLOUD_EXTENSION) {
            if (knownCloudSubfolders[root.id]?.isNotEmpty() == true) return false
            val extTracks = _state.value.tracks.filter { it.rootId == root.id }
            if (extTracks.isEmpty()) return false
            val slug = root.resolvedProviderId.orEmpty()
            return extTracks.all { t ->
                val prefix = "$slug/"
                t.relativePath.startsWith(prefix) && !t.relativePath.substring(prefix.length).contains('/')
            }
        }
        return true
    }

    private fun canonicalFolderPath(rootId: String, path: String): String {
        val norm = LibraryRoot.normalizeFolderPath(path)
        val root = _state.value.roots.firstOrNull { it.id == rootId }
        if (isFlatCloudRoot(root)) {
            if (norm.isEmpty() ||
                norm.equals(root!!.kind.name, ignoreCase = true) ||
                norm.equals("cloud_youtube", ignoreCase = true) ||
                norm.equals("cloud_bilibili", ignoreCase = true) ||
                norm.equals("cloud_google_drive", ignoreCase = true) ||
                norm.equals("youtube", ignoreCase = true) ||
                norm.equals("bilibili", ignoreCase = true) ||
                norm.equals("gdrive", ignoreCase = true) ||
                norm.equals("google_drive", ignoreCase = true)
            ) {
                return ""
            }
        }
        return norm
    }

    fun folderKey(rootId: String, path: String): String =
        "$rootId\u001F${canonicalFolderPath(rootId, path)}"

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

    private fun defaultDirectTrackOrder(tracks: List<Track>): List<Track> {
        if (tracks.isEmpty()) return tracks
        return if (tracks.all { it.isCloud }) {
            tracks.sortedWith(
                compareBy<Track> { it.trackNumber ?: Int.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
        } else {
            tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.fileName })
        }
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
                    defaultDirectTrackOrder(direct)
                } else {
                    val rank = HashMap<String, Int>(customIds.size)
                    customIds.forEachIndexed { idx, id -> rank.putIfAbsent(id, idx) }
                    val inCustom = direct.filter { it.id in rank }.sortedBy { rank[it.id] }
                    val remaining = defaultDirectTrackOrder(direct.filter { it.id !in rank })
                    inCustom + remaining
                }
            }
            explicitSort != null -> sortTracks(direct, explicitSort, playCounts)
            else -> defaultDirectTrackOrder(direct)
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
        val root = _state.value.roots.firstOrNull { it.id == rootId }
        val normPath = canonicalFolderPath(rootId, path)
        if (isFlatCloudRoot(root)) {
            if (normPath.isNotEmpty()) {
                return emptyList<String>() to emptyList()
            }
            val direct = _state.value.tracks.filter { it.rootId == rootId }
            return emptyList<String>() to orderDirectTracks(rootId, "", direct, playCounts)
        }

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
        if (root != null && root.kind.isCloud && !isFlatCloudRoot(root)) {
            for (knownPath in knownCloudSubfolders[rootId].orEmpty()) {
                if (root.isExcluded(knownPath)) continue
                if (!knownPath.startsWith(prefix)) continue
                val rest = knownPath.substring(prefix.length)
                if (rest.isNotEmpty()) {
                    folders.add(rest.substringBefore('/'))
                }
            }
        }
        return folders.sortedWith(String.CASE_INSENSITIVE_ORDER) to
            orderDirectTracks(rootId, normPath, direct, playCounts)
    }

    private fun hasDescendantOrder(rootId: String, normPath: String): Boolean {
        val prefixKey = if (normPath.isEmpty()) "$rootId\u001F" else "$rootId\u001F$normPath/"
        return folderSortOrders.keys.any { it.startsWith(prefixKey) && it != "$rootId\u001F$normPath" } ||
            folderTrackOrders.keys.any { it.startsWith(prefixKey) && it != "$rootId\u001F$normPath" }
    }

    /** Every song in a folder and its subfolders, respecting persisted sort/custom order when set. */
    fun tracksUnder(
        rootId: String,
        path: String,
        playCounts: (String) -> Int = { 0 },
    ): List<Track> {
        val root = _state.value.roots.firstOrNull { it.id == rootId }
        val normPath = canonicalFolderPath(rootId, path)
        if (isFlatCloudRoot(root)) {
            if (normPath.isNotEmpty()) return emptyList()
            return folderContents(rootId, "", playCounts).second
        }

        val prefix = if (normPath.isEmpty()) "" else "$normPath/"
        val matching = _state.value.tracks.filter {
            it.rootId == rootId && it.relativePath.startsWith(prefix)
        }
        val key = folderKey(rootId, normPath)
        val explicitSort = folderSortOrders[key]
        val hasCustom = explicitSort == TrackSortOrder.CUSTOM ||
            (explicitSort == null && folderTrackOrders.containsKey(key))

        if (hasCustom || (explicitSort == null && hasDescendantOrder(rootId, normPath))) {
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
        if (Track.isEphemeralId(id) || track(id)?.isEphemeral == true) return
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
        val eligible = trackIds.filterNot { Track.isEphemeralId(it) || track(it)?.isEphemeral == true }
        if (eligible.isEmpty()) return
        updatePlaylist(playlistId) { it.copy(trackIds = it.trackIds + eligible) }
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
        private const val READER_VERSION = 5
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
