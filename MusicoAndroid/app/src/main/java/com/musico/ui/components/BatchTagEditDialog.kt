package com.musico.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musico.i18n.LocalStrings
import com.musico.model.Track

@Composable
fun BatchSelectionBar(
    selectedCount: Int,
    totalCount: Int,
    onToggleSelectAll: () -> Unit,
    onEditTags: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val allSelected = totalCount > 0 && selectedCount >= totalCount
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = strings.selectedCountLabel(selectedCount),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onToggleSelectAll,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (allSelected) strings.deselectAllAction else strings.selectAllAction,
                    maxLines = 1,
                )
            }
            FilledTonalButton(
                onClick = onEditTags,
                enabled = selectedCount > 0,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(strings.editTagsAction, maxLines = 1)
            }
            TextButton(
                onClick = onDone,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(strings.done, maxLines = 1)
            }
        }
    }
}

@Composable
fun BatchTagEditDialog(
    tracks: List<Track>,
    onDismiss: () -> Unit,
    onApply: (artist: String?, album: String?) -> Unit,
) {
    val strings = LocalStrings.current
    val distinctArtists = remember(tracks) {
        tracks.map { it.artist.trim() }.distinct()
    }
    val distinctAlbums = remember(tracks) {
        tracks.map { it.album.trim() }.distinct()
    }
    val initialArtist = remember(distinctArtists) {
        if (distinctArtists.size == 1) distinctArtists.first() else ""
    }
    val initialAlbum = remember(distinctAlbums) {
        if (distinctAlbums.size == 1) distinctAlbums.first() else ""
    }
    val isArtistMixed = distinctArtists.size > 1
    val isAlbumMixed = distinctAlbums.size > 1

    var artistText by remember(tracks) { mutableStateOf(initialArtist) }
    var albumText by remember(tracks) { mutableStateOf(initialAlbum) }

    val artistToApply = artistText.trim().takeIf { next ->
        next.isNotEmpty() && (isArtistMixed || next != initialArtist)
    }
    val albumToApply = albumText.trim().takeIf { next ->
        next.isNotEmpty() && (isAlbumMixed || next != initialAlbum)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(strings.batchEditTagsTitle)
                Text(
                    text = strings.songCount(tracks.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = artistText,
                    onValueChange = { artistText = it },
                    label = { Text(strings.artistLabel) },
                    placeholder = if (isArtistMixed) {
                        {
                            Text(
                                strings.mixedValuesPlaceholder,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = albumText,
                    onValueChange = { albumText = it },
                    label = { Text(strings.albumLabel) },
                    placeholder = if (isAlbumMixed) {
                        {
                            Text(
                                strings.mixedValuesPlaceholder,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onApply(artistToApply, albumToApply) },
                enabled = artistToApply != null || albumToApply != null,
            ) {
                Text(strings.applyAction)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) {
                    Text(strings.cancel)
                }
                Spacer(Modifier.width(4.dp))
            }
        },
    )
}
