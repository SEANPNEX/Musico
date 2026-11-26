package com.musico.ui.theme

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

val LocalThemeStore = staticCompositionLocalOf<ThemeStore?> { null }

class ThemeStore(
    private val context: Context? = null,
    stateDir: File? = null,
) {
    private val resolvedStateDir: File? = stateDir ?: context?.let { ctx ->
        File(ctx.applicationContext.filesDir, "musico")
    }

    private val appearanceFile: File? = resolvedStateDir?.let { File(it, FILE_NAME) }
    private val appearanceDir: File? = resolvedStateDir?.let { File(it, APPEARANCE_DIR_NAME) }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _settings = MutableStateFlow(loadInitial())
    val settings: StateFlow<AppearanceSettings> = _settings.asStateFlow()

    private fun loadInitial(): AppearanceSettings {
        val file = appearanceFile ?: return AppearanceSettings()
        if (!file.exists()) return AppearanceSettings()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return AppearanceSettings()
        return runCatching {
            json.decodeFromString<AppearanceSettings>(text).sanitized()
        }.getOrElse {
            AppearanceSettings()
        }
    }

    private fun updateSettings(transform: (AppearanceSettings) -> AppearanceSettings) {
        val updated = transform(_settings.value).sanitized()
        _settings.value = updated
        persist(updated)
    }

    private fun persist(value: AppearanceSettings) {
        val file = appearanceFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp.${System.nanoTime()}")
            temp.writeText(json.encodeToString(value), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        updateSettings { it.copy(themeMode = mode) }
    }

    fun setBackgroundPreset(preset: BackgroundColorPreset) {
        updateSettings { it.copy(backgroundPreset = preset) }
    }

    fun setCustomBackgroundHex(hex: String?) {
        if (hex == null) {
            updateSettings { it.copy(customBackgroundHex = null) }
            return
        }
        val normalized = normalizeHexOrNull(hex) ?: return
        updateSettings {
            it.copy(
                backgroundPreset = BackgroundColorPreset.CUSTOM,
                customBackgroundHex = normalized,
            )
        }
    }

    fun setForegroundPreset(preset: ForegroundColorPreset) {
        updateSettings { it.copy(foregroundPreset = preset) }
    }

    fun setCustomAccentHex(hex: String?) {
        if (hex == null) {
            updateSettings { it.copy(customAccentHex = null) }
            return
        }
        val normalized = normalizeHexOrNull(hex) ?: return
        updateSettings {
            it.copy(
                foregroundPreset = ForegroundColorPreset.CUSTOM,
                customAccentHex = normalized,
            )
        }
    }

    fun setCustomForegroundHex(hex: String?) {
        if (hex == null) {
            updateSettings { it.copy(customForegroundHex = null) }
            return
        }
        val normalized = normalizeHexOrNull(hex) ?: return
        updateSettings { it.copy(customForegroundHex = normalized) }
    }

    fun setBackgroundImageOpacity(opacity: Float) {
        updateSettings { it.copy(backgroundImageOpacity = clampBackgroundImageOpacity(opacity)) }
    }

    fun setBackgroundImageBlurDp(blurDp: Float) {
        updateSettings { it.copy(backgroundImageBlurDp = clampBackgroundImageBlurDp(blurDp)) }
    }

    fun setLyricsFontSize(fontSize: LyricsFontSize) {
        updateSettings { it.copy(lyricsFontSize = fontSize) }
    }

    fun setLyricsAlignment(alignment: LyricsAlignment) {
        updateSettings { it.copy(lyricsAlignment = alignment) }
    }

    fun autoFixContrast(systemDark: Boolean = false) {
        updateSettings { it.autoFixContrast(systemDark) }
    }

    fun importBackgroundImage(uri: Uri): Result<File> = runCatching {
        val appContext = context?.applicationContext
            ?: throw IllegalStateException("Context is required to import an image URI.")
        val mimeType = appContext.contentResolver.getType(uri)
        val mimeExt = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        val pathExt = uri.lastPathSegment?.substringAfterLast('.', "")
        val extension = mimeExt?.takeIf { it.isNotBlank() }
            ?: pathExt?.takeIf { it.isNotBlank() }
            ?: DEFAULT_IMAGE_EXTENSION
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Unable to open image stream.")
        importBackgroundImage(bytes, extension).getOrThrow()
    }

    fun importBackgroundImage(bytes: ByteArray, extension: String): Result<File> = runCatching {
        require(bytes.isNotEmpty()) { "Image data is empty." }
        val dir = appearanceDir
            ?: throw IllegalStateException("State directory is not configured.")
        dir.mkdirs()

        val cleanExt = extension
            .trim()
            .trimStart('.')
            .lowercase(Locale.US)
            .filter { it.isLetterOrDigit() }
            .ifEmpty { DEFAULT_IMAGE_EXTENSION }

        val targetName = "background.$cleanExt"
        val targetFile = File(dir, targetName)
        val tempFile = File(dir, "$targetName.tmp.${System.nanoTime()}")
        try {
            tempFile.writeBytes(bytes)
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
        } catch (t: Throwable) {
            runCatching { tempFile.delete() }
            throw t
        }

        deleteSupersededFiles(dir, keepFileName = targetName)
        updateSettings { it.copy(backgroundImageFileName = targetName) }
        targetFile
    }

    fun clearBackgroundImage() {
        appearanceDir?.let { dir ->
            deleteSupersededFiles(dir, keepFileName = null)
        }
        updateSettings { it.copy(backgroundImageFileName = null) }
    }

    fun resolveBackgroundImageFile(): File? {
        val dir = appearanceDir ?: return null
        val fileName = _settings.value.backgroundImageFileName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val safeName = File(fileName).name
        if (safeName != fileName) return null
        return File(dir, safeName).takeIf { it.isFile }
    }

    fun resetToDefaults() {
        appearanceDir?.let { dir ->
            deleteSupersededFiles(dir, keepFileName = null)
        }
        val defaults = AppearanceSettings()
        _settings.value = defaults
        persist(defaults)
    }

    private fun deleteSupersededFiles(dir: File, keepFileName: String?) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name != keepFileName) {
                runCatching { file.delete() }
            }
        }
    }

    companion object {
        private const val FILE_NAME = "appearance.json"
        private const val APPEARANCE_DIR_NAME = "Appearance"
        private const val DEFAULT_IMAGE_EXTENSION = "jpg"
    }
}
