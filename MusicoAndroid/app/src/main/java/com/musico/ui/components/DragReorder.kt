package com.musico.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
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
    private val onCommitProvider: () -> ((List<DragReorderItem<T>>) -> Unit),
) {
    var items by mutableStateOf<List<DragReorderItem<T>>>(emptyList())
        private set

    var activeUid by mutableStateOf<String?>(null)
        private set

    var draggingOffsetY by mutableFloatStateOf(0f)
        private set

    val isDragging: Boolean
        get() = activeUid != null

    fun syncSource(source: List<T>, keyOf: (T) -> String) {
        if (activeUid != null) return
        val seenCounts = HashMap<String, Int>(source.size)
        items = source.mapIndexed { index, value ->
            val baseKey = keyOf(value)
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
        activeUid = uid
        draggingOffsetY = 0f
    }

    fun onDrag(change: PointerInputChange, deltaY: Float) {
        val uid = activeUid ?: return
        change.consume()
        draggingOffsetY += deltaY

        var curIdx = items.indexOfFirst { it.uid == uid }
        if (curIdx < 0) return

        val headerCount = headerCountProvider()
        val visible = listState.layoutInfo.visibleItemsInfo

        while (true) {
            val currentInfo = visible.firstOrNull { it.index == headerCount + curIdx } ?: break
            var swapped = false

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
                        curIdx += 1
                        swapped = true
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
                        curIdx -= 1
                        swapped = true
                    }
                }
            }

            if (!swapped) break
        }
    }

    fun endDrag() {
        val uid = activeUid ?: return
        activeUid = null
        draggingOffsetY = 0f
        val snapshot = items
        val moved = snapshot.indices.any { idx -> snapshot[idx].originalIndex != idx }
        if (moved && snapshot.any { it.uid == uid }) {
            onCommitProvider().invoke(snapshot)
        }
    }

    internal suspend fun autoScrollStep() {
        val uid = activeUid ?: return
        val curIdx = items.indexOfFirst { it.uid == uid }
        if (curIdx < 0) return
        val layoutInfo = listState.layoutInfo
        val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull {
            it.index == headerCountProvider() + curIdx
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

    fun offsetFor(uid: String): Float = if (activeUid == uid) draggingOffsetY else 0f
}

@Composable
fun <T> rememberDragReorderState(
    items: List<T>,
    listState: LazyListState,
    headerCount: Int = 0,
    keyOf: (T) -> String,
    onCommit: (List<DragReorderItem<T>>) -> Unit,
): DragReorderState<T> {
    val currentHeaderCount by rememberUpdatedState(headerCount)
    val currentOnCommit by rememberUpdatedState(onCommit)

    val state = remember(listState) {
        DragReorderState<T>(
            listState = listState,
            headerCountProvider = { currentHeaderCount },
            onCommitProvider = { currentOnCommit },
        )
    }

    state.syncSource(items, keyOf)

    LaunchedEffect(state.activeUid) {
        while (state.activeUid != null) {
            state.autoScrollStep()
            delay(16L)
        }
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
