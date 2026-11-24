using System.Net.Http;

namespace MusicoWindows.Cloud;

/// <summary>
/// Coordinates on-demand cloud lyrics search across built-in providers (LRCLIB, NetEase)
/// and active JS extensions declaring `capabilities: ["lyrics"]`.
/// Never invoked automatically during track playback.
/// </summary>
public sealed class CloudLyricsService
{
    public delegate Task<List<CloudLyricsCandidate>> ExtensionLyricsSearcher(
        string extensionId,
        CloudLyricsQuery query,
        CancellationToken ct);

    private readonly ExtensionLyricsSearcher? _extensionSearch;

    public CloudLyricsService(
        HttpMessageHandler? customHandler = null,
        string appVersion = "1.2.3",
        ExtensionLyricsSearcher? extensionSearch = null)
    {
        Lrclib = new LrclibClient(customHandler, appVersion);
        Netease = new NeteaseLyricsClient(customHandler);
        _extensionSearch = extensionSearch;
    }

    public LrclibClient Lrclib { get; }
    public NeteaseLyricsClient Netease { get; }

    public static List<CloudLyricsSourceOption> BuildSourceOptions(
        IEnumerable<CloudLyricsExtensionDescriptor> extensions,
        string autoLabel)
    {
        var list = new List<CloudLyricsSourceOption>
        {
            new("auto", CloudLyricsSourceSelection.Auto, autoLabel),
            new("lrclib", CloudLyricsSourceSelection.Lrclib, "LRCLIB"),
            new("netease", CloudLyricsSourceSelection.Netease, "NetEase")
        };
        foreach (CloudLyricsExtensionDescriptor ext in extensions)
        {
            list.Add(new CloudLyricsSourceOption($"ext.{ext.Id}", CloudLyricsSourceSelection.Extension(ext.Id), ext.Name));
        }
        return list;
    }

    public async Task<CloudLyricsSearchResponse> SearchAsync(
        CloudLyricsQuery query,
        CloudLyricsSourceSelection source,
        IReadOnlyList<CloudLyricsExtensionDescriptor> activeExtensions,
        string? proxyUrl = null,
        CancellationToken ct = default)
    {
        if (query.IsEmpty) throw CloudLyricsException.EmptyQuery();

        switch (source.Kind)
        {
            case CloudLyricsSourceKind.Lrclib:
            {
                List<CloudLyricsCandidate> list = await Lrclib.SearchAsync(query, proxyUrl, ct).ConfigureAwait(false);
                return new CloudLyricsSearchResponse(list, []);
            }
            case CloudLyricsSourceKind.Netease:
            {
                List<CloudLyricsCandidate> list = await Netease.SearchAsync(query, proxyUrl, ct).ConfigureAwait(false);
                return new CloudLyricsSearchResponse(list, []);
            }
            case CloudLyricsSourceKind.Extension:
            {
                string extId = source.ExtensionId ?? string.Empty;
                CloudLyricsExtensionDescriptor? desc = activeExtensions.FirstOrDefault(e => e.Id == extId);
                if (desc is null || _extensionSearch is null)
                {
                    throw CloudLyricsException.ProviderUnavailable(extId);
                }
                List<CloudLyricsCandidate> raw = await _extensionSearch(extId, query, ct).ConfigureAwait(false);
                return new CloudLyricsSearchResponse(CloudLyricsScorer.RankAndDeduplicate(raw, query), []);
            }
            default:
                return await SearchAllSourcesAsync(query, activeExtensions, proxyUrl, ct).ConfigureAwait(false);
        }
    }

    private async Task<CloudLyricsSearchResponse> SearchAllSourcesAsync(
        CloudLyricsQuery query,
        IReadOnlyList<CloudLyricsExtensionDescriptor> activeExtensions,
        string? proxyUrl,
        CancellationToken ct)
    {
        var tasks = new List<Task<(bool Ok, List<CloudLyricsCandidate>? List, CloudLyricsSourceFailure? Failure)>>();

        tasks.Add(RunSourceAsync(
            CloudLyricsProviderIds.Lrclib,
            "LRCLIB",
            () => Lrclib.SearchAsync(query, proxyUrl, ct),
            ct));

        tasks.Add(RunSourceAsync(
            CloudLyricsProviderIds.Netease,
            "NetEase",
            () => Netease.SearchAsync(query, proxyUrl, ct),
            ct));

        if (_extensionSearch is not null)
        {
            foreach (CloudLyricsExtensionDescriptor ext in activeExtensions)
            {
                string capturedId = ext.Id;
                string capturedName = ext.Name;
                tasks.Add(RunSourceAsync(
                    $"ext.{capturedId}",
                    capturedName,
                    () => _extensionSearch(capturedId, query, ct),
                    ct));
            }
        }

        var outcomes = await Task.WhenAll(tasks).ConfigureAwait(false);
        int successCount = 0;
        var allCandidates = new List<CloudLyricsCandidate>();
        var failures = new List<CloudLyricsSourceFailure>();

        foreach (var outcome in outcomes)
        {
            if (outcome.Ok && outcome.List is not null)
            {
                successCount++;
                allCandidates.AddRange(outcome.List);
            }
            else if (outcome.Failure is not null)
            {
                failures.Add(outcome.Failure);
            }
        }

        if (successCount == 0 && failures.Count > 0)
        {
            throw new CloudLyricsException(failures[0].Message);
        }

        return new CloudLyricsSearchResponse(
            CloudLyricsScorer.RankAndDeduplicate(allCandidates, query),
            failures);
    }

    private static async Task<(bool Ok, List<CloudLyricsCandidate>? List, CloudLyricsSourceFailure? Failure)> RunSourceAsync(
        string providerId,
        string sourceName,
        Func<Task<List<CloudLyricsCandidate>>> runner,
        CancellationToken ct)
    {
        try
        {
            List<CloudLyricsCandidate> list = await runner().ConfigureAwait(false);
            return (true, list, null);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            return (false, null, new CloudLyricsSourceFailure(providerId, sourceName, ex.Message));
        }
    }
}
