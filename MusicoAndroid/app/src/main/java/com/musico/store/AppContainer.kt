package com.musico.store

import android.content.Context
import android.os.Environment
import com.musico.audio.AudioEngine
import com.musico.audio.PlayerController
import com.musico.cloud.BilibiliClient
import com.musico.cloud.CloudExporter
import com.musico.cloud.CloudHttpClient
import com.musico.cloud.CloudStreamResolver
import com.musico.cloud.GoogleDriveClient
import com.musico.cloud.UrlConnectionCloudHttpClient
import com.musico.cloud.YouTubeClient
import com.musico.core.AndroidMetadataReader
import com.musico.core.ArtworkCache
import com.musico.core.CloudThumbnailCache
import com.musico.core.LyricsStore
import com.musico.core.MetadataReading
import com.musico.extensions.CloudProviderRegistry
import com.musico.extensions.ExtensionStore
import com.musico.i18n.LanguageStore
import com.musico.update.UpdateManager
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
    val cloudHttpClient: CloudHttpClient = UrlConnectionCloudHttpClient()
    val cloudThumbnailCache: CloudThumbnailCache = CloudThumbnailCache(cloudHttpClient)
    val youTubeClient: YouTubeClient = YouTubeClient(cloudHttpClient)
    val bilibiliClient: BilibiliClient = BilibiliClient(cloudHttpClient)
    val googleDriveClient: GoogleDriveClient = GoogleDriveClient(cloudHttpClient)
    val cloudProviderRegistry: CloudProviderRegistry = CloudProviderRegistry(
        youTubeClient = youTubeClient,
        bilibiliClient = bilibiliClient,
        googleDriveClient = googleDriveClient,
    )
    val extensionStore: ExtensionStore = ExtensionStore(
        stateDir = stateDir,
        httpClient = cloudHttpClient,
        providerRegistry = cloudProviderRegistry,
    )
    val cloudStreamResolver: CloudStreamResolver = CloudStreamResolver(cloudProviderRegistry)
    val cloudExporter: CloudExporter = CloudExporter(
        context = appContext,
        cloudResolver = cloudStreamResolver,
        providerRegistry = cloudProviderRegistry,
        artworkCache = artworkCache,
        thumbnailCache = cloudThumbnailCache,
        httpClient = cloudHttpClient,
    )
    val libraryStore: LibraryStore = LibraryStore(
        directory = stateDir,
        appFolderDir = appMusicDir,
        reader = metadataReader,
        artwork = artworkCache,
        context = appContext,
        cloudHttpClient = cloudHttpClient,
        youTubeClient = youTubeClient,
        bilibiliClient = bilibiliClient,
        googleDriveClient = googleDriveClient,
        providerRegistry = cloudProviderRegistry,
    ).also { store ->
        extensionStore.onAvailabilityChanged = {
            store.refreshAvailability()
        }
    }
    val queueStore: QueueStore = QueueStore(stateDir).also { qStore ->
        libraryStore.referencedQueueTrackIdsProvider = { qStore.allReferencedTrackIds() }
        qStore.onReferencedTrackIdsChanged = { ids -> libraryStore.pruneEphemeralTracks(ids) }
        libraryStore.pruneEphemeralTracks(qStore.allReferencedTrackIds())
    }
    val historyStore: PlayHistoryStore = PlayHistoryStore(stateDir)
    val lyricsStore: LyricsStore = LyricsStore(File(stateDir, "lyrics"))
    val cloudLyricsService: com.musico.lyrics.CloudLyricsService = com.musico.lyrics.CloudLyricsService(
        httpClient = cloudHttpClient,
        extensionSearch = { extId, query -> extensionStore.searchLyrics(extId, query) },
    )
    val languageStore: LanguageStore = LanguageStore(appContext)
    val updateManager: UpdateManager = UpdateManager(appContext)
    val audioEngine: AudioEngine = AudioEngine(appContext)
    val playerController: PlayerController = PlayerController(
        context = appContext,
        library = libraryStore,
        queues = queueStore,
        history = historyStore,
        engine = audioEngine,
        settingsFile = File(stateDir, "settings.json"),
        cloudResolver = cloudStreamResolver,
    )
    val cloudDownloadManager: CloudDownloadManager = CloudDownloadManager(
        libraryStore = libraryStore,
        cloudExporter = cloudExporter,
        onFolderSavedWithoutLibrary = { folderName ->
            playerController.onFolderAdded(folderName, emptyList())
        },
    )
}
