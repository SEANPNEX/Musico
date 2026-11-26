package com.musico.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musico.audio.PlayerUiState
import com.musico.core.ArtworkCache
import com.musico.i18n.LocalStrings
import com.musico.ui.components.ArtworkImage
import com.musico.ui.theme.LocalHasBackgroundImage

@Composable
fun MiniPlayer(
    playerState: PlayerUiState,
    artworkCache: ArtworkCache,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpenFullPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    applyNavBarPadding: Boolean = false
) {
    val strings = LocalStrings.current
    val track = playerState.currentTrack ?: return
    val duration = playerState.durationSec.coerceAtLeast(0.01)
    val progress = (playerState.currentTimeSec / duration).toFloat().coerceIn(0f, 1f)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenFullPlayer),
        color = if (LocalHasBackgroundImage.current) {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.84f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        tonalElevation = if (LocalHasBackgroundImage.current) 0.dp else 3.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (playerState.isLoadingCloud) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { if (applyNavBarPadding) it.navigationBarsPadding() else it }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtworkImage(
                    artworkKey = track.artworkKey,
                    artworkCache = artworkCache,
                    remoteArtworkUrl = track.remoteArtworkUrl,
                    size = 48.dp,
                    cornerRadius = 8.dp
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = strings.displayArtist(track.artist),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        imageVector = if (playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playerState.isPlaying) strings.pause else strings.play
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = strings.next
                    )
                }
            }
        }
    }
}
