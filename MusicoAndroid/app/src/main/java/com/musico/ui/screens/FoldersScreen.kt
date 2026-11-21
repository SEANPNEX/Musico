package com.musico.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.musico.ui.components.UpdateDialog
import com.musico.ui.theme.MusicoMotion
import com.musico.update.UpdateManager
import com.musico.update.UpdateUiState
import com.musico.update.hasAvailableUpdate
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.random.Random

data class FolderRef(
    val rootId: String,
    val path: String = ""
)

private fun folderDepth(ref: FolderRef?): Int {
    if (ref == null) return 0
    if (ref.path.isEmpty()) return 1
    return 1 + ref.path.split('/').count { it.isNotEmpty() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    playerState: PlayerUiState,
    queues: List<PlayQueue>,
    activeQueue: PlayQueue,
    trackActions: TrackActionState,
    updateManager: UpdateManager,
    onPickFolder: ((Uri) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val updateState by updateManager.state.collectAsStateWithLifecycle()
    var currentFolder by remember { mutableStateOf<FolderRef?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var removingRoot by remember { mutableStateOf<LibraryRoot?>(null) }
    var removingSubfolder by remember { mutableStateOf<FolderRef?>(null) }
    var showAddCloudDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var detailMenuExpanded by remember { mutableStateOf(false) }

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
                            onClick = {
                                showUpdateDialog = true
                                if (updateState is UpdateUiState.Idle ||
                                    updateState is UpdateUiState.UpToDate ||
                                    updateState is UpdateUiState.Error
                                ) {
                                    scope.launch { updateManager.checkForUpdates(silent = false) }
                                }
                            }
                        ) {
                            BadgedBox(
                                badge = {
                                    if (updateState.hasAvailableUpdate) {
                                        Badge()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SystemUpdate,
                                    contentDescription = "Check for Updates"
                                )
                            }
                        }
                        IconButton(onClick = { showAddCloudDialog = true }) {
                            Icon(Icons.Rounded.CloudQueue, contentDescription = "Add Cloud Folder")
                        }
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
                val isRemovableRootDetail = activeRef.path.isEmpty() &&
                    currentRoot != null &&
                    currentRoot.kind != RootKind.APP_FOLDER
                val hasExcluded = activeRef.path.isEmpty() &&
                    (currentRoot?.excludedPaths?.isNotEmpty() == true)
                val showDetailMenu = activeRef.path.isNotEmpty() || isRemovableRootDetail || hasExcluded

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
                        if (showDetailMenu) {
                            val rootForMenu = currentRoot
                            Box {
                                IconButton(onClick = { detailMenuExpanded = true }) {
                                    Icon(Icons.Rounded.MoreVert, contentDescription = "Folder options")
                                }
                                DropdownMenu(
                                    expanded = detailMenuExpanded,
                                    onDismissRequest = { detailMenuExpanded = false }
                                ) {
                                    if (activeRef.path.isNotEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text("Remove Folder") },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Rounded.Delete,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            },
                                            onClick = {
                                                detailMenuExpanded = false
                                                removingSubfolder = activeRef
                                            }
                                        )
                                    } else if (rootForMenu != null) {
                                        if (rootForMenu.kind == RootKind.PICKED) {
                                            DropdownMenuItem(
                                                text = { Text("Locate Again…") },
                                                leadingIcon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                                                onClick = {
                                                    detailMenuExpanded = false
                                                    launchRelocateFolder(rootForMenu.id)
                                                }
                                            )
                                        }
                                        if (rootForMenu.excludedPaths.isNotEmpty()) {
                                            DropdownMenuItem(
                                                text = { Text("Restore Removed Subfolders (${rootForMenu.excludedPaths.size})") },
                                                leadingIcon = { Icon(Icons.Rounded.Restore, contentDescription = null) },
                                                onClick = {
                                                    detailMenuExpanded = false
                                                    scope.launch { libraryStore.restoreExcludedFolders(rootForMenu.id) }
                                                }
                                            )
                                        }
                                        if (isRemovableRootDetail) {
                                            DropdownMenuItem(
                                                text = { Text("Remove Folder") },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Rounded.Delete,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                },
                                                onClick = {
                                                    detailMenuExpanded = false
                                                    removingRoot = rootForMenu
                                                }
                                            )
                                        }
                                    }
                                }
                            }
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
            AnimatedContent(
                targetState = activeRef,
                transitionSpec = {
                    MusicoMotion.sharedAxisXTransform(
                        forward = folderDepth(targetState) >= folderDepth(initialState)
                    )
                },
                label = "folders_navigation"
            ) { ref ->
                if (ref == null) {
                    FolderRootList(
                        libraryStore = libraryStore,
                        libraryState = libraryState,
                        queues = queues,
                        activeQueue = activeQueue,
                        trackActions = trackActions,
                        onOpenRoot = { rootId -> currentFolder = FolderRef(rootId, "") },
                        onRelocateRoot = ::launchRelocateFolder,
                        onRequestRemove = { removingRoot = it },
                        onRestoreExcluded = { rootId -> scope.launch { libraryStore.restoreExcludedFolders(rootId) } },
                        onAddFolder = ::launchAddFolder,
                        onAddCloudFolder = { showAddCloudDialog = true }
                    )
                } else {
                    val rootForRef = libraryState.roots.firstOrNull { it.id == ref.rootId }
                    FolderDetailContent(
                        ref = ref,
                        rootName = rootForRef?.name ?: "Folder",
                        libraryStore = libraryStore,
                        libraryState = libraryState,
                        playerState = playerState,
                        queues = queues,
                        activeQueue = activeQueue,
                        trackActions = trackActions,
                        onNavigate = { nextRef -> currentFolder = nextRef },
                        onRequestRemoveSubfolder = { removingSubfolder = it }
                    )
                }
            }
        }
    }

    if (showAddCloudDialog) {
        AddCloudFolderDialog(
            onDismiss = { showAddCloudDialog = false },
            onAdd = { url, cookie ->
                libraryStore.addCloudRoot(url, cookie)
            }
        )
    }

    if (showUpdateDialog) {
        UpdateDialog(
            updateManager = updateManager,
            onDismiss = { showUpdateDialog = false }
        )
    }

    val pendingRemove = removingRoot
    if (pendingRemove != null) {
        ConfirmDialog(
            title = "Remove “${pendingRemove.name}” from your library?",
            message = "Its songs leave your library, playlists and queues. The files themselves are not deleted.",
            confirmLabel = "Remove Folder",
            onDismiss = { removingRoot = null },
            onConfirm = {
                libraryStore.removeRoot(pendingRemove.id)
                if (currentFolder?.rootId == pendingRemove.id) {
                    currentFolder = null
                }
            }
        )
    }

    val pendingSubfolder = removingSubfolder
    if (pendingSubfolder != null) {
        val folderTitle = pendingSubfolder.path.substringAfterLast('/')
        val parentIsCloud = libraryState.roots.firstOrNull { it.id == pendingSubfolder.rootId }?.kind?.isCloud == true
        ConfirmDialog(
            title = "Remove “$folderTitle” from your library?",
            message = if (parentIsCloud) {
                "Its songs leave your library, playlists and queues."
            } else {
                "Its songs leave your library, playlists and queues. The files themselves are not deleted."
            },
            confirmLabel = "Remove Folder",
            onDismiss = { removingSubfolder = null },
            onConfirm = {
                libraryStore.removeSubfolder(pendingSubfolder.rootId, pendingSubfolder.path)
                val open = currentFolder
                if (open != null && open.rootId == pendingSubfolder.rootId &&
                    (open.path == pendingSubfolder.path || open.path.startsWith("${pendingSubfolder.path}/"))
                ) {
                    currentFolder = FolderRef(
                        pendingSubfolder.rootId,
                        pendingSubfolder.path.substringBeforeLast('/', "")
                    )
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

@Composable
private fun AddCloudFolderDialog(
    onDismiss: () -> Unit,
    onAdd: suspend (url: String, cookie: String?) -> Result<Unit>
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var cookie by remember { mutableStateOf("") }
    var isAdding by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isAdding) onDismiss() },
        icon = { Icon(Icons.Rounded.CloudQueue, contentDescription = null) },
        title = { Text("Add Cloud Folder") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        errorText = null
                    },
                    label = { Text("YouTube or Bilibili URL") },
                    placeholder = { Text("Playlist, collection, or video URL") },
                    supportingText = {
                        Text("Supports YouTube / YT Music playlists & videos, Bilibili favlists, collections, series, and BV / b23.tv links.")
                    },
                    singleLine = true,
                    enabled = !isAdding,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = cookie,
                    onValueChange = { cookie = it },
                    label = { Text("Cookie (optional)") },
                    placeholder = { Text("Only needed for private playlists") },
                    maxLines = 3,
                    enabled = !isAdding,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isAdding) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                val err = errorText
                if (err != null) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.trim().isNotEmpty() && !isAdding,
                onClick = {
                    isAdding = true
                    errorText = null
                    scope.launch {
                        val result = onAdd(url, cookie.takeIf { it.isNotBlank() })
                        isAdding = false
                        result.onSuccess {
                            onDismiss()
                        }.onFailure { err ->
                            errorText = err.message ?: "Could not load that cloud playlist."
                        }
                    }
                }
            ) {
                Text(if (isAdding) "Adding…" else "Add")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isAdding,
                onClick = onDismiss
            ) {
                Text("Cancel")
            }
        }
    )
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
    onRestoreExcluded: (String) -> Unit,
    onAddFolder: () -> Unit,
    onAddCloudFolder: () -> Unit
) {
    val userRoots = libraryState.roots.filter { it.kind != RootKind.APP_FOLDER }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        if (libraryState.isScanning) {
            item(key = "scan_progress") {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
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
            val isRemovable = root.kind != RootKind.APP_FOLDER

            Column(modifier = Modifier.animateItem()) {
                if (isRemovable) {
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
                                    contentDescription = "Remove Folder",
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
                            onRestoreExcluded = { onRestoreExcluded(root.id) },
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
                        onRestoreExcluded = { onRestoreExcluded(root.id) },
                        onRemove = {}
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }

        item(key = "cloud_action_row") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(onClick = onAddCloudFolder) {
                    Icon(
                        imageVector = Icons.Rounded.CloudQueue,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Add Cloud Folder")
                }
            }
        }

        if (userRoots.isEmpty() && libraryState.tracks.isEmpty()) {
            item(key = "empty_hint_card") {
                EmptyState(
                    icon = Icons.Rounded.CreateNewFolder,
                    title = "Add Your Music Folders",
                    description = "Pick folders on your device, add a YouTube or Bilibili playlist, or copy songs into the Musico Folder and pull down to rescan.",
                    actionLabel = "Add Folder",
                    onAction = onAddFolder
                )
            }
        } else {
            item(key = "footer_hint") {
                Text(
                    text = "Add local folders, link YouTube or Bilibili playlists, or copy music into the Musico Folder and pull down to rescan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
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
    onRestoreExcluded: () -> Unit,
    onRemove: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isRemovable = root.kind != RootKind.APP_FOLDER

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        ListItem(
            modifier = Modifier.clickable(onClick = onOpen),
            leadingContent = {
                Icon(
                    imageVector = when {
                        !available -> Icons.Rounded.FolderOff
                        root.kind == RootKind.APP_FOLDER -> Icons.Rounded.LibraryMusic
                        root.kind == RootKind.CLOUD_YOUTUBE -> Icons.Rounded.PlayCircle
                        root.kind == RootKind.CLOUD_BILIBILI -> Icons.Rounded.LiveTv
                        else -> Icons.Rounded.Folder
                    },
                    contentDescription = null,
                    tint = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            },
            headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = root.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (root.kind.isCloud) {
                        val chipLabel = if (root.kind == RootKind.CLOUD_YOUTUBE) "YouTube" else "Bilibili"
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = chipLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            },
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
                        }
                        if (root.excludedPaths.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Restore Removed Subfolders (${root.excludedPaths.size})") },
                                leadingIcon = { Icon(Icons.Rounded.Restore, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onRestoreExcluded()
                                }
                            )
                        }
                        if (isRemovable) {
                            DropdownMenuItem(
                                text = { Text("Remove Folder") },
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
                        }
                        if ((isRemovable || root.kind == RootKind.PICKED || root.excludedPaths.isNotEmpty()) && rootTrackIds.isNotEmpty()) {
                            HorizontalDivider()
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

@OptIn(ExperimentalMaterial3Api::class)
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
    onNavigate: (FolderRef) -> Unit,
    onRequestRemoveSubfolder: (FolderRef) -> Unit
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
            val childRef = FolderRef(ref.rootId, childPath)
            val childIds = remember(libraryState.tracks, ref.rootId, childPath) {
                libraryStore.tracksUnder(ref.rootId, childPath).map { it.id }
            }
            var menuExpanded by remember { mutableStateOf(false) }
            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value == SwipeToDismissBoxValue.EndToStart) {
                        onRequestRemoveSubfolder(childRef)
                    }
                    false
                }
            )

            Box(modifier = Modifier.animateItem()) {
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
                                contentDescription = "Remove Folder",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                ) {
                    ListItem(
                        modifier = Modifier.clickable { onNavigate(childRef) },
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
                                    DropdownMenuItem(
                                        text = { Text("Remove Folder") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Rounded.Delete,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            onRequestRemoveSubfolder(childRef)
                                        }
                                    )
                                    if (childIds.isNotEmpty()) {
                                        HorizontalDivider()
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
                        }
                    )
                }
            }
        }

        itemsIndexed(directTracks, key = { _, t -> t.id }) { index, track ->
            TrackRow(
                track = track,
                artworkCache = libraryStore.artwork,
                isCurrent = playerState.currentTrack?.id == track.id,
                isPlaying = playerState.isPlaying,
                isAvailable = libraryStore.isAvailable(track),
                subtitle = if (track.isCloud) track.artist else track.fileName,
                onClick = { trackActions.playerController.play(directIds, startAt = index) },
                modifier = Modifier.animateItem(),
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
