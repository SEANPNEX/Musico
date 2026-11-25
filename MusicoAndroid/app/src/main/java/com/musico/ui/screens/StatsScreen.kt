package com.musico.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musico.audio.PlayerUiState
import com.musico.i18n.AppStrings
import com.musico.i18n.LanguageStore
import com.musico.i18n.LocalStrings
import com.musico.model.PlayQueue
import com.musico.store.LibraryStore
import com.musico.store.LibraryUiState
import com.musico.store.PlayHistoryStore
import com.musico.store.PlayHistoryUiState
import com.musico.ui.components.EmptyState
import com.musico.ui.components.LanguageDialog
import com.musico.ui.components.PlayShuffleButtons
import com.musico.ui.components.TrackActionState
import com.musico.ui.components.TrackMenuItems
import com.musico.ui.components.TrackRow
import kotlin.random.Random

enum class StatsSection {
    RECENT,
    PLAYS,
    NOT_LATELY,
    NEVER;

    fun label(strings: AppStrings): String = when (this) {
        RECENT -> strings.statsSectionRecent
        PLAYS -> strings.statsSectionPlays
        NOT_LATELY -> strings.statsSectionNotLately
        NEVER -> strings.statsSectionNever
    }

    fun emptyTitle(strings: AppStrings): String = when (this) {
        RECENT -> strings.statsEmptyRecentTitle
        PLAYS -> strings.statsEmptyPlaysTitle
        NOT_LATELY -> strings.statsEmptyNotLatelyTitle
        NEVER -> strings.statsEmptyEverythingPlayedTitle
    }

    fun emptyText(strings: AppStrings): String = when (this) {
        RECENT -> strings.statsEmptyRecentDesc
        PLAYS -> strings.statsEmptyPlaysDesc
        NOT_LATELY -> strings.statsEmptyNotLatelyDesc(PlayHistoryStore.STALE_AFTER_DAYS)
        NEVER -> strings.statsEmptyEverythingPlayedDesc
    }
}

enum class CountOrder {
    MOST,
    LEAST;

    fun label(strings: AppStrings): String = when (this) {
        MOST -> strings.countOrderMost
        LEAST -> strings.countOrderLeast
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    historyStore: PlayHistoryStore,
    historyState: PlayHistoryUiState,
    playerState: PlayerUiState,
    queues: List<PlayQueue>,
    activeQueue: PlayQueue,
    trackActions: TrackActionState,
    languageStore: LanguageStore? = null,
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current
    var showLanguageDialog by remember { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf(StatsSection.RECENT) }
    var countOrder by rememberSaveable { mutableStateOf(CountOrder.MOST) }
    var orderMenuExpanded by remember { mutableStateOf(false) }

    val neverPlayedCount = remember(libraryState.tracks, historyState.entries) {
        historyStore.neverPlayed(libraryState.tracks).size
    }

    val tracks = remember(libraryState.tracks, historyState.entries, section, countOrder) {
        when (section) {
            StatsSection.RECENT -> historyStore.recentlyPlayed(libraryState.tracks)
            StatsSection.PLAYS -> if (countOrder == CountOrder.MOST) {
                historyStore.mostPlayed(libraryState.tracks)
            } else {
                historyStore.leastPlayed(libraryState.tracks)
            }
            StatsSection.NOT_LATELY -> historyStore.notPlayedLately(libraryState.tracks)
            StatsSection.NEVER -> historyStore.neverPlayed(libraryState.tracks)
        }
    }
    val trackIds = remember(tracks) { tracks.map { it.id } }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(strings.statsTitle) },
                actions = {
                    if (languageStore != null) {
                        IconButton(onClick = { showLanguageDialog = true }) {
                            Icon(
                                imageVector = Icons.Rounded.Language,
                                contentDescription = strings.languageTitle,
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item(key = "totals_card") {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .padding(vertical = 14.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatTotalCell(
                            value = historyState.totalPlays.toString(),
                            label = strings.statPlaysLabel(historyState.totalPlays),
                            modifier = Modifier.weight(1f)
                        )
                        VerticalDivider(modifier = Modifier.fillMaxHeight())
                        StatTotalCell(
                            value = strings.formatListeningTime(historyState.totalSeconds),
                            label = strings.statListenedLabel,
                            modifier = Modifier.weight(1f)
                        )
                        VerticalDivider(modifier = Modifier.fillMaxHeight())
                        StatTotalCell(
                            value = neverPlayedCount.toString(),
                            label = strings.statNeverPlayedLabel,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item(key = "section_picker") {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    StatsSection.entries.forEachIndexed { index, item ->
                        SegmentedButton(
                            selected = section == item,
                            onClick = { section = item },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = StatsSection.entries.size
                            ),
                            label = {
                                Text(
                                    text = item.label(strings),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }

            if (section == StatsSection.PLAYS) {
                item(key = "count_order") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        TextButton(onClick = { orderMenuExpanded = true }) {
                            Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null)
                            Spacer(Modifier.padding(horizontal = 4.dp))
                            Text(countOrder.label(strings))
                        }
                        DropdownMenu(
                            expanded = orderMenuExpanded,
                            onDismissRequest = { orderMenuExpanded = false }
                        ) {
                            CountOrder.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label(strings)) },
                                    trailingIcon = {
                                        if (countOrder == option) {
                                            Icon(Icons.Rounded.Check, contentDescription = null)
                                        }
                                    },
                                    onClick = {
                                        countOrder = option
                                        orderMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (tracks.isEmpty()) {
                item(key = "empty_stats") {
                    EmptyState(
                        icon = Icons.Rounded.BarChart,
                        title = section.emptyTitle(strings),
                        description = section.emptyText(strings)
                    )
                }
            } else {
                item(key = "play_shuffle") {
                    PlayShuffleButtons(
                        enabled = trackIds.isNotEmpty(),
                        onPlay = {
                            trackActions.playerController.playInNamedQueue(
                                name = "Stats",
                                ids = trackIds,
                                startAt = 0,
                                shuffle = false,
                            )
                        },
                        onShuffle = {
                            trackActions.playerController.playInNamedQueue(
                                name = "Stats",
                                ids = trackIds,
                                startAt = Random.nextInt(trackIds.size.coerceAtLeast(1)),
                                shuffle = true,
                            )
                        }
                    )
                }

                itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                    val count = historyStore.count(track.id)
                    val plays = strings.songPlaysCount(count)
                    val lastPlayedMs = historyStore.lastPlayed(track.id)
                    val displayArtist = strings.displayArtist(track.artist)
                    val caption = when (section) {
                        StatsSection.RECENT, StatsSection.NOT_LATELY -> {
                            listOfNotNull(
                                lastPlayedMs?.let { strings.formatRelativeTime(it) },
                                displayArtist
                            ).joinToString(" · ")
                        }
                        StatsSection.PLAYS -> "$plays · $displayArtist"
                        StatsSection.NEVER -> displayArtist
                    }

                    TrackRow(
                        track = track,
                        artworkCache = libraryStore.artwork,
                        isCurrent = playerState.currentTrack?.id == track.id,
                        isPlaying = playerState.isPlaying,
                        isAvailable = libraryStore.isAvailable(track),
                        subtitle = caption,
                        onClick = {
                            trackActions.playerController.playInNamedQueue(
                                name = "Stats",
                                ids = trackIds,
                                startAt = index,
                                shuffle = false,
                            )
                        },
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
    }

    if (showLanguageDialog && languageStore != null) {
        val currentLanguage by languageStore.language.collectAsStateWithLifecycle()
        LanguageDialog(
            current = currentLanguage,
            onSelect = { languageStore.setLanguage(it) },
            onDismiss = { showLanguageDialog = false },
        )
    }
}

@Composable
private fun StatTotalCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum"
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}
