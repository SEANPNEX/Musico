using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using MusicoWindows.Cloud;
using MusicoWindows.Core;

namespace MusicoWindows.Extensions;

public sealed record PendingExtensionUpdate(
    string ExtensionId,
    string UpdateUrl,
    string InstalledVersion,
    string NewVersion,
    LoadedExtensionPackage Package,
    IReadOnlyList<ExtensionCapability> AddedCapabilities,
    IReadOnlyList<string> AddedHosts,
    ExtensionManifest PreviousManifest)
{
    public bool HasPermissionEscalation => AddedCapabilities.Count > 0 || AddedHosts.Count > 0;
}

public sealed class ExtensionStore : IExtensionHostServices
{
    public const int MaxLogEntriesPerExtension = 200;
    public static readonly TimeSpan AutoUpdateInterval = TimeSpan.FromHours(6);

    private sealed class ExtensionStateFile
    {
        public List<ExtensionStateRecord> Extensions { get; set; } = new();
        public bool? AutoUpdateEnabled { get; set; }
        public DateTimeOffset? LastUpdateCheckUtc { get; set; }
    }

    private readonly object _stateLock = new();
    private readonly object _logsLock = new();
    private readonly object _kvLock = new();
    private readonly SemaphoreSlim _updateLock = new(1, 1);

    private readonly HttpMessageHandler? _httpHandler;
    private readonly JintExtensionRuntime _runtime;
    private readonly List<InstalledExtension> _extensions = new();
    private readonly Dictionary<string, PendingExtensionUpdate> _pendingUpdates = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, List<ExtensionLogEntry>> _logs = new(StringComparer.Ordinal);
    private readonly Dictionary<string, Dictionary<string, ExtensionKvEntry>> _kvCache = new(StringComparer.Ordinal);
    private bool _autoUpdateEnabled = true;
    private DateTimeOffset? _lastUpdateCheckUtc;
    private CloudProviderRegistry? _registry;

    public ExtensionStore(
        string? stateDirectory = null,
        HttpMessageHandler? httpHandler = null,
        JintExtensionRuntime? runtime = null,
        string? appVersion = null)
    {
        string baseStateDir = string.IsNullOrWhiteSpace(stateDirectory)
            ? Persistence.AppDataDirectory
            : stateDirectory;
        ExtensionsDirectory = Path.Combine(baseStateDir, "Extensions");
        Directory.CreateDirectory(ExtensionsDirectory);

        _httpHandler = httpHandler;
        _runtime = runtime ?? new JintExtensionRuntime(httpHandler, appVersion);

        LoadFromDisk();
    }

    public string ExtensionsDirectory { get; }
    public JintExtensionRuntime Runtime => _runtime;
    private string StateFilePath => Path.Combine(ExtensionsDirectory, "state.json");

    public event Action? Changed;
    public event Action? UpdatesChanged;
    public event Action<string>? LogsChanged;

    public bool AutoUpdateEnabled
    {
        get
        {
            lock (_stateLock)
            {
                return _autoUpdateEnabled;
            }
        }
        set
        {
            bool changed;
            lock (_stateLock)
            {
                changed = _autoUpdateEnabled != value;
                if (changed)
                {
                    _autoUpdateEnabled = value;
                    SaveStateLocked();
                }
            }
            if (changed)
            {
                UpdatesChanged?.Invoke();
                Changed?.Invoke();
            }
        }
    }

    public DateTimeOffset? LastUpdateCheckUtc
    {
        get
        {
            lock (_stateLock)
            {
                return _lastUpdateCheckUtc;
            }
        }
    }

    public IReadOnlyList<PendingExtensionUpdate> PendingUpdates
    {
        get
        {
            lock (_stateLock)
            {
                return _pendingUpdates.Values.ToList();
            }
        }
    }

    public PendingExtensionUpdate? GetPendingUpdate(string? extensionId)
    {
        if (string.IsNullOrWhiteSpace(extensionId)) return null;
        string target = extensionId.Trim();
        lock (_stateLock)
        {
            return _pendingUpdates.TryGetValue(target, out var pending) ? pending : null;
        }
    }

    public static bool TryNormalizeHttpsUrl(string? rawUrl, out string? normalized)
    {
        normalized = null;
        if (string.IsNullOrWhiteSpace(rawUrl)) return false;
        if (!Uri.TryCreate(rawUrl.Trim(), UriKind.Absolute, out var uri) ||
            !string.Equals(uri.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }
        normalized = uri.AbsoluteUri;
        return true;
    }

    public static string? ResolveUpdateUrl(InstalledExtension ext)
    {
        ArgumentNullException.ThrowIfNull(ext);
        if (TryNormalizeHttpsUrl(ext.Manifest.UpdateUrl, out string? manifestUrl))
        {
            return manifestUrl;
        }
        if (TryNormalizeHttpsUrl(ext.State.SourceUrl, out string? sourceUrl))
        {
            return sourceUrl;
        }
        if (TryNormalizeHttpsUrl(ext.State.SourceLocation, out string? sourceLoc))
        {
            return sourceLoc;
        }
        return null;
    }

    public IReadOnlyList<InstalledExtension> Extensions
    {
        get
        {
            lock (_stateLock)
            {
                return _extensions.ToList();
            }
        }
    }

    public InstalledExtension? GetById(string? extensionId)
    {
        if (string.IsNullOrWhiteSpace(extensionId)) return null;
        string target = extensionId.Trim();
        lock (_stateLock)
        {
            return _extensions.FirstOrDefault(e => string.Equals(e.Id, target, StringComparison.OrdinalIgnoreCase));
        }
    }

    public InstalledExtension? GetByProviderId(string? providerId)
    {
        if (string.IsNullOrWhiteSpace(providerId)) return null;
        string target = providerId.Trim();
        if (target.StartsWith("ext.", StringComparison.OrdinalIgnoreCase))
        {
            target = target[4..];
        }
        return GetById(target);
    }

    public void BindRegistry(CloudProviderRegistry registry)
    {
        ArgumentNullException.ThrowIfNull(registry);
        _registry = registry;
        _registry.ExtensionCookieResolver = GetCookie;
        SyncToRegistry();
    }

    public void SyncToRegistry()
    {
        var registry = _registry;
        if (registry is null) return;

        List<InstalledExtension> enabled;
        lock (_stateLock)
        {
            enabled = _extensions
                .Where(e => e.IsEnabled && (
                    e.Manifest.HasCapability(ExtensionCapability.Playlist) ||
                    e.Manifest.HasCapability(ExtensionCapability.Tree) ||
                    e.Manifest.HasCapability(ExtensionCapability.Stream)))
                .ToList();
        }

        var providers = enabled
            .Select(ext => (ICloudSourceProvider)new JsExtensionCloudProvider(ext, this, _runtime))
            .ToList();
        registry.SyncExtensionProviders(providers);
    }

    public IReadOnlyList<CloudSearchExtensionDescriptor> ActiveSearchExtensions => GetActiveSearchExtensions();

    public List<CloudSearchExtensionDescriptor> GetActiveSearchExtensions()
    {
        lock (_stateLock)
        {
            return _extensions
                .Where(e => e.IsEnabled && e.Manifest.HasCapability(ExtensionCapability.Search))
                .Select(e => new CloudSearchExtensionDescriptor(
                    ExtensionId: e.Id,
                    DisplayName: e.Manifest.EffectiveBadgeLabel,
                    SearchKinds: e.Manifest.EffectiveSearchKinds,
                    SupportsStream: e.Manifest.HasCapability(ExtensionCapability.Stream),
                    SupportsCookie: e.Manifest.SupportsCookieAuth,
                    SupportsCollectionImport:
                        e.Manifest.HasCapability(ExtensionCapability.Playlist) ||
                        e.Manifest.HasCapability(ExtensionCapability.Tree)))
                .ToList();
        }
    }

    public async Task<ExtSearchPage> SearchAsync(
        string extensionId,
        string query,
        ExtensionSearchKind? kind = null,
        string? cursor = null,
        int limit = 30,
        string? proxyUrl = null,
        CancellationToken ct = default)
    {
        InstalledExtension? ext;
        string? cookie;
        lock (_stateLock)
        {
            ext = _extensions.FirstOrDefault(e =>
                string.Equals(e.Id, extensionId, StringComparison.OrdinalIgnoreCase) &&
                e.IsEnabled &&
                e.Manifest.HasCapability(ExtensionCapability.Search));
            cookie = ext is not null && ext.Manifest.SupportsCookieAuth
                ? ext.State.Cookie
                : null;
        }

        if (ext is null)
        {
            throw new CloudServiceException(
                CloudErrorCode.ExtensionUnavailable,
                $"Search extension '{extensionId}' is unavailable or disabled.");
        }

        string script = ReadScriptSource(ext);
        var req = new ExtSearchRequest
        {
            Query = (query ?? string.Empty).Trim(),
            Kind = kind?.ToWireString(),
            Cursor = string.IsNullOrWhiteSpace(cursor) ? null : cursor.Trim(),
            Limit = limit > 0 ? limit : 30,
            Cookie = string.IsNullOrWhiteSpace(cookie) ? null : cookie
        };

        return await _runtime.SearchAsync(
            ext.Manifest,
            script,
            req,
            proxyUrl: proxyUrl,
            host: this,
            ct: ct).ConfigureAwait(false);
    }

    public async Task<CloudPlaylistResult> FetchCollectionPreviewAsync(
        string extensionId,
        string collectionUrl,
        string? proxyUrl = null,
        CancellationToken ct = default)
    {
        InstalledExtension? ext;
        string? cookie;
        lock (_stateLock)
        {
            ext = _extensions.FirstOrDefault(e =>
                string.Equals(e.Id, extensionId, StringComparison.OrdinalIgnoreCase) &&
                e.IsEnabled);
            cookie = ext is not null && ext.Manifest.SupportsCookieAuth
                ? ext.State.Cookie
                : null;
        }

        if (ext is null)
        {
            throw new CloudServiceException(
                CloudErrorCode.ExtensionUnavailable,
                $"Extension '{extensionId}' is unavailable or disabled.");
        }

        var provider = _registry?.GetById(CloudSources.Extension(ext.Id))
                       ?? new JsExtensionCloudProvider(ext, this, _runtime);
        return await provider.FetchPlaylistAsync(
            collectionUrl,
            string.IsNullOrWhiteSpace(cookie) ? null : cookie,
            proxyUrl,
            ct).ConfigureAwait(false);
    }

    public string? GetCookie(string extensionId)
    {
        if (string.IsNullOrWhiteSpace(extensionId)) return null;
        lock (_stateLock)
        {
            var ext = _extensions.FirstOrDefault(e => string.Equals(e.Id, extensionId, StringComparison.OrdinalIgnoreCase));
            return string.IsNullOrWhiteSpace(ext?.State.Cookie) ? null : ext!.State.Cookie;
        }
    }

    public void SetCookie(string extensionId, string? cookie)
    {
        if (string.IsNullOrWhiteSpace(extensionId)) return;
        string? normalized = string.IsNullOrWhiteSpace(cookie) ? null : cookie.Trim();
        lock (_stateLock)
        {
            int idx = FindIndexLocked(extensionId);
            if (idx < 0) return;

            string? current = string.IsNullOrWhiteSpace(_extensions[idx].State.Cookie) ? null : _extensions[idx].State.Cookie;
            if (string.Equals(current, normalized, StringComparison.Ordinal))
            {
                return;
            }

            _extensions[idx].State.Cookie = normalized;
            SaveStateLocked();
        }
        Changed?.Invoke();
    }

    public string? GetStreamQualityId(string extensionId)
    {
        if (string.IsNullOrWhiteSpace(extensionId)) return null;
        lock (_stateLock)
        {
            var ext = _extensions.FirstOrDefault(e => string.Equals(e.Id, extensionId, StringComparison.OrdinalIgnoreCase));
            if (ext is null) return null;
            return ext.Manifest.FindQuality(ext.State.StreamQualityId)?.Id.Trim();
        }
    }

    public void SetStreamQualityId(string extensionId, string? qualityId)
    {
        if (string.IsNullOrWhiteSpace(extensionId)) return;
        lock (_stateLock)
        {
            int idx = FindIndexLocked(extensionId);
            if (idx < 0) return;
            var ext = _extensions[idx];
            string? normalized = ext.Manifest.FindQuality(qualityId)?.Id.Trim();
            if (string.Equals(ext.State.StreamQualityId, normalized, StringComparison.OrdinalIgnoreCase))
            {
                return;
            }
            ext.State.StreamQualityId = normalized;
            SaveStateLocked();
        }
        Changed?.Invoke();
    }

    public string? GetDownloadQualityId(string extensionId)
    {
        if (string.IsNullOrWhiteSpace(extensionId)) return null;
        lock (_stateLock)
        {
            var ext = _extensions.FirstOrDefault(e => string.Equals(e.Id, extensionId, StringComparison.OrdinalIgnoreCase));
            if (ext is null) return null;
            return ext.Manifest.FindQuality(ext.State.DownloadQualityId)?.Id.Trim();
        }
    }

    public void SetDownloadQualityId(string extensionId, string? qualityId)
    {
        if (string.IsNullOrWhiteSpace(extensionId)) return;
        lock (_stateLock)
        {
            int idx = FindIndexLocked(extensionId);
            if (idx < 0) return;
            var ext = _extensions[idx];
            string? normalized = ext.Manifest.FindQuality(qualityId)?.Id.Trim();
            if (string.Equals(ext.State.DownloadQualityId, normalized, StringComparison.OrdinalIgnoreCase))
            {
                return;
            }
            ext.State.DownloadQualityId = normalized;
            SaveStateLocked();
        }
        Changed?.Invoke();
    }

    public async Task<ExtQrLoginSession> StartQrLoginAsync(
        string extensionId,
        string? proxyUrl = null,
        CancellationToken ct = default)
    {
        InstalledExtension? ext;
        lock (_stateLock)
        {
            ext = _extensions.FirstOrDefault(e =>
                string.Equals(e.Id, extensionId, StringComparison.OrdinalIgnoreCase) &&
                e.IsEnabled &&
                e.Manifest.SupportsQrLogin);
        }
        if (ext is null)
        {
            throw new CloudServiceException(
                CloudErrorCode.ExtensionUnavailable,
                $"QR login is unavailable or extension '{extensionId}' is disabled.");
        }

        string script = ReadScriptSource(ext);
        return await _runtime.StartQrLoginAsync(
            ext.Manifest,
            script,
            proxyUrl: proxyUrl,
            host: this,
            ct: ct).ConfigureAwait(false);
    }

    public async Task<ExtQrLoginPollResult> PollQrLoginAsync(
        string extensionId,
        string sessionId,
        string? proxyUrl = null,
        CancellationToken ct = default)
    {
        InstalledExtension? ext;
        lock (_stateLock)
        {
            ext = _extensions.FirstOrDefault(e =>
                string.Equals(e.Id, extensionId, StringComparison.OrdinalIgnoreCase) &&
                e.IsEnabled &&
                e.Manifest.SupportsQrLogin);
        }
        if (ext is null)
        {
            throw new CloudServiceException(
                CloudErrorCode.ExtensionUnavailable,
                $"QR login is unavailable or extension '{extensionId}' is disabled.");
        }

        string script = ReadScriptSource(ext);
        ExtQrLoginPollResult result = await _runtime.PollQrLoginAsync(
            ext.Manifest,
            script,
            sessionId,
            proxyUrl: proxyUrl,
            host: this,
            ct: ct).ConfigureAwait(false);

        if (result.Status == ExtQrLoginStatus.Confirmed && !string.IsNullOrWhiteSpace(result.Cookie))
        {
            SetCookie(ext.Id, result.Cookie);
        }

        return result;
    }

    public List<CloudLyricsExtensionDescriptor> GetActiveLyricsExtensions()
    {
        lock (_stateLock)
        {
            return _extensions
                .Where(e => e.IsEnabled && e.Manifest.HasCapability(ExtensionCapability.Lyrics))
                .Select(e => new CloudLyricsExtensionDescriptor(e.Id, e.Manifest.EffectiveBadgeLabel))
                .ToList();
        }
    }

    public async Task<List<CloudLyricsCandidate>> SearchLyricsAsync(
        string extensionId,
        CloudLyricsQuery query,
        string? proxyUrl = null,
        CancellationToken ct = default)
    {
        InstalledExtension? ext;
        string? cookie;
        lock (_stateLock)
        {
            ext = _extensions.FirstOrDefault(e =>
                string.Equals(e.Id, extensionId, StringComparison.OrdinalIgnoreCase) &&
                e.IsEnabled &&
                e.Manifest.HasCapability(ExtensionCapability.Lyrics));
            cookie = ext is not null && ext.Manifest.SupportsCookieAuth && !string.IsNullOrWhiteSpace(ext.State.Cookie)
                ? ext.State.Cookie.Trim()
                : null;
        }
        if (ext is null)
        {
            throw CloudLyricsException.ProviderUnavailable(extensionId);
        }

        string script = File.ReadAllText(ext.EntryScriptPath, Encoding.UTF8);
        List<JintExtensionRuntime.ExtLyricsCandidateSpec> specs = await _runtime.SearchLyricsAsync(
            ext.Manifest,
            script,
            query,
            proxyUrl: proxyUrl,
            host: this,
            cookie: cookie,
            ct: ct).ConfigureAwait(false);

        var candidates = new List<CloudLyricsCandidate>(specs.Count);
        for (int i = 0; i < specs.Count; i++)
        {
            var s = specs[i];
            string title = (s.Title ?? string.Empty).Trim();
            string artist = (s.Artist ?? string.Empty).Trim();
            string? synced = string.IsNullOrWhiteSpace(s.SyncedLyrics) ? null : s.SyncedLyrics.Trim();
            string? plain = string.IsNullOrWhiteSpace(s.PlainLyrics) ? null : s.PlainLyrics.Trim();
            string? trans = string.IsNullOrWhiteSpace(s.TranslatedLyrics) ? null : s.TranslatedLyrics.Trim();
            bool isInst = s.Instrumental == true;
            string itemId = string.IsNullOrWhiteSpace(s.ExternalId) ? $"row-{i}" : s.ExternalId.Trim();

            var cand = new CloudLyricsCandidate(
                Id: $"ext.{ext.Id}:{itemId}",
                ProviderId: $"ext.{ext.Id}",
                SourceName: ext.Manifest.EffectiveBadgeLabel,
                Title: title.Length > 0 ? title : query.TrimmedTitle,
                Artist: artist.Length > 0 ? artist : query.TrimmedArtist,
                Album: string.IsNullOrWhiteSpace(s.Album) ? null : s.Album.Trim(),
                DurationSec: s.DurationSec,
                IsInstrumental: isInst,
                SyncedLyrics: synced,
                PlainLyrics: plain,
                TranslatedLyrics: trans);
            if (cand.HasUsableContent)
            {
                candidates.Add(cand);
            }
        }
        return candidates;
    }

    public async Task<InstalledExtension> ImportFromFileAsync(string filePath, CancellationToken ct = default)
    {
        LoadedExtensionPackage pkg = await Task.Run(() => ExtensionPackageLoader.LoadFromFile(filePath), ct)
            .ConfigureAwait(false);
        return StageImportedPackage(pkg, sourceLocation: filePath, sourceUrl: null);
    }

    public async Task<IReadOnlyList<InstalledExtension>> ImportPackagesFromFileAsync(string filePath, CancellationToken ct = default)
    {
        IReadOnlyList<LoadedExtensionPackage> packages = await Task.Run(() => ExtensionPackageLoader.LoadPackagesFromFile(filePath), ct)
            .ConfigureAwait(false);
        var installed = new List<InstalledExtension>(packages.Count);
        foreach (LoadedExtensionPackage pkg in packages)
        {
            installed.Add(StageImportedPackage(pkg, sourceLocation: filePath, sourceUrl: null));
        }
        return installed;
    }

    public async Task<IReadOnlyList<InstalledExtension>> ImportPackagesFromFilesAsync(
        IEnumerable<string> filePaths,
        CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(filePaths);
        var byId = new Dictionary<string, InstalledExtension>(StringComparer.OrdinalIgnoreCase);
        var orderedIds = new List<string>();
        foreach (string path in filePaths)
        {
            if (string.IsNullOrWhiteSpace(path)) continue;
            IReadOnlyList<InstalledExtension> batch = await ImportPackagesFromFileAsync(path, ct).ConfigureAwait(false);
            foreach (InstalledExtension ext in batch)
            {
                if (!byId.ContainsKey(ext.Id))
                {
                    orderedIds.Add(ext.Id);
                }
                byId[ext.Id] = ext;
            }
        }
        return orderedIds.Select(id => byId[id]).ToList();
    }

    public async Task<InstalledExtension> ImportFromUrlAsync(
        string url,
        string? proxyUrl = null,
        CancellationToken ct = default)
    {
        if (!TryNormalizeHttpsUrl(url, out string? normalizedUrl) || normalizedUrl is null)
        {
            throw new ArgumentException("Extension URL must be a valid https:// URL.", nameof(url));
        }

        LoadedExtensionPackage pkg = await DownloadPackageAsync(normalizedUrl, proxyUrl, ct).ConfigureAwait(false);
        return StageImportedPackage(pkg, sourceLocation: normalizedUrl, sourceUrl: normalizedUrl);
    }

    public async Task<IReadOnlyList<InstalledExtension>> ImportPackagesFromUrlAsync(
        string url,
        string? proxyUrl = null,
        CancellationToken ct = default)
    {
        if (!TryNormalizeHttpsUrl(url, out string? normalizedUrl) || normalizedUrl is null)
        {
            throw new ArgumentException("Extension URL must be a valid https:// URL.", nameof(url));
        }

        IReadOnlyList<LoadedExtensionPackage> packages = await DownloadPackagesAsync(normalizedUrl, proxyUrl, ct).ConfigureAwait(false);
        string? singleSourceUrl = packages.Count == 1 ? normalizedUrl : null;
        var installed = new List<InstalledExtension>(packages.Count);
        foreach (LoadedExtensionPackage pkg in packages)
        {
            installed.Add(StageImportedPackage(pkg, sourceLocation: normalizedUrl, sourceUrl: singleSourceUrl));
        }
        return installed;
    }

    public async Task<ExtensionUpdateCheckSummary> CheckForUpdatesIfDueAsync(
        DateTimeOffset? nowUtc = null,
        string? proxyUrl = null,
        CancellationToken ct = default)
    {
        DateTimeOffset now = nowUtc ?? DateTimeOffset.UtcNow;
        lock (_stateLock)
        {
            if (!_autoUpdateEnabled)
            {
                return new ExtensionUpdateCheckSummary(WasSkippedByThrottleOrSetting: true, Array.Empty<ExtensionUpdateItemResult>());
            }

            if (_lastUpdateCheckUtc is DateTimeOffset last && (now - last) < AutoUpdateInterval)
            {
                return new ExtensionUpdateCheckSummary(WasSkippedByThrottleOrSetting: true, Array.Empty<ExtensionUpdateItemResult>());
            }
        }

        return await CheckForUpdatesAsync(extensionId: null, proxyUrl: proxyUrl, nowUtc: now, ct: ct)
            .ConfigureAwait(false);
    }

    public async Task<ExtensionUpdateCheckSummary> CheckForUpdatesAsync(
        string? extensionId = null,
        string? proxyUrl = null,
        DateTimeOffset? nowUtc = null,
        CancellationToken ct = default)
    {
        await _updateLock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            DateTimeOffset now = nowUtc ?? DateTimeOffset.UtcNow;
            List<InstalledExtension> targets;
            lock (_stateLock)
            {
                if (string.IsNullOrWhiteSpace(extensionId))
                {
                    _lastUpdateCheckUtc = now;
                    SaveStateLocked();
                    targets = _extensions.ToList();
                }
                else
                {
                    string targetId = extensionId.Trim();
                    targets = _extensions
                        .Where(e => string.Equals(e.Id, targetId, StringComparison.OrdinalIgnoreCase))
                        .ToList();
                }
            }

            var results = new List<ExtensionUpdateItemResult>(targets.Count);
            bool anyChange = false;
            bool anyUpdateStateChange = false;

            foreach (var installed in targets)
            {
                ct.ThrowIfCancellationRequested();
                var itemResult = await CheckSingleExtensionUpdateAsync(installed, proxyUrl, ct).ConfigureAwait(false);
                results.Add(itemResult);
                if (itemResult.Outcome == ExtensionUpdateOutcome.AppliedSilently)
                {
                    anyChange = true;
                    anyUpdateStateChange = true;
                }
                else if (itemResult.Outcome == ExtensionUpdateOutcome.StagedForReview)
                {
                    anyUpdateStateChange = true;
                }
            }

            if (anyChange)
            {
                SyncToRegistry();
                Changed?.Invoke();
            }
            if (anyUpdateStateChange)
            {
                UpdatesChanged?.Invoke();
            }

            return new ExtensionUpdateCheckSummary(WasSkippedByThrottleOrSetting: false, results);
        }
        finally
        {
            _updateLock.Release();
        }
    }

    public InstalledExtension ApprovePendingUpdate(string extensionId)
    {
        if (string.IsNullOrWhiteSpace(extensionId))
        {
            throw new ArgumentException("Extension id is required.", nameof(extensionId));
        }

        InstalledExtension applied;
        lock (_stateLock)
        {
            if (!_pendingUpdates.TryGetValue(extensionId.Trim(), out var pending))
            {
                throw new KeyNotFoundException($"No pending update for extension '{extensionId}'.");
            }

            applied = ApplyPackageUpdateLocked(pending.ExtensionId, pending.Package, clearPendingReview: true);
            _pendingUpdates.Remove(pending.ExtensionId);
            SaveStateLocked();
        }

        SyncToRegistry();
        UpdatesChanged?.Invoke();
        Changed?.Invoke();
        return applied;
    }

    public bool DismissPendingUpdate(string extensionId)
    {
        if (string.IsNullOrWhiteSpace(extensionId)) return false;
        bool removed;
        lock (_stateLock)
        {
            removed = _pendingUpdates.Remove(extensionId.Trim());
        }
        if (removed)
        {
            UpdatesChanged?.Invoke();
            Changed?.Invoke();
        }
        return removed;
    }

    private async Task<ExtensionUpdateItemResult> CheckSingleExtensionUpdateAsync(
        InstalledExtension installed,
        string? proxyUrl,
        CancellationToken ct)
    {
        string? updateUrl = ResolveUpdateUrl(installed);
        if (updateUrl is null)
        {
            return new ExtensionUpdateItemResult(
                installed.Id,
                null,
                ExtensionUpdateOutcome.SkippedNoUrl,
                InstalledVersion: installed.Manifest.Version);
        }

        try
        {
            LoadedExtensionPackage remotePkg = await DownloadPackageAsync(updateUrl, proxyUrl, ct).ConfigureAwait(false);

            if (!string.Equals(remotePkg.Manifest.Id, installed.Manifest.Id, StringComparison.Ordinal))
            {
                return new ExtensionUpdateItemResult(
                    installed.Id,
                    updateUrl,
                    ExtensionUpdateOutcome.Failed,
                    InstalledVersion: installed.Manifest.Version,
                    RemoteVersion: remotePkg.Manifest.Version,
                    ErrorMessage: $"Extension update id '{remotePkg.Manifest.Id}' does not match '{installed.Manifest.Id}'.");
            }

            if (!ExtensionVersionComparer.IsStrictlyNewer(remotePkg.Manifest.Version, installed.Manifest.Version))
            {
                lock (_stateLock)
                {
                    _pendingUpdates.Remove(installed.Id);
                }
                return new ExtensionUpdateItemResult(
                    installed.Id,
                    updateUrl,
                    ExtensionUpdateOutcome.UpToDate,
                    InstalledVersion: installed.Manifest.Version,
                    RemoteVersion: remotePkg.Manifest.Version);
            }

            ExtensionPermissionEscalation escalation = ExtensionPermissionAnalyzer.AnalyzeEscalation(
                installed.Manifest,
                remotePkg.Manifest);

            if (escalation.HasEscalation || installed.PendingPermissionReview)
            {
                var pending = new PendingExtensionUpdate(
                    ExtensionId: installed.Id,
                    UpdateUrl: updateUrl,
                    InstalledVersion: installed.Manifest.Version,
                    NewVersion: remotePkg.Manifest.Version,
                    Package: remotePkg,
                    AddedCapabilities: escalation.AddedCapabilities,
                    AddedHosts: escalation.AddedHosts,
                    PreviousManifest: installed.Manifest);

                lock (_stateLock)
                {
                    _pendingUpdates[installed.Id] = pending;
                }

                return new ExtensionUpdateItemResult(
                    installed.Id,
                    updateUrl,
                    ExtensionUpdateOutcome.StagedForReview,
                    InstalledVersion: installed.Manifest.Version,
                    RemoteVersion: remotePkg.Manifest.Version);
            }

            lock (_stateLock)
            {
                ApplyPackageUpdateLocked(installed.Id, remotePkg, clearPendingReview: false);
                _pendingUpdates.Remove(installed.Id);
                SaveStateLocked();
            }

            return new ExtensionUpdateItemResult(
                installed.Id,
                updateUrl,
                ExtensionUpdateOutcome.AppliedSilently,
                InstalledVersion: installed.Manifest.Version,
                RemoteVersion: remotePkg.Manifest.Version);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            return new ExtensionUpdateItemResult(
                installed.Id,
                updateUrl,
                ExtensionUpdateOutcome.Failed,
                InstalledVersion: installed.Manifest.Version,
                ErrorMessage: ex.Message);
        }
    }

    private async Task<LoadedExtensionPackage> DownloadPackageAsync(
        string httpsUrl,
        string? proxyUrl,
        CancellationToken ct)
    {
        IReadOnlyList<LoadedExtensionPackage> packages = await DownloadPackagesAsync(httpsUrl, proxyUrl, ct).ConfigureAwait(false);
        if (packages.Count != 1)
        {
            throw new InvalidDataException($"Downloaded archive contains {packages.Count} extensions; expected a single extension package.");
        }
        return packages[0];
    }

    private async Task<IReadOnlyList<LoadedExtensionPackage>> DownloadPackagesAsync(
        string httpsUrl,
        string? proxyUrl,
        CancellationToken ct)
    {
        if (!TryNormalizeHttpsUrl(httpsUrl, out string? normalized) || normalized is null ||
            !Uri.TryCreate(normalized, UriKind.Absolute, out var uri))
        {
            throw new ArgumentException("Extension URL must be a valid https:// URL.", nameof(httpsUrl));
        }

        using var http = CloudHttpHelper.CreateClient(proxyUrl, _httpHandler, TimeSpan.FromSeconds(30));
        using var resp = await http.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        resp.EnsureSuccessStatusCode();

        if (resp.RequestMessage?.RequestUri is Uri finalUri &&
            !string.Equals(finalUri.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidDataException("Extension download redirected to a non-HTTPS URL.");
        }

        await using var stream = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        using var ms = new MemoryStream();
        byte[] buffer = new byte[16384];
        long total = 0;
        while (true)
        {
            int read = await stream.ReadAsync(buffer.AsMemory(0, buffer.Length), ct).ConfigureAwait(false);
            if (read <= 0) break;
            total += read;
            if (total > ExtensionPackageLoader.MaxUncompressedBytes)
            {
                throw new InvalidDataException(
                    $"Downloaded extension exceeds maximum size of {ExtensionPackageLoader.MaxUncompressedBytes / (1024 * 1024)} MB.");
            }
            ms.Write(buffer, 0, read);
        }

        byte[] bytes = ms.ToArray();
        string? hint = Path.GetFileName((resp.RequestMessage?.RequestUri ?? uri).AbsolutePath);
        return await Task.Run(() => ExtensionPackageLoader.LoadPackagesFromBytes(bytes, hint), ct)
            .ConfigureAwait(false);
    }

    private InstalledExtension ApplyPackageUpdateLocked(
        string extensionId,
        LoadedExtensionPackage pkg,
        bool clearPendingReview)
    {
        int idx = FindIndexLocked(extensionId);
        if (idx < 0)
        {
            throw new KeyNotFoundException($"Extension '{extensionId}' is not installed.");
        }

        var existing = _extensions[idx];
        string extDir = GetExtensionDirectory(pkg.Manifest.Id);
        ExtensionPackageLoader.InstallToDirectory(pkg, extDir);

        var settings = new Dictionary<string, JsonElement>(existing.State.Settings, StringComparer.Ordinal);
        bool nextPendingReview = clearPendingReview ? false : existing.State.PendingPermissionReview;
        bool nextEnabled = clearPendingReview && existing.State.PendingPermissionReview
            ? true
            : existing.State.Enabled;

        var state = new ExtensionStateRecord
        {
            Id = pkg.Manifest.Id,
            Enabled = nextEnabled,
            PendingPermissionReview = nextPendingReview,
            Format = pkg.Format,
            SourceLocation = existing.State.SourceLocation,
            SourceUrl = existing.State.SourceUrl,
            InstalledUtc = DateTimeOffset.UtcNow,
            Cookie = pkg.Manifest.SupportsCookieAuth ? existing.State.Cookie : null,
            Settings = settings,
            StreamQualityId = pkg.Manifest.FindQuality(existing.State.StreamQualityId)?.Id.Trim(),
            DownloadQualityId = pkg.Manifest.FindQuality(existing.State.DownloadQualityId)?.Id.Trim()
        };

        var updated = BuildInstalledExtension(pkg.Manifest, state, extDir);
        _extensions[idx] = updated;
        return updated;
    }

    public InstalledExtension StageImportedPackage(
        LoadedExtensionPackage pkg,
        string? sourceLocation = null,
        string? sourceUrl = null)
    {
        ArgumentNullException.ThrowIfNull(pkg);
        string extDir = GetExtensionDirectory(pkg.Manifest.Id);
        ExtensionPackageLoader.InstallToDirectory(pkg, extDir);

        string? normalizedSourceUrl = TryNormalizeHttpsUrl(sourceUrl, out string? su)
            ? su
            : null;

        InstalledExtension installed;
        lock (_stateLock)
        {
            var existing = _extensions.FirstOrDefault(e =>
                string.Equals(e.Id, pkg.Manifest.Id, StringComparison.OrdinalIgnoreCase));

            var settings = existing is not null
                ? new Dictionary<string, JsonElement>(existing.State.Settings, StringComparer.Ordinal)
                : new Dictionary<string, JsonElement>(StringComparer.Ordinal);

            var state = new ExtensionStateRecord
            {
                Id = pkg.Manifest.Id,
                Enabled = false,
                PendingPermissionReview = true,
                Format = pkg.Format,
                SourceLocation = sourceLocation,
                SourceUrl = normalizedSourceUrl,
                InstalledUtc = DateTimeOffset.UtcNow,
                Cookie = pkg.Manifest.SupportsCookieAuth ? existing?.State.Cookie : null,
                Settings = settings,
                StreamQualityId = pkg.Manifest.FindQuality(existing?.State.StreamQualityId)?.Id.Trim(),
                DownloadQualityId = pkg.Manifest.FindQuality(existing?.State.DownloadQualityId)?.Id.Trim()
            };

            installed = BuildInstalledExtension(pkg.Manifest, state, extDir);

            _extensions.RemoveAll(e => string.Equals(e.Id, pkg.Manifest.Id, StringComparison.OrdinalIgnoreCase));
            _extensions.Add(installed);
            _pendingUpdates.Remove(pkg.Manifest.Id);
            SaveStateLocked();
        }

        SyncToRegistry();
        UpdatesChanged?.Invoke();
        Changed?.Invoke();
        return installed;
    }

    public void ApproveAndEnable(string extensionId)
    {
        lock (_stateLock)
        {
            int idx = FindIndexLocked(extensionId);
            if (idx < 0)
            {
                throw new KeyNotFoundException($"Extension '{extensionId}' is not installed.");
            }
            var ext = _extensions[idx];
            ext.State.PendingPermissionReview = false;
            ext.State.Enabled = true;
            SaveStateLocked();
        }

        SyncToRegistry();
        Changed?.Invoke();
    }

    public void ApproveAndEnableBatch(IEnumerable<string> extensionIds)
    {
        ArgumentNullException.ThrowIfNull(extensionIds);
        bool anyChanged = false;
        lock (_stateLock)
        {
            foreach (string rawId in extensionIds)
            {
                if (string.IsNullOrWhiteSpace(rawId)) continue;
                int idx = FindIndexLocked(rawId);
                if (idx < 0) continue;
                var ext = _extensions[idx];
                ext.State.PendingPermissionReview = false;
                ext.State.Enabled = true;
                anyChanged = true;
            }
            if (anyChanged)
            {
                SaveStateLocked();
            }
        }

        if (anyChanged)
        {
            SyncToRegistry();
            Changed?.Invoke();
        }
    }

    public void SetEnabled(string extensionId, bool enabled)
    {
        lock (_stateLock)
        {
            int idx = FindIndexLocked(extensionId);
            if (idx < 0) return;

            var ext = _extensions[idx];
            if (enabled && ext.State.PendingPermissionReview)
            {
                throw new InvalidOperationException("Extension permissions must be reviewed and approved before enabling.");
            }
            if (ext.State.Enabled == enabled) return;

            ext.State.Enabled = enabled;
            SaveStateLocked();
        }

        SyncToRegistry();
        Changed?.Invoke();
    }

    public bool Uninstall(string extensionId)
    {
        if (string.IsNullOrWhiteSpace(extensionId)) return false;

        string? dirToDelete = null;
        lock (_stateLock)
        {
            int idx = FindIndexLocked(extensionId);
            if (idx < 0) return false;
            dirToDelete = _extensions[idx].DirectoryPath;
            _extensions.RemoveAt(idx);
            _pendingUpdates.Remove(extensionId.Trim());
            SaveStateLocked();
        }

        lock (_logsLock)
        {
            _logs.Remove(extensionId);
        }
        lock (_kvLock)
        {
            _kvCache.Remove(extensionId);
        }

        if (!string.IsNullOrWhiteSpace(dirToDelete) && Directory.Exists(dirToDelete))
        {
            try { Directory.Delete(dirToDelete, recursive: true); } catch { /* best effort */ }
        }

        SyncToRegistry();
        Changed?.Invoke();
        return true;
    }

    public string ReadScriptSource(InstalledExtension ext)
    {
        ArgumentNullException.ThrowIfNull(ext);
        if (!File.Exists(ext.EntryScriptPath))
        {
            throw new CloudServiceException(
                CloudErrorCode.ExtensionUnavailable,
                $"Extension entry script is missing for '{ext.Id}'.");
        }
        return File.ReadAllText(ext.EntryScriptPath, Encoding.UTF8);
    }

    // Typed Settings

    public string? GetSettingJson(string extensionId, string key)
    {
        if (string.IsNullOrWhiteSpace(extensionId) || string.IsNullOrWhiteSpace(key)) return null;
        lock (_stateLock)
        {
            var ext = _extensions.FirstOrDefault(e => string.Equals(e.Id, extensionId, StringComparison.OrdinalIgnoreCase));
            if (ext is null) return null;

            if (ext.State.Settings.TryGetValue(key, out var val))
            {
                return val.GetRawText();
            }

            var spec = ext.Manifest.Settings?.FirstOrDefault(s => string.Equals(s.Key, key, StringComparison.Ordinal));
            return spec?.Default is JsonElement defVal ? defVal.GetRawText() : null;
        }
    }

    public bool GetSettingBool(string extensionId, string key)
    {
        string? raw = GetSettingJson(extensionId, key);
        if (string.IsNullOrWhiteSpace(raw)) return false;
        try
        {
            using var doc = JsonDocument.Parse(raw);
            return doc.RootElement.ValueKind switch
            {
                JsonValueKind.True => true,
                JsonValueKind.False => false,
                JsonValueKind.String when bool.TryParse(doc.RootElement.GetString(), out bool b) => b,
                _ => false
            };
        }
        catch
        {
            return false;
        }
    }

    public string GetSettingString(string extensionId, string key)
    {
        string? raw = GetSettingJson(extensionId, key);
        if (string.IsNullOrWhiteSpace(raw)) return string.Empty;
        try
        {
            using var doc = JsonDocument.Parse(raw);
            return doc.RootElement.ValueKind switch
            {
                JsonValueKind.String => doc.RootElement.GetString() ?? string.Empty,
                JsonValueKind.Null or JsonValueKind.Undefined => string.Empty,
                JsonValueKind.True => "true",
                JsonValueKind.False => "false",
                _ => doc.RootElement.ToString()
            };
        }
        catch
        {
            return string.Empty;
        }
    }

    public void SetSettingValue(string extensionId, string key, bool value) =>
        SetSettingJsonElement(extensionId, key, JsonSerializer.SerializeToElement(value, ExtensionJson.Options));

    public void SetSettingValue(string extensionId, string key, string? value) =>
        SetSettingJsonElement(extensionId, key, JsonSerializer.SerializeToElement(value ?? string.Empty, ExtensionJson.Options));

    public void SetSettingJsonElement(string extensionId, string key, JsonElement value)
    {
        if (string.IsNullOrWhiteSpace(extensionId) || string.IsNullOrWhiteSpace(key)) return;
        lock (_stateLock)
        {
            int idx = FindIndexLocked(extensionId);
            if (idx < 0) return;

            _extensions[idx].State.Settings[key] = value.Clone();
            SaveStateLocked();
        }
        Changed?.Invoke();
    }

    // TTL-capable KV store (kv.json)

    public string? KvGet(string extensionId, string key)
    {
        if (string.IsNullOrWhiteSpace(extensionId) || string.IsNullOrWhiteSpace(key)) return null;
        lock (_kvLock)
        {
            var dict = LoadKvDictLocked(extensionId);
            if (!dict.TryGetValue(key, out var entry)) return null;

            if (entry.ExpiresAtMs is long exp && DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() >= exp)
            {
                dict.Remove(key);
                SaveKvDictLocked(extensionId, dict);
                return null;
            }
            return entry.Value;
        }
    }

    public void KvSet(string extensionId, string key, string value, double? ttlSeconds = null)
    {
        if (string.IsNullOrWhiteSpace(extensionId) || string.IsNullOrWhiteSpace(key)) return;
        long? expiresAtMs = ttlSeconds is double ttl && !double.IsNaN(ttl) && !double.IsInfinity(ttl) && ttl > 0
            ? DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + (long)Math.Round(ttl * 1000.0)
            : null;

        lock (_kvLock)
        {
            var dict = LoadKvDictLocked(extensionId);
            dict[key] = new ExtensionKvEntry(value ?? string.Empty, expiresAtMs);
            SaveKvDictLocked(extensionId, dict);
        }
    }

    public void KvRemove(string extensionId, string key)
    {
        if (string.IsNullOrWhiteSpace(extensionId) || string.IsNullOrWhiteSpace(key)) return;
        lock (_kvLock)
        {
            var dict = LoadKvDictLocked(extensionId);
            if (dict.Remove(key))
            {
                SaveKvDictLocked(extensionId, dict);
            }
        }
    }

    // 200-entry ring buffer logs

    public void AppendLog(string extensionId, ExtensionLogLevel level, string message)
    {
        if (string.IsNullOrWhiteSpace(extensionId)) return;
        string sanitized = RedactCookieFromMessage(extensionId, message ?? string.Empty);
        var entry = new ExtensionLogEntry(
            DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            level,
            sanitized);

        lock (_logsLock)
        {
            if (!_logs.TryGetValue(extensionId, out var list))
            {
                list = new List<ExtensionLogEntry>();
                _logs[extensionId] = list;
            }
            list.Add(entry);
            while (list.Count > MaxLogEntriesPerExtension)
            {
                list.RemoveAt(0);
            }
        }

        LogsChanged?.Invoke(extensionId);
    }

    public IReadOnlyList<ExtensionLogEntry> GetLogs(string extensionId)
    {
        if (string.IsNullOrWhiteSpace(extensionId)) return Array.Empty<ExtensionLogEntry>();
        lock (_logsLock)
        {
            return _logs.TryGetValue(extensionId, out var list)
                ? list.ToList()
                : Array.Empty<ExtensionLogEntry>();
        }
    }

    public void ClearLogs(string extensionId)
    {
        if (string.IsNullOrWhiteSpace(extensionId)) return;
        bool changed;
        lock (_logsLock)
        {
            changed = _logs.Remove(extensionId);
        }
        if (changed)
        {
            LogsChanged?.Invoke(extensionId);
        }
    }

    private string RedactCookieFromMessage(string extensionId, string message)
    {
        if (string.IsNullOrEmpty(message)) return string.Empty;
        string? cookie = GetCookie(extensionId);
        if (string.IsNullOrWhiteSpace(cookie) || cookie.Length < 3) return message;

        string redacted = message.Replace(cookie, "[REDACTED]", StringComparison.Ordinal);
        string escaped = Uri.EscapeDataString(cookie);
        if (!string.Equals(escaped, cookie, StringComparison.Ordinal) && escaped.Length >= 3)
        {
            redacted = redacted.Replace(escaped, "[REDACTED]", StringComparison.OrdinalIgnoreCase);
        }
        return redacted;
    }

    private void LoadFromDisk()
    {
        var stateFile = Persistence.Load<ExtensionStateFile>(StateFilePath);
        if (stateFile is null) return;

        bool prunedMissing = false;
        lock (_stateLock)
        {
            _autoUpdateEnabled = stateFile.AutoUpdateEnabled ?? true;
            _lastUpdateCheckUtc = stateFile.LastUpdateCheckUtc;

            if (stateFile.Extensions is null || stateFile.Extensions.Count == 0)
            {
                return;
            }

            foreach (var state in stateFile.Extensions)
            {
                if (string.IsNullOrWhiteSpace(state.Id)) continue;
                if (string.IsNullOrWhiteSpace(state.SourceUrl) &&
                    TryNormalizeHttpsUrl(state.SourceLocation, out string? migratedSourceUrl))
                {
                    state.SourceUrl = migratedSourceUrl;
                }
                string extDir = GetExtensionDirectory(state.Id);
                string manifestPath = Path.Combine(extDir, "manifest.json");
                if (!File.Exists(manifestPath))
                {
                    prunedMissing = true;
                    continue;
                }

                try
                {
                    byte[] bytes = File.ReadAllBytes(manifestPath);
                    var manifest = JsonSerializer.Deserialize<ExtensionManifest>(bytes, ExtensionJson.Options);
                    if (manifest is null)
                    {
                        prunedMissing = true;
                        continue;
                    }
                    ExtensionManifestValidator.Validate(manifest);
                    var installed = BuildInstalledExtension(manifest, state, extDir);
                    if (!File.Exists(installed.EntryScriptPath))
                    {
                        prunedMissing = true;
                        continue;
                    }
                    _extensions.Add(installed);
                }
                catch
                {
                    prunedMissing = true;
                }
            }

            if (prunedMissing)
            {
                SaveStateLocked();
            }
        }
    }

    private static InstalledExtension BuildInstalledExtension(
        ExtensionManifest manifest,
        ExtensionStateRecord state,
        string extDir)
    {
        string entryPath = Path.Combine(
            extDir,
            manifest.EffectiveEntry.Replace('\\', '/').TrimStart('/').Replace('/', Path.DirectorySeparatorChar));
        string iconCandidate = Path.Combine(extDir, "icon.png");
        string? iconPath = File.Exists(iconCandidate) ? iconCandidate : null;

        return new InstalledExtension(
            Manifest: manifest,
            State: state,
            DirectoryPath: extDir,
            EntryScriptPath: entryPath,
            IconPath: iconPath);
    }

    private string GetExtensionDirectory(string extensionId) =>
        Path.Combine(ExtensionsDirectory, extensionId);

    private int FindIndexLocked(string extensionId) =>
        _extensions.FindIndex(e => string.Equals(e.Id, extensionId, StringComparison.OrdinalIgnoreCase));

    private void SaveStateLocked()
    {
        var stateFile = new ExtensionStateFile
        {
            Extensions = _extensions.Select(e => e.State).ToList(),
            AutoUpdateEnabled = _autoUpdateEnabled,
            LastUpdateCheckUtc = _lastUpdateCheckUtc
        };
        Persistence.Save(stateFile, StateFilePath);
    }

    private Dictionary<string, ExtensionKvEntry> LoadKvDictLocked(string extensionId)
    {
        if (_kvCache.TryGetValue(extensionId, out var cached))
        {
            return cached;
        }

        string kvPath = Path.Combine(GetExtensionDirectory(extensionId), "kv.json");
        var loaded = Persistence.Load<Dictionary<string, ExtensionKvEntry>>(kvPath)
                     ?? new Dictionary<string, ExtensionKvEntry>(StringComparer.Ordinal);
        var normalized = new Dictionary<string, ExtensionKvEntry>(loaded, StringComparer.Ordinal);
        _kvCache[extensionId] = normalized;
        return normalized;
    }

    private void SaveKvDictLocked(string extensionId, Dictionary<string, ExtensionKvEntry> dict)
    {
        string extDir = GetExtensionDirectory(extensionId);
        if (!Directory.Exists(extDir)) return;
        string kvPath = Path.Combine(extDir, "kv.json");
        try
        {
            Persistence.Save(dict, kvPath);
        }
        catch
        {
            // Best-effort KV persistence.
        }
    }
}
