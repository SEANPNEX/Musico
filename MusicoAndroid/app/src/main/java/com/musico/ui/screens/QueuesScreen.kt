package com.musico.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musico.audio.PlayerController
import com.musico.audio.PlayerUiState
import com.musico.model.PlayQueue
import com.musico.store.LibraryStore
import com.musico.store.LibraryUiState
import com.musico.store.QueueStore
import com.musico.store.QueueUiState
import com.musico.ui.components.ConfirmDialog
import com.musico.ui.components.EmptyState
import com.musico.ui.components.TextInputDialog
import com.musico.ui.components.TrackActionState
import com.musico.ui.components.TrackMenuItems
import com.musico.ui.components.TrackRow
import com.musico.ui.components.formatTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueuesScreen(
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    queueStore: QueueStore,
    queueState: QueueUiState,
    playerController: PlayerController,
    playerState: PlayerUiState,
    trackActions: TrackActionState,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Queues") },
                actions = {
                    if (queueState.active.trackIds.isNotEmpty()) {
                        TextButton(onClick = { editing = !editing }) {
                            Text(if (editing) "Done" else "Edit")
                        }
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear This Queue")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        QueuesContent(
            libraryStore = libraryStore,
            libraryState = libraryState,
            queueStore = queueStore,
            queueState = queueState,
            playerController = playerController,
            playerState = playerState,
            trackActions = trackActions,
            editing = editing,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Remove every song from “${queueState.active.name}”?",
            message = "Your songs remain in your library.",
            confirmLabel = "Clear Queue",
            onDismiss = { confirmClear = false },
            onConfirm = { playerController.clearQueue() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    queueStore: QueueStore,
    queueState: QueueUiState,
    playerController: PlayerController,
    playerState: PlayerUiState,
    trackActions: TrackActionState,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Queues",
                    style = MaterialTheme.typography.titleLarge
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (queueState.active.trackIds.isNotEmpty()) {
                        TextButton(onClick = { editing = !editing }) {
                            Text(if (editing) "Done" else "Edit")
                        }
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear Queue")
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Done")
                    }
                }
            }
            HorizontalDivider()
            QueuesContent(
                libraryStore = libraryStore,
                libraryState = libraryState,
                queueStore = queueStore,
                queueState = queueState,
                playerController = playerController,
                playerState = playerState,
                trackActions = trackActions,
                editing = editing,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
            )
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Remove every song from “${queueState.active.name}”?",
            message = "Your songs remain in your library.",
            confirmLabel = "Clear Queue",
            onDismiss = { confirmClear = false },
            onConfirm = { playerController.clearQueue() }
        )
    }
}

@Composable
fun QueuesContent(
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    queueStore: QueueStore,
    queueState: QueueUiState,
    playerController: PlayerController,
    playerState: PlayerUiState,
    trackActions: TrackActionState,
    editing: Boolean,
    modifier: Modifier = Modifier
) {
    var renamingQueue by remember { mutableStateOf<PlayQueue?>(null) }
    var deletingQueue by remember { mutableStateOf<PlayQueue?>(null) }
    var queueActionsExpanded by remember { mutableStateOf(false) }

    val active = queueState.active

    Column(modifier = modifier) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(queueState.queues, key = { it.id }) { queue ->
                val isSelected = queue.id == queueState.activeId
                FilterChip(
                    selected = isSelected,
                    onClick = { playerController.switchQueue(queue.id) },
                    label = {
                        Text(
                            text = queue.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = if (isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else null
                )
            }
            item(key = "add_queue") {
                AssistChip(
                    onClick = { queueStore.addQueue(name = null) },
                    label = { Text("New Queue") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val currentTrack = active.currentTrackId?.let { libraryStore.track(it) }
            val summary = if (currentTrack != null) {
                "${active.trackIds.size} songs · ${currentTrack.title} at ${formatTime(playerState.currentTimeSec)}"
            } else {
                "Empty"
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = active.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box {
                IconButton(onClick = { queueActionsExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Queue actions")
                }
                DropdownMenu(
                    expanded = queueActionsExpanded,
                    onDismissRequest = { queueActionsExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename Queue…") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = {
                            queueActionsExpanded = false
                            renamingQueue = active
                        }
                    )
                    if (queueState.queues.size > 1) {
                        DropdownMenuItem(
                            text = { Text("Delete Queue") },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                queueActionsExpanded = false
                                deletingQueue = active
                            }
                        )
                    }
                    if (active.trackIds.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Clear Queue") },
                            leadingIcon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
                            onClick = {
                                queueActionsExpanded = false
                                playerController.clearQueue()
                            }
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (active.trackIds.isEmpty()) {
                item(key = "empty_queue") {
                    EmptyState(
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        title = "Queue is Empty",
                        description = "This queue is empty. Tap a song in your library to fill it."
                    )
                }
            } else {
                itemsIndexed(
                    items = active.trackIds,
                    key = { index, id -> "${index}_$id" }
                ) { index, trackId ->
                    val track = libraryStore.track(trackId)
                    val isCurrentSlot = index == active.currentIndex
                    val rowBg = if (isCurrentSlot) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    } else {
                        Color.Transparent
                    }

                    if (track != null) {
                        TrackRow(
                            track = track,
                            artworkCache = libraryStore.artwork,
                            isCurrent = isCurrentSlot,
                            isPlaying = isCurrentSlot && playerState.isPlaying,
                            isAvailable = libraryStore.isAvailable(track),
                            backgroundColor = rowBg,
                            onClick = { playerController.jump(index) },
                            trailingActions = if (editing) {
                                {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { playerController.moveInQueue(index, index - 1) },
                                            enabled = index > 0
                                        ) {
                                            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move up")
                                        }
                                        IconButton(
                                            onClick = { playerController.moveInQueue(index, index + 1) },
                                            enabled = index < active.trackIds.lastIndex
                                        ) {
                                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move down")
                                        }
                                        IconButton(
                                            onClick = { playerController.removeFromQueue(index) }
                                        ) {
                                            Icon(
                                                Icons.Rounded.DeleteOutline,
                                                contentDescription = "Remove from queue",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            } else null,
                            menuContent = if (!editing) {
                                { onDismiss ->
                                    TrackMenuItems(
                                        ids = listOf(track.id),
                                        queues = queueState.queues,
                                        activeQueueId = active.id,
                                        actionState = trackActions,
                                        onDismiss = onDismiss,
                                        favouriteId = track.id,
                                        isFavourite = track.id in libraryState.favourites
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Remove from Queue") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Rounded.DeleteOutline,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        onClick = {
                                            onDismiss()
                                            playerController.removeFromQueue(index)
                                        }
                                    )
                                }
                            } else null
                        )
                    } else {
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = "Missing song",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { playerController.removeFromQueue(index) }) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove")
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    val renaming = renamingQueue
    if (renaming != null) {
        TextInputDialog(
            title = "Rename Queue",
            label = "Name",
            initialValue = renaming.name,
            confirmLabel = "Rename",
            onDismiss = { renamingQueue = null },
            onConfirm = { newName -> queueStore.rename(renaming.id, newName) }
        )
    }

    val deleting = deletingQueue
    if (deleting != null) {
        ConfirmDialog(
            title = "Delete “${deleting.name}”?",
            message = "This queue will be deleted. Your songs remain in your library.",
            confirmLabel = "Delete",
            onDismiss = { deletingQueue = null },
            onConfirm = { playerController.deleteQueue(deleting.id) }
        )
    }
}
