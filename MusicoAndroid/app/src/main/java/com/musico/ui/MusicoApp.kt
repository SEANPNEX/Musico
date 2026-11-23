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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.musico.ui.components.TrackActionDialogs
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
import com.musico.ui.screens.StatsScreen
import com.musico.ui.theme.MusicoMotion

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
    onPickExtensionFile: ((Uri) -> Unit) -> Unit = {},
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

    CompositionLocalProvider(LocalStrings provides strings) {
        MusicoAppContent(
            appContainer = appContainer,
            onPickFolder = onPickFolder,
            onPickLyricsFile = onPickLyricsFile,
            onPickImage = onPickImage,
            onPickExtensionFile = onPickExtensionFile,
        )
    }
}

@Composable
private fun MusicoAppContent(
    appContainer: AppContainer,
    onPickFolder: ((Uri) -> Unit) -> Unit,
    onPickLyricsFile: ((Uri) -> Unit) -> Unit,
    onPickImage: ((Uri) -> Unit) -> Unit,
    onPickExtensionFile: ((Uri) -> Unit) -> Unit,
) {
    val strings = LocalStrings.current
    val libraryStore = appContainer.libraryStore
    val queueStore = appContainer.queueStore
    val historyStore = appContainer.historyStore
    val lyricsStore = appContainer.lyricsStore
    val playerController = appContainer.playerController
    val updateManager = appContainer.updateManager
    val cloudExporter = appContainer.cloudExporter

    LaunchedEffect(updateManager) {
        updateManager.checkForUpdates(silent = true)
    }

    val libraryState by libraryStore.state.collectAsStateWithLifecycle()
    val queueState by queueStore.state.collectAsStateWithLifecycle()
    val historyState by historyStore.state.collectAsStateWithLifecycle()
    val playerState by playerController.state.collectAsStateWithLifecycle()

    var selectedDestination by rememberSaveable { mutableStateOf(TopDestination.LIBRARY) }
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
            selectedDestination = TopDestination.LIBRARY
            pendingSearchRequest = SearchRequest(query = query, field = field, token = System.nanoTime())
        },
    )
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < 600.dp
        val isWide = maxWidth >= 840.dp

        if (isCompact) {
            Scaffold(
                contentWindowInsets = WindowInsets(0),
                bottomBar = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        AnimatedVisibility(
                            visible = playerState.currentTrack != null,
                            enter = MusicoMotion.miniPlayerEnter,
                            exit = MusicoMotion.miniPlayerExit
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
                        NavigationBar {
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
                    appContainer = appContainer,
                    libraryState = libraryState,
                    queueState = queueState,
                    historyState = historyState,
                    playerState = playerState,
                    trackActions = trackActions,
                    pendingSearchRequest = pendingSearchRequest,
                    onConsumeSearchRequest = { pendingSearchRequest = null },
                    onPickFolder = onPickFolder,
                    onPickExtensionFile = onPickExtensionFile,
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
                                selected = selectedDestination == dest,
                                onClick = { selectedDestination = dest },
                                icon = { Icon(dest.icon, contentDescription = destLabel) },
                                label = { Text(destLabel) }
                            )
                        }
                    }

                    VerticalDivider()

                    DestinationContent(
                        destination = selectedDestination,
                        appContainer = appContainer,
                        libraryState = libraryState,
                        queueState = queueState,
                        historyState = historyState,
                        playerState = playerState,
                        trackActions = trackActions,
                        pendingSearchRequest = pendingSearchRequest,
                        onConsumeSearchRequest = { pendingSearchRequest = null },
                        onPickFolder = onPickFolder,
                        onPickExtensionFile = onPickExtensionFile,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )

                    AnimatedVisibility(
                        visible = isWide && sidePanel != null,
                        enter = MusicoMotion.sidePanelEnter,
                        exit = MusicoMotion.sidePanelExit
                    ) {
                        Row(modifier = Modifier.fillMaxHeight()) {
                            VerticalDivider()
                            Surface(
                                modifier = Modifier
                                    .width(340.dp)
                                    .fillMaxHeight()
                                    .statusBarsPadding(),
                                color = MaterialTheme.colorScheme.surface
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
                        enter = MusicoMotion.miniPlayerEnter,
                        exit = MusicoMotion.miniPlayerExit
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
            enter = MusicoMotion.fullPlayerEnter,
            exit = MusicoMotion.fullPlayerExit
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
                onClose = { showFullPlayer = false }
            )
        }
    }

    TrackActionDialogs(
        state = trackActions,
        queues = queueState.queues,
        activeQueueId = queueState.activeId,
        playlists = libraryState.playlists,
        historyState = historyState,
    )

    val errorMsg = playerState.errorMessage
    val structuredError = playerState.playbackError
    if (errorMsg != null || structuredError != null) {
        val displayError = when (structuredError) {
            is PlaybackError.ConsecutiveFailures -> strings.playbackStoppedConsecutive(structuredError.count)
            is PlaybackError.TrackFailed -> strings.playbackFailedTrack(structuredError.title ?: strings.thisSong)
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
    appContainer: AppContainer,
    libraryState: com.musico.store.LibraryUiState,
    queueState: com.musico.store.QueueUiState,
    historyState: com.musico.store.PlayHistoryUiState,
    playerState: com.musico.audio.PlayerUiState,
    trackActions: com.musico.ui.components.TrackActionState,
    pendingSearchRequest: SearchRequest?,
    onConsumeSearchRequest: () -> Unit,
    onPickFolder: ((Uri) -> Unit) -> Unit,
    onPickExtensionFile: ((Uri) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        AnimatedContent(
            targetState = destination,
            transitionSpec = { MusicoMotion.fadeThroughTransform() },
            label = "top_destination"
        ) { dest ->
            when (dest) {
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
                    extensionStore = appContainer.extensionStore,
                    providerRegistry = appContainer.cloudProviderRegistry,
                    onPickFolder = onPickFolder,
                    onPickExtensionFile = onPickExtensionFile,
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
                    languageStore = appContainer.languageStore,
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
                    languageStore = appContainer.languageStore,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
