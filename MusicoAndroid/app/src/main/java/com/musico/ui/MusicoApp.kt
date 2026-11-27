package com.musico.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musico.audio.PlaybackError
import com.musico.i18n.AppStrings
import com.musico.i18n.LocalStrings
import com.musico.i18n.resolveLanguage
import com.musico.i18n.stringsFor
import com.musico.model.SearchField
import java.util.Locale
import com.musico.store.AppContainer
import com.musico.ui.components.LocalThumbnailCache
import com.musico.ui.components.LocalTrackSwipeActions
import com.musico.ui.components.MusicoSnackbarHost
import com.musico.ui.components.TrackActionDialogs
import com.musico.ui.components.TrackSwipeActions
import com.musico.ui.components.rememberTrackActionState
import com.musico.ui.player.FullPlayerScreen
import com.musico.ui.player.LyricsContent
import com.musico.ui.player.MiniPlayer
import com.musico.ui.player.PlayerBar
import com.musico.ui.player.SidePanel
import com.musico.ui.screens.FoldersScreen
import com.musico.ui.screens.LibraryScreen
import com.musico.ui.screens.QueuesContent
import com.musico.ui.screens.QueuesScreen
import com.musico.ui.screens.SearchRequest
import com.musico.ui.screens.SettingsScreen
import com.musico.ui.screens.StatsScreen
import com.musico.ui.theme.LocalHasBackgroundImage
import com.musico.ui.theme.LocalReduceMotion
import com.musico.ui.theme.LocalThemeStore
import com.musico.ui.theme.MusicoMotion
import com.musico.ui.theme.ThemeBackgroundLayer
import com.musico.ui.theme.musicoScaffoldContainerColor
import com.musico.ui.theme.musicoScaffoldContentColor
import com.musico.update.hasAvailableUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Stable
class MusicoMessageDispatcher(
    private val hostState: SnackbarHostState?,
    private val scope: CoroutineScope?,
) {
    operator fun invoke(
        message: String,
        actionLabel: String? = null,
        duration: SnackbarDuration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
        onAction: (() -> Unit)? = null,
    ) {
        val host = hostState ?: return
        val coroutineScope = scope ?: return
        coroutineScope.launch {
            host.currentSnackbarData?.dismiss()
            val result = host.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = duration,
            )
            if (result == SnackbarResult.ActionPerformed) {
                onAction?.invoke()
            }
        }
    }
}

val LocalSnackbarHost = staticCompositionLocalOf<SnackbarHostState?> { null }
val LocalShowMessage = staticCompositionLocalOf { MusicoMessageDispatcher(null, null) }

enum class TopDestination(
    val icon: ImageVector
) {
    FOLDERS(Icons.Rounded.Folder),
    LIBRARY(Icons.Rounded.LibraryMusic),
    QUEUES(Icons.AutoMirrored.Rounded.QueueMusic),
    STATS(Icons.Rounded.BarChart);

    fun label(strings: AppStrings): String = when (this) {
        FOLDERS -> strings.tabFolders
        LIBRARY -> strings.tabLibrary
        QUEUES -> strings.tabQueues
        STATS -> strings.tabStats
    }
}

@Composable
fun MusicoApp(
    appContainer: AppContainer,
    onPickFolder: ((Uri) -> Unit) -> Unit,
    onPickLyricsFile: ((Uri) -> Unit) -> Unit,
    onPickImage: ((Uri) -> Unit) -> Unit,
    onPickExtensionFiles: ((List<Uri>) -> Unit) -> Unit = {},
) {
    val languageStore = appContainer.languageStore
    val selectedLanguage by languageStore.language.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val systemLocale = remember(configuration) {
        ConfigurationCompat.getLocales(configuration)[0] ?: Locale.getDefault()
    }
    val resolvedLanguage = remember(selectedLanguage, systemLocale) {
        resolveLanguage(selectedLanguage, systemLocale)
    }
    val strings = stringsFor(resolvedLanguage)
    val appearance by appContainer.themeStore.settings.collectAsStateWithLifecycle()
    val backgroundImageFile = remember(appearance.backgroundImageFileName) {
        appContainer.themeStore.resolveBackgroundImageFile()
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val messageDispatcher = remember(snackbarHostState, snackbarScope) {
        MusicoMessageDispatcher(snackbarHostState, snackbarScope)
    }
    val reduceMotion = MusicoMotion.rememberReduceMotion()

    CompositionLocalProvider(
        LocalStrings provides strings,
        LocalThumbnailCache provides appContainer.cloudThumbnailCache,
        LocalHasBackgroundImage provides (backgroundImageFile != null),
        LocalThemeStore provides appContainer.themeStore,
        LocalSnackbarHost provides snackbarHostState,
        LocalShowMessage provides messageDispatcher,
        LocalReduceMotion provides reduceMotion,
    ) {
        MusicoAppContent(
            appContainer = appContainer,
            backgroundImageFile = backgroundImageFile,
            backgroundImageOpacity = appearance.backgroundImageOpacity,
            backgroundImageBlurDp = appearance.backgroundImageBlurDp,
            onPickFolder = onPickFolder,
            onPickLyricsFile = onPickLyricsFile,
            onPickImage = onPickImage,
            onPickExtensionFiles = onPickExtensionFiles,
        )
    }
}

@Composable
private fun MusicoAppContent(
    appContainer: AppContainer,
    backgroundImageFile: java.io.File?,
    backgroundImageOpacity: Float,
    backgroundImageBlurDp: Float,
    onPickFolder: ((Uri) -> Unit) -> Unit,
    onPickLyricsFile: ((Uri) -> Unit) -> Unit,
    onPickImage: ((Uri) -> Unit) -> Unit,
    onPickExtensionFiles: ((List<Uri>) -> Unit) -> Unit,
) {
    val strings = LocalStrings.current
    val libraryStore = appContainer.libraryStore
    val queueStore = appContainer.queueStore
    val historyStore = appContainer.historyStore
    val lyricsStore = appContainer.lyricsStore
    val playerController = appContainer.playerController
    val updateManager = appContainer.updateManager
    val cloudExporter = appContainer.cloudExporter
    val extensionStore = appContainer.extensionStore
    val cloudLyricsService = appContainer.cloudLyricsService

    LaunchedEffect(updateManager) {
        updateManager.checkForUpdates(silent = true)
    }
    LaunchedEffect(extensionStore) {
        extensionStore.checkForUpdatesOnLaunch()
    }

    val libraryState by libraryStore.state.collectAsStateWithLifecycle()
    val queueState by queueStore.state.collectAsStateWithLifecycle()
    val historyState by historyStore.state.collectAsStateWithLifecycle()
    val playerState by playerController.state.collectAsStateWithLifecycle()
    val pendingExtensionUpdates by extensionStore.pendingUpdates.collectAsStateWithLifecycle()
    val updateState by updateManager.state.collectAsStateWithLifecycle()
    val hasBackgroundImage = LocalHasBackgroundImage.current
    val snackbarHostState = LocalSnackbarHost.current
    val reduceMotion = LocalReduceMotion.current

    var selectedDestination by rememberSaveable { mutableStateOf(TopDestination.QUEUES) }
    var showSettingsWide by rememberSaveable { mutableStateOf(false) }
    var dismissedExtensionReviewIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showFullPlayer by rememberSaveable { mutableStateOf(false) }
    var sidePanel by rememberSaveable { mutableStateOf<SidePanel?>(null) }
    var pendingSearchRequest by remember { mutableStateOf<SearchRequest?>(null) }

    val trackActions = rememberTrackActionState(
        libraryStore = libraryStore,
        playerController = playerController,
        cloudExporter = cloudExporter,
        cloudDownloadManager = appContainer.cloudDownloadManager,
        onPickFolder = onPickFolder,
        onPickImage = onPickImage,
        onQuickSearch = { query, field ->
            showFullPlayer = false
            showSettingsWide = false
            selectedDestination = TopDestination.LIBRARY
            pendingSearchRequest = SearchRequest(query = query, field = field, token = System.nanoTime())
        },
        lyricsStore = lyricsStore,
    )
    val messageDispatcher = LocalShowMessage.current
    val trackSwipeActions = remember(playerController, messageDispatcher, strings, trackActions) {
        TrackSwipeActions(
            onPlayNext = { track ->
                playerController.playNext(listOf(track.id))
                messageDispatcher(strings.playingNextNotice)
            },
            onDelete = { track ->
                trackActions.deleteTargetTrack = track
            },
        )
    }
    var lastTrack by remember { mutableStateOf(playerState.currentTrack) }
    if (playerState.currentTrack != null) {
        lastTrack = playerState.currentTrack
    }
    val miniPlayerState = remember(playerState, lastTrack) {
        if (playerState.currentTrack != null) playerState else playerState.copy(currentTrack = lastTrack)
    }
    var lastSidePanel by remember { mutableStateOf(sidePanel) }
    if (sidePanel != null) {
        lastSidePanel = sidePanel
    }

    BackHandler(enabled = showFullPlayer) {
        showFullPlayer = false
    }

    CompositionLocalProvider(LocalTrackSwipeActions provides trackSwipeActions) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < 600.dp
        val isWide = maxWidth >= 840.dp

        BackHandler(enabled = !isCompact && showSettingsWide && !showFullPlayer) {
            showSettingsWide = false
        }

        ThemeBackgroundLayer(
            imageFile = backgroundImageFile,
            imageOpacity = backgroundImageOpacity,
            imageBlurDp = backgroundImageBlurDp,
        )

        if (isCompact) {
            Scaffold(
                containerColor = musicoScaffoldContainerColor(),
                contentColor = musicoScaffoldContentColor(),
                contentWindowInsets = WindowInsets(0),
                snackbarHost = {
                    if (!showFullPlayer && snackbarHostState != null) {
                        MusicoSnackbarHost(hostState = snackbarHostState)
                    }
                },
                bottomBar = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        AnimatedVisibility(
                            visible = playerState.currentTrack != null,
                            enter = MusicoMotion.miniPlayerEnter(reduceMotion),
                            exit = MusicoMotion.miniPlayerExit(reduceMotion)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                MiniPlayer(
                                    playerState = miniPlayerState,
                                    artworkCache = libraryStore.artwork,
                                    onTogglePlayPause = { playerController.togglePlayPause() },
                                    onNext = { playerController.next() },
                                    onOpenFullPlayer = { showFullPlayer = true }
                                )
                                HorizontalDivider()
                            }
                        }
                        NavigationBar(
                            containerColor = if (hasBackgroundImage) {
                                MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.80f)
                            } else {
                                NavigationBarDefaults.containerColor
                            },
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) {
                            TopDestination.entries.forEach { dest ->
                                val destLabel = dest.label(strings)
                                NavigationBarItem(
                                    selected = selectedDestination == dest,
                                    onClick = { selectedDestination = dest },
                                    icon = { Icon(dest.icon, contentDescription = destLabel) },
                                    label = { Text(destLabel) }
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                DestinationContent(
                    destination = selectedDestination,
                    showSettings = false,
                    appContainer = appContainer,
                    libraryState = libraryState,
                    queueState = queueState,
                    historyState = historyState,
                    playerState = playerState,
                    trackActions = trackActions,
                    pendingSearchRequest = pendingSearchRequest,
                    onConsumeSearchRequest = { pendingSearchRequest = null },
                    onPickFolder = onPickFolder,
                    onPickImage = onPickImage,
                    onPickExtensionFiles = onPickExtensionFiles,
                    isCompact = isCompact,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding)
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                val hasBottomPlayer = isWide || playerState.currentTrack != null
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .let { if (!hasBottomPlayer) it.navigationBarsPadding() else it }
                ) {
                    NavigationRail(
                        containerColor = if (hasBackgroundImage) {
                            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.80f)
                        } else {
                            NavigationRailDefaults.ContainerColor
                        },
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxHeight()
                            .statusBarsPadding()
                            .let {
                                if (playerState.currentTrack == null && !isWide) it.navigationBarsPadding() else it
                            }
                    ) {
                        TopDestination.entries.forEach { dest ->
                            val destLabel = dest.label(strings)
                            NavigationRailItem(
                                selected = !showSettingsWide && selectedDestination == dest,
                                onClick = {
                                    showSettingsWide = false
                                    selectedDestination = dest
                                },
                                icon = { Icon(dest.icon, contentDescription = destLabel) },
                                label = { Text(destLabel) }
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        NavigationRailItem(
                            selected = showSettingsWide,
                            onClick = { showSettingsWide = true },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (updateState.hasAvailableUpdate) {
                                            Badge()
                                        }
                                    }
                                ) {
                                    Icon(Icons.Rounded.Settings, contentDescription = strings.settingsLabel)
                                }
                            },
                            label = { Text(strings.settingsLabel) },
                        )
                    }

                    VerticalDivider()

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        DestinationContent(
                            destination = selectedDestination,
                            showSettings = showSettingsWide,
                            appContainer = appContainer,
                            libraryState = libraryState,
                            queueState = queueState,
                            historyState = historyState,
                            playerState = playerState,
                            trackActions = trackActions,
                            pendingSearchRequest = pendingSearchRequest,
                            onConsumeSearchRequest = { pendingSearchRequest = null },
                            onPickFolder = onPickFolder,
                            onPickImage = onPickImage,
                            onPickExtensionFiles = onPickExtensionFiles,
                            isCompact = isCompact,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (!showFullPlayer && snackbarHostState != null) {
                            MusicoSnackbarHost(
                                hostState = snackbarHostState,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = isWide && sidePanel != null,
                        enter = MusicoMotion.sidePanelEnter(reduceMotion),
                        exit = MusicoMotion.sidePanelExit(reduceMotion)
                    ) {
                        Row(modifier = Modifier.fillMaxHeight()) {
                            VerticalDivider()
                            Surface(
                                modifier = Modifier
                                    .width(340.dp)
                                    .fillMaxHeight()
                                    .statusBarsPadding(),
                                color = if (hasBackgroundImage) {
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.84f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ) {
                                val displayedPanel = sidePanel ?: lastSidePanel ?: SidePanel.QUEUE
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, end = 4.dp, top = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = if (displayedPanel == SidePanel.LYRICS) strings.lyrics else strings.queue,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        IconButton(onClick = { sidePanel = null }) {
                                            Icon(Icons.Rounded.Close, contentDescription = strings.closePanel)
                                        }
                                    }
                                    Crossfade(
                                        targetState = displayedPanel,
                                        animationSpec = tween(durationMillis = MusicoMotion.ENTER_DURATION_MS),
                                        label = "wide_side_panel"
                                    ) { panel ->
                                        when (panel) {
                                            SidePanel.LYRICS -> LyricsContent(
                                                libraryStore = libraryStore,
                                                lyricsStore = lyricsStore,
                                                playerController = playerController,
                                                playerState = playerState,
                                                onPickLyricsFile = onPickLyricsFile,
                                                cloudLyricsService = cloudLyricsService,
                                                extensionStore = extensionStore,
                                                embedded = true,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            SidePanel.QUEUE -> QueuesContent(
                                                libraryStore = libraryStore,
                                                libraryState = libraryState,
                                                historyState = historyState,
                                                queueStore = queueStore,
                                                queueState = queueState,
                                                playerController = playerController,
                                                playerState = playerState,
                                                trackActions = trackActions,
                                                editing = false,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (isWide) {
                    PlayerBar(
                        playerController = playerController,
                        playerState = playerState,
                        activeQueue = queueState.active,
                        artworkCache = libraryStore.artwork,
                        sidePanel = sidePanel,
                        onToggleSidePanel = { target ->
                            sidePanel = if (sidePanel == target) null else target
                        },
                        onOpenSongInfo = { trackId -> trackActions.songInfoTrackId = trackId },
                        onOpenFullPlayer = { showFullPlayer = true }
                    )
                } else {
                    AnimatedVisibility(
                        visible = playerState.currentTrack != null,
                        enter = MusicoMotion.miniPlayerEnter(reduceMotion),
                        exit = MusicoMotion.miniPlayerExit(reduceMotion)
                    ) {
                        MiniPlayer(
                            playerState = miniPlayerState,
                            artworkCache = libraryStore.artwork,
                            onTogglePlayPause = { playerController.togglePlayPause() },
                            onNext = { playerController.next() },
                            onOpenFullPlayer = { showFullPlayer = true },
                            applyNavBarPadding = true
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showFullPlayer,
            enter = MusicoMotion.fullPlayerEnter(reduceMotion),
            exit = MusicoMotion.fullPlayerExit(reduceMotion)
        ) {
            FullPlayerScreen(
                libraryStore = libraryStore,
                libraryState = libraryState,
                historyState = historyState,
                queueStore = queueStore,
                queueState = queueState,
                lyricsStore = lyricsStore,
                playerController = playerController,
                playerState = playerState,
                trackActions = trackActions,
                onPickLyricsFile = onPickLyricsFile,
                cloudLyricsService = cloudLyricsService,
                extensionStore = extensionStore,
                backgroundImageFile = backgroundImageFile,
                backgroundImageOpacity = backgroundImageOpacity,
                backgroundImageBlurDp = backgroundImageBlurDp,
                onClose = { showFullPlayer = false }
            )
        }
    }
    }

    TrackActionDialogs(
        state = trackActions,
        queues = queueState.queues,
        activeQueueId = queueState.activeId,
        playlists = libraryState.playlists,
        historyState = historyState,
    )

    val pendingExtensionPrompt = pendingExtensionUpdates.values.firstOrNull {
        it.installed.id !in dismissedExtensionReviewIds
    }
    if (pendingExtensionPrompt != null) {
        ReviewExtensionUpdateDialog(
            pending = pendingExtensionPrompt,
            strings = strings,
            onDismiss = {
                dismissedExtensionReviewIds = dismissedExtensionReviewIds + pendingExtensionPrompt.installed.id
            },
            onApprove = {
                extensionStore.approvePendingUpdate(pendingExtensionPrompt.installed.id)
                dismissedExtensionReviewIds = dismissedExtensionReviewIds - pendingExtensionPrompt.installed.id
            },
        )
    }

    val errorMsg = playerState.errorMessage
    val structuredError = playerState.playbackError
    if (errorMsg != null || structuredError != null) {
        val displayError = when (structuredError) {
            is PlaybackError.ConsecutiveFailures -> strings.playbackStoppedConsecutive(structuredError.count)
            is PlaybackError.TrackFailed -> strings.playbackFailedTrack(structuredError.title ?: strings.thisSong)
            is PlaybackError.ExtensionUnavailable -> strings.errorExtensionUnavailable
            is PlaybackError.EngineError -> structuredError.message ?: strings.playbackErrorGeneric
            null -> errorMsg ?: strings.playbackErrorGeneric
        }
        AlertDialog(
            onDismissRequest = { playerController.clearError() },
            title = { Text(strings.playbackProblem) },
            text = { Text(displayError) },
            confirmButton = {
                TextButton(onClick = { playerController.clearError() }) {
                    Text(strings.ok)
                }
            }
        )
    }
}

@Composable
private fun DestinationContent(
    destination: TopDestination,
    showSettings: Boolean,
    appContainer: AppContainer,
    libraryState: com.musico.store.LibraryUiState,
    queueState: com.musico.store.QueueUiState,
    historyState: com.musico.store.PlayHistoryUiState,
    playerState: com.musico.audio.PlayerUiState,
    trackActions: com.musico.ui.components.TrackActionState,
    pendingSearchRequest: SearchRequest?,
    onConsumeSearchRequest: () -> Unit,
    onPickFolder: ((Uri) -> Unit) -> Unit,
    onPickImage: ((Uri) -> Unit) -> Unit,
    onPickExtensionFiles: ((List<Uri>) -> Unit) -> Unit,
    isCompact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val activeKey: TopDestination? = if (showSettings) null else destination
    val reduceMotion = LocalReduceMotion.current
    Box(modifier = modifier) {
        AnimatedContent(
            targetState = activeKey,
            transitionSpec = { MusicoMotion.fadeThroughTransform(reduceMotion) },
            label = "top_destination"
        ) { dest ->
            when (dest) {
                null -> SettingsScreen(
                    themeStore = appContainer.themeStore,
                    languageStore = appContainer.languageStore,
                    libraryStore = appContainer.libraryStore,
                    libraryState = libraryState,
                    updateManager = appContainer.updateManager,
                    googleDriveOAuthManager = appContainer.googleDriveOAuthManager,
                    onPickImage = onPickImage,
                    showBackButton = false,
                    modifier = Modifier.fillMaxSize()
                )
                TopDestination.FOLDERS -> FoldersScreen(
                    libraryStore = appContainer.libraryStore,
                    libraryState = libraryState,
                    historyState = historyState,
                    playerState = playerState,
                    queues = queueState.queues,
                    activeQueue = queueState.active,
                    trackActions = trackActions,
                    updateManager = appContainer.updateManager,
                    cloudExporter = appContainer.cloudExporter,
                    cloudDownloadManager = appContainer.cloudDownloadManager,
                    languageStore = appContainer.languageStore,
                    themeStore = appContainer.themeStore,
                    extensionStore = appContainer.extensionStore,
                    googleDriveOAuthManager = appContainer.googleDriveOAuthManager,
                    providerRegistry = appContainer.cloudProviderRegistry,
                    isCompact = isCompact,
                    onPickFolder = onPickFolder,
                    onPickImage = onPickImage,
                    onPickExtensionFiles = onPickExtensionFiles,
                    modifier = Modifier.fillMaxSize()
                )
                TopDestination.LIBRARY -> LibraryScreen(
                    libraryStore = appContainer.libraryStore,
                    libraryState = libraryState,
                    historyState = historyState,
                    playerState = playerState,
                    queues = queueState.queues,
                    activeQueue = queueState.active,
                    trackActions = trackActions,
                    extensionStore = appContainer.extensionStore,
                    pendingSearchRequest = pendingSearchRequest,
                    onConsumeSearchRequest = onConsumeSearchRequest,
                    modifier = Modifier.fillMaxSize()
                )
                TopDestination.QUEUES -> QueuesScreen(
                    libraryStore = appContainer.libraryStore,
                    libraryState = libraryState,
                    historyState = historyState,
                    queueStore = appContainer.queueStore,
                    queueState = queueState,
                    playerController = appContainer.playerController,
                    playerState = playerState,
                    trackActions = trackActions,
                    modifier = Modifier.fillMaxSize()
                )
                TopDestination.STATS -> StatsScreen(
                    libraryStore = appContainer.libraryStore,
                    libraryState = libraryState,
                    historyStore = appContainer.historyStore,
                    historyState = historyState,
                    playerState = playerState,
                    queues = queueState.queues,
                    activeQueue = queueState.active,
                    trackActions = trackActions,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
