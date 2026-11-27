package com.musico.cloud

import com.musico.extensions.CloudProviderIds
import com.musico.extensions.CloudProviderRegistry
import com.musico.extensions.CloudSourceProvider
import com.musico.model.LibraryRoot
import com.musico.model.Track
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

open class CloudStreamResolver(
    private val providerRegistry: CloudProviderRegistry,
    val audioCache: CloudAudioCache? = null,
) {
    constructor() : this(CloudProviderRegistry(), null)

    constructor(
        googleDriveClient: GoogleDriveClient,
        audioCache: CloudAudioCache? = null,
    ) : this(CloudProviderRegistry(googleDriveClient), audioCache)

    private val cache = ConcurrentHashMap<String, ResolvedStream>()

    private fun cacheKey(
        providerId: String,
        track: Track,
        cookie: String?,
        qualityId: String?,
        purpose: CloudStreamPurpose,
    ): String {
        val cookieTag = cookie?.hashCode() ?: 0
        val qualityTag = qualityId?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_QUALITY_CACHE_TAG
        return "$providerId:${track.remoteTrackId}:$cookieTag:$qualityTag:${purpose.name}"
    }

    open fun providerFor(track: Track, root: LibraryRoot?): CloudSourceProvider? =
        providerRegistry.providerForTrack(track, root)

    open fun effectiveQualityId(
        track: Track,
        root: LibraryRoot?,
        purpose: CloudStreamPurpose = CloudStreamPurpose.STREAM,
        requestedQualityId: String? = null,
    ): String? {
        val provider = providerFor(track, root) ?: return null
        return resolveEffectiveQuality(provider, root, purpose, requestedQualityId)
    }

    private fun resolveEffectiveQuality(
        provider: CloudSourceProvider,
        root: LibraryRoot?,
        purpose: CloudStreamPurpose,
        requestedQualityId: String?,
    ): String? {
        val available = provider.availableQualities
        if (available.isEmpty()) return null
        val validIds = available.mapTo(HashSet()) { it.id }
        fun cleanIfValid(raw: String?): String? =
            raw?.trim()?.takeIf { it.isNotEmpty() && it in validIds }

        val extPrefs = providerRegistry.extensionQualityFor(provider.providerId)
        return when (purpose) {
            CloudStreamPurpose.DOWNLOAD ->
                cleanIfValid(requestedQualityId)
                    ?: cleanIfValid(root?.downloadQualityId)
                    ?: cleanIfValid(extPrefs?.second)
                    ?: cleanIfValid(provider.defaultDownloadQualityId)
                    ?: available.first().id
            CloudStreamPurpose.STREAM ->
                cleanIfValid(requestedQualityId)
                    ?: cleanIfValid(root?.streamQualityId)
                    ?: cleanIfValid(extPrefs?.first)
                    ?: cleanIfValid(provider.defaultStreamQualityId)
                    ?: available.first().id
        }
    }

    open suspend fun resolve(
        track: Track,
        root: LibraryRoot?,
        forceRefresh: Boolean = false,
    ): ResolvedStream = resolveInternal(
        track = track,
        root = root,
        forceRefresh = forceRefresh,
        purpose = CloudStreamPurpose.STREAM,
        requestedQualityId = null,
    )

    open suspend fun resolve(
        track: Track,
        root: LibraryRoot?,
        forceRefresh: Boolean,
        purpose: CloudStreamPurpose,
        requestedQualityId: String? = null,
    ): ResolvedStream = resolveInternal(
        track = track,
        root = root,
        forceRefresh = forceRefresh,
        purpose = purpose,
        requestedQualityId = requestedQualityId,
    )

    private suspend fun resolveInternal(
        track: Track,
        root: LibraryRoot?,
        forceRefresh: Boolean,
        purpose: CloudStreamPurpose,
        requestedQualityId: String?,
    ): ResolvedStream {
        val source = track.cloudSource
            ?: Track.extensionIdFromEphemeralId(track.id)?.let { CloudProviderIds.extensionProviderId(it) }
            ?: throw IOException("Not a cloud track: ${track.title}")
        val remoteId = track.remoteTrackId ?: throw IOException("Missing remote ID for ${track.title}")
        val provider = providerRegistry.providerForTrack(track, root)
            ?: run {
                val pid = root?.resolvedProviderId ?: CloudProviderIds.fromLegacySlug(source)
                val extPid = CloudProviderIds.legacyExtensionProviderId(pid) ?: pid
                if (CloudProviderIds.extensionIdFromProviderId(extPid) != null) {
                    throw CloudSourceException(
                        CloudErrorCode.EXTENSION_UNAVAILABLE,
                        "Extension unavailable for ${track.title} ($extPid)",
                    )
                }
                throw IOException("Unsupported cloud source: $source")
            }

        val cookie = root?.cookie?.trim()?.takeIf { it.isNotEmpty() }
            ?: providerRegistry.extensionCookieFor(provider.providerId)
        val effectiveQuality = resolveEffectiveQuality(provider, root, purpose, requestedQualityId)
        val key = cacheKey(provider.providerId, track, cookie, effectiveQuality, purpose)

        if (!forceRefresh) {
            val cached = cache[key]
            if (cached != null && System.currentTimeMillis() < cached.expiresAtEpochMs) {
                return cached
            }
        }

        val resolved = provider.resolveStream(
            remoteTrackId = remoteId,
            relativePath = track.relativePath,
            cookie = cookie,
            forceRefresh = forceRefresh,
            quality = effectiveQuality,
            purpose = purpose,
        )
        cache[key] = resolved
        return resolved
    }

    open fun invalidate(track: Track, root: LibraryRoot? = null) {
        val providerId = providerRegistry.providerForTrack(track, root)?.providerId
            ?: CloudProviderIds.fromLegacySlug(track.cloudSource)
            ?: Track.extensionIdFromEphemeralId(track.id)?.let { CloudProviderIds.extensionProviderId(it) }
            ?: track.cloudSource
        if (root != null) {
            val effectiveCookie = root.cookie?.trim()?.takeIf { it.isNotEmpty() }
                ?: providerId?.let { providerRegistry.extensionCookieFor(it) }
            val prefixes = buildList {
                if (providerId != null) {
                    add("$providerId:${track.remoteTrackId}:${effectiveCookie?.hashCode() ?: 0}:")
                    add("$providerId:${track.remoteTrackId}:${root.cookie?.hashCode() ?: 0}:")
                }
                track.cloudSource?.let { src ->
                    add("$src:${track.remoteTrackId}:${effectiveCookie?.hashCode() ?: 0}:")
                    add("$src:${track.remoteTrackId}:${root.cookie?.hashCode() ?: 0}:")
                }
            }.distinct()
            cache.keys.filter { key -> prefixes.any { key.startsWith(it) } }.forEach { cache.remove(it) }
        } else {
            val prefixes = listOfNotNull(providerId, track.cloudSource).distinct()
                .map { "$it:${track.remoteTrackId}:" }
            cache.keys.filter { key -> prefixes.any { key.startsWith(it) } }.forEach { cache.remove(it) }
        }
    }

    suspend fun prefetch(
        track: Track,
        root: LibraryRoot?,
        requestedQualityId: String? = null,
    ) {
        if (!track.isCloud) return
        runCatching {
            resolve(
                track = track,
                root = root,
                forceRefresh = false,
                purpose = CloudStreamPurpose.STREAM,
                requestedQualityId = requestedQualityId,
            )
        }
    }

    companion object {
        private const val DEFAULT_QUALITY_CACHE_TAG = "_default"
    }
}
