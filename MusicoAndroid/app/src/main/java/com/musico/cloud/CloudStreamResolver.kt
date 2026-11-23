package com.musico.cloud

import com.musico.extensions.CloudProviderIds
import com.musico.extensions.CloudProviderRegistry
import com.musico.model.LibraryRoot
import com.musico.model.Track
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

open class CloudStreamResolver(
    private val providerRegistry: CloudProviderRegistry,
) {
    constructor() : this(CloudProviderRegistry())

    constructor(
        youTubeClient: YouTubeClient = YouTubeClient(),
        bilibiliClient: BilibiliClient = BilibiliClient(),
        googleDriveClient: GoogleDriveClient = GoogleDriveClient(),
    ) : this(CloudProviderRegistry(youTubeClient, bilibiliClient, googleDriveClient))

    private val cache = ConcurrentHashMap<String, ResolvedStream>()

    private fun cacheKey(providerId: String, track: Track, cookie: String?): String {
        val cookieTag = cookie?.hashCode() ?: 0
        return "$providerId:${track.remoteTrackId}:$cookieTag"
    }

    open suspend fun resolve(
        track: Track,
        root: LibraryRoot?,
        forceRefresh: Boolean = false,
    ): ResolvedStream {
        val source = track.cloudSource ?: throw IOException("Not a cloud track: ${track.title}")
        val remoteId = track.remoteTrackId ?: throw IOException("Missing remote ID for ${track.title}")
        val provider = providerRegistry.providerForTrack(track, root)
            ?: run {
                val pid = root?.resolvedProviderId ?: CloudProviderIds.fromLegacySlug(source)
                if (CloudProviderIds.extensionIdFromProviderId(pid) != null) {
                    throw CloudSourceException(
                        CloudErrorCode.EXTENSION_UNAVAILABLE,
                        "Extension unavailable for ${track.title} ($pid)",
                    )
                }
                throw IOException("Unsupported cloud source: $source")
            }

        val cookie = root?.cookie
        val key = cacheKey(provider.providerId, track, cookie)

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
        )
        cache[key] = resolved
        return resolved
    }

    open fun invalidate(track: Track, root: LibraryRoot? = null) {
        val providerId = providerRegistry.providerForTrack(track, root)?.providerId
            ?: CloudProviderIds.fromLegacySlug(track.cloudSource)
            ?: track.cloudSource
        if (root != null) {
            if (providerId != null) {
                cache.remove(cacheKey(providerId, track, root.cookie))
            }
            track.cloudSource?.let { cache.remove(cacheKey(it, track, root.cookie)) }
        } else {
            val prefixes = listOfNotNull(providerId, track.cloudSource).distinct()
                .map { "$it:${track.remoteTrackId}:" }
            cache.keys.filter { key -> prefixes.any { key.startsWith(it) } }.forEach { cache.remove(it) }
        }
    }

    suspend fun prefetch(track: Track, root: LibraryRoot?) {
        if (!track.isCloud) return
        runCatching { resolve(track, root, forceRefresh = false) }
    }
}
