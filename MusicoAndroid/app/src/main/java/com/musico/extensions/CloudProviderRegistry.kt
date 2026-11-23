package com.musico.extensions

import com.musico.cloud.BilibiliClient
import com.musico.cloud.CloudErrorCode
import com.musico.cloud.CloudPlaylistResult
import com.musico.cloud.CloudSource
import com.musico.cloud.CloudSourceException
import com.musico.cloud.CloudTrackMetadata
import com.musico.cloud.DriveCrawlBatch
import com.musico.cloud.GoogleDriveClient
import com.musico.cloud.ResolvedStream
import com.musico.cloud.YouTubeClient
import com.musico.cloud.extractCloudSourceInput
import com.musico.model.CloudTrackingInfo
import com.musico.model.LibraryRoot
import com.musico.model.RootKind
import com.musico.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

object CloudProviderIds {
    const val YOUTUBE = "builtin.youtube"
    const val BILIBILI = "builtin.bilibili"
    const val GOOGLE_DRIVE = "builtin.gdrive"

    fun extensionProviderId(extensionId: String): String =
        if (extensionId.startsWith("ext.")) extensionId else "ext.$extensionId"

    fun extensionIdFromProviderId(providerId: String?): String? =
        providerId?.takeIf { it.startsWith("ext.") }?.removePrefix("ext.")?.takeIf { it.isNotEmpty() }

    fun isBuiltin(providerId: String?): Boolean =
        providerId == YOUTUBE || providerId == BILIBILI || providerId == GOOGLE_DRIVE

    fun fromLegacySlug(slug: String?): String? = when (slug?.trim()?.lowercase()) {
        YOUTUBE, CloudSource.YOUTUBE, "cloud_youtube" -> YOUTUBE
        BILIBILI, CloudSource.BILIBILI, "cloud_bilibili" -> BILIBILI
        GOOGLE_DRIVE, CloudSource.GOOGLE_DRIVE, "google_drive", "cloud_google_drive" -> GOOGLE_DRIVE
        else -> slug?.trim()?.takeIf { it.startsWith("ext.") }
    }
}

enum class CloudProviderAvailability {
    AVAILABLE,
    DISABLED,
    MISSING,
}

data class CloudUrlMatch(
    val normalizedUrl: String,
    val remoteId: String? = null,
    val kind: String? = null,
)

data class CloudTreeBatch(
    val rootRemoteId: String,
    val rootTitle: String,
    val batchTracks: List<CloudTrackMetadata>,
    val knownFolderPaths: Set<String>,
    val completedFolders: Int,
    val totalDiscoveredFolders: Int,
    val tracksSoFar: Int,
    val isComplete: Boolean,
)

data class ClassifiedCloudUrl(
    val provider: CloudSourceProvider,
    val normalizedUrl: String,
    val remoteId: String? = null,
    val kind: String? = null,
)

interface CloudSourceProvider {
    val providerId: String
    val rootKind: RootKind
    val cloudSourceSlug: String
    val displayName: String
    val badgeLabel: String
    val isBuiltin: Boolean
    val supportsTree: Boolean
    val supportsCookie: Boolean
    val usesFlatCloudListing: Boolean

    fun canHandle(rawInput: String): CloudUrlMatch?

    fun matchesUrlPatternFast(rawInput: String): Boolean = canHandle(rawInput) != null

    suspend fun fetchCollection(sourceUrl: String, cookie: String? = null): CloudPlaylistResult

    suspend fun resolveStream(
        remoteTrackId: String,
        relativePath: String? = null,
        cookie: String? = null,
        forceRefresh: Boolean = false,
    ): ResolvedStream

    suspend fun crawlProgressive(
        sourceUrl: String,
        cookie: String? = null,
        priorityPathProvider: () -> String? = { null },
        onBatch: suspend (CloudTreeBatch) -> Unit,
    ): CloudPlaylistResult {
        val result = fetchCollection(sourceUrl, cookie)
        onBatch(
            CloudTreeBatch(
                rootRemoteId = result.remoteId,
                rootTitle = result.title,
                batchTracks = result.tracks,
                knownFolderPaths = emptySet(),
                completedFolders = 1,
                totalDiscoveredFolders = 1,
                tracksSoFar = result.tracks.size,
                isComplete = true,
            )
        )
        return result
    }

    fun artworkHeaders(): Map<String, String> = emptyMap()

    fun downloadHeaders(stream: ResolvedStream): Map<String, String> = stream.headers
}

class YouTubeSourceProvider(
    private val client: YouTubeClient,
) : CloudSourceProvider {
    override val providerId: String = CloudProviderIds.YOUTUBE
    override val rootKind: RootKind = RootKind.CLOUD_YOUTUBE
    override val cloudSourceSlug: String = CloudSource.YOUTUBE
    override val displayName: String = "YouTube"
    override val badgeLabel: String = "YouTube"
    override val isBuiltin: Boolean = true
    override val supportsTree: Boolean = false
    override val supportsCookie: Boolean = true
    override val usesFlatCloudListing: Boolean = true

    override fun canHandle(rawInput: String): CloudUrlMatch? {
        val clean = extractCloudSourceInput(rawInput)
        if (clean.isEmpty() || !YouTubeClient.canHandle(clean)) return null
        return CloudUrlMatch(normalizedUrl = clean)
    }

    override suspend fun fetchCollection(sourceUrl: String, cookie: String?): CloudPlaylistResult =
        client.fetchPlaylist(sourceUrl, cookie)

    override suspend fun resolveStream(
        remoteTrackId: String,
        relativePath: String?,
        cookie: String?,
        forceRefresh: Boolean,
    ): ResolvedStream = client.resolveStream(remoteTrackId, cookie)

    override fun downloadHeaders(stream: ResolvedStream): Map<String, String> {
        val hasRange = stream.headers.keys.any { it.equals("Range", ignoreCase = true) }
        return if (hasRange) stream.headers else stream.headers + ("Range" to "bytes=0-")
    }
}

class BilibiliSourceProvider(
    private val client: BilibiliClient,
) : CloudSourceProvider {
    override val providerId: String = CloudProviderIds.BILIBILI
    override val rootKind: RootKind = RootKind.CLOUD_BILIBILI
    override val cloudSourceSlug: String = CloudSource.BILIBILI
    override val displayName: String = "Bilibili"
    override val badgeLabel: String = "Bilibili"
    override val isBuiltin: Boolean = true
    override val supportsTree: Boolean = false
    override val supportsCookie: Boolean = true
    override val usesFlatCloudListing: Boolean = true

    override fun canHandle(rawInput: String): CloudUrlMatch? {
        val clean = extractCloudSourceInput(rawInput)
        if (clean.isEmpty() || !BilibiliClient.canHandle(clean)) return null
        return CloudUrlMatch(normalizedUrl = clean)
    }

    override suspend fun fetchCollection(sourceUrl: String, cookie: String?): CloudPlaylistResult =
        client.fetchPlaylist(sourceUrl, cookie)

    override suspend fun resolveStream(
        remoteTrackId: String,
        relativePath: String?,
        cookie: String?,
        forceRefresh: Boolean,
    ): ResolvedStream = client.resolveStream(remoteTrackId, cookie)

    override fun artworkHeaders(): Map<String, String> = mapOf(
        "Referer" to "https://www.bilibili.com/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    )
}

class GoogleDriveSourceProvider(
    private val client: GoogleDriveClient,
) : CloudSourceProvider {
    override val providerId: String = CloudProviderIds.GOOGLE_DRIVE
    override val rootKind: RootKind = RootKind.CLOUD_GOOGLE_DRIVE
    override val cloudSourceSlug: String = CloudSource.GOOGLE_DRIVE
    override val displayName: String = "Google Drive"
    override val badgeLabel: String = "Google Drive"
    override val isBuiltin: Boolean = true
    override val supportsTree: Boolean = true
    override val supportsCookie: Boolean = true
    override val usesFlatCloudListing: Boolean = false

    override fun canHandle(rawInput: String): CloudUrlMatch? {
        val clean = extractCloudSourceInput(rawInput)
        if (clean.isEmpty() || !GoogleDriveClient.canHandle(clean)) return null
        return CloudUrlMatch(normalizedUrl = clean)
    }

    override suspend fun fetchCollection(sourceUrl: String, cookie: String?): CloudPlaylistResult =
        client.fetchPlaylist(sourceUrl, cookie)

    override suspend fun resolveStream(
        remoteTrackId: String,
        relativePath: String?,
        cookie: String?,
        forceRefresh: Boolean,
    ): ResolvedStream = client.resolveStream(remoteTrackId, cookie)

    override suspend fun crawlProgressive(
        sourceUrl: String,
        cookie: String?,
        priorityPathProvider: () -> String?,
        onBatch: suspend (CloudTreeBatch) -> Unit,
    ): CloudPlaylistResult {
        return client.crawlProgressive(
            sourceUrl = sourceUrl,
            cookie = cookie,
            priorityPathProvider = priorityPathProvider,
        ) { batch: DriveCrawlBatch ->
            onBatch(
                CloudTreeBatch(
                    rootRemoteId = batch.rootRemoteId,
                    rootTitle = batch.rootTitle,
                    batchTracks = batch.batchTracks,
                    knownFolderPaths = batch.knownFolderPaths,
                    completedFolders = batch.progress.completedFolders,
                    totalDiscoveredFolders = batch.progress.totalDiscoveredFolders,
                    tracksSoFar = batch.progress.tracksSoFar,
                    isComplete = batch.progress.isComplete,
                )
            )
        }
    }
}

class CloudProviderRegistry(
    youTubeClient: YouTubeClient = YouTubeClient(),
    bilibiliClient: BilibiliClient = BilibiliClient(),
    googleDriveClient: GoogleDriveClient = GoogleDriveClient(),
) {
    private val youtubeProvider = YouTubeSourceProvider(youTubeClient)
    private val bilibiliProvider = BilibiliSourceProvider(bilibiliClient)
    private val googleDriveProvider = GoogleDriveSourceProvider(googleDriveClient)

    private val builtinsById: Map<String, CloudSourceProvider> = linkedMapOf(
        CloudProviderIds.YOUTUBE to youtubeProvider,
        CloudProviderIds.BILIBILI to bilibiliProvider,
        CloudProviderIds.GOOGLE_DRIVE to googleDriveProvider,
    )

    private val extensionProvidersMap = ConcurrentHashMap<String, CloudSourceProvider>()

    @Volatile
    var extensionStatusLookup: ((String) -> CloudProviderAvailability)? = null

    private val _providers = MutableStateFlow<List<CloudSourceProvider>>(builtinsById.values.toList())
    val providers: StateFlow<List<CloudSourceProvider>> = _providers.asStateFlow()

    fun builtinProviders(): List<CloudSourceProvider> = builtinsById.values.toList()

    fun activeExtensionProviders(): List<CloudSourceProvider> =
        extensionProvidersMap.values.sortedBy { it.displayName.lowercase() }

    fun setExtensionProviders(providers: List<CloudSourceProvider>) {
        extensionProvidersMap.clear()
        for (provider in providers) {
            extensionProvidersMap[provider.providerId] = provider
        }
        publishProviders()
    }

    fun registerExtensionProvider(provider: CloudSourceProvider) {
        extensionProvidersMap[provider.providerId] = provider
        publishProviders()
    }

    fun unregisterExtensionProvider(providerId: String) {
        extensionProvidersMap.remove(providerId)
        publishProviders()
    }

    private fun publishProviders() {
        _providers.value = builtinsById.values.toList() + activeExtensionProviders()
    }

    fun availabilityFor(providerId: String?): CloudProviderAvailability {
        if (providerId.isNullOrBlank()) return CloudProviderAvailability.MISSING
        if (providerId in builtinsById) return CloudProviderAvailability.AVAILABLE
        if (extensionProvidersMap.containsKey(providerId)) return CloudProviderAvailability.AVAILABLE
        return extensionStatusLookup?.invoke(providerId) ?: CloudProviderAvailability.MISSING
    }

    fun providerForId(providerId: String?): CloudSourceProvider? {
        val resolved = CloudProviderIds.fromLegacySlug(providerId) ?: providerId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return builtinsById[resolved] ?: extensionProvidersMap[resolved]
    }

    fun providerForKind(kind: RootKind?, providerId: String? = null): CloudSourceProvider? {
        providerForId(providerId)?.let { return it }
        val builtinId = kind?.builtinProviderId ?: return null
        return builtinsById[builtinId]
    }

    fun providerForRoot(root: LibraryRoot?): CloudSourceProvider? {
        if (root == null || !root.kind.isCloud) return null
        return providerForKind(root.kind, root.resolvedProviderId)
    }

    fun providerForTracking(tracking: CloudTrackingInfo?): CloudSourceProvider? {
        if (tracking == null) return null
        return providerForKind(tracking.cloudKind, tracking.resolvedProviderId)
    }

    fun providerForTrack(track: Track, root: LibraryRoot? = null): CloudSourceProvider? {
        val rootPid = root?.resolvedProviderId
        if (rootPid != null) {
            providerForId(rootPid)?.let { return it }
            if (root.kind == RootKind.CLOUD_EXTENSION) return null
        }
        val trackPid = CloudProviderIds.fromLegacySlug(track.cloudSource)
        return providerForId(trackPid) ?: root?.let { providerForKind(it.kind, it.resolvedProviderId) }
    }

    /**
     * Fast UI preview of which provider matches [rawInput] without invoking QuickJS hooks for extensions.
     */
    fun previewProvider(rawInput: String): CloudSourceProvider? {
        val clean = extractCloudSourceInput(rawInput)
        if (clean.isEmpty()) return null
        classifyBuiltin(clean)?.let { return it.provider }
        return activeExtensionProviders().firstOrNull { it.matchesUrlPatternFast(rawInput) }
    }

    fun classifyUrl(rawInput: String, providerIdHint: String? = null): ClassifiedCloudUrl? {
        val clean = extractCloudSourceInput(rawInput)
        if (clean.isEmpty()) return null

        if (!providerIdHint.isNullOrBlank()) {
            val provider = providerForId(providerIdHint)
                ?: throw CloudSourceException(
                    CloudErrorCode.EXTENSION_UNAVAILABLE,
                    "Cloud provider unavailable: $providerIdHint",
                )
            val match = provider.canHandle(rawInput) ?: return null
            return ClassifiedCloudUrl(
                provider = provider,
                normalizedUrl = match.normalizedUrl,
                remoteId = match.remoteId,
                kind = match.kind,
            )
        }

        classifyBuiltin(clean)?.let { return it }

        for (extProvider in activeExtensionProviders()) {
            val match = extProvider.canHandle(rawInput)
            if (match != null) {
                return ClassifiedCloudUrl(
                    provider = extProvider,
                    normalizedUrl = match.normalizedUrl,
                    remoteId = match.remoteId,
                    kind = match.kind,
                )
            }
        }
        return null
    }

    private fun classifyBuiltin(cleanUrl: String): ClassifiedCloudUrl? {
        val driveMatch = googleDriveProvider.canHandle(cleanUrl)
        val biliMatch = bilibiliProvider.canHandle(cleanUrl)
        val ytMatch = youtubeProvider.canHandle(cleanUrl)

        return when {
            driveMatch != null -> ClassifiedCloudUrl(googleDriveProvider, driveMatch.normalizedUrl)
            biliMatch != null && ytMatch == null -> ClassifiedCloudUrl(bilibiliProvider, biliMatch.normalizedUrl)
            ytMatch != null && biliMatch == null -> ClassifiedCloudUrl(youtubeProvider, ytMatch.normalizedUrl)
            biliMatch != null && ytMatch != null -> {
                if (cleanUrl.contains("bilibili", ignoreCase = true) || cleanUrl.contains("b23.tv", ignoreCase = true)) {
                    ClassifiedCloudUrl(bilibiliProvider, biliMatch.normalizedUrl)
                } else {
                    ClassifiedCloudUrl(youtubeProvider, ytMatch.normalizedUrl)
                }
            }
            else -> null
        }
    }
}
