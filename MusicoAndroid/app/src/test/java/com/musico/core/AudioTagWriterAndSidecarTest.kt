package com.musico.core

import android.net.Uri
import com.musico.cloud.CloudExporter
import com.musico.cloud.CloudHttpClient
import com.musico.cloud.CloudHttpResponse
import com.musico.cloud.CloudPlaylistResult
import com.musico.cloud.CloudSource
import com.musico.cloud.CloudStreamDownloader
import com.musico.cloud.CloudStreamPurpose
import com.musico.cloud.CloudStreamResolver
import com.musico.cloud.CloudTrackMetadata
import com.musico.cloud.ResolvedStream
import com.musico.model.LibraryRoot
import com.musico.model.RootKind
import com.musico.model.Track
import com.musico.store.LibraryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioTagWriterAndSidecarTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val samplePng = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53,
    )

    private val sampleJpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
        0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
        0xFF.toByte(), 0xD9.toByte(),
    )

    private val sampleWebp = "RIFF\u0018\u0000\u0000\u0000WEBPVP8 \u000C\u0000\u0000\u0000000000000000"
        .toByteArray(Charsets.ISO_8859_1)

    private fun minimalFlacBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        // STREAMINFO (type 0, last=1, length=34)
        out.write(0x80)
        out.write(0x00)
        out.write(0x00)
        out.write(34)
        out.write(ByteArray(34))
        // Fake audio frame bytes
        out.write(byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x01, 0x02))
        return out.toByteArray()
    }

    private fun minimalMp3Bytes(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00, 0x11, 0x22, 0x33, 0x44)

    private fun minimalMp4Bytes(): Pair<ByteArray, Int> {
        fun box(type: String, payload: ByteArray): ByteArray {
            val out = ByteArray(8 + payload.size)
            ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(out.size)
                put(type.toByteArray(Charsets.ISO_8859_1), 0, 4)
                put(payload)
            }
            return out
        }

        val ftyp = box("ftyp", "M4A \u0000\u0000\u0000\u0000M4A mp42".toByteArray(Charsets.ISO_8859_1))
        val mdatPayload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val mdat = box("mdat", mdatPayload)

        // Placeholder stco with 1 chunk offset pointing to mdat payload.
        val stcoPlaceholder = ByteArray(12).apply {
            // version/flags = 0, count = 1, offset = placeholder
            ByteBuffer.wrap(this).order(ByteOrder.BIG_ENDIAN).putInt(4, 1)
        }
        val stbl = box("stbl", box("stco", stcoPlaceholder))
        val minf = box("minf", stbl)
        val mdia = box("mdia", minf)
        val trak = box("trak", mdia)
        val moov = box("moov", trak)

        val mdatPayloadOffset = ftyp.size + moov.size + 8
        // Patch stco offset inside moov
        val stcoMarker = "stco".toByteArray(Charsets.ISO_8859_1)
        val idx = (0..(moov.size - 16)).first { i ->
            moov[i] == stcoMarker[0] && moov[i + 1] == stcoMarker[1] &&
                moov[i + 2] == stcoMarker[2] && moov[i + 3] == stcoMarker[3]
        }
        ByteBuffer.wrap(moov).order(ByteOrder.BIG_ENDIAN).putInt(idx + 12, mdatPayloadOffset)

        val combined = ftyp + moov + mdat
        return combined to mdatPayloadOffset
    }

    @Test
    fun detectImageIdentifiesPngJpegAndWebpMagicBytes() {
        val png = AudioTagWriter.detectImage(samplePng)!!
        assertEquals("image/png", png.mimeType)
        assertEquals("png", png.extension)

        val jpg = AudioTagWriter.detectImage(sampleJpeg)!!
        assertEquals("image/jpeg", jpg.mimeType)
        assertEquals("jpg", jpg.extension)

        val webp = AudioTagWriter.detectImage(sampleWebp)!!
        assertEquals("image/webp", webp.mimeType)
        assertEquals("webp", webp.extension)
    }

    @Test
    fun audioTagWriterEmbedsCoverAndTagsInFlacMp3AndM4aAndSkipsWebm() {
        val flacFile = tempFolder.newFile("song.flac").apply { writeBytes(minimalFlacBytes()) }
        assertTrue(
            AudioTagWriter.write(
                flacFile,
                AudioTagPayload(
                    title = "FLAC Song",
                    artist = "FLAC Artist",
                    album = "FLAC Album",
                    genre = "Electronic",
                    trackNumber = 3,
                    artworkBytes = samplePng,
                ),
            )
        )
        val flacRead = FlacTags.read(flacFile)!!
        assertEquals("FLAC Song", flacRead.comments["TITLE"])
        assertEquals("FLAC Artist", flacRead.comments["ARTIST"])
        assertEquals("FLAC Album", flacRead.comments["ALBUM"])
        assertEquals("Electronic", flacRead.comments["GENRE"])
        assertEquals("3", flacRead.comments["TRACKNUMBER"])
        assertArrayEquals(samplePng, flacRead.picture)

        val mp3File = tempFolder.newFile("song.mp3").apply { writeBytes(minimalMp3Bytes()) }
        assertTrue(
            AudioTagWriter.write(
                mp3File,
                AudioTagPayload(
                    title = "MP3 Song",
                    artist = "MP3 Artist",
                    album = "MP3 Album",
                    artworkBytes = sampleJpeg,
                ),
            )
        )
        val mp3Extracted = EmbeddedArtworkExtractor.extract(mp3File, needArtwork = true, needLyrics = false)
        assertArrayEquals(sampleJpeg, mp3Extracted.artwork)

        val (mp4Initial, _) = minimalMp4Bytes()
        val m4aFile = tempFolder.newFile("song.m4a").apply { writeBytes(mp4Initial) }
        assertTrue(
            AudioTagWriter.write(
                m4aFile,
                AudioTagPayload(
                    title = "M4A Song",
                    artist = "M4A Artist",
                    album = "M4A Album",
                    trackNumber = 2,
                    artworkBytes = samplePng,
                ),
            )
        )
        val m4aExtracted = EmbeddedArtworkExtractor.extract(m4aFile, needArtwork = true, needLyrics = false)
        assertArrayEquals(samplePng, m4aExtracted.artwork)
        // Verify mdat payload offset in stco was shifted to still point to 0xDEADBEEF.
        val rewritten = m4aFile.readBytes()
        val stcoIdx = (0..(rewritten.size - 16)).first { i ->
            rewritten[i] == 's'.code.toByte() &&
                rewritten[i + 1] == 't'.code.toByte() &&
                rewritten[i + 2] == 'c'.code.toByte() &&
                rewritten[i + 3] == 'o'.code.toByte()
        }
        val shiftedOffset = ByteBuffer.wrap(rewritten).order(ByteOrder.BIG_ENDIAN).getInt(stcoIdx + 12)
        assertEquals(0xDE.toByte(), rewritten[shiftedOffset])
        assertEquals(0xAD.toByte(), rewritten[shiftedOffset + 1])

        val webmFile = tempFolder.newFile("song.webm").apply { writeText("webm-bytes") }
        assertFalse(AudioTagWriter.isEmbeddableExtension("webm"))
        assertFalse(
            AudioTagWriter.write(
                webmFile,
                AudioTagPayload("WebM", "Artist", "Album", artworkBytes = samplePng),
            )
        )
        assertEquals("webm-bytes", webmFile.readText())
    }

    @Test
    fun cloudExporterTagsFlacWritesWebmSidecarWithCdnRefererAndScannerLoadsSiblingArtwork() = runBlocking {
        val stateDir = tempFolder.newFolder("lib_state")
        val exportParent = tempFolder.newFolder("exports")
        val artworkCache = ArtworkCache(File(stateDir, "art"))

        val recordedImageHeaders = mutableListOf<Map<String, String>>()
        val httpClient = object : CloudHttpClient {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                followRedirects: Boolean,
            ): CloudHttpResponse {
                recordedImageHeaders.add(headers)
                val body = if (url.endsWith("cover.png")) samplePng else sampleJpeg
                return CloudHttpResponse(200, url, emptyMap(), body)
            }

            override suspend fun postJson(
                url: String,
                jsonBody: String,
                headers: Map<String, String>,
            ): CloudHttpResponse = CloudHttpResponse(404, url, emptyMap(), ByteArray(0))

            override suspend fun probeRange(url: String, headers: Map<String, String>): Int = 206
        }

        val thumbnailCache = CloudThumbnailCache(httpClient = httpClient)
        val resolver = object : CloudStreamResolver() {
            override suspend fun resolve(
                track: Track,
                root: LibraryRoot?,
                forceRefresh: Boolean,
                purpose: CloudStreamPurpose,
                requestedQualityId: String?,
            ): ResolvedStream = resolve(track, root, forceRefresh)

            override suspend fun resolve(
                track: Track,
                root: LibraryRoot?,
                forceRefresh: Boolean,
            ): ResolvedStream {
                return if (track.id == "t_flac") {
                    ResolvedStream("https://cdn.example.com/a.flac", mimeType = "audio/flac")
                } else {
                    ResolvedStream("https://cdn.example.com/b.webm", mimeType = "audio/webm")
                }
            }
        }
        val downloader = CloudStreamDownloader { url, _, output ->
            if (url.endsWith(".flac")) {
                output.write(minimalFlacBytes())
            } else {
                output.write("webm-audio-data".toByteArray(Charsets.UTF_8))
            }
        }

        val exporter = CloudExporter(
            context = null,
            cloudResolver = resolver,
            downloader = downloader,
            artworkCache = artworkCache,
            thumbnailCache = thumbnailCache,
            httpClient = httpClient,
        )

        val biliRoot = LibraryRoot(
            id = "bili_root",
            name = "Bili Mix",
            kind = RootKind.CLOUD_BILIBILI,
            sourceUrl = "https://www.bilibili.com/video/BV1xx411c7mD",
        )
        val tracks = listOf(
            Track(
                id = "t_flac",
                rootId = biliRoot.id,
                relativePath = "bili/1",
                title = "Lossless Track",
                artist = "Bili Artist",
                album = "Bili Album",
                albumArtist = "Bili Artist",
                durationSec = 120.0,
                fileSize = 0L,
                modifiedEpochMs = 0L,
                dateAddedEpochMs = 1L,
                cloudSource = CloudSource.BILIBILI,
                remoteTrackId = "1",
                remoteArtworkUrl = "https://i0.hdslb.com/bfs/archive/cover.png",
            ),
            Track(
                id = "t_webm",
                rootId = biliRoot.id,
                relativePath = "bili/2",
                title = "Opus WebM Track",
                artist = "Bili Artist",
                album = "Bili Album",
                albumArtist = "Bili Artist",
                durationSec = 150.0,
                fileSize = 0L,
                modifiedEpochMs = 0L,
                dateAddedEpochMs = 2L,
                cloudSource = CloudSource.BILIBILI,
                remoteTrackId = "2",
                remoteArtworkUrl = "https://i0.hdslb.com/bfs/archive/cover.jpg",
            ),
        )

        val summary = exporter.exportPlaylist(tracks, biliRoot, exportParent, "Bili Export")
        assertEquals(2, summary.savedCount)
        assertEquals(0, summary.failedCount)

        val targetDir = summary.targetDir!!
        assertTrue(recordedImageHeaders.isNotEmpty())
        assertTrue(recordedImageHeaders.all { it["Referer"] == "https://www.bilibili.com/" })
        assertTrue(recordedImageHeaders.all { !it["User-Agent"].isNullOrBlank() })

        // Verify FLAC has embedded PNG picture.
        val exportedFlac = File(targetDir, "Lossless Track.flac")
        assertArrayEquals(samplePng, FlacTags.read(exportedFlac)!!.picture)

        // Verify .webm wrote sibling <stem>.jpg.
        val exportedWebm = File(targetDir, "Opus WebM Track.webm")
        val sidecarJpg = File(targetDir, "Opus WebM Track.jpg")
        assertTrue(exportedWebm.exists())
        assertTrue(sidecarJpg.exists())
        assertArrayEquals(sampleJpeg, sidecarJpg.readBytes())

        // Verify FolderScanner discovers sibling artwork for .webm and .webp tracks.
        val webpAudio = File(targetDir, "Third.webm").apply { writeText("webm2") }
        File(targetDir, "Third.webp").writeBytes(sampleWebp)
        val scanned = FolderScanner.scan(targetDir)!!
        val webmScanned = scanned.first { it.relativePath == "Opus WebM Track.webm" }
        assertNotNull(webmScanned.siblingArtworkUriString)
        assertTrue(webmScanned.siblingArtworkUriString!!.endsWith("Opus%20WebM%20Track.jpg") || webmScanned.siblingArtworkUriString!!.endsWith("Opus WebM Track.jpg"))
        val thirdScanned = scanned.first { it.relativePath == "Third.webm" }
        assertNotNull(thirdScanned.siblingArtworkUriString)
        assertNotNull(AndroidMetadataReader.findSiblingArtworkFile(webpAudio))

        // Verify updateLocalFolder also tags / writes sidecar for newly added tracks.
        val updateSummary = exporter.updateLocalFolder(
            root = biliRoot,
            targetDir = targetDir,
            fetchPlaylist = { _, _, _ ->
                CloudPlaylistResult(
                    source = CloudSource.BILIBILI,
                    remoteId = "BV1xx411c7mD",
                    title = "Bili Export",
                    tracks = listOf(
                        CloudTrackMetadata("1", "Lossless Track", "Bili Artist", "Bili Album", 120.0, artworkUrl = "https://i0.hdslb.com/bfs/archive/cover.png"),
                        CloudTrackMetadata("2", "Opus WebM Track", "Bili Artist", "Bili Album", 150.0, artworkUrl = "https://i0.hdslb.com/bfs/archive/cover.jpg"),
                        CloudTrackMetadata("3", "Updated WebM", "Bili Artist", "Bili Album", 180.0, artworkUrl = "https://i0.hdslb.com/bfs/archive/cover.png"),
                    ),
                )
            },
        )
        assertEquals(1, updateSummary.addedCount)
        assertTrue(File(targetDir, "Updated WebM.webm").exists())
        assertTrue(File(targetDir, "Updated WebM.png").exists())

        // Verify LibraryStore scanner indexes .webm tracks alongside sibling artwork files.
        val stubReader = object : MetadataReading {
            override suspend fun read(uri: Uri, fileNameHint: String?): TrackMetadata =
                TrackMetadata(title = fileNameHint?.substringBeforeLast('.'), durationSec = 10.0)

            override suspend fun read(file: File): TrackMetadata =
                TrackMetadata(title = file.nameWithoutExtension, durationSec = 10.0)
        }
        val trackingArtCache = ArtworkCache(File(stateDir, "art2"))
        val libStore = LibraryStore(
            directory = stateDir,
            appFolderDir = targetDir,
            reader = stubReader,
            artwork = trackingArtCache,
            context = null,
        )
        libStore.rescan()
        val libWebm = libStore.state.value.tracks.firstOrNull { it.relativePath == "Opus WebM Track.webm" }
        assertNotNull(libWebm)
    }

    @Test
    fun partialWritePreservesExistingTitleGenreAndArtworkAcrossFlacId3v24AndMp4() {
        fun buildId3v24Mp3WithUtf8AndCover(): ByteArray {
            fun synch(v: Int): ByteArray = byteArrayOf(
                ((v ushr 21) and 0x7F).toByte(),
                ((v ushr 14) and 0x7F).toByte(),
                ((v ushr 7) and 0x7F).toByte(),
                (v and 0x7F).toByte(),
            )
            fun textFrame(id: String, value: String): ByteArray {
                val utf8 = value.toByteArray(Charsets.UTF_8)
                val body = byteArrayOf(0x03) + utf8
                return id.toByteArray(Charsets.ISO_8859_1) + synch(body.size) + byteArrayOf(0x00, 0x00) + body
            }
            fun apicFrame(image: ByteArray): ByteArray {
                val mime = "image/jpeg".toByteArray(Charsets.ISO_8859_1)
                val body = byteArrayOf(0x00) + mime + byteArrayOf(0x00, 0x03, 0x00) + image
                return "APIC".toByteArray(Charsets.ISO_8859_1) + synch(body.size) + byteArrayOf(0x00, 0x00) + body
            }
            val body = ByteArrayOutputStream().apply {
                write(textFrame("TIT2", "Kept MP3 Title"))
                write(textFrame("TPE1", "Old Artist"))
                write(textFrame("TALB", "Old Album"))
                write(textFrame("TCON", "Dream Pop"))
                write(apicFrame(sampleJpeg))
            }.toByteArray()
            val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 0x04, 0x00, 0x00) + synch(body.size)
            return header + body + minimalMp3Bytes()
        }

        val mp3File = File(tempFolder.root, "v24_partial.mp3").apply { writeBytes(buildId3v24Mp3WithUtf8AndCover()) }
        val flacFile = File(tempFolder.root, "partial.flac").apply { writeBytes(minimalFlacBytes()) }
        val (mp4Bytes, _) = minimalMp4Bytes()
        val m4aFile = File(tempFolder.root, "partial.m4a").apply { writeBytes(mp4Bytes) }

        assertTrue(
            AudioTagWriter.write(
                flacFile,
                AudioTagPayload(
                    title = "Kept FLAC Title",
                    artist = "Orig FLAC Artist",
                    album = "Orig FLAC Album",
                    genre = "Shoegaze",
                    trackNumber = 4,
                    artworkBytes = samplePng,
                ),
            ),
        )
        assertTrue(
            AudioTagWriter.write(
                m4aFile,
                AudioTagPayload(
                    title = "Kept M4A Title",
                    artist = "Orig M4A Artist",
                    album = "Orig M4A Album",
                    genre = "Ambient",
                    trackNumber = 7,
                    artworkBytes = sampleJpeg,
                ),
            ),
        )

        val partialUpdate = AudioTagPayload(
            artist = "Batch Artist",
            album = "Batch Album",
            albumArtist = "Batch Artist",
            artworkBytes = null,
        )
        assertTrue(AudioTagWriter.write(mp3File, partialUpdate, "mp3"))
        assertTrue(AudioTagWriter.write(flacFile, partialUpdate, "flac"))
        assertTrue(AudioTagWriter.write(m4aFile, partialUpdate, "m4a"))

        val mp3Summary = AudioTagWriter.readEmbeddedSummary(mp3File)
        assertEquals("Kept MP3 Title", mp3Summary.title)
        assertEquals("Batch Artist", mp3Summary.artist)
        assertEquals("Batch Album", mp3Summary.album)
        assertEquals("Batch Artist", mp3Summary.albumArtist)
        assertEquals("Dream Pop", mp3Summary.genre)
        assertTrue(mp3Summary.hasArtwork)
        assertArrayEquals(sampleJpeg, EmbeddedArtworkExtractor.extract(mp3File).artwork!!)

        val flacSummary = AudioTagWriter.readEmbeddedSummary(flacFile)
        assertEquals("Kept FLAC Title", flacSummary.title)
        assertEquals("Batch Artist", flacSummary.artist)
        assertEquals("Batch Album", flacSummary.album)
        assertEquals("Batch Artist", flacSummary.albumArtist)
        assertEquals("Shoegaze", flacSummary.genre)
        assertEquals(4, flacSummary.trackNumber)
        assertArrayEquals(samplePng, FlacTags.read(flacFile)!!.picture)

        val m4aSummary = AudioTagWriter.readEmbeddedSummary(m4aFile)
        assertEquals("Kept M4A Title", m4aSummary.title)
        assertEquals("Batch Artist", m4aSummary.artist)
        assertEquals("Batch Album", m4aSummary.album)
        assertEquals("Batch Artist", m4aSummary.albumArtist)
        assertEquals("Ambient", m4aSummary.genre)
        assertEquals(7, m4aSummary.trackNumber)
        assertArrayEquals(sampleJpeg, EmbeddedArtworkExtractor.extract(m4aFile).artwork!!)
    }
}
