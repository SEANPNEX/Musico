package com.musico.audio

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.musico.core.Persistence
import com.musico.model.EqSettings
import com.musico.model.RepeatMode
import com.musico.model.SleepMode
import com.musico.model.Track
import com.musico.store.LibraryStore
import com.musico.store.PlayHistoryStore
import com.musico.store.QueueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max

data class PlayerUiState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val durationSec: Double = 0.0,
    val currentTimeSec: Double = 0.0,
    val errorMessage: String? = null,
    val sleepMode: SleepMode = SleepMode.Off,
    val eq: EqSettings = EqSettings(),
)

/**
 * Drives playback from [QueueStore], tracks listening history, enforces sleep timer,
 * persists EQ settings, and connects with [MusicoPlaybackService].
 */
class PlayerController(
    private val context: Context?,
    private val library: LibraryStore,
    private val queues: QueueStore,
    private val history: PlayHistoryStore,
    private val engine: AudioEngine,
    private val settingsFile: File,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state: MutableStateFlow<PlayerUiState>
    val state: StateFlow<PlayerUiState>

    private var listenedSec = 0.0
    private var playCounted = false
    private var markedRecent = false
    private var isLoaded = false

    private var tickerJob: Job? = null
    private var tickCount = 0
    private var sleepJob: Job? = null
    private var mediaControllerStarted = false

    init {
        val loadedEq = Persistence.load<EqSettings>(settingsFile) ?: EqSettings()
        engine.applyEq(loadedEq)
        _state = MutableStateFlow(PlayerUiState(eq = loadedEq))
        state = _state.asStateFlow()

        engine.onTrackEnded = { trackDidEnd() }
        engine.onDurationChanged = { dur ->
            if (dur > 0.0) {
                _state.value = _state.value.copy(durationSec = dur)
            }
        }
        engine.onIsPlayingChanged = { playing ->
            if (_state.value.isPlaying != playing) {
                _state.value = _state.value.copy(isPlaying = playing)
            }
        }
        engine.onPlaybackError = { message ->
            _state.value = _state.value.copy(
                isPlaying = false,
                errorMessage = message,
            )
            stopTicker()
        }
        library.onTracksRemoved = { ids -> tracksRemoved(ids) }
    }

    fun shutdown() {
        stopTicker()
        sleepJob?.cancel()
        sleepJob = null
        engine.onTrackEnded = null
        engine.onDurationChanged = null
        engine.onIsPlayingChanged = null
        engine.onPlaybackError = null
        engine.unload()
        _state.value = _state.value.copy(isPlaying = false)
        scope.cancel()
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                tick()
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun ensureMediaServiceConnected() {
        val ctx = context ?: return
        if (mediaControllerStarted) return
        mediaControllerStarted = true
        runCatching {
            val token = SessionToken(
                ctx,
                ComponentName(ctx, MusicoPlaybackService::class.java),
            )
            MediaController.Builder(ctx, token).buildAsync()
        }
    }

    private fun buildMediaMetadata(track: Track): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setAlbumArtist(track.albumArtist)
        val artFile = library.artwork.file(track.artworkKey)
        if (artFile != null) {
            builder.setArtworkUri(Uri.fromFile(artFile))
        }
        return builder.build()
    }

    private fun loadCurrent(autoplay: Boolean, positionSec: Double): Boolean {
        isLoaded = false
        val id = queues.active.currentTrackId ?: return false
        val track = library.track(id) ?: return false

        _state.value = _state.value.copy(
            currentTrack = track,
            durationSec = track.durationSec,
            currentTimeSec = 0.0,
        )

        val uri = library.uriFor(track) ?: return false
        try {
            engine.load(uri, buildMediaMetadata(track))
        } catch (_: Exception) {
            return false
        }

        isLoaded = true
        listenedSec = 0.0
        playCounted = false
        markedRecent = false

        val resolvedDur = if (engine.durationSec > 0.0) engine.durationSec else track.durationSec
        if (positionSec > 0.0 && (resolvedDur <= 0.0 || positionSec < resolvedDur - 1.0)) {
            engine.seekTo(positionSec)
        }
        val currentPos = if (positionSec > 0.0) positionSec else engine.currentTimeSec

        if (autoplay) {
            ensureMediaServiceConnected()
            engine.play()
            startTicker()
        } else {
            stopTicker()
        }

        _state.value = _state.value.copy(
            currentTrack = track,
            durationSec = resolvedDur,
            currentTimeSec = currentPos,
            isPlaying = autoplay,
        )
        return true
    }

    private fun startCurrent(autoplay: Boolean, positionSec: Double = 0.0) {
        var failures = 0
        var pos = positionSec
        while (true) {
            if (queues.active.currentTrackId == null) {
                clearPlayback()
                return
            }
            if (loadCurrent(autoplay = autoplay, positionSec = pos)) {
                return
            }
            pos = 0.0
            failures += 1
            var advanced: String? = null
            if (failures < MAX_CONSECUTIVE_FAILURES) {
                queues.updateActive { queue ->
                    val (updated, nextId) = queue.next(auto = false)
                    advanced = nextId
                    updated
                }
            }
            if (advanced == null) {
                engine.unload()
                stopTicker()
                val title = _state.value.currentTrack?.title ?: "This song"
                val message = if (failures > 1) {
                    "Playback stopped: $failures songs in a row could not be played."
                } else {
                    "“$title” could not be played."
                }
                _state.value = _state.value.copy(
                    isPlaying = false,
                    errorMessage = message,
                )
                return
            }
        }
    }

    private fun clearPlayback() {
        engine.unload()
        stopTicker()
        isLoaded = false
        _state.value = _state.value.copy(
            currentTrack = null,
            isPlaying = false,
            durationSec = 0.0,
            currentTimeSec = 0.0,
        )
    }

    fun play(ids: List<String>, startAt: Int) {
        if (ids.isEmpty()) return
        _state.value = _state.value.copy(errorMessage = null)
        queues.updateActive { it.replace(ids, startAt) }
        startCurrent(autoplay = true)
    }

    fun playInNewQueue(ids: List<String>) {
        if (ids.isEmpty()) return
        persistPosition()
        val newId = queues.addQueue(name = null)
        queues.setActive(newId)
        play(ids, startAt = 0)
    }

    fun playNext(ids: List<String>) {
        if (ids.isEmpty()) return
        val wasEmpty = queues.active.currentTrackId == null
        queues.updateActive { it.insertNext(ids) }
        if (wasEmpty) {
            startCurrent(autoplay = true)
        }
    }

    fun addToQueue(ids: List<String>, queueId: String? = null) {
        if (ids.isEmpty()) return
        val target = queueId ?: queues.state.value.activeId
        val startsPlayback = target == queues.state.value.activeId && queues.active.currentTrackId == null
        queues.update(target) { it.append(ids) }
        if (startsPlayback) {
            startCurrent(autoplay = true)
        }
    }

    fun togglePlayPause() {
        if (_state.value.isPlaying) pause() else resume()
    }

    fun resume() {
        if (_state.value.isPlaying) return
        _state.value = _state.value.copy(errorMessage = null)
        if (isLoaded) {
            ensureMediaServiceConnected()
            engine.play()
            startTicker()
            _state.value = _state.value.copy(isPlaying = true)
        } else {
            startCurrent(autoplay = true, positionSec = queues.active.positionSec)
        }
    }

    fun pause() {
        if (!_state.value.isPlaying) return
        engine.pause()
        stopTicker()
        val nowPos = engine.currentTimeSec
        _state.value = _state.value.copy(
            isPlaying = false,
            currentTimeSec = nowPos,
        )
        persistPosition()
    }

    fun next() {
        val wasPlaying = _state.value.isPlaying
        var advanced: String? = null
        queues.updateActive { queue ->
            val (updated, nextId) = queue.next(auto = false)
            advanced = nextId
            updated
        }
        if (advanced != null) {
            startCurrent(autoplay = wasPlaying)
        }
    }

    fun previous() {
        if (isLoaded && engine.currentTimeSec > RESTART_THRESHOLD_SEC) {
            seek(0.0)
            return
        }
        val wasPlaying = _state.value.isPlaying
        queues.updateActive { it.previous().first }
        startCurrent(autoplay = wasPlaying)
    }

    fun seek(timeSec: Double) {
        if (!isLoaded) return
        engine.seekTo(timeSec)
        val clamped = timeSec.coerceAtLeast(0.0)
        _state.value = _state.value.copy(currentTimeSec = clamped)
    }

    fun jump(index: Int) {
        _state.value = _state.value.copy(errorMessage = null)
        queues.updateActive { it.jump(index) }
        startCurrent(autoplay = true)
    }

    fun toggleShuffle() {
        queues.updateActive { it.withShuffle(!it.shuffle) }
    }

    fun cycleRepeat() {
        queues.updateActive { queue ->
            val nextMode = when (queue.repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
            queue.copy(repeatMode = nextMode)
        }
    }

    fun noteListening(seconds: Double) {
        val track = _state.value.currentTrack ?: return
        if (!isLoaded) return
        listenedSec += seconds
        history.addListening(seconds)
        if (!markedRecent && listenedSec >= RECENT_AFTER_SEC) {
            markedRecent = true
            history.markStarted(track.id)
        }
        val dur = _state.value.durationSec
        if (!playCounted && dur > 0.0 && listenedSec >= dur / 2.0) {
            countPlay(track)
        }
    }

    private fun countPlay(track: Track) {
        playCounted = true
        markedRecent = true
        history.recordPlay(track.id)
    }

    fun trackDidEnd() {
        val track = _state.value.currentTrack
        if (isLoaded && !playCounted && track != null) {
            countPlay(track)
        }
        val stopAfter = _state.value.sleepMode == SleepMode.EndOfTrack
        if (stopAfter) {
            setSleep(SleepMode.Off)
        }
        var advanced: String? = null
        queues.updateActive { queue ->
            val (updated, nextId) = queue.next(auto = true)
            advanced = nextId
            updated
        }
        if (advanced != null) {
            startCurrent(autoplay = !stopAfter)
        } else {
            engine.pause()
            engine.seekTo(0.0)
            stopTicker()
            _state.value = _state.value.copy(
                isPlaying = false,
                currentTimeSec = 0.0,
            )
            persistPosition()
        }
    }

    fun switchQueue(id: String) {
        if (id == queues.state.value.activeId) return
        val wasPlaying = _state.value.isPlaying
        persistPosition()
        queues.setActive(id)
        _state.value = _state.value.copy(errorMessage = null)
        startCurrent(autoplay = wasPlaying, positionSec = queues.active.positionSec)
    }

    fun deleteQueue(id: String) {
        val wasActive = id == queues.state.value.activeId
        val wasPlaying = _state.value.isPlaying
        queues.delete(id)
        if (wasActive && queues.state.value.activeId != id) {
            startCurrent(autoplay = wasPlaying, positionSec = queues.active.positionSec)
        }
    }

    fun removeFromQueue(index: Int) {
        val removingCurrent = index == queues.active.currentIndex
        val wasPlaying = _state.value.isPlaying
        queues.updateActive { it.removeAt(index) }
        if (removingCurrent) {
            startCurrent(autoplay = wasPlaying)
        }
    }

    fun moveInQueue(fromIndex: Int, toIndex: Int) {
        queues.updateActive { it.move(fromIndex, toIndex) }
    }

    fun clearQueue() {
        queues.updateActive { it.replace(emptyList(), 0) }
        clearPlayback()
    }

    private fun tracksRemoved(ids: Set<String>) {
        val currentRemoved = _state.value.currentTrack?.id?.let { it in ids } ?: false
        val wasPlaying = _state.value.isPlaying
        queues.removeTracks(ids)
        history.removeTracks(ids)
        if (currentRemoved) {
            startCurrent(autoplay = wasPlaying)
        }
    }

    fun setSleep(mode: SleepMode) {
        sleepJob?.cancel()
        sleepJob = null
        _state.value = _state.value.copy(sleepMode = mode)
        if (mode is SleepMode.Until) {
            sleepJob = scope.launch {
                val delayMs = max(0L, mode.targetEpochMs - System.currentTimeMillis())
                delay(delayMs)
                _state.value = _state.value.copy(sleepMode = SleepMode.Off)
                pause()
            }
        }
    }

    fun updateEq(eq: EqSettings) {
        engine.applyEq(eq)
        _state.value = _state.value.copy(eq = eq)
        runCatching { Persistence.save(eq, settingsFile) }
    }

    fun clearError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    fun restore() {
        loadCurrent(autoplay = false, positionSec = queues.active.positionSec)
    }

    fun persistPosition(flush: Boolean = true) {
        if (!isLoaded) return
        queues.setActivePosition(engine.currentTimeSec)
        if (flush) {
            queues.save()
            history.save()
        }
    }

    private fun tick() {
        if (!_state.value.isPlaying) return
        val pos = engine.currentTimeSec
        val dur = if (engine.durationSec > 0.0) engine.durationSec else _state.value.durationSec
        _state.value = _state.value.copy(
            currentTimeSec = pos,
            durationSec = dur,
        )
        noteListening(TICK_INTERVAL_SEC)
        tickCount += 1
        if (tickCount % 20 == 0) {
            persistPosition(flush = tickCount % 120 == 0)
        }
    }

    companion object {
        const val MAX_CONSECUTIVE_FAILURES = 3
        const val RESTART_THRESHOLD_SEC = 3.0
        const val TICK_INTERVAL_SEC = 0.25
        const val TICK_INTERVAL_MS = 250L
        const val RECENT_AFTER_SEC = 5.0
    }
}
