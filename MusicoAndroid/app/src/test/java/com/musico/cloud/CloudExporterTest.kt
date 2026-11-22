package com.musico.cloud

import com.musico.model.LibraryRoot
import com.musico.model.RootKind
import com.musico.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream

class CloudExporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeCloudStreamResolver(
        private val onResolve: suspend (Track, LibraryRoot?, Boolean) -> ResolvedStream,
    ) : CloudStreamResolver() {
        val invalidateCalls = mutableListOf<String>()

        override suspend fun resolve(
            track: Track,
            root: LibraryRoot?,
            forceRefresh: Boolean,
        ): ResolvedStream = onResolve(track, root, forceRefresh)

        override fun invalidate(track: Track, root: LibraryRoot?) {
            invalidateCalls.add(track.id)
        }
    }

    private fun cloudTrack(
        id: String,
        title: String,
        remoteId: String = "remote_$id",
        source: String = CloudSource.YOUTUBE,
    ): Track = Track(
        id = id,
        rootId = "root_1",
        relativePath = "cloud/$remoteId",
        title = title,
        artist = "Artist",
        album = "Album",
        albumArtist = "Artist",
        durationSec = 180.0,
        fileSize = 0L,
        modifiedEpochMs = 0L,
        dateAddedEpochMs = 1000L,
        cloudSource = source,
        remoteTrackId = remoteId,
    )

    private fun cloudRoot(): LibraryRoot = LibraryRoot(
        id = "root_1",
        name = "My: Cloud / Mix?",
        kind = RootKind.CLOUD_YOUTUBE,
        sourceUrl = "https://music.youtube.com/playlist?list=PL123",
        remoteId = "PL123",
        cookie = "SID=abc",
    )

    @Test
    fun sanitizeFileNameHandlesIllegalCharsDotsBlankAndUnicode() {
        assertEquals("A_B_C_D_E_F_G_H_I", CloudExporter.sanitizeFileName("A/B\\C:D*E?F\"G<H>I"))
        assertEquals("曲名 日本語 (Live)", CloudExporter.sanitizeFileName("  .曲名   日本語 (Live).  "))
        assertEquals("fallback", CloudExporter.sanitizeFileName("   ", "fallback"))
        assertEquals("fallback", CloudExporter.sanitizeFileName("...", "fallback"))
        assertEquals("fallback", CloudExporter.sanitizeFileName("..", "fallback"))
        assertEquals("a_b", CloudExporter.sanitizeFileName("a\u0000b"))
        val longInput = "x".repeat(200)
        assertTrue(CloudExporter.sanitizeFileName(longInput).length <= 120)
    }

    @Test
    fun uniqueFileNameAvoidsCaseInsensitiveCollisions() {
        val existing = mutableSetOf("Song.m4a", "song (2).m4a", "Folder")
        assertEquals("Other.m4a", CloudExporter.uniqueFileName(existing, "Other", "m4a"))
        assertEquals("Song (3).m4a", CloudExporter.uniqueFileName(existing, "Song", "m4a"))
        assertEquals("song (3).m4a", CloudExporter.uniqueFileName(existing, "song", "M4A"))
        assertEquals("Folder (2)", CloudExporter.uniqueFileName(existing, "Folder", ""))
    }

    @Test
    fun exportPlaylistCreatesSanitizedSubfolderAndHandlesDuplicateTitles() = runBlocking {
        val parent = tempFolder.newFolder("exports")
        val root = cloudRoot()
        val tracks = listOf(
            cloudTrack("t1", "Song: One"),
            cloudTrack("t2", "Song: One"),
            cloudTrack("t3", "Track/Three"),
        )
        val resolver = FakeCloudStreamResolver { track, passedRoot, _ ->
            assertEquals(root, passedRoot)
            val mime = if (track.id == "t3") "audio/webm; codecs=\"opus\"" else "audio/mp4"
            ResolvedStream(
                url = "https://cdn.example.com/${track.id}",
                headers = mapOf("Referer" to "https://www.youtube.com/"),
                mimeType = mime,
            )
        }
        val recordedHeaders = mutableListOf<Map<String, String>>()
        val downloader = CloudStreamDownloader { url, headers, output ->
            recordedHeaders.add(headers)
            output.write("audio:$url".toByteArray(Charsets.UTF_8))
        }

        val progressEvents = mutableListOf<Triple<Int, Int, String>>()
        val exporter = CloudExporter(context = null, cloudResolver = resolver, downloader = downloader)
        val summary = exporter.exportPlaylist(
            tracks = tracks,
            root = root,
            parentDir = parent,
            folderName = "My: Cloud / Mix?",
            onProgress = { done, total, title ->
                progressEvents.add(Triple(done, total, title))
            },
        )

        assertEquals(3, summary.savedCount)
        assertEquals(0, summary.failedCount)
        assertTrue(summary.errors.isEmpty())
        val targetDir = summary.targetDir
        assertNotNull(targetDir)
        assertEquals("My_ Cloud _ Mix_", targetDir!!.name)

        val savedFiles = targetDir.listFiles()!!
            .map { it.name }
            .filter { it != CloudExporter.MANIFEST_FILE_NAME }
            .sorted()
        assertEquals(listOf("Song_ One (2).m4a", "Song_ One.m4a", "Track_Three.webm"), savedFiles)
        val manifest = CloudExporter.loadManifest(targetDir)
        assertNotNull(manifest)
        assertEquals(root.sourceUrl, manifest!!.sourceURL)
        assertEquals(RootKind.CLOUD_YOUTUBE.name, manifest.cloudKind)
        assertEquals(3, manifest.tracks.size)
        assertEquals("audio:https://cdn.example.com/t1", File(targetDir, "Song_ One.m4a").readText())
        assertEquals("audio:https://cdn.example.com/t2", File(targetDir, "Song_ One (2).m4a").readText())
        assertEquals("audio:https://cdn.example.com/t3", File(targetDir, "Track_Three.webm").readText())
        assertTrue(recordedHeaders.all { it["Referer"] == "https://www.youtube.com/" })
        assertTrue(progressEvents.contains(Triple(0, 3, "Song: One")))
        assertTrue(progressEvents.contains(Triple(3, 3, "Track/Three")))
    }

    @Test
    fun exportTrackUsesCollisionSafeNameAndRetriesRefreshedStream() = runBlocking {
        val dir = tempFolder.newFolder("single")
        File(dir, "Hello.m4a").writeText("existing")
        val root = cloudRoot()
        val track = cloudTrack("t1", "Hello")

        val resolver = FakeCloudStreamResolver { _, _, forceRefresh ->
            ResolvedStream(
                url = if (forceRefresh) "https://cdn.example.com/fresh" else "https://cdn.example.com/stale",
                mimeType = "audio/mp4",
            )
        }
        val downloader = CloudStreamDownloader { url, _, output ->
            if (url.endsWith("stale")) {
                output.write("partial".toByteArray())
                throw IOException("HTTP 403")
            }
            output.write("fresh-bytes".toByteArray())
        }

        val exporter = CloudExporter(context = null, cloudResolver = resolver, downloader = downloader)
        val savedName = exporter.exportTrack(track, root, dir)

        assertEquals("Hello (2).m4a", savedName)
        assertEquals(listOf("t1"), resolver.invalidateCalls)
        assertEquals("existing", File(dir, "Hello.m4a").readText())
        assertEquals("fresh-bytes", File(dir, "Hello (2).m4a").readText())
        assertFalse(File(dir, ".Hello (2).m4a.part").exists())
    }

    @Test
    fun exportPlaylistContinuesOnPartialFailureAndCleansUpOnCancellation() = runBlocking {
        val parent = tempFolder.newFolder("partial")
        val root = cloudRoot()
        val tracks = listOf(
            cloudTrack("ok1", "First"),
            cloudTrack("bad", "Broken"),
            cloudTrack("ok2", "Third"),
        )
        val resolver = FakeCloudStreamResolver { track, _, _ ->
            if (track.id == "bad") throw IOException("Stream unavailable")
            ResolvedStream(url = "https://cdn.example.com/${track.id}", mimeType = "audio/mp4")
        }
        val downloader = CloudStreamDownloader { url, _, output ->
            output.write("bytes:$url".toByteArray())
        }

        val exporter = CloudExporter(context = null, cloudResolver = resolver, downloader = downloader)
        val summary = exporter.exportPlaylist(tracks, root, parent, "Partial Test")

        assertEquals(2, summary.savedCount)
        assertEquals(1, summary.failedCount)
        assertEquals(1, summary.errors.size)
        assertTrue(summary.errors[0].contains("Broken"))
        val names = summary.targetDir!!.listFiles()!!
            .map { it.name }
            .filter { it != CloudExporter.MANIFEST_FILE_NAME }
            .sorted()
        assertEquals(listOf("First.m4a", "Third.m4a"), names)

        // Cancellation rethrows and leaves no partial files behind.
        val cancelDir = tempFolder.newFolder("cancel")
        val cancelExporter = CloudExporter(
            context = null,
            cloudResolver = FakeCloudStreamResolver { _, _, _ ->
                ResolvedStream("https://cdn.example.com/x", mimeType = "audio/mp4")
            },
            downloader = CloudStreamDownloader { _, _, output: OutputStream ->
                output.write("partial".toByteArray())
                throw CancellationException("user cancelled")
            },
        )
        var cancelled = false
        try {
            cancelExporter.exportPlaylist(listOf(cloudTrack("c1", "Cancelled")), root, cancelDir, "Cancelled")
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(0, File(cancelDir, "Cancelled").listFiles()?.size ?: 0)
    }

    @Test
    fun updateLocalFolderDownloadsOnlyNewAndMissingAndUpdatesManifest() = runBlocking {
        val parent = tempFolder.newFolder("tracked_parent")
        val root = cloudRoot()
        val initialTracks = listOf(
            cloudTrack("t1", "Song One", remoteId = "vid_1"),
            cloudTrack("t2", "Song Two", remoteId = "vid_2"),
        )
        val resolvedIds = mutableListOf<String>()
        val exporter = CloudExporter(
            context = null,
            cloudResolver = FakeCloudStreamResolver { track, _, _ ->
                resolvedIds.add(track.remoteTrackId ?: track.id)
                ResolvedStream(
                    url = "https://cdn.example.com/${track.remoteTrackId}",
                    mimeType = "audio/mp4",
                )
            },
            downloader = CloudStreamDownloader { url, _, output ->
                output.write("audio:$url".toByteArray(Charsets.UTF_8))
            },
        )
        val exportSummary = exporter.exportPlaylist(initialTracks, root, parent, "Tracked Mix")
        val targetDir = exportSummary.targetDir!!
        assertEquals(2, exportSummary.savedCount)

        // Delete Song Two on disk while leaving Song One intact; keep an unrelated file too.
        File(targetDir, "Song Two.m4a").delete()
        File(targetDir, "Custom Note.txt").writeText("keep")
        resolvedIds.clear()

        val trackedRoot = LibraryRoot(
            id = "local_tracked",
            name = "Tracked Mix",
            kind = RootKind.PICKED,
            treeUri = targetDir.toURI().toString(),
            sourceUrl = root.sourceUrl,
            remoteId = root.remoteId,
            linkedCloudKind = RootKind.CLOUD_YOUTUBE,
        )
        val updateSummary = exporter.updateLocalFolder(
            root = trackedRoot,
            targetDir = targetDir,
            fetchPlaylist = { _, _, _ ->
                CloudPlaylistResult(
                    source = CloudSource.YOUTUBE,
                    remoteId = "PL123",
                    title = "Tracked Mix",
                    tracks = listOf(
                        CloudTrackMetadata("vid_1", "Song One", "Artist", "Album", 180.0),
                        CloudTrackMetadata("vid_2", "Song Two", "Artist", "Album", 200.0),
                        CloudTrackMetadata("vid_3", "Song Three", "Artist", "Album", 210.0),
                    ),
                )
            },
        )

        assertEquals(2, updateSummary.addedCount)
        assertEquals(1, updateSummary.skippedCount)
        assertEquals(0, updateSummary.failedCount)
        assertEquals(3, updateSummary.totalRemoteCount)
        assertEquals(listOf("vid_2", "vid_3"), resolvedIds)
        assertTrue(File(targetDir, "Song One.m4a").exists())
        assertTrue(File(targetDir, "Song Two.m4a").exists())
        assertTrue(File(targetDir, "Song Three.m4a").exists())
        assertTrue(File(targetDir, "Custom Note.txt").exists())

        val updatedManifest = CloudExporter.loadManifest(targetDir)
        assertNotNull(updatedManifest)
        assertEquals(3, updatedManifest!!.tracks.size)
        assertEquals("Song Three.m4a", updatedManifest.tracks["vid_3"])
    }
}
