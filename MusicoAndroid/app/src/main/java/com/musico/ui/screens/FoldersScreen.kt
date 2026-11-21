package com.musico.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musico.audio.PlayerUiState
import com.musico.model.LibraryRoot
import com.musico.model.PlayQueue
import com.musico.model.RootKind
import com.musico.store.LibraryStore
import com.musico.store.LibraryUiState
import com.musico.ui.components.ConfirmDialog
import com.musico.ui.components.EmptyState
import com.musico.ui.components.PlayShuffleButtons
import com.musico.ui.components.TrackActionState
import com.musico.ui.components.TrackMenuItems
import com.musico.ui.components.TrackRow
import kotlinx.coroutines.launch
import kotlin.random.Random

data class FolderRef(
    val rootId: String,
    val path: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    playerState: PlayerUiState,
    queues: List<PlayQueue>,
    activeQueue: PlayQueue,
    trackActions: TrackActionState,
    onPickFolder: ((Uri) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var currentFolder by remember { mutableStateOf<FolderRef?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var removingRoot by remember { mutableStateOf<LibraryRoot?>(null) }

    BackHandler(enabled = currentFolder != null) {
        val ref = currentFolder ?: return@BackHandler
        currentFolder = if (ref.path.isEmpty()) {
            null
        } else {
            val parentPath = ref.path.substringBeforeLast('/', "")
            FolderRef(ref.rootId, parentPath)
        }
    }

    val activeRef = currentFolder
    val currentRoot = activeRef?.let { ref -> libraryState.roots.firstOrNull { it.id == ref.rootId } }

    fun launchAddFolder() {
        onPickFolder { uri ->
            scope.launch {
                libraryStore.addPickedRoot(uri).onFailure { err ->
                    importError = err.message ?: "Could not add that folder."
                }
            }
        }
    }

    fun launchRelocateFolder(rootId: String) {
        onPickFolder { uri ->
            scope.launch {
                libraryStore.relocateRoot(rootId, uri).onFailure { err ->
                    importError = err.message ?: "Could not relocate that folder."
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (activeRef == null) {
                TopAppBar(
                    title = { Text("Folders") },
                    actions = {
                        IconButton(
                            onClick = { scope.launch { libraryStore.rescan() } },
                            enabled = !libraryState.isScanning
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Rescan")
                        }
                    }
                )
            } else {
                val title = if (activeRef.path.isEmpty()) {
                    currentRoot?.name ?: "Folder"
                } else {
                    activeRef.path.substringAfterLast('/')
                }
                TopAppBar(
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                currentFolder = if (activeRef.path.isEmpty()) {
                                    null
                                } else {
                                    FolderRef(
                                        activeRef.rootId,
                                        activeRef.path.substringBeforeLast('/', "")
                                    )
                                }
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = { scope.launch { libraryStore.rescan() } },
                            enabled = !libraryState.isScanning
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Rescan")
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (activeRef == null) {
                ExtendedFloatingActionButton(
                    onClick = { launchAddFolder() },
                    icon = { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null) },
                    text = { Text("Add Folder") }
                )
            }
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = libraryState.isScanning,
            onRefresh = { scope.launch { libraryStore.rescan() } },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (activeRef == null) {
                FolderRootList(
                    libraryStore = libraryStore,
                    libraryState = libraryState,
                    queues = queues,
                    activeQueue = activeQueue,
                    trackActions = trackActions,
                    onOpenRoot = { rootId -> currentFolder = FolderRef(rootId, "") },
                    onRelocateRoot = ::launchRelocateFolder,
                    onRequestRemove = { removingRoot = it },
                    onAddFolder = ::launchAddFolder
                )
            } else {
                FolderDetailContent(
                    ref = activeRef,
                    rootName = currentRoot?.name ?: "Folder",
                    libraryStore = libraryStore,
                    libraryState = libraryState,
                    playerState = playerState,
                    queues = queues,
                    activeQueue = activeQueue,
                    trackActions = trackActions,
                    onNavigate = { nextRef -> currentFolder = nextRef }
                )
            }
        }
    }

    val pendingRemove = removingRoot
    if (pendingRemove != null) {
        ConfirmDialog(
            title = "Remove “${pendingRemove.name}” from your library?",
            message = "Its songs leave your library, playlists and queues. The files themselves are not deleted.",
            confirmLabel = "Remove from Library",
            onDismiss = { removingRoot = null },
            onConfirm = {
                libraryStore.removeRoot(pendingRemove.id)
                if (currentFolder?.rootId == pendingRemove.id) {
                    currentFolder = null
                }
            }
        )
    }

    val err = importError
    if (err != null) {
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Couldn’t Add Folder") },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = { importError = null }) {
                    Text("OK")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderRootList(
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    queues: List<PlayQueue>,
    activeQueue: PlayQueue,
    trackActions: TrackActionState,
    onOpenRoot: (String) -> Unit,
    onRelocateRoot: (String) -> Unit,
    onRequestRemove: (LibraryRoot) -> Unit,
    onAddFolder: () -> Unit
) {
    val pickedRoots = libraryState.roots.filter { it.kind == RootKind.PICKED }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        if (libraryState.isScanning) {
            item(key = "scan_progress") {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val message = if (libraryState.scanTotal > 0) {
                            "Reading ${libraryState.scanDone} of ${libraryState.scanTotal} songs…"
                        } else {
                            "Looking for music…"
                        }
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        if (libraryState.scanTotal > 0) {
                            LinearProgressIndicator(
                                progress = {
                                    (libraryState.scanDone.toFloat() / libraryState.scanTotal.toFloat())
                                        .coerceIn(0f, 1f)
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }

        items(libraryState.roots, key = { it.id }) { root ->
            val available = libraryStore.isAvailable(root)
            val count = remember(libraryState.tracks, root.id) {
                libraryState.tracks.count { it.rootId == root.id }
            }
            val rootTrackIds = remember(libraryState.tracks, root.id) {
                libraryStore.tracksUnder(root.id, "").map { it.id }
            }

            if (root.kind == RootKind.PICKED) {
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart) {
                            onRequestRemove(root)
                        }
                        false
                    }
                )
                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    backgroundContent = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = "Remove folder",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                ) {
                    RootItemRow(
                        root = root,
                        available = available,
                        songCount = count,
                        rootTrackIds = rootTrackIds,
                        queues = queues,
                        activeQueue = activeQueue,
                        trackActions = trackActions,
                        onOpen = { onOpenRoot(root.id) },
                        onRelocate = { onRelocateRoot(root.id) },
                        onRemove = { onRequestRemove(root) }
                    )
                }
            } else {
                RootItemRow(
                    root = root,
                    available = available,
                    songCount = count,
                    rootTrackIds = rootTrackIds,
                    queues = queues,
                    activeQueue = activeQueue,
                    trackActions = trackActions,
                    onOpen = { onOpenRoot(root.id) },
                    onRelocate = {},
                    onRemove = {}
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }

        if (pickedRoots.isEmpty() && libraryState.tracks.isEmpty()) {
            item(key = "empty_hint_card") {
                EmptyState(
                    icon = Icons.Rounded.CreateNewFolder,
                    title = "Add Your Music Folders",
                    description = "Pick folders on your device that hold your music, or copy songs into the Musico Folder and pull down to rescan.",
                    actionLabel = "Add Folder",
                    onAction = onAddFolder
                )
            }
        } else {
            item(key = "footer_hint") {
                Text(
                    text = "Add any folder on your device that holds music, or copy music into the Musico Folder and pull down to rescan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun RootItemRow(
    root: LibraryRoot,
    available: Boolean,
    songCount: Int,
    rootTrackIds: List<String>,
    queues: List<PlayQueue>,
    activeQueue: PlayQueue,
    trackActions: TrackActionState,
    onOpen: () -> Unit,
    onRelocate: () -> Unit,
    onRemove: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier.clickable(onClick = onOpen),
            leadingContent = {
                Icon(
                    imageVector = when {
                        !available -> Icons.Rounded.FolderOff
                        root.kind == RootKind.APP_FOLDER -> Icons.Rounded.LibraryMusic
                        else -> Icons.Rounded.Folder
                    },
                    contentDescription = null,
                    tint = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            },
            headlineContent = { Text(root.name) },
            supportingContent = {
                Text(
                    text = if (available) "$songCount songs" else "Unavailable — folder moved or disconnected",
                    color = if (available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
            },
            trailingContent = {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Folder options")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        if (root.kind == RootKind.PICKED) {
                            DropdownMenuItem(
                                text = { Text("Locate Again…") },
                                leadingIcon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onRelocate()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Remove from Library") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onRemove()
                                }
                            )
                            if (rootTrackIds.isNotEmpty()) {
                                HorizontalDivider()
                            }
                        }
                        if (rootTrackIds.isNotEmpty()) {
                            TrackMenuItems(
                                ids = rootTrackIds,
                                queues = queues,
                                activeQueueId = activeQueue.id,
                                actionState = trackActions,
                                onDismiss = { menuExpanded = false }
                            )
                        }
                    }
                }
            }
        )

        if (!available && root.kind == RootKind.PICKED) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Folder permission lost or moved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(onClick = onRelocate) {
                        Text("Relocate")
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderDetailContent(
    ref: FolderRef,
    rootName: String,
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    playerState: PlayerUiState,
    queues: List<PlayQueue>,
    activeQueue: PlayQueue,
    trackActions: TrackActionState,
    onNavigate: (FolderRef) -> Unit
) {
    val (subfolders, directTracks) = remember(libraryState.tracks, ref) {
        libraryStore.folderContents(ref.rootId, ref.path)
    }
    val allTracks = remember(libraryState.tracks, ref) {
        libraryStore.tracksUnder(ref.rootId, ref.path)
    }
    val allIds = remember(allTracks) { allTracks.map { it.id } }
    val directIds = remember(directTracks) { directTracks.map { it.id } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item(key = "breadcrumbs") {
            FolderBreadcrumbs(
                rootName = rootName,
                ref = ref,
                onNavigate = onNavigate
            )
        }

        if (allTracks.isEmpty()) {
            item(key = "empty_folder") {
                EmptyState(
                    icon = Icons.Rounded.Folder,
                    title = "No Music Here",
                    description = "Songs you add to this folder appear after a rescan."
                )
            }
        } else {
            item(key = "play_shuffle") {
                PlayShuffleButtons(
                    enabled = allIds.isNotEmpty(),
                    onPlay = {
                        if (activeQueue.shuffle) trackActions.playerController.toggleShuffle()
                        trackActions.playerController.play(allIds, startAt = 0)
                    },
                    onShuffle = {
                        if (!activeQueue.shuffle) trackActions.playerController.toggleShuffle()
                        trackActions.playerController.play(
                            allIds,
                            startAt = Random.nextInt(allIds.size.coerceAtLeast(1))
                        )
                    }
                )
            }
        }

        items(subfolders, key = { "dir:$it" }) { folderName ->
            val childPath = if (ref.path.isEmpty()) folderName else "${ref.path}/$folderName"
            val childIds = remember(libraryState.tracks, ref.rootId, childPath) {
                libraryStore.tracksUnder(ref.rootId, childPath).map { it.id }
            }
            var menuExpanded by remember { mutableStateOf(false) }

            ListItem(
                modifier = Modifier.clickable { onNavigate(FolderRef(ref.rootId, childPath)) },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                headlineContent = { Text(folderName) },
                supportingContent = { Text("${childIds.size} songs") },
                trailingContent = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "Folder options")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            TrackMenuItems(
                                ids = childIds,
                                queues = queues,
                                activeQueueId = activeQueue.id,
                                actionState = trackActions,
                                onDismiss = { menuExpanded = false }
                            )
                        }
                    }
                }
            )
        }

        itemsIndexed(directTracks, key = { _, t -> t.id }) { index, track ->
            TrackRow(
                track = track,
                artworkCache = libraryStore.artwork,
                isCurrent = playerState.currentTrack?.id == track.id,
                isPlaying = playerState.isPlaying,
                isAvailable = libraryStore.isAvailable(track),
                subtitle = track.fileName,
                onClick = { trackActions.playerController.play(directIds, startAt = index) },
                menuContent = { onDismiss ->
                    TrackMenuItems(
                        ids = listOf(track.id),
                        queues = queues,
                        activeQueueId = activeQueue.id,
                        actionState = trackActions,
                        onDismiss = onDismiss,
                        favouriteId = track.id,
                        isFavourite = track.id in libraryState.favourites
                    )
                }
            )
        }
    }
}

@Composable
private fun FolderBreadcrumbs(
    rootName: String,
    ref: FolderRef,
    onNavigate: (FolderRef) -> Unit
) {
    val segments = remember(ref.path) {
        if (ref.path.isEmpty()) emptyList() else ref.path.split('/')
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = rootName,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (segments.isEmpty()) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = if (segments.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable { onNavigate(FolderRef(ref.rootId, "")) }
                .padding(vertical = 4.dp)
        )
        segments.forEachIndexed { idx, segment ->
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            val targetPath = segments.take(idx + 1).joinToString("/")
            val isLast = idx == segments.lastIndex
            Text(
                text = segment,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable { onNavigate(FolderRef(ref.rootId, targetPath)) }
                    .padding(vertical = 4.dp)
            )
        }
    }
}
