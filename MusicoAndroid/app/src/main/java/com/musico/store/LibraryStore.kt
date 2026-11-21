package com.musico.store

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
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
import com.musico.model.Track
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
}

data class LibraryUiState(
    val roots: List<LibraryRoot> = emptyList(),
    val availableRootIds: Set<String> = emptySet(),
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<String> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val favourites: Set<String> = emptySet(),
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
) {
    @Serializable
    private data class LibraryFile(
        val roots: List<LibraryRoot> = emptyList(),
        val tracks: List<Track> = emptyList(),
        val readerVersion: Int? = null,
        val trackUris: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class PlaylistFile(
        val playlists: List<Playlist> = emptyList(),
        val favourites: Set<String> = emptySet(),
    )

    var onTracksRemoved: ((Set<String>) -> Unit)? = null

    private val libraryFile = File(directory, "library.json")
    private val playlistsFile = File(directory, "playlists.json")
    private val batchSize = scanBatchSize.coerceAtLeast(1)

    private var rereadAll = false
    private var byId: Map<String, Track> = emptyMap()
    private val trackUris = mutableMapOf<String, String>()

    private var rescanRequested = false
    private var scanningJob: Job? = null

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        val loadedLib = Persistence.load<LibraryFile>(libraryFile)
        rereadAll = loadedLib != null && loadedLib.readerVersion != READER_VERSION

        val pickedRoots = loadedLib?.roots?.filter { it.kind == RootKind.PICKED }.orEmpty()
        val appRoot = LibraryRoot(
            id = LibraryRoot.APP_FOLDER_ID,
            name = APP_FOLDER_NAME,
            kind = RootKind.APP_FOLDER,
            treeUri = null,
        )
        val initialRoots = listOf(appRoot) + pickedRoots
        makeAppFolderVisible(appFolderDir)

        if (loadedLib != null) {
            trackUris.putAll(loadedLib.trackUris)
        }

        val initialAvailable = computeAvailableRootIds(initialRoots)
        val initialTracks = loadedLib?.tracks.orEmpty()
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
        if (root.kind == RootKind.APP_FOLDER) {
            return appFolderDir.exists() && appFolderDir.isDirectory && appFolderDir.canRead()
        }
        val uriStr = root.treeUri ?: return false
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return false
        if (uri.scheme == "file") {
            val file = uri.path?.let { File(it) } ?: return false
            return file.exists() && file.isDirectory && file.canRead()
        }
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
                ),
                playlistsFile,
            )
        }
    }

    private fun canonicalUriKey(uri: Uri): String {
        if (uri.scheme == "file" && uri.path != null) {
            return runCatching { File(uri.path!!).canonicalPath }.getOrElse { uri.toString() }
        }
        return uri.toString()
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
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
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

    suspend fun addPickedRoot(treeUri: Uri): Result<Unit> {
        val name = resolvePickedRootName(treeUri)
            ?: return Result.failure(LibraryException.NotAFolder)

        val candidateKey = canonicalUriKey(treeUri)
        val duplicate = _state.value.roots.any { root ->
            when (root.kind) {
                RootKind.APP_FOLDER -> canonicalUriKey(Uri.fromFile(appFolderDir)) == candidateKey
                RootKind.PICKED -> root.treeUri?.let {
                    runCatching { canonicalUriKey(Uri.parse(it)) }.getOrNull() == candidateKey
                } == true
            }
        }
        if (duplicate) {
            return Result.failure(LibraryException.DuplicateRoot)
        }

        takePersistableReadPermission(treeUri)
        val root = LibraryRoot(
            id = UUID.randomUUID().toString(),
            name = name,
            kind = RootKind.PICKED,
            treeUri = treeUri.toString(),
        )
        val current = _state.value
        _state.value = current.copy(
            roots = current.roots + root,
            availableRootIds = current.availableRootIds + root.id,
        )
        saveLibrary()
        rescan()
        return Result.success(Unit)
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

    fun removeRoot(rootId: String) {
        val current = _state.value
        val index = current.roots.indexOfFirst { it.id == rootId }
        if (index < 0) return
        val root = current.roots[index]
        if (root.kind == RootKind.APP_FOLDER) return

        releasePersistableReadPermission(root.treeUri)

        val updatedRoots = current.roots.toMutableList().apply { removeAt(index) }
        val removedIds = current.tracks.filter { it.rootId == rootId }.map { it.id }.toSet()
        val keptTracks = current.tracks.filter { it.rootId != rootId }
        for (id in removedIds) {
            trackUris.remove(id)
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

        for (root in currentRoots) {
            if (root.id !in availableNow) continue
            val existingTracks = currentTracks.filter { it.rootId == root.id }
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
                    kept.add(old)
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

            val track = makeTrack(
                root = item.root,
                file = item.file,
                metadata = metadata,
                artworkKey = artworkKey,
                dateAddedEpochMs = item.old?.dateAddedEpochMs ?: nowMs,
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

        val rootIds = _state.value.roots.map { it.id }.toSet()
        val finalTracks = kept.filter { it.rootId in rootIds }
        val finalIds = finalTracks.map { it.id }.toSet()
        val finalRemoved = removed.filter { it !in finalIds }.toSet()

        if (reread) rereadAll = false
        if (pending.isEmpty() && finalRemoved.isEmpty() && finalTracks.size == _state.value.tracks.size) {
            if (reread) saveLibrary()
            return
        }

        publish(finalTracks)
        for (id in finalRemoved) {
            trackUris.remove(id)
        }
        forget(finalRemoved)

        val keysToKeep = _state.value.tracks.mapNotNull { it.artworkKey }.toSet()
        withContext(Dispatchers.IO) {
            artwork.prune(keysToKeep)
        }
    }

    private fun scanRoot(root: LibraryRoot): List<ScannedFile>? {
        if (root.kind == RootKind.APP_FOLDER) {
            return FolderScanner.scan(appFolderDir)
        }
        val uriStr = root.treeUri ?: return null
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
        if (uri.scheme == "file") {
            val dir = uri.path?.let { File(it) } ?: return null
            return FolderScanner.scan(dir)
        }
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
        val uriStr = root.treeUri ?: return null
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
        if (uri.scheme == "file" && uri.path != null) {
            return FolderScanner.fileFor(File(uri.path!!), relativePath)
        }
        return null
    }

    private fun publish(list: List<Track>) {
        val current = _state.value
        val rootIds = current.roots.map { it.id }.toSet()
        val filtered = list.filter { it.rootId in rootIds }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
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

    fun uriFor(track: Track): Uri? {
        val current = _state.value
        if (track.rootId !in current.availableRootIds) return null
        val root = current.roots.firstOrNull { it.id == track.rootId } ?: return null
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

    suspend fun embeddedLyrics(track: Track): String? {
        if (!track.hasEmbeddedLyrics) return null
        val uri = uriFor(track) ?: return null
        return reader.read(uri, track.fileName).lyrics
    }

    suspend fun readSiblingLyrics(track: Track): ByteArray? = withContext(Dispatchers.IO) {
        val current = _state.value
        if (track.rootId !in current.availableRootIds) return@withContext null
        val root = current.roots.firstOrNull { it.id == track.rootId } ?: return@withContext null

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

    /**
     * Direct children of a folder: sorted subfolder names, then sorted direct tracks.
     */
    fun folderContents(rootId: String, path: String): Pair<List<String>, List<Track>> {
        val prefix = if (path.isEmpty()) "" else "$path/"
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
            direct.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.fileName })
    }

    /** Every song in a folder and its subfolders, in path order. */
    fun tracksUnder(rootId: String, path: String): List<Track> {
        val prefix = if (path.isEmpty()) "" else "$path/"
        return _state.value.tracks
            .filter { it.rootId == rootId && it.relativePath.startsWith(prefix) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.relativePath })
    }

    fun search(query: String): List<Track> {
        val needle = foldForSearch(query.trim())
        if (needle.isEmpty()) return emptyList()
        return _state.value.tracks.filter { track ->
            foldForSearch(track.title).contains(needle) ||
                foldForSearch(track.artist).contains(needle) ||
                foldForSearch(track.album).contains(needle)
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

    fun movePlaylistTrack(playlistId: String, fromIndex: Int, toIndex: Int) {
        updatePlaylist(playlistId) { pl ->
            if (fromIndex !in pl.trackIds.indices || toIndex !in pl.trackIds.indices || fromIndex == toIndex) {
                pl
            } else {
                val updated = pl.trackIds.toMutableList()
                val item = updated.removeAt(fromIndex)
                updated.add(toIndex, item)
                pl.copy(trackIds = updated)
            }
        }
    }

    private data class PendingScan(
        val root: LibraryRoot,
        val file: ScannedFile,
        val old: Track?,
    )

    companion object {
        private const val READER_VERSION = 2
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
