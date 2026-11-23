using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace MusicoWindows.Extensions;

[JsonConverter(typeof(ExtensionCapabilityJsonConverter))]
public enum ExtensionCapability
{
    Playlist,
    Tree,
    Stream,
    Cookie,
    Proxy,
    Lyrics
}

[JsonConverter(typeof(ExtensionSettingTypeJsonConverter))]
public enum ExtensionSettingType
{
    Boolean,
    String,
    Select
}

public sealed class ExtensionCapabilityJsonConverter : JsonConverter<ExtensionCapability>
{
    public override ExtensionCapability Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        string? raw = reader.GetString();
        return raw?.Trim().ToLowerInvariant() switch
        {
            "playlist" => ExtensionCapability.Playlist,
            "tree" => ExtensionCapability.Tree,
            "stream" => ExtensionCapability.Stream,
            "cookie" => ExtensionCapability.Cookie,
            "proxy" => ExtensionCapability.Proxy,
            "lyrics" => ExtensionCapability.Lyrics,
            _ => throw new JsonException($"Unsupported extension capability: '{raw}'.")
        };
    }

    public override void Write(Utf8JsonWriter writer, ExtensionCapability value, JsonSerializerOptions options)
    {
        writer.WriteStringValue(value switch
        {
            ExtensionCapability.Playlist => "playlist",
            ExtensionCapability.Tree => "tree",
            ExtensionCapability.Stream => "stream",
            ExtensionCapability.Cookie => "cookie",
            ExtensionCapability.Proxy => "proxy",
            ExtensionCapability.Lyrics => "lyrics",
            _ => value.ToString().ToLowerInvariant()
        });
    }
}

public sealed class ExtensionSettingTypeJsonConverter : JsonConverter<ExtensionSettingType>
{
    public override ExtensionSettingType Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        string? raw = reader.GetString();
        return raw?.Trim().ToLowerInvariant() switch
        {
            "boolean" => ExtensionSettingType.Boolean,
            "string" => ExtensionSettingType.String,
            "select" => ExtensionSettingType.Select,
            _ => throw new JsonException($"Unsupported extension setting type: '{raw}'.")
        };
    }

    public override void Write(Utf8JsonWriter writer, ExtensionSettingType value, JsonSerializerOptions options)
    {
        writer.WriteStringValue(value switch
        {
            ExtensionSettingType.Boolean => "boolean",
            ExtensionSettingType.String => "string",
            ExtensionSettingType.Select => "select",
            _ => value.ToString().ToLowerInvariant()
        });
    }
}

[JsonConverter(typeof(ExtensionSettingOptionJsonConverter))]
public sealed record ExtensionSettingOption(string Value, string Label);

public sealed class ExtensionSettingOptionJsonConverter : JsonConverter<ExtensionSettingOption>
{
    public override ExtensionSettingOption Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        if (reader.TokenType == JsonTokenType.String)
        {
            string val = reader.GetString() ?? string.Empty;
            return new ExtensionSettingOption(val, val);
        }

        if (reader.TokenType != JsonTokenType.StartObject)
        {
            throw new JsonException("Extension setting option must be a string or an object with 'value' and 'label'.");
        }

        string? value = null;
        string? label = null;
        while (reader.Read())
        {
            if (reader.TokenType == JsonTokenType.EndObject)
            {
                if (value is null)
                {
                    throw new JsonException("Extension setting option object is missing 'value'.");
                }
                return new ExtensionSettingOption(value, string.IsNullOrWhiteSpace(label) ? value : label);
            }

            if (reader.TokenType != JsonTokenType.PropertyName)
            {
                throw new JsonException("Unexpected token in extension setting option.");
            }

            string prop = reader.GetString() ?? string.Empty;
            if (!reader.Read()) break;

            if (string.Equals(prop, "value", StringComparison.OrdinalIgnoreCase))
            {
                value = reader.TokenType == JsonTokenType.String
                    ? reader.GetString()
                    : JsonElement.ParseValue(ref reader).ToString();
            }
            else if (string.Equals(prop, "label", StringComparison.OrdinalIgnoreCase))
            {
                label = reader.TokenType == JsonTokenType.String
                    ? reader.GetString()
                    : JsonElement.ParseValue(ref reader).ToString();
            }
            else
            {
                reader.Skip();
            }
        }

        throw new JsonException("Unterminated extension setting option object.");
    }

    public override void Write(Utf8JsonWriter writer, ExtensionSettingOption value, JsonSerializerOptions options)
    {
        writer.WriteStartObject();
        writer.WriteString("value", value.Value);
        writer.WriteString("label", value.Label);
        writer.WriteEndObject();
    }
}

public sealed class ExtensionSettingSpec
{
    public string Key { get; set; } = string.Empty;
    public string Label { get; set; } = string.Empty;
    public ExtensionSettingType Type { get; set; } = ExtensionSettingType.String;
    public JsonElement? Default { get; set; }
    public List<ExtensionSettingOption>? Options { get; set; }

    public bool GetDefaultBool()
    {
        if (Default is not JsonElement el) return false;
        return el.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            JsonValueKind.String when bool.TryParse(el.GetString(), out bool b) => b,
            _ => false
        };
    }

    public string GetDefaultString()
    {
        if (Default is not JsonElement el)
        {
            return Type == ExtensionSettingType.Select && Options is { Count: > 0 }
                ? Options[0].Value
                : string.Empty;
        }

        return el.ValueKind switch
        {
            JsonValueKind.String => el.GetString() ?? string.Empty,
            JsonValueKind.Null or JsonValueKind.Undefined => string.Empty,
            JsonValueKind.True => "true",
            JsonValueKind.False => "false",
            _ => el.ToString()
        };
    }
}

public sealed class ExtensionPermissions
{
    public List<string> Hosts { get; set; } = new();
}

public sealed class ExtensionManifest
{
    public const int CurrentApiVersion = 1;
    public const int MaxBadgeLength = 16;

    public int ApiVersion { get; set; }
    public string Id { get; set; } = string.Empty;
    public string Name { get; set; } = string.Empty;
    public string Version { get; set; } = string.Empty;
    public string? Author { get; set; }
    public string? Description { get; set; }
    public string? BadgeLabel { get; set; }
    public string? Entry { get; set; }
    public List<ExtensionCapability> Capabilities { get; set; } = new();
    public List<string> UrlPatterns { get; set; } = new();
    public ExtensionPermissions Permissions { get; set; } = new();
    public List<ExtensionSettingSpec>? Settings { get; set; }

    [JsonIgnore]
    public string ProviderId => $"ext.{Id}";

    [JsonIgnore]
    public string EffectiveEntry => string.IsNullOrWhiteSpace(Entry) ? "index.js" : Entry.Trim();

    [JsonIgnore]
    public string EffectiveBadgeLabel
    {
        get
        {
            string raw = !string.IsNullOrWhiteSpace(BadgeLabel) ? BadgeLabel.Trim() : Name.Trim();
            if (raw.Length <= MaxBadgeLength) return raw;
            return raw[..MaxBadgeLength];
        }
    }

    public bool HasCapability(ExtensionCapability capability) =>
        Capabilities.Contains(capability);
}

public static class HostAllowlistMatcher
{
    private static readonly Regex HostLabelRegex = new(
        @"^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$",
        RegexOptions.Compiled);

    /// <summary>
    /// Validates and normalizes an extension host allowlist pattern.
    /// Allows exact hostnames or wildcard patterns of the form <c>*.domain.tld</c> with at least two domain labels.
    /// </summary>
    public static string ValidatePattern(string? pattern)
    {
        if (string.IsNullOrWhiteSpace(pattern))
        {
            throw new InvalidDataException("Host allowlist pattern cannot be empty.");
        }

        string trimmed = pattern.Trim().ToLowerInvariant().TrimEnd('.');
        if (trimmed.Length == 0 ||
            trimmed == "*" ||
            trimmed.Contains("://", StringComparison.Ordinal) ||
            trimmed.IndexOfAny(['/', '\\', '?', '#', '@', ':', ' ']) >= 0)
        {
            throw new InvalidDataException($"Invalid host allowlist pattern: '{pattern}'.");
        }

        string domainPart;
        if (trimmed.StartsWith("*.", StringComparison.Ordinal))
        {
            domainPart = trimmed[2..];
            if (domainPart.Contains('*', StringComparison.Ordinal))
            {
                throw new InvalidDataException($"Invalid wildcard host pattern: '{pattern}'.");
            }
            string[] labels = domainPart.Split('.');
            if (labels.Length < 2 || labels.Any(l => !HostLabelRegex.IsMatch(l)))
            {
                throw new InvalidDataException(
                    $"Wildcard host pattern '{pattern}' must include at least two domain labels (e.g. '*.example.com').");
            }
        }
        else
        {
            if (trimmed.Contains('*', StringComparison.Ordinal))
            {
                throw new InvalidDataException($"Invalid host allowlist pattern: '{pattern}'.");
            }
            string[] labels = trimmed.Split('.');
            if (labels.Length == 0 || labels.Any(l => !HostLabelRegex.IsMatch(l)))
            {
                throw new InvalidDataException($"Invalid hostname in allowlist: '{pattern}'.");
            }
        }

        return trimmed;
    }

    /// <summary>
    /// Returns true if <paramref name="urlString"/> is an HTTP/HTTPS URL whose host matches any pattern in <paramref name="allowedPatterns"/>.
    /// </summary>
    public static bool IsAllowed(string? urlString, IEnumerable<string>? allowedPatterns, out Uri? parsedUri)
    {
        parsedUri = null;
        if (string.IsNullOrWhiteSpace(urlString) || allowedPatterns is null)
        {
            return false;
        }

        if (!Uri.TryCreate(urlString.Trim(), UriKind.Absolute, out var uri))
        {
            return false;
        }

        if (!string.Equals(uri.Scheme, Uri.UriSchemeHttp, StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(uri.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        string host = (uri.IdnHost ?? uri.Host).Trim().ToLowerInvariant().TrimEnd('.');
        if (host.Length == 0)
        {
            return false;
        }

        foreach (string rawPattern in allowedPatterns)
        {
            if (string.IsNullOrWhiteSpace(rawPattern)) continue;
            string pat = rawPattern.Trim().ToLowerInvariant().TrimEnd('.');
            if (pat.Length == 0) continue;

            if (pat.StartsWith("*.", StringComparison.Ordinal))
            {
                string domain = pat[2..];
                if (domain.Split('.', StringSplitOptions.RemoveEmptyEntries).Length < 2)
                {
                    continue;
                }
                if (host.EndsWith("." + domain, StringComparison.Ordinal) && host.Length > domain.Length + 1)
                {
                    parsedUri = uri;
                    return true;
                }
            }
            else if (string.Equals(host, pat, StringComparison.Ordinal))
            {
                parsedUri = uri;
                return true;
            }
        }

        return false;
    }

    public static bool IsAllowed(string? urlString, IEnumerable<string>? allowedPatterns) =>
        IsAllowed(urlString, allowedPatterns, out _);
}

public static class ExtensionManifestValidator
{
    private static readonly Regex IdRegex = new(@"^[a-z0-9._-]{3,64}$", RegexOptions.Compiled);
    private static readonly Regex SemVerRegex = new(@"^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$", RegexOptions.Compiled);

    public static void Validate(ExtensionManifest manifest)
    {
        ArgumentNullException.ThrowIfNull(manifest);

        if (manifest.ApiVersion != ExtensionManifest.CurrentApiVersion)
        {
            throw new InvalidDataException($"Unsupported extension apiVersion '{manifest.ApiVersion}'. Expected {ExtensionManifest.CurrentApiVersion}.");
        }

        if (string.IsNullOrWhiteSpace(manifest.Id) ||
            !IdRegex.IsMatch(manifest.Id) ||
            manifest.Id.Contains("..", StringComparison.Ordinal))
        {
            throw new InvalidDataException($"Invalid extension id '{manifest.Id}'. Expected 3-64 chars matching [a-z0-9._-].");
        }

        if (string.IsNullOrWhiteSpace(manifest.Name))
        {
            throw new InvalidDataException("Extension manifest.name is required.");
        }

        if (string.IsNullOrWhiteSpace(manifest.Version) || !SemVerRegex.IsMatch(manifest.Version.Trim()))
        {
            throw new InvalidDataException($"Invalid extension version '{manifest.Version}'. Expected SemVer (MAJOR.MINOR.PATCH).");
        }

        string entry = manifest.EffectiveEntry.Replace('\\', '/');
        if (entry.StartsWith('/') ||
            entry.Contains(':', StringComparison.Ordinal) ||
            entry.Split('/').Any(seg => seg is "" or "." or ".."))
        {
            throw new InvalidDataException($"Invalid extension entry path '{manifest.Entry}'.");
        }

        if (manifest.Capabilities is null || manifest.Capabilities.Count == 0)
        {
            throw new InvalidDataException("Extension manifest.capabilities must declare at least one capability.");
        }

        bool hasSourceCap = manifest.HasCapability(ExtensionCapability.Playlist) ||
                            manifest.HasCapability(ExtensionCapability.Tree) ||
                            manifest.HasCapability(ExtensionCapability.Stream);

        if (!manifest.HasCapability(ExtensionCapability.Playlist) &&
            !manifest.HasCapability(ExtensionCapability.Tree) &&
            !manifest.HasCapability(ExtensionCapability.Lyrics))
        {
            throw new InvalidDataException("Extension manifest.capabilities must include 'playlist', 'tree', or 'lyrics'.");
        }

        if (hasSourceCap && (manifest.UrlPatterns is null || manifest.UrlPatterns.Count == 0))
        {
            throw new InvalidDataException("Extension manifest.urlPatterns must include at least one regex pattern.");
        }

        foreach (string pattern in manifest.UrlPatterns ?? [])
        {
            if (string.IsNullOrWhiteSpace(pattern))
            {
                throw new InvalidDataException("Extension urlPatterns entries cannot be empty.");
            }
            try
            {
                _ = new Regex(pattern, RegexOptions.IgnoreCase | RegexOptions.CultureInvariant, TimeSpan.FromSeconds(1));
            }
            catch (ArgumentException ex)
            {
                throw new InvalidDataException($"Invalid regex in extension urlPatterns: '{pattern}'.", ex);
            }
        }

        if (manifest.Permissions?.Hosts is null || manifest.Permissions.Hosts.Count == 0)
        {
            throw new InvalidDataException("Extension manifest.permissions.hosts must declare at least one allowed host.");
        }

        foreach (string hostPattern in manifest.Permissions.Hosts)
        {
            HostAllowlistMatcher.ValidatePattern(hostPattern);
        }

        if (manifest.Settings is not null)
        {
            var seenKeys = new HashSet<string>(StringComparer.Ordinal);
            foreach (var setting in manifest.Settings)
            {
                if (string.IsNullOrWhiteSpace(setting.Key))
                {
                    throw new InvalidDataException("Extension setting key cannot be empty.");
                }
                if (!seenKeys.Add(setting.Key))
                {
                    throw new InvalidDataException($"Duplicate extension setting key '{setting.Key}'.");
                }
                if (string.IsNullOrWhiteSpace(setting.Label))
                {
                    throw new InvalidDataException($"Extension setting '{setting.Key}' must specify a label.");
                }
                if (setting.Type == ExtensionSettingType.Select &&
                    (setting.Options is null || setting.Options.Count == 0))
                {
                    throw new InvalidDataException($"Select setting '{setting.Key}' must specify at least one option.");
                }
            }
        }
    }
}

public enum ExtensionPackageFormat
{
    Bundle,
    SingleFile
}

public sealed class ExtensionStateRecord
{
    public string Id { get; set; } = string.Empty;
    public bool Enabled { get; set; }
    public bool PendingPermissionReview { get; set; }
    public ExtensionPackageFormat Format { get; set; }
    public string? SourceLocation { get; set; }
    public DateTimeOffset InstalledUtc { get; set; }
    public Dictionary<string, JsonElement> Settings { get; set; } = new(StringComparer.Ordinal);
}

public enum ExtensionLogLevel
{
    Info,
    Warn,
    Error
}

public sealed record ExtensionLogEntry(
    long TimestampMs,
    ExtensionLogLevel Level,
    string Message);

public sealed record ExtensionKvEntry(
    string Value,
    long? ExpiresAtMs);

public sealed record InstalledExtension(
    ExtensionManifest Manifest,
    ExtensionStateRecord State,
    string DirectoryPath,
    string EntryScriptPath,
    string? IconPath)
{
    public string Id => Manifest.Id;
    public string ProviderId => Manifest.ProviderId;
    public bool IsEnabled => State.Enabled && !State.PendingPermissionReview;
    public bool PendingPermissionReview => State.PendingPermissionReview;
}

public static class ExtensionJson
{
    public static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
    };
}

// JS Hook DTOs

public sealed class ExtCanHandleInput
{
    public string RawText { get; set; } = string.Empty;
    public string ExtractedUrl { get; set; } = string.Empty;
}

public sealed class ExtCanHandleResult
{
    public bool Matched { get; set; }
    public string? NormalizedUrl { get; set; }
    public string? RemoteId { get; set; }
    public string? Kind { get; set; }
}

public sealed class ExtFetchCollectionRequest
{
    public string Url { get; set; } = string.Empty;
    public string? Cookie { get; set; }
    public string? Cursor { get; set; }
}

public sealed class ExtFetchFolderRequest
{
    public string FolderId { get; set; } = string.Empty;
    public List<string> PathSegments { get; set; } = new();
    public int Depth { get; set; }
    public string? Cookie { get; set; }
    public string? Cursor { get; set; }
    public JsonElement? Extra { get; set; }
}

public sealed class ExtResolveStreamRequest
{
    public string RemoteTrackId { get; set; } = string.Empty;
    public string? RelativePath { get; set; }
    public string? Cookie { get; set; }
    public bool ForceRefresh { get; set; }
}

public sealed class ExtTrackDto
{
    public string? RemoteTrackId { get; set; }
    public string? RelativePath { get; set; }
    public string? Title { get; set; }
    public string? Artist { get; set; }
    public string? Album { get; set; }
    public double? TrackNumber { get; set; }
    public double? DurationSec { get; set; }
    public string? ArtworkUrl { get; set; }
}

public sealed class ExtSubfolderDto
{
    public string? Id { get; set; }
    public string? Name { get; set; }
    public List<string>? PathSegments { get; set; }
    public JsonElement? Extra { get; set; }
}

public sealed class ExtCollectionResult
{
    public string? RemoteId { get; set; }
    public string? Title { get; set; }
    public List<ExtTrackDto>? Tracks { get; set; }
    public List<ExtSubfolderDto>? Subfolders { get; set; }
    public string? NextCursor { get; set; }
}

public sealed class ExtFolderResult
{
    public string? FolderId { get; set; }
    public List<ExtTrackDto>? Tracks { get; set; }
    public List<ExtSubfolderDto>? Subfolders { get; set; }
    public string? NextCursor { get; set; }
}

public sealed class ExtStreamResult
{
    public string? Url { get; set; }
    public Dictionary<string, string>? Headers { get; set; }
    public string? FileExtension { get; set; }
    public string? MimeType { get; set; }
    public double? ExpiresAtMs { get; set; }
    public List<string>? FallbackUrls { get; set; }
    public long? ContentLength { get; set; }
}
