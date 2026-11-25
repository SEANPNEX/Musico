package com.musico.store

import android.net.Uri
import androidx.media3.common.MediaMetadata
import com.musico.audio.AudioPlaybackEngine
import com.musico.audio.PlayerController
import com.musico.core.ArtworkCache
import com.musico.core.MetadataReading
import com.musico.core.TrackMetadata
import com.musico.model.EqSettings
import com.musico.model.TrackSortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class QueueOverhaulTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeEngine : AudioPlaybackEngine {
        override var onTrackEnded: (() -> Unit)? = null
        override var onPlaybackError: ((String) -> Unit)? = null
        override var onDurationChanged: ((Double) -> Unit)? = null
        override var onIsPlayingChanged: ((Boolean) -> Unit)? = null
        override var currentTimeSec: Double = 0.0
        override var durationSec: Double = 100.0

        var loadCalls = 0
        var playCalls = 0
        var pauseCalls = 0
        var unloadCalls = 0

        override fun load(uri: Uri, metadata: MediaMetadata?) {
            loadCalls += 1
        }

        override fun loadFile(file: File, metadata: MediaMetadata?) {
            loadCalls += 1
        }

        override fun loadStream(url: String, headers: Map<String, String>, metadata: MediaMetadata?) {
            loadCalls += 1
        }

        override fun play() {
            playCalls += 1
        }

        override fun pause() {
            pauseCalls += 1
        }

        override fun seekTo(sec: Double) {
            currentTimeSec = sec
        }

        override fun unload() {
            unloadCalls += 1
        }

        override fun applyEq(settings: EqSettings) = Unit
    }

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var stateDir: File
    private lateinit var docsDir: File
    private lateinit var libraryStore: LibraryStore
    private lateinit var queueStore: QueueStore
    private lateinit var historyStore: PlayHistoryStore
    private lateinit var engine: FakeEngine
    private lateinit var controller: PlayerController

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        stateDir = tempFolder.newFolder("state")
        docsDir = tempFolder.newFolder("docs")
        val reader = object : MetadataReading {
            override suspend fun read(uri: Uri, fileNameHint: String?): TrackMetadata {
                val name = (fileNameHint ?: uri.lastPathSegment ?: "song").substringBeforeLast('.')
                return TrackMetadata(title = name, artist = "Artist", album = "Album", durationSec = 100.0)
            }

            override suspend fun read(file: File): TrackMetadata =
                TrackMetadata(title = file.nameWithoutExtension, artist = "Artist", album = "Album", durationSec = 100.0)
        }
        libraryStore = LibraryStore(
            directory = stateDir,
            appFolderDir = docsDir,
            reader = reader,
            artwork = ArtworkCache(File(stateDir, "art")),
            context = null,
        )
        queueStore = QueueStore(stateDir)
        historyStore = PlayHistoryStore(stateDir)
        engine = FakeEngine()
        controller = PlayerController(
            context = null,
            library = libraryStore,
            queues = queueStore,
            history = historyStore,
            engine = engine,
            settingsFile = File(stateDir, "settings.json"),
        )
    }

    @After
    fun tearDown() {
        controller.shutdown()
        Dispatchers.resetMain()
    }

    private fun seedTracks(vararg names: String): List<String> = runBlocking {
        for (name in names) {
            File(docsDir, "$name.mp3").writeText("audio:$name", Charsets.UTF_8)
        }
        libraryStore.rescan()
        names.map { name -> libraryStore.state.value.tracks.first { it.title == name }.id }
    }

    @Test
    fun coldLaunchInitializesViewedQueueToActiveQueueAndSelectingViewedDoesNotInterruptPlayback() {
        val ids = seedTracks("Alpha", "Beta", "Gamma", "Delta")
        val initialId = queueStore.state.value.activeId
        assertEquals(initialId, queueStore.state.value.viewedId)

        // Start playing tracks in Queue 1.
        controller.play(listOf(ids[0], ids[1]), startAt = 0)
        assertTrue(controller.state.value.isPlaying)
        assertEquals(ids[0], controller.state.value.currentTrack?.id)
        val loadsBeforeInspect = engine.loadCalls
        val playsBeforeInspect = engine.playCalls

        // Create Queue 2 (auto-selected as viewedId) and add tracks without disturbing Queue 1 playback.
        val secondId = queueStore.addQueue("Late Night")
        assertEquals(initialId, queueStore.state.value.activeId)
        assertEquals(secondId, queueStore.state.value.viewedId)
        assertEquals(loadsBeforeInspect, engine.loadCalls)
        assertEquals(playsBeforeInspect, engine.playCalls)
        assertEquals(ids[0], controller.state.value.currentTrack?.id)

        // Mutate viewed queue (add, move, sort, rename) -> playing queue remains untouched!
        controller.addToQueue(listOf(ids[2], ids[3]), queueId = secondId)
        controller.moveInQueue(fromIndex = 1, toIndex = 0, queueId = secondId)
        controller.sortQueue(TrackSortOrder.NAME_ASC, queueId = secondId)
        queueStore.rename(secondId, "Night Mix")

        assertEquals(initialId, queueStore.state.value.activeId)
        assertEquals(ids[0], controller.state.value.currentTrack?.id)
        assertEquals(loadsBeforeInspect, engine.loadCalls)
        val secondQueue = queueStore.state.value.queues.first { it.id == secondId }
        assertEquals("Night Mix", secondQueue.name)
        assertEquals(listOf(ids[3], ids[2]), secondQueue.trackIds) // "Delta", "Gamma"

        // Switching viewed back to Queue 1 and back to Queue 2 never interrupts playback.
        queueStore.selectViewedQueue(initialId)
        assertEquals(initialId, queueStore.state.value.viewedId)
        queueStore.selectViewedQueue(secondId)
        assertEquals(secondId, queueStore.state.value.viewedId)
        assertEquals(initialId, queueStore.state.value.activeId)
        assertEquals(loadsBeforeInspect, engine.loadCalls)

        // Only tapping a track row in viewed queue switches active playing queue and starts playback.
        controller.playTrack(inQueue = secondId, index = 1)
        assertEquals(secondId, queueStore.state.value.activeId)
        assertEquals(secondId, queueStore.state.value.viewedId)
        assertEquals(ids[2], controller.state.value.currentTrack?.id)
        assertTrue(controller.state.value.isPlaying)
        assertTrue(engine.loadCalls > loadsBeforeInspect)
    }

    @Test
    fun deletingOrClearingPlayingQueuePausesCleanlyWithoutAutoStartingAnotherQueue() {
        val ids = seedTracks("One", "Two", "Three")
        val q1 = queueStore.state.value.activeId
        controller.play(listOf(ids[0]), startAt = 0)

        val q2 = queueStore.addQueue("Other Queue")
        controller.addToQueue(listOf(ids[1], ids[2]), queueId = q2)

        // Switch playback to q2.
        controller.playTrack(inQueue = q2, index = 0)
        assertEquals(q2, queueStore.state.value.activeId)
        assertTrue(controller.state.value.isPlaying)

        val loadsBeforeClear = engine.loadCalls
        // Clearing q2 pauses/clears cleanly without starting q1.
        controller.clearQueue(queueId = q2)
        assertFalse(controller.state.value.isPlaying)
        assertNull(controller.state.value.currentTrack)
        assertEquals(loadsBeforeClear, engine.loadCalls)

        // Re-start q2, then delete q2 -> falls back to q1 without auto-starting q1!
        controller.addToQueue(listOf(ids[1]), queueId = q2)
        controller.playTrack(inQueue = q2, index = 0)
        assertTrue(controller.state.value.isPlaying)

        val loadsBeforeDelete = engine.loadCalls
        controller.deleteQueue(q2)
        assertEquals(q1, queueStore.state.value.activeId)
        assertEquals(q1, queueStore.state.value.viewedId)
        assertFalse(controller.state.value.isPlaying)
        assertNull(controller.state.value.currentTrack)
        assertEquals(loadsBeforeDelete, engine.loadCalls)
    }

    @Test
    fun playInNamedQueuePreservesExistingCustomQueues() {
        val ids = seedTracks("A", "B", "C")
        val customId = queueStore.state.value.activeId
        queueStore.rename(customId, "My Custom Queue")
        controller.play(listOf(ids[0]), startAt = 0)

        // Playing from Songs, Album, Artist, or Stats uses named queues and keeps "My Custom Queue" intact.
        controller.playInNamedQueue(name = "Songs", ids = ids, startAt = 1, shuffle = false)
        assertEquals("Songs", queueStore.active.name)
        assertEquals(ids[1], controller.state.value.currentTrack?.id)

        controller.playInNamedQueue(name = "Album: Best Of", ids = listOf(ids[0], ids[2]), startAt = 0, shuffle = true)
        assertEquals("Album: Best Of", queueStore.active.name)
        assertTrue(queueStore.active.shuffle)

        controller.playInNamedQueue(name = "Artist: Kano", ids = listOf(ids[2]), startAt = 0, shuffle = false)
        assertEquals("Artist: Kano", queueStore.active.name)

        controller.playInNamedQueue(name = "Stats", ids = listOf(ids[0]), startAt = 0, shuffle = false)
        assertEquals("Stats", queueStore.active.name)

        val custom = queueStore.state.value.queues.first { it.id == customId }
        assertEquals("My Custom Queue", custom.name)
        assertEquals(listOf(ids[0]), custom.trackIds)
        assertNotEquals(customId, queueStore.state.value.activeId)
    }
}
