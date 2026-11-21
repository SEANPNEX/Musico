package com.musico.model

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.random.Random

/**
 * Immutable play queue value object.
 *
 * Invariant: when [shuffle] is true, [shuffleOrder] is a permutation of `trackIds.indices`;
 * otherwise [shuffleOrder] is empty.
 */
@Serializable
data class PlayQueue(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val trackIds: List<String> = emptyList(),
    val currentIndex: Int? = null,
    val positionSec: Double = 0.0,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleOrder: List<Int> = emptyList(),
) {
    val currentTrackId: String?
        get() {
            val idx = currentIndex ?: return null
            return trackIds.getOrNull(idx)
        }

    private val order: List<Int>
        get() = if (shuffle) shuffleOrder else trackIds.indices.toList()

    private val orderPosition: Int?
        get() {
            val idx = currentIndex ?: return null
            return if (shuffle) {
                shuffleOrder.indexOf(idx).takeIf { it >= 0 }
            } else {
                idx.takeIf { it in trackIds.indices }
            }
        }

    val upcomingIndices: List<Int>
        get() {
            val pos = orderPosition ?: return emptyList()
            val currentOrder = order
            return if (pos + 1 < currentOrder.size) {
                currentOrder.subList(pos + 1, currentOrder.size)
            } else {
                emptyList()
            }
        }

    fun replace(
        ids: List<String>,
        startAt: Int,
        random: Random = Random.Default,
    ): PlayQueue {
        val nextIndex = if (ids.isEmpty()) null else startAt.coerceIn(0, ids.lastIndex)
        return copy(
            trackIds = ids.toList(),
            currentIndex = nextIndex,
            positionSec = 0.0,
        ).reshuffled(random)
    }

    /**
     * Advances and returns the updated queue and new current track ID.
     * [auto] (track finished by itself) honours [RepeatMode.ONE].
     * Returns null track ID, leaving [currentIndex] unchanged, at the end of the queue unless [repeatMode] is [RepeatMode.ALL].
     */
    fun next(auto: Boolean): Pair<PlayQueue, String?> {
        val pos = orderPosition ?: return this to null
        if (auto && repeatMode == RepeatMode.ONE) {
            val updated = copy(positionSec = 0.0)
            return updated to updated.currentTrackId
        }
        val currentOrder = order
        val nextIndex = when {
            pos + 1 < currentOrder.size -> currentOrder[pos + 1]
            repeatMode == RepeatMode.ALL && currentOrder.isNotEmpty() -> currentOrder[0]
            else -> return this to null
        }
        val updated = copy(currentIndex = nextIndex, positionSec = 0.0)
        return updated to updated.currentTrackId
    }

    /**
     * Steps back; wraps to the end only with [RepeatMode.ALL], otherwise stays on the first track.
     */
    fun previous(): Pair<PlayQueue, String?> {
        val pos = orderPosition ?: return this to null
        val currentOrder = order
        val prevIndex = when {
            pos > 0 -> currentOrder[pos - 1]
            repeatMode == RepeatMode.ALL && currentOrder.isNotEmpty() -> currentOrder[currentOrder.lastIndex]
            else -> currentIndex
        }
        val updated = copy(currentIndex = prevIndex, positionSec = 0.0)
        return updated to updated.currentTrackId
    }

    fun jump(index: Int): PlayQueue {
        if (index !in trackIds.indices) return this
        return copy(currentIndex = index, positionSec = 0.0)
    }

    fun insertNext(ids: List<String>): PlayQueue {
        if (ids.isEmpty()) return this
        val insertAt = currentIndex?.let { it + 1 } ?: 0
        val orderPos = orderPosition
        val nextTracks = trackIds.toMutableList().apply { addAll(insertAt, ids) }
        val newIndices = (insertAt until (insertAt + ids.size)).toList()
        val nextShuffleOrder = if (shuffle) {
            val shifted = shuffleOrder.map { if (it >= insertAt) it + ids.size else it }.toMutableList()
            val shuffleInsertAt = orderPos?.let { it + 1 } ?: 0
            shifted.addAll(shuffleInsertAt, newIndices)
            shifted
        } else {
            emptyList()
        }
        val nextCurrent = currentIndex ?: 0
        val nextPos = if (currentIndex == null) 0.0 else positionSec
        return copy(
            trackIds = nextTracks,
            currentIndex = nextCurrent,
            positionSec = nextPos,
            shuffleOrder = nextShuffleOrder,
        )
    }

    fun append(ids: List<String>): PlayQueue {
        if (ids.isEmpty()) return this
        val start = trackIds.size
        val nextTracks = trackIds + ids
        val nextShuffleOrder = if (shuffle) {
            shuffleOrder + (start until nextTracks.size)
        } else {
            emptyList()
        }
        val nextCurrent = currentIndex ?: start
        val nextPos = if (currentIndex == null) 0.0 else positionSec
        return copy(
            trackIds = nextTracks,
            currentIndex = nextCurrent,
            positionSec = nextPos,
            shuffleOrder = nextShuffleOrder,
        )
    }

    fun removeAt(index: Int): PlayQueue {
        if (index !in trackIds.indices) return this
        val removingCurrent = index == currentIndex
        var successor: Int? = null
        if (removingCurrent) {
            val pos = orderPosition
            if (pos != null) {
                val currentOrder = order
                successor = when {
                    pos + 1 < currentOrder.size -> currentOrder[pos + 1]
                    pos > 0 -> currentOrder[pos - 1]
                    else -> null
                }
            }
        }
        val nextTracks = trackIds.toMutableList().apply { removeAt(index) }
        val nextShuffleOrder = if (shuffle) {
            shuffleOrder.filter { it != index }.map { if (it > index) it - 1 else it }
        } else {
            emptyList()
        }
        val nextCurrent = when {
            removingCurrent -> successor?.let { if (it > index) it - 1 else it }
            currentIndex != null && currentIndex > index -> currentIndex - 1
            else -> currentIndex
        }
        val nextPos = if (removingCurrent) 0.0 else positionSec
        return copy(
            trackIds = nextTracks,
            currentIndex = nextCurrent,
            positionSec = nextPos,
            shuffleOrder = nextShuffleOrder,
        )
    }

    /**
     * Moves the track at [fromIndex] to [toIndex] (both in `0 until trackIds.size`).
     * Keeps [currentIndex] pointing at the same track position and updates [shuffleOrder].
     */
    fun move(fromIndex: Int, toIndex: Int): PlayQueue {
        if (fromIndex !in trackIds.indices || toIndex !in trackIds.indices || fromIndex == toIndex) {
            return this
        }
        val oldIndices = trackIds.indices.toMutableList()
        val movedOldIndex = oldIndices.removeAt(fromIndex)
        oldIndices.add(toIndex, movedOldIndex)

        val nextTracks = oldIndices.map { trackIds[it] }
        val newPosition = IntArray(oldIndices.size)
        oldIndices.forEachIndexed { newPos, oldPos -> newPosition[oldPos] = newPos }

        return copy(
            trackIds = nextTracks,
            currentIndex = currentIndex?.let { newPosition[it] },
            shuffleOrder = if (shuffle) shuffleOrder.map { newPosition[it] } else emptyList(),
        )
    }

    fun withShuffle(on: Boolean, random: Random = Random.Default): PlayQueue {
        return copy(shuffle = on).reshuffled(random)
    }

    /** Drops every track whose id is not in [validIds]. */
    fun removeTracksNotIn(validIds: Set<String>): PlayQueue {
        var result = this
        for (index in trackIds.indices.reversed()) {
            if (trackIds[index] !in validIds) {
                result = result.removeAt(index)
            }
        }
        return result
    }

    private fun reshuffled(random: Random): PlayQueue {
        if (!shuffle) return copy(shuffleOrder = emptyList())
        val current = currentIndex
        val rest = trackIds.indices.filter { it != current }.shuffled(random)
        val newOrder = if (current != null && current in trackIds.indices) {
            listOf(current) + rest
        } else {
            rest
        }
        return copy(shuffleOrder = newOrder)
    }
}
