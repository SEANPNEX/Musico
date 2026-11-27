package com.musico.ui.player

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.rounded.FormatAlignLeft
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musico.audio.PlayerController
import com.musico.audio.PlayerUiState
import com.musico.core.LyricsStore
import com.musico.core.LyricsUnreadableException
import com.musico.extensions.ExtensionStore
import com.musico.i18n.LocalStrings
import com.musico.lyrics.CloudLyricsFormatter
import com.musico.lyrics.CloudLyricsService
import com.musico.model.Lyrics
import com.musico.model.LyricsSource
import com.musico.store.LibraryStore
import com.musico.ui.LocalShowMessage
import com.musico.ui.LocalSnackbarHost
import com.musico.ui.components.MusicoSnackbarHost
import com.musico.ui.components.EmptyState
import com.musico.ui.theme.AppearanceSettings
import com.musico.ui.theme.LocalReduceMotion
import com.musico.ui.theme.LocalThemeStore
import com.musico.ui.theme.LyricsAlignment
import com.musico.ui.theme.LyricsFontSize
import com.musico.ui.theme.MusicoMotion
import com.musico.ui.theme.ThemeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSheet(
    libraryStore: LibraryStore,
    lyricsStore: LyricsStore,
    playerController: PlayerController,
    playerState: PlayerUiState,
    onPickLyricsFile: ((Uri) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    cloudLyricsService: CloudLyricsService? = null,
    extensionStore: ExtensionStore? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbarHostState = LocalSnackbarHost.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
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
                onDone = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
            if (snackbarHostState != null) {
                MusicoSnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
fun LyricsContent(
    libraryStore: LibraryStore,
    lyricsStore: LyricsStore,
    playerController: PlayerController,
    playerState: PlayerUiState,
    onPickLyricsFile: ((Uri) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    cloudLyricsService: CloudLyricsService? = null,
    extensionStore: ExtensionStore? = null,
    themeStore: ThemeStore? = LocalThemeStore.current,
    embedded: Boolean = false,
    onDone: (() -> Unit)? = null
) {
    val strings = LocalStrings.current
    val showMessage = LocalShowMessage.current
    val reduceMotion = LocalReduceMotion.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val track = playerState.currentTrack
    val appearance = if (themeStore != null) {
        themeStore.settings.collectAsStateWithLifecycle().value
    } else {
        AppearanceSettings()
    }

    var revision by remember { mutableIntStateOf(0) }
    var resolvedLyrics by remember { mutableStateOf<Lyrics?>(null) }
    var resolvedSource by remember { mutableStateOf<LyricsSource?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showCloudSearch by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var canWriteSidecar by remember { mutableStateOf(false) }

    LaunchedEffect(lyricsStore, track?.id) {
        lyricsStore.lyricsChanges.collect { changedTrackId ->
            if (changedTrackId == track?.id) {
                revision += 1
            }
        }
    }

    LaunchedEffect(track?.id, revision) {
        if (track == null) {
            resolvedLyrics = null
            resolvedSource = null
            canWriteSidecar = false
            return@LaunchedEffect
        }
        val trackId = track.id
        val (found, writable) = withContext(Dispatchers.IO) {
            val embeddedText = libraryStore.embeddedLyrics(track)
            val siblingBytes = libraryStore.readSiblingLyrics(track)
            val folderBytes = libraryStore.readFolderMatchedLyrics(track)
            val resolved = lyricsStore.resolve(
                trackId = trackId,
                audioUri = libraryStore.uriFor(track),
                siblingProvider = { siblingBytes },
                embeddedText = embeddedText,
                trackTitle = track.title,
                trackArtist = track.artist,
                folderMatchProvider = { folderBytes },
            )
            resolved to libraryStore.canWriteSidecarLyrics(track)
        }
        if (playerState.currentTrack?.id == trackId) {
            resolvedLyrics = found?.first
            resolvedSource = found?.second
            canWriteSidecar = writable
        }
    }

    fun launchImport() {
        val currentId = track?.id ?: return
        onPickLyricsFile { uri ->
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            lyricsStore.importLyrics(stream, currentId)
                        } ?: throw LyricsUnreadableException()
                    }
                }
                result.onSuccess {
                    revision += 1
                }.onFailure { err ->
                    importError = if (err is LyricsUnreadableException) {
                        strings.lyricsImportErrorFallback
                    } else {
                        err.message ?: strings.lyricsImportErrorFallback
                    }
                }
            }
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track?.title ?: strings.lyrics,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val source = resolvedSource
                if (source != null) {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = strings.lyricsSourceBadge(source),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        enabled = track != null
                    ) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = strings.lyricsOptions)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        resolvedSource?.let { source ->
                            DropdownMenuItem(
                                text = { Text(strings.lyricsSourceDescription(source)) },
                                enabled = false,
                                onClick = {}
                            )
                            HorizontalDivider()
                        }
                        if (cloudLyricsService != null) {
                            DropdownMenuItem(
                                text = { Text(strings.searchOnlineEllipsis) },
                                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showCloudSearch = true
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(strings.importFromFileEllipsis) },
                            leadingIcon = { Icon(Icons.Rounded.FileOpen, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                launchImport()
                            }
                        )
                        if (track != null && canWriteSidecar && resolvedLyrics != null) {
                            DropdownMenuItem(
                                text = { Text(strings.saveLrcToFolder) },
                                leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    val lrcText = lyricsStore.rawImportedText(track.id)
                                        ?: resolvedLyrics?.lines?.joinToString("\n") { line ->
                                            val t = line.timeSec
                                            if (t != null) "${CloudLyricsFormatter.formatLrcTimestamp(t)}${line.text}"
                                            else line.text
                                        }
                                    if (!lrcText.isNullOrBlank()) {
                                        scope.launch {
                                            val res = libraryStore.writeSidecarLyrics(track, lrcText)
                                            res.onSuccess {
                                                showMessage(strings.savedLrcToFolderNotice)
                                            }.onFailure { err ->
                                                importError = err.message ?: strings.couldNotSaveLyricsTitle
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        if (track != null && lyricsStore.hasImported(track.id)) {
                            DropdownMenuItem(
                                text = { Text(strings.removeImportedLyrics) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    lyricsStore.removeImported(track.id)
                                    revision += 1
                                }
                            )
                        }
                        if (themeStore != null) {
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(strings.lyricsFontSizeLabel) },
                                leadingIcon = { Icon(Icons.Rounded.FormatSize, contentDescription = null) },
                                enabled = false,
                                onClick = {},
                            )
                            LyricsFontSize.entries.forEach { sizeOption ->
                                val sizeLabel = when (sizeOption) {
                                    LyricsFontSize.SMALL -> strings.lyricsFontSizeSmall
                                    LyricsFontSize.MEDIUM -> strings.lyricsFontSizeMedium
                                    LyricsFontSize.LARGE -> strings.lyricsFontSizeLarge
                                }
                                DropdownMenuItem(
                                    text = { Text(sizeLabel) },
                                    trailingIcon = if (appearance.lyricsFontSize == sizeOption) {
                                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                                    } else null,
                                    onClick = {
                                        themeStore.setLyricsFontSize(sizeOption)
                                        menuExpanded = false
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(strings.lyricsAlignmentLabel) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (appearance.lyricsAlignment == LyricsAlignment.CENTER) {
                                            Icons.Rounded.FormatAlignCenter
                                        } else {
                                            Icons.Rounded.FormatAlignLeft
                                        },
                                        contentDescription = null,
                                    )
                                },
                                enabled = false,
                                onClick = {},
                            )
                            LyricsAlignment.entries.forEach { alignOption ->
                                val alignLabel = when (alignOption) {
                                    LyricsAlignment.START -> strings.lyricsAlignmentLeft
                                    LyricsAlignment.CENTER -> strings.lyricsAlignmentCenter
                                }
                                DropdownMenuItem(
                                    text = { Text(alignLabel) },
                                    trailingIcon = if (appearance.lyricsAlignment == alignOption) {
                                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                                    } else null,
                                    onClick = {
                                        themeStore.setLyricsAlignment(alignOption)
                                        menuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                if (!embedded && onDone != null) {
                    TextButton(onClick = onDone) {
                        Text(strings.done, maxLines = 1)
                    }
                }
            }
        }

        HorizontalDivider()

        val lyrics = resolvedLyrics
        if (lyrics == null) {
            EmptyState(
                icon = Icons.Rounded.Lyrics,
                title = strings.noLyricsTitle,
                description = strings.noLyricsDesc,
                actionLabel = if (track != null && cloudLyricsService != null) strings.searchOnlineEllipsis else if (track != null) strings.importFromFileEllipsis else null,
                onAction = if (track != null && cloudLyricsService != null) ({ showCloudSearch = true }) else if (track != null) ({ launchImport() }) else null,
                secondaryActionLabel = if (track != null && cloudLyricsService != null) strings.importFromFileEllipsis else null,
                onSecondaryAction = if (track != null && cloudLyricsService != null) ({ launchImport() }) else null,
            )
        } else if (lyrics.isTimed) {
            TimedLyricsList(
                lyrics = lyrics,
                currentTimeSec = playerState.currentTimeSec,
                fontSize = appearance.lyricsFontSize,
                alignment = appearance.lyricsAlignment,
                reduceMotion = reduceMotion,
                onSeek = { playerController.seek(it) },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            PlainLyricsList(
                lyrics = lyrics,
                fontSize = appearance.lyricsFontSize,
                alignment = appearance.lyricsAlignment,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    val err = importError
    if (err != null) {
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text(strings.couldNotImportLyricsTitle) },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = { importError = null }) {
                    Text(strings.ok)
                }
            }
        )
    }

    if (showCloudSearch && track != null && cloudLyricsService != null) {
        CloudLyricsPickerSheet(
            track = track,
            canExportSidecar = canWriteSidecar,
            cloudLyricsService = cloudLyricsService,
            extensionStore = extensionStore,
            onConfirm = { lyricsText, exportSidecar ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        lyricsStore.importLyrics(lyricsText, track.id)
                        if (exportSidecar) {
                            libraryStore.writeSidecarLyrics(track, lyricsText).getOrThrow()
                        }
                    }
                }.onSuccess {
                    revision += 1
                    if (exportSidecar) {
                        showMessage(strings.savedLrcToFolderNotice)
                    }
                }
            },
            onDismiss = { showCloudSearch = false },
        )
    }
}

@Composable
private fun TimedLyricsList(
    lyrics: Lyrics,
    currentTimeSec: Double,
    fontSize: LyricsFontSize,
    alignment: LyricsAlignment,
    reduceMotion: Boolean,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current
    val activeIndex = lyrics.lineIndexAt(currentTimeSec)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var isUserScrollingPaused by remember { mutableStateOf(false) }
    var manualScrollToken by remember { mutableIntStateOf(0) }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    isUserScrollingPaused = true
                    manualScrollToken += 1
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(manualScrollToken) {
        if (manualScrollToken > 0) {
            delay(3500L)
            isUserScrollingPaused = false
        }
    }

    LaunchedEffect(activeIndex, isUserScrollingPaused) {
        if (!isUserScrollingPaused && activeIndex != null && activeIndex in lyrics.lines.indices) {
            if (reduceMotion) {
                listState.scrollToItem(activeIndex, scrollOffset = -180)
            } else {
                listState.animateScrollToItem(activeIndex, scrollOffset = -180)
            }
        }
    }

    val textAlign = if (alignment == LyricsAlignment.CENTER) TextAlign.Center else TextAlign.Start
    val originX = if (alignment == LyricsAlignment.CENTER) 0.5f else 0f
    val baseStyle = MaterialTheme.typography.titleLarge
    val scaledFontSize = baseStyle.fontSize * fontSize.scaleFactor
    val scaledLineHeight = baseStyle.lineHeight * fontSize.scaleFactor

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    if (size.height > 0f) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.08f to Color.Black,
                                0.90f to Color.Black,
                                1f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                },
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            itemsIndexed(lyrics.lines, key = { idx, line -> "${idx}_${line.id}" }) { index, line ->
                val isActive = index == activeIndex
                val distance = activeIndex?.let { abs(index - it) }
                val targetScale = when {
                    isActive -> 1.05f
                    distance == 1 -> 0.98f
                    distance == 2 -> 0.95f
                    else -> 0.92f
                }
                val targetAlpha = when {
                    isActive -> 1.0f
                    distance == 1 -> 0.62f
                    distance == 2 -> 0.44f
                    distance != null && distance >= 3 -> 0.28f
                    else -> 0.72f
                }
                val scale by animateFloatAsState(
                    targetValue = targetScale,
                    animationSpec = MusicoMotion.motionSpec(reduceMotion, MusicoMotion.ExpressiveSpring),
                    label = "lyric_line_scale"
                )
                val lineAlpha by animateFloatAsState(
                    targetValue = targetAlpha,
                    animationSpec = MusicoMotion.motionSpec(
                        reduceMotion,
                        tween(durationMillis = 220, easing = MusicoMotion.EmphasizedEasing),
                    ),
                    label = "lyric_line_alpha"
                )
                val color by animateColorAsState(
                    targetValue = if (isActive) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = MusicoMotion.motionSpec(
                        reduceMotion,
                        tween(durationMillis = 200, easing = MusicoMotion.EmphasizedEasing),
                    ),
                    label = "lyric_line_color"
                )
                Text(
                    text = line.text.ifEmpty { "♪" },
                    style = baseStyle.copy(
                        fontSize = scaledFontSize,
                        lineHeight = scaledLineHeight,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    ),
                    textAlign = textAlign,
                    color = color.copy(alpha = color.alpha * lineAlpha),
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(originX, 0.5f)
                        }
                        .clickable(enabled = line.timeSec != null) {
                            isUserScrollingPaused = false
                            line.timeSec?.let(onSeek)
                        }
                )
            }
            item(key = "bottom_pad") {
                Spacer(Modifier.height(160.dp))
            }
        }

        AnimatedVisibility(
            visible = isUserScrollingPaused && activeIndex != null,
            enter = if (reduceMotion) fadeIn(androidx.compose.animation.core.snap()) else fadeIn(),
            exit = if (reduceMotion) fadeOut(androidx.compose.animation.core.snap()) else fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    isUserScrollingPaused = false
                    val idx = activeIndex
                    if (idx != null && idx in lyrics.lines.indices) {
                        scope.launch {
                            if (reduceMotion) {
                                listState.scrollToItem(idx, scrollOffset = -180)
                            } else {
                                listState.animateScrollToItem(idx, scrollOffset = -180)
                            }
                        }
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.Rounded.MyLocation,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(strings.syncToCurrentLine)
            }
        }
    }
}

@Composable
private fun PlainLyricsList(
    lyrics: Lyrics,
    fontSize: LyricsFontSize,
    alignment: LyricsAlignment,
    modifier: Modifier = Modifier
) {
    val textAlign = if (alignment == LyricsAlignment.CENTER) TextAlign.Center else TextAlign.Start
    val baseStyle = MaterialTheme.typography.bodyLarge
    val scaledFontSize = baseStyle.fontSize * fontSize.scaleFactor
    val scaledLineHeight = baseStyle.lineHeight * fontSize.scaleFactor

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                if (size.height > 0f) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.06f to Color.Black,
                            0.92f to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        lyrics.lines.forEach { line ->
            Text(
                text = line.text,
                style = baseStyle.copy(
                    fontSize = scaledFontSize,
                    lineHeight = scaledLineHeight,
                ),
                textAlign = textAlign,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}
