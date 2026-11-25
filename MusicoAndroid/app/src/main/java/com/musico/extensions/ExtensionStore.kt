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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val updateMutex = Mutex()
    private val updatePrefsFile = File(extensionsDir, UPDATE_PREFS_FILE)
    private var updatePrefs: ExtensionUpdatePrefs = ExtensionUpdatePrefs()
    private val kvMap = ConcurrentHashMap<String, MutableMap<String, ExtensionKvEntry>>()
    private val logsMap = ConcurrentHashMap<String, ArrayDeque<ExtensionLogEntry>>()

    private val _extensions = MutableStateFlow<List<InstalledExtension>>(emptyList())
    val extensions: StateFlow<List<InstalledExtension>> = _extensions.asStateFlow()

    private val _logs = MutableStateFlow<Map<String, List<ExtensionLogEntry>>>(emptyMap())
    val logs: StateFlow<Map<String, List<ExtensionLogEntry>>> = _logs.asStateFlow()

    private val _autoUpdateEnabled = MutableStateFlow(true)
    val autoUpdateEnabled: StateFlow<Boolean> = _autoUpdateEnabled.asStateFlow()

    private val _isCheckingUpdates = MutableStateFlow(false)
    val isCheckingUpdates: StateFlow<Boolean> = _isCheckingUpdates.asStateFlow()

    private val _pendingUpdates = MutableStateFlow<Map<String, PendingExtensionUpdate>>(emptyMap())
    val pendingUpdates: StateFlow<Map<String, PendingExtensionUpdate>> = _pendingUpdates.asStateFlow()

    private val _updateOutcomes = MutableStateFlow<Map<String, ExtensionUpdateCheckOutcome>>(emptyMap())
    val updateOutcomes: StateFlow<Map<String, ExtensionUpdateCheckOutcome>> = _updateOutcomes.asStateFlow()

    private val _lastBatchUpdateSummary = MutableStateFlow<ExtensionBatchUpdateSummary?>(null)
    val lastBatchUpdateSummary: StateFlow<ExtensionBatchUpdateSummary?> = _lastBatchUpdateSummary.asStateFlow()

    @Volatile
    var onAvailabilityChanged: (() -> Unit)? = null

    init {
        providerRegistry.extensionStatusLookup = { providerId -> availabilityForProvider(providerId) }
        providerRegistry.extensionCookieLookup = { providerId -> cookieForProvider(providerId) }
        loadUpdatePrefs()
        loadFromDisk()
        syncProviderRegistry()
    }

    private fun loadUpdatePrefs() {
        val loaded = Persistence.load<ExtensionUpdatePrefs>(updatePrefsFile) ?: ExtensionUpdatePrefs()
        updatePrefs = loaded
        _autoUpdateEnabled.value = loaded.autoUpdateEnabled
    }

    private fun saveUpdatePrefsLocked() {
        runCatching { Persistence.save(updatePrefs, updatePrefsFile) }
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
                    supportsCookie = ext.manifest.supportsCookieAuth,
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
        if (!ext.manifest.supportsCookieAuth) return null
        return ext.state.cookie?.trim()?.takeIf { it.isNotEmpty() }
    }

    suspend fun startQrLogin(extensionId: String): ExtQrLoginSession {
        val ext = _extensions.value.firstOrNull {
            it.id == extensionId && it.enabled && it.manifest.supportsQrLogin
        } ?: throw CloudSourceException(
            CloudErrorCode.EXTENSION_UNAVAILABLE,
            "QR login extension unavailable: $extensionId",
        )
        return runtime.startQrLogin(ext)
    }

    suspend fun pollQrLogin(extensionId: String, sessionId: String): ExtQrLoginPollResult {
        val ext = _extensions.value.firstOrNull {
            it.id == extensionId && it.enabled && it.manifest.supportsQrLogin
        } ?: throw CloudSourceException(
            CloudErrorCode.EXTENSION_UNAVAILABLE,
            "QR login extension unavailable: $extensionId",
        )
        val result = runtime.pollQrLogin(ext, sessionId)
        if (result.status == ExtQrLoginStatus.CONFIRMED && !result.cookie.isNullOrBlank()) {
            updateCookie(extensionId, result.cookie)
        }
        return result
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
            val preservedCookie = if (manifest.supportsCookieAuth) {
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
            if (_pendingUpdates.value.containsKey(manifest.id)) {
                _pendingUpdates.value = _pendingUpdates.value - manifest.id
            }
            installed
        }

        syncProviderRegistry()
        onAvailabilityChanged?.invoke()
        return result
    }

    fun setAutoUpdateEnabled(enabled: Boolean) {
        synchronized(storeLock) {
            if (updatePrefs.autoUpdateEnabled == enabled) return
            updatePrefs = updatePrefs.copy(autoUpdateEnabled = enabled)
            _autoUpdateEnabled.value = enabled
            saveUpdatePrefsLocked()
        }
    }

    fun effectiveUpdateUrl(extension: InstalledExtension): String? {
        val manifestUrl = extension.manifest.updateUrl
            ?.trim()
            ?.takeIf { it.isNotEmpty() && isValidHttpsUrl(it) }
        if (manifestUrl != null) return manifestUrl
        return extension.state.sourceUrl
            ?.trim()
            ?.takeIf { it.isNotEmpty() && isValidHttpsUrl(it) }
    }

    suspend fun checkForUpdatesOnLaunch(): ExtensionBatchUpdateSummary? {
        val (enabled, lastCheck) = synchronized(storeLock) {
            updatePrefs.autoUpdateEnabled to updatePrefs.lastCheckEpochMs
        }
        if (!enabled) return null
        val now = clockMs()
        if (lastCheck > 0L && now - lastCheck < AUTO_UPDATE_INTERVAL_MS) {
            return null
        }
        return checkAllForUpdates(manual = false)
    }

    suspend fun checkAllForUpdates(manual: Boolean = true): ExtensionBatchUpdateSummary = updateMutex.withLock {
        _isCheckingUpdates.value = true
        try {
            synchronized(storeLock) {
                updatePrefs = updatePrefs.copy(lastCheckEpochMs = clockMs())
                saveUpdatePrefsLocked()
            }
            val targets = _extensions.value.filter { effectiveUpdateUrl(it) != null }
            var appliedCount = 0
            var reviewCount = 0
            var failedCount = 0
            for (ext in targets) {
                when (checkSingleExtensionLocked(ext)) {
                    is ExtensionUpdateCheckOutcome.Applied -> appliedCount += 1
                    is ExtensionUpdateCheckOutcome.ReviewRequired -> reviewCount += 1
                    is ExtensionUpdateCheckOutcome.Error -> failedCount += 1
                    ExtensionUpdateCheckOutcome.UpToDate,
                    ExtensionUpdateCheckOutcome.NoUpdateUrl,
                    ExtensionUpdateCheckOutcome.Checking -> Unit
                }
            }
            val summary = ExtensionBatchUpdateSummary(
                checkedCount = targets.size,
                appliedCount = appliedCount,
                reviewRequiredCount = reviewCount,
                failedCount = failedCount,
            )
            if (manual || appliedCount > 0 || reviewCount > 0) {
                _lastBatchUpdateSummary.value = summary
            }
            summary
        } finally {
            _isCheckingUpdates.value = false
        }
    }

    suspend fun checkForUpdate(extensionId: String): ExtensionUpdateCheckOutcome = updateMutex.withLock {
        val ext = findById(extensionId) ?: return@withLock ExtensionUpdateCheckOutcome.NoUpdateUrl
        checkSingleExtensionLocked(ext)
    }

    private suspend fun checkSingleExtensionLocked(ext: InstalledExtension): ExtensionUpdateCheckOutcome {
        val updateUrl = effectiveUpdateUrl(ext)
        if (updateUrl == null) {
            _updateOutcomes.value = _updateOutcomes.value + (ext.id to ExtensionUpdateCheckOutcome.NoUpdateUrl)
            return ExtensionUpdateCheckOutcome.NoUpdateUrl
        }

        _updateOutcomes.value = _updateOutcomes.value + (ext.id to ExtensionUpdateCheckOutcome.Checking)
        val outcome = try {
            requireHttpsUrl(updateUrl)
            val staged = stageFromUrl(updateUrl).getOrElse { throw it }
            if (staged.manifest.id != ext.id) {
                throw IllegalArgumentException(
                    "Extension ID mismatch: expected '${ext.id}', got '${staged.manifest.id}'."
                )
            }
            if (!ExtensionSemVer.isStrictlyGreater(staged.manifest.version, ext.manifest.version)) {
                _pendingUpdates.value = _pendingUpdates.value - ext.id
                ExtensionUpdateCheckOutcome.UpToDate
            } else {
                val diff = ExtensionUpdateGuard.evaluatePermissionEscalation(ext.manifest, staged.manifest)
                if (!diff.hasEscalation) {
                    val preservedSource = ext.state.sourceUrl ?: staged.sourceUrl
                    installStaged(
                        staged = staged.copy(sourceUrl = preservedSource),
                        enableImmediately = ext.enabled,
                    )
                    _pendingUpdates.value = _pendingUpdates.value - ext.id
                    ExtensionUpdateCheckOutcome.Applied(
                        fromVersion = ext.manifest.version,
                        toVersion = staged.manifest.version,
                    )
                } else {
                    val pending = PendingExtensionUpdate(
                        installed = ext,
                        staged = staged,
                        diff = diff,
                        updateUrl = updateUrl,
                    )
                    _pendingUpdates.value = _pendingUpdates.value + (ext.id to pending)
                    ExtensionUpdateCheckOutcome.ReviewRequired(pending)
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (err: Exception) {
            ExtensionUpdateCheckOutcome.Error(err.message ?: "Update check failed.")
        }

        _updateOutcomes.value = _updateOutcomes.value + (ext.id to outcome)
        return outcome
    }

    fun approvePendingUpdate(extensionId: String): InstalledExtension? {
        val pending = _pendingUpdates.value[extensionId] ?: return null
        val current = findById(extensionId) ?: pending.installed
        val preservedSource = current.state.sourceUrl ?: pending.staged.sourceUrl
        val installed = installStaged(
            staged = pending.staged.copy(sourceUrl = preservedSource),
            enableImmediately = current.enabled,
        )
        _pendingUpdates.value = _pendingUpdates.value - extensionId
        _updateOutcomes.value = _updateOutcomes.value + (
            extensionId to ExtensionUpdateCheckOutcome.Applied(
                fromVersion = current.manifest.version,
                toVersion = installed.manifest.version,
            )
        )
        return installed
    }

    fun dismissPendingUpdate(extensionId: String) {
        _pendingUpdates.value = _pendingUpdates.value - extensionId
        if (_updateOutcomes.value[extensionId] is ExtensionUpdateCheckOutcome.ReviewRequired) {
            _updateOutcomes.value = _updateOutcomes.value - extensionId
        }
    }

    fun clearBatchUpdateSummary() {
        _lastBatchUpdateSummary.value = null
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
            if (!existing.manifest.supportsCookieAuth) return
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
            _pendingUpdates.value = _pendingUpdates.value - extensionId
            _updateOutcomes.value = _updateOutcomes.value - extensionId
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

    private fun isValidHttpsUrl(urlString: String): Boolean {
        val parsed = runCatching { URL(urlString) }.getOrNull() ?: return false
        return parsed.protocol.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank()
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
        const val UPDATE_PREFS_FILE = "update-prefs.json"
        const val MAX_LOG_ENTRIES = 200
        const val AUTO_UPDATE_INTERVAL_MS = 6L * 60L * 60L * 1000L
    }
}
