package com.musico.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay

data class DragReorderItem<T>(
    val uid: String,
    val originalIndex: Int,
    val value: T,
)

@Stable
class DragReorderState<T>(
    private val listState: LazyListState,
    private val headerCountProvider: () -> Int,
    private val keyOfProvider: () -> ((T) -> String),
    private val onCommitProvider: () -> ((List<DragReorderItem<T>>) -> Unit),
    private val onStartHaptic: (() -> Unit)? = null,
    private val onSwapHaptic: (() -> Unit)? = null,
) {
    var items by mutableStateOf<List<DragReorderItem<T>>>(emptyList())
        private set

    var activeUid by mutableStateOf<String?>(null)
        private set

    var draggingOffsetY by mutableFloatStateOf(0f)
        private set

    var settlingUid by mutableStateOf<String?>(null)
        private set

    internal var settlingOffsetY by mutableFloatStateOf(0f)

    internal var settlingToken by mutableIntStateOf(0)
        private set

    private var pendingCommitKeys by mutableStateOf<List<String>?>(null)

    internal var pendingCommitToken by mutableIntStateOf(0)
        private set

    val isDragging: Boolean
        get() = activeUid != null

    val isSettling: Boolean
        get() = settlingUid != null

    fun syncSource(source: List<T>, keyOf: (T) -> String) {
        if (activeUid != null) return
        val incomingKeys = source.map(keyOf)
        val pending = pendingCommitKeys
        if (pending != null) {
            if (incomingKeys == pending) {
                pendingCommitKeys = null
            } else if (sameMultiset(incomingKeys, pending)) {
                val byKey = HashMap<String, ArrayDeque<T>>(source.size)
                for (i in source.indices) {
                    byKey.getOrPut(incomingKeys[i]) { ArrayDeque() }.addLast(source[i])
                }
                var changed = false
                val refreshed = items.map { item ->
                    val key = keyOf(item.value)
                    val nextValue = byKey[key]?.removeFirstOrNull()
                    if (nextValue != null && nextValue != item.value) {
                        changed = true
                        item.copy(value = nextValue)
                    } else {
                        item
                    }
                }
                if (changed) {
                    items = refreshed
                }
                return
            } else {
                pendingCommitKeys = null
            }
        }

        if (items.size == source.size &&
            items.indices.all { idx ->
                keyOf(items[idx].value) == incomingKeys[idx] &&
                    items[idx].originalIndex == idx &&
                    items[idx].value == source[idx]
            }
        ) {
            return
        }

        if (items.size == source.size &&
            items.indices.all { idx -> keyOf(items[idx].value) == incomingKeys[idx] }
        ) {
            items = items.mapIndexed { idx, existing ->
                existing.copy(originalIndex = idx, value = source[idx])
            }
            return
        }

        val seenCounts = HashMap<String, Int>(source.size)
        items = source.mapIndexed { index, value ->
            val baseKey = incomingKeys[index]
            val occ = seenCounts.getOrPut(baseKey) { 0 }
            seenCounts[baseKey] = occ + 1
            DragReorderItem(
                uid = "${baseKey}#$occ",
                originalIndex = index,
                value = value,
            )
        }
    }

    fun startDrag(uid: String) {
        if (items.none { it.uid == uid }) return
        settlingUid = null
        settlingOffsetY = 0f
        activeUid = uid
        draggingOffsetY = 0f
        onStartHaptic?.invoke()
    }

    fun onDrag(change: PointerInputChange, deltaY: Float) {
        val uid = activeUid ?: return
        change.consume()
        draggingOffsetY += deltaY

        val curIdx = items.indexOfFirst { it.uid == uid }
        if (curIdx < 0) return

        val headerCount = headerCountProvider()
        val visible = listState.layoutInfo.visibleItemsInfo
        val currentInfo = visible.firstOrNull { it.key == uid }
            ?: visible.firstOrNull { it.index == headerCount + curIdx }
            ?: return
        if (currentInfo.index != headerCount + curIdx) return

        if (draggingOffsetY > 0f && curIdx < items.lastIndex) {
            val nextInfo = visible.firstOrNull { it.index == headerCount + curIdx + 1 }
            if (nextInfo != null) {
                val draggedBottom = currentInfo.offset + draggingOffsetY + currentInfo.size
                val nextCenter = nextInfo.offset + nextInfo.size / 2f
                if (draggedBottom > nextCenter) {
                    val mutable = items.toMutableList()
                    val moved = mutable.removeAt(curIdx)
                    mutable.add(curIdx + 1, moved)
                    items = mutable
                    draggingOffsetY -= (nextInfo.offset - currentInfo.offset).toFloat()
                    onSwapHaptic?.invoke()
                }
            }
        } else if (draggingOffsetY < 0f && curIdx > 0) {
            val prevInfo = visible.firstOrNull { it.index == headerCount + curIdx - 1 }
            if (prevInfo != null) {
                val draggedTop = currentInfo.offset + draggingOffsetY
                val prevCenter = prevInfo.offset + prevInfo.size / 2f
                if (draggedTop < prevCenter) {
                    val mutable = items.toMutableList()
                    val moved = mutable.removeAt(curIdx)
                    mutable.add(curIdx - 1, moved)
                    items = mutable
                    draggingOffsetY += (currentInfo.offset - prevInfo.offset).toFloat()
                    onSwapHaptic?.invoke()
                }
            }
        }
    }

    fun endDrag() {
        val uid = activeUid ?: return
        val releaseOffset = draggingOffsetY
        activeUid = null
        draggingOffsetY = 0f
        val snapshot = items
        val moved = snapshot.indices.any { idx -> snapshot[idx].originalIndex != idx }
        val uidPresent = snapshot.any { it.uid == uid }
        if (releaseOffset != 0f && uidPresent) {
            settlingUid = uid
            settlingOffsetY = releaseOffset
            settlingToken += 1
        }
        if (moved && uidPresent) {
            val keyOf = keyOfProvider()
            pendingCommitKeys = snapshot.map { keyOf(it.value) }
            pendingCommitToken += 1
            onCommitProvider().invoke(snapshot)
        }
    }

    internal fun moveItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in items.indices || toIndex !in items.indices || fromIndex == toIndex) return
        val mutable = items.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        items = mutable
    }

    internal fun finishSettling(token: Int) {
        if (settlingToken == token) {
            settlingUid = null
            settlingOffsetY = 0f
        }
    }

    internal fun clearPendingCommitIfMatching(token: Int) {
        if (pendingCommitToken == token) {
            pendingCommitKeys = null
        }
    }

    internal suspend fun autoScrollStep() {
        val uid = activeUid ?: return
        val curIdx = items.indexOfFirst { it.uid == uid }
        if (curIdx < 0) return
        val layoutInfo = listState.layoutInfo
        val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull {
            it.key == uid || it.index == headerCountProvider() + curIdx
        } ?: return

        val topEdge = layoutInfo.viewportStartOffset + 80
        val bottomEdge = layoutInfo.viewportEndOffset - 80
        val itemTop = itemInfo.offset + draggingOffsetY
        val itemBottom = itemTop + itemInfo.size

        val scrollDelta = when {
            itemTop < topEdge -> -14f
            itemBottom > bottomEdge -> 14f
            else -> 0f
        }
        if (scrollDelta != 0f) {
            val consumed = listState.scrollBy(scrollDelta)
            if (consumed != 0f) {
                draggingOffsetY += consumed
            }
        }
    }

    fun offsetFor(uid: String): Float = when (uid) {
        activeUid -> draggingOffsetY
        settlingUid -> settlingOffsetY
        else -> 0f
    }

    private fun sameMultiset(a: List<String>, b: List<String>): Boolean {
        if (a.size != b.size) return false
        val counts = HashMap<String, Int>(a.size)
        for (item in a) {
            counts[item] = (counts[item] ?: 0) + 1
        }
        for (item in b) {
            val remaining = counts[item] ?: return false
            if (remaining <= 1) counts.remove(item) else counts[item] = remaining - 1
        }
        return counts.isEmpty()
    }
}

@Composable
fun <T> rememberDragReorderState(
    items: List<T>,
    listState: LazyListState,
    headerCount: Int = 0,
    scopeKey: Any? = Unit,
    keyOf: (T) -> String,
    onCommit: (List<DragReorderItem<T>>) -> Unit,
): DragReorderState<T> {
    val currentHeaderCount by rememberUpdatedState(headerCount)
    val currentKeyOf by rememberUpdatedState(keyOf)
    val currentOnCommit by rememberUpdatedState(onCommit)
    val haptic = LocalHapticFeedback.current
    val currentHaptic by rememberUpdatedState(haptic)

    val state = remember(listState, scopeKey) {
        DragReorderState<T>(
            listState = listState,
            headerCountProvider = { currentHeaderCount },
            keyOfProvider = { currentKeyOf },
            onCommitProvider = { currentOnCommit },
            onStartHaptic = { currentHaptic.performHapticFeedback(HapticFeedbackType.LongPress) },
            onSwapHaptic = { currentHaptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
        )
    }

    state.syncSource(items, keyOf)

    LaunchedEffect(state, state.activeUid) {
        while (state.activeUid != null) {
            state.autoScrollStep()
            delay(16L)
        }
    }

    LaunchedEffect(state, state.settlingToken) {
        val uid = state.settlingUid ?: return@LaunchedEffect
        val token = state.settlingToken
        val startOffset = state.settlingOffsetY
        if (startOffset != 0f) {
            animate(
                initialValue = startOffset,
                targetValue = 0f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            ) { value, _ ->
                if (state.settlingUid == uid && state.settlingToken == token) {
                    state.settlingOffsetY = value
                }
            }
        }
        state.finishSettling(token)
    }

    LaunchedEffect(state, state.pendingCommitToken) {
        val token = state.pendingCommitToken
        if (token == 0) return@LaunchedEffect
        delay(600L)
        state.clearPendingCommitIfMatching(token)
    }

    return state
}

fun <T> Modifier.dragHandle(
    state: DragReorderState<T>,
    uid: String,
): Modifier = pointerInput(state, uid) {
    detectDragGestures(
        onDragStart = { state.startDrag(uid) },
        onDrag = { change, dragAmount -> state.onDrag(change, dragAmount.y) },
        onDragEnd = { state.endDrag() },
        onDragCancel = { state.endDrag() },
    )
}
