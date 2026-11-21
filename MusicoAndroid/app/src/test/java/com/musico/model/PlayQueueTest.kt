package com.musico.model

import com.musico.core.Persistence
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class PlayQueueTest {
    private fun queue(
        ids: List<String>,
        at: Int = 0,
        repeatMode: RepeatMode = RepeatMode.OFF,
    ): PlayQueue = PlayQueue(name = "Q", repeatMode = repeatMode).replace(ids, startAt = at)

    @Test
    fun nextAdvancesAndStopsAtEnd() {
        var q = queue(listOf("a", "b"))
        assertEquals("a", q.currentTrackId)

        val (q2, t2) = q.next(auto = true)
        q = q2
        assertEquals("b", t2)

        val (q3, t3) = q.next(auto = true)
        assertNull(t3)
        assertEquals("b", q3.currentTrackId)
    }

    @Test
    fun repeatAllWraps() {
        var q = queue(listOf("a", "b"), at = 1, repeatMode = RepeatMode.ALL)
        val (q2, t2) = q.next(auto = true)
        q = q2
        assertEquals("a", t2)

        val (_, prev) = q.previous()
        assertEquals("b", prev)
    }

    @Test
    fun repeatOneAutoRepeatsButManualNextAdvances() {
        val q = queue(listOf("a", "b"), repeatMode = RepeatMode.ONE)
        assertEquals("a", q.next(auto = true).second)
        assertEquals("b", q.next(auto = false).second)
    }

    @Test
    fun previousAtStartStays() {
        val q = queue(listOf("a", "b"))
        val (updated, track) = q.previous()
        assertEquals("a", track)
        assertEquals(0, updated.currentIndex)
    }

    @Test
    fun replaceClampsStartIndexAndResetsPosition() {
        val q = queue(listOf("a")).copy(positionSec = 42.0).replace(listOf("x", "y"), startAt = 9)
        assertEquals("y", q.currentTrackId)
        assertEquals(0.0, q.positionSec, 0.0001)
    }

    @Test
    fun shuffleOrderIsPermutationStartingAtCurrent() {
        val q = queue(listOf("a", "b", "c", "d", "e"), at = 2)
            .withShuffle(true, Random(7))
        assertEquals(2, q.shuffleOrder.first())
        assertEquals(listOf(0, 1, 2, 3, 4), q.shuffleOrder.sorted())
        assertEquals("c", q.currentTrackId)
    }

    @Test
    fun shuffleVisitsEveryTrackOnce() {
        var q = queue(listOf("a", "b", "c", "d", "e"), at = 1)
            .withShuffle(true, Random(3))
        val seen = mutableListOf(q.currentTrackId!!)
        while (true) {
            val (nextQ, nextId) = q.next(auto = true)
            q = nextQ
            if (nextId == null) break
            seen.add(nextId)
        }
        assertEquals(5, seen.size)
        assertEquals(setOf("a", "b", "c", "d", "e"), seen.toSet())
    }

    @Test
    fun turningShuffleOffKeepsCurrentTrackAndRestoresListOrder() {
        val q = queue(listOf("a", "b", "c"), at = 1)
            .withShuffle(true, Random(1))
            .withShuffle(false, Random(1))
        assertEquals("b", q.currentTrackId)
        assertEquals("c", q.next(auto = true).second)
    }

    @Test
    fun insertNextPlaysNext() {
        var q = queue(listOf("a", "b")).insertNext(listOf("x", "y"))
        assertEquals(listOf("a", "x", "y", "b"), q.trackIds)
        val (q2, t2) = q.next(auto = true)
        q = q2
        assertEquals("x", t2)
        assertEquals("y", q.next(auto = true).second)
    }

    @Test
    fun insertNextPlaysNextWhenShuffled() {
        val q = queue(listOf("a", "b", "c", "d"), at = 3)
            .withShuffle(true, Random(5))
            .insertNext(listOf("x"))
        assertEquals("d", q.currentTrackId)
        assertEquals(listOf(0, 1, 2, 3, 4), q.shuffleOrder.sorted())
        assertEquals("x", q.next(auto = true).second)
    }

    @Test
    fun insertIntoEmptyQueueMakesFirstCurrent() {
        val q = PlayQueue(name = "Q").insertNext(listOf("x", "y"))
        assertEquals("x", q.currentTrackId)

        val r = PlayQueue(name = "R").append(listOf("z"))
        assertEquals("z", r.currentTrackId)
    }

    @Test
    fun appendWhenShuffledPlaysLast() {
        var q = queue(listOf("a", "b", "c"))
            .withShuffle(true, Random(9))
            .append(listOf("z"))
        var last: String? = null
        while (true) {
            val (nextQ, nextId) = q.next(auto = true)
            q = nextQ
            if (nextId == null) break
            last = nextId
        }
        assertEquals("z", last)
    }

    @Test
    fun removeBeforeCurrentKeepsCurrentTrack() {
        val q = queue(listOf("a", "b", "c"), at = 2)
            .copy(positionSec = 30.0)
            .removeAt(0)
        assertEquals("c", q.currentTrackId)
        assertEquals(30.0, q.positionSec, 0.0001)
    }

    @Test
    fun removeCurrentSelectsSuccessor() {
        val q = queue(listOf("a", "b", "c"), at = 1)
            .copy(positionSec = 30.0)
            .removeAt(1)
        assertEquals("c", q.currentTrackId)
        assertEquals(0.0, q.positionSec, 0.0001)
    }

    @Test
    fun removeCurrentAtEndSelectsNewLast() {
        val q = queue(listOf("a", "b", "c"), at = 2).removeAt(2)
        assertEquals("b", q.currentTrackId)
    }

    @Test
    fun removeLastRemainingClearsCurrent() {
        val q = queue(listOf("a")).removeAt(0)
        assertNull(q.currentIndex)
        assertNull(q.currentTrackId)
        assertNull(q.next(auto = true).second)
    }

    @Test
    fun removeWhenShuffledKeepsOrderValid() {
        val q = queue(listOf("a", "b", "c", "d"), at = 1)
            .withShuffle(true, Random(11))
            .removeAt(0)
        assertEquals("b", q.currentTrackId)
        assertEquals(listOf(0, 1, 2), q.shuffleOrder.sorted())
    }

    @Test
    fun moveKeepsCurrentTrack() {
        var q = queue(listOf("a", "b", "c", "d"), at = 1)
        q = q.move(fromIndex = 1, toIndex = 3)
        assertEquals(listOf("a", "c", "d", "b"), q.trackIds)
        assertEquals("b", q.currentTrackId)

        q = q.move(fromIndex = 0, toIndex = 3)
        assertEquals("b", q.currentTrackId)
        assertEquals(2, q.currentIndex)
    }

    @Test
    fun moveWithDuplicateIdsKeepsCurrentPosition() {
        val q = queue(listOf("a", "a", "b"), at = 1).move(fromIndex = 2, toIndex = 0)
        assertEquals(listOf("b", "a", "a"), q.trackIds)
        assertEquals(2, q.currentIndex)
    }

    @Test
    fun removeTracksNotInValidSet() {
        val q = queue(listOf("a", "b", "c", "b"), at = 2).removeTracksNotIn(setOf("a", "c"))
        assertEquals(listOf("a", "c"), q.trackIds)
        assertEquals("c", q.currentTrackId)
    }

    @Test
    fun upcomingIndices() {
        var q = queue(listOf("a", "b", "c"), at = 1)
        assertEquals(listOf(2), q.upcomingIndices)
        q = q.jump(0)
        assertEquals(listOf(1, 2), q.upcomingIndices)
    }

    @Test
    fun emptyQueueOperationsAreSafe() {
        val q = PlayQueue(name = "Q")
        assertNull(q.next(auto = false).second)
        assertNull(q.previous().second)
        val updated = q.withShuffle(true, Random(1))
            .replace(emptyList(), 0)
            .removeTracksNotIn(emptySet())
            .jump(5)
        assertNull(updated.currentTrackId)
        assertEquals(emptyList<Int>(), updated.upcomingIndices)
    }

    @Test
    fun serializationRoundTrip() {
        val q = queue(listOf("a", "b", "c"), at = 1, repeatMode = RepeatMode.ALL)
            .withShuffle(true, Random(2))
            .copy(positionSec = 12.5)
        val encoded = Persistence.json.encodeToString(q)
        val decoded = Persistence.json.decodeFromString<PlayQueue>(encoded)
        assertEquals(q, decoded)
    }
}
