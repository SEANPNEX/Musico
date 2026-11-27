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

data class ArtistPlayStat(
    val artist: String,
    val playCount: Int,
    val artworkKey: String? = null,
    val remoteArtworkUrl: String? = null,
)

data class AlbumPlayStat(
    val albumId: String,
    val title: String,
    val artist: String,
    val playCount: Int,
    val artworkKey: String? = null,
    val remoteArtworkUrl: String? = null,
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

    fun remapTracks(idMap: Map<String, String>) {
        if (idMap.isEmpty()) return
        val current = _state.value
        val nextEntries = current.entries.toMutableMap()
        var changed = false
        for ((oldId, newId) in idMap) {
            if (oldId == newId) continue
            if (Track.isEphemeralId(oldId) || Track.isEphemeralId(newId)) continue
            val oldEntry = nextEntries.remove(oldId) ?: continue
            changed = true
            val existingNew = nextEntries[newId]
            nextEntries[newId] = if (existingNew != null) {
                PlayHistoryEntry(
                    count = existingNew.count + oldEntry.count,
                    lastPlayedEpochMs = maxOf(existingNew.lastPlayedEpochMs, oldEntry.lastPlayedEpochMs),
                )
            } else {
                oldEntry
            }
        }
        if (changed) {
            _state.value = current.copy(entries = nextEntries)
            save()
        }
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

    fun topArtists(tracks: List<Track>, limit: Int = 5): List<ArtistPlayStat> {
        if (limit <= 0) return emptyList()
        val entries = _state.value.entries
        val eligible = tracks.filter { !it.isEphemeral && !Track.isEphemeralId(it.id) }
        val byArtist = eligible.groupBy { it.artist }

        return byArtist.mapNotNull { (artist, artistTracks) ->
            var total = 0
            var latestMs = 0L
            for (track in artistTracks) {
                val entry = entries[track.id] ?: continue
                if (entry.count > 0) {
                    total += entry.count
                    if (entry.lastPlayedEpochMs > latestMs) {
                        latestMs = entry.lastPlayedEpochMs
                    }
                }
            }
            if (total <= 0) return@mapNotNull null

            val sortedForArtwork = artistTracks.sortedByDescending { entries[it.id]?.count ?: 0 }
            val stat = ArtistPlayStat(
                artist = artist,
                playCount = total,
                artworkKey = sortedForArtwork.firstNotNullOfOrNull { it.artworkKey },
                remoteArtworkUrl = sortedForArtwork.firstNotNullOfOrNull { it.remoteArtworkUrl },
            )
            stat to latestMs
        }
            .sortedWith(
                compareByDescending<Pair<ArtistPlayStat, Long>> { it.first.playCount }
                    .thenByDescending { it.second }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.artist }
            )
            .take(limit)
            .map { it.first }
    }

    fun topAlbums(tracks: List<Track>, limit: Int = 5): List<AlbumPlayStat> {
        if (limit <= 0) return emptyList()
        val entries = _state.value.entries
        val eligible = tracks.filter { !it.isEphemeral && !Track.isEphemeralId(it.id) }
        val byAlbum = eligible.groupBy { "${it.albumArtist}\u001F${it.album}" }

        return byAlbum.mapNotNull { (albumId, albumTracks) ->
            var total = 0
            var latestMs = 0L
            for (track in albumTracks) {
                val entry = entries[track.id] ?: continue
                if (entry.count > 0) {
                    total += entry.count
                    if (entry.lastPlayedEpochMs > latestMs) {
                        latestMs = entry.lastPlayedEpochMs
                    }
                }
            }
            if (total <= 0) return@mapNotNull null

            val first = albumTracks.first()
            val sortedForArtwork = albumTracks.sortedByDescending { entries[it.id]?.count ?: 0 }
            val stat = AlbumPlayStat(
                albumId = albumId,
                title = first.album,
                artist = first.albumArtist,
                playCount = total,
                artworkKey = sortedForArtwork.firstNotNullOfOrNull { it.artworkKey },
                remoteArtworkUrl = sortedForArtwork.firstNotNullOfOrNull { it.remoteArtworkUrl },
            )
            stat to latestMs
        }
            .sortedWith(
                compareByDescending<Pair<AlbumPlayStat, Long>> { it.first.playCount }
                    .thenByDescending { it.second }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.title }
            )
            .take(limit)
            .map { it.first }
    }

    fun playedLibraryFraction(tracks: List<Track>): Float {
        val eligible = tracks.filter { !it.isEphemeral && !Track.isEphemeralId(it.id) }
        if (eligible.isEmpty()) return 0f
        val playedCount = eligible.count { count(it.id) > 0 }
        return (playedCount.toFloat() / eligible.size.toFloat()).coerceIn(0f, 1f)
    }

    companion object {
        const val STALE_AFTER_DAYS = 30
    }
}
