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
) {
    val active: PlayQueue
        get() = queues.firstOrNull { it.id == activeId } ?: queues.first()
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

    init {
        val loaded = Persistence.load<QueueFile>(file)
        val initialState = if (loaded != null && loaded.queues.isNotEmpty()) {
            val activeId = if (loaded.queues.any { it.id == loaded.activeId }) {
                loaded.activeId
            } else {
                loaded.queues[0].id
            }
            QueueUiState(queues = loaded.queues, activeId = activeId)
        } else {
            val first = PlayQueue(name = "Queue 1")
            QueueUiState(queues = listOf(first), activeId = first.id)
        }
        _state = MutableStateFlow(initialState)
        state = _state.asStateFlow()
    }

    val active: PlayQueue
        get() = _state.value.active

    fun save() {
        val current = _state.value
        runCatching {
            Persistence.save(QueueFile(queues = current.queues, activeId = current.activeId), file)
        }
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

    fun addQueue(name: String? = null): String {
        val current = _state.value
        val title = resolveQueueTitle(name, current.queues)
        val queue = PlayQueue(name = title)
        _state.value = current.copy(queues = current.queues + queue)
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
            val nextQueues = current.queues.toMutableList().apply {
                this[existingIndex] = created
            }
            val nextActiveId = if (wasActive) created.id else current.activeId
            _state.value = QueueUiState(queues = nextQueues, activeId = nextActiveId)
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
        val nextActiveId = if (current.activeId == id) {
            nextQueues[min(index, nextQueues.lastIndex)].id
        } else {
            current.activeId
        }
        _state.value = QueueUiState(queues = nextQueues, activeId = nextActiveId)
        save()
    }

    fun setActive(id: String) {
        val current = _state.value
        if (current.queues.none { it.id == id }) return
        _state.value = current.copy(activeId = id)
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
}
