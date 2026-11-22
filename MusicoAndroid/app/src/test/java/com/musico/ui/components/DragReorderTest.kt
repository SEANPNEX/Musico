package com.musico.ui.components

import androidx.compose.foundation.lazy.LazyListState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DragReorderTest {

    @Test
    fun endDragHoldsOptimisticOrderUntilUpstreamCatchesUp() {
        val commits = mutableListOf<List<String>>()
        val state = DragReorderState<String>(
            listState = LazyListState(),
            headerCountProvider = { 0 },
            keyOfProvider = { { it } },
            onCommitProvider = { { reordered -> commits.add(reordered.map { it.value }) } },
        )

        val initial = listOf("a", "b", "c")
        state.syncSource(initial) { it }
        assertEquals(listOf("a", "b", "c"), state.items.map { it.value })

        state.startDrag("a#0")
        state.moveItem(0, 2)
        assertEquals(listOf("b", "c", "a"), state.items.map { it.value })

        state.endDrag()
        assertFalse(state.isDragging)
        assertEquals(listOf(listOf("b", "c", "a")), commits)

        // Frame gap: upstream StateFlow still has the stale pre-commit order.
        // syncSource must NOT revert to ["a", "b", "c"] (which caused the flash).
        state.syncSource(initial) { it }
        assertEquals(listOf("b", "c", "a"), state.items.map { it.value })

        // Once upstream catches up with the committed order, originalIndex updates cleanly.
        val committed = listOf("b", "c", "a")
        state.syncSource(committed) { it }
        assertEquals(listOf("b", "c", "a"), state.items.map { it.value })
        assertEquals(listOf(0, 1, 2), state.items.map { it.originalIndex })
    }

    @Test
    fun duplicateKeysKeepStableUidsAcrossCommit() {
        val state = DragReorderState<String>(
            listState = LazyListState(),
            headerCountProvider = { 0 },
            keyOfProvider = { { it } },
            onCommitProvider = { {} },
        )

        val initial = listOf("a", "b", "a")
        state.syncSource(initial) { it }
        assertEquals(listOf("a#0", "b#0", "a#1"), state.items.map { it.uid })

        state.startDrag("a#0")
        state.moveItem(0, 2)
        state.endDrag()

        val uidsAfterMove = state.items.map { it.uid }
        assertEquals(listOf("b#0", "a#1", "a#0"), uidsAfterMove)

        // Upstream emits the new order ["b", "a", "a"]; existing uids remain stable.
        state.syncSource(listOf("b", "a", "a")) { it }
        assertEquals(uidsAfterMove, state.items.map { it.uid })
        assertEquals(listOf(0, 1, 2), state.items.map { it.originalIndex })
    }

    @Test
    fun externalListMutationClearsPendingOptimisticHold() {
        val state = DragReorderState<String>(
            listState = LazyListState(),
            headerCountProvider = { 0 },
            keyOfProvider = { { it } },
            onCommitProvider = { {} },
        )

        state.syncSource(listOf("a", "b", "c")) { it }
        state.startDrag("a#0")
        state.moveItem(0, 1)
        state.endDrag()

        // External addition invalidates the pending commit snapshot.
        state.syncSource(listOf("a", "b", "c", "d")) { it }
        assertEquals(listOf("a", "b", "c", "d"), state.items.map { it.value })
    }
}
