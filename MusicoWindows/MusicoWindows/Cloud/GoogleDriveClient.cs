using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using MusicoWindows.Models;

namespace MusicoWindows.Cloud;

public enum GoogleDriveTargetKind
{
    Folder,
    File,
    Open
}

public sealed record GoogleDriveTarget(
    GoogleDriveTargetKind Kind,
    string Id,
    string? ResourceKey = null);

public sealed record GoogleDriveEntry(
    string Id,
    string Name,
    string MimeType,
    bool IsFolder,
    string? ResourceKey = null);

public sealed class GoogleDriveClient
{
    public const string DefaultUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    public const string FolderMimeType = "application/vnd.google-apps.folder";
    public const int MaxFolderDepth = 4;
    public const int MaxFolders = 30;

    private static readonly HashSet<string> SupportedAudioExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".mp3", ".m4a", ".aac", ".flac", ".wav", ".aif", ".aiff", ".wma", ".ogg", ".opus"
    };

    private static readonly Regex DriveIvdAssignRegex = new(
        @"window\s*(?:\[\s*['""]_DRIVE_ivd['""]\s*\]|\._DRIVE_ivd)\s*=\s*",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private static readonly Regex OgTitleRegex1 = new(
        @"<meta\b[^>]*property\s*=\s*[""']og:title[""'][^>]*content\s*=\s*[""']([^""']*)[""'][^>]*>",
        RegexOptions.IgnoreCase | RegexOptions.Singleline | RegexOptions.Compiled);

    private static readonly Regex OgTitleRegex2 = new(
        @"<meta\b[^>]*content\s*=\s*[""']([^""']*)[""'][^>]*property\s*=\s*[""']og:title[""'][^>]*>",
        RegexOptions.IgnoreCase | RegexOptions.Singleline | RegexOptions.Compiled);

    private static readonly Regex ItemPropNameRegex1 = new(
        @"<meta\b[^>]*itemprop\s*=\s*[""']name[""'][^>]*content\s*=\s*[""']([^""']*)[""'][^>]*>",
        RegexOptions.IgnoreCase | RegexOptions.Singleline | RegexOptions.Compiled);

    private static readonly Regex ItemPropNameRegex2 = new(
        @"<meta\b[^>]*content\s*=\s*[""']([^""']*)[""'][^>]*itemprop\s*=\s*[""']name[""'][^>]*>",
        RegexOptions.IgnoreCase | RegexOptions.Singleline | RegexOptions.Compiled);

    private static readonly Regex HtmlTitleRegex = new(
        @"<title\b[^>]*>(.*?)</title>",
        RegexOptions.IgnoreCase | RegexOptions.Singleline | RegexOptions.Compiled);

    private static readonly Regex TitleSuffixRegex = new(
        @"\s*[-–—]\s*(?:Google\s*Drive|Google\s*云端硬盘)\s*$",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private static readonly Regex FlipEntryRegex = new(
        @"class\s*=\s*[""'][^""']*(?<![-_])\bflip-entry\b(?![-_])[^""']*[""'][^>]*>(.*?)(?=<div\b[^>]*class\s*=\s*[""'][^""']*(?<![-_])\bflip-entry\b(?![-_])|</body>|$)",
        RegexOptions.IgnoreCase | RegexOptions.Singleline | RegexOptions.Compiled);

    private static readonly Regex FlipLinkRegex = new(
        @"href\s*=\s*[""']([^""']*(?:/file/d/|/drive/(?:u/\d+/)?folders/|/folders/)[^""']+)[""']",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private static readonly Regex FlipTitleRegex = new(
        @"class\s*=\s*[""'][^""']*\bflip-entry-title\b[^""']*[""'][^>]*>(.*?)</(?:div|span|a)>",
        RegexOptions.IgnoreCase | RegexOptions.Singleline | RegexOptions.Compiled);

    private static readonly Regex FormRegex = new(
        @"<form\b([^>]*)>(.*?)</form>",
        RegexOptions.IgnoreCase | RegexOptions.Singleline | RegexOptions.Compiled);

    private static readonly Regex InputRegex = new(
        @"<input\b([^>]*)/?>",
        RegexOptions.IgnoreCase | RegexOptions.Singleline | RegexOptions.Compiled);

    private static readonly Regex AttrRegex = new(
        @"([A-Za-z0-9_:-]+)\s*=\s*(?:""([^""]*)""|'([^']*)'|([^\s>]+))",
        RegexOptions.Compiled);

    private static readonly Regex UcNameLinkRegex = new(
        @"class\s*=\s*[""'][^""']*\buc-name-size\b[^""']*[""'][^>]*>\s*<a\b[^>]*>(.*?)</a>",
        RegexOptions.IgnoreCase | RegexOptions.Singleline | RegexOptions.Compiled);

    private static readonly Regex HtmlTagRegex = new(
        @"<[^>]+>",
        RegexOptions.Singleline | RegexOptions.Compiled);

    private readonly HttpMessageHandler? _handler;

    public GoogleDriveClient(HttpMessageHandler? handler = null)
    {
        _handler = handler;
    }

    public static GoogleDriveTarget ParseInput(string input)
    {
        string extracted = CloudUrlClassifier.ExtractCloudUrl(input);
        if (string.IsNullOrWhiteSpace(extracted))
        {
            throw new ArgumentException("Enter a Google Drive folder or file link.", nameof(input));
        }

        string withScheme = extracted.Contains("://", StringComparison.Ordinal) ? extracted : $"https://{extracted}";
        if (!Uri.TryCreate(withScheme, UriKind.Absolute, out var uri))
        {
            throw new ArgumentException("Could not parse Google Drive URL.", nameof(input));
        }

        string host = uri.Host.ToLowerInvariant();
        bool isDriveHost = host.Equals("drive.google.com", StringComparison.Ordinal) ||
                           host.EndsWith(".drive.google.com", StringComparison.Ordinal) ||
                           host.Equals("drive.usercontent.google.com", StringComparison.Ordinal);
        if (!isDriveHost)
        {
            throw new ArgumentException("Not a valid Google Drive URL.", nameof(input));
        }

        var query = ParseQueryString(uri.Query);
        string? resourceKey = query.TryGetValue("resourcekey", out var rk) && !string.IsNullOrWhiteSpace(rk)
            ? rk.Trim()
            : null;
        query.TryGetValue("id", out string? queryId);

        string[] segs = uri.AbsolutePath.Trim('/').Split('/', StringSplitOptions.RemoveEmptyEntries);

        // Folder paths:
        // /drive/folders/<ID>, /drive/u/<N>/folders/<ID>, /drive/mobile/folders/<ID>, /u/<N>/drive/folders/<ID>
        for (int i = 0; i < segs.Length - 1; i++)
        {
            if (string.Equals(segs[i], "folders", StringComparison.OrdinalIgnoreCase))
            {
                string folderId = segs[i + 1].Trim();
                if (!string.IsNullOrWhiteSpace(folderId))
                {
                    return new GoogleDriveTarget(GoogleDriveTargetKind.Folder, folderId, resourceKey);
                }
            }
        }

        // /embeddedfolderview?id=<ID> or /folderview?id=<ID>
        if (segs.Length >= 1 &&
            (segs.Any(s => string.Equals(s, "embeddedfolderview", StringComparison.OrdinalIgnoreCase) ||
                           string.Equals(s, "folderview", StringComparison.OrdinalIgnoreCase))) &&
            !string.IsNullOrWhiteSpace(queryId))
        {
            return new GoogleDriveTarget(GoogleDriveTargetKind.Folder, queryId.Trim(), resourceKey);
        }

        // File paths:
        // /file/d/<ID>/view, /file/d/<ID>/edit, /file/d/<ID>/preview, /file/d/<ID>, /u/<N>/file/d/<ID>/..., /file/u/<N>/d/<ID>/...
        for (int i = 0; i < segs.Length - 1; i++)
        {
            if (string.Equals(segs[i], "d", StringComparison.OrdinalIgnoreCase) &&
                segs.Take(i).Any(s => string.Equals(s, "file", StringComparison.OrdinalIgnoreCase)))
            {
                string fileId = segs[i + 1].Trim();
                if (!string.IsNullOrWhiteSpace(fileId))
                {
                    return new GoogleDriveTarget(GoogleDriveTargetKind.File, fileId, resourceKey);
                }
            }
        }

        // /open?id=<ID> or /u/<N>/open?id=<ID> (may resolve to either a folder or a file)
        if (!string.IsNullOrWhiteSpace(queryId) &&
            segs.Any(s => string.Equals(s, "open", StringComparison.OrdinalIgnoreCase)))
        {
            return new GoogleDriveTarget(GoogleDriveTargetKind.Open, queryId.Trim(), resourceKey);
        }

        // /uc?id=<ID> or drive.usercontent.google.com/download?id=<ID>
        if (!string.IsNullOrWhiteSpace(queryId) &&
            (host.Equals("drive.usercontent.google.com", StringComparison.Ordinal) ||
             segs.Any(s => string.Equals(s, "uc", StringComparison.OrdinalIgnoreCase) ||
                           string.Equals(s, "download", StringComparison.OrdinalIgnoreCase))))
        {
            return new GoogleDriveTarget(GoogleDriveTargetKind.File, queryId.Trim(), resourceKey);
        }

        throw new ArgumentException("Google Drive URL does not contain a recognized folder or file ID.", nameof(input));
    }

    public static string EncodeRemoteTrackId(string fileId, string? resourceKey = null)
    {
        if (string.IsNullOrWhiteSpace(fileId))
        {
            throw new ArgumentException("File ID is required.", nameof(fileId));
        }

        string trimmedId = fileId.Trim();
        return string.IsNullOrWhiteSpace(resourceKey)
            ? trimmedId
            : $"{trimmedId}?resourcekey={Uri.EscapeDataString(resourceKey.Trim())}";
    }

    public static (string FileId, string? ResourceKey) DecodeRemoteTrackId(string remoteTrackId)
    {
        if (string.IsNullOrWhiteSpace(remoteTrackId))
        {
            throw new ArgumentException("Remote track ID is required.", nameof(remoteTrackId));
        }

        string trimmed = remoteTrackId.Trim();
        if (trimmed.Contains("drive.google.com", StringComparison.OrdinalIgnoreCase) ||
            trimmed.Contains("drive.usercontent.google.com", StringComparison.OrdinalIgnoreCase))
        {
            var target = ParseInput(trimmed);
            return (target.Id, target.ResourceKey);
        }

        int qIdx = trimmed.IndexOf('?');
        if (qIdx > 0)
        {
            string id = trimmed[..qIdx].Trim();
            var query = ParseQueryString(trimmed[qIdx..]);
            query.TryGetValue("resourcekey", out string? rk);
            return (id, string.IsNullOrWhiteSpace(rk) ? null : rk.Trim());
        }

        int pipeIdx = trimmed.IndexOf('|');
        if (pipeIdx > 0)
        {
            string id = trimmed[..pipeIdx].Trim();
            string rest = trimmed[(pipeIdx + 1)..].Trim();
            if (rest.StartsWith("rk=", StringComparison.OrdinalIgnoreCase))
            {
                rest = rest[3..].Trim();
            }
            return (id, string.IsNullOrWhiteSpace(rest) ? null : rest);
        }

        return (trimmed, null);
    }

    public async Task<CloudPlaylistResult> FetchPlaylistAsync(
        string sourceUrl,
        string? cookie = null,
        string? proxyUrl = null,
        CancellationToken ct = default)
    {
        var target = ParseInput(sourceUrl);
        using var http = CloudHttpHelper.CreateClient(proxyUrl, _handler);

        if (target.Kind == GoogleDriveTargetKind.File)
        {
            return await FetchSingleFileAsync(http, target, cookie, ct).ConfigureAwait(false);
        }

        if (target.Kind == GoogleDriveTargetKind.Open)
        {
            return await FetchOpenTargetAsync(http, target, cookie, ct).ConfigureAwait(false);
        }

        return await FetchFolderTreeAsync(http, target, cookie, ct).ConfigureAwait(false);
    }

    private async Task<CloudPlaylistResult> FetchOpenTargetAsync(
        HttpClient http,
        GoogleDriveTarget openTarget,
        string? cookie,
        CancellationToken ct)
    {
        string openUrl = BuildOpenUrl(openTarget.Id, openTarget.ResourceKey);
        using var req = CreateRequest(HttpMethod.Get, openUrl, cookie);
        using var resp = await http.SendAsync(req, ct).ConfigureAwait(false);
        string body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);

        ThrowIfAccessDeniedOrNotFound(resp, body);

        Uri baseUri = resp.RequestMessage?.RequestUri ?? new Uri(openUrl);
        Uri? redirectUri = resp.Headers.Location is Uri loc
            ? (loc.IsAbsoluteUri ? loc : new Uri(baseUri, loc))
            : null;

        foreach (Uri? candidateUri in new[] { redirectUri, resp.RequestMessage?.RequestUri })
        {
            if (candidateUri is null) continue;
            try
            {
                var parsed = ParseInput(candidateUri.AbsoluteUri);
                if (parsed.Kind == GoogleDriveTargetKind.Folder)
                {
                    var folderTarget = parsed with { ResourceKey = parsed.ResourceKey ?? openTarget.ResourceKey };
                    return await FetchFolderTreeAsync(http, folderTarget, cookie, ct).ConfigureAwait(false);
                }
                if (parsed.Kind == GoogleDriveTargetKind.File)
                {
                    var fileTarget = parsed with { ResourceKey = parsed.ResourceKey ?? openTarget.ResourceKey };
                    return await FetchSingleFileAsync(http, fileTarget, cookie, ct).ConfigureAwait(false);
                }
            }
            catch
            {
                // Ignore non-specific URIs and inspect HTML payload next.
            }
        }

        if (resp.IsSuccessStatusCode &&
            (HasDriveIvdPayload(body) || ParseEmbeddedFolderViewEntries(body).Count > 0))
        {
            return await FetchFolderTreeAsync(
                http,
                new GoogleDriveTarget(GoogleDriveTargetKind.Folder, openTarget.Id, openTarget.ResourceKey),
                cookie,
                ct).ConfigureAwait(false);
        }

        return await FetchSingleFileAsync(
            http,
            new GoogleDriveTarget(GoogleDriveTargetKind.File, openTarget.Id, openTarget.ResourceKey),
            cookie,
            ct).ConfigureAwait(false);
    }

    private async Task<CloudPlaylistResult> FetchFolderTreeAsync(
        HttpClient http,
        GoogleDriveTarget rootTarget,
        string? cookie,
        CancellationToken ct)
    {
        var queue = new Queue<(string Id, string? ResourceKey, string? Name, int Depth)>();
        var visitedFolders = new HashSet<string>(StringComparer.Ordinal);
        var seenFileIds = new HashSet<string>(StringComparer.Ordinal);
        var items = new List<CloudPlaylistItem>();

        visitedFolders.Add(rootTarget.Id);
        queue.Enqueue((rootTarget.Id, rootTarget.ResourceKey, null, 0));

        string? rootTitle = null;
        int foldersVisited = 0;

        while (queue.Count > 0 && foldersVisited < MaxFolders)
        {
            ct.ThrowIfCancellationRequested();
            var (folderId, resourceKey, folderName, depth) = queue.Dequeue();
            foldersVisited++;

            (string? fetchedTitle, List<GoogleDriveEntry> entries) folderResult;
            try
            {
                folderResult = await FetchSingleFolderAsync(http, folderId, resourceKey, cookie, isRoot: depth == 0, ct)
                    .ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch when (depth > 0)
            {
                // Skip restricted or transient errors on nested subfolders while preserving root results.
                continue;
            }

            if (depth == 0)
            {
                rootTitle = !string.IsNullOrWhiteSpace(folderResult.fetchedTitle)
                    ? folderResult.fetchedTitle
                    : $"Google Drive ({folderId})";
            }

            string albumName = depth == 0
                ? rootTitle!
                : (!string.IsNullOrWhiteSpace(folderName)
                    ? folderName
                    : (!string.IsNullOrWhiteSpace(folderResult.fetchedTitle) ? folderResult.fetchedTitle : rootTitle!));

            foreach (var entry in folderResult.entries)
            {
                if (entry.IsFolder)
                {
                    if (depth < MaxFolderDepth && visitedFolders.Add(entry.Id))
                    {
                        queue.Enqueue((entry.Id, entry.ResourceKey ?? resourceKey, entry.Name, depth + 1));
                    }
                    continue;
                }

                if (!IsAudioEntry(entry.Name, entry.MimeType))
                {
                    continue;
                }

                if (!seenFileIds.Add(entry.Id))
                {
                    continue;
                }

                string? ext = ExtractAudioExtension(entry.Name, entry.MimeType);
                string title = StripAudioExtension(entry.Name);
                string remoteTrackId = EncodeRemoteTrackId(entry.Id, entry.ResourceKey);

                items.Add(new CloudPlaylistItem(
                    RemoteTrackId: remoteTrackId,
                    Title: title,
                    Artist: "Google Drive",
                    Album: albumName,
                    DurationSeconds: 0,
                    ArtworkUrl: null,
                    TrackNumber: items.Count + 1,
                    FileExtension: ext));
            }
        }

        string canonicalUrl = BuildFolderUrl(rootTarget.Id, rootTarget.ResourceKey);
        return new CloudPlaylistResult(
            Kind: RootKind.CloudGoogleDrive,
            RemoteId: rootTarget.Id,
            Title: rootTitle ?? $"Google Drive ({rootTarget.Id})",
            SourceUrl: canonicalUrl,
            Items: items);
    }

    private async Task<(string? Title, List<GoogleDriveEntry> Entries)> FetchSingleFolderAsync(
        HttpClient http,
        string folderId,
        string? resourceKey,
        string? cookie,
        bool isRoot,
        CancellationToken ct)
    {
        string primaryUrl = BuildFolderUrl(folderId, resourceKey);
        string? title = null;
        bool primaryHadSignInOrDenied = false;
        Exception? primaryError = null;

        try
        {
            using var req = CreateRequest(HttpMethod.Get, primaryUrl, cookie);
            using var resp = await http.SendAsync(req, ct).ConfigureAwait(false);
            string body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);

            ThrowIfAccessDeniedOrNotFound(resp, body);

            if (resp.IsSuccessStatusCode)
            {
                title = ExtractTitle(body);
                var ivdEntries = ParseDriveIvdEntries(body);
                if (ivdEntries.Count > 0 || HasDriveIvdPayload(body))
                {
                    return (title, ivdEntries);
                }
            }
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (InvalidOperationException ex)
        {
            primaryHadSignInOrDenied = true;
            primaryError = ex;
        }
        catch (Exception ex)
        {
            primaryError = ex;
        }

        if (primaryHadSignInOrDenied && isRoot)
        {
            throw primaryError!;
        }

        // Fallback: embeddedfolderview
        string fallbackUrl = BuildEmbeddedFolderViewUrl(folderId, resourceKey);
        using var fallbackReq = CreateRequest(HttpMethod.Get, fallbackUrl, cookie);
        using var fallbackResp = await http.SendAsync(fallbackReq, ct).ConfigureAwait(false);
        string fallbackBody = await fallbackResp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);

        ThrowIfAccessDeniedOrNotFound(fallbackResp, fallbackBody);

        if (!fallbackResp.IsSuccessStatusCode)
        {
            if (primaryError is not null)
            {
                throw primaryError;
            }
            throw new InvalidOperationException(
                $"Could not load Google Drive folder (HTTP {(int)fallbackResp.StatusCode}). Make sure the link is shared with 'Anyone with the link'.");
        }

        title ??= ExtractTitle(fallbackBody);
        var fallbackEntries = ParseEmbeddedFolderViewEntries(fallbackBody);
        return (title, fallbackEntries);
    }

    private async Task<CloudPlaylistResult> FetchSingleFileAsync(
        HttpClient http,
        GoogleDriveTarget target,
        string? cookie,
        CancellationToken ct)
    {
        string canonicalUrl = BuildFileViewUrl(target.Id, target.ResourceKey);
        string? rawFileName = null;
        string? contentType = null;

        // Try file view page first for title/filename metadata.
        try
        {
            using var viewReq = CreateRequest(HttpMethod.Get, canonicalUrl, cookie);
            using var viewResp = await http.SendAsync(viewReq, ct).ConfigureAwait(false);
            string viewBody = await viewResp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);

            ThrowIfAccessDeniedOrNotFound(viewResp, viewBody);

            if (viewResp.IsSuccessStatusCode)
            {
                rawFileName = ExtractTitle(viewBody);
            }
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (InvalidOperationException)
        {
            throw;
        }
        catch
        {
            // Fall through to header probe if view page fails transiently.
        }

        if (!string.IsNullOrWhiteSpace(rawFileName))
        {
            string? explicitExt = Path.GetExtension(rawFileName.Trim());
            if (IsPlausibleFileExtension(explicitExt) && !SupportedAudioExtensions.Contains(explicitExt))
            {
                throw new InvalidOperationException(
                    $"This Google Drive file is not a supported audio format ({rawFileName.Trim()}).");
            }
        }

        // If view page didn't provide an extension or filename, probe download headers for Content-Disposition/Content-Type.
        if (string.IsNullOrWhiteSpace(rawFileName) || ExtractAudioExtension(rawFileName, null) is null)
        {
            string probeUrl = BuildDownloadUrl("https://drive.usercontent.google.com/download", target.Id, target.ResourceKey, "t");
            try
            {
                using var probeReq = CreateRequest(HttpMethod.Get, probeUrl, cookie);
                probeReq.Headers.Range = new RangeHeaderValue(0, 0);
                using var probeResp = await http.SendAsync(probeReq, HttpCompletionOption.ResponseHeadersRead, ct)
                    .ConfigureAwait(false);

                string? probeHtml = null;
                if (IsHtmlResponse(probeResp))
                {
                    probeHtml = await probeResp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
                }

                if (IsSignInResponse(probeResp, probeHtml) ||
                    probeResp.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden)
                {
                    ThrowIfAccessDeniedOrNotFound(probeResp, probeHtml);
                }

                if (probeResp.StatusCode == HttpStatusCode.NotFound && string.IsNullOrWhiteSpace(rawFileName))
                {
                    throw BuildAccessDeniedException(HttpStatusCode.NotFound);
                }

                if (probeResp.IsSuccessStatusCode)
                {
                    if (!IsHtmlResponse(probeResp))
                    {
                        contentType = probeResp.Content.Headers.ContentType?.MediaType;
                        string? dispName = ExtractContentDispositionFileName(probeResp.Content.Headers);
                        if (!string.IsNullOrWhiteSpace(dispName))
                        {
                            rawFileName = dispName;
                        }
                    }
                    else if (!string.IsNullOrEmpty(probeHtml))
                    {
                        string? warnName = ExtractVirusScanFileName(probeHtml);
                        if (!string.IsNullOrWhiteSpace(warnName))
                        {
                            rawFileName = warnName;
                        }
                    }
                }
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (InvalidOperationException)
            {
                throw;
            }
            catch
            {
                // Keep view page filename if probe fails.
            }
        }

        string effectiveName = string.IsNullOrWhiteSpace(rawFileName) ? target.Id : rawFileName.Trim();
        string? ext = ExtractAudioExtension(effectiveName, contentType);
        if (ext is null)
        {
            string? nameExt = Path.GetExtension(effectiveName);
            bool hasNonAudioExt = IsPlausibleFileExtension(nameExt);
            bool hasNonAudioMime = !string.IsNullOrWhiteSpace(contentType) &&
                                   !contentType.StartsWith("audio/", StringComparison.OrdinalIgnoreCase) &&
                                   !contentType.Equals("application/octet-stream", StringComparison.OrdinalIgnoreCase) &&
                                   !contentType.Equals("binary/octet-stream", StringComparison.OrdinalIgnoreCase);
            if (hasNonAudioExt || hasNonAudioMime)
            {
                throw new InvalidOperationException(
                    $"This Google Drive file is not a supported audio format ({effectiveName}).");
            }
        }

        string title = StripAudioExtension(effectiveName);
        string remoteTrackId = EncodeRemoteTrackId(target.Id, target.ResourceKey);

        var item = new CloudPlaylistItem(
            RemoteTrackId: remoteTrackId,
            Title: title,
            Artist: "Google Drive",
            Album: title,
            DurationSeconds: 0,
            ArtworkUrl: null,
            TrackNumber: 1,
            FileExtension: ext);

        return new CloudPlaylistResult(
            Kind: RootKind.CloudGoogleDrive,
            RemoteId: target.Id,
            Title: title,
            SourceUrl: canonicalUrl,
            Items: [item]);
    }

    public async Task<CloudAudioStream> ResolveAudioStreamAsync(
        string remoteTrackId,
        string? cookie = null,
        string? proxyUrl = null,
        CancellationToken ct = default)
    {
        var (fileId, resourceKey) = DecodeRemoteTrackId(remoteTrackId);
        using var http = CloudHttpHelper.CreateClient(proxyUrl, _handler);

        string[] candidates =
        [
            BuildDownloadUrl("https://drive.usercontent.google.com/download", fileId, resourceKey, "t"),
            BuildDownloadUrl("https://drive.google.com/uc", fileId, resourceKey, "t")
        ];

        Exception? lastError = null;
        foreach (string candidateUrl in candidates)
        {
            try
            {
                using var req = CreateRequest(HttpMethod.Get, candidateUrl, cookie);
                req.Headers.Range = new RangeHeaderValue(0, 0);
                using var resp = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct)
                    .ConfigureAwait(false);

                bool isHtml = IsHtmlResponse(resp);
                string? html = isHtml
                    ? await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false)
                    : null;

                if (IsSignInResponse(resp, html))
                {
                    throw new InvalidOperationException(
                        "This Google Drive file requires sign-in. Make sure the link is shared with 'Anyone with the link'.");
                }

                if (resp.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden or HttpStatusCode.NotFound)
                {
                    lastError = BuildAccessDeniedException(resp.StatusCode);
                    continue;
                }

                if (!resp.IsSuccessStatusCode)
                {
                    lastError = new InvalidOperationException(
                        $"Google Drive download failed (HTTP {(int)resp.StatusCode}).");
                    continue;
                }

                if (!isHtml)
                {
                    string resolvedUrl = resp.RequestMessage?.RequestUri?.AbsoluteUri ?? candidateUrl;
                    string ext = ResolveStreamExtension(resp, null, null);
                    return new CloudAudioStream(resolvedUrl, ext, BuildStreamHeaders(cookie));
                }

                if (!string.IsNullOrEmpty(html))
                {
                    string? confirmUrl = TryExtractVirusScanConfirmUrl(html, resp.RequestMessage?.RequestUri ?? new Uri(candidateUrl), fileId, resourceKey);
                    if (!string.IsNullOrWhiteSpace(confirmUrl))
                    {
                        string? hintFileName = ExtractVirusScanFileName(html);
                        return await FinalizeConfirmedDownloadUrlAsync(http, confirmUrl, hintFileName, cookie, ct)
                            .ConfigureAwait(false);
                    }
                }

                lastError = new InvalidOperationException(
                    "Google Drive returned an HTML page instead of audio. Make sure the link is shared with 'Anyone with the link'.");
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (InvalidOperationException ex) when (IsSignInMessage(ex.Message))
            {
                throw;
            }
            catch (Exception ex)
            {
                lastError = ex;
            }
        }

        throw lastError ?? new InvalidOperationException(
            $"Could not resolve audio stream for Google Drive file \"{fileId}\". Make sure the link is shared with 'Anyone with the link'.");
    }

    private async Task<CloudAudioStream> FinalizeConfirmedDownloadUrlAsync(
        HttpClient http,
        string confirmUrl,
        string? hintFileName,
        string? cookie,
        CancellationToken ct)
    {
        try
        {
            using var confirmReq = CreateRequest(HttpMethod.Get, confirmUrl, cookie);
            confirmReq.Headers.Range = new RangeHeaderValue(0, 0);
            using var confirmResp = await http.SendAsync(confirmReq, HttpCompletionOption.ResponseHeadersRead, ct)
                .ConfigureAwait(false);

            bool confirmIsHtml = IsHtmlResponse(confirmResp);
            string? confirmHtml = confirmIsHtml
                ? await confirmResp.Content.ReadAsStringAsync(ct).ConfigureAwait(false)
                : null;

            if (IsSignInResponse(confirmResp, confirmHtml))
            {
                throw new InvalidOperationException(
                    "This Google Drive file requires sign-in. Make sure the link is shared with 'Anyone with the link'.");
            }

            if (confirmResp.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden)
            {
                throw BuildAccessDeniedException(confirmResp.StatusCode);
            }

            if (confirmResp.IsSuccessStatusCode && !confirmIsHtml)
            {
                string resolvedUrl = confirmResp.RequestMessage?.RequestUri?.AbsoluteUri ?? confirmUrl;
                string ext = ResolveStreamExtension(confirmResp, hintFileName, null);
                return new CloudAudioStream(resolvedUrl, ext, BuildStreamHeaders(cookie));
            }
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (InvalidOperationException)
        {
            throw;
        }
        catch
        {
            // Fall back to the parsed confirmation URL with extension from the warning page.
        }

        string fallbackExt = ExtractAudioExtension(hintFileName, null) ?? ".m4a";
        return new CloudAudioStream(confirmUrl, fallbackExt, BuildStreamHeaders(cookie));
    }

    public static string? ExtractTitle(string html)
    {
        if (string.IsNullOrWhiteSpace(html)) return null;

        string? raw = null;
        var og1 = OgTitleRegex1.Match(html);
        if (og1.Success)
        {
            raw = WebUtility.HtmlDecode(og1.Groups[1].Value);
        }
        else
        {
            var og2 = OgTitleRegex2.Match(html);
            if (og2.Success)
            {
                raw = WebUtility.HtmlDecode(og2.Groups[1].Value);
            }
        }

        if (string.IsNullOrWhiteSpace(raw))
        {
            var ip1 = ItemPropNameRegex1.Match(html);
            if (ip1.Success)
            {
                raw = WebUtility.HtmlDecode(ip1.Groups[1].Value);
            }
            else
            {
                var ip2 = ItemPropNameRegex2.Match(html);
                if (ip2.Success)
                {
                    raw = WebUtility.HtmlDecode(ip2.Groups[1].Value);
                }
            }
        }

        if (string.IsNullOrWhiteSpace(raw))
        {
            var titleMatch = HtmlTitleRegex.Match(html);
            if (titleMatch.Success)
            {
                raw = WebUtility.HtmlDecode(HtmlTagRegex.Replace(titleMatch.Groups[1].Value, string.Empty));
            }
        }

        if (string.IsNullOrWhiteSpace(raw)) return null;

        string cleaned = TitleSuffixRegex.Replace(raw.Trim(), string.Empty).Trim();
        if (string.IsNullOrWhiteSpace(cleaned) ||
            cleaned.Equals("Google Drive", StringComparison.OrdinalIgnoreCase) ||
            cleaned.Equals("Google 云端硬盘", StringComparison.OrdinalIgnoreCase) ||
            cleaned.Contains("Sign in", StringComparison.OrdinalIgnoreCase) ||
            cleaned.Contains("Sign-in", StringComparison.OrdinalIgnoreCase) ||
            cleaned.Contains("Google Accounts", StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        return cleaned;
    }

    public static List<GoogleDriveEntry> ParseDriveIvdEntries(string html)
    {
        var entries = new List<GoogleDriveEntry>();
        string? jsonPayload = ExtractDriveIvdJson(html);
        if (string.IsNullOrWhiteSpace(jsonPayload))
        {
            return entries;
        }

        try
        {
            using var doc = JsonDocument.Parse(jsonPayload);
            WalkDriveIvdElement(doc.RootElement, entries);
        }
        catch
        {
            // Malformed or partial _DRIVE_ivd falls back to embeddedfolderview.
        }

        return entries;
    }

    private static bool HasDriveIvdPayload(string html) =>
        !string.IsNullOrWhiteSpace(ExtractDriveIvdJson(html));

    private static string? ExtractDriveIvdJson(string html)
    {
        if (string.IsNullOrWhiteSpace(html)) return null;

        var match = DriveIvdAssignRegex.Match(html);
        if (!match.Success) return null;

        int pos = match.Index + match.Length;
        while (pos < html.Length && char.IsWhiteSpace(html[pos]))
        {
            pos++;
        }

        if (pos >= html.Length) return null;

        char first = html[pos];
        if (first is '\'' or '"')
        {
            return UnescapeJsStringLiteral(html, pos, first);
        }

        if (first is '[' or '{')
        {
            return ExtractBalancedJson(html, pos);
        }

        return null;
    }

    private static string? UnescapeJsStringLiteral(string source, int quoteIndex, char quoteChar)
    {
        var sb = new StringBuilder();
        int i = quoteIndex + 1;
        while (i < source.Length)
        {
            char c = source[i];
            if (c == quoteChar)
            {
                return sb.ToString();
            }

            if (c == '\\' && i + 1 < source.Length)
            {
                char next = source[i + 1];
                if (next == 'x' && i + 3 < source.Length &&
                    TryParseHexByte(source[i + 2], source[i + 3], out byte hexByte))
                {
                    sb.Append((char)hexByte);
                    i += 4;
                    continue;
                }
                if (next == 'u' && i + 5 < source.Length &&
                    TryParseHexUInt16(source.AsSpan(i + 2, 4), out ushort codeUnit))
                {
                    sb.Append((char)codeUnit);
                    i += 6;
                    continue;
                }

                switch (next)
                {
                    case 'n': sb.Append('\n'); break;
                    case 'r': sb.Append('\r'); break;
                    case 't': sb.Append('\t'); break;
                    case 'b': sb.Append('\b'); break;
                    case 'f': sb.Append('\f'); break;
                    case '/': sb.Append('/'); break;
                    case '\\': sb.Append('\\'); break;
                    case '\'': sb.Append('\''); break;
                    case '"': sb.Append('"'); break;
                    default: sb.Append(next); break;
                }
                i += 2;
                continue;
            }

            sb.Append(c);
            i++;
        }

        return null;
    }

    private static string? ExtractBalancedJson(string source, int startIndex)
    {
        char open = source[startIndex];
        char close = open == '[' ? ']' : '}';
        int depth = 0;
        bool inString = false;
        bool escaped = false;

        for (int i = startIndex; i < source.Length; i++)
        {
            char c = source[i];
            if (inString)
            {
                if (escaped)
                {
                    escaped = false;
                }
                else if (c == '\\')
                {
                    escaped = true;
                }
                else if (c == '"')
                {
                    inString = false;
                }
                continue;
            }

            if (c == '"')
            {
                inString = true;
            }
            else if (c == open)
            {
                depth++;
            }
            else if (c == close)
            {
                depth--;
                if (depth == 0)
                {
                    return source[startIndex..(i + 1)];
                }
            }
        }

        return null;
    }

    private static void WalkDriveIvdElement(JsonElement el, List<GoogleDriveEntry> entries)
    {
        if (el.ValueKind == JsonValueKind.Object)
        {
            if (TryParseObjectEntry(el, out var objEntry))
            {
                entries.Add(objEntry);
                return;
            }

            foreach (var prop in el.EnumerateObject())
            {
                WalkDriveIvdElement(prop.Value, entries);
            }
            return;
        }

        if (el.ValueKind == JsonValueKind.Array)
        {
            if (TryParseArrayEntry(el, out var arrEntry))
            {
                entries.Add(arrEntry);
                return;
            }

            foreach (var child in el.EnumerateArray())
            {
                WalkDriveIvdElement(child, entries);
            }
        }
    }

    private static bool TryParseObjectEntry(JsonElement obj, out GoogleDriveEntry entry)
    {
        entry = default!;
        string? id = ReadStringProp(obj, "id");
        string? name = ReadStringProp(obj, "name") ?? ReadStringProp(obj, "title");
        string? mimeType = ReadStringProp(obj, "mimeType") ?? ReadStringProp(obj, "mime_type");
        if (string.IsNullOrWhiteSpace(id) || string.IsNullOrWhiteSpace(name) || string.IsNullOrWhiteSpace(mimeType))
        {
            return false;
        }

        string? rk = ReadStringProp(obj, "resourceKey") ?? ReadStringProp(obj, "resourcekey");
        bool isFolder = string.Equals(mimeType, FolderMimeType, StringComparison.OrdinalIgnoreCase);
        entry = new GoogleDriveEntry(id.Trim(), name.Trim(), mimeType.Trim(), isFolder, string.IsNullOrWhiteSpace(rk) ? null : rk.Trim());
        return true;
    }

    private static bool TryParseArrayEntry(JsonElement arr, out GoogleDriveEntry entry)
    {
        entry = default!;
        int len = arr.GetArrayLength();
        if (len < 3) return false;

        // Standard Google Drive _DRIVE_ivd row: [id, [parentIds], name, mimeType, ...]
        if (len >= 4 &&
            arr[0].ValueKind == JsonValueKind.String &&
            arr[1].ValueKind is JsonValueKind.Array or JsonValueKind.Null &&
            arr[2].ValueKind == JsonValueKind.String &&
            arr[3].ValueKind == JsonValueKind.String)
        {
            string? id = arr[0].GetString();
            string? name = arr[2].GetString();
            string? mimeType = arr[3].GetString();
            if (!string.IsNullOrWhiteSpace(id) &&
                !string.IsNullOrWhiteSpace(name) &&
                !string.IsNullOrWhiteSpace(mimeType) &&
                mimeType.Contains('/', StringComparison.Ordinal))
            {
                string? rk = ExtractResourceKeyFromArray(arr);
                bool isFolder = string.Equals(mimeType, FolderMimeType, StringComparison.OrdinalIgnoreCase);
                entry = new GoogleDriveEntry(id.Trim(), name.Trim(), mimeType.Trim(), isFolder, rk);
                return true;
            }
        }

        // Compact positional row: [id, name, mimeType, optionalResourceKey]
        if (arr[0].ValueKind == JsonValueKind.String &&
            arr[1].ValueKind == JsonValueKind.String &&
            arr[2].ValueKind == JsonValueKind.String)
        {
            string? id = arr[0].GetString();
            string? name = arr[1].GetString();
            string? mimeType = arr[2].GetString();
            if (!string.IsNullOrWhiteSpace(id) &&
                !string.IsNullOrWhiteSpace(name) &&
                !string.IsNullOrWhiteSpace(mimeType) &&
                mimeType.Contains('/', StringComparison.Ordinal))
            {
                string? rk = len >= 4 && arr[3].ValueKind == JsonValueKind.String ? arr[3].GetString() : null;
                bool isFolder = string.Equals(mimeType, FolderMimeType, StringComparison.OrdinalIgnoreCase);
                entry = new GoogleDriveEntry(
                    id.Trim(),
                    name.Trim(),
                    mimeType.Trim(),
                    isFolder,
                    string.IsNullOrWhiteSpace(rk) ? null : rk.Trim());
                return true;
            }
        }

        return false;
    }

    private static string? ExtractResourceKeyFromArray(JsonElement arr)
    {
        foreach (var el in arr.EnumerateArray())
        {
            if (el.ValueKind == JsonValueKind.String)
            {
                string? s = el.GetString();
                if (!string.IsNullOrEmpty(s) && s.Contains("resourcekey=", StringComparison.OrdinalIgnoreCase))
                {
                    int idx = s.IndexOf("resourcekey=", StringComparison.OrdinalIgnoreCase);
                    string val = s[(idx + "resourcekey=".Length)..];
                    int amp = val.IndexOf('&');
                    if (amp >= 0) val = val[..amp];
                    if (!string.IsNullOrWhiteSpace(val))
                    {
                        return Uri.UnescapeDataString(val.Trim());
                    }
                }
            }
            else if (el.ValueKind == JsonValueKind.Object)
            {
                string? rk = ReadStringProp(el, "resourceKey") ?? ReadStringProp(el, "resourcekey");
                if (!string.IsNullOrWhiteSpace(rk))
                {
                    return rk.Trim();
                }
            }
        }
        return null;
    }

    public static List<GoogleDriveEntry> ParseEmbeddedFolderViewEntries(string html)
    {
        var entries = new List<GoogleDriveEntry>();
        if (string.IsNullOrWhiteSpace(html)) return entries;

        var seenIds = new HashSet<string>(StringComparer.Ordinal);
        foreach (Match entryMatch in FlipEntryRegex.Matches(html))
        {
            string block = entryMatch.Groups[1].Value;
            var linkMatch = FlipLinkRegex.Match(block);
            if (!linkMatch.Success) continue;

            string href = WebUtility.HtmlDecode(linkMatch.Groups[1].Value);
            GoogleDriveTarget parsedLink;
            try
            {
                parsedLink = ParseInput(href.StartsWith('/') ? $"https://drive.google.com{href}" : href);
            }
            catch
            {
                continue;
            }

            if (!seenIds.Add(parsedLink.Id)) continue;

            string title = parsedLink.Id;
            var titleMatch = FlipTitleRegex.Match(block);
            if (titleMatch.Success)
            {
                string rawTitle = HtmlTagRegex.Replace(titleMatch.Groups[1].Value, string.Empty);
                string decoded = WebUtility.HtmlDecode(rawTitle).Trim();
                if (!string.IsNullOrWhiteSpace(decoded))
                {
                    title = decoded;
                }
            }

            bool isFolder = parsedLink.Kind == GoogleDriveTargetKind.Folder;
            string mimeType = isFolder
                ? FolderMimeType
                : (MimeTypeFromExtension(Path.GetExtension(title)) ?? "application/octet-stream");

            entries.Add(new GoogleDriveEntry(parsedLink.Id, title, mimeType, isFolder, parsedLink.ResourceKey));
        }

        return entries;
    }

    public static string? TryExtractVirusScanConfirmUrl(
        string html,
        Uri requestUri,
        string fileId,
        string? resourceKey)
    {
        if (string.IsNullOrWhiteSpace(html)) return null;

        Match? selectedForm = null;
        string? selectedAction = null;

        foreach (Match formMatch in FormRegex.Matches(html))
        {
            string attrsText = formMatch.Groups[1].Value;
            var attrs = ParseAttributes(attrsText);
            attrs.TryGetValue("action", out string? action);
            attrs.TryGetValue("id", out string? formId);

            bool matchesAction = !string.IsNullOrWhiteSpace(action) &&
                                 (action.Contains("download", StringComparison.OrdinalIgnoreCase) ||
                                  action.Contains("/uc", StringComparison.OrdinalIgnoreCase));
            bool matchesId = string.Equals(formId, "download-form", StringComparison.OrdinalIgnoreCase);

            if (matchesAction || matchesId)
            {
                selectedForm = formMatch;
                selectedAction = action;
                if (matchesAction) break;
            }
        }

        if (selectedForm is null) return null;

        string actionDecoded = WebUtility.HtmlDecode(selectedAction ?? "/download");
        Uri baseActionUri = Uri.TryCreate(requestUri, actionDecoded, out var resolved)
            ? resolved
            : new Uri("https://drive.usercontent.google.com/download");

        var parameters = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        if (!string.IsNullOrEmpty(baseActionUri.Query))
        {
            foreach (var kvp in ParseQueryString(baseActionUri.Query))
            {
                parameters[kvp.Key] = kvp.Value;
            }
        }

        string formBody = selectedForm.Groups[2].Value;
        foreach (Match inputMatch in InputRegex.Matches(formBody))
        {
            var attrs = ParseAttributes(inputMatch.Groups[1].Value);
            attrs.TryGetValue("type", out string? type);
            if (!string.IsNullOrWhiteSpace(type) && !string.Equals(type, "hidden", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            if (attrs.TryGetValue("name", out string? name) && !string.IsNullOrWhiteSpace(name))
            {
                attrs.TryGetValue("value", out string? value);
                parameters[name.Trim()] = WebUtility.HtmlDecode(value ?? string.Empty);
            }
        }

        if (!parameters.ContainsKey("id"))
        {
            parameters["id"] = fileId;
        }
        if (!parameters.ContainsKey("export"))
        {
            parameters["export"] = "download";
        }
        if (!parameters.ContainsKey("confirm"))
        {
            parameters["confirm"] = "t";
        }
        if (!string.IsNullOrWhiteSpace(resourceKey) && !parameters.ContainsKey("resourcekey"))
        {
            parameters["resourcekey"] = resourceKey;
        }

        var builder = new UriBuilder(baseActionUri)
        {
            Query = string.Join("&", parameters.Select(kvp =>
                $"{Uri.EscapeDataString(kvp.Key)}={Uri.EscapeDataString(kvp.Value)}"))
        };
        return builder.Uri.AbsoluteUri;
    }

    public static bool IsAudioEntry(string name, string? mimeType)
    {
        if (string.Equals(mimeType, FolderMimeType, StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        string ext = Path.GetExtension(name ?? string.Empty);
        if (!string.IsNullOrEmpty(ext) && SupportedAudioExtensions.Contains(ext))
        {
            return true;
        }

        return !string.IsNullOrWhiteSpace(mimeType) &&
               mimeType.StartsWith("audio/", StringComparison.OrdinalIgnoreCase);
    }

    private static bool IsPlausibleFileExtension(string? ext)
    {
        if (string.IsNullOrWhiteSpace(ext) || ext.Length is < 2 or > 8 || ext[0] != '.')
        {
            return false;
        }
        bool hasLetter = false;
        for (int i = 1; i < ext.Length; i++)
        {
            char c = ext[i];
            if (!char.IsLetterOrDigit(c)) return false;
            if (c is (>= 'a' and <= 'z') or (>= 'A' and <= 'Z')) hasLetter = true;
        }
        return hasLetter;
    }

    public static string? ExtractAudioExtension(string? fileName, string? contentType)
    {
        if (!string.IsNullOrWhiteSpace(fileName))
        {
            string ext = Path.GetExtension(fileName.Trim());
            if (!string.IsNullOrEmpty(ext) && SupportedAudioExtensions.Contains(ext))
            {
                return ext.ToLowerInvariant();
            }
        }

        if (!string.IsNullOrWhiteSpace(contentType))
        {
            return ExtensionFromMimeType(contentType);
        }

        return null;
    }

    private static string StripAudioExtension(string name)
    {
        string trimmed = name.Trim();
        string ext = Path.GetExtension(trimmed);
        if (!string.IsNullOrEmpty(ext) && SupportedAudioExtensions.Contains(ext))
        {
            string stem = Path.GetFileNameWithoutExtension(trimmed);
            if (!string.IsNullOrWhiteSpace(stem))
            {
                return stem;
            }
        }
        return trimmed;
    }

    private static string? ExtensionFromMimeType(string? mimeType)
    {
        if (string.IsNullOrWhiteSpace(mimeType)) return null;
        string lower = mimeType.Split(';')[0].Trim().ToLowerInvariant();
        return lower switch
        {
            "audio/mpeg" or "audio/mp3" => ".mp3",
            "audio/mp4" or "audio/x-m4a" or "audio/m4a" => ".m4a",
            "audio/aac" or "audio/x-aac" => ".aac",
            "audio/flac" or "audio/x-flac" => ".flac",
            "audio/wav" or "audio/x-wav" or "audio/wave" or "audio/vnd.wave" => ".wav",
            "audio/aiff" or "audio/x-aiff" => ".aif",
            "audio/x-ms-wma" => ".wma",
            "audio/ogg" or "application/ogg" => ".ogg",
            "audio/opus" => ".opus",
            _ => lower.StartsWith("audio/", StringComparison.Ordinal) ? ".m4a" : null
        };
    }

    private static string? MimeTypeFromExtension(string? ext)
    {
        if (string.IsNullOrWhiteSpace(ext)) return null;
        return ext.ToLowerInvariant() switch
        {
            ".mp3" => "audio/mpeg",
            ".m4a" => "audio/mp4",
            ".aac" => "audio/aac",
            ".flac" => "audio/flac",
            ".wav" => "audio/wav",
            ".aif" or ".aiff" => "audio/aiff",
            ".wma" => "audio/x-ms-wma",
            ".ogg" => "audio/ogg",
            ".opus" => "audio/opus",
            _ => null
        };
    }

    private static string ResolveStreamExtension(HttpResponseMessage resp, string? hintFileName, string? fallbackFileName)
    {
        string? dispName = ExtractContentDispositionFileName(resp.Content.Headers);
        string? contentType = resp.Content.Headers.ContentType?.MediaType;

        return ExtractAudioExtension(dispName, null)
               ?? ExtensionFromMimeType(contentType)
               ?? ExtractAudioExtension(hintFileName, null)
               ?? ExtractAudioExtension(fallbackFileName, null)
               ?? ".m4a";
    }

    private static string? ExtractContentDispositionFileName(HttpContentHeaders headers)
    {
        var cd = headers.ContentDisposition;
        if (cd is null) return null;
        string? raw = cd.FileNameStar ?? cd.FileName;
        if (string.IsNullOrWhiteSpace(raw)) return null;
        return raw.Trim().Trim('"');
    }

    private static string? ExtractVirusScanFileName(string html)
    {
        var match = UcNameLinkRegex.Match(html);
        if (!match.Success) return null;
        string raw = HtmlTagRegex.Replace(match.Groups[1].Value, string.Empty);
        string decoded = WebUtility.HtmlDecode(raw).Trim();
        return string.IsNullOrWhiteSpace(decoded) ? null : decoded;
    }

    private static bool IsHtmlResponse(HttpResponseMessage resp)
    {
        string? mediaType = resp.Content.Headers.ContentType?.MediaType;
        return !string.IsNullOrWhiteSpace(mediaType) &&
               (mediaType.Equals("text/html", StringComparison.OrdinalIgnoreCase) ||
                mediaType.Equals("application/xhtml+xml", StringComparison.OrdinalIgnoreCase));
    }

    private static void ThrowIfAccessDeniedOrNotFound(HttpResponseMessage resp, string? html)
    {
        if (IsSignInResponse(resp, html))
        {
            throw new InvalidOperationException(
                "This Google Drive link requires sign-in. Make sure the link is shared with 'Anyone with the link'.");
        }

        if (resp.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden or HttpStatusCode.NotFound)
        {
            throw BuildAccessDeniedException(resp.StatusCode);
        }
    }

    private static InvalidOperationException BuildAccessDeniedException(HttpStatusCode statusCode) =>
        statusCode == HttpStatusCode.NotFound
            ? new InvalidOperationException("Google Drive file or folder not found (404). Make sure the link is shared with 'Anyone with the link'.")
            : new InvalidOperationException("Access denied to Google Drive link (403). Make sure the link is shared with 'Anyone with the link'.");

    private static bool IsSignInResponse(HttpResponseMessage resp, string? html)
    {
        if (resp.Headers.Location is Uri loc &&
            loc.ToString().Contains("accounts.google.com", StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }

        if (resp.RequestMessage?.RequestUri?.Host.Contains("accounts.google.com", StringComparison.OrdinalIgnoreCase) == true)
        {
            return true;
        }

        if (string.IsNullOrWhiteSpace(html)) return false;

        if (html.Contains("ServiceLogin", StringComparison.OrdinalIgnoreCase) &&
            html.Contains("accounts.google.com", StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }

        var titleMatch = HtmlTitleRegex.Match(html);
        if (titleMatch.Success)
        {
            string title = WebUtility.HtmlDecode(titleMatch.Groups[1].Value);
            if ((title.Contains("Sign in", StringComparison.OrdinalIgnoreCase) ||
                 title.Contains("Sign-in", StringComparison.OrdinalIgnoreCase) ||
                 title.Contains("登录", StringComparison.OrdinalIgnoreCase)) &&
                (title.Contains("Google", StringComparison.OrdinalIgnoreCase) ||
                 html.Contains("accounts.google.com", StringComparison.OrdinalIgnoreCase)))
            {
                return true;
            }
        }

        return false;
    }

    private static bool IsSignInMessage(string message) =>
        message.Contains("sign-in", StringComparison.OrdinalIgnoreCase) ||
        message.Contains("Anyone with the link", StringComparison.OrdinalIgnoreCase);

    private static HttpRequestMessage CreateRequest(HttpMethod method, string url, string? cookie)
    {
        var req = new HttpRequestMessage(method, url);
        req.Headers.TryAddWithoutValidation("User-Agent", DefaultUserAgent);
        if (!string.IsNullOrWhiteSpace(cookie))
        {
            req.Headers.TryAddWithoutValidation("Cookie", cookie);
        }
        return req;
    }

    private static Dictionary<string, string> BuildStreamHeaders(string? cookie)
    {
        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            ["User-Agent"] = DefaultUserAgent
        };
        if (!string.IsNullOrWhiteSpace(cookie))
        {
            headers["Cookie"] = cookie;
        }
        return headers;
    }

    private static string BuildFolderUrl(string folderId, string? resourceKey)
    {
        string url = $"https://drive.google.com/drive/folders/{Uri.EscapeDataString(folderId)}";
        if (!string.IsNullOrWhiteSpace(resourceKey))
        {
            url += $"?resourcekey={Uri.EscapeDataString(resourceKey)}";
        }
        return url;
    }

    private static string BuildEmbeddedFolderViewUrl(string folderId, string? resourceKey)
    {
        string url = $"https://drive.google.com/embeddedfolderview?id={Uri.EscapeDataString(folderId)}";
        if (!string.IsNullOrWhiteSpace(resourceKey))
        {
            url += $"&resourcekey={Uri.EscapeDataString(resourceKey)}";
        }
        return url + "#list";
    }

    private static string BuildFileViewUrl(string fileId, string? resourceKey)
    {
        string url = $"https://drive.google.com/file/d/{Uri.EscapeDataString(fileId)}/view";
        if (!string.IsNullOrWhiteSpace(resourceKey))
        {
            url += $"?resourcekey={Uri.EscapeDataString(resourceKey)}";
        }
        return url;
    }

    private static string BuildOpenUrl(string id, string? resourceKey)
    {
        string url = $"https://drive.google.com/open?id={Uri.EscapeDataString(id)}";
        if (!string.IsNullOrWhiteSpace(resourceKey))
        {
            url += $"&resourcekey={Uri.EscapeDataString(resourceKey)}";
        }
        return url;
    }

    private static string BuildDownloadUrl(string endpoint, string fileId, string? resourceKey, string confirm)
    {
        string url = $"{endpoint}?id={Uri.EscapeDataString(fileId)}&export=download&confirm={Uri.EscapeDataString(confirm)}";
        if (!string.IsNullOrWhiteSpace(resourceKey))
        {
            url += $"&resourcekey={Uri.EscapeDataString(resourceKey)}";
        }
        return url;
    }

    private static Dictionary<string, string> ParseAttributes(string attrText)
    {
        var dict = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (Match m in AttrRegex.Matches(attrText))
        {
            string name = m.Groups[1].Value;
            string value = m.Groups[2].Success ? m.Groups[2].Value
                : m.Groups[3].Success ? m.Groups[3].Value
                : m.Groups[4].Value;
            dict[name] = value;
        }
        return dict;
    }

    private static string? ReadStringProp(JsonElement obj, string propName) =>
        obj.TryGetProperty(propName, out var el) && el.ValueKind == JsonValueKind.String
            ? el.GetString()
            : null;

    private static bool TryParseHexByte(char c1, char c2, out byte value)
    {
        value = 0;
        if (!TryHexVal(c1, out int hi) || !TryHexVal(c2, out int lo)) return false;
        value = (byte)((hi << 4) | lo);
        return true;
    }

    private static bool TryParseHexUInt16(ReadOnlySpan<char> chars, out ushort value)
    {
        value = 0;
        if (chars.Length != 4) return false;
        int acc = 0;
        foreach (char c in chars)
        {
            if (!TryHexVal(c, out int digit)) return false;
            acc = (acc << 4) | digit;
        }
        value = (ushort)acc;
        return true;
    }

    private static bool TryHexVal(char c, out int val)
    {
        if (c is >= '0' and <= '9') { val = c - '0'; return true; }
        if (c is >= 'a' and <= 'f') { val = c - 'a' + 10; return true; }
        if (c is >= 'A' and <= 'F') { val = c - 'A' + 10; return true; }
        val = 0;
        return false;
    }

    private static Dictionary<string, string> ParseQueryString(string query)
    {
        var dict = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        if (string.IsNullOrEmpty(query)) return dict;
        foreach (string pair in query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            int eq = pair.IndexOf('=');
            if (eq < 0) continue;
            string k = Uri.UnescapeDataString(pair[..eq]);
            string v = Uri.UnescapeDataString(pair[(eq + 1)..]);
            dict[k] = v;
        }
        return dict;
    }
}
