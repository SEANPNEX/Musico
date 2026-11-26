package com.musico.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.musico.audio.PlayerController
import com.musico.audio.PlayerUiState
import com.musico.i18n.AppStrings
import com.musico.i18n.LocalStrings
import com.musico.model.PlayQueue
import com.musico.store.LibraryStore
import com.musico.store.LibraryUiState
import com.musico.store.PlayHistoryUiState
import com.musico.store.QueueStore
import com.musico.store.QueueUiState
import com.musico.ui.LocalShowMessage
import com.musico.ui.LocalSnackbarHost
import com.musico.ui.components.ConfirmDialog
import com.musico.ui.components.EmptyState
import com.musico.ui.components.SortOrderMenuButton
import com.musico.ui.components.TextInputDialog
import com.musico.ui.components.TrackActionState
import com.musico.ui.components.TrackMenuItems
import com.musico.ui.components.TrackRow
import com.musico.ui.components.dragHandle
import com.musico.ui.components.formatTime
import com.musico.ui.components.rememberDragReorderState
import com.musico.ui.theme.musicoListItemColors
import com.musico.ui.theme.musicoScaffoldContainerColor
import com.musico.ui.theme.musicoScaffoldContentColor
import com.musico.ui.theme.musicoTopAppBarColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueuesScreen(
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    historyState: PlayHistoryUiState,
    queueStore: QueueStore,
    queueState: QueueUiState,
    playerController: PlayerController,
    playerState: PlayerUiState,
    trackActions: TrackActionState,
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current
    val showMessage = LocalShowMessage.current
    val viewed = queueState.viewed
    var editing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        containerColor = musicoScaffoldContainerColor(),
        contentColor = musicoScaffoldContentColor(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(strings.queuesTitle) },
                colors = musicoTopAppBarColors(),
                actions = {
                    if (viewed.trackIds.isNotEmpty()) {
                        TextButton(onClick = { editing = !editing }) {
                            Text(if (editing) strings.done else strings.edit)
                        }
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = strings.clearThisQueue)
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        QueuesContent(
            libraryStore = libraryStore,
            libraryState = libraryState,
            historyState = historyState,
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
            title = strings.clearQueueConfirmTitle(strings.displayQueueName(viewed.name)),
            message = strings.clearQueueConfirmMessage,
            confirmLabel = strings.clearQueue,
            onDismiss = { confirmClear = false },
            onConfirm = {
                val snapshot = viewed
                playerController.clearQueue(queueId = viewed.id)
                showMessage(
                    message = strings.clearedQueueNotice,
                    actionLabel = strings.undo,
                    onAction = { playerController.restoreQueueSnapshot(snapshot) },
                )
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    historyState: PlayHistoryUiState,
    queueStore: QueueStore,
    queueState: QueueUiState,
    playerController: PlayerController,
    playerState: PlayerUiState,
    trackActions: TrackActionState,
    onDismiss: () -> Unit
) {
    val strings = LocalStrings.current
    val showMessage = LocalShowMessage.current
    val snackbarHostState = LocalSnackbarHost.current
    val viewed = queueState.viewed
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = strings.queuesTitle,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (viewed.trackIds.isNotEmpty()) {
                            TextButton(onClick = { editing = !editing }) {
                                Text(if (editing) strings.done else strings.edit)
                            }
                            IconButton(onClick = { confirmClear = true }) {
                                Icon(Icons.Rounded.DeleteSweep, contentDescription = strings.clearQueue)
                            }
                        }
                        TextButton(onClick = onDismiss) {
                            Text(strings.done)
                        }
                    }
                }
                HorizontalDivider()
                QueuesContent(
                    libraryStore = libraryStore,
                    libraryState = libraryState,
                    historyState = historyState,
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
            if (snackbarHostState != null) {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = strings.clearQueueConfirmTitle(strings.displayQueueName(viewed.name)),
            message = strings.clearQueueConfirmMessage,
            confirmLabel = strings.clearQueue,
            onDismiss = { confirmClear = false },
            onConfirm = {
                val snapshot = viewed
                playerController.clearQueue(queueId = viewed.id)
                showMessage(
                    message = strings.clearedQueueNotice,
                    actionLabel = strings.undo,
                    onAction = { playerController.restoreQueueSnapshot(snapshot) },
                )
            }
        )
    }
}

@Composable
fun QueuesContent(
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    historyState: PlayHistoryUiState,
    queueStore: QueueStore,
    queueState: QueueUiState,
    playerController: PlayerController,
    playerState: PlayerUiState,
    trackActions: TrackActionState,
    editing: Boolean,
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current
    val showMessage = LocalShowMessage.current
    var renamingQueue by remember { mutableStateOf<PlayQueue?>(null) }
    var deletingQueue by remember { mutableStateOf<PlayQueue?>(null) }
    var queueActionsExpanded by remember { mutableStateOf(false) }
    var showQueueSwitcher by remember { mutableStateOf(false) }

    val viewed = queueState.viewed
    val isViewingPlayingQueue = viewed.id == queueState.activeId
    val viewedSummary = queueSummaryText(
        queue = viewed,
        isActive = isViewingPlayingQueue,
        activePositionSec = playerState.currentTimeSec,
        libraryStore = libraryStore,
        strings = strings,
    )

    Column(modifier = modifier) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(queueState.queues, key = { it.id }) { queue ->
                val isViewedChip = queue.id == viewed.id
                val isPlayingChip = queue.id == queueState.activeId
                FilterChip(
                    selected = isViewedChip,
                    onClick = { queueStore.selectViewedQueue(queue.id) },
                    label = {
                        Text(
                            text = strings.displayQueueName(queue.name),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = if (isPlayingChip) {
                        {
                            Icon(
                                imageVector = if (playerState.isPlaying) {
                                    Icons.Rounded.GraphicEq
                                } else {
                                    Icons.AutoMirrored.Rounded.VolumeUp
                                },
                                contentDescription = strings.playing,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else null,
                )
            }
        }

        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showQueueSwitcher = true }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .animateContentSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (isViewingPlayingQueue) {
                                Icon(
                                    imageVector = if (playerState.isPlaying) {
                                        Icons.Rounded.GraphicEq
                                    } else {
                                        Icons.AutoMirrored.Rounded.VolumeUp
                                    },
                                    contentDescription = strings.playing,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Text(
                                text = strings.displayQueueName(viewed.name),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    initialDelayMillis = 1500,
                                    repeatDelayMillis = 1500,
                                ),
                            )
                        }
                        Text(
                            text = viewedSummary,
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = CircleShape,
                    ) {
                        Text(
                            text = "${queueState.queues.size}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontFeatureSettings = "tnum",
                            ),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Rounded.UnfoldMore,
                        contentDescription = strings.switchQueue,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { queueStore.addQueue(name = null) }) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = strings.newQueue,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    if (viewed.trackIds.isNotEmpty()) {
                        SortOrderMenuButton(
                            current = viewed.sortOrder,
                            onSelect = { order -> playerController.sortQueue(order, queueId = viewed.id) },
                            contentDescription = strings.sortQueue,
                        )
                    }

                    Box {
                        IconButton(onClick = { queueActionsExpanded = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = strings.queueActions)
                        }
                        DropdownMenu(
                            expanded = queueActionsExpanded,
                            onDismissRequest = { queueActionsExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(strings.renameQueueEllipsis) },
                                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                onClick = {
                                    queueActionsExpanded = false
                                    renamingQueue = viewed
                                }
                            )
                            if (queueState.queues.size > 1) {
                                DropdownMenuItem(
                                    text = { Text(strings.deleteQueue) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Rounded.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        queueActionsExpanded = false
                                        deletingQueue = viewed
                                    }
                                )
                            }
                            if (viewed.trackIds.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(strings.clearQueue) },
                                    leadingIcon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
                                    onClick = {
                                        queueActionsExpanded = false
                                        val snapshot = viewed
                                        playerController.clearQueue(queueId = viewed.id)
                                        showMessage(
                                            message = strings.clearedQueueNotice,
                                            actionLabel = strings.undo,
                                            onAction = { playerController.restoreQueueSnapshot(snapshot) },
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        val listState = rememberLazyListState()
        val dragState = rememberDragReorderState(
            items = viewed.trackIds,
            listState = listState,
            headerCount = 0,
            scopeKey = viewed.id,
            keyOf = { it },
            onCommit = { reordered ->
                playerController.reorderQueue(
                    newOrderIndices = reordered.map { it.originalIndex },
                    queueId = viewed.id,
                )
            },
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (viewed.trackIds.isEmpty()) {
                item(key = "empty_queue") {
                    EmptyState(
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        title = strings.queueIsEmptyTitle,
                        description = strings.queueIsEmptyDesc
                    )
                }
            } else {
                itemsIndexed(
                    items = dragState.items,
                    key = { _, item -> item.uid }
                ) { index, dragItem ->
                    val trackId = dragItem.value
                    val slotIndex = dragItem.originalIndex
                    val track = libraryStore.track(trackId)
                    val isCurrentSlot = isViewingPlayingQueue && slotIndex == viewed.currentIndex
                    val isDragged = dragState.activeUid == dragItem.uid
                    val rowBg = when {
                        isDragged -> MaterialTheme.colorScheme.surfaceContainerHighest
                        isCurrentSlot -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else -> Color.Transparent
                    }
                    val rowModifier = Modifier
                        .animateItem(
                            placementSpec = if (dragState.isDragging || dragState.isSettling) {
                                null
                            } else {
                                spring(
                                    stiffness = Spring.StiffnessMediumLow,
                                    visibilityThreshold = IntOffset.VisibilityThreshold,
                                )
                            }
                        )
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = dragState.offsetFor(dragItem.uid) }

                    if (track != null) {
                        TrackRow(
                            track = track,
                            artworkCache = libraryStore.artwork,
                            isCurrent = isCurrentSlot,
                            isPlaying = isCurrentSlot && playerState.isPlaying,
                            isAvailable = libraryStore.isAvailable(track),
                            backgroundColor = rowBg,
                            onClick = { playerController.playTrack(inQueue = viewed.id, index = index) },
                            modifier = rowModifier,
                            dragHandleModifier = Modifier.dragHandle(dragState, dragItem.uid),
                            trailingActions = if (editing) {
                                {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = {
                                                playerController.moveInQueue(
                                                    fromIndex = index,
                                                    toIndex = index - 1,
                                                    queueId = viewed.id,
                                                )
                                            },
                                            enabled = index > 0
                                        ) {
                                            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = strings.moveUp)
                                        }
                                        IconButton(
                                            onClick = {
                                                playerController.moveInQueue(
                                                    fromIndex = index,
                                                    toIndex = index + 1,
                                                    queueId = viewed.id,
                                                )
                                            },
                                            enabled = index < viewed.trackIds.lastIndex
                                        ) {
                                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = strings.moveDown)
                                        }
                                        IconButton(
                                            onClick = {
                                                playerController.removeFromQueue(
                                                    index = index,
                                                    queueId = viewed.id,
                                                )
                                            }
                                        ) {
                                            Icon(
                                                Icons.Rounded.DeleteOutline,
                                                contentDescription = strings.removeFromQueueCd,
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
                                        activeQueueId = viewed.id,
                                        actionState = trackActions,
                                        onDismiss = onDismiss,
                                        favouriteId = track.id,
                                        isFavourite = track.id in libraryState.favourites,
                                        addTargetQueueId = viewed.id,
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(strings.removeFromQueue) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Rounded.DeleteOutline,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        onClick = {
                                            onDismiss()
                                            playerController.removeFromQueue(
                                                index = index,
                                                queueId = viewed.id,
                                            )
                                        }
                                    )
                                }
                            } else null
                        )
                    } else {
                        ListItem(
                            modifier = rowModifier,
                            colors = musicoListItemColors(),
                            headlineContent = {
                                Text(
                                    text = strings.missingSong,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        playerController.removeFromQueue(
                                            index = index,
                                            queueId = viewed.id,
                                        )
                                    }
                                ) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = strings.remove)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showQueueSwitcher) {
        QueueSwitcherSheet(
            queueState = queueState,
            libraryStore = libraryStore,
            playerState = playerState,
            onSelectQueue = { queueId ->
                queueStore.selectViewedQueue(queueId)
                showQueueSwitcher = false
            },
            onCreateQueue = { queueStore.addQueue(name = null) },
            onRenameQueue = { renamingQueue = it },
            onDeleteQueue = { deletingQueue = it },
            onDismiss = { showQueueSwitcher = false },
        )
    }

    val renaming = renamingQueue
    if (renaming != null) {
        TextInputDialog(
            title = strings.renameQueueTitle,
            label = strings.nameLabel,
            initialValue = renaming.name,
            confirmLabel = strings.rename,
            onDismiss = { renamingQueue = null },
            onConfirm = { newName -> queueStore.rename(renaming.id, newName) }
        )
    }

    val deleting = deletingQueue
    if (deleting != null) {
        ConfirmDialog(
            title = strings.deleteItemConfirmTitle(strings.displayQueueName(deleting.name)),
            message = strings.deleteQueueConfirmMessage,
            confirmLabel = strings.delete,
            onDismiss = { deletingQueue = null },
            onConfirm = { playerController.deleteQueue(deleting.id) }
        )
    }
}

private fun queueSummaryText(
    queue: PlayQueue,
    isActive: Boolean,
    activePositionSec: Double,
    libraryStore: LibraryStore,
    strings: AppStrings,
): String {
    if (queue.trackIds.isEmpty()) return strings.emptyLabel
    val currentTrack = queue.currentTrackId?.let { libraryStore.track(it) }
    return if (currentTrack != null) {
        val posSec = if (isActive) activePositionSec else queue.positionSec
        strings.queueSummary(queue.trackIds.size, currentTrack.title, formatTime(posSec))
    } else {
        strings.songCount(queue.trackIds.size)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSwitcherSheet(
    queueState: QueueUiState,
    libraryStore: LibraryStore,
    playerState: PlayerUiState,
    onSelectQueue: (String) -> Unit,
    onCreateQueue: () -> Unit,
    onRenameQueue: (PlayQueue) -> Unit,
    onDeleteQueue: (PlayQueue) -> Unit,
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
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.switchQueue,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = strings.queueCountLabel(queueState.queues.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = onCreateQueue) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(strings.newQueue)
                }
            }
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(queueState.queues, key = { it.id }) { queue ->
                    val isViewed = queue.id == queueState.viewedId
                    val isPlayingQueue = queue.id == queueState.activeId
                    var rowMenuExpanded by remember(queue.id) { mutableStateOf(false) }
                    val summary = queueSummaryText(
                        queue = queue,
                        isActive = isPlayingQueue,
                        activePositionSec = playerState.currentTimeSec,
                        libraryStore = libraryStore,
                        strings = strings,
                    )
                    val containerColor = if (isViewed) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                    } else {
                        Color.Transparent
                    }

                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = containerColor),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(containerColor)
                            .clickable { onSelectQueue(queue.id) },
                        leadingContent = {
                            if (isPlayingQueue) {
                                Icon(
                                    imageVector = if (playerState.isPlaying) {
                                        Icons.Rounded.GraphicEq
                                    } else {
                                        Icons.AutoMirrored.Rounded.VolumeUp
                                    },
                                    contentDescription = strings.playing,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else if (isViewed) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        headlineContent = {
                            Text(
                                text = strings.displayQueueName(queue.name),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (isViewed || isPlayingQueue) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isViewed) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        },
                        supportingContent = {
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { rowMenuExpanded = true }) {
                                    Icon(Icons.Rounded.MoreVert, contentDescription = strings.queueActions)
                                }
                                DropdownMenu(
                                    expanded = rowMenuExpanded,
                                    onDismissRequest = { rowMenuExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(strings.renameQueueEllipsis) },
                                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                        onClick = {
                                            rowMenuExpanded = false
                                            onRenameQueue(queue)
                                        },
                                    )
                                    if (queueState.queues.size > 1) {
                                        DropdownMenuItem(
                                            text = { Text(strings.deleteQueue) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Rounded.Delete,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                            },
                                            onClick = {
                                                rowMenuExpanded = false
                                                onDeleteQueue(queue)
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
