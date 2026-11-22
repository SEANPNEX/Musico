package com.musico.i18n

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

enum class AppLanguage(val code: String) {
    SYSTEM("system"),
    ENGLISH("en"),
    SIMPLIFIED_CHINESE("zh-Hans"),
    ;

    companion object {
        fun fromCode(code: String?): AppLanguage {
            val normalized = code?.trim().orEmpty()
            return entries.firstOrNull { it.code.equals(normalized, ignoreCase = true) } ?: SYSTEM
        }
    }
}

enum class ResolvedLanguage {
    EN,
    ZH_HANS,
}

fun resolveLanguage(
    selection: AppLanguage,
    systemLocale: Locale = Locale.getDefault(),
): ResolvedLanguage {
    return when (selection) {
        AppLanguage.SIMPLIFIED_CHINESE -> ResolvedLanguage.ZH_HANS
        AppLanguage.ENGLISH -> ResolvedLanguage.EN
        AppLanguage.SYSTEM -> {
            if (systemLocale.language.equals("zh", ignoreCase = true)) {
                ResolvedLanguage.ZH_HANS
            } else {
                ResolvedLanguage.EN
            }
        }
    }
}

class LanguageStore(
    context: Context? = null,
    prefsFile: File? = null,
) {
    private val storageFile: File? = prefsFile ?: context?.let { ctx ->
        File(File(ctx.applicationContext.filesDir, "musico"), FILE_NAME)
    }

    private val _language = MutableStateFlow(loadInitial())
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    private fun loadInitial(): AppLanguage {
        val file = storageFile ?: return AppLanguage.SYSTEM
        if (!file.exists()) return AppLanguage.SYSTEM
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        return AppLanguage.fromCode(raw)
    }

    fun setLanguage(language: AppLanguage) {
        if (_language.value == language && storageFile?.exists() == true) {
            return
        }
        _language.value = language
        val file = storageFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp.${System.nanoTime()}")
            temp.writeText(language.code, Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
    }

    companion object {
        private const val FILE_NAME = "language.txt"
    }
}
