package com.musico.extensions

import com.musico.cloud.CloudHttpClient
import com.musico.cloud.CloudHttpResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtensionAutoUpdateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeHttpClient(
        private val responder: (url: String) -> CloudHttpResponse,
    ) : CloudHttpClient {
        val requestedUrls = mutableListOf<String>()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            followRedirects: Boolean,
        ): CloudHttpResponse {
            requestedUrls.add(url)
            return responder(url)
        }

        override suspend fun postJson(
            url: String,
            jsonBody: String,
            headers: Map<String, String>,
        ): CloudHttpResponse = CloudHttpResponse(404, url, emptyMap(), ByteArray(0))

        override suspend fun probeRange(url: String, headers: Map<String, String>): Int = 206
    }

    private fun makeBundle(
        id: String = "demo.ext",
        version: String = "1.0.0",
        hosts: List<String> = listOf("api.example.com"),
        capabilities: List<String> = listOf("search", "stream"),
        updateUrl: String? = null,
    ): ByteArray {
        val hostsJson = hosts.joinToString(",") { "\"$it\"" }
        val capsJson = capabilities.joinToString(",") { "\"$it\"" }
        val updateLine = if (updateUrl != null) "\"updateUrl\": \"$updateUrl\"," else ""
        val manifestJson = """
            {
              "apiVersion": 1,
              "id": "$id",
              "name": "Demo Extension",
              "version": "$version",
              $updateLine
              "entry": "index.js",
              "capabilities": [$capsJson],
              "permissions": { "hosts": [$hostsJson] }
            }
        """.trimIndent()
        val script = """
            module.exports = {
              search: function() { return { items: [] }; },
              resolveStream: function() { return { url: "https://api.example.com/a.mp3" }; }
            };
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifestJson.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("index.js"))
            zos.write(script.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun updateUrlValidationAcceptsHttpsAndRejectsHttpOrInvalid() {
        val valid = ExtensionManifestValidator.validate(
            ExtensionManifest(
                apiVersion = 1,
                id = "valid.ext",
                name = "Valid",
                version = "1.0.0",
                capabilities = listOf(ExtensionCapability.SEARCH),
                permissions = ExtensionPermissions(hosts = listOf("example.com")),
                updateUrl = "  https://cdn.example.com/ext.musicoext  ",
            )
        )
        assertEquals("https://cdn.example.com/ext.musicoext", valid.updateUrl)

        for (badUrl in listOf("http://cdn.example.com/ext.musicoext", "ftp://cdn.example.com/x", "not a url", "https:///nohost")) {
            try {
                ExtensionManifestValidator.validate(
                    ExtensionManifest(
                        apiVersion = 1,
                        id = "bad.ext",
                        name = "Bad",
                        version = "1.0.0",
                        capabilities = listOf(ExtensionCapability.SEARCH),
                        permissions = ExtensionPermissions(hosts = listOf("example.com")),
                        updateUrl = badUrl,
                    )
                )
                fail("Expected validation failure for updateUrl=$badUrl")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun semVerAndPermissionEscalationGuardBehaveAccurately() {
        assertTrue(ExtensionSemVer.isStrictlyGreater("1.0.1", "1.0.0"))
        assertTrue(ExtensionSemVer.isStrictlyGreater("1.2.0", "1.2.0-beta.1"))
        assertTrue(ExtensionSemVer.isStrictlyGreater("1.2.0-beta.2", "1.2.0-beta.1"))
        assertFalse(ExtensionSemVer.isStrictlyGreater("1.2.0", "1.2.0"))
        assertFalse(ExtensionSemVer.isStrictlyGreater("1.2.0+build5", "1.2.0"))
        assertFalse(ExtensionSemVer.isStrictlyGreater("1.1.9", "1.2.0"))

        val base = ExtensionManifest(
            apiVersion = 1,
            id = "demo.ext",
            name = "Demo",
            version = "1.0.0",
            capabilities = listOf(ExtensionCapability.SEARCH, ExtensionCapability.STREAM),
            permissions = ExtensionPermissions(hosts = listOf("*.example.com", "static.other.org")),
        )

        val narrowed = base.copy(
            version = "1.0.1",
            capabilities = listOf(ExtensionCapability.SEARCH),
            permissions = ExtensionPermissions(hosts = listOf("api.example.com")),
        )
        val narrowDiff = ExtensionUpdateGuard.evaluatePermissionEscalation(base, narrowed)
        assertFalse(narrowDiff.hasEscalation)

        val expanded = base.copy(
            version = "1.1.0",
            capabilities = listOf(ExtensionCapability.SEARCH, ExtensionCapability.STREAM, ExtensionCapability.COOKIE),
            permissions = ExtensionPermissions(hosts = listOf("*.example.com", "evil.attacker.net")),
        )
        val expandedDiff = ExtensionUpdateGuard.evaluatePermissionEscalation(base, expanded)
        assertTrue(expandedDiff.hasEscalation)
        assertEquals(listOf(ExtensionCapability.COOKIE), expandedDiff.addedCapabilities)
        assertEquals(listOf("evil.attacker.net"), expandedDiff.addedHosts)
    }

    @Test
    fun silentUpdatePrefersManifestUpdateUrlAndEscalationStagesForReview() = runBlocking {
        val stateDir = tempFolder.newFolder("ext_update_state")
        val now = AtomicLong(1_000_000L)
        val manifestUpdateUrl = "https://updates.example.com/demo-latest.musicoext"
        val fallbackSourceUrl = "https://fallback.example.com/demo-1.0.0.musicoext"

        var remoteBundle = makeBundle(
            id = "demo.ext",
            version = "1.0.1",
            hosts = listOf("api.example.com"),
            capabilities = listOf("search", "stream"),
            updateUrl = manifestUpdateUrl,
        )

        val httpClient = FakeHttpClient { url ->
            when (url) {
                manifestUpdateUrl -> CloudHttpResponse(200, url, emptyMap(), remoteBundle)
                else -> CloudHttpResponse(404, url, emptyMap(), ByteArray(0))
            }
        }

        val store = ExtensionStore(
            stateDir = stateDir,
            httpClient = httpClient,
            providerRegistry = CloudProviderRegistry(),
            clockMs = { now.get() },
        )

        val initialStaged = store.stageFromBytes(
            bytes = makeBundle(
                id = "demo.ext",
                version = "1.0.0",
                hosts = listOf("api.example.com"),
                capabilities = listOf("search", "stream"),
                updateUrl = manifestUpdateUrl,
            ),
            fileNameHint = "demo.musicoext",
            sourceUrl = fallbackSourceUrl,
        ).getOrThrow()
        store.installStaged(initialStaged, enableImmediately = false)

        // Launch auto-update applies safe 1.0.1 silently and preserves enabled=false + sourceUrl.
        val launchSummary = store.checkForUpdatesOnLaunch()
        assertNotNull(launchSummary)
        assertEquals(1, launchSummary!!.appliedCount)
        assertEquals(0, launchSummary.reviewRequiredCount)
        assertEquals(listOf(manifestUpdateUrl), httpClient.requestedUrls)

        val updated = store.findById("demo.ext")!!
        assertEquals("1.0.1", updated.manifest.version)
        assertFalse(updated.enabled)
        assertEquals(fallbackSourceUrl, updated.state.sourceUrl)

        // Within 6-hour throttle window, launch check is skipped.
        now.addAndGet(2 * 60 * 60 * 1000L)
        assertNull(store.checkForUpdatesOnLaunch())

        // After 6 hours, an update that adds a host + capability is staged, NOT auto-applied.
        now.addAndGet(5 * 60 * 60 * 1000L)
        remoteBundle = makeBundle(
            id = "demo.ext",
            version = "1.2.0",
            hosts = listOf("api.example.com", "auth.example.com"),
            capabilities = listOf("search", "stream", "cookie"),
            updateUrl = manifestUpdateUrl,
        )
        val escalatedSummary = store.checkForUpdatesOnLaunch()
        assertNotNull(escalatedSummary)
        assertEquals(0, escalatedSummary!!.appliedCount)
        assertEquals(1, escalatedSummary.reviewRequiredCount)

        // Still on 1.0.1 until user approves!
        assertEquals("1.0.1", store.findById("demo.ext")!!.manifest.version)
        val pending = store.pendingUpdates.value["demo.ext"]
        assertNotNull(pending)
        assertEquals(listOf("auth.example.com"), pending!!.diff.addedHosts)
        assertEquals(listOf(ExtensionCapability.COOKIE), pending.diff.addedCapabilities)

        // User approves pending update.
        val approved = store.approvePendingUpdate("demo.ext")
        assertNotNull(approved)
        assertEquals("1.2.0", approved!!.manifest.version)
        assertTrue(store.pendingUpdates.value.isEmpty())
    }

    @Test
    fun updateCheckRejectsMismatchedManifestIdAndRespectsAutoUpdatePreferencePersistence() = runBlocking {
        val stateDir = tempFolder.newFolder("ext_prefs_state")
        val sourceUrl = "https://cdn.example.com/ext.musicoext"
        val httpClient = FakeHttpClient { url ->
            CloudHttpResponse(
                200,
                url,
                emptyMap(),
                makeBundle(id = "hijacked.other.id", version = "9.9.9"),
            )
        }

        val store1 = ExtensionStore(stateDir, httpClient, CloudProviderRegistry())
        assertTrue(store1.autoUpdateEnabled.value)
        store1.setAutoUpdateEnabled(false)

        val staged = store1.stageFromBytes(
            bytes = makeBundle(id = "demo.ext", version = "1.0.0"),
            fileNameHint = "demo.musicoext",
            sourceUrl = sourceUrl,
        ).getOrThrow()
        store1.installStaged(staged, enableImmediately = true)

        // Reload from disk and verify autoUpdateEnabled=false persisted.
        val store2 = ExtensionStore(stateDir, httpClient, CloudProviderRegistry())
        assertFalse(store2.autoUpdateEnabled.value)
        assertNull(store2.checkForUpdatesOnLaunch())

        // Manual check must reject mismatched manifest.id.
        val outcome = store2.checkForUpdate("demo.ext")
        assertTrue(outcome is ExtensionUpdateCheckOutcome.Error)
        assertEquals("1.0.0", store2.findById("demo.ext")!!.manifest.version)
        assertNull(store2.findById("hijacked.other.id"))
    }
}
