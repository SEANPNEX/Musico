package com.musico.ui.player

import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musico.audio.PlayerController
import com.musico.audio.PlayerUiState
import com.musico.cloud.CloudQualityOption
import com.musico.core.LyricsStore
import com.musico.extensions.ExtensionStore
import com.musico.i18n.AppStrings
import com.musico.i18n.LocalStrings
import com.musico.lyrics.CloudLyricsService
import com.musico.model.RepeatMode
import com.musico.model.SleepMode
import com.musico.store.LibraryStore
import com.musico.store.LibraryUiState
import com.musico.store.PlayHistoryUiState
import com.musico.store.QueueStore
import com.musico.store.QueueUiState
import com.musico.ui.LocalSnackbarHost
import com.musico.ui.components.ArtworkImage
import com.musico.ui.components.EmptyState
import com.musico.ui.components.LocalThumbnailCache
import com.musico.ui.components.MusicoSnackbarHost
import com.musico.ui.components.TrackActionState
import com.musico.ui.screens.QueueSheet
import com.musico.ui.screens.QueuesContent
import com.musico.ui.theme.AppearanceSettings
import com.musico.ui.theme.LocalReduceMotion
import com.musico.ui.theme.MusicoMotion
import com.musico.ui.theme.ThemeBackgroundLayer
import com.musico.ui.theme.fastBlurBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class PlayerModalSheet {
    QUEUE,
    LYRICS,
    EQUALIZER,
    SLEEP,
    QUALITY,
}

enum class PlayerSidePane {
    LYRICS,
    QUEUE;

    fun label(strings: AppStrings): String = when (this) {
        LYRICS -> strings.lyrics
        QUEUE -> strings.queue
    }
}

@Composable
fun FullPlayerScreen(
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    historyState: PlayHistoryUiState,
    queueStore: QueueStore,
    queueState: QueueUiState,
    lyricsStore: LyricsStore,
    playerController: PlayerController,
    playerState: PlayerUiState,
    trackActions: TrackActionState,
    onPickLyricsFile: ((Uri) -> Unit) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    cloudLyricsService: CloudLyricsService? = null,
    extensionStore: ExtensionStore? = null,
    backgroundImageFile: File? = null,
    backgroundImageOpacity: Float = AppearanceSettings.DEFAULT_BACKGROUND_IMAGE_OPACITY,
    backgroundImageBlurDp: Float = AppearanceSettings.DEFAULT_BACKGROUND_IMAGE_BLUR_DP,
) {
    val strings = LocalStrings.current
    val reduceMotion = LocalReduceMotion.current
    val snackbarHostState = LocalSnackbarHost.current
    val density = LocalDensity.current
    val dragScope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    val dismissThresholdPx = with(density) { 140.dp.toPx() }
    val flingVelocityPx = with(density) { 800.dp.toPx() }

    var activeSheet by remember { mutableStateOf<PlayerModalSheet?>(null) }
    var sidePane by rememberSaveable { mutableStateOf(PlayerSidePane.LYRICS) }
    var showLyricsOverlay by rememberSaveable { mutableStateOf(false) }

    val track = playerState.currentTrack
    val thumbnailCache = LocalThumbnailCache.current
    val ambientArtwork by produceState<ImageBitmap?>(
        initialValue = null,
        track?.id,
        track?.artworkKey,
        track?.remoteArtworkUrl,
    ) {
        value = if (track == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                val bmp = libraryStore.artwork.bitmap(track.artworkKey)
                    ?: track.remoteArtworkUrl?.let { url -> thumbnailCache?.load(url) }
                if (bmp == null) {
                    null
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    fastBlurBitmap(bmp, 14).asImageBitmap()
                } else {
                    bmp.asImageBitmap()
                }
            }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { translationY = offsetY.value }
            .draggable(
                orientation = Orientation.Vertical,
                enabled = !showLyricsOverlay,
                state = rememberDraggableState { delta ->
                    val next = (offsetY.value + delta).coerceAtLeast(0f)
                    dragScope.launch { offsetY.snapTo(next) }
                },
                onDragStopped = { velocity ->
                    if (offsetY.value > dismissThresholdPx || (velocity > flingVelocityPx && offsetY.value > 0f)) {
                        onClose()
                    } else {
                        dragScope.launch {
                            offsetY.animateTo(
                                0f,
                                MusicoMotion.motionSpec(
                                    reduceMotion,
                                    spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                                ),
                            )
                        }
                    }
                },
            ),
        color = MaterialTheme.colorScheme.background
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isTwoColumn = maxWidth >= 760.dp
            val isLyricsOverlayVisible = !isTwoColumn && showLyricsOverlay
            val lyricsBackdropBlurDp by animateDpAsState(
                targetValue = if (isLyricsOverlayVisible && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 28.dp else 0.dp,
                animationSpec = MusicoMotion.motionSpec(
                    reduceMotion,
                    tween(durationMillis = MusicoMotion.ENTER_DURATION_MS, easing = MusicoMotion.EmphasizedEasing),
                ),
                label = "lyrics_backdrop_blur",
            )
            val controlsBackdropAlpha by animateFloatAsState(
                targetValue = when {
                    !isLyricsOverlayVisible -> 1f
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> 0.55f
                    else -> 0f
                },
                animationSpec = MusicoMotion.motionSpec(
                    reduceMotion,
                    tween(durationMillis = MusicoMotion.ENTER_DURATION_MS, easing = MusicoMotion.EmphasizedEasing),
                ),
                label = "lyrics_controls_backdrop_alpha",
            )

            LaunchedEffect(isTwoColumn) {
                if (isTwoColumn && showLyricsOverlay) {
                    showLyricsOverlay = false
                }
            }

            BackHandler(enabled = isLyricsOverlayVisible) {
                showLyricsOverlay = false
            }

            val singleColumnBlurModifier = if (!isTwoColumn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lyricsBackdropBlurDp > 0.dp) {
                Modifier.fillMaxSize().blur(radius = lyricsBackdropBlurDp, edgeTreatment = BlurredEdgeTreatment.Rectangle)
            } else {
                Modifier.fillMaxSize()
            }

            Box(modifier = Modifier.fillMaxSize()) {
                Box(modifier = singleColumnBlurModifier) {
                    ThemeBackgroundLayer(
                        imageFile = backgroundImageFile,
                        imageOpacity = backgroundImageOpacity,
                        imageBlurDp = if (isLyricsOverlayVisible && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                            maxOf(backgroundImageBlurDp, 22f)
                        } else {
                            backgroundImageBlurDp
                        },
                    )
                    Crossfade(
                        targetState = ambientArtwork,
                        animationSpec = MusicoMotion.finiteMotionSpec(
                            reduceMotion,
                            tween(durationMillis = 380, easing = MusicoMotion.EmphasizedEasing),
                        ),
                        label = "ambient_artwork_backdrop",
                    ) { art ->
                        if (art != null) {
                            val targetArtAlpha = if (isLyricsOverlayVisible) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.42f else 0.50f
                            } else {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.28f else 0.26f
                            }
                            val animatedArtAlpha by animateFloatAsState(
                                targetValue = targetArtAlpha,
                                animationSpec = MusicoMotion.motionSpec(
                                    reduceMotion,
                                    tween(durationMillis = 260, easing = MusicoMotion.EmphasizedEasing),
                                ),
                                label = "ambient_artwork_alpha",
                            )
                            val artMod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = 1.12f
                                        scaleY = 1.12f
                                    }
                                    .blur(48.dp, edgeTreatment = BlurredEdgeTreatment.Rectangle)
                                    .alpha(animatedArtAlpha)
                            } else {
                                Modifier.fillMaxSize().alpha(animatedArtAlpha)
                            }
                            Box(modifier = Modifier.fillMaxSize()) {
                                Image(
                                    bitmap = art,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = artMod,
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                0f to MaterialTheme.colorScheme.background.copy(alpha = 0.30f),
                                                0.55f to MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                                                1f to MaterialTheme.colorScheme.background.copy(alpha = 0.88f),
                                            )
                                        )
                                )
                            }
                        }
                    }

                    if (!isTwoColumn) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .navigationBarsPadding()
                                .alpha(controlsBackdropAlpha)
                        ) {
                            PlayerControlsPane(
                                libraryStore = libraryStore,
                                libraryState = libraryState,
                                queueState = queueState,
                                playerController = playerController,
                                playerState = playerState,
                                onOpenSheet = { target ->
                                    if (target == PlayerModalSheet.LYRICS) {
                                        showLyricsOverlay = true
                                    } else {
                                        activeSheet = target
                                    }
                                },
                                onOpenSongInfo = { trackId -> trackActions.songInfoTrackId = trackId },
                                onClose = onClose,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                if (isTwoColumn) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    ) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            PlayerControlsPane(
                                libraryStore = libraryStore,
                                libraryState = libraryState,
                                queueState = queueState,
                                playerController = playerController,
                                playerState = playerState,
                                onOpenSheet = { target ->
                                    when (target) {
                                        PlayerModalSheet.LYRICS -> sidePane = PlayerSidePane.LYRICS
                                        PlayerModalSheet.QUEUE -> sidePane = PlayerSidePane.QUEUE
                                        else -> activeSheet = target
                                    }
                                },
                                onOpenSongInfo = { trackId -> trackActions.songInfoTrackId = trackId },
                                onClose = onClose,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                            VerticalDivider()
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            ) {
                                SingleChoiceSegmentedButtonRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .widthIn(max = 320.dp)
                                        .align(Alignment.CenterHorizontally)
                                        .padding(horizontal = 24.dp, vertical = 12.dp)
                                ) {
                                    PlayerSidePane.entries.forEachIndexed { index, pane ->
                                        SegmentedButton(
                                            selected = sidePane == pane,
                                            onClick = { sidePane = pane },
                                            shape = SegmentedButtonDefaults.itemShape(
                                                index = index,
                                                count = PlayerSidePane.entries.size
                                            ),
                                            icon = {},
                                            label = {
                                                Text(
                                                    text = pane.label(strings),
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        )
                                    }
                                }

                                AnimatedContent(
                                    targetState = sidePane,
                                    transitionSpec = {
                                        MusicoMotion.sharedAxisXTransform(
                                            forward = targetState.ordinal >= initialState.ordinal,
                                            reduceMotion = reduceMotion,
                                        )
                                    },
                                    label = "player_side_pane"
                                ) { pane ->
                                    when (pane) {
                                        PlayerSidePane.LYRICS -> LyricsContent(
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
                                        PlayerSidePane.QUEUE -> QueuesContent(
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

                AnimatedVisibility(
                    visible = isLyricsOverlayVisible,
                    enter = if (reduceMotion) {
                        EnterTransition.None
                    } else {
                        fadeIn(
                            animationSpec = tween(
                                durationMillis = MusicoMotion.ENTER_DURATION_MS,
                                easing = MusicoMotion.EmphasizedDecelerateEasing,
                            )
                        ) + slideInVertically(
                            animationSpec = MusicoMotion.SpatialSpring,
                            initialOffsetY = { it / 10 },
                        )
                    },
                    exit = if (reduceMotion) {
                        ExitTransition.None
                    } else {
                        fadeOut(
                            animationSpec = tween(
                                durationMillis = 160,
                                easing = MusicoMotion.EmphasizedAccelerateEasing,
                            )
                        ) + slideOutVertically(
                            animationSpec = MusicoMotion.SpatialSpring,
                            targetOffsetY = { it / 10 },
                        )
                    },
                    label = "full_player_lyrics_overlay",
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to MaterialTheme.colorScheme.background.copy(alpha = 0.24f),
                                    0.5f to MaterialTheme.colorScheme.background.copy(alpha = 0.36f),
                                    1f to MaterialTheme.colorScheme.background.copy(alpha = 0.50f),
                                )
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                            )
                    ) {
                        LyricsContent(
                            libraryStore = libraryStore,
                            lyricsStore = lyricsStore,
                            playerController = playerController,
                            playerState = playerState,
                            onPickLyricsFile = onPickLyricsFile,
                            cloudLyricsService = cloudLyricsService,
                            extensionStore = extensionStore,
                            embedded = false,
                            onDone = { showLyricsOverlay = false },
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .navigationBarsPadding(),
                        )
                    }
                }

                if (snackbarHostState != null) {
                    MusicoSnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }

    when (activeSheet) {
        PlayerModalSheet.QUEUE -> QueueSheet(
            libraryStore = libraryStore,
            libraryState = libraryState,
            historyState = historyState,
            queueStore = queueStore,
            queueState = queueState,
            playerController = playerController,
            playerState = playerState,
            trackActions = trackActions,
            onDismiss = { activeSheet = null }
        )
        PlayerModalSheet.LYRICS -> Unit
        PlayerModalSheet.EQUALIZER -> EqualizerSheet(
            eq = playerState.eq,
            onUpdateEq = { playerController.updateEq(it) },
            onDismiss = { activeSheet = null }
        )
        PlayerModalSheet.SLEEP -> SleepTimerSheet(
            sleepMode = playerState.sleepMode,
            onSetSleep = { playerController.setSleep(it) },
            onDismiss = { activeSheet = null }
        )
        PlayerModalSheet.QUALITY -> StreamQualitySheet(
            qualities = playerState.availableStreamQualities,
            selectedQualityId = playerState.currentStreamQualityId,
            onSelectQuality = { qualityId ->
                playerController.switchCurrentTrackQuality(qualityId)
                activeSheet = null
            },
            onDismiss = { activeSheet = null },
        )
        null -> Unit
    }
}

@Composable
private fun PlayerControlsPane(
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    queueState: QueueUiState,
    playerController: PlayerController,
    playerState: PlayerUiState,
    onOpenSheet: (PlayerModalSheet) -> Unit,
    onOpenSongInfo: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current
    val haptic = LocalHapticFeedback.current
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val swipeScope = rememberCoroutineScope()
    val track = playerState.currentTrack
    val activeQueue = queueState.active

    Column(
        modifier = modifier
            .widthIn(max = 560.dp)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = strings.closePlayer,
                    modifier = Modifier.size(30.dp)
                )
            }
            if (track != null) {
                val showQualityPill = track.isCloud &&
                    playerState.availableStreamQualities.isNotEmpty() &&
                    !playerState.currentStreamQualityLabel.isNullOrBlank()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (showQualityPill) {
                        Surface(
                            onClick = { onOpenSheet(PlayerModalSheet.QUALITY) },
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(50),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.HighQuality,
                                    contentDescription = strings.audioQualityLabel,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = playerState.currentStreamQualityLabel.orEmpty(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Icon(
                                    imageVector = Icons.Rounded.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                    IconButton(onClick = { onOpenSongInfo(track.id) }) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = strings.songInfo
                        )
                    }
                }
            }
        }

        if (track != null) {
            val artworkScale by animateFloatAsState(
                targetValue = if (playerState.isPlaying) 1.0f else 0.92f,
                animationSpec = MusicoMotion.motionSpec(reduceMotion, MusicoMotion.ArtworkScaleSpring),
                label = "artwork_scale"
            )
            val artworkElevation by animateDpAsState(
                targetValue = if (playerState.isPlaying) 12.dp else 4.dp,
                animationSpec = MusicoMotion.motionSpec(
                    reduceMotion,
                    tween(durationMillis = 260, easing = MusicoMotion.EmphasizedEasing),
                ),
                label = "artwork_elevation"
            )
            val swipeX = remember(track.id) { Animatable(0f) }
            val swipeThresholdPx = with(density) { 72.dp.toPx() }
            val swipeFlingPx = with(density) { 500.dp.toPx() }

            AnimatedContent(
                targetState = track,
                contentKey = { it.id },
                transitionSpec = {
                    if (reduceMotion) {
                        androidx.compose.animation.EnterTransition.None togetherWith
                            androidx.compose.animation.ExitTransition.None
                    } else {
                        (fadeIn(
                            animationSpec = tween(
                                durationMillis = MusicoMotion.ENTER_DURATION_MS,
                                easing = MusicoMotion.EmphasizedDecelerateEasing,
                            )
                        ) + scaleIn(
                            initialScale = 0.96f,
                            animationSpec = tween(
                                durationMillis = MusicoMotion.ENTER_DURATION_MS,
                                easing = MusicoMotion.EmphasizedDecelerateEasing,
                            )
                        )) togetherWith fadeOut(
                            animationSpec = tween(
                                durationMillis = 120,
                                easing = MusicoMotion.EmphasizedAccelerateEasing,
                            )
                        )
                    }
                },
                label = "full_player_artwork",
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(vertical = 8.dp)
                    .widthIn(max = 360.dp)
                    .aspectRatio(1f)
                    .graphicsLayer { translationX = swipeX.value * 0.35f }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            swipeScope.launch { swipeX.snapTo(swipeX.value + delta) }
                        },
                        onDragStopped = { velocity ->
                            if (swipeX.value < -swipeThresholdPx || velocity < -swipeFlingPx) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                playerController.next()
                            } else if (swipeX.value > swipeThresholdPx || velocity > swipeFlingPx) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                playerController.previous()
                            }
                            swipeScope.launch {
                                swipeX.animateTo(
                                    0f,
                                    MusicoMotion.motionSpec(reduceMotion, spring()),
                                )
                            }
                        },
                    )
            ) { currentTrack ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    shadowElevation = artworkElevation,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = artworkScale
                            scaleY = artworkScale
                        }
                ) {
                    ArtworkImage(
                        artworkKey = currentTrack.artworkKey,
                        artworkCache = libraryStore.artwork,
                        remoteArtworkUrl = currentTrack.remoteArtworkUrl,
                        cornerRadius = 20.dp,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { onOpenSheet(PlayerModalSheet.LYRICS) }
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnimatedContent(
                        targetState = track,
                        contentKey = { it.id },
                        transitionSpec = {
                            if (reduceMotion) {
                                androidx.compose.animation.EnterTransition.None togetherWith
                                    androidx.compose.animation.ExitTransition.None
                            } else {
                                (slideInVertically(
                                    animationSpec = tween(
                                        durationMillis = 220,
                                        easing = MusicoMotion.EmphasizedDecelerateEasing,
                                    ),
                                    initialOffsetY = { it / 4 }
                                ) + fadeIn(
                                    animationSpec = tween(durationMillis = 200)
                                )) togetherWith (slideOutVertically(
                                    animationSpec = tween(
                                        durationMillis = 120,
                                        easing = MusicoMotion.EmphasizedAccelerateEasing,
                                    ),
                                    targetOffsetY = { -it / 4 }
                                ) + fadeOut(
                                    animationSpec = tween(durationMillis = 100)
                                ))
                            }
                        },
                        label = "full_player_title",
                        modifier = Modifier.weight(1f)
                    ) { currentTrack ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = currentTrack.title,
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "${strings.displayArtist(currentTrack.artist)} — ${strings.displayAlbum(currentTrack.album)}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (!track.isEphemeral) {
                        val isFav = track.id in libraryState.favourites
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                libraryStore.toggleFavourite(track.id)
                            }
                        ) {
                            Icon(
                                imageVector = if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = if (isFav) strings.removeFromFavourites else strings.addToFavourites,
                                tint = if (isFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                PlaybackScrubber(
                    currentTimeSec = playerState.currentTimeSec,
                    durationSec = playerState.durationSec,
                    enabled = true,
                    onSeek = { playerController.seek(it) }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { playerController.toggleShuffle() }) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = strings.shuffle,
                            tint = if (activeQueue.shuffle) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    IconButton(onClick = { playerController.previous() }) {
                        Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = strings.previous,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    FilledIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            playerController.togglePlayPause()
                        },
                        modifier = Modifier.size(68.dp)
                    ) {
                        Icon(
                            imageVector = if (playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playerState.isPlaying) strings.pause else strings.play,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    IconButton(onClick = { playerController.next() }) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = strings.next,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    IconButton(onClick = { playerController.cycleRepeat() }) {
                        Icon(
                            imageVector = if (activeQueue.repeatMode == RepeatMode.ONE) {
                                Icons.Rounded.RepeatOne
                            } else {
                                Icons.Rounded.Repeat
                            },
                            contentDescription = strings.repeat,
                            tint = if (activeQueue.repeatMode == RepeatMode.OFF) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }

                val positionText = activeQueue.currentIndex?.let { idx ->
                    strings.queuePosition(idx + 1, activeQueue.trackIds.size)
                }.orEmpty()
                val queueDisplayName = strings.displayQueueName(activeQueue.name)
                val indicatorStyle = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
                val indicatorColor = MaterialTheme.colorScheme.onSurfaceVariant
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = queueDisplayName,
                        style = indicatorStyle,
                        color = indicatorColor,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .basicMarquee(
                                iterations = Int.MAX_VALUE,
                                initialDelayMillis = 1500,
                                repeatDelayMillis = 1500,
                            ),
                    )
                    if (positionText.isNotEmpty()) {
                        Text(
                            text = " · $positionText",
                            style = indicatorStyle,
                            color = indicatorColor,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    PlayerActionButton(
                        label = strings.queue,
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        active = false,
                        onClick = { onOpenSheet(PlayerModalSheet.QUEUE) }
                    )
                    PlayerActionButton(
                        label = strings.lyrics,
                        icon = Icons.Rounded.Lyrics,
                        active = false,
                        onClick = { onOpenSheet(PlayerModalSheet.LYRICS) }
                    )
                    PlayerActionButton(
                        label = strings.equalizer,
                        icon = Icons.Rounded.Equalizer,
                        active = playerState.eq.enabled,
                        onClick = { onOpenSheet(PlayerModalSheet.EQUALIZER) }
                    )
                    PlayerActionButton(
                        label = strings.sleepTimer,
                        icon = Icons.Rounded.Bedtime,
                        active = playerState.sleepMode !is SleepMode.Off,
                        onClick = { onOpenSheet(PlayerModalSheet.SLEEP) }
                    )
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
            EmptyState(
                icon = Icons.Rounded.MusicNote,
                title = strings.nothingPlayingTitle,
                description = strings.pickSongFromLibrary,
                actionLabel = strings.tabQueues,
                onAction = { onOpenSheet(PlayerModalSheet.QUEUE) }
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun PlayerActionButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit
) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StreamQualitySheet(
    qualities: List<CloudQualityOption>,
    selectedQualityId: String?,
    onSelectQuality: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = strings.audioQualityLabel,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = strings.currentTrackOnlyHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(qualities, key = { it.id }) { option ->
                    val isSelected = option.id == selectedQualityId
                    ListItem(
                        modifier = Modifier.clickable { onSelectQuality(option.id) },
                        headlineContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = option.label,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (option.lossless) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(6.dp),
                                    ) {
                                        Text(
                                            text = strings.qualityLosslessBadge,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        },
                        trailingContent = if (isSelected) {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}
