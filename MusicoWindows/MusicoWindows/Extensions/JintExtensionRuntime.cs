using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Jint;
using Jint.Native;
using Jint.Native.Object;
using Jint.Runtime;
using MusicoWindows.Cloud;

namespace MusicoWindows.Extensions;

public interface IExtensionHostServices
{
    string? KvGet(string extensionId, string key);
    void KvSet(string extensionId, string key, string value, double? ttlSeconds);
    void KvRemove(string extensionId, string key);
    string? GetSettingJson(string extensionId, string key);
    void AppendLog(string extensionId, ExtensionLogLevel level, string message);
}

internal sealed class InMemoryExtensionHostServices : IExtensionHostServices
{
    private readonly object _lock = new();
    private readonly Dictionary<string, ExtensionKvEntry> _kv = new(StringComparer.Ordinal);
    private readonly Dictionary<string, string> _settings = new(StringComparer.Ordinal);
    private readonly List<ExtensionLogEntry> _logs = new();

    public InMemoryExtensionHostServices(ExtensionManifest? manifest = null)
    {
        if (manifest?.Settings is not null)
        {
            foreach (var s in manifest.Settings)
            {
                if (s.Default is JsonElement el)
                {
                    _settings[s.Key] = el.GetRawText();
                }
            }
        }
    }

    public string? KvGet(string extensionId, string key)
    {
        lock (_lock)
        {
            if (!_kv.TryGetValue(key, out var entry)) return null;
            if (entry.ExpiresAtMs is long exp && DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() >= exp)
            {
                _kv.Remove(key);
                return null;
            }
            return entry.Value;
        }
    }

    public void KvSet(string extensionId, string key, string value, double? ttlSeconds)
    {
        long? exp = ttlSeconds is > 0
            ? DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + (long)Math.Round(ttlSeconds.Value * 1000.0)
            : null;
        lock (_lock)
        {
            _kv[key] = new ExtensionKvEntry(value, exp);
        }
    }

    public void KvRemove(string extensionId, string key)
    {
        lock (_lock)
        {
            _kv.Remove(key);
        }
    }

    public string? GetSettingJson(string extensionId, string key)
    {
        lock (_lock)
        {
            return _settings.TryGetValue(key, out string? raw) ? raw : null;
        }
    }

    public void AppendLog(string extensionId, ExtensionLogLevel level, string message)
    {
        lock (_lock)
        {
            _logs.Add(new ExtensionLogEntry(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), level, message));
            if (_logs.Count > 200) _logs.RemoveAt(0);
        }
    }
}

public sealed class JintExtensionRuntime
{
    public const long MemoryLimitBytes = 32_000_000;
    public static readonly TimeSpan DefaultShortHookTimeout = TimeSpan.FromSeconds(15);
    public static readonly TimeSpan DefaultLongHookTimeout = TimeSpan.FromSeconds(60);

    private const string BootstrapFactoryScript = """
(function(__hostHttp, __hostCrypto, __hostKv, __hostSettings, __hostLog, __envJson, __scriptLoader) {
  'use strict';
  var env = JSON.parse(__envJson);
  var ctx = {
    http: {
      request: async function(req) {
        var envStr = await __hostHttp(JSON.stringify(req || {}));
        var envelope = JSON.parse(envStr);
        if (!envelope || !envelope.ok) {
          var err = new Error((envelope && envelope.message) || 'HTTP request failed');
          err.code = (envelope && envelope.code) || 'NETWORK_ERROR';
          throw err;
        }
        return envelope.result;
      }
    },
    crypto: {
      md5: function(s) { return __hostCrypto.Md5(s == null ? '' : String(s)); },
      sha1: function(s) { return __hostCrypto.Sha1(s == null ? '' : String(s)); },
      sha256: function(s) { return __hostCrypto.Sha256(s == null ? '' : String(s)); },
      hmacSha256: function(k, m) {
        return __hostCrypto.HmacSha256(k == null ? '' : String(k), m == null ? '' : String(m));
      },
      base64Encode: function(s) { return __hostCrypto.Base64Encode(s == null ? '' : String(s)); },
      base64Decode: function(b) { return __hostCrypto.Base64Decode(b == null ? '' : String(b)); }
    },
    kv: {
      get: function(k) { return __hostKv.Get(String(k)); },
      set: function(k, v, ttl) {
        __hostKv.Set(String(k), v == null ? '' : String(v), ttl == null ? null : Number(ttl));
      },
      remove: function(k) { __hostKv.Remove(String(k)); }
    },
    settings: {
      get: function(k) {
        var raw = __hostSettings.GetJson(String(k));
        return raw == null ? null : JSON.parse(raw);
      }
    },
    env: env,
    log: {
      info: function(m) { __hostLog.Info(m == null ? '' : String(m)); },
      warn: function(m) { __hostLog.Warn(m == null ? '' : String(m)); },
      error: function(m) { __hostLog.Error(m == null ? '' : String(m)); }
    }
  };
  var console = {
    log: ctx.log.info,
    info: ctx.log.info,
    warn: ctx.log.warn,
    error: ctx.log.error
  };
  var module = { exports: {} };
  __scriptLoader(module.exports, module, console);
  return {
    invoke: function(hookName, reqJson, includeCtx) {
      var exportsObj = module.exports;
      var fn = exportsObj && exportsObj[hookName];
      if (typeof fn !== 'function') {
        var missingErr = new Error('Extension does not export hook "' + hookName + '"');
        missingErr.code = 'EXTENSION_RUNTIME_ERROR';
        throw missingErr;
      }
      var req = reqJson == null ? {} : JSON.parse(reqJson);
      return includeCtx ? fn(ctx, req) : fn(req);
    }
  };
})
""";

    private sealed class CryptoBridge
    {
        public string Md5(string s) =>
            Convert.ToHexString(MD5.HashData(Encoding.UTF8.GetBytes(s ?? string.Empty))).ToLowerInvariant();

        public string Sha1(string s) =>
            Convert.ToHexString(SHA1.HashData(Encoding.UTF8.GetBytes(s ?? string.Empty))).ToLowerInvariant();

        public string Sha256(string s) =>
            Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(s ?? string.Empty))).ToLowerInvariant();

        public string HmacSha256(string key, string msg) =>
            Convert.ToHexString(HMACSHA256.HashData(
                Encoding.UTF8.GetBytes(key ?? string.Empty),
                Encoding.UTF8.GetBytes(msg ?? string.Empty))).ToLowerInvariant();

        public string Base64Encode(string s) =>
            Convert.ToBase64String(Encoding.UTF8.GetBytes(s ?? string.Empty));

        public string Base64Decode(string b64) =>
            Encoding.UTF8.GetString(Convert.FromBase64String(b64 ?? string.Empty));
    }

    private sealed class KvBridge(IExtensionHostServices host, string extensionId)
    {
        public string? Get(string key) => host.KvGet(extensionId, key);
        public void Set(string key, string value, double? ttl) => host.KvSet(extensionId, key, value, ttl);
        public void Remove(string key) => host.KvRemove(extensionId, key);
    }

    private sealed class SettingsBridge(IExtensionHostServices host, string extensionId)
    {
        public string? GetJson(string key) => host.GetSettingJson(extensionId, key);
    }

    private sealed class LogBridge(IExtensionHostServices host, string extensionId)
    {
        public void Info(string message) => host.AppendLog(extensionId, ExtensionLogLevel.Info, message);
        public void Warn(string message) => host.AppendLog(extensionId, ExtensionLogLevel.Warn, message);
        public void Error(string message) => host.AppendLog(extensionId, ExtensionLogLevel.Error, message);
    }

    private sealed class HttpEnvelope
    {
        public bool Ok { get; set; }
        public ExtensionHttpResponse? Result { get; set; }
        public string? Code { get; set; }
        public string? Message { get; set; }
    }

    private readonly HttpMessageHandler? _httpHandler;
    private readonly string _appVersion;
    private readonly TimeSpan _shortHookTimeout;
    private readonly TimeSpan _longHookTimeout;

    public JintExtensionRuntime(
        HttpMessageHandler? httpHandler = null,
        string? appVersion = null,
        TimeSpan? shortHookTimeout = null,
        TimeSpan? longHookTimeout = null)
    {
        _httpHandler = httpHandler;
        _appVersion = string.IsNullOrWhiteSpace(appVersion) ? "1.2.0" : appVersion.Trim();
        _shortHookTimeout = shortHookTimeout ?? DefaultShortHookTimeout;
        _longHookTimeout = longHookTimeout ?? DefaultLongHookTimeout;
    }

    public Task<ExtCanHandleResult> CanHandleAsync(
        ExtensionManifest manifest,
        string scriptSource,
        ExtCanHandleInput input,
        IExtensionHostServices? host = null,
        CancellationToken ct = default) =>
        InvokeHookAsync<ExtCanHandleResult>(
            manifest,
            scriptSource,
            hookName: "canHandle",
            requestPayload: input,
            includeCtx: false,
            timeout: _shortHookTimeout,
            proxyUrl: null,
            host: host,
            ct: ct);

    public Task<ExtCollectionResult> FetchCollectionAsync(
        ExtensionManifest manifest,
        string scriptSource,
        ExtFetchCollectionRequest request,
        string? proxyUrl = null,
        IExtensionHostServices? host = null,
        CancellationToken ct = default) =>
        InvokeHookAsync<ExtCollectionResult>(
            manifest,
            scriptSource,
            hookName: "fetchCollection",
            requestPayload: request,
            includeCtx: true,
            timeout: _longHookTimeout,
            proxyUrl: proxyUrl,
            host: host,
            ct: ct);

    public Task<ExtFolderResult> FetchFolderAsync(
        ExtensionManifest manifest,
        string scriptSource,
        ExtFetchFolderRequest request,
        string? proxyUrl = null,
        IExtensionHostServices? host = null,
        CancellationToken ct = default) =>
        InvokeHookAsync<ExtFolderResult>(
            manifest,
            scriptSource,
            hookName: "fetchFolder",
            requestPayload: request,
            includeCtx: true,
            timeout: _longHookTimeout,
            proxyUrl: proxyUrl,
            host: host,
            ct: ct);

    public async Task<ExtStreamResult> ResolveStreamAsync(
        ExtensionManifest manifest,
        string scriptSource,
        ExtResolveStreamRequest request,
        string? proxyUrl = null,
        IExtensionHostServices? host = null,
        CancellationToken ct = default)
    {
        var result = await InvokeHookAsync<ExtStreamResult>(
            manifest,
            scriptSource,
            hookName: "resolveStream",
            requestPayload: request,
            includeCtx: true,
            timeout: _shortHookTimeout,
            proxyUrl: proxyUrl,
            host: host,
            ct: ct).ConfigureAwait(false);

        if (string.IsNullOrWhiteSpace(result.Url))
        {
            throw new CloudServiceException(
                CloudErrorCode.NoPlayableStream,
                "Extension resolveStream did not return a playable stream URL.");
        }

        IReadOnlyList<string> allowedHosts = ResolveEffectiveAllowedHosts(manifest, host);
        if (!HostAllowlistMatcher.IsAllowed(result.Url, allowedHosts))
        {
            throw new CloudServiceException(
                CloudErrorCode.PermissionDenied,
                $"Stream URL host blocked by permissions.hosts: {result.Url}");
        }

        if (result.FallbackUrls is not null)
        {
            foreach (string fallbackUrl in result.FallbackUrls)
            {
                if (string.IsNullOrWhiteSpace(fallbackUrl)) continue;
                if (!HostAllowlistMatcher.IsAllowed(fallbackUrl, allowedHosts))
                {
                    throw new CloudServiceException(
                        CloudErrorCode.PermissionDenied,
                        $"Fallback stream URL host blocked by permissions.hosts: {fallbackUrl}");
                }
            }
        }

        return result;
    }

    public async Task<List<ExtLyricsCandidateSpec>> SearchLyricsAsync(
        ExtensionManifest manifest,
        string scriptSource,
        CloudLyricsQuery query,
        string? proxyUrl = null,
        IExtensionHostServices? host = null,
        CancellationToken ct = default)
    {
        var reqObj = new Dictionary<string, object?>
        {
            ["title"] = query.TrimmedTitle,
            ["artist"] = query.TrimmedArtist
        };
        if (query.TrimmedAlbum is { } album)
        {
            reqObj["album"] = album;
        }
        if (query.DurationSec is double dur && !double.IsNaN(dur) && !double.IsInfinity(dur) && dur > 0)
        {
            reqObj["durationSec"] = dur;
        }

        JsonElement payload = await InvokeHookAsync<JsonElement>(
            manifest,
            scriptSource,
            hookName: "searchLyrics",
            requestPayload: reqObj,
            includeCtx: true,
            timeout: _shortHookTimeout,
            proxyUrl: proxyUrl,
            host: host,
            ct: ct,
            allowNullResult: true).ConfigureAwait(false);

        if (payload.ValueKind == JsonValueKind.Undefined || payload.ValueKind == JsonValueKind.Null)
        {
            return [];
        }

        try
        {
            if (payload.ValueKind == JsonValueKind.Array)
            {
                return JsonSerializer.Deserialize<List<ExtLyricsCandidateSpec>>(payload.GetRawText(), ExtensionJson.Options) ?? [];
            }

            if (payload.ValueKind == JsonValueKind.Object)
            {
                ExtLyricsSearchEnvelope? env = JsonSerializer.Deserialize<ExtLyricsSearchEnvelope>(payload.GetRawText(), ExtensionJson.Options);
                return env?.Candidates ?? env?.Results ?? [];
            }
        }
        catch (Exception ex)
        {
            throw new CloudServiceException(
                CloudErrorCode.ExtensionRuntimeError,
                $"Invalid extension lyrics schema: {ex.Message}",
                ex);
        }

        throw new CloudServiceException(
            CloudErrorCode.ExtensionRuntimeError,
            "Extension searchLyrics must return an array or candidate object.");
    }

    public sealed class ExtLyricsCandidateSpec
    {
        public string? ExternalId { get; set; }
        public string? Title { get; set; }
        public string? Artist { get; set; }
        public string? Album { get; set; }
        public double? DurationSec { get; set; }
        public string? SyncedLyrics { get; set; }
        public string? PlainLyrics { get; set; }
        public string? TranslatedLyrics { get; set; }
        public bool? Instrumental { get; set; }
    }

    private sealed class ExtLyricsSearchEnvelope
    {
        public List<ExtLyricsCandidateSpec>? Candidates { get; set; }
        public List<ExtLyricsCandidateSpec>? Results { get; set; }
    }

    public async Task<ExtSearchPage> SearchAsync(
        ExtensionManifest manifest,
        string scriptSource,
        ExtSearchRequest request,
        string? proxyUrl = null,
        IExtensionHostServices? host = null,
        CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(manifest);
        ArgumentNullException.ThrowIfNull(request);

        var reqObj = new Dictionary<string, object?>
        {
            ["query"] = (request.Query ?? string.Empty).Trim(),
            ["kind"] = string.IsNullOrWhiteSpace(request.Kind) ? "all" : request.Kind.Trim().ToLowerInvariant(),
            ["limit"] = request.Limit > 0 ? request.Limit : 30
        };
        if (!string.IsNullOrWhiteSpace(request.Cursor))
        {
            reqObj["cursor"] = request.Cursor.Trim();
        }
        if (!string.IsNullOrWhiteSpace(request.Cookie))
        {
            reqObj["cookie"] = request.Cookie.Trim();
        }

        JsonElement payload = await InvokeHookAsync<JsonElement>(
            manifest,
            scriptSource,
            hookName: "search",
            requestPayload: reqObj,
            includeCtx: true,
            timeout: _shortHookTimeout,
            proxyUrl: proxyUrl,
            host: host,
            ct: ct,
            allowNullResult: true).ConfigureAwait(false);

        if (payload.ValueKind is JsonValueKind.Undefined or JsonValueKind.Null)
        {
            return new ExtSearchPage(Array.Empty<ExtSearchResultItem>(), null, false, null);
        }

        List<ExtSearchItemDto>? rawItems;
        string? nextCursor = null;
        bool? explicitHasMore = null;
        int? totalCount = null;

        try
        {
            if (payload.ValueKind == JsonValueKind.Array)
            {
                rawItems = JsonSerializer.Deserialize<List<ExtSearchItemDto>>(payload.GetRawText(), ExtensionJson.Options);
            }
            else if (payload.ValueKind == JsonValueKind.Object)
            {
                var dto = JsonSerializer.Deserialize<ExtSearchResultDto>(payload.GetRawText(), ExtensionJson.Options);
                rawItems = dto?.Items;
                nextCursor = ReadLossyScalarString(dto?.NextCursor);
                explicitHasMore = dto?.HasMore;
                totalCount = dto?.TotalCount;
            }
            else
            {
                throw new CloudServiceException(
                    CloudErrorCode.ExtensionRuntimeError,
                    "Extension search must return an array or result object.");
            }
        }
        catch (CloudServiceException)
        {
            throw;
        }
        catch (Exception ex)
        {
            throw new CloudServiceException(
                CloudErrorCode.ExtensionRuntimeError,
                $"Invalid extension search schema: {ex.Message}",
                ex);
        }

        var items = new List<ExtSearchResultItem>();
        if (rawItems is not null)
        {
            foreach (var dto in rawItems)
            {
                if (dto is null) continue;

                string? rawKind = !string.IsNullOrWhiteSpace(dto.Kind)
                    ? dto.Kind
                    : (string.Equals(request.Kind, "all", StringComparison.OrdinalIgnoreCase) ? null : request.Kind);
                ExtensionSearchKind kind;
                switch (rawKind?.Trim().ToLowerInvariant())
                {
                    case "track":
                    case null:
                    case "":
                        kind = ExtensionSearchKind.Track;
                        break;
                    case "playlist":
                        kind = ExtensionSearchKind.Playlist;
                        break;
                    case "album":
                        kind = ExtensionSearchKind.Album;
                        break;
                    default:
                        continue;
                }

                string? title = string.IsNullOrWhiteSpace(dto.Title) ? null : dto.Title.Trim();
                if (title is null) continue;

                string? id = kind == ExtensionSearchKind.Track
                    ? ReadLossyScalarString(dto.RemoteTrackId) ?? ReadLossyScalarString(dto.Id)
                    : ReadLossyScalarString(dto.RemoteId) ?? ReadLossyScalarString(dto.Id);
                if (id is null) continue;

                double dur = dto.DurationSec is double d && !double.IsNaN(d) && !double.IsInfinity(d) && d > 0 ? d : 0;
                int? trackNo = null;
                if (dto.TrackNumber is JsonElement tnEl)
                {
                    if (tnEl.ValueKind == JsonValueKind.Number && tnEl.TryGetDouble(out double tnd) && tnd > 0)
                    {
                        trackNo = (int)Math.Round(tnd);
                    }
                    else if (tnEl.ValueKind == JsonValueKind.String && int.TryParse(tnEl.GetString(), out int tni) && tni > 0)
                    {
                        trackNo = tni;
                    }
                }

                string? relativePath = !string.IsNullOrWhiteSpace(dto.RelativePath)
                    ? dto.RelativePath.Trim()
                    : (string.IsNullOrWhiteSpace(dto.StreamHint) ? null : dto.StreamHint.Trim());
                string? sourceUrl = !string.IsNullOrWhiteSpace(dto.SourceUrl)
                    ? dto.SourceUrl.Trim()
                    : (string.IsNullOrWhiteSpace(dto.Url) ? null : dto.Url.Trim());

                items.Add(new ExtSearchResultItem(
                    Kind: kind,
                    Id: id,
                    Title: title,
                    Artist: string.IsNullOrWhiteSpace(dto.Artist) ? null : dto.Artist.Trim(),
                    Album: string.IsNullOrWhiteSpace(dto.Album) ? null : dto.Album.Trim(),
                    DurationSec: dur,
                    ArtworkUrl: string.IsNullOrWhiteSpace(dto.ArtworkUrl) ? null : dto.ArtworkUrl.Trim(),
                    RelativePath: relativePath,
                    SourceUrl: sourceUrl,
                    TrackCount: dto.TrackCount is > 0 ? dto.TrackCount : null,
                    Year: dto.Year is > 0 ? dto.Year : null,
                    TrackNumber: trackNo));
            }
        }

        bool hasMore = !string.IsNullOrEmpty(nextCursor) && (explicitHasMore ?? true);
        if (!hasMore)
        {
            nextCursor = null;
        }

        return new ExtSearchPage(items, nextCursor, hasMore, totalCount);
    }

    public async Task<ExtQrLoginSession> StartQrLoginAsync(
        ExtensionManifest manifest,
        string scriptSource,
        string? proxyUrl = null,
        IExtensionHostServices? host = null,
        CancellationToken ct = default)
    {
        JsonElement payload = await InvokeHookAsync<JsonElement>(
            manifest,
            scriptSource,
            hookName: "startQrLogin",
            requestPayload: new Dictionary<string, object?>(),
            includeCtx: true,
            timeout: _shortHookTimeout,
            proxyUrl: proxyUrl,
            host: host,
            ct: ct).ConfigureAwait(false);

        if (payload.ValueKind != JsonValueKind.Object)
        {
            throw new CloudServiceException(
                CloudErrorCode.ExtensionRuntimeError,
                "Extension startQrLogin() must return an object.");
        }

        string? sessionId = ReadPropertyScalarString(payload, "sessionId");
        if (string.IsNullOrEmpty(sessionId))
        {
            throw new CloudServiceException(
                CloudErrorCode.ExtensionRuntimeError,
                "Extension startQrLogin() must return a non-empty sessionId.");
        }

        string? qrContent = ReadPropertyTrimmedString(payload, "qrContent") ?? ReadPropertyTrimmedString(payload, "qrUrl");
        if (string.IsNullOrEmpty(qrContent))
        {
            throw new CloudServiceException(
                CloudErrorCode.ExtensionRuntimeError,
                "Extension startQrLogin() must return a non-empty qrContent.");
        }

        if (Encoding.UTF8.GetByteCount(qrContent) > 512)
        {
            throw new CloudServiceException(
                CloudErrorCode.ExtensionRuntimeError,
                "Extension startQrLogin() qrContent exceeds 512 UTF-8 bytes.");
        }

        int pollIntervalMs = 2000;
        if (payload.TryGetProperty("pollIntervalMs", out JsonElement intervalEl) &&
            intervalEl.ValueKind == JsonValueKind.Number &&
            intervalEl.TryGetDouble(out double intervalVal) &&
            double.IsFinite(intervalVal))
        {
            pollIntervalMs = Math.Clamp((int)Math.Round(intervalVal), 1000, 10000);
        }

        long? expiresAtMs = null;
        if (payload.TryGetProperty("expiresAtMs", out JsonElement expEl) &&
            expEl.ValueKind == JsonValueKind.Number &&
            expEl.TryGetDouble(out double expVal) &&
            double.IsFinite(expVal) &&
            expVal > 0)
        {
            expiresAtMs = (long)Math.Floor(expVal);
        }

        string? message = ReadPropertyTrimmedString(payload, "message");
        return new ExtQrLoginSession(sessionId, qrContent, expiresAtMs, pollIntervalMs, message);
    }

    public async Task<ExtQrLoginPollResult> PollQrLoginAsync(
        ExtensionManifest manifest,
        string scriptSource,
        string sessionId,
        string? proxyUrl = null,
        IExtensionHostServices? host = null,
        CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(sessionId))
        {
            throw new CloudServiceException(
                CloudErrorCode.ExtensionRuntimeError,
                "QR login sessionId must not be empty.");
        }

        var req = new Dictionary<string, object?>
        {
            ["sessionId"] = sessionId.Trim()
        };

        JsonElement payload = await InvokeHookAsync<JsonElement>(
            manifest,
            scriptSource,
            hookName: "pollQrLogin",
            requestPayload: req,
            includeCtx: true,
            timeout: _shortHookTimeout,
            proxyUrl: proxyUrl,
            host: host,
            ct: ct).ConfigureAwait(false);

        if (payload.ValueKind != JsonValueKind.Object)
        {
            throw new CloudServiceException(
                CloudErrorCode.ExtensionRuntimeError,
                "Extension pollQrLogin() must return an object.");
        }

        string? rawStatus = ReadPropertyTrimmedString(payload, "status");
        if (!ExtQrLoginStatusExtensions.TryParseWire(rawStatus, out ExtQrLoginStatus status))
        {
            throw new CloudServiceException(
                CloudErrorCode.ExtensionRuntimeError,
                $"Extension pollQrLogin() returned invalid status '{rawStatus}'.");
        }

        string? cookie = ReadPropertyTrimmedString(payload, "cookie");
        if (status == ExtQrLoginStatus.Confirmed && string.IsNullOrEmpty(cookie))
        {
            throw new CloudServiceException(
                CloudErrorCode.ExtensionRuntimeError,
                "Extension pollQrLogin() returned 'confirmed' without a cookie.");
        }

        string? accountName = ReadPropertyTrimmedString(payload, "accountName");
        string? message = ReadPropertyTrimmedString(payload, "message");
        return new ExtQrLoginPollResult(status, cookie, accountName, message);
    }

    private static string? ReadPropertyTrimmedString(JsonElement obj, string propertyName)
    {
        if (!obj.TryGetProperty(propertyName, out JsonElement el) || el.ValueKind != JsonValueKind.String)
        {
            return null;
        }
        string? val = el.GetString();
        return string.IsNullOrWhiteSpace(val) ? null : val.Trim();
    }

    private static string? ReadPropertyScalarString(JsonElement obj, string propertyName)
    {
        if (!obj.TryGetProperty(propertyName, out JsonElement el))
        {
            return null;
        }
        return ReadLossyScalarString(el);
    }

    private static string? ReadLossyScalarString(JsonElement? el)
    {
        if (el is not JsonElement val) return null;
        return val.ValueKind switch
        {
            JsonValueKind.String => string.IsNullOrWhiteSpace(val.GetString()) ? null : val.GetString()!.Trim(),
            JsonValueKind.Number => val.GetRawText(),
            _ => null
        };
    }

    private Task<TResult> InvokeHookAsync<TResult>(
        ExtensionManifest manifest,
        string scriptSource,
        string hookName,
        object requestPayload,
        bool includeCtx,
        TimeSpan timeout,
        string? proxyUrl,
        IExtensionHostServices? host,
        CancellationToken ct,
        bool allowNullResult = false)
    {
        ArgumentNullException.ThrowIfNull(manifest);
        if (string.IsNullOrWhiteSpace(scriptSource))
        {
            throw new CloudServiceException(CloudErrorCode.ExtensionRuntimeError, "Extension script source is empty.");
        }

        var effectiveHost = host ?? new InMemoryExtensionHostServices(manifest);
        string requestJson = JsonSerializer.Serialize(requestPayload, ExtensionJson.Options);
        string envJson = JsonSerializer.Serialize(new
        {
            platform = "windows",
            appVersion = _appVersion,
            apiVersion = ExtensionManifest.CurrentApiVersion
        }, ExtensionJson.Options);

        return Task.Run(() =>
        {
            ct.ThrowIfCancellationRequested();

            using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            linkedCts.CancelAfter(timeout);

            var engine = new Engine(opts =>
            {
                opts.LimitMemory(MemoryLimitBytes);
                opts.TimeoutInterval(timeout);
                opts.CancellationToken(linkedCts.Token);
                opts.Constraints.PromiseTimeout = timeout;
                opts.ExperimentalFeatures = ExperimentalFeature.TaskInterop;
                opts.CatchClrExceptions();
            });

            IReadOnlyList<string> effectiveHosts = ResolveEffectiveAllowedHosts(manifest, effectiveHost);
            Func<string, Task<string>> httpBridge = reqJson =>
                ExecuteHttpRequestEnvelopeAsync(reqJson, effectiveHosts, proxyUrl, linkedCts.Token);

            var cryptoBridge = new CryptoBridge();
            var kvBridge = new KvBridge(effectiveHost, manifest.Id);
            var settingsBridge = new SettingsBridge(effectiveHost, manifest.Id);
            var logBridge = new LogBridge(effectiveHost, manifest.Id);

            try
            {
                JsValue factory = engine.Evaluate(BootstrapFactoryScript);
                JsValue scriptLoader = engine.Evaluate($"(function(exports, module, console) {{\n{scriptSource}\n}})");

                JsValue runnerVal = engine.Invoke(
                    factory,
                    httpBridge,
                    cryptoBridge,
                    kvBridge,
                    settingsBridge,
                    logBridge,
                    envJson,
                    scriptLoader);

                ObjectInstance runner = runnerVal.AsObject();
                JsValue invokeFn = runner.Get("invoke");
                JsValue rawResult = engine.Invoke(invokeFn, runner, [JsValue.FromObject(engine, hookName), JsValue.FromObject(engine, requestJson), JsValue.FromObject(engine, includeCtx)]);
                JsValue settled = rawResult.UnwrapIfPromise();

                if (settled.IsNull() || settled.IsUndefined())
                {
                    if (allowNullResult)
                    {
                        return default!;
                    }
                    throw new CloudServiceException(
                        CloudErrorCode.ExtensionRuntimeError,
                        $"Extension hook '{hookName}' returned null or undefined.");
                }

                var jsonSerializer = new Jint.Native.Json.JsonSerializer(engine);
                JsValue resultJsonVal = jsonSerializer.Serialize(settled);
                if (!resultJsonVal.IsString())
                {
                    throw new CloudServiceException(
                        CloudErrorCode.ExtensionRuntimeError,
                        $"Extension hook '{hookName}' returned a non-serializable value.");
                }

                string resultJson = resultJsonVal.AsString();
                TResult? parsed = JsonSerializer.Deserialize<TResult>(resultJson, ExtensionJson.Options);
                if (parsed is null)
                {
                    throw new CloudServiceException(
                        CloudErrorCode.ExtensionRuntimeError,
                        $"Failed to parse result from extension hook '{hookName}'.");
                }

                return parsed;
            }
            catch (CloudServiceException)
            {
                throw;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                throw TranslateJsException(ex, hookName, timeout, ct);
            }
        }, ct);
    }

    private static IReadOnlyList<string> ResolveEffectiveAllowedHosts(
        ExtensionManifest manifest,
        IExtensionHostServices? host)
    {
        var effectiveHost = host ?? new InMemoryExtensionHostServices(manifest);
        return manifest.GetEffectiveAllowedHosts(key => effectiveHost.GetSettingJson(manifest.Id, key));
    }

    private async Task<string> ExecuteHttpRequestEnvelopeAsync(
        string reqJson,
        IReadOnlyList<string> allowedHosts,
        string? proxyUrl,
        CancellationToken ct)
    {
        try
        {
            ExtensionHttpRequest? req = JsonSerializer.Deserialize<ExtensionHttpRequest>(reqJson, ExtensionJson.Options);
            if (req is null)
            {
                return JsonSerializer.Serialize(new HttpEnvelope
                {
                    Ok = false,
                    Code = "INVALID_URL",
                    Message = "Invalid HTTP request object."
                }, ExtensionJson.Options);
            }

            ExtensionHttpResponse resp = await CloudHttpHelper.SendExtensionRequestAsync(
                req,
                allowedHosts,
                proxyUrl,
                _httpHandler,
                ct).ConfigureAwait(false);

            return JsonSerializer.Serialize(new HttpEnvelope
            {
                Ok = true,
                Result = resp
            }, ExtensionJson.Options);
        }
        catch (CloudServiceException cse)
        {
            return JsonSerializer.Serialize(new HttpEnvelope
            {
                Ok = false,
                Code = cse.WireCode,
                Message = cse.Message
            }, ExtensionJson.Options);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            return JsonSerializer.Serialize(new HttpEnvelope
            {
                Ok = false,
                Code = "NETWORK_ERROR",
                Message = ex.Message
            }, ExtensionJson.Options);
        }
    }

    private static CloudServiceException TranslateJsException(
        Exception ex,
        string hookName,
        TimeSpan timeout,
        CancellationToken callerCt)
    {
        if (callerCt.IsCancellationRequested)
        {
            throw new OperationCanceledException(callerCt);
        }

        if (ex is PromiseRejectedException pre)
        {
            return MapJsErrorValue(pre.RejectedValue, pre.Message);
        }

        if (ex is JavaScriptException jse)
        {
            return MapJsErrorValue(jse.Error, jse.Message);
        }

        if (ex is MemoryLimitExceededException)
        {
            return new CloudServiceException(
                CloudErrorCode.ExtensionRuntimeError,
                $"Extension exceeded 32 MB memory limit in '{hookName}'.",
                ex);
        }

        if (ex is TimeoutException or ExecutionCanceledException or OperationCanceledException or InvalidOperationException)
        {
            if (callerCt.IsCancellationRequested)
            {
                throw new OperationCanceledException(callerCt);
            }
            return new CloudServiceException(
                CloudErrorCode.ExtensionRuntimeError,
                $"Extension execution timed out after {(int)timeout.TotalMilliseconds}ms in '{hookName}'.",
                ex);
        }

        return new CloudServiceException(
            CloudErrorCode.ExtensionRuntimeError,
            $"Extension error in '{hookName}': {ex.Message}",
            ex);
    }

    private static CloudServiceException MapJsErrorValue(JsValue errVal, string fallbackMessage)
    {
        string? codeRaw = null;
        string? message = null;

        if (errVal.IsObject())
        {
            var obj = errVal.AsObject();
            JsValue codeVal = obj.Get("code");
            if (codeVal.IsString())
            {
                codeRaw = codeVal.AsString();
            }

            JsValue msgVal = obj.Get("message");
            if (msgVal.IsString())
            {
                message = msgVal.AsString();
            }
        }
        else if (errVal.IsString())
        {
            message = errVal.AsString();
        }

        CloudErrorCode mappedCode = CloudServiceException.ParseWireCode(codeRaw) ?? CloudErrorCode.ExtensionRuntimeError;
        string finalMsg = !string.IsNullOrWhiteSpace(message) ? message! : fallbackMessage;
        return new CloudServiceException(mappedCode, finalMsg);
    }
}
