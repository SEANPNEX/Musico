package com.musico.store

import com.musico.core.Persistence
import com.musico.model.PlayHistoryEntry
import com.musico.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

data class PlayHistoryUiState(
    val entries: Map<String, PlayHistoryEntry> = emptyMap(),
    val totalPlays: Int = 0,
    val totalSeconds: Double = 0.0,
)

class PlayHistoryStore(
    directory: File,
) {
    @Serializable
    private data class HistoryFile(
        val entries: Map<String, PlayHistoryEntry> = emptyMap(),
        val totalPlays: Int = 0,
        val totalSeconds: Double = 0.0,
    )

    private val file = File(directory, "history.json")
    private val _state: MutableStateFlow<PlayHistoryUiState>
    val state: StateFlow<PlayHistoryUiState>

    init {
        val loaded = Persistence.load<HistoryFile>(file)
        _state = MutableStateFlow(
            if (loaded != null) {
                PlayHistoryUiState(
                    entries = loaded.entries.filterKeys { !Track.isEphemeralId(it) },
                    totalPlays = loaded.totalPlays,
                    totalSeconds = loaded.totalSeconds,
                )
            } else {
                PlayHistoryUiState()
            }
        )
        state = _state.asStateFlow()
    }

    fun save() {
        val current = _state.value
        runCatching {
            Persistence.save(
                HistoryFile(
                    entries = current.entries.filterKeys { !Track.isEphemeralId(it) },
                    totalPlays = current.totalPlays,
                    totalSeconds = current.totalSeconds,
                ),
                file,
            )
        }
    }

    fun recordPlay(id: String, nowMs: Long = System.currentTimeMillis()) {
        if (Track.isEphemeralId(id)) return
        val current = _state.value
        val existing = current.entries[id]
        val nextEntries = current.entries.toMutableMap()
        nextEntries[id] = PlayHistoryEntry(
            count = (existing?.count ?: 0) + 1,
            lastPlayedEpochMs = nowMs,
        )
        _state.value = current.copy(
            entries = nextEntries,
            totalPlays = current.totalPlays + 1,
        )
        save()
    }

    fun recordPlay(track: Track, nowMs: Long = System.currentTimeMillis()) {
        if (track.isEphemeral || Track.isEphemeralId(track.id)) return
        recordPlay(track.id, nowMs)
    }

    /** Notes that a song has started playing: makes it recent without adding a play count. */
    fun markStarted(id: String, nowMs: Long = System.currentTimeMillis()) {
        if (Track.isEphemeralId(id)) return
        val current = _state.value
        val existing = current.entries[id]
        val nextEntries = current.entries.toMutableMap()
        nextEntries[id] = PlayHistoryEntry(
            count = existing?.count ?: 0,
            lastPlayedEpochMs = nowMs,
        )
        _state.value = current.copy(entries = nextEntries)
        save()
    }

    fun markStarted(track: Track, nowMs: Long = System.currentTimeMillis()) {
        if (track.isEphemeral || Track.isEphemeralId(track.id)) return
        markStarted(track.id, nowMs)
    }

    /** Adds listening time in memory; callers flush with [save]. */
    fun addListening(seconds: Double) {
        if (seconds <= 0.0 || !seconds.isFinite()) return
        val current = _state.value
        _state.value = current.copy(totalSeconds = current.totalSeconds + seconds)
    }

    fun addListening(seconds: Double, track: Track) {
        if (track.isEphemeral || Track.isEphemeralId(track.id)) return
        addListening(seconds)
    }

    fun removeTracks(ids: Set<String>) {
        val current = _state.value
        if (ids.none { current.entries.containsKey(it) }) return
        val nextEntries = current.entries.toMutableMap()
        for (id in ids) {
            nextEntries.remove(id)
        }
        _state.value = current.copy(entries = nextEntries)
        save()
    }

    fun count(id: String): Int = if (Track.isEphemeralId(id)) 0 else (_state.value.entries[id]?.count ?: 0)

    fun lastPlayed(id: String): Long? = if (Track.isEphemeralId(id)) null else _state.value.entries[id]?.lastPlayedEpochMs

    private fun started(tracks: List<Track>): List<Pair<Track, PlayHistoryEntry>> {
        val entries = _state.value.entries
        return tracks.mapNotNull { track ->
            if (track.isEphemeral || Track.isEphemeralId(track.id)) null
            else entries[track.id]?.let { track to it }
        }
    }

    private fun played(tracks: List<Track>): List<Pair<Track, PlayHistoryEntry>> =
        started(tracks).filter { it.second.count > 0 }

    fun recentlyPlayed(tracks: List<Track>): List<Track> =
        started(tracks)
            .sortedByDescending { it.second.lastPlayedEpochMs }
            .map { it.first }

    fun mostPlayed(tracks: List<Track>): List<Track> =
        played(tracks)
            .sortedWith(
                compareByDescending<Pair<Track, PlayHistoryEntry>> { it.second.count }
                    .thenByDescending { it.second.lastPlayedEpochMs }
            )
            .map { it.first }

    fun leastPlayed(tracks: List<Track>): List<Track> =
        played(tracks)
            .sortedWith(
                compareBy<Pair<Track, PlayHistoryEntry>> { it.second.count }
                    .thenBy { it.second.lastPlayedEpochMs }
            )
            .map { it.first }

    fun neverPlayed(tracks: List<Track>): List<Track> =
        tracks.filter { !it.isEphemeral && !Track.isEphemeralId(it.id) && count(it.id) == 0 }

    fun notPlayedLately(tracks: List<Track>, nowMs: Long = System.currentTimeMillis()): List<Track> {
        val cutoff = nowMs - STALE_AFTER_DAYS * 86_400_000L
        return started(tracks)
            .filter { it.second.lastPlayedEpochMs < cutoff }
            .sortedBy { it.second.lastPlayedEpochMs }
            .map { it.first }
    }

    companion object {
        const val STALE_AFTER_DAYS = 30
    }
}
