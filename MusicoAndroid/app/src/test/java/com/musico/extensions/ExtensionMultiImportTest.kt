package com.musico.extensions

import com.musico.cloud.CloudHttpClient
import com.musico.cloud.CloudHttpResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtensionMultiImportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class MockHttpTransport(
        private val handler: suspend (String) -> CloudHttpResponse,
    ) : CloudHttpClient {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            followRedirects: Boolean,
        ): CloudHttpResponse = handler(url)

        override suspend fun postJson(
            url: String,
            jsonBody: String,
            headers: Map<String, String>,
        ): CloudHttpResponse = CloudHttpResponse(404, url, emptyMap(), ByteArray(0))

        override suspend fun probeRange(url: String, headers: Map<String, String>): Int = 200
    }

    private fun zipEntriesToBytes(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun buildExtensionScript(id: String, name: String): String = """
        'use strict';
        var manifest = {
          apiVersion: 1,
          id: '$id',
          name: '$name',
          version: '1.0.0',
          author: 'Fixture',
          updateUrl: 'https://updates.fixture.test/$id.musicoext',
          capabilities: ['playlist'],
          urlPatterns: ['^https://fixture\\.test/'],
          permissions: { hosts: ['fixture.test'] }
        };
        function canHandle(url) { return String(url).indexOf('https://fixture.test/') === 0; }
        async function fetchCollection() {
          return { remoteId: 'c1', title: '$name', tracks: [] };
        }
        module.exports = { manifest: manifest, canHandle: canHandle, fetchCollection: fetchCollection };
    """.trimIndent()

    private fun buildManifestJson(id: String, name: String): String = """
        {
          "apiVersion": 1,
          "id": "$id",
          "name": "$name",
          "version": "1.0.0",
          "author": "Fixture",
          "entry": "index.js",
          "updateUrl": "https://updates.fixture.test/$id.musicoext",
          "capabilities": ["playlist"],
          "urlPatterns": ["^https://fixture\\.test/"],
          "permissions": { "hosts": ["fixture.test"] }
        }
    """.trimIndent()

    private fun buildMusicoextBytes(id: String, name: String): ByteArray = zipEntriesToBytes(
        mapOf(
            "manifest.json" to buildManifestJson(id, name).toByteArray(Charsets.UTF_8),
            "index.js" to buildExtensionScript(id, name).toByteArray(Charsets.UTF_8),
            "icon.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
        )
    )

    private fun createStore(httpClient: CloudHttpClient = MockHttpTransport {
        CloudHttpResponse(404, it, emptyMap(), ByteArray(0))
    }): Pair<ExtensionStore, CloudProviderRegistry> {
        val stateDir = tempFolder.newFolder("ext_multi_state_${System.nanoTime()}")
        val registry = CloudProviderRegistry()
        val store = ExtensionStore(
            stateDir = stateDir,
            httpClient = httpClient,
            providerRegistry = registry,
            appVersion = "1.2.0-test",
        )
        return store to registry
    }

    @Test
    fun loadsNestedMusicoextPackFromFileAndInstallsBatch() = runBlocking {
        val (store, registry) = createStore()
        val packBytes = zipEntriesToBytes(
            mapOf(
                "alpha.musicoext" to buildMusicoextBytes("com.example.alpha", "Alpha Plugin"),
                "nested/beta.musicoext" to buildMusicoextBytes("com.example.beta", "Beta Plugin"),
                "__MACOSX/._alpha.musicoext" to "appledouble".toByteArray(Charsets.UTF_8),
                ".DS_Store" to "junk".toByteArray(Charsets.UTF_8),
            )
        )
        val packFile = File(tempFolder.root, "musico-plugins-all.zip").apply {
            writeBytes(packBytes)
        }

        val staged = store.stagePackagesFromFile(packFile).getOrThrow()
        assertEquals(2, staged.size)
        assertEquals(listOf("com.example.alpha", "com.example.beta"), staged.map { it.manifest.id }.sorted())
        assertTrue(staged.all { it.iconBytes != null })

        // Single-package API must still reject multi-extension archives.
        assertTrue(store.stageFromFile(packFile).isFailure)

        val installed = store.installStagedBatch(staged, enableImmediately = true)
        assertEquals(2, installed.size)
        assertTrue(installed.all { it.enabled })
        assertNotNull(registry.providerForId("ext.com.example.alpha"))
        assertNotNull(registry.providerForId("ext.com.example.beta"))
    }

    @Test
    fun loadsSubdirectoryBundlesAndIgnoresMacOsJunk() = runBlocking {
        val (store, _) = createStore()
        val packBytes = zipEntriesToBytes(
            mapOf(
                "__MACOSX/pkg-one/._manifest.json" to "appledouble".toByteArray(Charsets.UTF_8),
                "pkg-one/.DS_Store" to "junk".toByteArray(Charsets.UTF_8),
                "pkg-one/manifest.json" to buildManifestJson("com.example.one", "One").toByteArray(Charsets.UTF_8),
                "pkg-one/index.js" to buildExtensionScript("com.example.one", "One").toByteArray(Charsets.UTF_8),
                "pkg-two/manifest.json" to buildManifestJson("com.example.two", "Two").toByteArray(Charsets.UTF_8),
                "pkg-two/index.js" to buildExtensionScript("com.example.two", "Two").toByteArray(Charsets.UTF_8),
            )
        )

        val staged = store.stagePackagesFromBytes(packBytes, fileNameHint = "bundle.zip").getOrThrow()
        assertEquals(listOf("com.example.one", "com.example.two"), staged.map { it.manifest.id }.sorted())
    }

    @Test
    fun loadsNestedMusicoJsScriptsFromZip() = runBlocking {
        val (store, _) = createStore()
        val packBytes = zipEntriesToBytes(
            mapOf(
                "scripts/one.musico.js" to buildExtensionScript("com.example.jsone", "JS One").toByteArray(Charsets.UTF_8),
                "scripts/two.musico.js" to buildExtensionScript("com.example.jstwo", "JS Two").toByteArray(Charsets.UTF_8),
                "__MACOSX/scripts/._one.musico.js" to "appledouble".toByteArray(Charsets.UTF_8),
            )
        )

        val staged = store.stagePackagesFromBytes(packBytes, fileNameHint = "scripts.zip").getOrThrow()
        assertEquals(listOf("com.example.jsone", "com.example.jstwo"), staged.map { it.manifest.id }.sorted())
    }

    @Test
    fun rejectsDuplicateIdsAndEmptyPacks() = runBlocking {
        val (store, _) = createStore()
        val duplicatePack = zipEntriesToBytes(
            mapOf(
                "a.musicoext" to buildMusicoextBytes("com.example.dup", "First"),
                "b.musicoext" to buildMusicoextBytes("com.example.dup", "Second"),
            )
        )
        val dupResult = store.stagePackagesFromBytes(duplicatePack, fileNameHint = "dup.zip")
        assertTrue(dupResult.isFailure)
        assertTrue(dupResult.exceptionOrNull()?.message?.contains("Duplicate", ignoreCase = true) == true)

        val emptyPack = zipEntriesToBytes(
            mapOf(
                "README.md" to "not an extension".toByteArray(Charsets.UTF_8),
                "__MACOSX/._README.md" to "appledouble".toByteArray(Charsets.UTF_8),
            )
        )
        assertTrue(store.stagePackagesFromBytes(emptyPack, fileNameHint = "empty.zip").isFailure)
    }

    @Test
    fun urlImportClearsSourceUrlForMultiPackAndPreservesForSingleBundle() = runBlocking {
        val singleBytes = buildMusicoextBytes("com.example.single", "Single")
        val multiBytes = zipEntriesToBytes(
            mapOf(
                "alpha.musicoext" to buildMusicoextBytes("com.example.alpha", "Alpha"),
                "beta.musicoext" to buildMusicoextBytes("com.example.beta", "Beta"),
            )
        )
        val http = MockHttpTransport { url ->
            when {
                url.endsWith("single.musicoext") -> CloudHttpResponse(200, url, emptyMap(), singleBytes)
                else -> CloudHttpResponse(200, url, emptyMap(), multiBytes)
            }
        }
        val (store, _) = createStore(http)

        val singleStaged = store.stagePackagesFromUrl("https://updates.fixture.test/single.musicoext").getOrThrow()
        assertEquals(1, singleStaged.size)
        assertEquals("https://updates.fixture.test/single.musicoext", singleStaged.first().sourceUrl)

        val multiStaged = store.stagePackagesFromUrl("https://updates.fixture.test/musico-plugins-all.zip").getOrThrow()
        assertEquals(2, multiStaged.size)
        assertTrue(multiStaged.all { it.sourceUrl == null })
        assertNull(multiStaged.first().sourceUrl)
    }
}
