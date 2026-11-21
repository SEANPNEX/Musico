package com.musico.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.musico.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.abs

data class UpdateHttpTextResponse(
    val statusCode: Int,
    val body: String,
)

interface UpdateHttpClient {
    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): UpdateHttpTextResponse

    suspend fun downloadToFile(
        url: String,
        destination: File,
        headers: Map<String, String> = emptyMap(),
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    )
}

class UrlConnectionUpdateHttpClient(
    private val userAgent: String,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : UpdateHttpClient {

    override suspend fun getText(
        url: String,
        headers: Map<String, String>,
    ): UpdateHttpTextResponse = withContext(Dispatchers.IO) {
        executeWithRedirects(url, headers) { conn, statusCode ->
            val stream = if (statusCode in 200..399) conn.inputStream else conn.errorStream
            val bytes = stream?.use { readAllBytes(it) } ?: ByteArray(0)
            UpdateHttpTextResponse(
                statusCode = statusCode,
                body = bytes.toString(Charsets.UTF_8),
            )
        }
    }

    override suspend fun downloadToFile(
        url: String,
        destination: File,
        headers: Map<String, String>,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        executeWithRedirects(url, headers) { conn, statusCode ->
            if (statusCode !in 200..299) {
                throw IOException("HTTP $statusCode while downloading update")
            }
            val totalBytes = conn.contentLengthLong
            destination.parentFile?.mkdirs()
            val buffer = ByteArray(8192)
            var bytesRead = 0L
            conn.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytesRead += read
                        onProgress(bytesRead, totalBytes)
                    }
                    output.flush()
                }
            }
        }
    }

    private fun <T> executeWithRedirects(
        initialUrl: String,
        headers: Map<String, String>,
        block: (HttpURLConnection, Int) -> T,
    ): T {
        var currentUrl = initialUrl
        for (hop in 0 until MAX_REDIRECTS) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", userAgent)
                for ((k, v) in headers) {
                    setRequestProperty(k, v)
                }
            }
            var redirectUrl: String? = null
            try {
                val code = conn.responseCode
                if (isRedirect(code)) {
                    val location = conn.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        redirectUrl = URL(URL(currentUrl), location).toString()
                    }
                }
                if (redirectUrl == null) {
                    return block(conn, code)
                }
            } finally {
                conn.disconnect()
            }
            currentUrl = redirectUrl
        }
        throw IOException("Too many redirects for $initialUrl")
    }

    private fun isRedirect(code: Int): Boolean =
        code == HttpURLConnection.HTTP_MOVED_PERM ||
            code == HttpURLConnection.HTTP_MOVED_TEMP ||
            code == HttpURLConnection.HTTP_SEE_OTHER ||
            code == 307 ||
            code == 308

    private fun readAllBytes(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_REDIRECTS = 6
    }
}

interface UpdatePrefsStore {
    fun getSkippedVersion(): String?
    fun setSkippedVersion(version: String?)
}

class SharedPreferencesUpdatePrefsStore(context: Context) : UpdatePrefsStore {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    override fun getSkippedVersion(): String? = prefs.getString(KEY_SKIPPED_VERSION, null)

    override fun setSkippedVersion(version: String?) {
        prefs.edit().apply {
            if (version == null) remove(KEY_SKIPPED_VERSION) else putString(KEY_SKIPPED_VERSION, version)
        }.apply()
    }

    companion object {
        private const val PREFS_NAME = "musico_update_prefs"
        private const val KEY_SKIPPED_VERSION = "skipped_version"
    }
}

class UpdateManager(
    private val cacheDir: File,
    val currentVersion: String = BuildConfig.VERSION_NAME,
    private val prefs: UpdatePrefsStore,
    private val httpClient: UpdateHttpClient = UrlConnectionUpdateHttpClient(userAgent = "Musico/$currentVersion"),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val latestReleaseUrl: String = LATEST_RELEASE_URL,
) {
    constructor(context: Context) : this(
        cacheDir = context.applicationContext.cacheDir,
        currentVersion = resolveAppVersion(context.applicationContext),
        prefs = SharedPreferencesUpdatePrefsStore(context.applicationContext),
    )

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    @Volatile
    private var launchCheckAttempted: Boolean = false

    suspend fun checkForUpdates(silent: Boolean = false): UpdateUiState {
        if (silent && launchCheckAttempted) {
            return _state.value
        }
        if (silent) {
            launchCheckAttempted = true
        }

        val current = _state.value
        if (current is UpdateUiState.Downloading) {
            return current
        }

        if (!silent) {
            _state.value = UpdateUiState.Checking
        }

        return try {
            val response = withContext(ioDispatcher) {
                httpClient.getText(
                    url = latestReleaseUrl,
                    headers = mapOf(
                        "Accept" to "application/vnd.github+json",
                        "User-Agent" to "Musico/$currentVersion",
                    ),
                )
            }

            if (response.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                val nextState = if (silent) UpdateUiState.Idle else UpdateUiState.UpToDate
                _state.value = nextState
                return nextState
            }
            if (response.statusCode !in 200..299) {
                throw IOException("GitHub API returned HTTP ${response.statusCode}")
            }

            val release = parseReleaseJson(response.body)
            if (release == null) {
                val nextState = if (silent) UpdateUiState.Idle else UpdateUiState.UpToDate
                _state.value = nextState
                return nextState
            }

            val installedVersion = AppVersion.parse(currentVersion)
            val isNewer = installedVersion == null || release.version > installedVersion

            if (!isNewer) {
                val nextState = if (silent) UpdateUiState.Idle else UpdateUiState.UpToDate
                _state.value = nextState
                return nextState
            }

            val skipped = prefs.getSkippedVersion()?.let { AppVersion.parse(it) }
            if (silent && skipped != null && release.version == skipped) {
                _state.value = UpdateUiState.Idle
                return UpdateUiState.Idle
            }

            val available = UpdateUiState.Available(release)
            _state.value = available
            available
        } catch (t: Throwable) {
            if (silent) {
                if (_state.value !is UpdateUiState.Available && _state.value !is UpdateUiState.ReadyToInstall) {
                    _state.value = UpdateUiState.Idle
                }
                _state.value
            } else {
                val err = UpdateUiState.Error(t.message ?: "Unable to check for updates.")
                _state.value = err
                err
            }
        }
    }

    fun skipVersion(version: String) {
        val normalized = AppVersion.parse(version)?.toString() ?: version.trim()
        prefs.setSkippedVersion(normalized)
        _state.value = UpdateUiState.Idle
    }

    fun clearSkippedVersion() {
        prefs.setSkippedVersion(null)
    }

    suspend fun downloadAndVerify(release: ReleaseInfo): Result<File> {
        val updatesDir = File(cacheDir, UPDATES_DIR_NAME).apply { mkdirs() }
        val safeName = File(release.apkAsset.name).name.ifBlank { "MusicoAndroid-update.apk" }
        val partFile = File(updatesDir, "$safeName.part")
        val targetFile = File(updatesDir, safeName)

        _state.value = UpdateUiState.Downloading(release, 0f)

        return try {
            withContext(ioDispatcher) {
                cleanupUpdatesDir(updatesDir)
                var lastReportedProgress = 0f
                httpClient.downloadToFile(
                    url = release.apkAsset.downloadUrl,
                    destination = partFile,
                    headers = mapOf("User-Agent" to "Musico/$currentVersion"),
                ) { bytesRead, totalBytes ->
                    val progress = if (totalBytes > 0L) {
                        (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    } else {
                        -1f
                    }
                    if (progress < 0f || abs(progress - lastReportedProgress) >= 0.01f || progress >= 1f) {
                        lastReportedProgress = progress
                        _state.value = UpdateUiState.Downloading(release, progress)
                    }
                }

                val sumsAsset = release.sha256SumsAsset
                if (sumsAsset != null) {
                    val sumsResponse = httpClient.getText(
                        url = sumsAsset.downloadUrl,
                        headers = mapOf("User-Agent" to "Musico/$currentVersion"),
                    )
                    if (sumsResponse.statusCode !in 200..299) {
                        throw IOException("Failed to fetch SHA256SUMS.txt (HTTP ${sumsResponse.statusCode})")
                    }
                    val sums = parseSha256Sums(sumsResponse.body)
                    val expectedDigest = findChecksumForAsset(sums, release.apkAsset.name)
                        ?: throw SecurityException("SHA-256 checksum missing for ${release.apkAsset.name}")
                    val actualDigest = computeSha256(partFile)
                    if (!actualDigest.equals(expectedDigest, ignoreCase = true)) {
                        throw SecurityException(
                            "SHA-256 mismatch for ${release.apkAsset.name}: expected $expectedDigest, got $actualDigest"
                        )
                    }
                }

                if (targetFile.exists()) {
                    targetFile.delete()
                }
                if (!partFile.renameTo(targetFile)) {
                    partFile.copyTo(targetFile, overwrite = true)
                    partFile.delete()
                }
            }

            _state.value = UpdateUiState.ReadyToInstall(release, targetFile)
            Result.success(targetFile)
        } catch (t: Throwable) {
            partFile.delete()
            _state.value = UpdateUiState.Error(t.message ?: "Failed to download update.")
            Result.failure(t)
        }
    }

    /**
     * Launches the Android package installer for [apkFile], or navigates to Unknown App Sources
     * settings on API 26+ if install permission is not yet granted.
     *
     * @return true if the package installer activity was launched, false if redirected to settings.
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            return false
        }

        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return true
    }

    private fun cleanupUpdatesDir(dir: File) {
        dir.listFiles()?.forEach { file ->
            if (file.isFile && (file.name.endsWith(".apk", ignoreCase = true) || file.name.endsWith(".part", ignoreCase = true))) {
                file.delete()
            }
        }
    }

    companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/SEANPNEX/local_music_player/releases/latest"
        const val RELEASES_PAGE_URL =
            "https://github.com/SEANPNEX/local_music_player/releases"
        private const val UPDATES_DIR_NAME = "updates"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        private val json = Json { ignoreUnknownKeys = true }
        private val shaLineRegex = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""")

        @Suppress("DEPRECATION")
        private fun resolveAppVersion(context: Context): String {
            return runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: BuildConfig.VERSION_NAME
        }

        fun parseReleaseJson(jsonText: String): ReleaseInfo? {
            val root = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull()
                ?: return null

            val isDraft = (root["draft"] as? JsonPrimitive)?.booleanOrNull ?: false
            val isPrerelease = (root["prerelease"] as? JsonPrimitive)?.booleanOrNull ?: false
            if (isDraft || isPrerelease) return null

            val tagName = (root["tag_name"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return null
            val version = AppVersion.parse(tagName) ?: return null

            val name = (root["name"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() } ?: tagName
            val body = (root["body"] as? JsonPrimitive)?.contentOrNull ?: ""
            val htmlUrl = (root["html_url"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() } ?: RELEASES_PAGE_URL
            val publishedAt = (root["published_at"] as? JsonPrimitive)?.contentOrNull ?: ""

            val assetsArray = root["assets"] as? JsonArray ?: return null
            val assets = assetsArray.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val assetName = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val downloadUrl = (obj["browser_download_url"] as? JsonPrimitive)?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val sizeBytes = (obj["size"] as? JsonPrimitive)?.longOrNull ?: 0L
                ReleaseAsset(name = assetName, downloadUrl = downloadUrl, sizeBytes = sizeBytes)
            }

            val apkAsset = selectApkAsset(assets) ?: return null
            val sha256SumsAsset = assets.firstOrNull {
                it.name.equals("SHA256SUMS.txt", ignoreCase = true)
            }

            return ReleaseInfo(
                tagName = tagName,
                version = version,
                name = name,
                body = body,
                htmlUrl = htmlUrl,
                publishedAt = publishedAt,
                apkAsset = apkAsset,
                sha256SumsAsset = sha256SumsAsset,
            )
        }

        fun selectApkAsset(assets: List<ReleaseAsset>): ReleaseAsset? {
            val apks = assets.filter {
                it.name.endsWith(".apk", ignoreCase = true) && it.downloadUrl.isNotBlank()
            }
            if (apks.isEmpty()) return null

            // Prefer signed non-unsigned release APKs.
            apks.firstOrNull {
                !it.name.contains("-unsigned", ignoreCase = true) &&
                    !it.name.endsWith("-debug.apk", ignoreCase = true)
            }?.let { return it }

            // Otherwise prefer MusicoAndroid-*-debug.apk.
            apks.firstOrNull {
                it.name.startsWith("MusicoAndroid-", ignoreCase = true) &&
                    it.name.endsWith("-debug.apk", ignoreCase = true)
            }?.let { return it }

            // Fallback to any other non-unsigned APK.
            return apks.firstOrNull { !it.name.contains("-unsigned", ignoreCase = true) }
        }

        fun parseSha256Sums(content: String): Map<String, String> {
            val map = linkedMapOf<String, String>()
            content.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val match = shaLineRegex.matchEntire(line) ?: return@forEach
                val digest = match.groupValues[1].lowercase()
                val rawPath = match.groupValues[2].trim().removePrefix("./")
                val baseName = rawPath.substringAfterLast('/').substringAfterLast('\\')
                if (baseName.isNotEmpty()) {
                    map[baseName] = digest
                }
                if (rawPath.isNotEmpty() && rawPath != baseName) {
                    map[rawPath] = digest
                }
            }
            return map
        }

        private fun findChecksumForAsset(sums: Map<String, String>, assetName: String): String? {
            sums[assetName]?.let { return it }
            return sums.entries.firstOrNull { it.key.equals(assetName, ignoreCase = true) }?.value
        }

        fun computeSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            FileInputStream(file).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
