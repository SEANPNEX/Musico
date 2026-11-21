package com.musico.ui.player

import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musico.audio.PlayerController
import com.musico.audio.PlayerUiState
import com.musico.core.LyricsStore
import com.musico.core.LyricsUnreadableException
import com.musico.model.Lyrics
import com.musico.model.LyricsSource
import com.musico.store.LibraryStore
import com.musico.ui.components.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSheet(
    libraryStore: LibraryStore,
    lyricsStore: LyricsStore,
    playerController: PlayerController,
    playerState: PlayerUiState,
    onPickLyricsFile: ((Uri) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        LyricsContent(
            libraryStore = libraryStore,
            lyricsStore = lyricsStore,
            playerController = playerController,
            playerState = playerState,
            onPickLyricsFile = onPickLyricsFile,
            embedded = false,
            onDone = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        )
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
    embedded: Boolean = false,
    onDone: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val track = playerState.currentTrack

    var revision by remember { mutableIntStateOf(0) }
    var resolvedLyrics by remember { mutableStateOf<Lyrics?>(null) }
    var resolvedSource by remember { mutableStateOf<LyricsSource?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(track?.id, revision) {
        if (track == null) {
            resolvedLyrics = null
            resolvedSource = null
            return@LaunchedEffect
        }
        val trackId = track.id
        val found = withContext(Dispatchers.IO) {
            val embeddedText = libraryStore.embeddedLyrics(track)
            val siblingBytes = libraryStore.readSiblingLyrics(track)
            lyricsStore.resolve(
                trackId = trackId,
                audioUri = libraryStore.uriFor(track),
                siblingProvider = { siblingBytes },
                embeddedText = embeddedText
            )
        }
        if (playerState.currentTrack?.id == trackId) {
            resolvedLyrics = found?.first
            resolvedSource = found?.second
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
                    importError = err.message ?: "That file could not be read as lyrics text."
                }
            }
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track?.title ?: "Lyrics",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val source = resolvedSource
                if (source != null) {
                    Spacer(Modifier.height(2.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = sourceBadgeLabel(source),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    enabled = track != null
                ) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Lyrics options")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    resolvedSource?.let { source ->
                        DropdownMenuItem(
                            text = { Text(sourceDescription(source)) },
                            enabled = false,
                            onClick = {}
                        )
                        HorizontalDivider()
                    }
                    DropdownMenuItem(
                        text = { Text("Import from File…") },
                        leadingIcon = { Icon(Icons.Rounded.FileOpen, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            launchImport()
                        }
                    )
                    if (track != null && lyricsStore.hasImported(track.id)) {
                        DropdownMenuItem(
                            text = { Text("Remove Imported Lyrics") },
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
                }
            }

            if (!embedded && onDone != null) {
                TextButton(onClick = onDone) {
                    Text("Done")
                }
            }
        }

        HorizontalDivider()

        val lyrics = resolvedLyrics
        if (lyrics == null) {
            EmptyState(
                icon = Icons.Rounded.Lyrics,
                title = "No Lyrics",
                description = "Import a .lrc or .txt file for this song, or put one with the same name next to it.",
                actionLabel = if (track != null) "Import from File…" else null,
                onAction = if (track != null) ({ launchImport() }) else null
            )
        } else if (lyrics.isTimed) {
            TimedLyricsList(
                lyrics = lyrics,
                currentTimeSec = playerState.currentTimeSec,
                onSeek = { playerController.seek(it) },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            PlainLyricsList(
                lyrics = lyrics,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    val err = importError
    if (err != null) {
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Couldn’t Import Lyrics") },
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
private fun TimedLyricsList(
    lyrics: Lyrics,
    currentTimeSec: Double,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeIndex = lyrics.lineIndexAt(currentTimeSec)
    val listState = rememberLazyListState()

    LaunchedEffect(activeIndex) {
        if (activeIndex != null && activeIndex in lyrics.lines.indices) {
            listState.animateScrollToItem(activeIndex, scrollOffset = -180)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        itemsIndexed(lyrics.lines, key = { idx, line -> "${idx}_${line.id}" }) { index, line ->
            val isActive = index == activeIndex
            Text(
                text = line.text.ifEmpty { "♪" },
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                ),
                color = if (isActive) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = line.timeSec != null) {
                        line.timeSec?.let(onSeek)
                    }
            )
        }
        item(key = "bottom_pad") {
            Spacer(Modifier.height(160.dp))
        }
    }
}

@Composable
private fun PlainLyricsList(
    lyrics: Lyrics,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        lyrics.lines.forEach { line ->
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun sourceBadgeLabel(source: LyricsSource): String = when (source) {
    LyricsSource.IMPORTED -> "Imported"
    LyricsSource.SIDECAR -> "Sidecar"
    LyricsSource.EMBEDDED -> "Embedded"
}

private fun sourceDescription(source: LyricsSource): String = when (source) {
    LyricsSource.IMPORTED -> "From the file you imported"
    LyricsSource.SIDECAR -> "From a file next to the song"
    LyricsSource.EMBEDDED -> "From the song’s tags"
}
