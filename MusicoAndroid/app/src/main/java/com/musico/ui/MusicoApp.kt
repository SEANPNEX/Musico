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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.musico.ui.screens.StatsScreen
import com.musico.ui.theme.MusicoMotion

enum class TopDestination(
    val label: String,
    val icon: ImageVector
) {
    FOLDERS("Folders", Icons.Rounded.Folder),
    LIBRARY("Library", Icons.Rounded.LibraryMusic),
    QUEUES("Queues", Icons.AutoMirrored.Rounded.QueueMusic),
    STATS("Stats", Icons.Rounded.BarChart)
}

@Composable
fun MusicoApp(
    appContainer: AppContainer,
    onPickFolder: ((Uri) -> Unit) -> Unit,
    onPickLyricsFile: ((Uri) -> Unit) -> Unit
) {
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

    val trackActions = rememberTrackActionState(libraryStore, playerController, cloudExporter, onPickFolder)

    var selectedDestination by rememberSaveable { mutableStateOf(TopDestination.LIBRARY) }
    var showFullPlayer by rememberSaveable { mutableStateOf(false) }
    var sidePanel by rememberSaveable { mutableStateOf<SidePanel?>(null) }
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
                                NavigationBarItem(
                                    selected = selectedDestination == dest,
                                    onClick = { selectedDestination = dest },
                                    icon = { Icon(dest.icon, contentDescription = dest.label) },
                                    label = { Text(dest.label) }
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
                    onPickFolder = onPickFolder,
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
                            NavigationRailItem(
                                selected = selectedDestination == dest,
                                onClick = { selectedDestination = dest },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label) }
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
                        onPickFolder = onPickFolder,
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
                                            text = if (displayedPanel == SidePanel.LYRICS) "Lyrics" else "Queue",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        IconButton(onClick = { sidePanel = null }) {
                                            Icon(Icons.Rounded.Close, contentDescription = "Close panel")
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
        playlists = libraryState.playlists
    )

    val errorMsg = playerState.errorMessage
    if (errorMsg != null) {
        AlertDialog(
            onDismissRequest = { playerController.clearError() },
            title = { Text("Playback Problem") },
            text = { Text(errorMsg) },
            confirmButton = {
                TextButton(onClick = { playerController.clearError() }) {
                    Text("OK")
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
    onPickFolder: ((Uri) -> Unit) -> Unit,
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
                    playerState = playerState,
                    queues = queueState.queues,
                    activeQueue = queueState.active,
                    trackActions = trackActions,
                    updateManager = appContainer.updateManager,
                    cloudExporter = appContainer.cloudExporter,
                    onPickFolder = onPickFolder,
                    modifier = Modifier.fillMaxSize()
                )
                TopDestination.LIBRARY -> LibraryScreen(
                    libraryStore = appContainer.libraryStore,
                    libraryState = libraryState,
                    playerState = playerState,
                    queues = queueState.queues,
                    activeQueue = queueState.active,
                    trackActions = trackActions,
                    modifier = Modifier.fillMaxSize()
                )
                TopDestination.QUEUES -> QueuesScreen(
                    libraryStore = appContainer.libraryStore,
                    libraryState = libraryState,
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
