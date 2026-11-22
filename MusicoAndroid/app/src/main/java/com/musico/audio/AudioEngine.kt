package com.musico.audio

import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.musico.model.EqSettings
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Playback engine abstraction for [PlayerController].
 */
interface AudioPlaybackEngine {
    var onTrackEnded: (() -> Unit)?
    var onPlaybackError: ((String) -> Unit)?
    var onDurationChanged: ((Double) -> Unit)?
    var onIsPlayingChanged: ((Boolean) -> Unit)?

    val currentTimeSec: Double
    val durationSec: Double

    fun load(uri: Uri, metadata: MediaMetadata? = null)
    fun loadFile(file: File, metadata: MediaMetadata? = null) = load(Uri.fromFile(file), metadata)
    fun loadStream(url: String, headers: Map<String, String>, metadata: MediaMetadata? = null)
    fun play()
    fun pause()
    fun seekTo(sec: Double)
    fun unload()
    fun applyEq(settings: EqSettings)
}

/**
 * ExoPlayer wrapper with a 10-band equalizer using [DynamicsProcessing] on API 28+
 * and [Equalizer] fallback on API 26–27.
 */
class AudioEngine(
    private val context: Context,
) : AudioPlaybackEngine {
    override var onTrackEnded: (() -> Unit)? = null
    var onAudioSessionIdChanged: ((Int) -> Unit)? = null
    override var onPlaybackError: ((String) -> Unit)? = null
    override var onDurationChanged: ((Double) -> Unit)? = null
    override var onIsPlayingChanged: ((Boolean) -> Unit)? = null

    val player: ExoPlayer

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var legacyEqualizer: Equalizer? = null
    private var lastEqSettings: EqSettings = EqSettings()
    private var currentSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    val dur = durationSec
                    if (dur > 0.0) {
                        onDurationChanged?.invoke(dur)
                    }
                }
                Player.STATE_ENDED -> {
                    onTrackEnded?.invoke()
                }
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@AudioEngine.onIsPlayingChanged?.invoke(isPlaying)
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            currentSessionId = audioSessionId
            setupEqualizerForSession(audioSessionId)
            this@AudioEngine.onAudioSessionIdChanged?.invoke(audioSessionId)
        }

        override fun onPlayerError(error: PlaybackException) {
            onPlaybackError?.invoke(error.localizedMessage ?: "Playback error")
        }
    }

    init {
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(context.applicationContext)
            .setAudioAttributes(attributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(playerListener)
        currentSessionId = player.audioSessionId
        setupEqualizerForSession(currentSessionId)
    }

    override val currentTimeSec: Double
        get() {
            val posMs = player.currentPosition
            return if (posMs > 0L) posMs / 1000.0 else 0.0
        }

    override val durationSec: Double
        get() {
            val durMs = player.duration
            return if (durMs != C.TIME_UNSET && durMs > 0L) durMs / 1000.0 else 0.0
        }

    val isPlaying: Boolean
        get() = player.isPlaying

    @Throws(IOException::class)
    override fun load(uri: Uri, metadata: MediaMetadata?) {
        if (uri.scheme == "file") {
            val path = uri.path ?: throw IOException("Invalid file URI")
            val file = File(path)
            if (!file.exists() || !file.canRead() || file.length() == 0L) {
                throw IOException("File cannot be opened: $path")
            }
        }

        val itemBuilder = MediaItem.Builder().setUri(uri)
        if (metadata != null) {
            itemBuilder.setMediaMetadata(metadata)
        }
        player.playWhenReady = false
        player.setMediaItem(itemBuilder.build())
        player.prepare()
    }

    @OptIn(UnstableApi::class)
    @Throws(IOException::class)
    override fun loadStream(
        url: String,
        headers: Map<String, String>,
        metadata: MediaMetadata?,
    ) {
        if (url.isBlank()) throw IOException("Empty stream URL")
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(headers)
        val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)
        val itemBuilder = MediaItem.Builder().setUri(url)
        if (metadata != null) {
            itemBuilder.setMediaMetadata(metadata)
        }
        val mediaSource = mediaSourceFactory.createMediaSource(itemBuilder.build())
        player.playWhenReady = false
        player.setMediaSource(mediaSource)
        player.prepare()
    }

    override fun play() {
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0L)
        }
        player.playWhenReady = true
        player.play()
    }

    override fun pause() {
        player.playWhenReady = false
        player.pause()
    }

    override fun seekTo(sec: Double) {
        val safeSec = if (sec.isFinite() && sec >= 0.0) sec else 0.0
        player.seekTo((safeSec * 1000.0).toLong())
    }

    override fun unload() {
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
    }

    override fun applyEq(settings: EqSettings) {
        lastEqSettings = settings
        applyCurrentEq()
    }

    fun release() {
        releaseEqualizer()
        player.removeListener(playerListener)
        player.release()
    }

    private fun setupEqualizerForSession(sessionId: Int) {
        releaseEqualizer()
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId == 0) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val bandCount = EqSettings.FREQUENCIES.size
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    /* channelCount= */ 2,
                    /* preEqInUse= */ true,
                    /* preEqBandCount= */ bandCount,
                    /* mbcInUse= */ false,
                    /* mbcBandCount= */ 0,
                    /* postEqInUse= */ false,
                    /* postEqBandCount= */ 0,
                    /* limiterInUse= */ true,
                ).build()
                dynamicsProcessing = DynamicsProcessing(0, sessionId, config)
            }
        } else {
            runCatching {
                legacyEqualizer = Equalizer(0, sessionId)
            }
        }
        applyCurrentEq()
    }

    private fun applyCurrentEq() {
        val settings = lastEqSettings
        val minGain = EqSettings.GAIN_RANGE.start
        val maxGain = EqSettings.GAIN_RANGE.endInclusive
        val clampedPreamp = settings.preampDb.coerceIn(minGain, maxGain)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val dp = dynamicsProcessing ?: return
            runCatching {
                dp.enabled = settings.enabled
                dp.setInputGainAllChannelsTo(if (settings.enabled) clampedPreamp else 0f)
                val freqs = EqSettings.FREQUENCIES
                val preEq = DynamicsProcessing.Eq(true, settings.enabled, freqs.size)
                for (i in freqs.indices) {
                    val cutoff = if (i < freqs.lastIndex) {
                        sqrt(freqs[i] * freqs[i + 1])
                    } else {
                        20000f
                    }
                    val rawGain = settings.gains.getOrElse(i) { 0f }
                    val gain = if (settings.enabled) rawGain.coerceIn(minGain, maxGain) else 0f
                    preEq.setBand(i, DynamicsProcessing.EqBand(true, cutoff, gain))
                }
                dp.setPreEqAllChannelsTo(preEq)
            }
        } else {
            val eq = legacyEqualizer ?: return
            runCatching {
                eq.enabled = settings.enabled
                val range = eq.bandLevelRange
                val minMb = range[0].toInt()
                val maxMb = range[1].toInt()
                val numBands = eq.numberOfBands.toInt()
                for (band in 0 until numBands) {
                    val centerHz = eq.getCenterFreq(band.toShort()) / 1000f
                    val gainDb = if (settings.enabled) {
                        (nearestBandGain(centerHz, settings.gains) + clampedPreamp)
                            .coerceIn(minGain, maxGain)
                    } else {
                        0f
                    }
                    val levelMb = (gainDb * 100f).roundToInt().coerceIn(minMb, maxMb).toShort()
                    eq.setBandLevel(band.toShort(), levelMb)
                }
            }
        }
    }

    private fun nearestBandGain(freqHz: Float, gains: List<Float>): Float {
        val freqs = EqSettings.FREQUENCIES
        var bestIdx = 0
        var bestDist = Float.MAX_VALUE
        for (i in freqs.indices) {
            val dist = abs(freqs[i] - freqHz)
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
            }
        }
        return gains.getOrElse(bestIdx) { 0f }
    }

    private fun releaseEqualizer() {
        runCatching { dynamicsProcessing?.release() }
        dynamicsProcessing = null
        runCatching { legacyEqualizer?.release() }
        legacyEqualizer = null
    }
}
