package com.musico.store

import android.content.Context
import android.os.Environment
import com.musico.audio.AudioEngine
import com.musico.audio.PlayerController
import com.musico.core.AndroidMetadataReader
import com.musico.core.ArtworkCache
import com.musico.core.LyricsStore
import com.musico.core.MetadataReading
import java.io.File

interface AppContainerProvider {
    val appContainer: AppContainer
}

class AppContainer(context: Context) {
    private val appContext: Context = context.applicationContext

    val stateDir: File = File(appContext.filesDir, "musico").apply { mkdirs() }
    val appMusicDir: File = (
        appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: File(appContext.filesDir, "Music")
        ).apply { mkdirs() }

    val artworkCache: ArtworkCache = ArtworkCache(File(stateDir, "artwork"))
    val metadataReader: MetadataReading = AndroidMetadataReader(appContext)
    val libraryStore: LibraryStore = LibraryStore(
        directory = stateDir,
        appFolderDir = appMusicDir,
        reader = metadataReader,
        artwork = artworkCache,
        context = appContext,
    )
    val queueStore: QueueStore = QueueStore(stateDir)
    val historyStore: PlayHistoryStore = PlayHistoryStore(stateDir)
    val lyricsStore: LyricsStore = LyricsStore(File(stateDir, "lyrics"))
    val audioEngine: AudioEngine = AudioEngine(appContext)
    val playerController: PlayerController = PlayerController(
        context = appContext,
        library = libraryStore,
        queues = queueStore,
        history = historyStore,
        engine = audioEngine,
        settingsFile = File(stateDir, "settings.json"),
    )
}
