using MusicoWindows.Cloud;
using MusicoWindows.Models;
using MusicoWindows.Stores;

namespace MusicoWindows.Extensions;

public sealed record CloudSearchError(
    CloudErrorCode Code,
    string Message,
    bool ExtensionSupportsCookie);

/// <summary>
/// Framework-agnostic state machine managing extension-only cloud search,
/// kind filtering, cursor pagination with dedup/cycle guards, and queue creation.
/// </summary>
public sealed class CloudSearchController
{
    private readonly ExtensionStore _extensions;
    private readonly List<ExtSearchResultItem> _results = new();
    private readonly HashSet<(ExtensionSearchKind Kind, string Id)> _seenKeys = new();
    private readonly HashSet<string> _seenCursors = new(StringComparer.Ordinal);

    private CancellationTokenSource? _activeCts;
    private int _requestVersion;
    private string? _lastSubmittedQuery;

    public CloudSearchController(ExtensionStore extensions)
    {
        _extensions = extensions ?? throw new ArgumentNullException(nameof(extensions));
        AvailableSources = _extensions.ActiveSearchExtensions;
    }

    public event Action? StateChanged;

    public IReadOnlyList<CloudSearchExtensionDescriptor> AvailableSources { get; private set; }
    public string? SelectedExtensionId { get; private set; }
    public bool IsCloudMode => SelectedExtensionId is not null;
    public ExtensionSearchKind? SelectedKind { get; private set; }

    public CloudSearchExtensionDescriptor? ActiveExtension =>
        SelectedExtensionId is null
            ? null
            : AvailableSources.FirstOrDefault(e =>
                string.Equals(e.ExtensionId, SelectedExtensionId, StringComparison.OrdinalIgnoreCase));

    public IReadOnlyList<ExtSearchResultItem> Results => _results;
    public string? NextCursor { get; private set; }
    public bool HasMore { get; private set; }
    public int? TotalCount { get; private set; }
    public bool IsLoading { get; private set; }
    public bool IsLoadingMore { get; private set; }
    public bool HasSearched { get; private set; }
    public string LastSubmittedQuery => _lastSubmittedQuery ?? string.Empty;
    public CloudSearchError? Error { get; private set; }

    /// <summary>
    /// Refreshes available search extensions from <see cref="ExtensionStore"/>.
    /// If the currently active extension was disabled or removed, resets cleanly to Local mode.
    /// Returns true if an active cloud source was forcefully reset to Local.
    /// </summary>
    public bool SyncExtensions()
    {
        AvailableSources = _extensions.ActiveSearchExtensions;
        if (SelectedExtensionId is not null && ActiveExtension is null)
        {
            SelectLocal();
            return true;
        }

        StateChanged?.Invoke();
        return false;
    }

    public void SelectLocal()
    {
        CancelInFlight();
        SelectedExtensionId = null;
        SelectedKind = null;
        ClearResultsState();
        StateChanged?.Invoke();
    }

    /// <summary>
    /// Switches to a cloud search extension. Auto-triggers one search ONLY when switching
    /// from another cloud source with a non-empty query; Local -> Cloud never auto-searches.
    /// </summary>
    public async Task SelectExtensionAsync(string extensionId, string? currentQuery, CancellationToken externalCt = default)
    {
        AvailableSources = _extensions.ActiveSearchExtensions;
        var target = AvailableSources.FirstOrDefault(e =>
            string.Equals(e.ExtensionId, extensionId, StringComparison.OrdinalIgnoreCase));
        if (target is null)
        {
            SelectLocal();
            return;
        }

        bool wasCloud = SelectedExtensionId is not null;
        bool changedSource = !string.Equals(SelectedExtensionId, target.ExtensionId, StringComparison.OrdinalIgnoreCase);
        CancelInFlight();
        SelectedExtensionId = target.ExtensionId;
        SelectedKind = target.SearchKinds.Count == 1 ? target.SearchKinds[0] : null;

        if (changedSource)
        {
            ClearResultsState();
        }

        string trimmedQuery = (currentQuery ?? string.Empty).Trim();
        if (wasCloud && changedSource && trimmedQuery.Length > 0)
        {
            await SubmitSearchAsync(trimmedQuery, ct: externalCt).ConfigureAwait(false);
        }
        else
        {
            StateChanged?.Invoke();
        }
    }

    public async Task SelectKindAsync(ExtensionSearchKind? kind, string? currentQuery, CancellationToken externalCt = default)
    {
        if (SelectedExtensionId is null) return;
        var ext = ActiveExtension;
        if (ext is null)
        {
            SelectLocal();
            return;
        }

        if (kind is ExtensionSearchKind k && !ext.SearchKinds.Contains(k))
        {
            return;
        }

        ExtensionSearchKind? nextKind = ext.SearchKinds.Count == 1 ? ext.SearchKinds[0] : kind;
        if (SelectedKind == nextKind) return;

        bool wasSearched = HasSearched;
        string activeQuery = !string.IsNullOrWhiteSpace(currentQuery)
            ? currentQuery!.Trim()
            : (_lastSubmittedQuery ?? string.Empty);

        SelectedKind = nextKind;
        CancelInFlight();
        ClearResultsState();

        if (wasSearched && activeQuery.Length > 0)
        {
            await SubmitSearchAsync(activeQuery, ct: externalCt).ConfigureAwait(false);
        }
        else
        {
            StateChanged?.Invoke();
        }
    }

    public async Task SubmitSearchAsync(string query, int limit = 30, CancellationToken ct = default)
    {
        if (SelectedExtensionId is null) return;

        var ext = ActiveExtension;
        if (ext is null)
        {
            SelectLocal();
            return;
        }

        string trimmed = (query ?? string.Empty).Trim();
        if (trimmed.Length == 0)
        {
            CancelInFlight();
            ClearResultsState();
            StateChanged?.Invoke();
            return;
        }

        CancelInFlight();
        int version = ++_requestVersion;
        var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _activeCts = cts;

        _lastSubmittedQuery = trimmed;
        _results.Clear();
        _seenKeys.Clear();
        _seenCursors.Clear();
        NextCursor = null;
        HasMore = false;
        TotalCount = null;
        Error = null;
        HasSearched = false;
        IsLoading = true;
        IsLoadingMore = false;
        StateChanged?.Invoke();

        ExtensionSearchKind? effectiveKind = ext.SearchKinds.Count == 1
            ? ext.SearchKinds[0]
            : SelectedKind;

        try
        {
            ExtSearchPage page = await _extensions.SearchAsync(
                ext.ExtensionId,
                trimmed,
                effectiveKind,
                cursor: null,
                limit: limit,
                proxyUrl: null,
                ct: cts.Token).ConfigureAwait(false);

            if (version != _requestVersion || cts.IsCancellationRequested) return;

            AppendPageItems(page, prevCursor: null);
            HasSearched = true;
            IsLoading = false;
            StateChanged?.Invoke();
        }
        catch (OperationCanceledException) when (cts.IsCancellationRequested || version != _requestVersion)
        {
            // Superseded or cancelled cleanly.
        }
        catch (CloudServiceException cse)
        {
            if (version != _requestVersion) return;
            IsLoading = false;
            HasSearched = true;
            Error = new CloudSearchError(cse.Code, cse.Message, ext.SupportsCookie);
            StateChanged?.Invoke();
        }
        catch (Exception ex)
        {
            if (version != _requestVersion) return;
            IsLoading = false;
            HasSearched = true;
            Error = new CloudSearchError(CloudErrorCode.ExtensionRuntimeError, ex.Message, ext.SupportsCookie);
            StateChanged?.Invoke();
        }
        finally
        {
            if (ReferenceEquals(_activeCts, cts))
            {
                _activeCts = null;
            }
            cts.Dispose();
        }
    }

    public async Task LoadMoreAsync(int limit = 30, CancellationToken ct = default)
    {
        if (SelectedExtensionId is null || IsLoading || IsLoadingMore || !HasMore || string.IsNullOrWhiteSpace(NextCursor))
        {
            return;
        }

        var ext = ActiveExtension;
        if (ext is null)
        {
            SelectLocal();
            return;
        }

        string query = _lastSubmittedQuery ?? string.Empty;
        if (query.Length == 0) return;

        string prevCursor = NextCursor!;
        int version = ++_requestVersion;
        var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _activeCts = cts;

        IsLoadingMore = true;
        Error = null;
        StateChanged?.Invoke();

        ExtensionSearchKind? effectiveKind = ext.SearchKinds.Count == 1
            ? ext.SearchKinds[0]
            : SelectedKind;

        try
        {
            ExtSearchPage page = await _extensions.SearchAsync(
                ext.ExtensionId,
                query,
                effectiveKind,
                cursor: prevCursor,
                limit: limit,
                proxyUrl: null,
                ct: cts.Token).ConfigureAwait(false);

            if (version != _requestVersion || cts.IsCancellationRequested) return;

            AppendPageItems(page, prevCursor);
            IsLoadingMore = false;
            StateChanged?.Invoke();
        }
        catch (OperationCanceledException) when (cts.IsCancellationRequested || version != _requestVersion)
        {
            // Cancelled cleanly.
        }
        catch (CloudServiceException cse)
        {
            if (version != _requestVersion) return;
            IsLoadingMore = false;
            Error = new CloudSearchError(cse.Code, cse.Message, ext.SupportsCookie);
            StateChanged?.Invoke();
        }
        catch (Exception ex)
        {
            if (version != _requestVersion) return;
            IsLoadingMore = false;
            Error = new CloudSearchError(CloudErrorCode.ExtensionRuntimeError, ex.Message, ext.SupportsCookie);
            StateChanged?.Invoke();
        }
        finally
        {
            if (ReferenceEquals(_activeCts, cts))
            {
                _activeCts = null;
            }
            cts.Dispose();
        }
    }

    public Task RetryAsync(int limit = 30, CancellationToken ct = default)
    {
        if (_results.Count > 0 && HasMore && !string.IsNullOrWhiteSpace(NextCursor))
        {
            return LoadMoreAsync(limit, ct);
        }
        return SubmitSearchAsync(_lastSubmittedQuery ?? string.Empty, limit, ct);
    }

    public Track ToEphemeralTrack(ExtSearchResultItem item)
    {
        string extId = SelectedExtensionId
            ?? throw new InvalidOperationException("No active cloud search extension.");
        return Track.MakeEphemeral(
            extensionId: extId,
            remoteTrackId: item.Id,
            title: item.Title,
            artist: item.Artist,
            album: item.Album,
            durationSeconds: item.DurationSec,
            remoteArtworkUrl: item.ArtworkUrl,
            trackNumber: item.TrackNumber,
            relativePath: item.RelativePath);
    }

    public (string QueueName, IReadOnlyList<Track> Tracks, int StartIndex)? PrepareTrackQueue(
        LibraryStore library,
        string selectedTrackItemId)
    {
        var ext = ActiveExtension;
        if (ext is null || string.IsNullOrWhiteSpace(selectedTrackItemId)) return null;

        var trackItems = _results.Where(r => r.Kind == ExtensionSearchKind.Track).ToList();
        int idx = trackItems.FindIndex(r => string.Equals(r.Id, selectedTrackItemId, StringComparison.Ordinal));
        if (idx < 0) return null;

        var tracks = trackItems.Select(r => Track.MakeEphemeral(
            extensionId: ext.ExtensionId,
            remoteTrackId: r.Id,
            title: r.Title,
            artist: r.Artist,
            album: r.Album,
            durationSeconds: r.DurationSec,
            remoteArtworkUrl: r.ArtworkUrl,
            trackNumber: r.TrackNumber,
            relativePath: r.RelativePath)).ToList();

        library.RegisterEphemeralTracks(tracks);
        string queueName = QueueStore.FormatCloudSearchQueueName(ext.DisplayName, LastSubmittedQuery);
        return (queueName, tracks, idx);
    }

    private void AppendPageItems(ExtSearchPage page, string? prevCursor)
    {
        if (prevCursor is not null)
        {
            _seenCursors.Add(prevCursor);
        }

        foreach (var item in page.Items)
        {
            if (_seenKeys.Add((item.Kind, item.Id)))
            {
                _results.Add(item);
            }
        }

        if (page.TotalCount is int tc)
        {
            TotalCount = tc;
        }

        string? next = string.IsNullOrWhiteSpace(page.NextCursor) ? null : page.NextCursor.Trim();
        bool cursorRepeated = next is not null &&
                              (string.Equals(next, prevCursor, StringComparison.Ordinal) ||
                               _seenCursors.Contains(next));

        if (!page.HasMore || next is null || cursorRepeated)
        {
            NextCursor = null;
            HasMore = false;
        }
        else
        {
            NextCursor = next;
            HasMore = true;
        }
    }

    private void ClearResultsState()
    {
        _results.Clear();
        _seenKeys.Clear();
        _seenCursors.Clear();
        NextCursor = null;
        HasMore = false;
        TotalCount = null;
        IsLoading = false;
        IsLoadingMore = false;
        HasSearched = false;
        Error = null;
        _lastSubmittedQuery = null;
    }

    private void CancelInFlight()
    {
        _requestVersion++;
        var prev = _activeCts;
        _activeCts = null;
        if (prev is not null)
        {
            try { prev.Cancel(); } catch { /* ignore */ }
        }
    }
}
