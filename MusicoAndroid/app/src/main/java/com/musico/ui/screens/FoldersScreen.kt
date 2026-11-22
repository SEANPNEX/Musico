package com.musico.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Language
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
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.documentfile.provider.DocumentFile
import com.musico.audio.PlayerUiState
import com.musico.cloud.CloudExportSummary
import com.musico.cloud.CloudExporter
import com.musico.cloud.CloudUpdateSummary
import com.musico.cloud.extractCloudSourceInput
import com.musico.i18n.AppStrings
import com.musico.i18n.LanguageStore
import com.musico.i18n.LocalStrings
import com.musico.model.LibraryRoot
import com.musico.model.PlayQueue
import com.musico.model.RootKind
import com.musico.model.Track
import com.musico.store.LibraryException
import com.musico.store.LibraryStore
import com.musico.store.LibraryUiState
import com.musico.store.PlayHistoryUiState
import com.musico.ui.components.ConfirmDialog
import com.musico.ui.components.EmptyState
import com.musico.ui.components.LanguageDialog
import com.musico.ui.components.PlayShuffleButtons
import com.musico.ui.components.SortOrderMenuButton
import com.musico.ui.components.TrackActionState
import com.musico.ui.components.TrackMenuItems
import com.musico.ui.components.TrackRow
import com.musico.ui.components.UpdateDialog
import com.musico.ui.components.dragHandle
import com.musico.ui.components.rememberDragReorderState
import com.musico.ui.theme.MusicoMotion
import com.musico.update.UpdateManager
import com.musico.update.UpdateUiState
import com.musico.update.hasAvailableUpdate
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
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

private fun displayRootName(root: LibraryRoot, strings: AppStrings): String {
    return if (root.kind == RootKind.APP_FOLDER && root.name == LibraryStore.APP_FOLDER_NAME) {
        strings.musicoFolderName
    } else {
        root.name
    }
}

private fun localizeLibraryError(err: Throwable, fallback: String, strings: AppStrings): String {
    return when (err) {
        is LibraryException.DuplicateRoot -> strings.errorDuplicateRoot
        is LibraryException.NotAFolder -> strings.errorNotAFolder
        is LibraryException.UnsupportedCloudUrl -> strings.errorUnsupportedCloudUrl
        else -> err.message ?: fallback
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    historyState: PlayHistoryUiState,
    playerState: PlayerUiState,
    queues: List<PlayQueue>,
    activeQueue: PlayQueue,
    trackActions: TrackActionState,
    updateManager: UpdateManager,
    cloudExporter: CloudExporter,
    languageStore: LanguageStore? = null,
    onPickFolder: ((Uri) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val updateState by updateManager.state.collectAsStateWithLifecycle()
    var currentFolder by remember { mutableStateOf<FolderRef?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var removingRoot by remember { mutableStateOf<LibraryRoot?>(null) }
    var removingSubfolder by remember { mutableStateOf<FolderRef?>(null) }
    var savingCloudTarget by remember { mutableStateOf<Pair<LibraryRoot, List<Track>>?>(null) }
    var updatingTrackedRoot by remember { mutableStateOf<LibraryRoot?>(null) }
    var showAddCloudDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
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
                    importError = localizeLibraryError(err, strings.couldNotAddFolderFallback, strings)
                }
            }
        }
    }

    fun launchRelocateFolder(rootId: String) {
        onPickFolder { uri ->
            scope.launch {
                libraryStore.relocateRoot(rootId, uri).onFailure { err ->
                    importError = localizeLibraryError(err, strings.couldNotRelocateFolderFallback, strings)
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
                    title = { Text(strings.foldersTitle) },
                    actions = {
                        if (languageStore != null) {
                            IconButton(onClick = { showLanguageDialog = true }) {
                                Icon(
                                    imageVector = Icons.Rounded.Language,
                                    contentDescription = strings.languageTitle,
                                )
                            }
                        }
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
                                    contentDescription = strings.checkForUpdates
                                )
                            }
                        }
                        IconButton(onClick = { showAddCloudDialog = true }) {
                            Icon(Icons.Rounded.CloudQueue, contentDescription = strings.addCloudFolder)
                        }
                        IconButton(
                            onClick = { scope.launch { libraryStore.rescan() } },
                            enabled = !libraryState.isScanning
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = strings.rescan)
                        }
                    }
                )
            } else {
                val title = if (activeRef.path.isEmpty()) {
                    currentRoot?.let { displayRootName(it, strings) } ?: strings.folderFallbackTitle
                } else {
                    activeRef.path.substringAfterLast('/')
                }
                val isRemovableRootDetail = activeRef.path.isEmpty() &&
                    currentRoot != null &&
                    currentRoot.kind != RootKind.APP_FOLDER
                val hasExcluded = activeRef.path.isEmpty() &&
                    (currentRoot?.excludedPaths?.isNotEmpty() == true)
                val isCloudRootDetail = currentRoot?.kind?.isCloud == true
                val isTrackedLocalDetail = activeRef.path.isEmpty() && currentRoot?.isCloudTrackedLocal == true
                val showDetailMenu = activeRef.path.isNotEmpty() || isRemovableRootDetail || hasExcluded || isCloudRootDetail || isTrackedLocalDetail

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
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.back)
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
                        val activeFolderKey = libraryStore.folderKey(activeRef.rootId, activeRef.path)
                        val folderSort = libraryState.folderSortOrders[activeFolderKey]
                            ?: libraryStore.folderSortOrder(activeRef.rootId, activeRef.path)
                        SortOrderMenuButton(
                            current = folderSort,
                            onSelect = { selected ->
                                libraryStore.setFolderSortOrder(activeRef.rootId, activeRef.path, selected)
                            },
                            contentDescription = strings.sortFolder,
                        )
                        IconButton(
                            onClick = { scope.launch { libraryStore.rescan() } },
                            enabled = !libraryState.isScanning
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = strings.rescan)
                        }
                        if (showDetailMenu) {
                            val rootForMenu = currentRoot
                            Box {
                                IconButton(onClick = { detailMenuExpanded = true }) {
                                    Icon(Icons.Rounded.MoreVert, contentDescription = strings.folderOptions)
                                }
                                DropdownMenu(
                                    expanded = detailMenuExpanded,
                                    onDismissRequest = { detailMenuExpanded = false }
                                ) {
                                    if (rootForMenu != null && rootForMenu.kind.isCloud) {
                                        val cloudTracks = libraryStore.tracksUnder(rootForMenu.id, activeRef.path)
                                        DropdownMenuItem(
                                            text = { Text(strings.saveToLocalFolderEllipsis) },
                                            leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                                            enabled = cloudTracks.isNotEmpty(),
                                            onClick = {
                                                detailMenuExpanded = false
                                                savingCloudTarget = rootForMenu to cloudTracks
                                            }
                                        )
                                    }
                                    if (isTrackedLocalDetail) {
                                        currentRoot?.let { trackedRoot ->
                                            DropdownMenuItem(
                                                text = { Text(strings.updateFromCloud) },
                                                leadingIcon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
                                                onClick = {
                                                    detailMenuExpanded = false
                                                    updatingTrackedRoot = trackedRoot
                                                }
                                            )
                                        }
                                    }
                                    if (activeRef.path.isNotEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text(strings.removeFolder) },
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
                                                text = { Text(strings.locateAgainEllipsis) },
                                                leadingIcon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                                                onClick = {
                                                    detailMenuExpanded = false
                                                    launchRelocateFolder(rootForMenu.id)
                                                }
                                            )
                                        }
                                        if (rootForMenu.excludedPaths.isNotEmpty()) {
                                            DropdownMenuItem(
                                                text = { Text(strings.restoreRemovedSubfolders(rootForMenu.excludedPaths.size)) },
                                                leadingIcon = { Icon(Icons.Rounded.Restore, contentDescription = null) },
                                                onClick = {
                                                    detailMenuExpanded = false
                                                    scope.launch { libraryStore.restoreExcludedFolders(rootForMenu.id) }
                                                }
                                            )
                                        }
                                        if (isRemovableRootDetail) {
                                            DropdownMenuItem(
                                                text = { Text(strings.removeFolder) },
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
                    text = { Text(strings.addFolder) }
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
                        historyState = historyState,
                        queues = queues,
                        activeQueue = activeQueue,
                        trackActions = trackActions,
                        onOpenRoot = { rootId -> currentFolder = FolderRef(rootId, "") },
                        onRelocateRoot = ::launchRelocateFolder,
                        onRequestRemove = { removingRoot = it },
                        onSaveCloudRoot = { root ->
                            val tracks = libraryStore.tracksUnder(root.id, "") { id ->
                                historyState.entries[id]?.count ?: 0
                            }
                            savingCloudTarget = root to tracks
                        },
                        onUpdateTrackedRoot = { root -> updatingTrackedRoot = root },
                        onRestoreExcluded = { rootId -> scope.launch { libraryStore.restoreExcludedFolders(rootId) } },
                        onAddFolder = ::launchAddFolder,
                        onAddCloudFolder = { showAddCloudDialog = true }
                    )
                } else {
                    val rootForRef = libraryState.roots.firstOrNull { it.id == ref.rootId }
                    FolderDetailContent(
                        ref = ref,
                        rootName = rootForRef?.let { displayRootName(it, strings) } ?: strings.folderFallbackTitle,
                        libraryStore = libraryStore,
                        libraryState = libraryState,
                        historyState = historyState,
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

    if (showLanguageDialog && languageStore != null) {
        val currentLanguage by languageStore.language.collectAsStateWithLifecycle()
        LanguageDialog(
            current = currentLanguage,
            onSelect = { languageStore.setLanguage(it) },
            onDismiss = { showLanguageDialog = false },
        )
    }

    val pendingSaveCloud = savingCloudTarget
    if (pendingSaveCloud != null) {
        SaveCloudPlaylistDialog(
            root = pendingSaveCloud.first,
            tracks = pendingSaveCloud.second,
            libraryStore = libraryStore,
            cloudExporter = cloudExporter,
            onPickFolder = onPickFolder,
            onFolderSavedWithoutLibrary = { folderName ->
                trackActions.playerController.onFolderAdded(folderName, emptyList())
            },
            onDismiss = { savingCloudTarget = null },
        )
    }

    val pendingUpdateTracked = updatingTrackedRoot
    if (pendingUpdateTracked != null) {
        UpdateTrackedFolderDialog(
            root = pendingUpdateTracked,
            libraryStore = libraryStore,
            cloudExporter = cloudExporter,
            onDismiss = { updatingTrackedRoot = null },
        )
    }

    val pendingRemove = removingRoot
    if (pendingRemove != null) {
        ConfirmDialog(
            title = strings.removeFolderConfirmTitle(displayRootName(pendingRemove, strings)),
            message = if (pendingRemove.kind.isCloud) {
                strings.removeCloudFolderConfirmMessage
            } else {
                strings.removeLocalFolderConfirmMessage
            },
            confirmLabel = strings.removeFolder,
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
            title = strings.removeFolderConfirmTitle(folderTitle),
            message = if (parentIsCloud) {
                strings.removeCloudFolderConfirmMessage
            } else {
                strings.removeLocalFolderConfirmMessage
            },
            confirmLabel = strings.removeFolder,
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
            title = { Text(strings.couldNotAddFolderTitle) },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = { importError = null }) {
                    Text(strings.ok)
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
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var cookie by remember { mutableStateOf("") }
    var isAdding by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isAdding) onDismiss() },
        icon = { Icon(Icons.Rounded.CloudQueue, contentDescription = null) },
        title = { Text(strings.addCloudFolder) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        errorText = null
                    },
                    label = { Text(strings.cloudUrlLabel) },
                    placeholder = { Text(strings.cloudUrlPlaceholder) },
                    supportingText = {
                        Text(strings.cloudUrlSupportingText)
                    },
                    singleLine = true,
                    enabled = !isAdding,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = cookie,
                    onValueChange = { cookie = it },
                    label = { Text(strings.cookieOptionalLabel) },
                    placeholder = { Text(strings.cookiePlaceholder) },
                    minLines = 2,
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
                enabled = extractCloudSourceInput(url).isNotEmpty() && !isAdding,
                onClick = {
                    isAdding = true
                    errorText = null
                    val cleanedUrl = extractCloudSourceInput(url)
                    scope.launch {
                        val result = onAdd(cleanedUrl, cookie.takeIf { it.isNotBlank() })
                        isAdding = false
                        result.onSuccess {
                            onDismiss()
                        }.onFailure { err ->
                            errorText = localizeLibraryError(err, strings.cloudPlaylistLoadErrorFallback, strings)
                        }
                    }
                }
            ) {
                Text(if (isAdding) strings.addingEllipsis else strings.add)
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isAdding,
                onClick = onDismiss
            ) {
                Text(strings.cancel)
            }
        }
    )
}

@Composable
private fun SaveCloudPlaylistDialog(
    root: LibraryRoot,
    tracks: List<Track>,
    libraryStore: LibraryStore,
    cloudExporter: CloudExporter,
    onPickFolder: ((Uri) -> Unit) -> Unit,
    onFolderSavedWithoutLibrary: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val defaultName = remember(root.id, root.name, strings.cloudPlaylistDefaultName) {
        CloudExporter.sanitizeFileName(root.name, strings.cloudPlaylistDefaultName)
    }
    var folderName by remember(root.id) { mutableStateOf(defaultName) }
    var pickedParentUri by remember(root.id) { mutableStateOf<Uri?>(null) }
    var addToLibrary by remember(root.id) { mutableStateOf(true) }
    var isExporting by remember(root.id) { mutableStateOf(false) }
    var progressDone by remember(root.id) { mutableIntStateOf(0) }
    var progressTotal by remember(root.id) { mutableIntStateOf(tracks.size) }
    var progressTitle by remember(root.id) { mutableStateOf("") }
    var summary by remember(root.id) { mutableStateOf<CloudExportSummary?>(null) }
    var libraryStatusText by remember(root.id) { mutableStateOf<String?>(null) }
    var errorText by remember(root.id) { mutableStateOf<String?>(null) }
    var exportJob by remember(root.id) { mutableStateOf<Job?>(null) }

    val pickedLocationLabel = remember(pickedParentUri, strings.noFolderSelected) {
        val uri = pickedParentUri ?: return@remember strings.noFolderSelected
        if (uri.scheme == "file" && uri.path != null) {
            File(uri.path!!).name.ifBlank { uri.path!! }
        } else {
            runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment
                ?: uri.toString()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isExporting) onDismiss() },
        icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
        title = {
            Text(
                when {
                    isExporting -> strings.savingPlaylistTitle
                    summary != null -> strings.saveCompleteTitle
                    else -> strings.saveToLocalFolderTitle
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (summary == null) {
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        label = { Text(strings.folderNameLabel) },
                        singleLine = true,
                        enabled = !isExporting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = strings.locationLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = pickedLocationLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (pickedParentUri != null) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                enabled = !isExporting,
                                onClick = {
                                    onPickFolder { uri ->
                                        pickedParentUri = uri
                                        errorText = null
                                    }
                                },
                            ) {
                                Text(strings.chooseFolder)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isExporting) {
                                addToLibrary = !addToLibrary
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = addToLibrary,
                            enabled = !isExporting,
                            onCheckedChange = { addToLibrary = it },
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = strings.addSavedFolderToLibrary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                if (isExporting) {
                    val fraction = if (progressTotal > 0) {
                        (progressDone.toFloat() / progressTotal.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val statusLine = if (progressTitle.isNotBlank()) {
                        strings.savingProgressWithTitle(progressDone, progressTotal, progressTitle)
                    } else {
                        strings.savingProgress(progressDone, progressTotal)
                    }
                    Text(
                        text = statusLine,
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val result = summary
                if (result != null) {
                    val summaryMsg = strings.savePlaylistSummary(result.savedCount, tracks.size, result.failedCount)
                    Text(
                        text = summaryMsg,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val libNote = libraryStatusText
                    if (libNote != null) {
                        Text(
                            text = libNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (result.errors.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            result.errors.take(5).forEach { msg ->
                                Text(
                                    text = "• $msg",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (result.errors.size > 5) {
                                Text(
                                    text = strings.andMoreErrors(result.errors.size - 5),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                val err = errorText
                if (err != null) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (summary != null) {
                Button(onClick = onDismiss) {
                    Text(strings.done)
                }
            } else if (!isExporting) {
                Button(
                    enabled = pickedParentUri != null && folderName.trim().isNotEmpty() && tracks.isNotEmpty(),
                    onClick = {
                        val parentUri = pickedParentUri ?: return@Button
                        isExporting = true
                        errorText = null
                        progressDone = 0
                        progressTotal = tracks.size
                        progressTitle = tracks.firstOrNull()?.title.orEmpty()
                        exportJob = scope.launch {
                            try {
                                val res = cloudExporter.exportPlaylist(
                                    tracks = tracks,
                                    root = root,
                                    parentTreeUri = parentUri,
                                    folderName = folderName,
                                    onProgress = { done, total, title ->
                                        progressDone = done
                                        progressTotal = total
                                        progressTitle = title
                                    },
                                )
                                if (addToLibrary && res.savedCount > 0) {
                                    val isDirectFileDir = parentUri.scheme == "file" && res.targetDir != null
                                    val savedUri = if (isDirectFileDir) {
                                        Uri.fromFile(res.targetDir!!)
                                    } else {
                                        parentUri
                                    }
                                    val trackedSub = if (isDirectFileDir) null else res.savedFolderName
                                    val addResult = libraryStore.addPickedRoot(
                                        treeUri = savedUri,
                                        cloudSourceRoot = root,
                                        trackedSubfolderName = trackedSub,
                                    )
                                    if (addResult.isSuccess) {
                                        libraryStatusText = strings.addedSavedFolderWithCloudTracking
                                    } else {
                                        val addErr = addResult.exceptionOrNull()
                                        libraryStatusText = if (addErr != null) {
                                            localizeLibraryError(addErr, strings.couldNotAddFolderToLibraryFallback, strings)
                                        } else {
                                            strings.couldNotAddFolderToLibraryFallback
                                        }
                                    }
                                } else if (!addToLibrary && res.savedCount > 0) {
                                    val savedName = res.savedFolderName?.trim()?.takeIf { it.isNotEmpty() }
                                    if (savedName != null) {
                                        onFolderSavedWithoutLibrary(savedName)
                                    }
                                }
                                summary = res
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (err: Exception) {
                                errorText = err.message ?: strings.couldNotSavePlaylistFallback
                            } finally {
                                isExporting = false
                                exportJob = null
                            }
                        }
                    },
                ) {
                    Text(strings.save)
                }
            }
        },
        dismissButton = {
            if (summary == null) {
                TextButton(
                    onClick = {
                        if (isExporting) {
                            exportJob?.cancel()
                            exportJob = null
                            isExporting = false
                        }
                        onDismiss()
                    },
                ) {
                    Text(strings.cancel)
                }
            }
        },
    )
}

@Composable
private fun UpdateTrackedFolderDialog(
    root: LibraryRoot,
    libraryStore: LibraryStore,
    cloudExporter: CloudExporter,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var isUpdating by remember { mutableStateOf(true) }
    var progressDone by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }
    var progressTitle by remember(root.id) { mutableStateOf("") }
    var summary by remember { mutableStateOf<CloudUpdateSummary?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var syncJob by remember { mutableStateOf<Job?>(null) }

    androidx.compose.runtime.LaunchedEffect(root.id) {
        val treeUri = root.treeUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (treeUri == null) {
            errorText = strings.savedFolderLocationUnavailable
            isUpdating = false
            return@LaunchedEffect
        }
        syncJob = scope.launch {
            try {
                val res = cloudExporter.updateLocalFolder(
                    root = root,
                    treeUri = treeUri,
                    fetchPlaylist = { url, cookie, kindHint ->
                        libraryStore.fetchCloudPlaylist(url, cookie, kindHint)
                    },
                    onProgress = { done, total, title ->
                        progressDone = done
                        progressTotal = total
                        progressTitle = title
                    },
                )
                libraryStore.markRootCloudSynced(root.id)
                if (res.addedCount > 0) {
                    libraryStore.rescan()
                }
                summary = res
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: Exception) {
                errorText = err.message ?: strings.couldNotUpdateFromCloudFallback
            } finally {
                isUpdating = false
                syncJob = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isUpdating) onDismiss() },
        icon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
        title = {
            Text(
                when {
                    isUpdating -> strings.updatingFolderTitle(displayRootName(root, strings))
                    summary != null -> strings.updateCompleteTitle
                    else -> strings.updateFromCloud
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                root.sourceUrl?.let { url ->
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (isUpdating) {
                    if (progressTotal > 0) {
                        val fraction = (progressDone.toFloat() / progressTotal.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = if (progressTitle.isNotBlank()) {
                                strings.checkingProgressWithTitle(progressDone, progressTotal, progressTitle)
                            } else {
                                strings.checkingProgress(progressDone, progressTotal)
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = strings.fetchingLatestCloudPlaylist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                val result = summary
                if (result != null) {
                    val summaryLine = if (result.addedCount > 0) {
                        strings.updateAddedSongsSummary(result.addedCount, result.skippedCount)
                    } else {
                        strings.alreadyUpToDateSummary(result.skippedCount)
                    }
                    Text(summaryLine, style = MaterialTheme.typography.bodyMedium)
                    if (result.failedCount > 0) {
                        Text(
                            text = strings.failedCountLine(result.failedCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        result.errors.take(5).forEach { msg ->
                            Text(
                                text = "• $msg",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                val err = errorText
                if (err != null) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (!isUpdating) {
                Button(onClick = onDismiss) {
                    Text(strings.done)
                }
            }
        },
        dismissButton = {
            if (isUpdating) {
                TextButton(
                    onClick = {
                        syncJob?.cancel()
                        syncJob = null
                        isUpdating = false
                        onDismiss()
                    },
                ) {
                    Text(strings.cancel)
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderRootList(
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    historyState: PlayHistoryUiState,
    queues: List<PlayQueue>,
    activeQueue: PlayQueue,
    trackActions: TrackActionState,
    onOpenRoot: (String) -> Unit,
    onRelocateRoot: (String) -> Unit,
    onRequestRemove: (LibraryRoot) -> Unit,
    onSaveCloudRoot: (LibraryRoot) -> Unit,
    onUpdateTrackedRoot: (LibraryRoot) -> Unit,
    onRestoreExcluded: (String) -> Unit,
    onAddFolder: () -> Unit,
    onAddCloudFolder: () -> Unit
) {
    val strings = LocalStrings.current
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
                            strings.readingScanProgress(libraryState.scanDone, libraryState.scanTotal)
                        } else {
                            strings.lookingForMusic
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
            val rootTrackIds = remember(
                libraryState.tracks,
                libraryState.folderSortOrders,
                libraryState.folderTrackOrders,
                historyState.entries,
                root.id,
            ) {
                libraryStore.tracksUnder(root.id, "") { id ->
                    historyState.entries[id]?.count ?: 0
                }.map { it.id }
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
                                    contentDescription = strings.removeFolder,
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
                            onSaveCloudRoot = { onSaveCloudRoot(root) },
                            onUpdateTrackedRoot = { onUpdateTrackedRoot(root) },
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
                        onSaveCloudRoot = {},
                        onUpdateTrackedRoot = { onUpdateTrackedRoot(root) },
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
                    Text(strings.addCloudFolder)
                }
            }
        }

        if (userRoots.isEmpty() && libraryState.tracks.isEmpty()) {
            item(key = "empty_hint_card") {
                EmptyState(
                    icon = Icons.Rounded.CreateNewFolder,
                    title = strings.addYourMusicFoldersTitle,
                    description = strings.addYourMusicFoldersDesc,
                    actionLabel = strings.addFolder,
                    onAction = onAddFolder
                )
            }
        } else {
            item(key = "footer_hint") {
                Text(
                    text = strings.foldersFooterHint,
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
    onSaveCloudRoot: () -> Unit,
    onUpdateTrackedRoot: () -> Unit,
    onRestoreExcluded: () -> Unit,
    onRemove: () -> Unit
) {
    val strings = LocalStrings.current
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
                        text = displayRootName(root, strings),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    val badgeLabel = when {
                        root.kind == RootKind.CLOUD_YOUTUBE -> strings.badgeYouTube
                        root.kind == RootKind.CLOUD_BILIBILI -> strings.badgeBilibili
                        root.isCloudTrackedLocal -> when (root.linkedCloudKind) {
                            RootKind.CLOUD_YOUTUBE -> strings.badgeYouTubeLocal
                            RootKind.CLOUD_BILIBILI -> strings.badgeBilibiliLocal
                            else -> strings.badgeCloudLocal
                        }
                        else -> null
                    }
                    if (badgeLabel != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = badgeLabel,
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
                    text = if (available) strings.songCount(songCount) else strings.folderUnavailable,
                    color = if (available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
            },
            trailingContent = {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = strings.folderOptions)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        if (root.kind.isCloud) {
                            DropdownMenuItem(
                                text = { Text(strings.saveToLocalFolderEllipsis) },
                                leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                                enabled = rootTrackIds.isNotEmpty(),
                                onClick = {
                                    menuExpanded = false
                                    onSaveCloudRoot()
                                }
                            )
                        }
                        if (root.isCloudTrackedLocal) {
                            DropdownMenuItem(
                                text = { Text(strings.updateFromCloud) },
                                leadingIcon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onUpdateTrackedRoot()
                                }
                            )
                        }
                        if (root.kind == RootKind.PICKED) {
                            DropdownMenuItem(
                                text = { Text(strings.locateAgainEllipsis) },
                                leadingIcon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onRelocate()
                                }
                            )
                        }
                        if (root.excludedPaths.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text(strings.restoreRemovedSubfolders(root.excludedPaths.size)) },
                                leadingIcon = { Icon(Icons.Rounded.Restore, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onRestoreExcluded()
                                }
                            )
                        }
                        if (isRemovable) {
                            DropdownMenuItem(
                                text = { Text(strings.removeFolder) },
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
                                onDismiss = { menuExpanded = false },
                                namedQueueTitle = root.name,
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
                        text = strings.folderPermissionLost,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(onClick = onRelocate) {
                        Text(strings.relocate)
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
    historyState: PlayHistoryUiState,
    playerState: PlayerUiState,
    queues: List<PlayQueue>,
    activeQueue: PlayQueue,
    trackActions: TrackActionState,
    onNavigate: (FolderRef) -> Unit,
    onRequestRemoveSubfolder: (FolderRef) -> Unit
) {
    val strings = LocalStrings.current
    val playCounts: (String) -> Int = { id -> historyState.entries[id]?.count ?: 0 }
    val (subfolders, directTracks) = remember(
        libraryState.tracks,
        libraryState.folderSortOrders,
        libraryState.folderTrackOrders,
        historyState.entries,
        ref,
    ) {
        libraryStore.folderContents(ref.rootId, ref.path, playCounts)
    }
    val allTracks = remember(
        libraryState.tracks,
        libraryState.folderSortOrders,
        libraryState.folderTrackOrders,
        historyState.entries,
        ref,
    ) {
        libraryStore.tracksUnder(ref.rootId, ref.path, playCounts)
    }
    val allIds = remember(allTracks) { allTracks.map { it.id } }
    val directIds = remember(directTracks) { directTracks.map { it.id } }
    val exactFolderName = remember(ref, rootName) {
        val norm = LibraryRoot.normalizeFolderPath(ref.path)
        if (norm.isEmpty()) rootName else norm.substringAfterLast('/')
    }

    val listState = rememberLazyListState()
    val headerCount = 1 + 1 + subfolders.size
    val dragState = rememberDragReorderState(
        items = directTracks,
        listState = listState,
        headerCount = headerCount,
        scopeKey = ref,
        keyOf = { it.id },
        onCommit = { reordered ->
            libraryStore.setFolderTrackOrder(
                rootId = ref.rootId,
                path = ref.path,
                orderedTrackIds = reordered.map { it.value.id },
            )
        },
    )

    LazyColumn(
        state = listState,
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
                    title = strings.noMusicHereTitle,
                    description = strings.noMusicHereDesc
                )
            }
        } else {
            item(key = "play_shuffle") {
                PlayShuffleButtons(
                    enabled = allIds.isNotEmpty(),
                    onPlay = {
                        trackActions.playerController.playInNamedQueue(
                            name = exactFolderName,
                            ids = allIds,
                            startAt = 0,
                            shuffle = false,
                        )
                    },
                    onShuffle = {
                        trackActions.playerController.playInNamedQueue(
                            name = exactFolderName,
                            ids = allIds,
                            startAt = Random.nextInt(allIds.size.coerceAtLeast(1)),
                            shuffle = true,
                        )
                    }
                )
            }
        }

        items(subfolders, key = { "dir:$it" }) { folderName ->
            val childPath = if (ref.path.isEmpty()) folderName else "${ref.path}/$folderName"
            val childRef = FolderRef(ref.rootId, childPath)
            val childIds = remember(
                libraryState.tracks,
                libraryState.folderSortOrders,
                libraryState.folderTrackOrders,
                historyState.entries,
                ref.rootId,
                childPath,
            ) {
                libraryStore.tracksUnder(ref.rootId, childPath, playCounts).map { it.id }
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
                                contentDescription = strings.removeFolder,
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
                        supportingContent = { Text(strings.songCount(childIds.size)) },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(Icons.Rounded.MoreVert, contentDescription = strings.folderOptions)
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(strings.removeFolder) },
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
                                            onDismiss = { menuExpanded = false },
                                            namedQueueTitle = folderName,
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }

        itemsIndexed(dragState.items, key = { _, item -> item.uid }) { index, dragItem ->
            val track = dragItem.value
            val isDragged = dragState.activeUid == dragItem.uid
            val rowBg = if (isDragged) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                Color.Transparent
            }
            val rowModifier = Modifier
                .animateItem(
                    placementSpec = if (dragState.isDragging || dragState.isSettling) {
                        null
                    } else {
                        spring(
                            stiffness = Spring.StiffnessMediumLow,
                            visibilityThreshold = IntOffset.VisibilityThreshold,
                        )
                    }
                )
                .zIndex(if (isDragged) 1f else 0f)
                .graphicsLayer { translationY = dragState.offsetFor(dragItem.uid) }

            TrackRow(
                track = track,
                artworkCache = libraryStore.artwork,
                isCurrent = playerState.currentTrack?.id == track.id,
                isPlaying = playerState.isPlaying,
                isAvailable = libraryStore.isAvailable(track),
                subtitle = if (track.isCloud) strings.displayArtist(track.artist) else track.fileName,
                backgroundColor = rowBg,
                onClick = {
                    val orderedIds = dragState.items.map { it.value.id }
                    trackActions.playerController.playInNamedQueue(
                        name = exactFolderName,
                        ids = orderedIds.ifEmpty { directIds },
                        startAt = index,
                        shuffle = false,
                    )
                },
                modifier = rowModifier,
                dragHandleModifier = Modifier.dragHandle(dragState, dragItem.uid),
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
