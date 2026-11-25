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
import androidx.compose.material.icons.rounded.AddToDrive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
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
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.musico.cloud.CloudErrorCode
import com.musico.cloud.CloudExporter
import com.musico.cloud.CloudSourceException
import com.musico.cloud.extractCloudSourceInput
import com.musico.extensions.CloudProviderAvailability
import com.musico.extensions.CloudProviderRegistry
import com.musico.extensions.CloudSourceProvider
import com.musico.extensions.ExtensionStore
import com.musico.i18n.AppStrings
import com.musico.i18n.LanguageStore
import com.musico.i18n.LocalStrings
import com.musico.model.CloudTrackingInfo
import com.musico.model.LibraryRoot
import com.musico.model.PlayQueue
import com.musico.model.RootKind
import com.musico.model.Track
import com.musico.store.CloudDownloadJob
import com.musico.store.CloudDownloadKind
import com.musico.store.CloudDownloadManager
import com.musico.store.CloudDownloadStatus
import com.musico.store.CloudSyncProgress
import com.musico.store.LibraryException
import com.musico.store.LibraryStore
import com.musico.store.LibraryUiState
import com.musico.store.PlayHistoryUiState
import com.musico.ui.ExtensionsScreen
import com.musico.ui.components.CloudTransferProgressSection
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
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

data class FolderRef(
    val rootId: String,
    val path: String = ""
)

private data class FolderUpdateTarget(
    val root: LibraryRoot,
    val subfolderPath: String = "",
)

private data class EditCloudSourceTarget(
    val root: LibraryRoot,
    val subfolderPath: String = "",
    val tracking: CloudTrackingInfo,
    val reopenUpdateOnSave: Boolean = false,
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
        is LibraryException.ExtensionUnavailable -> strings.errorExtensionUnavailable
        is CloudSourceException -> when (err.code) {
            CloudErrorCode.EXTENSION_UNAVAILABLE -> strings.errorExtensionUnavailable
            else -> err.message
        }
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
    cloudDownloadManager: CloudDownloadManager = remember(libraryStore, cloudExporter) {
        CloudDownloadManager(libraryStore, cloudExporter)
    },
    languageStore: LanguageStore? = null,
    extensionStore: ExtensionStore? = null,
    providerRegistry: CloudProviderRegistry = libraryStore.providerRegistry,
    isCompact: Boolean = false,
    onPickFolder: ((Uri) -> Unit) -> Unit,
    onPickExtensionFile: ((Uri) -> Unit) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val updateState by updateManager.state.collectAsStateWithLifecycle()
    val downloadJobs by cloudDownloadManager.jobs.collectAsStateWithLifecycle()
    val activeDownloadCount = remember(downloadJobs) { downloadJobs.count { it.isActive } }
    val activeProviders by providerRegistry.providers.collectAsStateWithLifecycle()
    val pendingExtUpdateCount = if (extensionStore != null) {
        extensionStore.extensions.collectAsStateWithLifecycle().value
        extensionStore.pendingUpdates.collectAsStateWithLifecycle().value.size
    } else {
        0
    }

    var showExtensionsScreen by remember { mutableStateOf(false) }
    if (showExtensionsScreen && extensionStore != null) {
        ExtensionsScreen(
            extensionStore = extensionStore,
            onPickExtensionFile = onPickExtensionFile,
            onBack = { showExtensionsScreen = false },
            modifier = modifier,
        )
        return
    }

    var currentFolder by remember { mutableStateOf<FolderRef?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var removingRoot by remember { mutableStateOf<LibraryRoot?>(null) }
    var removingSubfolder by remember { mutableStateOf<FolderRef?>(null) }
    var savingCloudTarget by remember { mutableStateOf<Pair<LibraryRoot, List<Track>>?>(null) }
    var updatingTarget by remember { mutableStateOf<FolderUpdateTarget?>(null) }
    var editingCloudSource by remember { mutableStateOf<EditCloudSourceTarget?>(null) }
    var showAddCloudDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showDownloadsSheet by remember { mutableStateOf(false) }
    var detailMenuExpanded by remember { mutableStateOf(false) }
    var rootOverflowExpanded by remember { mutableStateOf(false) }

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

    LaunchedEffect(activeRef, currentRoot?.kind, currentRoot?.resolvedProviderId) {
        if (activeRef != null && currentRoot != null && libraryStore.supportsTreeCrawling(currentRoot)) {
            libraryStore.prioritizeCloudFolder(activeRef.rootId, activeRef.path)
        }
    }

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

    fun openUpdateDialog() {
        showUpdateDialog = true
        if (updateState is UpdateUiState.Idle ||
            updateState is UpdateUiState.UpToDate ||
            updateState is UpdateUiState.Error
        ) {
            scope.launch { updateManager.checkForUpdates(silent = false) }
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (activeRef == null) {
                TopAppBar(
                    title = {
                        Text(
                            text = strings.foldersTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    actions = {
                        if (isCompact) {
                            if (activeDownloadCount > 0) {
                                DownloadsToolbarButton(
                                    activeCount = activeDownloadCount,
                                    onClick = { showDownloadsSheet = true },
                                )
                            }
                            IconButton(onClick = { showAddCloudDialog = true }) {
                                Icon(Icons.Rounded.CloudQueue, contentDescription = strings.addCloudFolder)
                            }
                            if (extensionStore != null) {
                                IconButton(onClick = { showExtensionsScreen = true }) {
                                    BadgedBox(
                                        badge = {
                                            if (pendingExtUpdateCount > 0) {
                                                Badge()
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Rounded.Extension, contentDescription = strings.extensionsTitle)
                                    }
                                }
                            }
                            Box {
                                IconButton(onClick = { rootOverflowExpanded = true }) {
                                    BadgedBox(
                                        badge = {
                                            if (updateState.hasAvailableUpdate) {
                                                Badge()
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.MoreVert,
                                            contentDescription = strings.moreOptions,
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = rootOverflowExpanded,
                                    onDismissRequest = { rootOverflowExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(strings.downloadsTitle) },
                                        leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                                        trailingIcon = if (activeDownloadCount > 0) {
                                            { Badge { Text("$activeDownloadCount") } }
                                        } else null,
                                        onClick = {
                                            rootOverflowExpanded = false
                                            showDownloadsSheet = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(strings.rescan) },
                                        leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                                        enabled = !libraryState.isScanning,
                                        onClick = {
                                            rootOverflowExpanded = false
                                            scope.launch { libraryStore.rescan() }
                                        },
                                    )
                                    if (languageStore != null) {
                                        DropdownMenuItem(
                                            text = { Text(strings.languageTitle) },
                                            leadingIcon = { Icon(Icons.Rounded.Language, contentDescription = null) },
                                            onClick = {
                                                rootOverflowExpanded = false
                                                showLanguageDialog = true
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(strings.checkForUpdates) },
                                        leadingIcon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
                                        trailingIcon = if (updateState.hasAvailableUpdate) {
                                            { Badge() }
                                        } else null,
                                        onClick = {
                                            rootOverflowExpanded = false
                                            openUpdateDialog()
                                        },
                                    )
                                }
                            }
                        } else {
                            DownloadsToolbarButton(
                                activeCount = activeDownloadCount,
                                onClick = { showDownloadsSheet = true },
                            )
                            if (languageStore != null) {
                                IconButton(onClick = { showLanguageDialog = true }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Language,
                                        contentDescription = strings.languageTitle,
                                    )
                                }
                            }
                            IconButton(onClick = { openUpdateDialog() }) {
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
                            if (extensionStore != null) {
                                IconButton(onClick = { showExtensionsScreen = true }) {
                                    BadgedBox(
                                        badge = {
                                            if (pendingExtUpdateCount > 0) {
                                                Badge()
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Rounded.Extension, contentDescription = strings.extensionsTitle)
                                    }
                                }
                            }
                            IconButton(
                                onClick = { scope.launch { libraryStore.rescan() } },
                                enabled = !libraryState.isScanning
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = strings.rescan)
                            }
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
                val currentTracking = currentRoot?.takeIf { !it.kind.isCloud }?.trackingFor(activeRef.path)
                val isTrackedLocalDetail = currentTracking != null
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
                        if (activeDownloadCount > 0) {
                            DownloadsToolbarButton(
                                activeCount = activeDownloadCount,
                                onClick = { showDownloadsSheet = true },
                            )
                        }
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
                                    if (rootForMenu != null && currentTracking != null) {
                                        DropdownMenuItem(
                                            text = { Text(strings.updateFromCloud) },
                                            leadingIcon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
                                            onClick = {
                                                detailMenuExpanded = false
                                                updatingTarget = FolderUpdateTarget(rootForMenu, activeRef.path)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(strings.editCloudSourceEllipsis) },
                                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                            onClick = {
                                                detailMenuExpanded = false
                                                editingCloudSource = EditCloudSourceTarget(
                                                    root = rootForMenu,
                                                    subfolderPath = activeRef.path,
                                                    tracking = currentTracking,
                                                )
                                            }
                                        )
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
                        extensionStore = extensionStore,
                        providerRegistry = providerRegistry,
                        onOpenRoot = { rootId -> currentFolder = FolderRef(rootId, "") },
                        onRelocateRoot = ::launchRelocateFolder,
                        onRequestRemove = { removingRoot = it },
                        onSaveCloudRoot = { root ->
                            val tracks = libraryStore.tracksUnder(root.id, "") { id ->
                                historyState.entries[id]?.count ?: 0
                            }
                            savingCloudTarget = root to tracks
                        },
                        onUpdateTrackedRoot = { root -> updatingTarget = FolderUpdateTarget(root, "") },
                        onEditTrackedRootSource = { root, tracking ->
                            editingCloudSource = EditCloudSourceTarget(
                                root = root,
                                subfolderPath = "",
                                tracking = tracking,
                            )
                        },
                        onRestoreExcluded = { rootId -> scope.launch { libraryStore.restoreExcludedFolders(rootId) } },
                        onOpenExtensions = if (extensionStore != null) {
                            { showExtensionsScreen = true }
                        } else null,
                        onAddFolder = ::launchAddFolder,
                        onAddCloudFolder = { showAddCloudDialog = true }
                    )
                } else {
                    val rootForRef = libraryState.roots.firstOrNull { it.id == ref.rootId }
                    FolderDetailContent(
                        ref = ref,
                        root = rootForRef,
                        rootName = rootForRef?.let { displayRootName(it, strings) } ?: strings.folderFallbackTitle,
                        libraryStore = libraryStore,
                        libraryState = libraryState,
                        historyState = historyState,
                        playerState = playerState,
                        queues = queues,
                        activeQueue = activeQueue,
                        trackActions = trackActions,
                        extensionStore = extensionStore,
                        providerRegistry = providerRegistry,
                        onNavigate = { nextRef -> currentFolder = nextRef },
                        onRequestRemoveSubfolder = { removingSubfolder = it },
                        onUpdateTrackedSubfolder = { r, subPath ->
                            updatingTarget = FolderUpdateTarget(r, subPath)
                        },
                        onEditTrackedSubfolderSource = { r, subPath, tracking ->
                            editingCloudSource = EditCloudSourceTarget(
                                root = r,
                                subfolderPath = subPath,
                                tracking = tracking,
                            )
                        },
                        onOpenExtensions = if (extensionStore != null) {
                            { showExtensionsScreen = true }
                        } else null,
                    )
                }
            }
        }
    }

    if (showAddCloudDialog) {
        AddCloudFolderDialog(
            providers = activeProviders.filter { it.supportsCollections },
            previewMatchedProvider = { raw -> providerRegistry.previewProvider(raw) },
            onDismiss = { showAddCloudDialog = false },
            onAdd = { url, cookie, providerIdHint ->
                libraryStore.addCloudRoot(url, cookie, providerIdHint)
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

    if (showDownloadsSheet) {
        DownloadsBottomSheet(
            cloudDownloadManager = cloudDownloadManager,
            strings = strings,
            onDismiss = { showDownloadsSheet = false },
        )
    }

    val pendingSaveCloud = savingCloudTarget
    if (pendingSaveCloud != null) {
        SaveCloudPlaylistDialog(
            root = pendingSaveCloud.first,
            tracks = pendingSaveCloud.second,
            cloudDownloadManager = cloudDownloadManager,
            onPickFolder = onPickFolder,
            onDismiss = { savingCloudTarget = null },
        )
    }

    val pendingUpdateTarget = updatingTarget
    if (pendingUpdateTarget != null) {
        val latestRoot = libraryState.roots.firstOrNull { it.id == pendingUpdateTarget.root.id } ?: pendingUpdateTarget.root
        val currentTracking = latestRoot.trackingFor(pendingUpdateTarget.subfolderPath)
        UpdateTrackedFolderDialog(
            root = latestRoot,
            subfolderPath = pendingUpdateTarget.subfolderPath,
            tracking = currentTracking,
            cloudDownloadManager = cloudDownloadManager,
            onEditSource = {
                val trackInfo = currentTracking ?: CloudTrackingInfo(
                    sourceUrl = latestRoot.sourceUrl.orEmpty(),
                    remoteId = latestRoot.remoteId,
                    cloudKind = latestRoot.effectiveCloudKind ?: RootKind.CLOUD_YOUTUBE,
                    cookie = latestRoot.cookie,
                    lastSyncedEpochMs = latestRoot.lastSyncedEpochMs,
                )
                updatingTarget = null
                editingCloudSource = EditCloudSourceTarget(
                    root = latestRoot,
                    subfolderPath = pendingUpdateTarget.subfolderPath,
                    tracking = trackInfo,
                    reopenUpdateOnSave = true,
                )
            },
            onDismiss = { updatingTarget = null },
        )
    }

    val pendingEditCloud = editingCloudSource
    if (pendingEditCloud != null) {
        EditCloudSourceDialog(
            target = pendingEditCloud,
            libraryStore = libraryStore,
            onDismiss = { editingCloudSource = null },
            onSaved = {
                val updatedRoot = libraryStore.state.value.roots.firstOrNull { it.id == pendingEditCloud.root.id }
                    ?: pendingEditCloud.root
                if (pendingEditCloud.reopenUpdateOnSave) {
                    updatingTarget = FolderUpdateTarget(updatedRoot, pendingEditCloud.subfolderPath)
                }
            },
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
    providers: List<CloudSourceProvider>,
    previewMatchedProvider: (String) -> CloudSourceProvider?,
    onDismiss: () -> Unit,
    onAdd: suspend (url: String, cookie: String?, providerIdHint: String?) -> Result<Unit>
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var cookie by remember { mutableStateOf("") }
    var selectedProviderId by remember { mutableStateOf<String?>(null) }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var isAdding by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val selectedProvider = remember(providers, selectedProviderId) {
        selectedProviderId?.let { id -> providers.firstOrNull { it.providerId == id } }
    }
    val detectedProvider = remember(url, selectedProviderId, providers) {
        if (selectedProviderId == null) previewMatchedProvider(url) else null
    }
    val effectiveProvider = selectedProvider ?: detectedProvider
    val showCookieField = effectiveProvider?.supportsCookie ?: true

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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = strings.cloudProviderLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box {
                        OutlinedButton(
                            enabled = !isAdding,
                            onClick = { providerMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = selectedProvider?.displayName ?: strings.providerAutoDetect,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        DropdownMenu(
                            expanded = providerMenuExpanded,
                            onDismissRequest = { providerMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(strings.providerAutoDetect) },
                                leadingIcon = if (selectedProviderId == null) {
                                    { Icon(Icons.Rounded.Check, contentDescription = null) }
                                } else null,
                                onClick = {
                                    selectedProviderId = null
                                    providerMenuExpanded = false
                                    errorText = null
                                },
                            )
                            providers.forEach { provider ->
                                DropdownMenuItem(
                                    text = { Text(provider.displayName) },
                                    leadingIcon = if (selectedProviderId == provider.providerId) {
                                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                                    } else null,
                                    onClick = {
                                        selectedProviderId = provider.providerId
                                        providerMenuExpanded = false
                                        errorText = null
                                    },
                                )
                            }
                        }
                    }
                    if (detectedProvider != null) {
                        Text(
                            text = strings.detectedProviderHint(detectedProvider.displayName),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

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
                if (showCookieField) {
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
                }
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
                        val effectiveCookie = if (showCookieField) cookie.takeIf { it.isNotBlank() } else null
                        val result = onAdd(cleanedUrl, effectiveCookie, selectedProviderId)
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
private fun DownloadsToolbarButton(
    activeCount: Int,
    onClick: () -> Unit,
) {
    val strings = LocalStrings.current
    IconButton(onClick = onClick) {
        BadgedBox(
            badge = {
                if (activeCount > 0) {
                    Badge { Text("$activeCount") }
                }
            }
        ) {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = strings.downloadsTitle,
                tint = if (activeCount > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun CloudSourceBadge(
    cloudKind: RootKind?,
    isLocal: Boolean,
    providerId: String? = null,
    customBadgeLabel: String? = null,
) {
    val strings = LocalStrings.current
    val customLabel = customBadgeLabel?.trim()?.takeIf { it.isNotEmpty() }
        ?: providerId?.removePrefix("ext.")?.takeIf { it.isNotEmpty() && providerId.startsWith("ext.") }
    val label = when {
        !isLocal -> when (cloudKind) {
            RootKind.CLOUD_YOUTUBE -> strings.badgeYouTube
            RootKind.CLOUD_BILIBILI -> strings.badgeBilibili
            RootKind.CLOUD_GOOGLE_DRIVE -> strings.badgeGoogleDrive
            RootKind.CLOUD_EXTENSION -> customLabel ?: strings.badgeExtension
            else -> null
        }
        else -> when (cloudKind) {
            RootKind.CLOUD_YOUTUBE -> strings.badgeYouTubeLocal
            RootKind.CLOUD_BILIBILI -> strings.badgeBilibiliLocal
            RootKind.CLOUD_GOOGLE_DRIVE -> strings.badgeGoogleDriveLocal
            RootKind.CLOUD_EXTENSION -> strings.badgeCustomLocal(customLabel ?: strings.badgeExtension)
            else -> customLabel?.let { strings.badgeCustomLocal(it) } ?: strings.badgeCloudLocal
        }
    } ?: return

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ExtensionUnavailableBanner(
    availability: CloudProviderAvailability,
    providerId: String?,
    onOpenExtensions: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val message = when (availability) {
        CloudProviderAvailability.DISABLED -> strings.extensionDisabledBanner
        CloudProviderAvailability.MISSING -> strings.extensionMissingBanner(providerId.orEmpty())
        CloudProviderAvailability.AVAILABLE -> return
    }
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            if (onOpenExtensions != null) {
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onOpenExtensions) {
                    Text(strings.manageExtensions)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsBottomSheet(
    cloudDownloadManager: CloudDownloadManager,
    strings: AppStrings,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val jobs by cloudDownloadManager.jobs.collectAsStateWithLifecycle()
    val hasFinished = remember(jobs) { jobs.any { !it.isActive } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = strings.downloadsTitle,
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(
                    enabled = hasFinished,
                    onClick = { cloudDownloadManager.clearFinished() },
                ) {
                    Text(strings.clearFinished)
                }
            }
            HorizontalDivider()

            if (jobs.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.Download,
                    title = strings.noDownloadsTitle,
                    description = strings.noDownloadsDesc,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(jobs, key = { it.id }) { job ->
                        DownloadJobCard(
                            job = job,
                            strings = strings,
                            onCancel = { cloudDownloadManager.cancel(job.id) },
                            onDismiss = { cloudDownloadManager.dismiss(job.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadJobCard(
    job: CloudDownloadJob,
    strings: AppStrings,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val kindLabel = when (job.kind) {
        CloudDownloadKind.PLAYLIST_EXPORT -> strings.downloadKindPlaylistExport
        CloudDownloadKind.TRACK_EXPORT -> strings.downloadKindTrackExport
        CloudDownloadKind.FOLDER_UPDATE -> strings.downloadKindFolderUpdate
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = job.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = kindLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (job.isActive) {
                    TextButton(onClick = onCancel) {
                        Text(strings.cancel)
                    }
                } else {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = strings.dismiss)
                    }
                }
            }

            if (job.isActive) {
                val activeStatus = when (job.kind) {
                    CloudDownloadKind.PLAYLIST_EXPORT ->
                        if (job.currentTrackTitle.isNotBlank()) {
                            strings.savingProgressWithTitle(job.completedTracks, job.totalTracks, job.currentTrackTitle)
                        } else {
                            strings.savingProgress(job.completedTracks, job.totalTracks)
                        }
                    CloudDownloadKind.TRACK_EXPORT -> strings.downloadingTrack(job.title)
                    CloudDownloadKind.FOLDER_UPDATE ->
                        if (job.totalTracks > 0) {
                            if (job.currentTrackTitle.isNotBlank()) {
                                strings.checkingProgressWithTitle(job.completedTracks, job.totalTracks, job.currentTrackTitle)
                            } else {
                                strings.checkingProgress(job.completedTracks, job.totalTracks)
                            }
                        } else {
                            strings.fetchingLatestCloudPlaylist
                        }
                }
                CloudTransferProgressSection(job = job, statusLine = activeStatus)
            } else {
                val summaryText = when (job.status) {
                    CloudDownloadStatus.CANCELLED -> strings.downloadStatusCancelled
                    CloudDownloadStatus.FAILED ->
                        if (job.locationUnavailable) strings.savedFolderLocationUnavailable
                        else (job.errorMessage ?: strings.downloadStatusFailed)
                    CloudDownloadStatus.COMPLETED -> when (job.kind) {
                        CloudDownloadKind.PLAYLIST_EXPORT -> job.exportSummary?.let { res ->
                            strings.savePlaylistSummary(res.savedCount, job.totalTracks, res.failedCount)
                        } ?: strings.downloadStatusCompleted
                        CloudDownloadKind.TRACK_EXPORT ->
                            job.savedFileName?.let { strings.savedAsFile(it) } ?: strings.downloadStatusCompleted
                        CloudDownloadKind.FOLDER_UPDATE -> job.updateSummary?.let { res ->
                            if (res.addedCount > 0) {
                                strings.updateAddedSongsSummary(res.addedCount, res.skippedCount)
                            } else {
                                strings.alreadyUpToDateSummary(res.skippedCount)
                            }
                        } ?: strings.downloadStatusCompleted
                    }
                    CloudDownloadStatus.RUNNING -> ""
                }
                val color = if (job.status == CloudDownloadStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SaveCloudPlaylistDialog(
    root: LibraryRoot,
    tracks: List<Track>,
    cloudDownloadManager: CloudDownloadManager,
    onPickFolder: ((Uri) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val jobs by cloudDownloadManager.jobs.collectAsStateWithLifecycle()
    val defaultName = remember(root.id, root.name, strings.cloudPlaylistDefaultName) {
        CloudExporter.sanitizeFileName(root.name, strings.cloudPlaylistDefaultName)
    }
    var folderName by remember(root.id) { mutableStateOf(defaultName) }
    var pickedParentUri by remember(root.id) { mutableStateOf<Uri?>(null) }
    var addToLibrary by remember(root.id) { mutableStateOf(true) }
    var jobId by remember(root.id) { mutableStateOf<String?>(null) }

    val activeJob = remember(jobs, jobId) {
        jobId?.let { id -> jobs.firstOrNull { it.id == id } }
    }
    val isExporting = activeJob?.isActive == true
    val summary = activeJob?.exportSummary
    val isFailed = activeJob?.status == CloudDownloadStatus.FAILED
    val isCancelled = activeJob?.status == CloudDownloadStatus.CANCELLED

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
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
        title = {
            Text(
                when {
                    isExporting -> strings.savingPlaylistTitle
                    summary != null -> strings.saveCompleteTitle
                    isFailed -> strings.saveFailedTitle
                    isCancelled -> strings.downloadStatusCancelled
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
                if (activeJob == null) {
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        label = { Text(strings.folderNameLabel) },
                        singleLine = true,
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
                                onClick = {
                                    onPickFolder { uri ->
                                        pickedParentUri = uri
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
                            .clickable { addToLibrary = !addToLibrary },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = addToLibrary,
                            onCheckedChange = { addToLibrary = it },
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = strings.addSavedFolderToLibrary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else if (isExporting) {
                    val statusLine = if (activeJob.currentTrackTitle.isNotBlank()) {
                        strings.savingProgressWithTitle(
                            activeJob.completedTracks,
                            activeJob.totalTracks,
                            activeJob.currentTrackTitle,
                        )
                    } else {
                        strings.savingProgress(activeJob.completedTracks, activeJob.totalTracks)
                    }
                    CloudTransferProgressSection(job = activeJob, statusLine = statusLine)
                }

                if (summary != null) {
                    Text(
                        text = strings.savePlaylistSummary(summary.savedCount, tracks.size, summary.failedCount),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (activeJob.libraryAddAttempted) {
                        val libNote = if (activeJob.libraryUpdated) {
                            strings.addedSavedFolderWithCloudTracking
                        } else {
                            val addErr = activeJob.libraryAddError
                            if (addErr != null) {
                                localizeLibraryError(addErr, strings.couldNotAddFolderToLibraryFallback, strings)
                            } else {
                                strings.couldNotAddFolderToLibraryFallback
                            }
                        }
                        Text(
                            text = libNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (summary.errors.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            summary.errors.take(5).forEach { msg ->
                                Text(
                                    text = "• $msg",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (summary.errors.size > 5) {
                                Text(
                                    text = strings.andMoreErrors(summary.errors.size - 5),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (isFailed) {
                    Text(
                        text = activeJob?.errorMessage ?: strings.couldNotSavePlaylistFallback,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (isCancelled) {
                    Text(
                        text = strings.downloadStatusCancelled,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            when {
                activeJob == null -> {
                    Button(
                        enabled = pickedParentUri != null && folderName.trim().isNotEmpty() && tracks.isNotEmpty(),
                        onClick = {
                            val parentUri = pickedParentUri ?: return@Button
                            jobId = cloudDownloadManager.startPlaylistExport(
                                tracks = tracks,
                                root = root,
                                parentUri = parentUri,
                                folderName = folderName,
                                addToLibrary = addToLibrary,
                            )
                        },
                    ) {
                        Text(strings.save)
                    }
                }
                isExporting -> {
                    TextButton(onClick = onDismiss) {
                        Text(strings.runInBackground)
                    }
                }
                else -> {
                    Button(onClick = onDismiss) {
                        Text(strings.done)
                    }
                }
            }
        },
        dismissButton = {
            if (activeJob == null || isExporting) {
                TextButton(
                    onClick = {
                        jobId?.let(cloudDownloadManager::cancel)
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
    subfolderPath: String,
    tracking: CloudTrackingInfo?,
    cloudDownloadManager: CloudDownloadManager,
    onEditSource: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val jobs by cloudDownloadManager.jobs.collectAsStateWithLifecycle()
    val folderTitle = remember(root, subfolderPath, strings) {
        val norm = LibraryRoot.normalizeFolderPath(subfolderPath)
        if (norm.isEmpty()) displayRootName(root, strings) else norm.substringAfterLast('/')
    }
    val trackingUrl = tracking?.sourceUrl ?: root.sourceUrl
    var jobId by remember(root.id, subfolderPath, trackingUrl) { mutableStateOf<String?>(null) }

    LaunchedEffect(root.id, subfolderPath, trackingUrl) {
        jobId = cloudDownloadManager.startFolderUpdate(
            root = root,
            subfolderPath = subfolderPath,
            sourceUrlOverride = trackingUrl,
            cookieOverride = tracking?.cookie,
        )
    }

    val activeJob = remember(jobs, jobId, folderTitle, trackingUrl) {
        jobId?.let { id -> jobs.firstOrNull { it.id == id } } ?: CloudDownloadJob(
            id = jobId.orEmpty(),
            kind = CloudDownloadKind.FOLDER_UPDATE,
            title = folderTitle,
            subtitle = trackingUrl,
        )
    }
    val isUpdating = activeJob.isActive
    val summary = activeJob.updateSummary
    val isFailed = activeJob.status == CloudDownloadStatus.FAILED
    val isCancelled = activeJob.status == CloudDownloadStatus.CANCELLED

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
        title = {
            Text(
                when {
                    isUpdating -> strings.updatingFolderTitle(folderTitle)
                    summary != null -> strings.updateCompleteTitle
                    isCancelled -> strings.downloadStatusCancelled
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = trackingUrl ?: strings.notAvailable,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            if (isUpdating) {
                                jobId?.let(cloudDownloadManager::cancel)
                            }
                            onEditSource()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(strings.edit)
                    }
                }

                if (isUpdating) {
                    val statusLine = if (activeJob.totalTracks > 0) {
                        if (activeJob.currentTrackTitle.isNotBlank()) {
                            strings.checkingProgressWithTitle(
                                activeJob.completedTracks,
                                activeJob.totalTracks,
                                activeJob.currentTrackTitle,
                            )
                        } else {
                            strings.checkingProgress(activeJob.completedTracks, activeJob.totalTracks)
                        }
                    } else {
                        strings.fetchingLatestCloudPlaylist
                    }
                    CloudTransferProgressSection(job = activeJob, statusLine = statusLine)
                }

                if (summary != null) {
                    val summaryLine = if (summary.addedCount > 0) {
                        strings.updateAddedSongsSummary(summary.addedCount, summary.skippedCount)
                    } else {
                        strings.alreadyUpToDateSummary(summary.skippedCount)
                    }
                    Text(summaryLine, style = MaterialTheme.typography.bodyMedium)
                    if (summary.failedCount > 0) {
                        Text(
                            text = strings.failedCountLine(summary.failedCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        summary.errors.take(5).forEach { msg ->
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

                if (isFailed) {
                    val errMessage = if (activeJob.locationUnavailable) {
                        strings.savedFolderLocationUnavailable
                    } else {
                        activeJob.errorMessage ?: strings.couldNotUpdateFromCloudFallback
                    }
                    Text(
                        text = errMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (isCancelled) {
                    Text(
                        text = strings.downloadStatusCancelled,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (isUpdating) {
                TextButton(onClick = onDismiss) {
                    Text(strings.runInBackground)
                }
            } else {
                Button(onClick = onDismiss) {
                    Text(strings.done)
                }
            }
        },
        dismissButton = {
            if (isUpdating) {
                TextButton(
                    onClick = {
                        jobId?.let(cloudDownloadManager::cancel)
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
private fun EditCloudSourceDialog(
    target: EditCloudSourceTarget,
    libraryStore: LibraryStore,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var url by remember(target) { mutableStateOf(target.tracking.sourceUrl) }
    var cookie by remember(target) { mutableStateOf(target.tracking.cookie.orEmpty()) }
    var isSaving by remember(target) { mutableStateOf(false) }
    var errorText by remember(target) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
        title = { Text(strings.editCloudSourceTitle) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        errorText = null
                    },
                    label = { Text(strings.cloudUrlLabel) },
                    placeholder = { Text(strings.cloudUrlPlaceholder) },
                    supportingText = { Text(strings.cloudUrlSupportingText) },
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cookie,
                    onValueChange = { cookie = it },
                    label = { Text(strings.cookieOptionalLabel) },
                    placeholder = { Text(strings.cookiePlaceholder) },
                    minLines = 2,
                    maxLines = 3,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isSaving) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
            TextButton(
                enabled = extractCloudSourceInput(url).isNotEmpty() && !isSaving,
                onClick = {
                    isSaving = true
                    errorText = null
                    scope.launch {
                        val res = libraryStore.updateCloudTrackingAddress(
                            rootId = target.root.id,
                            path = target.subfolderPath,
                            newSourceUrl = url,
                            cookie = cookie.takeIf { it.isNotBlank() },
                        )
                        isSaving = false
                        res.onSuccess {
                            onDismiss()
                            onSaved()
                        }.onFailure { err ->
                            errorText = localizeLibraryError(err, strings.couldNotUpdateFromCloudFallback, strings)
                        }
                    }
                },
            ) {
                Text(strings.save)
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSaving,
                onClick = onDismiss,
            ) {
                Text(strings.cancel)
            }
        },
    )
}

private fun resolveCustomBadgeLabel(
    providerId: String?,
    extensionStore: ExtensionStore?,
    providerRegistry: CloudProviderRegistry,
): String? {
    if (providerId.isNullOrBlank()) return null
    return extensionStore?.badgeLabelForProvider(providerId)
        ?: providerRegistry.providerForId(providerId)?.badgeLabel
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
    extensionStore: ExtensionStore?,
    providerRegistry: CloudProviderRegistry,
    onOpenRoot: (String) -> Unit,
    onRelocateRoot: (String) -> Unit,
    onRequestRemove: (LibraryRoot) -> Unit,
    onSaveCloudRoot: (LibraryRoot) -> Unit,
    onUpdateTrackedRoot: (LibraryRoot) -> Unit,
    onEditTrackedRootSource: (LibraryRoot, CloudTrackingInfo) -> Unit,
    onRestoreExcluded: (String) -> Unit,
    onOpenExtensions: (() -> Unit)?,
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
            val availability = libraryStore.cloudProviderAvailability(root)
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
            val rootTracking = remember(root) { root.trackingFor("") }
            val customBadgeLabel = resolveCustomBadgeLabel(
                providerId = root.resolvedProviderId ?: rootTracking?.resolvedProviderId,
                extensionStore = extensionStore,
                providerRegistry = providerRegistry,
            )
            val syncProgress = libraryState.cloudSyncProgress[root.id]
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
                            cloudAvailability = availability,
                            customBadgeLabel = customBadgeLabel,
                            songCount = count,
                            rootTrackIds = rootTrackIds,
                            rootTracking = rootTracking,
                            syncProgress = syncProgress,
                            queues = queues,
                            activeQueue = activeQueue,
                            trackActions = trackActions,
                            onOpen = { onOpenRoot(root.id) },
                            onRelocate = { onRelocateRoot(root.id) },
                            onSaveCloudRoot = { onSaveCloudRoot(root) },
                            onUpdateTrackedRoot = { onUpdateTrackedRoot(root) },
                            onEditTrackedRootSource = { info -> onEditTrackedRootSource(root, info) },
                            onRestoreExcluded = { onRestoreExcluded(root.id) },
                            onOpenExtensions = onOpenExtensions,
                            onRemove = { onRequestRemove(root) }
                        )
                    }
                } else {
                    RootItemRow(
                        root = root,
                        available = available,
                        cloudAvailability = availability,
                        customBadgeLabel = customBadgeLabel,
                        songCount = count,
                        rootTrackIds = rootTrackIds,
                        rootTracking = rootTracking,
                        syncProgress = syncProgress,
                        queues = queues,
                        activeQueue = activeQueue,
                        trackActions = trackActions,
                        onOpen = { onOpenRoot(root.id) },
                        onRelocate = {},
                        onSaveCloudRoot = {},
                        onUpdateTrackedRoot = { onUpdateTrackedRoot(root) },
                        onEditTrackedRootSource = { info -> onEditTrackedRootSource(root, info) },
                        onRestoreExcluded = { onRestoreExcluded(root.id) },
                        onOpenExtensions = onOpenExtensions,
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
    cloudAvailability: CloudProviderAvailability,
    customBadgeLabel: String?,
    songCount: Int,
    rootTrackIds: List<String>,
    rootTracking: CloudTrackingInfo?,
    syncProgress: CloudSyncProgress?,
    queues: List<PlayQueue>,
    activeQueue: PlayQueue,
    trackActions: TrackActionState,
    onOpen: () -> Unit,
    onRelocate: () -> Unit,
    onSaveCloudRoot: () -> Unit,
    onUpdateTrackedRoot: () -> Unit,
    onEditTrackedRootSource: (CloudTrackingInfo) -> Unit,
    onRestoreExcluded: () -> Unit,
    onOpenExtensions: (() -> Unit)?,
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
                        !available && root.kind == RootKind.CLOUD_EXTENSION -> Icons.Rounded.Extension
                        !available -> Icons.Rounded.FolderOff
                        root.kind == RootKind.APP_FOLDER -> Icons.Rounded.LibraryMusic
                        root.kind == RootKind.CLOUD_YOUTUBE -> Icons.Rounded.PlayCircle
                        root.kind == RootKind.CLOUD_BILIBILI -> Icons.Rounded.LiveTv
                        root.kind == RootKind.CLOUD_GOOGLE_DRIVE -> Icons.Rounded.AddToDrive
                        root.kind == RootKind.CLOUD_EXTENSION -> Icons.Rounded.Extension
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
                    when {
                        root.kind.isCloud -> CloudSourceBadge(
                            cloudKind = root.kind,
                            isLocal = false,
                            providerId = root.resolvedProviderId,
                            customBadgeLabel = customBadgeLabel,
                        )
                        rootTracking != null -> CloudSourceBadge(
                            cloudKind = rootTracking.cloudKind,
                            isLocal = true,
                            providerId = rootTracking.resolvedProviderId,
                            customBadgeLabel = customBadgeLabel,
                        )
                    }
                }
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = when {
                            available -> strings.songCount(songCount)
                            root.kind == RootKind.CLOUD_EXTENSION -> strings.extensionUnavailableStatus
                            else -> strings.folderUnavailable
                        },
                        color = if (available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )
                    if (syncProgress != null) {
                        Text(
                            text = strings.cloudSyncProgress(
                                syncProgress.completedFolders,
                                syncProgress.totalFolders,
                                syncProgress.tracksFound,
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (syncProgress.totalFolders > 0) {
                            LinearProgressIndicator(
                                progress = {
                                    (syncProgress.completedFolders.toFloat() / syncProgress.totalFolders.toFloat())
                                        .coerceIn(0f, 1f)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
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
                        if (!root.kind.isCloud && rootTracking != null) {
                            DropdownMenuItem(
                                text = { Text(strings.updateFromCloud) },
                                leadingIcon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onUpdateTrackedRoot()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(strings.editCloudSourceEllipsis) },
                                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onEditTrackedRootSource(rootTracking)
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
                        if ((isRemovable || root.kind == RootKind.PICKED || root.excludedPaths.isNotEmpty() || (!root.kind.isCloud && rootTracking != null)) && rootTrackIds.isNotEmpty()) {
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

        if (!available && root.kind == RootKind.CLOUD_EXTENSION) {
            ExtensionUnavailableBanner(
                availability = cloudAvailability,
                providerId = root.resolvedProviderId,
                onOpenExtensions = onOpenExtensions,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        } else if (!available && root.kind == RootKind.PICKED) {
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
    root: LibraryRoot?,
    rootName: String,
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    historyState: PlayHistoryUiState,
    playerState: PlayerUiState,
    queues: List<PlayQueue>,
    activeQueue: PlayQueue,
    trackActions: TrackActionState,
    extensionStore: ExtensionStore?,
    providerRegistry: CloudProviderRegistry,
    onNavigate: (FolderRef) -> Unit,
    onRequestRemoveSubfolder: (FolderRef) -> Unit,
    onUpdateTrackedSubfolder: (LibraryRoot, String) -> Unit,
    onEditTrackedSubfolderSource: (LibraryRoot, String, CloudTrackingInfo) -> Unit,
    onOpenExtensions: (() -> Unit)?,
) {
    val strings = LocalStrings.current
    val playCounts: (String) -> Int = { id -> historyState.entries[id]?.count ?: 0 }
    val syncProgress = libraryState.cloudSyncProgress[ref.rootId]
    val extAvailability = root?.takeIf { it.kind == RootKind.CLOUD_EXTENSION }
        ?.let { libraryStore.cloudProviderAvailability(it) }
    val showExtUnavailableBanner = root != null &&
        root.kind == RootKind.CLOUD_EXTENSION &&
        extAvailability != null &&
        extAvailability != CloudProviderAvailability.AVAILABLE
    val (subfolders, directTracks) = remember(
        libraryState.tracks,
        libraryState.folderSortOrders,
        libraryState.folderTrackOrders,
        libraryState.cloudSyncProgress,
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
    val headerCount = 1 +
        (if (showExtUnavailableBanner) 1 else 0) +
        (if (syncProgress != null) 1 else 0) +
        1 +
        subfolders.size
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

        if (showExtUnavailableBanner && root != null && extAvailability != null) {
            item(key = "extension_unavailable_banner") {
                ExtensionUnavailableBanner(
                    availability = extAvailability,
                    providerId = root.resolvedProviderId,
                    onOpenExtensions = onOpenExtensions,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        if (syncProgress != null) {
            item(key = "cloud_sync_banner") {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = strings.cloudSyncProgress(
                                syncProgress.completedFolders,
                                syncProgress.totalFolders,
                                syncProgress.tracksFound,
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (syncProgress.totalFolders > 0) {
                            LinearProgressIndicator(
                                progress = {
                                    (syncProgress.completedFolders.toFloat() / syncProgress.totalFolders.toFloat())
                                        .coerceIn(0f, 1f)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
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
            val subTracking = remember(root, childPath) {
                root?.takeIf { !it.kind.isCloud }?.trackingFor(childPath)
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
                        headlineContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = folderName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (subTracking != null) {
                                    CloudSourceBadge(
                                        cloudKind = subTracking.cloudKind,
                                        isLocal = true,
                                        providerId = subTracking.resolvedProviderId,
                                        customBadgeLabel = resolveCustomBadgeLabel(
                                            providerId = subTracking.resolvedProviderId,
                                            extensionStore = extensionStore,
                                            providerRegistry = providerRegistry,
                                        ),
                                    )
                                }
                            }
                        },
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
                                    if (root != null && subTracking != null) {
                                        DropdownMenuItem(
                                            text = { Text(strings.updateFromCloud) },
                                            leadingIcon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
                                            onClick = {
                                                menuExpanded = false
                                                onUpdateTrackedSubfolder(root, childPath)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(strings.editCloudSourceEllipsis) },
                                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                            onClick = {
                                                menuExpanded = false
                                                onEditTrackedSubfolderSource(root, childPath, subTracking)
                                            }
                                        )
                                        HorizontalDivider()
                                    }
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
