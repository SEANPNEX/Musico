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
    Lyrics,
    Search,
    QrLogin
}

[JsonConverter(typeof(ExtensionSearchKindJsonConverter))]
public enum ExtensionSearchKind
{
    Track,
    Playlist,
    Album
}

[JsonConverter(typeof(ExtensionSettingTypeJsonConverter))]
public enum ExtensionSettingType
{
    Boolean,
    String,
    Select,
    ServerUrl
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
            "search" => ExtensionCapability.Search,
            "qr_login" => ExtensionCapability.QrLogin,
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
            ExtensionCapability.Search => "search",
            ExtensionCapability.QrLogin => "qr_login",
            _ => value.ToString().ToLowerInvariant()
        });
    }
}

public static class ExtensionSearchKindExtensions
{
    public static string ToWireString(this ExtensionSearchKind kind) => kind switch
    {
        ExtensionSearchKind.Track => "track",
        ExtensionSearchKind.Playlist => "playlist",
        ExtensionSearchKind.Album => "album",
        _ => "track"
    };
}

public sealed class ExtensionSearchKindJsonConverter : JsonConverter<ExtensionSearchKind>
{
    public override ExtensionSearchKind Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        string? raw = reader.GetString();
        return raw?.Trim().ToLowerInvariant() switch
        {
            "track" => ExtensionSearchKind.Track,
            "playlist" => ExtensionSearchKind.Playlist,
            "album" => ExtensionSearchKind.Album,
            _ => throw new JsonException($"Unsupported extension searchKind: '{raw}'.")
        };
    }

    public override void Write(Utf8JsonWriter writer, ExtensionSearchKind value, JsonSerializerOptions options)
    {
        writer.WriteStringValue(value.ToWireString());
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
            "server_url" => ExtensionSettingType.ServerUrl,
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
            ExtensionSettingType.ServerUrl => "server_url",
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
    public string? UpdateUrl { get; set; }
    public List<ExtensionCapability> Capabilities { get; set; } = new();
    public List<ExtensionSearchKind>? SearchKinds { get; set; }
    public List<string> UrlPatterns { get; set; } = new();
    public ExtensionPermissions Permissions { get; set; } = new();
    public List<ExtensionSettingSpec>? Settings { get; set; }

    [JsonIgnore]
    public IReadOnlyList<ExtensionSearchKind> EffectiveSearchKinds =>
        SearchKinds is { Count: > 0 }
            ? SearchKinds.Distinct().ToList()
            : [ExtensionSearchKind.Track, ExtensionSearchKind.Playlist, ExtensionSearchKind.Album];

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

    [JsonIgnore]
    public bool SupportsCookieAuth =>
        HasCapability(ExtensionCapability.Cookie) || HasCapability(ExtensionCapability.QrLogin);

    [JsonIgnore]
    public bool SupportsQrLogin =>
        HasCapability(ExtensionCapability.QrLogin);

    [JsonIgnore]
    public bool HasServerUrlSetting =>
        Settings?.Any(s => s.Type == ExtensionSettingType.ServerUrl) ?? false;

    public IReadOnlyList<string> GetEffectiveAllowedHosts(Func<string, string?> settingJsonLookup)
    {
        var combined = new List<string>(Permissions?.Hosts ?? []);
        var seen = new HashSet<string>(
            combined.Select(h => (h ?? string.Empty).Trim().ToLowerInvariant().TrimEnd('.')),
            StringComparer.Ordinal);

        if (Settings is not null)
        {
            foreach (var spec in Settings)
            {
                if (spec.Type != ExtensionSettingType.ServerUrl) continue;
                string? rawUrl = null;
                string? rawJson = settingJsonLookup(spec.Key);
                if (!string.IsNullOrWhiteSpace(rawJson))
                {
                    try
                    {
                        using var doc = JsonDocument.Parse(rawJson);
                        if (doc.RootElement.ValueKind == JsonValueKind.String)
                        {
                            rawUrl = doc.RootElement.GetString();
                        }
                    }
                    catch
                    {
                        rawUrl = null;
                    }
                }
                rawUrl ??= spec.GetDefaultString();
                string? approved = HostAllowlistMatcher.ExtractApprovedHost(rawUrl);
                if (approved is not null && seen.Add(approved))
                {
                    combined.Add(approved);
                }
            }
        }

        return combined;
    }
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

    public static string? ExtractApprovedHost(string? rawServerUrl)
    {
        if (string.IsNullOrWhiteSpace(rawServerUrl)) return null;
        if (!Uri.TryCreate(rawServerUrl.Trim(), UriKind.Absolute, out var uri)) return null;
        if (!string.Equals(uri.Scheme, Uri.UriSchemeHttp, StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(uri.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }
        string host = (uri.IdnHost ?? uri.Host).Trim().ToLowerInvariant().TrimEnd('.');
        if (string.IsNullOrEmpty(host) || host.StartsWith("*.", StringComparison.Ordinal) || host.Contains('*', StringComparison.Ordinal))
        {
            return null;
        }
        try
        {
            return ValidatePattern(host);
        }
        catch
        {
            return null;
        }
    }
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

        if (!string.IsNullOrWhiteSpace(manifest.UpdateUrl))
        {
            if (!Uri.TryCreate(manifest.UpdateUrl.Trim(), UriKind.Absolute, out var updateUri) ||
                !string.Equals(updateUri.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidDataException(
                    $"Invalid extension updateUrl '{manifest.UpdateUrl}'. Expected an https:// URL.");
            }
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

        bool hasCollectionCap = manifest.HasCapability(ExtensionCapability.Playlist) ||
                                manifest.HasCapability(ExtensionCapability.Tree);

        if (!hasCollectionCap &&
            !manifest.HasCapability(ExtensionCapability.Lyrics) &&
            !manifest.HasCapability(ExtensionCapability.Search))
        {
            throw new InvalidDataException("Extension manifest.capabilities must include 'playlist', 'tree', 'lyrics', or 'search'.");
        }

        if (hasCollectionCap && (manifest.UrlPatterns is null || manifest.UrlPatterns.Count == 0))
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

        if ((manifest.Permissions?.Hosts is null || manifest.Permissions.Hosts.Count == 0) &&
            !manifest.HasServerUrlSetting)
        {
            throw new InvalidDataException("Extension manifest.permissions.hosts must declare at least one allowed host.");
        }

        foreach (string hostPattern in manifest.Permissions?.Hosts ?? [])
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
    public string? SourceUrl { get; set; }
    public DateTimeOffset InstalledUtc { get; set; }
    public string? Cookie { get; set; }
    public Dictionary<string, JsonElement> Settings { get; set; } = new(StringComparer.Ordinal);
}

public enum ExtensionUpdateOutcome
{
    UpToDate,
    AppliedSilently,
    StagedForReview,
    SkippedNoUrl,
    Failed
}

public sealed record ExtensionUpdateItemResult(
    string ExtensionId,
    string? UpdateUrl,
    ExtensionUpdateOutcome Outcome,
    string? InstalledVersion = null,
    string? RemoteVersion = null,
    string? ErrorMessage = null);

public sealed record ExtensionUpdateCheckSummary(
    bool WasSkippedByThrottleOrSetting,
    IReadOnlyList<ExtensionUpdateItemResult> Results);

public sealed record ExtensionPermissionEscalation(
    IReadOnlyList<ExtensionCapability> AddedCapabilities,
    IReadOnlyList<string> AddedHosts)
{
    public bool HasEscalation => AddedCapabilities.Count > 0 || AddedHosts.Count > 0;
}

public static class ExtensionPermissionAnalyzer
{
    public static ExtensionPermissionEscalation AnalyzeEscalation(
        ExtensionManifest current,
        ExtensionManifest next)
    {
        ArgumentNullException.ThrowIfNull(current);
        ArgumentNullException.ThrowIfNull(next);

        var currentCaps = new HashSet<ExtensionCapability>(current.Capabilities ?? []);
        var addedCaps = (next.Capabilities ?? [])
            .Distinct()
            .Where(c => !currentCaps.Contains(c))
            .ToList();

        var currentHosts = (current.Permissions?.Hosts ?? [])
            .Select(NormalizeHostPattern)
            .Where(h => h.Length > 0)
            .ToList();

        var addedHosts = new List<string>();
        var seenAdded = new HashSet<string>(StringComparer.Ordinal);
        foreach (string rawHost in next.Permissions?.Hosts ?? [])
        {
            string normalized = NormalizeHostPattern(rawHost);
            if (normalized.Length == 0 || !seenAdded.Add(normalized))
            {
                continue;
            }

            if (!IsHostCoveredByAny(normalized, currentHosts))
            {
                addedHosts.Add(normalized);
            }
        }

        return new ExtensionPermissionEscalation(addedCaps, addedHosts);
    }

    private static string NormalizeHostPattern(string? pattern) =>
        string.IsNullOrWhiteSpace(pattern)
            ? string.Empty
            : pattern.Trim().ToLowerInvariant().TrimEnd('.');

    private static bool IsHostCoveredByAny(string candidate, IReadOnlyList<string> existingPatterns)
    {
        foreach (string existing in existingPatterns)
        {
            if (string.Equals(candidate, existing, StringComparison.Ordinal))
            {
                return true;
            }

            if (existing.StartsWith("*.", StringComparison.Ordinal))
            {
                string domain = existing[2..];
                if (domain.Length == 0) continue;

                if (candidate.StartsWith("*.", StringComparison.Ordinal))
                {
                    string candDomain = candidate[2..];
                    if (candDomain.EndsWith("." + domain, StringComparison.Ordinal) &&
                        candDomain.Length > domain.Length + 1)
                    {
                        return true;
                    }
                }
                else if (candidate.EndsWith("." + domain, StringComparison.Ordinal) &&
                         candidate.Length > domain.Length + 1)
                {
                    return true;
                }
            }
        }

        return false;
    }
}

public static class ExtensionVersionComparer
{
    private static readonly Regex StrictSemVerRegex = new(
        @"^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+([0-9A-Za-z.-]+))?$",
        RegexOptions.Compiled);

    public static bool TryParse(
        string? raw,
        out (int Major, int Minor, int Patch, string? PreRelease) parsed)
    {
        parsed = default;
        if (string.IsNullOrWhiteSpace(raw)) return false;
        Match m = StrictSemVerRegex.Match(raw.Trim());
        if (!m.Success) return false;

        if (!int.TryParse(m.Groups[1].Value, System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out int major) ||
            !int.TryParse(m.Groups[2].Value, System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out int minor) ||
            !int.TryParse(m.Groups[3].Value, System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out int patch))
        {
            return false;
        }

        string? pre = m.Groups[4].Success ? m.Groups[4].Value : null;
        parsed = (major, minor, patch, pre);
        return true;
    }

    public static int Compare(string? left, string? right)
    {
        if (!TryParse(left, out var l))
            throw new FormatException($"Invalid SemVer '{left}'.");
        if (!TryParse(right, out var r))
            throw new FormatException($"Invalid SemVer '{right}'.");

        int cmp = l.Major.CompareTo(r.Major);
        if (cmp != 0) return cmp;
        cmp = l.Minor.CompareTo(r.Minor);
        if (cmp != 0) return cmp;
        cmp = l.Patch.CompareTo(r.Patch);
        if (cmp != 0) return cmp;

        bool lHasPre = !string.IsNullOrEmpty(l.PreRelease);
        bool rHasPre = !string.IsNullOrEmpty(r.PreRelease);
        if (!lHasPre && !rHasPre) return 0;
        if (!lHasPre) return 1;
        if (!rHasPre) return -1;

        return ComparePreRelease(l.PreRelease!, r.PreRelease!);
    }

    public static bool IsStrictlyNewer(string? remoteVersion, string? installedVersion)
    {
        if (!TryParse(remoteVersion, out _) || !TryParse(installedVersion, out _))
        {
            return false;
        }
        return Compare(remoteVersion, installedVersion) > 0;
    }

    private static int ComparePreRelease(string left, string right)
    {
        string[] lp = left.Split('.');
        string[] rp = right.Split('.');
        int count = Math.Min(lp.Length, rp.Length);

        for (int i = 0; i < count; i++)
        {
            string l = lp[i];
            string r = rp[i];
            bool lNum = int.TryParse(l, System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out int li) && IsAsciiDigits(l);
            bool rNum = int.TryParse(r, System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out int ri) && IsAsciiDigits(r);

            if (lNum && rNum)
            {
                int nc = li.CompareTo(ri);
                if (nc != 0) return nc;
            }
            else if (lNum != rNum)
            {
                return lNum ? -1 : 1;
            }
            else
            {
                int sc = string.CompareOrdinal(l, r);
                if (sc != 0) return sc;
            }
        }

        return lp.Length.CompareTo(rp.Length);
    }

    private static bool IsAsciiDigits(string s)
    {
        if (s.Length == 0) return false;
        foreach (char c in s)
        {
            if (c is < '0' or > '9') return false;
        }
        return true;
    }
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

public sealed class ExtSearchRequest
{
    public string Query { get; set; } = string.Empty;
    public string? Kind { get; set; }
    public string? Cursor { get; set; }
    public int Limit { get; set; } = 30;
    public string? Cookie { get; set; }
}

public sealed class ExtSearchItemDto
{
    public string? Kind { get; set; }
    public JsonElement? RemoteTrackId { get; set; }
    public JsonElement? RemoteId { get; set; }
    public JsonElement? Id { get; set; }
    public string? Title { get; set; }
    public string? Artist { get; set; }
    public string? Album { get; set; }
    public double? DurationSec { get; set; }
    public string? ArtworkUrl { get; set; }
    public string? SourceUrl { get; set; }
    public string? Url { get; set; }
    public string? RelativePath { get; set; }
    public string? StreamHint { get; set; }
    public int? TrackCount { get; set; }
    public int? Year { get; set; }
    public JsonElement? TrackNumber { get; set; }
}

public sealed class ExtSearchResultDto
{
    public List<ExtSearchItemDto>? Items { get; set; }
    public JsonElement? NextCursor { get; set; }
    public bool? HasMore { get; set; }
    public int? TotalCount { get; set; }
}

public sealed record ExtSearchResultItem(
    ExtensionSearchKind Kind,
    string Id,
    string Title,
    string? Artist = null,
    string? Album = null,
    double DurationSec = 0,
    string? ArtworkUrl = null,
    string? RelativePath = null,
    string? SourceUrl = null,
    int? TrackCount = null,
    int? Year = null,
    int? TrackNumber = null);

public sealed record ExtSearchPage(
    IReadOnlyList<ExtSearchResultItem> Items,
    string? NextCursor,
    bool HasMore,
    int? TotalCount = null);

public sealed record CloudSearchExtensionDescriptor(
    string ExtensionId,
    string DisplayName,
    IReadOnlyList<ExtensionSearchKind> SearchKinds,
    bool SupportsStream,
    bool SupportsCookie,
    bool SupportsCollectionImport);

public enum ExtQrLoginStatus
{
    Pending,
    Scanned,
    Confirmed,
    Expired,
    Error
}

public static class ExtQrLoginStatusExtensions
{
    public static bool TryParseWire(string? raw, out ExtQrLoginStatus status)
    {
        switch (raw?.Trim().ToLowerInvariant())
        {
            case "pending":
                status = ExtQrLoginStatus.Pending;
                return true;
            case "scanned":
                status = ExtQrLoginStatus.Scanned;
                return true;
            case "confirmed":
                status = ExtQrLoginStatus.Confirmed;
                return true;
            case "expired":
                status = ExtQrLoginStatus.Expired;
                return true;
            case "error":
                status = ExtQrLoginStatus.Error;
                return true;
            default:
                status = ExtQrLoginStatus.Error;
                return false;
        }
    }
}

public sealed record ExtQrLoginSession(
    string SessionId,
    string QrContent,
    long? ExpiresAtMs = null,
    int PollIntervalMs = 2000,
    string? Message = null);

public sealed record ExtQrLoginPollResult(
    ExtQrLoginStatus Status,
    string? Cookie = null,
    string? AccountName = null,
    string? Message = null);
