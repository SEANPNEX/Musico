package com.musico.extensions

import com.musico.cloud.CloudErrorCode
import com.musico.cloud.CloudSourceException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URL

@Serializable
enum class ExtensionCapability(val wireName: String) {
    @SerialName("playlist")
    PLAYLIST("playlist"),

    @SerialName("tree")
    TREE("tree"),

    @SerialName("stream")
    STREAM("stream"),

    @SerialName("cookie")
    COOKIE("cookie"),

    @SerialName("proxy")
    PROXY("proxy"),

    @SerialName("lyrics")
    LYRICS("lyrics"),

    @SerialName("search")
    SEARCH("search"),

    @SerialName("qr_login")
    QR_LOGIN("qr_login"),
    ;

    companion object {
        fun fromWire(raw: String): ExtensionCapability? =
            entries.firstOrNull { it.wireName == raw.trim().lowercase() }
    }
}

@Serializable
enum class ExtensionSearchKind(val wireName: String) {
    @SerialName("track")
    TRACK("track"),

    @SerialName("playlist")
    PLAYLIST("playlist"),

    @SerialName("album")
    ALBUM("album"),
    ;

    fun toFilterKind(): CloudSearchFilterKind = when (this) {
        TRACK -> CloudSearchFilterKind.TRACK
        PLAYLIST -> CloudSearchFilterKind.PLAYLIST
        ALBUM -> CloudSearchFilterKind.ALBUM
    }

    companion object {
        val DEFAULT_ALL: List<ExtensionSearchKind> = listOf(TRACK, PLAYLIST, ALBUM)

        fun fromWire(raw: String): ExtensionSearchKind? =
            entries.firstOrNull { it.wireName == raw.trim().lowercase() }
    }
}

enum class CloudSearchFilterKind(val wireName: String) {
    ALL("all"),
    TRACK("track"),
    PLAYLIST("playlist"),
    ALBUM("album"),
}

@Serializable
enum class ExtensionSettingType(val wireName: String) {
    @SerialName("boolean")
    BOOLEAN("boolean"),

    @SerialName("string")
    STRING("string"),

    @SerialName("select")
    SELECT("select"),

    @SerialName("server_url")
    SERVER_URL("server_url"),
}

@Serializable(with = ExtensionSelectOptionSerializer::class)
data class ExtensionSelectOption(
    val value: String,
    val label: String,
)

object ExtensionSelectOptionSerializer : KSerializer<ExtensionSelectOption> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ExtensionSelectOption") {
        element<String>("value")
        element<String>("label")
    }

    override fun deserialize(decoder: Decoder): ExtensionSelectOption {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("ExtensionSelectOption requires JSON decoding")
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                val str = element.content
                ExtensionSelectOption(value = str, label = str)
            }
            is JsonObject -> {
                val value = element["value"]?.jsonPrimitive?.contentOrNull
                    ?: throw SerializationException("Select option missing 'value'")
                val label = element["label"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: value
                ExtensionSelectOption(value = value, label = label)
            }
            else -> throw SerializationException("Unsupported select option JSON shape")
        }
    }

    override fun serialize(encoder: Encoder, value: ExtensionSelectOption) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("ExtensionSelectOption requires JSON encoding")
        jsonEncoder.encodeJsonElement(
            buildJsonObject {
                put("value", value.value)
                put("label", value.label)
            }
        )
    }
}

@Serializable
data class ExtensionSettingSpec(
    val key: String,
    val label: String,
    val type: ExtensionSettingType,
    val default: JsonPrimitive? = null,
    val options: List<ExtensionSelectOption> = emptyList(),
) {
    fun resolveValue(overrides: Map<String, JsonPrimitive>): JsonPrimitive? {
        val override = overrides[key]
        if (override != null) return override
        return default
    }

    fun resolveBoolean(overrides: Map<String, JsonPrimitive>): Boolean {
        val raw = resolveValue(overrides) ?: return false
        return raw.booleanOrNull ?: raw.contentOrNull?.equals("true", ignoreCase = true) ?: false
    }

    fun resolveString(overrides: Map<String, JsonPrimitive>): String {
        val raw = resolveValue(overrides) ?: return ""
        return raw.contentOrNull ?: ""
    }
}

@Serializable
data class ExtensionPermissions(
    val hosts: List<String> = emptyList(),
)

@Serializable
data class ExtensionManifest(
    val apiVersion: Int,
    val id: String,
    val name: String,
    val version: String,
    val author: String? = null,
    val description: String? = null,
    val badgeLabel: String? = null,
    val entry: String = "index.js",
    val capabilities: List<ExtensionCapability> = emptyList(),
    val urlPatterns: List<String> = emptyList(),
    val searchKinds: List<ExtensionSearchKind> = emptyList(),
    val permissions: ExtensionPermissions = ExtensionPermissions(),
    val settings: List<ExtensionSettingSpec> = emptyList(),
    val updateUrl: String? = null,
) {
    val providerId: String
        get() = "ext.$id"

    val effectiveBadgeLabel: String
        get() = (badgeLabel?.trim()?.takeIf { it.isNotEmpty() } ?: name.trim()).take(MAX_BADGE_LENGTH)

    val effectiveSearchKinds: List<ExtensionSearchKind>
        get() = searchKinds.distinct().ifEmpty { ExtensionSearchKind.DEFAULT_ALL }

    val supportsCookieAuth: Boolean
        get() = ExtensionCapability.COOKIE in capabilities || ExtensionCapability.QR_LOGIN in capabilities

    val supportsQrLogin: Boolean
        get() = ExtensionCapability.QR_LOGIN in capabilities

    val hasServerUrlSetting: Boolean
        get() = settings.any { it.type == ExtensionSettingType.SERVER_URL }

    fun effectiveAllowedHosts(settingLookup: (String) -> JsonElement?): List<String> {
        val combined = permissions.hosts.toMutableList()
        val seen = combined.mapTo(HashSet()) { it.trim().lowercase().trimEnd('.') }
        for (spec in settings) {
            if (spec.type != ExtensionSettingType.SERVER_URL) continue
            val rawElem = settingLookup(spec.key) ?: spec.default
            val rawUrl = (rawElem as? JsonPrimitive)?.contentOrNull ?: continue
            val approved = HostAllowlistMatcher.extractApprovedHost(rawUrl) ?: continue
            if (seen.add(approved)) {
                combined.add(approved)
            }
        }
        return combined
    }

    companion object {
        const val CURRENT_API_VERSION = 1
        const val MAX_BADGE_LENGTH = 16
    }
}

/**
 * Validates and evaluates strict HTTP/HTTPS host allowlist patterns (`example.com` or `*.sub.example.com`).
 */
object HostAllowlistMatcher {
    fun validatePattern(pattern: String): String {
        val trimmed = pattern.trim().lowercase().trimEnd('.')
        if (trimmed.isEmpty() ||
            trimmed == "*" ||
            trimmed.any { it.isWhitespace() } ||
            trimmed.contains("://") ||
            trimmed.contains('/') ||
            trimmed.contains('?') ||
            trimmed.contains('#') ||
            trimmed.contains('@') ||
            trimmed.contains(':')
        ) {
            throw CloudSourceException(
                CloudErrorCode.PERMISSION_DENIED,
                "Invalid host allowlist pattern: $pattern",
            )
        }

        if (trimmed.startsWith("*.")) {
            val domain = trimmed.removePrefix("*.")
            val labels = domain.split('.')
            if (domain.contains('*') || labels.size < 2 || labels.any { it.isEmpty() }) {
                throw CloudSourceException(
                    CloudErrorCode.PERMISSION_DENIED,
                    "Invalid wildcard host pattern (requires at least 2 domain labels): $pattern",
                )
            }
        } else {
            val labels = trimmed.split('.')
            if (trimmed.contains('*') || labels.any { it.isEmpty() }) {
                throw CloudSourceException(
                    CloudErrorCode.PERMISSION_DENIED,
                    "Invalid host allowlist pattern: $pattern",
                )
            }
        }
        return trimmed
    }

    fun isAllowed(urlString: String, allowedPatterns: List<String>): Boolean {
        val parsed = runCatching { URL(urlString.trim()) }.getOrNull() ?: return false
        val scheme = parsed.protocol?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") {
            return false
        }
        val host = parsed.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return false
        if (host.any { it.isWhitespace() }) return false

        for (raw in allowedPatterns) {
            val pat = runCatching { validatePattern(raw) }.getOrNull() ?: continue
            if (pat.startsWith("*.")) {
                val domain = pat.removePrefix("*.")
                if (host.endsWith(".$domain") && host.length > domain.length + 1) {
                    return true
                }
            } else if (host == pat) {
                return true
            }
        }
        return false
    }

    fun assertAllowed(urlString: String, allowedPatterns: List<String>) {
        if (!isAllowed(urlString, allowedPatterns)) {
            throw CloudSourceException(
                CloudErrorCode.PERMISSION_DENIED,
                "Host blocked by permissions.hosts: $urlString",
            )
        }
    }

    fun extractApprovedHost(rawServerUrl: String?): String? {
        val trimmed = rawServerUrl?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val parsed = runCatching { URL(trimmed) }.getOrNull() ?: return null
        val scheme = parsed.protocol?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val rawHost = parsed.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
        if (rawHost.startsWith("*.") || rawHost.contains('*')) return null
        return runCatching { validatePattern(rawHost) }.getOrNull()
    }
}

object ExtensionManifestValidator {
    private val ID_REGEX = Regex("^[a-z0-9._-]{3,64}$")
    private val SEMVER_REGEX = Regex("^\\d+\\.\\d+\\.\\d+(?:[-.+][0-9A-Za-z.-]+)?$")

    fun validate(manifest: ExtensionManifest): ExtensionManifest {
        require(manifest.apiVersion == ExtensionManifest.CURRENT_API_VERSION) {
            "Unsupported extension apiVersion: ${manifest.apiVersion} (expected ${ExtensionManifest.CURRENT_API_VERSION})"
        }
        require(ID_REGEX.matches(manifest.id) && !manifest.id.contains("..")) {
            "Invalid extension id '${manifest.id}': must match ^[a-z0-9._-]{3,64}$"
        }
        require(manifest.name.trim().isNotEmpty()) {
            "Extension manifest.name must not be blank"
        }
        require(SEMVER_REGEX.matches(manifest.version.trim())) {
            "Extension manifest.version '${manifest.version}' must be SemVer (MAJOR.MINOR.PATCH)"
        }
        require(manifest.capabilities.isNotEmpty()) {
            "Extension manifest.capabilities must not be empty"
        }
        val requiresSourcePatterns = manifest.capabilities.any {
            it == ExtensionCapability.PLAYLIST || it == ExtensionCapability.TREE
        }
        if (requiresSourcePatterns) {
            require(manifest.urlPatterns.isNotEmpty()) {
                "Extension manifest.urlPatterns must not be empty"
            }
        }
        for (pattern in manifest.urlPatterns) {
            runCatching { Regex(pattern) }.getOrElse { err ->
                throw IllegalArgumentException("Invalid urlPattern regex '$pattern': ${err.message}", err)
            }
        }
        require(manifest.permissions.hosts.isNotEmpty() || manifest.hasServerUrlSetting) {
            "Extension manifest.permissions.hosts must not be empty"
        }
        for (host in manifest.permissions.hosts) {
            HostAllowlistMatcher.validatePattern(host)
        }
        val cleanUpdateUrl = manifest.updateUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanUpdateUrl != null) {
            validateHttpsUpdateUrl(cleanUpdateUrl)
        }
        val cleanEntry = normalizeRelativeEntryPath(manifest.entry)
        val seenKeys = HashSet<String>()
        for (spec in manifest.settings) {
            require(spec.key.trim().isNotEmpty()) { "Setting key must not be blank" }
            require(spec.label.trim().isNotEmpty()) { "Setting label for '${spec.key}' must not be blank" }
            require(seenKeys.add(spec.key)) { "Duplicate setting key '${spec.key}'" }
            if (spec.type == ExtensionSettingType.SELECT) {
                require(spec.options.isNotEmpty()) { "Select setting '${spec.key}' must define options" }
            }
        }
        return manifest.copy(
            name = manifest.name.trim(),
            version = manifest.version.trim(),
            entry = cleanEntry,
            searchKinds = manifest.searchKinds.distinct(),
            updateUrl = cleanUpdateUrl,
        )
    }

    fun validateHttpsUpdateUrl(rawUrl: String): String {
        val clean = rawUrl.trim()
        require(clean.isNotEmpty()) { "Extension updateUrl must not be empty" }
        val parsed = runCatching { URL(clean) }.getOrNull()
            ?: throw IllegalArgumentException("Invalid extension updateUrl: $rawUrl")
        require(parsed.protocol.equals("https", ignoreCase = true)) {
            "Extension updateUrl must use https:// (got ${parsed.protocol})"
        }
        require(!parsed.host.isNullOrBlank()) {
            "Invalid extension updateUrl host: $rawUrl"
        }
        return clean
    }

    fun normalizeRelativeEntryPath(raw: String): String {
        val clean = raw.replace('\\', '/').trim().removePrefix("./")
        require(clean.isNotEmpty() && !clean.startsWith("/") && !clean.contains('\u0000')) {
            "Invalid entry path: $raw"
        }
        val segments = clean.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
            "Unsafe entry path: $raw"
        }
        return segments.joinToString("/")
    }
}

@Serializable
enum class ExtensionPackageFormat {
    BUNDLE,
    SINGLE_FILE,
}

@Serializable
data class ExtensionLogEntry(
    val timestampMs: Long,
    val level: String,
    val message: String,
)

@Serializable
data class ExtensionKvEntry(
    val value: String,
    val expiresAtMs: Long? = null,
)

@Serializable
data class ExtensionState(
    val id: String,
    val enabled: Boolean = false,
    val format: ExtensionPackageFormat = ExtensionPackageFormat.BUNDLE,
    val installedAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val sourceUrl: String? = null,
    val hasIcon: Boolean = false,
    val settings: Map<String, JsonPrimitive> = emptyMap(),
    val cookie: String? = null,
)

data class InstalledExtension(
    val manifest: ExtensionManifest,
    val state: ExtensionState,
    val scriptSource: String,
    val iconPath: String? = null,
) {
    val id: String get() = manifest.id
    val providerId: String get() = manifest.providerId
    val enabled: Boolean get() = state.enabled
}

data class StagedExtensionPackage(
    val manifest: ExtensionManifest,
    val scriptSource: String,
    val iconBytes: ByteArray? = null,
    val format: ExtensionPackageFormat,
    val sourceUrl: String? = null,
)

@Serializable
data class ExtCanHandleResult(
    val matched: Boolean = false,
    val normalizedUrl: String? = null,
    val remoteId: String? = null,
    val kind: String? = null,
)

@Serializable
data class ExtTrack(
    val remoteTrackId: String,
    val relativePath: String? = null,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
    val durationSec: Double? = null,
    val artworkUrl: String? = null,
)

@Serializable
data class ExtSubfolder(
    val id: String,
    val name: String,
    val pathSegments: List<String>? = null,
    val extra: JsonObject? = null,
)

@Serializable
data class ExtCollectionResult(
    val remoteId: String,
    val title: String,
    val tracks: List<ExtTrack> = emptyList(),
    val subfolders: List<ExtSubfolder> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class ExtFolderResult(
    val folderId: String,
    val tracks: List<ExtTrack> = emptyList(),
    val subfolders: List<ExtSubfolder> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class ExtResolveStreamResult(
    val url: String,
    val headers: Map<String, String>? = null,
    val fileExtension: String? = null,
    val mimeType: String? = null,
    val expiresAtMs: Long? = null,
    val fallbackUrls: List<String>? = null,
    val contentLength: Long? = null,
)

data class SearchExtensionDescriptor(
    val id: String,
    val providerId: String,
    val name: String,
    val badgeLabel: String,
    val searchKinds: List<ExtensionSearchKind>,
    val supportsStream: Boolean,
    val supportsCollection: Boolean,
    val supportsCookie: Boolean,
)

sealed interface ExtSearchResultItem {
    val kind: ExtensionSearchKind
    val id: String
    val title: String
    val artist: String?
    val album: String?
    val durationSec: Double?
    val artworkUrl: String?

    data class TrackItem(
        val remoteTrackId: String,
        override val title: String,
        override val artist: String? = null,
        override val album: String? = null,
        override val durationSec: Double? = null,
        override val artworkUrl: String? = null,
        val relativePath: String? = null,
    ) : ExtSearchResultItem {
        override val kind: ExtensionSearchKind get() = ExtensionSearchKind.TRACK
        override val id: String get() = remoteTrackId
    }

    data class CollectionItem(
        override val kind: ExtensionSearchKind,
        val remoteId: String,
        override val title: String,
        override val artist: String? = null,
        override val album: String? = null,
        override val durationSec: Double? = null,
        override val artworkUrl: String? = null,
        val sourceUrl: String? = null,
        val trackCount: Int? = null,
    ) : ExtSearchResultItem {
        override val id: String get() = remoteId
    }
}

data class ExtSearchPage(
    val items: List<ExtSearchResultItem>,
    val nextCursor: String? = null,
)

enum class ExtQrLoginStatus(val wireName: String) {
    PENDING("pending"),
    SCANNED("scanned"),
    CONFIRMED("confirmed"),
    EXPIRED("expired"),
    ERROR("error"),
    ;

    companion object {
        fun fromWire(raw: String?): ExtQrLoginStatus? {
            val clean = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wireName == clean }
        }
    }
}

data class ExtQrLoginSession(
    val sessionId: String,
    val qrContent: String,
    val expiresAtMs: Long? = null,
    val pollIntervalMs: Int = 2_000,
    val message: String? = null,
)

data class ExtQrLoginPollResult(
    val status: ExtQrLoginStatus,
    val cookie: String? = null,
    val accountName: String? = null,
    val message: String? = null,
)

/**
 * SemVer 2.0 parser and comparator for extension versions (`MAJOR.MINOR.PATCH[-prerelease][+build]`).
 */
data class ExtensionSemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<ExtensionSemVer> {

    override fun compareTo(other: ExtensionSemVer): Int {
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
                numA != null -> -1
                numB != null -> 1
                else -> partA.compareTo(partB)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    companion object {
        private val CORE_REGEX = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$")

        fun parse(raw: String): ExtensionSemVer? {
            val trimmed = raw.trim().removePrefix("v").removePrefix("V")
            if (trimmed.isEmpty()) return null
            val withoutBuild = trimmed.substringBefore('+')
            val dashIdx = withoutBuild.indexOf('-')
            val core = if (dashIdx >= 0) withoutBuild.substring(0, dashIdx) else withoutBuild
            val pre = if (dashIdx >= 0) {
                withoutBuild.substring(dashIdx + 1).takeIf { it.isNotBlank() } ?: return null
            } else {
                null
            }
            val match = CORE_REGEX.matchEntire(core) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            return ExtensionSemVer(major, minor, patch, pre)
        }

        fun isStrictlyGreater(remote: String, installed: String): Boolean {
            val remoteVer = parse(remote) ?: return false
            val installedVer = parse(installed) ?: return false
            return remoteVer > installedVer
        }
    }
}

data class ExtensionPermissionDiff(
    val addedHosts: List<String> = emptyList(),
    val addedCapabilities: List<ExtensionCapability> = emptyList(),
) {
    val hasEscalation: Boolean
        get() = addedHosts.isNotEmpty() || addedCapabilities.isNotEmpty()
}

object ExtensionUpdateGuard {
    fun evaluatePermissionEscalation(
        installed: ExtensionManifest,
        candidate: ExtensionManifest,
    ): ExtensionPermissionDiff {
        val installedCaps = installed.capabilities.toSet()
        val addedCapabilities = candidate.capabilities.distinct().filter { it !in installedCaps }

        val installedHosts = installed.permissions.hosts
            .mapNotNull { runCatching { HostAllowlistMatcher.validatePattern(it) }.getOrNull() }
        val addedHosts = candidate.permissions.hosts
            .mapNotNull { runCatching { HostAllowlistMatcher.validatePattern(it) }.getOrNull() }
            .distinct()
            .filterNot { candidateHost -> isHostCovered(candidateHost, installedHosts) }

        return ExtensionPermissionDiff(
            addedHosts = addedHosts,
            addedCapabilities = addedCapabilities,
        )
    }

    private fun isHostCovered(candidatePattern: String, installedPatterns: List<String>): Boolean {
        if (candidatePattern in installedPatterns) return true
        val candidateDomain = if (candidatePattern.startsWith("*.")) {
            candidatePattern.removePrefix("*.")
        } else {
            candidatePattern
        }
        return installedPatterns.any { existing ->
            if (!existing.startsWith("*.")) return@any false
            val allowedDomain = existing.removePrefix("*.")
            if (candidatePattern.startsWith("*.")) {
                candidateDomain == allowedDomain || candidateDomain.endsWith(".$allowedDomain")
            } else {
                candidateDomain.endsWith(".$allowedDomain") && candidateDomain.length > allowedDomain.length + 1
            }
        }
    }
}

@Serializable
data class ExtensionUpdatePrefs(
    val autoUpdateEnabled: Boolean = true,
    val lastCheckEpochMs: Long = 0L,
)

data class PendingExtensionUpdate(
    val installed: InstalledExtension,
    val staged: StagedExtensionPackage,
    val diff: ExtensionPermissionDiff,
    val updateUrl: String,
)

sealed interface ExtensionUpdateCheckOutcome {
    data object Checking : ExtensionUpdateCheckOutcome
    data object UpToDate : ExtensionUpdateCheckOutcome
    data object NoUpdateUrl : ExtensionUpdateCheckOutcome
    data class Applied(val fromVersion: String, val toVersion: String) : ExtensionUpdateCheckOutcome
    data class ReviewRequired(val pending: PendingExtensionUpdate) : ExtensionUpdateCheckOutcome
    data class Error(val message: String) : ExtensionUpdateCheckOutcome
}

data class ExtensionBatchUpdateSummary(
    val checkedCount: Int,
    val appliedCount: Int,
    val reviewRequiredCount: Int,
    val failedCount: Int,
)
