package com.musico.cloud

import com.musico.model.LibraryRoot
import com.musico.model.Track
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

open class CloudStreamResolver(
    private val youTubeClient: YouTubeClient = YouTubeClient(),
    private val bilibiliClient: BilibiliClient = BilibiliClient(),
) {
    private val cache = ConcurrentHashMap<String, ResolvedStream>()

    private fun cacheKey(track: Track, cookie: String?): String {
        val cookieTag = cookie?.hashCode() ?: 0
        return "${track.cloudSource}:${track.remoteTrackId}:$cookieTag"
    }

    open suspend fun resolve(
        track: Track,
        root: LibraryRoot?,
        forceRefresh: Boolean = false,
    ): ResolvedStream {
        val source = track.cloudSource ?: throw IOException("Not a cloud track: ${track.title}")
        val remoteId = track.remoteTrackId ?: throw IOException("Missing remote ID for ${track.title}")
        val cookie = root?.cookie
        val key = cacheKey(track, cookie)

        if (!forceRefresh) {
            val cached = cache[key]
            if (cached != null && System.currentTimeMillis() < cached.expiresAtEpochMs) {
                return cached
            }
        }

        val resolved = when (source) {
            CloudSource.YOUTUBE -> youTubeClient.resolveStream(remoteId, cookie)
            CloudSource.BILIBILI -> bilibiliClient.resolveStream(remoteId, cookie)
            else -> throw IOException("Unsupported cloud source: $source")
        }
        cache[key] = resolved
        return resolved
    }

    open fun invalidate(track: Track, root: LibraryRoot? = null) {
        if (root != null) {
            cache.remove(cacheKey(track, root.cookie))
        } else {
            val prefix = "${track.cloudSource}:${track.remoteTrackId}:"
            cache.keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
        }
    }

    suspend fun prefetch(track: Track, root: LibraryRoot?) {
        if (!track.isCloud) return
        runCatching { resolve(track, root, forceRefresh = false) }
    }
}
