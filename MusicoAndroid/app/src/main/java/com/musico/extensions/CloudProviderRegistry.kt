package com.musico.extensions

import com.musico.cloud.CloudErrorCode
import com.musico.cloud.CloudPlaylistResult
import com.musico.cloud.CloudQualityOption
import com.musico.cloud.CloudSource
import com.musico.cloud.CloudSourceException
import com.musico.cloud.CloudStreamPurpose
import com.musico.cloud.CloudTrackMetadata
import com.musico.cloud.DriveCrawlBatch
import com.musico.cloud.GoogleDriveClient
import com.musico.cloud.ResolvedStream
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
    const val YOUTUBE_EXTENSION = "ext.youtube-search"
    const val BILIBILI_EXTENSION = "ext.bilibili-search"

    fun extensionProviderId(extensionId: String): String =
        "ext.$extensionId"

    fun extensionIdFromProviderId(providerId: String?): String? =
        providerId?.takeIf { it.startsWith("ext.") }?.removePrefix("ext.")?.takeIf { it.isNotEmpty() }

    fun isBuiltin(providerId: String?): Boolean =
        providerId == YOUTUBE || providerId == BILIBILI || providerId == GOOGLE_DRIVE

    fun legacyExtensionProviderId(providerId: String?): String? = when (fromLegacySlug(providerId)) {
        YOUTUBE -> YOUTUBE_EXTENSION
        BILIBILI -> BILIBILI_EXTENSION
        else -> null
    }

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
    val supportsCollections: Boolean
        get() = true
    val supportsStream: Boolean
        get() = true
    val availableQualities: List<CloudQualityOption>
        get() = emptyList()
    val defaultStreamQualityId: String?
        get() = availableQualities.firstOrNull()?.id
    val defaultDownloadQualityId: String?
        get() = availableQualities.firstOrNull()?.id

    fun canHandle(rawInput: String): CloudUrlMatch?

    fun matchesUrlPatternFast(rawInput: String): Boolean = canHandle(rawInput) != null

    suspend fun fetchCollection(sourceUrl: String, cookie: String? = null): CloudPlaylistResult

    suspend fun resolveStream(
        remoteTrackId: String,
        relativePath: String? = null,
        cookie: String? = null,
        forceRefresh: Boolean = false,
        quality: String? = null,
        purpose: CloudStreamPurpose = CloudStreamPurpose.STREAM,
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
        quality: String?,
        purpose: CloudStreamPurpose,
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
    googleDriveClient: GoogleDriveClient = GoogleDriveClient(),
) {
    private val googleDriveProvider = GoogleDriveSourceProvider(googleDriveClient)

    private val builtinsById: Map<String, CloudSourceProvider> = linkedMapOf(
        CloudProviderIds.GOOGLE_DRIVE to googleDriveProvider,
    )

    private val extensionProvidersMap = ConcurrentHashMap<String, CloudSourceProvider>()

    @Volatile
    var extensionStatusLookup: ((String) -> CloudProviderAvailability)? = null

    @Volatile
    var extensionCookieLookup: ((String) -> String?)? = null

    @Volatile
    var extensionQualityLookup: ((String) -> Pair<String?, String?>?)? = null

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
        val resolved = CloudProviderIds.fromLegacySlug(providerId) ?: providerId.trim()
        if (resolved in builtinsById) return CloudProviderAvailability.AVAILABLE
        if (extensionProvidersMap.containsKey(resolved)) return CloudProviderAvailability.AVAILABLE
        CloudProviderIds.legacyExtensionProviderId(resolved)?.let { legacyExtPid ->
            return if (extensionProvidersMap.containsKey(legacyExtPid)) {
                CloudProviderAvailability.AVAILABLE
            } else {
                CloudProviderAvailability.MISSING
            }
        }
        return extensionStatusLookup?.invoke(resolved) ?: CloudProviderAvailability.MISSING
    }

    fun extensionCookieFor(providerId: String?): String? {
        val pid = providerId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val targetPid = CloudProviderIds.legacyExtensionProviderId(pid) ?: pid
        if (CloudProviderIds.isBuiltin(targetPid)) return null
        return extensionCookieLookup?.invoke(targetPid)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun extensionQualityFor(providerId: String?): Pair<String?, String?>? {
        val pid = providerId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val targetPid = CloudProviderIds.legacyExtensionProviderId(pid) ?: pid
        if (CloudProviderIds.isBuiltin(targetPid)) return null
        return extensionQualityLookup?.invoke(targetPid)
    }

    fun compatibleProvidersForUrl(
        rawInput: String?,
        excludingProviderId: String? = null,
    ): List<CloudSourceProvider> {
        val raw = rawInput?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        if (extractCloudSourceInput(raw).isEmpty()) return emptyList()
        return _providers.value.filter { provider ->
            provider.supportsCollections &&
                provider.providerId != excludingProviderId &&
                provider.matchesUrlPatternFast(raw)
        }
    }

    fun bestCompatibleProviderForUrl(
        rawInput: String?,
        excludingProviderId: String? = null,
    ): CloudSourceProvider? = compatibleProvidersForUrl(rawInput, excludingProviderId).firstOrNull()

    fun providerForId(providerId: String?): CloudSourceProvider? {
        val resolved = CloudProviderIds.fromLegacySlug(providerId) ?: providerId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        builtinsById[resolved]?.let { return it }
        extensionProvidersMap[resolved]?.let { return it }
        CloudProviderIds.legacyExtensionProviderId(resolved)?.let { legacyExtPid ->
            extensionProvidersMap[legacyExtPid]?.let { return it }
        }
        return null
    }

    fun providerForKind(kind: RootKind?, providerId: String? = null): CloudSourceProvider? {
        providerForId(providerId)?.let { return it }
        val builtinId = kind?.builtinProviderId ?: return null
        return providerForId(builtinId)
    }

    fun providerForRoot(root: LibraryRoot?): CloudSourceProvider? {
        if (root == null || !root.kind.isCloud) return null
        providerForKind(root.kind, root.resolvedProviderId)?.let { return it }
        if (root.kind == RootKind.CLOUD_YOUTUBE || root.kind == RootKind.CLOUD_BILIBILI) {
            val url = root.sourceUrl?.trim()?.takeIf { it.isNotEmpty() }
                ?: root.remoteId?.trim()?.takeIf { it.isNotEmpty() }
            if (url != null) {
                bestCompatibleProviderForUrl(url)?.let { return it }
            }
        }
        return null
    }

    fun providerForTracking(tracking: CloudTrackingInfo?): CloudSourceProvider? {
        if (tracking == null) return null
        providerForKind(tracking.cloudKind, tracking.resolvedProviderId)?.let { return it }
        if (tracking.cloudKind == RootKind.CLOUD_YOUTUBE || tracking.cloudKind == RootKind.CLOUD_BILIBILI) {
            val url = tracking.sourceUrl.trim().takeIf { it.isNotEmpty() }
                ?: tracking.remoteId?.trim()?.takeIf { it.isNotEmpty() }
            if (url != null) {
                bestCompatibleProviderForUrl(url)?.let { return it }
            }
        }
        return null
    }

    fun providerForTrack(track: Track, root: LibraryRoot? = null): CloudSourceProvider? {
        val rootPid = root?.resolvedProviderId
        if (rootPid != null) {
            providerForId(rootPid)?.let { return it }
            if (root.kind == RootKind.CLOUD_EXTENSION) return null
        }
        val trackPid = CloudProviderIds.fromLegacySlug(track.cloudSource)
            ?: Track.extensionIdFromEphemeralId(track.id)?.let { CloudProviderIds.extensionProviderId(it) }
        providerForId(trackPid)?.let { return it }
        return root?.let { providerForRoot(it) }
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
        val driveMatch = googleDriveProvider.canHandle(cleanUrl) ?: return null
        return ClassifiedCloudUrl(googleDriveProvider, driveMatch.normalizedUrl)
    }
}
