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
        require(manifest.permissions.hosts.isNotEmpty()) {
            "Extension manifest.permissions.hosts must not be empty"
        }
        for (host in manifest.permissions.hosts) {
            HostAllowlistMatcher.validatePattern(host)
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
        )
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
