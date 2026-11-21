package com.musico.update

import java.io.File

/**
 * Semantic version representation supporting leading `v` / `V` / `v.` / `V.` prefixes,
 * numeric major/minor/patch comparison, and optional pre-release identifiers.
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)

        val aPre = preRelease
        val bPre = other.preRelease
        if (aPre == bPre) return 0
        if (aPre == null) return 1
        if (bPre == null) return -1

        val aParts = aPre.split('.')
        val bParts = bPre.split('.')
        val count = maxOf(aParts.size, bParts.size)
        for (i in 0 until count) {
            if (i >= aParts.size) return -1
            if (i >= bParts.size) return 1
            val partA = aParts[i]
            val partB = bParts[i]
            val numA = partA.toIntOrNull()
            val numB = partB.toIntOrNull()
            val cmp = when {
                numA != null && numB != null -> numA.compareTo(numB)
                numA != null -> -1 // Numeric identifiers always have lower precedence than non-numeric.
                numB != null -> 1
                else -> partA.compareTo(partB)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    override fun toString(): String =
        if (preRelease.isNullOrEmpty()) "$major.$minor.$patch" else "$major.$minor.$patch-$preRelease"

    companion object {
        fun parse(raw: String): AppVersion? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            val withoutPrefix = when {
                trimmed.startsWith("v.", ignoreCase = true) -> trimmed.substring(2)
                trimmed.startsWith("v", ignoreCase = true) -> trimmed.substring(1)
                else -> trimmed
            }.trim()

            if (withoutPrefix.isEmpty()) return null

            // Strip optional +build metadata before splitting pre-release suffix.
            val withoutBuild = withoutPrefix.substringBefore('+')
            val dashIndex = withoutBuild.indexOf('-')
            val core = if (dashIndex >= 0) withoutBuild.substring(0, dashIndex) else withoutBuild
            val preRelease = if (dashIndex >= 0) {
                withoutBuild.substring(dashIndex + 1).takeIf { it.isNotBlank() } ?: return null
            } else {
                null
            }

            val parts = core.split('.')
            if (parts.size !in 2..3) return null
            val major = parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val minor = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val patch = if (parts.size == 3) {
                parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            } else {
                0
            }

            return AppVersion(major = major, minor = minor, patch = patch, preRelease = preRelease)
        }
    }
}

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long = 0L,
)

data class ReleaseInfo(
    val tagName: String,
    val version: AppVersion,
    val name: String,
    val body: String,
    val htmlUrl: String,
    val publishedAt: String,
    val apkAsset: ReleaseAsset,
    val sha256SumsAsset: ReleaseAsset? = null,
)

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val release: ReleaseInfo) : UpdateUiState
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateUiState
    data class ReadyToInstall(val release: ReleaseInfo, val apkFile: File) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

val UpdateUiState.hasAvailableUpdate: Boolean
    get() = this is UpdateUiState.Available ||
        this is UpdateUiState.Downloading ||
        this is UpdateUiState.ReadyToInstall
