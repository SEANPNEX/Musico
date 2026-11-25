package com.musico.store

import com.musico.core.Persistence
import com.musico.model.PlayQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.math.min

data class QueueUiState(
    val queues: List<PlayQueue>,
    val activeId: String,
    val viewedId: String = activeId,
) {
    val active: PlayQueue
        get() = queues.firstOrNull { it.id == activeId } ?: queues.first()

    val viewed: PlayQueue
        get() = queues.firstOrNull { it.id == viewedId } ?: active
}

class QueueStore(
    directory: File,
) {
    @Serializable
    private data class QueueFile(
        val queues: List<PlayQueue>,
        val activeId: String,
    )

    private val file = File(directory, "queues.json")
    private val _state: MutableStateFlow<QueueUiState>
    val state: StateFlow<QueueUiState>

    @Volatile
    var onReferencedTrackIdsChanged: ((Set<String>) -> Unit)? = null

    init {
        val loaded = Persistence.load<QueueFile>(file)
        val initialState = if (loaded != null && loaded.queues.isNotEmpty()) {
            val activeId = if (loaded.queues.any { it.id == loaded.activeId }) {
                loaded.activeId
            } else {
                loaded.queues[0].id
            }
            QueueUiState(queues = loaded.queues, activeId = activeId, viewedId = activeId)
        } else {
            val first = PlayQueue(name = "Queue 1")
            QueueUiState(queues = listOf(first), activeId = first.id, viewedId = first.id)
        }
        _state = MutableStateFlow(initialState)
        state = _state.asStateFlow()
    }

    val active: PlayQueue
        get() = _state.value.active

    fun allReferencedTrackIds(): Set<String> =
        _state.value.queues.flatMapTo(HashSet()) { it.trackIds }

    fun save() {
        val current = _state.value
        runCatching {
            Persistence.save(QueueFile(queues = current.queues, activeId = current.activeId), file)
        }
        onReferencedTrackIdsChanged?.invoke(current.queues.flatMapTo(HashSet()) { it.trackIds })
    }

    fun update(id: String, block: (PlayQueue) -> PlayQueue) {
        val current = _state.value
        val index = current.queues.indexOfFirst { it.id == id }
        if (index < 0) return
        val updatedList = current.queues.toMutableList()
        updatedList[index] = block(updatedList[index])
        _state.value = current.copy(queues = updatedList)
        save()
    }

    fun updateActive(block: (PlayQueue) -> PlayQueue) {
        update(_state.value.activeId, block)
    }

    /**
     * Records playback position in memory without writing to disk; callers flush with [save].
     */
    fun setActivePosition(timeSec: Double) {
        val current = _state.value
        val index = current.queues.indexOfFirst { it.id == current.activeId }
        if (index < 0) return
        val updatedList = current.queues.toMutableList()
        updatedList[index] = updatedList[index].copy(positionSec = timeSec)
        _state.value = current.copy(queues = updatedList)
    }

    fun addQueue(name: String? = null, selectAsViewed: Boolean = true): String {
        val current = _state.value
        val title = resolveQueueTitle(name, current.queues)
        val queue = PlayQueue(name = title)
        _state.value = current.copy(
            queues = current.queues + queue,
            viewedId = if (selectAsViewed) queue.id else current.viewedId,
        )
        save()
        return queue.id
    }

    /**
     * Creates a new queue named [name] containing [trackIds] starting at [startAt],
     * replacing any existing queue with the exact same name.
     * Returns `(queueId, wasActiveReplaced)`.
     */
    fun createOrReplaceQueue(
        name: String,
        trackIds: List<String> = emptyList(),
        startAt: Int = 0,
    ): Pair<String, Boolean> {
        val current = _state.value
        val title = resolveQueueTitle(name, current.queues)
        val existingIndex = current.queues.indexOfFirst { it.name == title }
        val created = PlayQueue(name = title).replace(trackIds, startAt = startAt)

        return if (existingIndex >= 0) {
            val existing = current.queues[existingIndex]
            val wasActive = existing.id == current.activeId
            val wasViewed = existing.id == current.viewedId
            val nextQueues = current.queues.toMutableList().apply {
                this[existingIndex] = created
            }
            val nextActiveId = if (wasActive) created.id else current.activeId
            val nextViewedId = if (wasViewed) created.id else current.viewedId
            _state.value = QueueUiState(queues = nextQueues, activeId = nextActiveId, viewedId = nextViewedId)
            save()
            created.id to wasActive
        } else {
            _state.value = current.copy(queues = current.queues + created)
            save()
            created.id to false
        }
    }

    private fun resolveQueueTitle(name: String?, queues: List<PlayQueue>): String {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isNotEmpty()) return trimmed
        val used = queues.map { it.name }.toSet()
        return generateSequence(1) { it + 1 }
            .map { "Queue $it" }
            .first { it !in used }
    }

    fun rename(id: String, name: String) {
        val title = name.trim()
        if (title.isEmpty()) return
        update(id) { it.copy(name = title) }
    }

    /** Deletes a queue. The last remaining queue cannot be deleted. */
    fun delete(id: String) {
        val current = _state.value
        if (current.queues.size <= 1) return
        val index = current.queues.indexOfFirst { it.id == id }
        if (index < 0) return
        val nextQueues = current.queues.toMutableList().apply { removeAt(index) }
        val fallbackId = nextQueues[min(index, nextQueues.lastIndex)].id
        val nextActiveId = if (current.activeId == id) fallbackId else current.activeId
        val nextViewedId = if (current.viewedId == id) fallbackId else current.viewedId
        _state.value = QueueUiState(queues = nextQueues, activeId = nextActiveId, viewedId = nextViewedId)
        save()
    }

    fun selectViewedQueue(id: String) {
        val current = _state.value
        if (current.viewedId == id) return
        if (current.queues.none { it.id == id }) return
        _state.value = current.copy(viewedId = id)
    }

    fun setActive(id: String, syncViewed: Boolean = true) {
        val current = _state.value
        if (current.queues.none { it.id == id }) return
        _state.value = current.copy(
            activeId = id,
            viewedId = if (syncViewed) id else current.viewedId,
        )
        save()
    }

    fun removeTracks(ids: Set<String>) {
        if (ids.isEmpty()) return
        val current = _state.value
        val nextQueues = current.queues.map { queue ->
            val valid = queue.trackIds.toSet() - ids
            queue.removeTracksNotIn(valid)
        }
        _state.value = current.copy(queues = nextQueues)
        save()
    }

    fun remapTracks(idMap: Map<String, String>) {
        if (idMap.isEmpty()) return
        val current = _state.value
        var changed = false
        val nextQueues = current.queues.map { queue ->
            var qChanged = false
            val mappedIds = queue.trackIds.map { tid ->
                val next = idMap[tid]
                if (next != null && next != tid) {
                    qChanged = true
                    next
                } else {
                    tid
                }
            }
            if (qChanged) {
                changed = true
                queue.copy(trackIds = mappedIds)
            } else {
                queue
            }
        }
        if (changed) {
            _state.value = current.copy(queues = nextQueues)
            save()
        }
    }
}
