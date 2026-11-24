package com.musico.extensions

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.musico.BuildConfig
import com.musico.cloud.CloudErrorCode
import com.musico.cloud.CloudHttpClient
import com.musico.cloud.CloudHttpRequest
import com.musico.cloud.CloudPlaylistResult
import com.musico.cloud.CloudSourceException
import com.musico.core.Persistence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import com.musico.lyrics.CloudLyricsCandidate
import com.musico.lyrics.CloudLyricsException
import com.musico.lyrics.CloudLyricsExtensionDescriptor
import com.musico.lyrics.CloudLyricsQuery
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class ExtensionStore(
    stateDir: File,
    private val httpClient: CloudHttpClient,
    private val providerRegistry: CloudProviderRegistry,
    appVersion: String = BuildConfig.VERSION_NAME,
    shortTimeoutMs: Long = QuickJsExtensionRuntime.DEFAULT_SHORT_TIMEOUT_MS,
    longTimeoutMs: Long = QuickJsExtensionRuntime.DEFAULT_LONG_TIMEOUT_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : ExtensionRuntimeHost {

    val extensionsDir: File = File(stateDir, EXTENSIONS_DIR_NAME).apply { mkdirs() }

    val runtime: QuickJsExtensionRuntime = QuickJsExtensionRuntime(
        httpClient = httpClient,
        host = this,
        appVersion = appVersion,
        shortTimeoutMs = shortTimeoutMs,
        longTimeoutMs = longTimeoutMs,
    )

    val packageLoader: ExtensionPackageLoader = ExtensionPackageLoader(runtime)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val storeLock = Any()
    private val kvMap = ConcurrentHashMap<String, MutableMap<String, ExtensionKvEntry>>()
    private val logsMap = ConcurrentHashMap<String, ArrayDeque<ExtensionLogEntry>>()

    private val _extensions = MutableStateFlow<List<InstalledExtension>>(emptyList())
    val extensions: StateFlow<List<InstalledExtension>> = _extensions.asStateFlow()

    private val _logs = MutableStateFlow<Map<String, List<ExtensionLogEntry>>>(emptyMap())
    val logs: StateFlow<Map<String, List<ExtensionLogEntry>>> = _logs.asStateFlow()

    @Volatile
    var onAvailabilityChanged: (() -> Unit)? = null

    init {
        providerRegistry.extensionStatusLookup = { providerId -> availabilityForProvider(providerId) }
        providerRegistry.extensionCookieLookup = { providerId -> cookieForProvider(providerId) }
        loadFromDisk()
        syncProviderRegistry()
    }

    private fun loadFromDisk() {
        val loaded = mutableListOf<InstalledExtension>()
        val loadedLogs = linkedMapOf<String, List<ExtensionLogEntry>>()
        val dirs = extensionsDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()

        for (dir in dirs) {
            val manifestFile = File(dir, MANIFEST_FILE)
            val scriptFile = File(dir, SCRIPT_FILE)
            if (!manifestFile.isFile || !scriptFile.isFile) continue

            val manifest = runCatching {
                val decoded = json.decodeFromString<ExtensionManifest>(manifestFile.readText(Charsets.UTF_8))
                ExtensionManifestValidator.validate(decoded)
            }.getOrNull() ?: continue

            val scriptSource = runCatching { scriptFile.readText(Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: continue

            val stateFile = File(dir, STATE_FILE)
            val loadedState = Persistence.load<ExtensionState>(stateFile)
            val state = (loadedState ?: ExtensionState(id = manifest.id, enabled = false))
                .copy(id = manifest.id)

            val iconFile = File(dir, ICON_FILE)
            val iconPath = if (iconFile.isFile) iconFile.absolutePath else null

            val kvLoaded = Persistence.load<Map<String, ExtensionKvEntry>>(File(dir, KV_FILE)).orEmpty()
            kvMap[manifest.id] = kvLoaded.toMutableMap()

            val persistedLogs = Persistence.load<List<ExtensionLogEntry>>(File(dir, LOGS_FILE)).orEmpty()
            val deque = ArrayDeque<ExtensionLogEntry>(MAX_LOG_ENTRIES)
            for (entry in persistedLogs.takeLast(MAX_LOG_ENTRIES)) {
                deque.addLast(entry)
            }
            logsMap[manifest.id] = deque
            loadedLogs[manifest.id] = deque.toList()

            loaded.add(
                InstalledExtension(
                    manifest = manifest,
                    state = state.copy(hasIcon = iconPath != null),
                    scriptSource = scriptSource,
                    iconPath = iconPath,
                )
            )
        }

        _extensions.value = loaded.sortedBy { it.manifest.name.lowercase() }
        _logs.value = loadedLogs
    }

    private fun syncProviderRegistry() {
        val active = _extensions.value
            .filter { ext ->
                ext.enabled && ext.manifest.capabilities.any {
                    it == ExtensionCapability.PLAYLIST ||
                        it == ExtensionCapability.TREE ||
                        it == ExtensionCapability.STREAM ||
                        it == ExtensionCapability.SEARCH
                }
            }
            .map { ext -> JsExtensionCloudProvider(ext, runtime) }
        providerRegistry.setExtensionProviders(active)
    }

    fun activeSearchExtensions(): List<SearchExtensionDescriptor> =
        _extensions.value
            .filter { it.enabled && ExtensionCapability.SEARCH in it.manifest.capabilities }
            .map { ext ->
                SearchExtensionDescriptor(
                    id = ext.id,
                    providerId = ext.providerId,
                    name = ext.manifest.name,
                    badgeLabel = ext.manifest.effectiveBadgeLabel,
                    searchKinds = ext.manifest.effectiveSearchKinds,
                    supportsStream = ExtensionCapability.STREAM in ext.manifest.capabilities,
                    supportsCollection = ExtensionCapability.PLAYLIST in ext.manifest.capabilities ||
                        ExtensionCapability.TREE in ext.manifest.capabilities,
                    supportsCookie = ExtensionCapability.COOKIE in ext.manifest.capabilities,
                )
            }

    suspend fun search(
        extensionId: String,
        query: String,
        kind: CloudSearchFilterKind = CloudSearchFilterKind.ALL,
        limit: Int = QuickJsExtensionRuntime.DEFAULT_SEARCH_LIMIT,
        cursor: String? = null,
    ): ExtSearchPage {
        val ext = _extensions.value.firstOrNull {
            it.id == extensionId && it.enabled && ExtensionCapability.SEARCH in it.manifest.capabilities
        } ?: throw CloudSourceException(
            CloudErrorCode.EXTENSION_UNAVAILABLE,
            "Search extension unavailable: $extensionId",
        )
        return runtime.search(
            extension = ext,
            query = query,
            kind = kind,
            limit = limit,
            cursor = cursor,
            cookie = cookieForExtension(extensionId),
        )
    }

    suspend fun fetchCollectionPreview(
        extensionId: String,
        sourceUrl: String,
    ): CloudPlaylistResult {
        val ext = _extensions.value.firstOrNull {
            it.id == extensionId && it.enabled
        } ?: throw CloudSourceException(
            CloudErrorCode.EXTENSION_UNAVAILABLE,
            "Extension unavailable: $extensionId",
        )
        val provider = providerRegistry.providerForId(ext.providerId)
            ?: JsExtensionCloudProvider(ext, runtime)
        return provider.fetchCollection(sourceUrl, cookieForExtension(extensionId))
    }

    fun cookieForExtension(extensionId: String): String? {
        val ext = findById(extensionId) ?: return null
        if (ExtensionCapability.COOKIE !in ext.manifest.capabilities) return null
        return ext.state.cookie?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun cookieForProvider(providerId: String?): String? {
        val extId = CloudProviderIds.extensionIdFromProviderId(providerId) ?: return null
        return cookieForExtension(extId)
    }

    fun activeLyricsExtensions(): List<CloudLyricsExtensionDescriptor> =
        _extensions.value
            .filter { it.enabled && ExtensionCapability.LYRICS in it.manifest.capabilities }
            .map { CloudLyricsExtensionDescriptor(id = it.id, name = it.manifest.effectiveBadgeLabel) }

    suspend fun searchLyrics(extensionId: String, query: CloudLyricsQuery): List<CloudLyricsCandidate> {
        val ext = _extensions.value.firstOrNull {
            it.id == extensionId && it.enabled && ExtensionCapability.LYRICS in it.manifest.capabilities
        } ?: throw CloudLyricsException.ProviderUnavailable(extensionId)

        val specs = runtime.searchLyrics(
            extension = ext,
            title = query.trimmedTitle,
            artist = query.trimmedArtist,
            album = query.trimmedAlbum,
            durationSec = query.durationSec,
        )
        return specs.mapIndexedNotNull { index, spec ->
            val itemTitle = spec.title?.trim().orEmpty()
            val itemArtist = spec.artist?.trim().orEmpty()
            val synced = spec.syncedLyrics?.trim()?.takeIf { it.isNotEmpty() }
            val plain = spec.plainLyrics?.trim()?.takeIf { it.isNotEmpty() }
            val trans = spec.translatedLyrics?.trim()?.takeIf { it.isNotEmpty() }
            val isInst = spec.instrumental == true
            val extItemId = spec.externalId?.trim()?.takeIf { it.isNotEmpty() } ?: "row-$index"
            val candidate = CloudLyricsCandidate(
                id = "ext.$extensionId:$extItemId",
                providerId = "ext.$extensionId",
                sourceName = ext.manifest.effectiveBadgeLabel,
                title = itemTitle.ifEmpty { query.trimmedTitle },
                artist = itemArtist.ifEmpty { query.trimmedArtist },
                album = spec.album?.trim()?.takeIf { it.isNotEmpty() },
                durationSec = spec.durationSec,
                isInstrumental = isInst,
                syncedLyrics = synced,
                plainLyrics = plain,
                translatedLyrics = trans,
            )
            candidate.takeIf { it.hasUsableContent }
        }
    }

    fun availabilityForProvider(providerId: String?): CloudProviderAvailability {
        val extId = CloudProviderIds.extensionIdFromProviderId(providerId) ?: return CloudProviderAvailability.MISSING
        val ext = _extensions.value.firstOrNull { it.id == extId } ?: return CloudProviderAvailability.MISSING
        return if (ext.enabled) CloudProviderAvailability.AVAILABLE else CloudProviderAvailability.DISABLED
    }

    fun badgeLabelForProvider(providerId: String?): String? {
        val extId = CloudProviderIds.extensionIdFromProviderId(providerId) ?: return null
        return _extensions.value.firstOrNull { it.id == extId }?.manifest?.effectiveBadgeLabel
    }

    fun findById(extensionId: String): InstalledExtension? =
        _extensions.value.firstOrNull { it.id == extensionId }

    suspend fun stageFromBytes(
        bytes: ByteArray,
        fileNameHint: String? = null,
        sourceUrl: String? = null,
    ): Result<StagedExtensionPackage> = withContext(Dispatchers.IO) {
        try {
            Result.success(packageLoader.loadFromBytes(bytes, fileNameHint, sourceUrl))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (err: Exception) {
            Result.failure(err)
        }
    }

    suspend fun stageFromFile(file: File): Result<StagedExtensionPackage> = withContext(Dispatchers.IO) {
        try {
            Result.success(packageLoader.loadFromFile(file))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (err: Exception) {
            Result.failure(err)
        }
    }

    suspend fun stageFromUri(context: Context, uri: Uri): Result<StagedExtensionPackage> = withContext(Dispatchers.IO) {
        try {
            val fileName = resolveDisplayName(context, uri) ?: uri.lastPathSegment
            val staged = context.contentResolver.openInputStream(uri)?.use { input ->
                packageLoader.loadFromStream(input, fileNameHint = fileName)
            } ?: throw IOException("Could not open selected extension file.")
            Result.success(staged)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (err: Exception) {
            Result.failure(err)
        }
    }

    suspend fun stageFromUrl(rawUrl: String): Result<StagedExtensionPackage> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = rawUrl.trim()
            requireHttpsUrl(cleanUrl)
            val resp = httpClient.request(
                request = CloudHttpRequest(
                    method = "GET",
                    url = cleanUrl,
                    followRedirects = true,
                    timeoutMs = 20_000,
                    returnBytes = true,
                ),
                urlValidator = { hop -> requireHttpsUrl(hop) },
            )
            if (resp.status !in 200..299) {
                throw CloudSourceException(
                    CloudErrorCode.NETWORK_ERROR,
                    "Extension download failed (HTTP ${resp.status}).",
                )
            }
            val bytes = resp.bytesBase64?.let { Base64.getDecoder().decode(it) }
                ?: resp.text.toByteArray(Charsets.UTF_8)
            val hint = extractFileNameHint(resp.headers["content-disposition"], resp.finalUrl.ifBlank { cleanUrl })
            Result.success(packageLoader.loadFromBytes(bytes, fileNameHint = hint, sourceUrl = cleanUrl))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (err: Exception) {
            Result.failure(err)
        }
    }

    fun installStaged(
        staged: StagedExtensionPackage,
        enableImmediately: Boolean = true,
    ): InstalledExtension {
        val now = clockMs()
        val manifest = ExtensionManifestValidator.validate(staged.manifest)
        val extDir = File(extensionsDir, manifest.id).apply { mkdirs() }

        val result = synchronized(storeLock) {
            val existing = _extensions.value.firstOrNull { it.id == manifest.id }
            val validKeys = manifest.settings.mapTo(HashSet()) { it.key }
            val preservedSettings = existing?.state?.settings
                ?.filterKeys { it in validKeys }
                ?: emptyMap()
            val preservedCookie = if (ExtensionCapability.COOKIE in manifest.capabilities) {
                existing?.state?.cookie?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }

            val manifestJsonText = json.encodeToString(manifest)
            File(extDir, MANIFEST_FILE).writeText(manifestJsonText, Charsets.UTF_8)
            File(extDir, SCRIPT_FILE).writeText(staged.scriptSource, Charsets.UTF_8)

            val iconFile = File(extDir, ICON_FILE)
            val hasIcon = if (staged.iconBytes != null && staged.iconBytes.isNotEmpty()) {
                iconFile.writeBytes(staged.iconBytes)
                true
            } else {
                if (iconFile.exists()) iconFile.delete()
                false
            }

            val nextState = ExtensionState(
                id = manifest.id,
                enabled = enableImmediately,
                format = staged.format,
                installedAtMs = existing?.state?.installedAtMs?.takeIf { it > 0L } ?: now,
                updatedAtMs = now,
                sourceUrl = staged.sourceUrl ?: existing?.state?.sourceUrl,
                hasIcon = hasIcon,
                settings = preservedSettings,
                cookie = preservedCookie,
            )
            Persistence.save(nextState, File(extDir, STATE_FILE))

            kvMap.putIfAbsent(manifest.id, mutableMapOf())
            logsMap.putIfAbsent(manifest.id, ArrayDeque())

            val installed = InstalledExtension(
                manifest = manifest,
                state = nextState,
                scriptSource = staged.scriptSource,
                iconPath = if (hasIcon) iconFile.absolutePath else null,
            )
            _extensions.value = (_extensions.value.filterNot { it.id == manifest.id } + installed)
                .sortedBy { it.manifest.name.lowercase() }
            installed
        }

        syncProviderRegistry()
        onAvailabilityChanged?.invoke()
        return result
    }

    fun setEnabled(extensionId: String, enabled: Boolean) {
        var changed = false
        synchronized(storeLock) {
            val cur = _extensions.value
            val idx = cur.indexOfFirst { it.id == extensionId }
            if (idx < 0) return
            val existing = cur[idx]
            if (existing.enabled == enabled) return
            val nextState = existing.state.copy(enabled = enabled, updatedAtMs = clockMs())
            Persistence.save(nextState, File(File(extensionsDir, extensionId), STATE_FILE))
            val updated = existing.copy(state = nextState)
            val list = cur.toMutableList()
            list[idx] = updated
            _extensions.value = list
            changed = true
        }
        if (changed) {
            syncProviderRegistry()
            onAvailabilityChanged?.invoke()
        }
    }

    fun updateCookie(extensionId: String, cookie: String?) {
        val normalized = cookie?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(storeLock) {
            val cur = _extensions.value
            val idx = cur.indexOfFirst { it.id == extensionId }
            if (idx < 0) return
            val existing = cur[idx]
            if (ExtensionCapability.COOKIE !in existing.manifest.capabilities) return
            if (existing.state.cookie == normalized) return
            val nextState = existing.state.copy(cookie = normalized, updatedAtMs = clockMs())
            Persistence.save(nextState, File(File(extensionsDir, extensionId), STATE_FILE))
            val updated = existing.copy(state = nextState)
            val list = cur.toMutableList()
            list[idx] = updated
            _extensions.value = list
        }
        syncProviderRegistry()
    }

    fun updateSetting(extensionId: String, key: String, value: JsonPrimitive) {
        synchronized(storeLock) {
            val cur = _extensions.value
            val idx = cur.indexOfFirst { it.id == extensionId }
            if (idx < 0) return
            val existing = cur[idx]
            if (existing.manifest.settings.none { it.key == key }) return
            val nextSettings = existing.state.settings + (key to value)
            val nextState = existing.state.copy(settings = nextSettings, updatedAtMs = clockMs())
            Persistence.save(nextState, File(File(extensionsDir, extensionId), STATE_FILE))
            val updated = existing.copy(state = nextState)
            val list = cur.toMutableList()
            list[idx] = updated
            _extensions.value = list
        }
        syncProviderRegistry()
    }

    fun resetSettings(extensionId: String) {
        synchronized(storeLock) {
            val cur = _extensions.value
            val idx = cur.indexOfFirst { it.id == extensionId }
            if (idx < 0) return
            val existing = cur[idx]
            val nextState = existing.state.copy(settings = emptyMap(), updatedAtMs = clockMs())
            Persistence.save(nextState, File(File(extensionsDir, extensionId), STATE_FILE))
            val updated = existing.copy(state = nextState)
            val list = cur.toMutableList()
            list[idx] = updated
            _extensions.value = list
        }
        syncProviderRegistry()
    }

    fun uninstall(extensionId: String) {
        var removed = false
        synchronized(storeLock) {
            val cur = _extensions.value
            if (cur.any { it.id == extensionId }) {
                _extensions.value = cur.filterNot { it.id == extensionId }
                removed = true
            }
            kvMap.remove(extensionId)
            logsMap.remove(extensionId)
            _logs.value = _logs.value - extensionId
            runCatching { File(extensionsDir, extensionId).deleteRecursively() }
        }
        if (removed) {
            syncProviderRegistry()
            onAvailabilityChanged?.invoke()
        }
    }

    override fun getSetting(extensionId: String, key: String): JsonElement? {
        val ext = findById(extensionId) ?: return null
        val spec = ext.manifest.settings.firstOrNull { it.key == key }
        return ext.state.settings[key] ?: spec?.default
    }

    override fun getKv(extensionId: String, key: String): String? {
        val now = clockMs()
        synchronized(storeLock) {
            val map = kvMap[extensionId] ?: return null
            val entry = map[key] ?: return null
            if (entry.expiresAtMs != null && now >= entry.expiresAtMs) {
                map.remove(key)
                persistKvLocked(extensionId, map)
                return null
            }
            return entry.value
        }
    }

    override fun setKv(extensionId: String, key: String, value: String, ttlMs: Long?) {
        val now = clockMs()
        val expiresAt = ttlMs?.takeIf { it > 0L }?.let { now + it }
        synchronized(storeLock) {
            val map = kvMap.getOrPut(extensionId) { mutableMapOf() }
            map[key] = ExtensionKvEntry(value = value, expiresAtMs = expiresAt)
            persistKvLocked(extensionId, map)
        }
    }

    override fun removeKv(extensionId: String, key: String) {
        synchronized(storeLock) {
            val map = kvMap[extensionId] ?: return
            if (map.remove(key) != null) {
                persistKvLocked(extensionId, map)
            }
        }
    }

    override fun appendLog(extensionId: String, level: String, message: String) {
        val cleanLevel = when (level.trim().lowercase()) {
            "warn", "warning" -> "warn"
            "error" -> "error"
            else -> "info"
        }
        val activeCookie = cookieForExtension(extensionId)
        var sanitizedMessage = message
        if (!activeCookie.isNullOrBlank()) {
            sanitizedMessage = sanitizedMessage.replace(activeCookie, "[REDACTED]")
            if (activeCookie.length >= 3) {
                runCatching { URLEncoder.encode(activeCookie, "UTF-8") }.getOrNull()?.let { encodedPlus ->
                    sanitizedMessage = sanitizedMessage
                        .replace(encodedPlus, "[REDACTED]")
                        .replace(encodedPlus.replace("+", "%20"), "[REDACTED]")
                }
            }
        }
        val entry = ExtensionLogEntry(
            timestampMs = clockMs(),
            level = cleanLevel,
            message = sanitizedMessage,
        )
        synchronized(storeLock) {
            val deque = logsMap.getOrPut(extensionId) { ArrayDeque(MAX_LOG_ENTRIES) }
            if (deque.size >= MAX_LOG_ENTRIES) {
                deque.removeFirst()
            }
            deque.addLast(entry)
            val snapshot = deque.toList()
            _logs.value = _logs.value + (extensionId to snapshot)
            persistLogsLocked(extensionId, snapshot)
        }
    }

    fun logsFor(extensionId: String): List<ExtensionLogEntry> =
        _logs.value[extensionId].orEmpty()

    fun clearLogs(extensionId: String) {
        synchronized(storeLock) {
            logsMap[extensionId]?.clear()
            _logs.value = _logs.value + (extensionId to emptyList())
            val logFile = File(File(extensionsDir, extensionId), LOGS_FILE)
            runCatching { if (logFile.exists()) logFile.delete() }
        }
    }

    private fun persistKvLocked(extensionId: String, map: Map<String, ExtensionKvEntry>) {
        val dir = File(extensionsDir, extensionId)
        if (!dir.exists()) return
        runCatching { Persistence.save(map.toMap(), File(dir, KV_FILE)) }
    }

    private fun persistLogsLocked(extensionId: String, entries: List<ExtensionLogEntry>) {
        val dir = File(extensionsDir, extensionId)
        if (!dir.exists()) return
        runCatching { Persistence.save(entries, File(dir, LOGS_FILE)) }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path?.let { File(it).name }
        }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
    }

    private fun requireHttpsUrl(urlString: String) {
        val parsed = runCatching { URL(urlString) }.getOrNull()
            ?: throw IllegalArgumentException("Invalid URL: $urlString")
        require(parsed.protocol.equals("https", ignoreCase = true)) {
            "Extension URLs must use https:// (got ${parsed.protocol})"
        }
        require(!parsed.host.isNullOrBlank()) {
            "Invalid extension URL host: $urlString"
        }
    }

    private fun extractFileNameHint(contentDisposition: String?, finalUrl: String): String? {
        if (!contentDisposition.isNullOrBlank()) {
            val match = Regex("""filename\*?=(?:UTF-8'')?["']?([^"';]+)["']?""", RegexOption.IGNORE_CASE)
                .find(contentDisposition)
                ?.groupValues?.getOrNull(1)
                ?.trim()
            if (!match.isNullOrBlank()) return match
        }
        return runCatching {
            URL(finalUrl).path.substringAfterLast('/').takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    companion object {
        const val EXTENSIONS_DIR_NAME = "Extensions"
        const val MANIFEST_FILE = "manifest.json"
        const val SCRIPT_FILE = "index.js"
        const val ICON_FILE = "icon.png"
        const val STATE_FILE = "state.json"
        const val KV_FILE = "kv.json"
        const val LOGS_FILE = "logs.json"
        const val MAX_LOG_ENTRIES = 200
    }
}
