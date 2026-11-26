package com.musico.audio

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.musico.cloud.CloudErrorCode
import com.musico.cloud.CloudQualityOption
import com.musico.cloud.CloudSourceException
import com.musico.cloud.CloudStreamPurpose
import com.musico.cloud.CloudStreamResolver
import com.musico.cloud.ResolvedStream
import com.musico.core.Persistence
import com.musico.model.EqSettings
import com.musico.model.RepeatMode
import com.musico.model.SleepMode
import com.musico.model.Track
import com.musico.model.TrackSortOrder
import com.musico.model.compareTracks
import com.musico.store.LibraryStore
import com.musico.store.PlayHistoryStore
import com.musico.store.QueueStore
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

sealed interface PlaybackError {
    data class ConsecutiveFailures(val count: Int) : PlaybackError
    data class TrackFailed(val title: String?) : PlaybackError
    data class EngineError(val message: String?) : PlaybackError
    data object ExtensionUnavailable : PlaybackError
}

data class PlayerUiState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val durationSec: Double = 0.0,
    val currentTimeSec: Double = 0.0,
    val errorMessage: String? = null,
    val playbackError: PlaybackError? = null,
    val sleepMode: SleepMode = SleepMode.Off,
    val eq: EqSettings = EqSettings(),
    val isLoadingCloud: Boolean = false,
    val currentStreamQualityLabel: String? = null,
    val currentStreamQualityId: String? = null,
    val availableStreamQualities: List<CloudQualityOption> = emptyList(),
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
    private val engine: AudioPlaybackEngine,
    private val settingsFile: File,
    private val cloudResolver: CloudStreamResolver = CloudStreamResolver(),
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
    private var cloudLoadJob: Job? = null
    private var prefetchJob: Job? = null
    private var loadGeneration: Long = 0L
    private var cloudRetryTrackId: String? = null
    private var cloudRetryCount: Int = 0
    private var cloudAutoplayRequested: Boolean = false
    private var mediaControllerStarted = false
    private val trackQualityOverrides = ConcurrentHashMap<String, String>()

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
            val current = _state.value.currentTrack
            if (current != null && current.isCloud && cloudRetryCount < 1) {
                cloudRetryCount += 1
                val resumePos = engine.currentTimeSec.coerceAtLeast(_state.value.currentTimeSec)
                val shouldPlay = _state.value.isPlaying || cloudAutoplayRequested
                cloudResolver.invalidate(current, library.rootFor(current))
                startCloudTrack(
                    track = current,
                    autoplay = shouldPlay,
                    positionSec = resumePos,
                    forceRefresh = true,
                    consecutiveFailures = 0,
                )
            } else {
                _state.value = _state.value.copy(
                    isPlaying = false,
                    isLoadingCloud = false,
                    errorMessage = message,
                    playbackError = PlaybackError.EngineError(message),
                )
                stopTicker()
            }
        }
        library.onTracksRemoved = { ids -> tracksRemoved(ids) }
        library.onTracksRemapped = { idMap -> tracksRemapped(idMap) }
        library.onFolderAdded = { name, trackIds -> onFolderAdded(name, trackIds) }
        library.onTrackUpdated = { track -> onTrackUpdated(track) }
        if (library.referencedQueueTrackIdsProvider == null) {
            library.referencedQueueTrackIdsProvider = { queues.allReferencedTrackIds() }
        }
        if (queues.onReferencedTrackIdsChanged == null) {
            queues.onReferencedTrackIdsChanged = { ids -> library.pruneEphemeralTracks(ids) }
        }
        library.pruneEphemeralTracks(queues.allReferencedTrackIds())
    }

    fun shutdown() {
        loadGeneration += 1
        cloudLoadJob?.cancel()
        cloudLoadJob = null
        prefetchJob?.cancel()
        prefetchJob = null
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
            currentStreamQualityLabel = null,
            currentStreamQualityId = null,
            availableStreamQualities = emptyList(),
        )

        val uri = library.uriFor(track)
        val localFile = if (uri == null) library.localFileFor(track) else null
        if (uri == null && localFile == null) return false
        try {
            if (uri != null) {
                engine.load(uri, buildMediaMetadata(track))
            } else {
                engine.loadFile(localFile!!, buildMediaMetadata(track))
            }
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
            isLoadingCloud = false,
        )
        prefetchNextCloudTrack()
        return true
    }

    private fun startCurrent(
        autoplay: Boolean,
        positionSec: Double = 0.0,
        initialFailures: Int = 0,
    ) {
        loadGeneration += 1
        cloudLoadJob?.cancel()
        cloudLoadJob = null

        var failures = initialFailures
        var pos = positionSec
        while (true) {
            val id = queues.active.currentTrackId
            if (id == null) {
                clearPlayback()
                return
            }
            val track = library.track(id)
            if (track != null && track.isCloud) {
                if (cloudRetryTrackId != track.id) {
                    cloudRetryTrackId = track.id
                    cloudRetryCount = 0
                }
                startCloudTrack(
                    track = track,
                    autoplay = autoplay,
                    positionSec = pos,
                    forceRefresh = false,
                    consecutiveFailures = failures,
                )
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
                stopOnConsecutiveFailures(failures)
                return
            }
        }
    }

    private fun startCloudTrack(
        track: Track,
        autoplay: Boolean,
        positionSec: Double,
        forceRefresh: Boolean,
        consecutiveFailures: Int,
        explicitQualityId: String? = null,
    ) {
        val gen = ++loadGeneration
        cloudLoadJob?.cancel()
        cloudAutoplayRequested = autoplay
        isLoaded = false
        engine.unload()
        stopTicker()

        val initialRoot = library.rootFor(track)
        val initialProvider = cloudResolver.providerFor(track, initialRoot)
        val initialQualities = initialProvider?.availableQualities.orEmpty()
        val requestedQuality = if (initialQualities.isNotEmpty()) {
            val candidate = explicitQualityId ?: trackQualityOverrides[track.id]
            candidate?.takeIf { qId -> initialQualities.any { it.id == qId } }
        } else {
            null
        }
        val initialTargetQualityId = requestedQuality
            ?: cloudResolver.effectiveQualityId(track, initialRoot, CloudStreamPurpose.STREAM, null)
        val initialTargetLabel = if (initialQualities.isEmpty()) {
            null
        } else {
            initialTargetQualityId?.let { qId ->
                initialQualities.firstOrNull { it.id == qId }?.compactLabel ?: qId
            }
        }

        _state.value = _state.value.copy(
            currentTrack = track,
            durationSec = track.durationSec,
            currentTimeSec = positionSec.coerceAtLeast(0.0),
            isPlaying = false,
            isLoadingCloud = true,
            currentStreamQualityLabel = initialTargetLabel,
            currentStreamQualityId = initialTargetQualityId,
            availableStreamQualities = initialQualities,
        )

        cloudLoadJob = scope.launch {
            val root = library.rootFor(track)
            val provider = cloudResolver.providerFor(track, root)
            val availableQualities = provider?.availableQualities.orEmpty()
            val resolved = try {
                Result.success(
                    withContext(Dispatchers.IO) {
                        cloudResolver.resolve(
                            track = track,
                            root = root,
                            forceRefresh = forceRefresh,
                            purpose = CloudStreamPurpose.STREAM,
                            requestedQualityId = requestedQuality,
                        )
                    }
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: Exception) {
                Result.failure(err)
            }
            if (gen != loadGeneration || !isActive) return@launch

            val stream: ResolvedStream? = resolved.getOrNull()
            if (stream != null) {
                try {
                    engine.loadStream(stream.url, stream.headers, buildMediaMetadata(track))
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

                    val actualQualityId = stream.actualQuality?.takeIf { it.isNotBlank() }
                        ?: requestedQuality
                        ?: cloudResolver.effectiveQualityId(track, root, CloudStreamPurpose.STREAM, null)
                    val qualityLabel = if (availableQualities.isEmpty()) {
                        null
                    } else {
                        stream.qualityLabel?.trim()?.takeIf { it.isNotEmpty() }
                            ?: actualQualityId?.let { qId ->
                                availableQualities.firstOrNull { it.id == qId }?.compactLabel ?: qId
                            }
                    }

                    _state.value = _state.value.copy(
                        currentTrack = track,
                        durationSec = resolvedDur,
                        currentTimeSec = currentPos,
                        isPlaying = autoplay,
                        isLoadingCloud = false,
                        currentStreamQualityLabel = qualityLabel,
                        currentStreamQualityId = actualQualityId,
                        availableStreamQualities = availableQualities,
                    )
                    prefetchNextCloudTrack()
                    return@launch
                } catch (_: Exception) {
                    cloudResolver.invalidate(track, root)
                }
            }

            if (gen != loadGeneration || !isActive) return@launch
            val cloudErr = resolved.exceptionOrNull() as? CloudSourceException
            if (cloudErr?.code == CloudErrorCode.EXTENSION_UNAVAILABLE) {
                stopOnExtensionUnavailable(cloudErr)
                return@launch
            }
            val nextFailures = consecutiveFailures + 1
            var advanced: String? = null
            if (nextFailures < MAX_CONSECUTIVE_FAILURES) {
                queues.updateActive { queue ->
                    val (updated, nextId) = queue.next(auto = false)
                    advanced = nextId
                    updated
                }
            }
            if (advanced == null) {
                stopOnConsecutiveFailures(nextFailures)
            } else {
                startCurrent(autoplay = autoplay, positionSec = 0.0, initialFailures = nextFailures)
            }
        }
    }

    private fun stopOnExtensionUnavailable(err: CloudSourceException) {
        engine.unload()
        stopTicker()
        isLoaded = false
        _state.value = _state.value.copy(
            isPlaying = false,
            isLoadingCloud = false,
            errorMessage = err.message,
            playbackError = PlaybackError.ExtensionUnavailable,
        )
    }

    private fun stopOnConsecutiveFailures(failures: Int) {
        engine.unload()
        stopTicker()
        isLoaded = false
        val title = _state.value.currentTrack?.title ?: "This song"
        val message = if (failures > 1) {
            "Playback stopped: $failures songs in a row could not be played."
        } else {
            "“$title” could not be played."
        }
        val structuredError = if (failures > 1) {
            PlaybackError.ConsecutiveFailures(failures)
        } else {
            PlaybackError.TrackFailed(_state.value.currentTrack?.title)
        }
        _state.value = _state.value.copy(
            isPlaying = false,
            isLoadingCloud = false,
            errorMessage = message,
            playbackError = structuredError,
        )
    }

    private fun prefetchNextCloudTrack() {
        val nextIndex = queues.active.upcomingIndices.firstOrNull() ?: return
        val nextId = queues.active.trackIds.getOrNull(nextIndex) ?: return
        val nextTrack = library.track(nextId) ?: return
        if (!nextTrack.isCloud) return
        val root = library.rootFor(nextTrack)
        val overrideQuality = trackQualityOverrides[nextTrack.id]
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            cloudResolver.prefetch(nextTrack, root, requestedQualityId = overrideQuality)
        }
    }

    /**
     * Switches audio quality strictly for the currently playing cloud track,
     * preserving playback position and play/pause state without changing folder or extension defaults.
     */
    fun switchCurrentTrackQuality(qualityId: String) {
        val current = _state.value.currentTrack ?: return
        if (!current.isCloud) return
        val root = library.rootFor(current)
        val available = _state.value.availableStreamQualities.ifEmpty {
            cloudResolver.providerFor(current, root)?.availableQualities.orEmpty()
        }
        val cleanId = qualityId.trim()
        if (available.none { it.id == cleanId }) return

        val resumePos = (if (isLoaded) engine.currentTimeSec else _state.value.currentTimeSec).coerceAtLeast(0.0)
        val shouldPlay = isActiveOrLoadingToPlay
        trackQualityOverrides[current.id] = cleanId
        cloudRetryCount = 0
        cloudResolver.audioCache?.evictTrack(current.id, keepQualityId = cleanId)
        startCloudTrack(
            track = current,
            autoplay = shouldPlay,
            positionSec = resumePos,
            forceRefresh = true,
            consecutiveFailures = 0,
            explicitQualityId = cleanId,
        )
    }

    fun trackQualityOverrideFor(trackId: String): String? = trackQualityOverrides[trackId]

    private fun clearPlayback() {
        loadGeneration += 1
        cloudLoadJob?.cancel()
        cloudLoadJob = null
        engine.unload()
        stopTicker()
        isLoaded = false
        _state.value = _state.value.copy(
            currentTrack = null,
            isPlaying = false,
            isLoadingCloud = false,
            durationSec = 0.0,
            currentTimeSec = 0.0,
            currentStreamQualityLabel = null,
            currentStreamQualityId = null,
            availableStreamQualities = emptyList(),
        )
    }

    fun play(ids: List<String>, startAt: Int) {
        if (ids.isEmpty()) return
        _state.value = _state.value.copy(errorMessage = null, playbackError = null)
        queues.updateActive { it.replace(ids, startAt) }
        startCurrent(autoplay = true)
    }

    fun playInNewQueue(ids: List<String>, startAt: Int = 0, name: String? = null) {
        if (ids.isEmpty()) return
        persistPosition()
        queues.addQueue(
            name = name,
            trackIds = ids,
            startAt = startAt,
            activate = true,
        )
        _state.value = _state.value.copy(errorMessage = null, playbackError = null)
        startCurrent(autoplay = true)
    }

    fun playInNamedQueue(
        name: String,
        ids: List<String>,
        startAt: Int = 0,
        shuffle: Boolean = false,
    ) {
        if (ids.isEmpty()) return
        persistPosition()
        val (newId, _) = queues.createOrReplaceQueue(
            name = name,
            trackIds = ids,
            startAt = startAt,
        )
        queues.setActive(newId)
        if (shuffle) {
            queues.update(newId) { it.withShuffle(true) }
        }
        _state.value = _state.value.copy(errorMessage = null, playbackError = null)
        startCurrent(autoplay = true)
    }

    fun syncNamedQueue(name: String, ids: List<String>) {
        if (ids.isEmpty()) return
        val active = queues.active
        if (active.name == name) {
            if (active.trackIds == ids) return
            val currentId = active.currentTrackId
            if (currentId != null && currentId in ids) {
                val nextIndex = ids.indexOf(currentId)
                queues.update(active.id) { queue ->
                    queue.copy(
                        trackIds = ids.toList(),
                        currentIndex = nextIndex,
                    ).withShuffle(queue.shuffle)
                }
                return
            }
        }
        queues.createOrReplaceQueue(
            name = name,
            trackIds = ids,
            startAt = 0,
        )
    }

    fun onFolderAdded(folderName: String, trackIds: List<String>) {
        val active = queues.active
        if (trackIds.isNotEmpty() && active.name == folderName) {
            val currentId = active.currentTrackId
            if (currentId != null && currentId in trackIds && (isLoaded || _state.value.isLoadingCloud || _state.value.isPlaying)) {
                if (active.trackIds != trackIds) {
                    val nextIndex = trackIds.indexOf(currentId)
                    queues.update(active.id) { queue ->
                        queue.copy(
                            trackIds = trackIds.toList(),
                            currentIndex = nextIndex,
                        ).withShuffle(queue.shuffle)
                    }
                }
                return
            }
        }
        val (_, wasActive) = queues.createOrReplaceQueue(
            name = folderName,
            trackIds = trackIds,
            startAt = 0,
        )
        if (wasActive) {
            if (trackIds.isEmpty()) {
                clearPlayback()
            } else {
                startCurrent(autoplay = isActiveOrLoadingToPlay)
            }
        }
    }

    private fun onTrackUpdated(updatedTrack: Track) {
        if (_state.value.currentTrack?.id == updatedTrack.id) {
            _state.value = _state.value.copy(currentTrack = updatedTrack)
        }
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

    private val isActiveOrLoadingToPlay: Boolean
        get() = _state.value.isPlaying || (_state.value.isLoadingCloud && cloudAutoplayRequested)

    fun togglePlayPause() {
        if (isActiveOrLoadingToPlay) pause() else resume()
    }

    fun resume() {
        if (_state.value.isPlaying) return
        _state.value = _state.value.copy(errorMessage = null, playbackError = null)
        if (_state.value.isLoadingCloud) {
            cloudAutoplayRequested = true
            return
        }
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
        if (_state.value.isLoadingCloud) {
            cloudAutoplayRequested = false
        }
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
        val wasPlaying = isActiveOrLoadingToPlay
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
        val wasPlaying = isActiveOrLoadingToPlay
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
        _state.value = _state.value.copy(errorMessage = null, playbackError = null)
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
        if (track.isEphemeral || Track.isEphemeralId(track.id)) return
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
        if (track.isEphemeral || Track.isEphemeralId(track.id)) return
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

    fun playTrack(inQueue: String, index: Int) {
        val targetQueue = queues.state.value.queues.firstOrNull { it.id == inQueue } ?: return
        if (index !in targetQueue.trackIds.indices) return
        if (queues.state.value.activeId != inQueue) {
            persistPosition()
            queues.setActive(inQueue)
        }
        _state.value = _state.value.copy(errorMessage = null, playbackError = null)
        queues.update(inQueue) { it.jump(index) }
        startCurrent(autoplay = true)
    }

    fun switchQueue(id: String) {
        if (id == queues.state.value.activeId) return
        val wasPlaying = isActiveOrLoadingToPlay
        persistPosition()
        queues.setActive(id)
        _state.value = _state.value.copy(errorMessage = null, playbackError = null)
        startCurrent(autoplay = wasPlaying, positionSec = queues.active.positionSec)
    }

    fun deleteQueue(id: String) {
        val wasActive = id == queues.state.value.activeId
        queues.delete(id)
        if (wasActive && queues.state.value.activeId != id) {
            clearPlayback()
        }
    }

    fun removeFromQueue(index: Int, queueId: String = queues.state.value.activeId) {
        val targetQueue = queues.state.value.queues.firstOrNull { it.id == queueId } ?: return
        val isActiveQueue = queueId == queues.state.value.activeId
        val removingCurrent = isActiveQueue && index == targetQueue.currentIndex
        val wasPlaying = isActiveOrLoadingToPlay
        queues.update(queueId) { it.removeAt(index) }
        if (removingCurrent) {
            startCurrent(autoplay = wasPlaying)
        }
    }

    fun moveInQueue(fromIndex: Int, toIndex: Int, queueId: String = queues.state.value.activeId) {
        queues.update(queueId) { it.move(fromIndex, toIndex) }
    }

    fun reorderQueue(newOrderIndices: List<Int>, queueId: String = queues.state.value.activeId) {
        queues.update(queueId) { it.reorder(newOrderIndices, TrackSortOrder.CUSTOM) }
    }

    fun sortQueue(sortOrder: TrackSortOrder, queueId: String? = null) {
        val targetId = queueId ?: queues.state.value.activeId
        if (sortOrder == TrackSortOrder.CUSTOM) {
            queues.update(targetId) { it.copy(sortOrder = TrackSortOrder.CUSTOM) }
            return
        }
        val playCounts: (String) -> Int = { history.count(it) }
        queues.update(targetId) { queue ->
            val sortedIndices = queue.trackIds.indices.sortedWith { idxA, idxB ->
                val trackA = library.track(queue.trackIds[idxA])
                val trackB = library.track(queue.trackIds[idxB])
                when {
                    trackA == null && trackB == null -> 0
                    trackA == null -> 1
                    trackB == null -> -1
                    else -> compareTracks(trackA, trackB, sortOrder, playCounts)
                }
            }
            queue.reorder(sortedIndices, sortOrder)
        }
    }

    fun clearQueue(queueId: String = queues.state.value.activeId) {
        val isActiveQueue = queueId == queues.state.value.activeId
        queues.update(queueId) { it.replace(emptyList(), 0) }
        if (isActiveQueue) {
            clearPlayback()
        }
    }

    private fun tracksRemoved(ids: Set<String>) {
        val currentRemoved = _state.value.currentTrack?.id?.let { it in ids } ?: false
        val wasPlaying = isActiveOrLoadingToPlay
        ids.forEach { trackQualityOverrides.remove(it) }
        queues.removeTracks(ids)
        history.removeTracks(ids)
        if (currentRemoved) {
            startCurrent(autoplay = wasPlaying)
        }
    }

    private fun tracksRemapped(idMap: Map<String, String>) {
        if (idMap.isEmpty()) return
        for ((oldId, newId) in idMap) {
            trackQualityOverrides.remove(oldId)?.let { q ->
                trackQualityOverrides.putIfAbsent(newId, q)
            }
        }
        queues.remapTracks(idMap)
        val current = _state.value.currentTrack
        val mappedCurrentId = current?.id?.let { idMap[it] }
        if (mappedCurrentId != null) {
            library.track(mappedCurrentId)?.let { updated ->
                _state.value = _state.value.copy(currentTrack = updated)
            }
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
        _state.value = _state.value.copy(errorMessage = null, playbackError = null)
    }

    fun restore() {
        val id = queues.active.currentTrackId ?: return
        val track = library.track(id) ?: return
        if (track.isCloud) {
            isLoaded = false
            _state.value = _state.value.copy(
                currentTrack = track,
                durationSec = track.durationSec,
                currentTimeSec = queues.active.positionSec.coerceAtLeast(0.0),
                isPlaying = false,
            )
            return
        }
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
