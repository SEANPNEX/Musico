package com.musico.features

import android.net.Uri
import androidx.media3.common.MediaMetadata
import com.musico.audio.AudioPlaybackEngine
import com.musico.audio.PlayerController
import com.musico.cloud.BilibiliClient
import com.musico.cloud.CloudHttpClient
import com.musico.cloud.CloudHttpResponse
import com.musico.cloud.YouTubeClient
import com.musico.core.ArtworkCache
import com.musico.core.AudioFileInfoReader
import com.musico.core.MetadataReading
import com.musico.core.TrackMetadata
import com.musico.model.EqSettings
import com.musico.model.TrackSortOrder
import com.musico.store.LibraryStore
import com.musico.store.PlayHistoryStore
import com.musico.store.QueueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.random.Random

private class FakeAudioEngine : AudioPlaybackEngine {
    override var onTrackEnded: (() -> Unit)? = null
    override var onPlaybackError: ((String) -> Unit)? = null
    override var onDurationChanged: ((Double) -> Unit)? = null
    override var onIsPlayingChanged: ((Boolean) -> Unit)? = null

    override var currentTimeSec: Double = 0.0
    override var durationSec: Double = 10.0

    var loadCalls = 0
    var playCalls = 0
    var unloadCalls = 0
    var lastLoadedUri: Uri? = null

    override fun load(uri: Uri, metadata: MediaMetadata?) {
        loadCalls += 1
        lastLoadedUri = uri
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

    override fun pause() = Unit

    override fun seekTo(sec: Double) {
        currentTimeSec = sec
    }

    override fun unload() {
        unloadCalls += 1
    }

    override fun applyEq(settings: EqSettings) = Unit
}

private class TestMetadataReader : MetadataReading {
    val byName = mutableMapOf<String, TrackMetadata>()

    override suspend fun read(uri: Uri, fileNameHint: String?): TrackMetadata {
        val name = fileNameHint ?: uri.lastPathSegment ?: ""
        return byName[name] ?: TrackMetadata(durationSec = 10.0)
    }

    override suspend fun read(file: File): TrackMetadata {
        return byName[file.name] ?: TrackMetadata(durationSec = 10.0)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MusicoV115FeaturesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var stateDir: File
    private lateinit var docsDir: File
    private lateinit var reader: TestMetadataReader
    private lateinit var libraryStore: LibraryStore
    private lateinit var queueStore: QueueStore
    private lateinit var historyStore: PlayHistoryStore
    private lateinit var engine: FakeAudioEngine
    private lateinit var controller: PlayerController

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        stateDir = tempFolder.newFolder("state")
        docsDir = tempFolder.newFolder("docs")
        reader = TestMetadataReader()
        libraryStore = LibraryStore(
            directory = stateDir,
            appFolderDir = docsDir,
            reader = reader,
            artwork = ArtworkCache(File(stateDir, "artwork")),
            context = null,
        )
        queueStore = QueueStore(stateDir)
        historyStore = PlayHistoryStore(stateDir)
        engine = FakeAudioEngine()
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

    private fun touch(relativePath: String, root: File = docsDir, modifiedMs: Long = 1000L): File {
        val file = File(root, relativePath)
        file.parentFile?.mkdirs()
        file.writeText("audio:$relativePath", Charsets.UTF_8)
        file.setLastModified(modifiedMs)
        return file
    }

    @Test
    fun searchQueueNamingUsesFieldAndQueryWithoutReplacingExistingQueue() = runBlocking {
        touch("a.mp3")
        touch("b.mp3")
        touch("c.mp3")
        reader.byName["a.mp3"] = TrackMetadata(title = "Song A", artist = "Other", durationSec = 10.0)
        reader.byName["b.mp3"] = TrackMetadata(title = "Song B", artist = "Kano", durationSec = 10.0)
        reader.byName["c.mp3"] = TrackMetadata(title = "Song C", artist = "Kano", durationSec = 10.0)
        libraryStore.rescan()

        val idA = libraryStore.state.value.tracks.first { it.title == "Song A" }.id
        val idB = libraryStore.state.value.tracks.first { it.title == "Song B" }.id
        val idC = libraryStore.state.value.tracks.first { it.title == "Song C" }.id

        val initialQueueId = queueStore.active.id
        controller.play(listOf(idA), startAt = 0)
        assertEquals(listOf(idA), queueStore.active.trackIds)

        // Clicking result in Search launched from Artist link creates "Artist: Kano" queue.
        controller.playInNewQueue(ids = listOf(idB, idC), startAt = 1, name = "Artist: Kano")
        assertEquals("Artist: Kano", queueStore.active.name)
        assertEquals(listOf(idB, idC), queueStore.active.trackIds)
        assertEquals(1, queueStore.active.currentIndex)
        assertEquals(idC, controller.state.value.currentTrack?.id)

        // Original queue remains intact.
        val originalQueue = queueStore.state.value.queues.first { it.id == initialQueueId }
        assertEquals(listOf(idA), originalQueue.trackIds)

        // Clicking result from manual Keyword search creates "Keyword: Kano" queue.
        controller.playInNewQueue(ids = listOf(idB, idC), startAt = 0, name = "Keyword: Kano")
        assertEquals("Keyword: Kano", queueStore.active.name)
        assertEquals(idB, controller.state.value.currentTrack?.id)
        assertEquals(3, queueStore.state.value.queues.size)
    }

    @Test
    fun queueSortingModesPreserveActivePlayingSongIndexAndShuffleInvariant() = runBlocking {
        touch("1.mp3", modifiedMs = 1_000L)
        touch("2.mp3", modifiedMs = 4_000L)
        touch("3.mp3", modifiedMs = 2_000L)
        touch("4.mp3", modifiedMs = 3_000L)
        reader.byName["1.mp3"] = TrackMetadata(title = "Charlie", durationSec = 10.0)
        reader.byName["2.mp3"] = TrackMetadata(title = "Alpha", durationSec = 10.0)
        reader.byName["3.mp3"] = TrackMetadata(title = "Delta", durationSec = 10.0)
        reader.byName["4.mp3"] = TrackMetadata(title = "Bravo", durationSec = 10.0)
        libraryStore.rescan()

        val idAlpha = libraryStore.state.value.tracks.first { it.title == "Alpha" }.id
        val idBravo = libraryStore.state.value.tracks.first { it.title == "Bravo" }.id
        val idCharlie = libraryStore.state.value.tracks.first { it.title == "Charlie" }.id
        val idDelta = libraryStore.state.value.tracks.first { it.title == "Delta" }.id

        // Record play counts: Bravo=3, Delta=2, Alpha=1, Charlie=0
        repeat(3) { historyStore.recordPlay(idBravo) }
        repeat(2) { historyStore.recordPlay(idDelta) }
        historyStore.recordPlay(idAlpha)

        controller.play(listOf(idCharlie, idAlpha, idDelta, idBravo), startAt = 2)
        assertEquals(idDelta, queueStore.active.currentTrackId)
        val loadsBeforeSort = engine.loadCalls

        // Enable shuffle to verify shuffleOrder stays valid across sorts.
        controller.toggleShuffle()
        assertTrue(queueStore.active.shuffle)

        controller.sortQueue(TrackSortOrder.NAME_ASC)
        assertEquals(listOf(idAlpha, idBravo, idCharlie, idDelta), queueStore.active.trackIds)
        assertEquals(3, queueStore.active.currentIndex)
        assertEquals(idDelta, queueStore.active.currentTrackId)
        assertEquals(TrackSortOrder.NAME_ASC, queueStore.active.sortOrder)
        assertEquals(listOf(0, 1, 2, 3), queueStore.active.shuffleOrder.sorted())
        assertEquals(loadsBeforeSort, engine.loadCalls)

        controller.sortQueue(TrackSortOrder.NAME_DESC)
        assertEquals(listOf(idDelta, idCharlie, idBravo, idAlpha), queueStore.active.trackIds)
        assertEquals(0, queueStore.active.currentIndex)
        assertEquals(idDelta, queueStore.active.currentTrackId)

        controller.sortQueue(TrackSortOrder.DATE_MODIFIED)
        assertEquals(listOf(idAlpha, idBravo, idDelta, idCharlie), queueStore.active.trackIds)
        assertEquals(2, queueStore.active.currentIndex)
        assertEquals(idDelta, queueStore.active.currentTrackId)

        controller.sortQueue(TrackSortOrder.MOST_PLAYED)
        assertEquals(listOf(idBravo, idDelta, idAlpha, idCharlie), queueStore.active.trackIds)
        assertEquals(1, queueStore.active.currentIndex)

        controller.sortQueue(TrackSortOrder.LEAST_PLAYED)
        assertEquals(listOf(idCharlie, idAlpha, idDelta, idBravo), queueStore.active.trackIds)
        assertEquals(2, queueStore.active.currentIndex)

        // Drag reorder sets CUSTOM and persists across QueueStore reload.
        controller.reorderQueue(listOf(3, 2, 1, 0))
        assertEquals(TrackSortOrder.CUSTOM, queueStore.active.sortOrder)
        assertEquals(listOf(idBravo, idDelta, idAlpha, idCharlie), queueStore.active.trackIds)
        assertEquals(1, queueStore.active.currentIndex)

        val reloadedQueues = QueueStore(stateDir)
        assertEquals(TrackSortOrder.CUSTOM, reloadedQueues.active.sortOrder)
        assertEquals(listOf(idBravo, idDelta, idAlpha, idCharlie), reloadedQueues.active.trackIds)
        assertEquals(1, reloadedQueues.active.currentIndex)
    }

    @Test
    fun newFolderAutoCreatesAndReplacesExactNameQueueWithoutDisruptingPlayback() = runBlocking {
        touch("existing.mp3")
        libraryStore.rescan()
        val existingId = libraryStore.state.value.tracks.single().id

        // Start playback on the default active queue.
        controller.play(listOf(existingId), startAt = 0)
        val activeBeforeId = queueStore.active.id
        val loadsBefore = engine.loadCalls

        // 1. Adding a picked folder "rock_songs" containing a subfolder "disc1" creates queue named "rock_songs".
        val parentDir = tempFolder.newFolder("music_parent")
        val rockDir = File(parentDir, "rock_songs").apply { mkdirs() }
        touch("disc1/r1.mp3", root = rockDir)
        touch("r2.mp3", root = rockDir)

        val addPicked = libraryStore.addLocalFolder(rockDir)
        assertTrue(addPicked.isSuccess)

        val rockQueue = queueStore.state.value.queues.firstOrNull { it.name == "rock_songs" }
        assertNotNull(rockQueue)
        assertEquals(2, rockQueue!!.trackIds.size)
        assertFalse(queueStore.state.value.queues.any { it.name == "music_parent" || it.name == "disc1" })
        // Active queue and playback were NOT disrupted.
        assertEquals(activeBeforeId, queueStore.active.id)
        assertEquals(loadsBefore, engine.loadCalls)

        // 2. Adding a picked root with trackedSubfolderName (simulating SaveCloudPlaylistDialog via SAF)
        // creates queue with the exact saved subfolder name, never the parent folder name.
        val safParent = tempFolder.newFolder("SafParent")
        touch("cloud_saved/c1.mp3", root = safParent)
        val cloudTrackRoot = com.musico.model.LibraryRoot(
            id = "cloud_src",
            name = "Cloud Src",
            kind = com.musico.model.RootKind.CLOUD_YOUTUBE,
            sourceUrl = "https://music.youtube.com/playlist?list=PL123",
            remoteId = "PL123",
        )
        val addSub = libraryStore.addLocalFolder(
            dir = safParent,
            cloudSourceRoot = cloudTrackRoot,
            trackedSubfolderName = "cloud_saved",
        )
        assertTrue(addSub.isSuccess)
        val cloudSavedQueue = queueStore.state.value.queues.firstOrNull { it.name == "cloud_saved" }
        assertNotNull(cloudSavedQueue)
        assertEquals(1, cloudSavedQueue!!.trackIds.size)
        assertFalse(queueStore.state.value.queues.any { it.name == "SafParent" })

        // 3. Replacing an existing non-active same-name queue replaces it without disrupting active playback.
        val oldRockQueueId = rockQueue.id
        controller.onFolderAdded("rock_songs", listOf(existingId))
        val replacedRockQueue = queueStore.state.value.queues.firstOrNull { it.name == "rock_songs" }!!
        assertTrue(replacedRockQueue.id != oldRockQueueId)
        assertEquals(listOf(existingId), replacedRockQueue.trackIds)
        assertEquals(activeBeforeId, queueStore.active.id)
        assertEquals(loadsBefore, engine.loadCalls)

        // 4. Playing a folder/subfolder uses its exact leaf folder name and replaces any same-name queue.
        controller.playInNamedQueue("rock_songs", rockQueue.trackIds, startAt = 1)
        assertEquals("rock_songs", queueStore.active.name)
        assertEquals(rockQueue.trackIds, queueStore.active.trackIds)
        assertEquals(1, queueStore.active.currentIndex)
        assertEquals(1, queueStore.state.value.queues.count { it.name == "rock_songs" })
    }

    @Test
    fun addingCloudFolderCreatesExactNamedQueue() = runBlocking {
        val fakeHttp = object : CloudHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>, followRedirects: Boolean): CloudHttpResponse =
                CloudHttpResponse(404, url, emptyMap(), ByteArray(0))

            override suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String>): CloudHttpResponse {
                val body = """
                    {
                      "header": { "musicDetailHeaderRenderer": { "title": { "runs": [ { "text": "Anime OST" } ] } } },
                      "contents": {
                        "items": [
                          {
                            "playlistVideoRenderer": {
                              "videoId": "vid_001",
                              "title": { "runs": [ { "text": "Opening" } ] },
                              "shortBylineText": { "runs": [ { "text": "Band" } ] },
                              "lengthSeconds": "90"
                            }
                          }
                        ]
                      }
                    }
                """.trimIndent()
                return CloudHttpResponse(200, url, emptyMap(), body.toByteArray())
            }

            override suspend fun probeRange(url: String, headers: Map<String, String>): Int = 206
        }

        val cloudLibrary = LibraryStore(
            directory = tempFolder.newFolder("c_state"),
            appFolderDir = tempFolder.newFolder("c_docs"),
            reader = reader,
            artwork = ArtworkCache(tempFolder.newFolder("c_art")),
            context = null,
            cloudHttpClient = fakeHttp,
            youTubeClient = YouTubeClient(fakeHttp),
            bilibiliClient = BilibiliClient(fakeHttp),
        )
        val cQueues = QueueStore(tempFolder.newFolder("c_q"))
        val cController = PlayerController(
            context = null,
            library = cloudLibrary,
            queues = cQueues,
            history = historyStore,
            engine = FakeAudioEngine(),
            settingsFile = File(stateDir, "c_settings.json"),
        )

        val result = cloudLibrary.addCloudRoot("https://music.youtube.com/playlist?list=PLanime001")
        assertTrue(result.isSuccess)
        val animeQueue = cQueues.state.value.queues.firstOrNull { it.name == "Anime OST" }
        assertNotNull(animeQueue)
        assertEquals(1, animeQueue!!.trackIds.size)
        cController.shutdown()
    }

    @Test
    fun audioFileInfoReaderExtractsFlacAndWavHeadersCleanly() {
        // Construct minimal FLAC header: "fLaC" + block header (last=1, type=0, len=34) + STREAMINFO(34 bytes)
        // Sample rate = 96000 (0x17700), channels = 2 (stored 1), bit depth = 24 (stored 23)
        // b10 = 0x17, b11 = 0x70, b12 = (0x0 shl 4) | (1 shl 1) | 1 = 0x03, b13 = (7 shl 4) = 0x70
        val flacHeader = ByteArray(4 + 4 + 34)
        flacHeader[0] = 'f'.code.toByte()
        flacHeader[1] = 'L'.code.toByte()
        flacHeader[2] = 'a'.code.toByte()
        flacHeader[3] = 'C'.code.toByte()
        flacHeader[4] = 0x80.toByte() // last=1, type=0
        flacHeader[7] = 34
        flacHeader[8 + 10] = 0x17
        flacHeader[8 + 11] = 0x70
        flacHeader[8 + 12] = 0x03
        flacHeader[8 + 13] = 0x70

        val flacInfo = AudioFileInfoReader.inspectHeader(ByteArrayInputStream(flacHeader))
        assertNotNull(flacInfo)
        assertEquals(24, flacInfo!!.bitDepth)
        assertEquals(96000, flacInfo.sampleRateHz)
        assertEquals(2, flacInfo.channelCount)

        // Construct minimal WAV header: RIFF....WAVE + fmt (16 bytes: PCM, 2 ch, 44100 Hz, 176400 B/s, 16-bit)
        val wav = ByteArray(44)
        "RIFF".toByteArray().copyInto(wav, 0)
        "WAVE".toByteArray().copyInto(wav, 8)
        "fmt ".toByteArray().copyInto(wav, 12)
        wav[16] = 16 // fmt size
        wav[20] = 1  // PCM
        wav[22] = 2  // 2 channels
        // 44100 = 0x0000AC44 LE
        wav[24] = 0x44
        wav[25] = 0xAC.toByte()
        // 176400 = 0x0002B110 LE
        wav[28] = 0x10
        wav[29] = 0xB1.toByte()
        wav[30] = 0x02
        // 16 bits per sample
        wav[34] = 16

        val wavInfo = AudioFileInfoReader.inspectHeader(ByteArrayInputStream(wav))
        assertNotNull(wavInfo)
        assertEquals(16, wavInfo!!.bitDepth)
        assertEquals(44100, wavInfo.sampleRateHz)
        assertEquals(2, wavInfo.channelCount)
        assertEquals(176400L * 8L, wavInfo.bitRateBps)
    }
}
