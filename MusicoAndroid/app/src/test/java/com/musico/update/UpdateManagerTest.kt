package com.musico.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

private class FakeUpdateHttpClient : UpdateHttpClient {
    var textHandler: (String, Map<String, String>) -> UpdateHttpTextResponse = { url, _ ->
        UpdateHttpTextResponse(404, """{"message":"Not Found: $url"}""")
    }
    var downloadPayload: ByteArray = ByteArray(0)
    var downloadChunkSize: Int = 4
    var downloadError: Throwable? = null
    val recordedGets = mutableListOf<Pair<String, Map<String, String>>>()

    override suspend fun getText(
        url: String,
        headers: Map<String, String>,
    ): UpdateHttpTextResponse {
        recordedGets.add(url to headers)
        return textHandler(url, headers)
    }

    override suspend fun downloadToFile(
        url: String,
        destination: File,
        headers: Map<String, String>,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ) {
        downloadError?.let { throw it }
        destination.parentFile?.mkdirs()
        val total = downloadPayload.size.toLong()
        var offset = 0
        destination.outputStream().use { out ->
            while (offset < downloadPayload.size) {
                val len = minOf(downloadChunkSize, downloadPayload.size - offset)
                out.write(downloadPayload, offset, len)
                offset += len
                onProgress(offset.toLong(), total)
            }
        }
    }
}

private class InMemoryUpdatePrefsStore(
    initialSkipped: String? = null,
) : UpdatePrefsStore {
    var skipped: String? = initialSkipped
    override fun getSkippedVersion(): String? = skipped
    override fun setSkippedVersion(version: String?) {
        skipped = version
    }
}

class UpdateManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun appVersionParsesPrefixesAndComparesNumerically() {
        val v1 = AppVersion.parse("v1.1.1")
        val v2 = AppVersion.parse("V.1.1.1")
        val v3 = AppVersion.parse("1.1.1")
        val v4 = AppVersion.parse("  V1.1.1  ")

        assertNotNull(v1)
        assertEquals(AppVersion(1, 1, 1), v1)
        assertEquals(v1, v2)
        assertEquals(v1, v3)
        assertEquals(v1, v4)

        val v1_9 = AppVersion.parse("1.9.0")!!
        val v1_10 = AppVersion.parse("v1.10.0")!!
        assertTrue(v1_10 > v1_9)

        val pre = AppVersion.parse("v1.2.0-rc.1")!!
        val stable = AppVersion.parse("1.2.0")!!
        assertTrue(stable > pre)

        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("v"))
        assertNull(AppVersion.parse("1.2.3.4"))
        assertNull(AppVersion.parse("abc"))
    }

    @Test
    fun githubJsonParsingFiltersDraftAndPrerelease() {
        val draftJson = sampleReleaseJson(tagName = "v1.2.0", draft = true)
        assertNull(UpdateManager.parseReleaseJson(draftJson))

        val preJson = sampleReleaseJson(tagName = "v1.2.0-beta.1", prerelease = true)
        assertNull(UpdateManager.parseReleaseJson(preJson))

        val validJson = sampleReleaseJson(
            tagName = "V.1.2.0",
            body = "## Changes\n- Faster playback",
        )
        val parsed = UpdateManager.parseReleaseJson(validJson)
        assertNotNull(parsed)
        assertEquals("V.1.2.0", parsed!!.tagName)
        assertEquals(AppVersion(1, 2, 0), parsed.version)
        assertEquals("## Changes\n- Faster playback", parsed.body)
        assertEquals("MusicoAndroid-1.2.0.apk", parsed.apkAsset.name)
        assertEquals("SHA256SUMS.txt", parsed.sha256SumsAsset?.name)
    }

    @Test
    fun apkAssetSelectionPrefersSignedOverDebugAndSkipsUnsigned() {
        val assetsWithSigned = listOf(
            ReleaseAsset("MusicoAndroid-1.2.0-unsigned.apk", "https://example.com/unsigned.apk", 100),
            ReleaseAsset("MusicoAndroid-1.2.0-debug.apk", "https://example.com/debug.apk", 120),
            ReleaseAsset("MusicoAndroid-1.2.0-release.apk", "https://example.com/release.apk", 110),
        )
        assertEquals(
            "MusicoAndroid-1.2.0-release.apk",
            UpdateManager.selectApkAsset(assetsWithSigned)?.name,
        )

        val assetsWithoutSigned = listOf(
            ReleaseAsset("MusicoAndroid-1.2.0-unsigned.apk", "https://example.com/unsigned.apk", 100),
            ReleaseAsset("MusicoAndroid-1.2.0-debug.apk", "https://example.com/debug.apk", 120),
            ReleaseAsset("SHA256SUMS.txt", "https://example.com/SHA256SUMS.txt", 10),
        )
        assertEquals(
            "MusicoAndroid-1.2.0-debug.apk",
            UpdateManager.selectApkAsset(assetsWithoutSigned)?.name,
        )

        val onlyUnsigned = listOf(
            ReleaseAsset("MusicoAndroid-1.2.0-unsigned.apk", "https://example.com/unsigned.apk", 100),
        )
        assertNull(UpdateManager.selectApkAsset(onlyUnsigned))
    }

    @Test
    fun sha256SumsParsingHandlesFormatsAndVerifiesDownload() = runBlocking {
        val payload = "fake-apk-binary-content-123456".toByteArray(Charsets.UTF_8)
        val expectedDigest = sha256Hex(payload)

        val sumsText = """
            # Release checksums
            $expectedDigest  ./MusicoAndroid-1.2.0.apk
            0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef *MusicoWindows-1.2.0-Setup.exe
        """.trimIndent()

        val parsedSums = UpdateManager.parseSha256Sums(sumsText)
        assertEquals(expectedDigest, parsedSums["MusicoAndroid-1.2.0.apk"])
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            parsedSums["MusicoWindows-1.2.0-Setup.exe"],
        )

        val http = FakeUpdateHttpClient().apply {
            downloadPayload = payload
            textHandler = { url, _ ->
                when {
                    url.endsWith("/releases/latest") ->
                        UpdateHttpTextResponse(200, sampleReleaseJson(tagName = "v1.2.0"))
                    url.endsWith("SHA256SUMS.txt") ->
                        UpdateHttpTextResponse(200, sumsText)
                    else -> UpdateHttpTextResponse(404, "")
                }
            }
        }

        val manager = UpdateManager(
            cacheDir = tempFolder.root,
            currentVersion = "1.1.1",
            prefs = InMemoryUpdatePrefsStore(),
            httpClient = http,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val checkState = manager.checkForUpdates(silent = false)
        assertTrue(checkState is UpdateUiState.Available)
        val release = (checkState as UpdateUiState.Available).release

        val downloadResult = manager.downloadAndVerify(release)
        assertTrue(downloadResult.isSuccess)
        val apkFile = downloadResult.getOrThrow()
        assertTrue(apkFile.exists())
        assertEquals("updates", apkFile.parentFile?.name)
        assertEquals(expectedDigest, UpdateManager.computeSha256(apkFile))
        assertTrue(manager.state.value is UpdateUiState.ReadyToInstall)
    }

    @Test
    fun sha256MismatchFailsAndCleansUpTempFile() = runBlocking {
        val payload = "corrupted-apk-bytes".toByteArray(Charsets.UTF_8)
        val wrongDigest = "f".repeat(64)

        val http = FakeUpdateHttpClient().apply {
            downloadPayload = payload
            textHandler = { url, _ ->
                when {
                    url.endsWith("/releases/latest") ->
                        UpdateHttpTextResponse(200, sampleReleaseJson(tagName = "v1.2.0"))
                    url.endsWith("SHA256SUMS.txt") ->
                        UpdateHttpTextResponse(200, "$wrongDigest  MusicoAndroid-1.2.0.apk\n")
                    else -> UpdateHttpTextResponse(404, "")
                }
            }
        }

        val manager = UpdateManager(
            cacheDir = tempFolder.root,
            currentVersion = "1.1.1",
            prefs = InMemoryUpdatePrefsStore(),
            httpClient = http,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val available = manager.checkForUpdates(silent = false) as UpdateUiState.Available
        val result = manager.downloadAndVerify(available.release)

        assertTrue(result.isFailure)
        assertTrue(manager.state.value is UpdateUiState.Error)
        val updatesDir = File(tempFolder.root, "updates")
        assertFalse(File(updatesDir, "MusicoAndroid-1.2.0.apk").exists())
        assertFalse(File(updatesDir, "MusicoAndroid-1.2.0.apk.part").exists())
    }

    @Test
    fun skippedVersionIsRespectedOnSilentCheckButShownOnManualCheck() = runBlocking {
        val prefs = InMemoryUpdatePrefsStore()
        val http = FakeUpdateHttpClient().apply {
            textHandler = { _, _ ->
                UpdateHttpTextResponse(200, sampleReleaseJson(tagName = "v1.2.0"))
            }
        }

        val manager = UpdateManager(
            cacheDir = tempFolder.root,
            currentVersion = "1.1.1",
            prefs = prefs,
            httpClient = http,
            ioDispatcher = Dispatchers.Unconfined,
        )

        // Skip v1.2.0
        manager.skipVersion("v1.2.0")
        assertEquals("1.2.0", prefs.getSkippedVersion())

        // Silent launch check respects skipped version -> stays Idle
        val silentState = manager.checkForUpdates(silent = true)
        assertEquals(UpdateUiState.Idle, silentState)

        // Manual check always reports available update
        val manualState = manager.checkForUpdates(silent = false)
        assertTrue(manualState is UpdateUiState.Available)
        assertEquals(AppVersion(1, 2, 0), (manualState as UpdateUiState.Available).release.version)
    }

    @Test
    fun silentCheckIgnoresErrorsWhileManualCheckReportsUpToDateAndErrors() = runBlocking {
        val http = FakeUpdateHttpClient().apply {
            textHandler = { _, _ -> throw IOException("No network") }
        }
        val manager = UpdateManager(
            cacheDir = tempFolder.root,
            currentVersion = "1.1.1",
            prefs = InMemoryUpdatePrefsStore(),
            httpClient = http,
            ioDispatcher = Dispatchers.Unconfined,
        )

        assertEquals(UpdateUiState.Idle, manager.checkForUpdates(silent = true))
        val manualError = manager.checkForUpdates(silent = false)
        assertTrue(manualError is UpdateUiState.Error)

        // Now return current version -> UpToDate
        http.textHandler = { _, _ ->
            UpdateHttpTextResponse(200, sampleReleaseJson(tagName = "v1.1.1"))
        }
        assertEquals(UpdateUiState.UpToDate, manager.checkForUpdates(silent = false))
    }

    private fun sampleReleaseJson(
        tagName: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        body: String = "Release notes",
    ): String {
        val cleanVersion = AppVersion.parse(tagName)?.toString() ?: tagName
        return """
            {
              "tag_name": "$tagName",
              "name": "Musico $cleanVersion",
              "draft": $draft,
              "prerelease": $prerelease,
              "body": ${jsonString(body)},
              "html_url": "https://github.com/SEANPNEX/local_music_player/releases/tag/$tagName",
              "published_at": "2026-11-22T00:00:00Z",
              "assets": [
                {
                  "name": "MusicoAndroid-$cleanVersion-unsigned.apk",
                  "browser_download_url": "https://github.com/SEANPNEX/local_music_player/releases/download/$tagName/MusicoAndroid-$cleanVersion-unsigned.apk",
                  "size": 1000
                },
                {
                  "name": "MusicoAndroid-$cleanVersion.apk",
                  "browser_download_url": "https://github.com/SEANPNEX/local_music_player/releases/download/$tagName/MusicoAndroid-$cleanVersion.apk",
                  "size": 2000
                },
                {
                  "name": "SHA256SUMS.txt",
                  "browser_download_url": "https://github.com/SEANPNEX/local_music_player/releases/download/$tagName/SHA256SUMS.txt",
                  "size": 128
                }
              ]
            }
        """.trimIndent()
    }

    private fun jsonString(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
