package com.musico.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musico.core.AudioFileInfo
import com.musico.core.AudioFileInfoReader
import com.musico.i18n.AppStrings
import com.musico.i18n.LocalStrings
import com.musico.model.SearchField
import com.musico.model.Track
import com.musico.store.LibraryStore
import com.musico.store.PlayHistoryUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SongInfoSheet(
    track: Track,
    libraryStore: LibraryStore,
    historyState: PlayHistoryUiState,
    onPickImage: ((Uri) -> Unit) -> Unit,
    onQuickSearch: (query: String, field: SearchField) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var title by remember(track.id) { mutableStateOf(track.title) }
    var artist by remember(track.id) { mutableStateOf(track.artist) }
    var album by remember(track.id) { mutableStateOf(track.album) }
    var genre by remember(track.id) { mutableStateOf(track.genre) }
    var coverError by remember(track.id) { mutableStateOf<String?>(null) }

    val hasTextEdits = title.trim() != track.title ||
        artist.trim() != track.artist ||
        album.trim() != track.album ||
        genre.trim() != track.genre

    val audioInfo by produceState<AudioFileInfo?>(initialValue = null, key1 = track.id, key2 = track.modifiedEpochMs) {
        value = withContext(Dispatchers.IO) {
            val uri = libraryStore.uriFor(track)
            AudioFileInfoReader.read(
                context = context,
                uri = uri,
                fileSize = track.fileSize,
                durationSec = track.durationSec,
            )
        }
    }

    val historyEntry = historyState.entries[track.id]
    val playCount = historyEntry?.count ?: 0
    val lastPlayedMs = historyEntry?.lastPlayedEpochMs

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = strings.songInfo,
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = onDismiss) {
                    Text(strings.close)
                }
            }

            // Top Section: Editable Metadata + Cover Image
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ArtworkImage(
                    artworkKey = track.artworkKey,
                    artworkCache = libraryStore.artwork,
                    size = 88.dp,
                    cornerRadius = 12.dp,
                    remoteArtworkUrl = track.remoteArtworkUrl,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = track.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!track.isEphemeral) {
                        OutlinedButton(
                            onClick = {
                                onPickImage { uri ->
                                    scope.launch {
                                        coverError = null
                                        val newKey = withContext(Dispatchers.IO) {
                                            val bytes = runCatching {
                                                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                            }.getOrNull()
                                            if (bytes != null && bytes.isNotEmpty()) {
                                                libraryStore.artwork.store(bytes)
                                            } else {
                                                null
                                            }
                                        }
                                        if (newKey != null) {
                                            libraryStore.updateTrackMetadata(
                                                trackId = track.id,
                                                artworkKey = newKey,
                                            )
                                        } else {
                                            coverError = strings.couldNotLoadSelectedImage
                                        }
                                    }
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Image,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(strings.changeCover)
                        }
                    }
                    if (coverError != null) {
                        Text(
                            text = coverError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { if (!track.isEphemeral) title = it },
                readOnly = track.isEphemeral,
                label = { Text(strings.titleLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = artist,
                onValueChange = { if (!track.isEphemeral) artist = it },
                readOnly = track.isEphemeral,
                label = { Text(strings.artistLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = album,
                onValueChange = { if (!track.isEphemeral) album = it },
                readOnly = track.isEphemeral,
                label = { Text(strings.albumLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!track.isEphemeral || genre.isNotBlank()) {
                OutlinedTextField(
                    value = genre,
                    onValueChange = { if (!track.isEphemeral) genre = it },
                    readOnly = track.isEphemeral,
                    label = { Text(strings.genreLabel) },
                    placeholder = { Text(strings.addGenrePlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (!track.isEphemeral) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        enabled = hasTextEdits && title.trim().isNotEmpty(),
                        onClick = {
                            libraryStore.updateTrackMetadata(
                                trackId = track.id,
                                title = title.trim(),
                                artist = artist.trim().ifEmpty { "Unknown Artist" },
                                album = album.trim().ifEmpty { "Unknown Album" },
                                genre = genre.trim(),
                            )
                        },
                    ) {
                        Text(strings.saveChanges)
                    }
                }
            }

            // Metadata Quick-Search Links
            val artistValue = artist.trim()
            val albumValue = album.trim()
            val genreValue = genre.trim()
            if (artistValue.isNotEmpty() || albumValue.isNotEmpty() || genreValue.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = strings.quickSearch,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (artistValue.isNotEmpty()) {
                            AssistChip(
                                onClick = {
                                    onDismiss()
                                    onQuickSearch(artistValue, SearchField.ARTIST)
                                },
                                label = { Text("${strings.searchFieldArtist}: ${strings.displayArtist(artistValue)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                        if (albumValue.isNotEmpty()) {
                            AssistChip(
                                onClick = {
                                    onDismiss()
                                    onQuickSearch(albumValue, SearchField.ALBUM)
                                },
                                label = { Text("${strings.searchFieldAlbum}: ${strings.displayAlbum(albumValue)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                        if (genreValue.isNotEmpty()) {
                            AssistChip(
                                onClick = {
                                    onDismiss()
                                    onQuickSearch(genreValue, SearchField.GENRE)
                                },
                                label = { Text("${strings.searchFieldGenre}: $genreValue", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // Bottom Section: File & Playback Info
            Text(
                text = strings.fileAndPlaybackInfo,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )

            val info = audioInfo
            val fileLocation = remember(track.id, track.rootId, track.relativePath) {
                libraryStore.displayLocation(track)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow(strings.infoFileName, track.fileName, multiline = true)
                InfoRow(strings.infoFileLocation, fileLocation, multiline = true)
                InfoRow(strings.infoPrecision, info?.bitDepth?.let { strings.bitDepthFormat(it) } ?: strings.notAvailable)
                InfoRow(strings.infoFrequency, formatSampleRate(info?.sampleRateHz, strings))
                InfoRow(strings.infoBitRate, info?.bitRateBps?.let { "${(it + 500L) / 1000L} kbps" } ?: strings.notAvailable)
                InfoRow(strings.infoFileSize, formatFileSize(track.fileSize, strings))
                InfoRow(strings.infoLength, formatTime(track.durationSec))
                InfoRow(strings.infoSoundTracks, formatChannels(info?.channelCount, strings))
                InfoRow(strings.infoTimeAdded, formatEpochDateTime(track.dateAddedEpochMs, fallback = strings.notAvailable, locale = strings.locale))
                InfoRow(strings.infoTimeEdited, formatEpochDateTime(track.modifiedEpochMs, fallback = strings.notAvailable, locale = strings.locale))
                InfoRow(strings.infoLastPlayed, formatEpochDateTime(lastPlayedMs, fallback = strings.neverLabel, locale = strings.locale))
                InfoRow(strings.infoPlayedTimes, playCount.toString())
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    multiline: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.End,
            maxLines = if (multiline) Int.MAX_VALUE else 1,
            overflow = if (multiline) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

private fun formatSampleRate(hz: Int?, strings: AppStrings): String {
    if (hz == null || hz <= 0) return strings.notAvailable
    val khz = if (hz % 1000 == 0) {
        "${hz / 1000} kHz"
    } else {
        String.format(Locale.US, "%.1f kHz", hz / 1000.0)
    }
    return "$khz ($hz Hz)"
}

private fun formatChannels(channels: Int?, strings: AppStrings): String {
    if (channels == null || channels <= 0) return strings.notAvailable
    return when (channels) {
        1 -> strings.channelsMono
        2 -> strings.channelsStereo
        else -> strings.channelsCount(channels)
    }
}

private fun formatFileSize(bytes: Long, strings: AppStrings): String {
    if (bytes <= 0L) return strings.notAvailable
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

private fun formatEpochDateTime(epochMs: Long?, fallback: String, locale: Locale): String {
    if (epochMs == null || epochMs <= 0L) return fallback
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(epochMs))
}
