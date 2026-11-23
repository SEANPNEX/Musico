using System.Diagnostics;
using System.IO.Compression;
using System.Net.Http;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using MusicoWindows.Core;
using MusicoWindows.Localization;

namespace MusicoWindows.Update;

/// <summary>
/// Checks GitHub Releases for new Musico versions, verifies SHA-256 checksums,
/// and applies updates in either Installer (.exe) or Portable (.zip) mode.
/// </summary>
public sealed class UpdateService : IDisposable
{
    public const string LatestReleaseUrl = "https://api.github.com/repos/SEANPNEX/Musico/releases/latest";
    public const string ReleasesPageUrl = "https://github.com/SEANPNEX/Musico/releases";

    private readonly HttpClient _http;
    private readonly string _prefsPath;
    private readonly string _baseDirectory;
    private readonly string _tempUpdatesDirectory;
    private readonly Func<ProcessStartInfo, bool> _processStarter;
    private readonly int _currentProcessId;
    private readonly object _stateLock = new();
    private bool _disposed;

    public UpdateService(
        string? stateDirectory = null,
        bool? isPortable = null,
        AppVersion? currentVersion = null,
        string? baseDirectory = null,
        string? tempUpdatesDirectory = null,
        HttpMessageHandler? handler = null,
        Func<ProcessStartInfo, bool>? processStarter = null,
        int? currentProcessId = null)
    {
        string resolvedStateDir = stateDirectory ?? Persistence.AppDataDirectory;
        _prefsPath = Path.Combine(resolvedStateDir, "update_prefs.json");
        IsPortable = isPortable ?? Persistence.IsPortableMode;
        CurrentVersion = currentVersion ?? GetCurrentAppVersion();
        _baseDirectory = string.IsNullOrWhiteSpace(baseDirectory) ? AppContext.BaseDirectory : baseDirectory;
        _tempUpdatesDirectory = string.IsNullOrWhiteSpace(tempUpdatesDirectory)
            ? Path.Combine(Path.GetTempPath(), "MusicoUpdates")
            : tempUpdatesDirectory;
        _processStarter = processStarter ?? (psi => Process.Start(psi) is not null);
        _currentProcessId = currentProcessId ?? Environment.ProcessId;

        _http = handler is not null
            ? new HttpClient(handler, disposeHandler: false)
            : new HttpClient();
        _http.Timeout = TimeSpan.FromSeconds(60);
        _http.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", $"Musico/{CurrentVersion}");
        _http.DefaultRequestHeaders.TryAddWithoutValidation("Accept", "application/vnd.github+json");
    }

    public AppVersion CurrentVersion { get; }
    public bool IsPortable { get; }

    public UpdateCheckResult? LatestCheckResult
    {
        get { lock (_stateLock) return _latestCheckResult; }
        private set { lock (_stateLock) _latestCheckResult = value; }
    }
    private UpdateCheckResult? _latestCheckResult;

    public event EventHandler<UpdateCheckResult>? UpdateStateChanged;

    public static AppVersion GetCurrentAppVersion()
    {
        Assembly asm = typeof(UpdateService).Assembly;
        var infoAttr = asm.GetCustomAttribute<AssemblyInformationalVersionAttribute>();
        if (infoAttr is not null && AppVersion.TryParse(infoAttr.InformationalVersion, out var parsedInfo))
        {
            return parsedInfo;
        }

        Version? asmVer = asm.GetName().Version;
        if (asmVer is not null)
        {
            return new AppVersion(
                Math.Max(0, asmVer.Major),
                Math.Max(0, asmVer.Minor),
                Math.Max(0, asmVer.Build),
                revision: Math.Max(0, asmVer.Revision));
        }

        return new AppVersion(1, 1, 1);
    }

    public async Task<UpdateCheckResult> CheckForUpdatesAsync(bool manual = false, CancellationToken ct = default)
    {
        UpdateCheckResult result;
        try
        {
            using var response = await _http.GetAsync(LatestReleaseUrl, ct).ConfigureAwait(false);
            response.EnsureSuccessStatusCode();
            string json = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            UpdateRelease release = ParseGitHubRelease(json);

            if (release.IsDraft || release.IsPrerelease)
            {
                result = new UpdateCheckResult(
                    UpdateCheckStatus.UpToDate,
                    CurrentVersion,
                    IsPortable: IsPortable);
            }
            else if (release.Version <= CurrentVersion)
            {
                result = new UpdateCheckResult(
                    UpdateCheckStatus.UpToDate,
                    CurrentVersion,
                    release,
                    IsPortable: IsPortable);
            }
            else
            {
                UpdateAsset? selected = SelectAsset(release.Assets, IsPortable);
                UpdateAsset? checksum = SelectChecksumAsset(release.Assets);

                if (!manual && IsVersionSkipped(release.Version))
                {
                    result = new UpdateCheckResult(
                        UpdateCheckStatus.Skipped,
                        CurrentVersion,
                        release,
                        selected,
                        checksum,
                        IsPortable);
                }
                else
                {
                    result = new UpdateCheckResult(
                        UpdateCheckStatus.UpdateAvailable,
                        CurrentVersion,
                        release,
                        selected,
                        checksum,
                        IsPortable);
                }
            }
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            result = new UpdateCheckResult(
                UpdateCheckStatus.Error,
                CurrentVersion,
                IsPortable: IsPortable,
                ErrorMessage: ex.Message);
        }

        LatestCheckResult = result;
        UpdateStateChanged?.Invoke(this, result);
        return result;
    }

    public string? GetSkippedVersion()
    {
        var prefs = Persistence.Load<UpdatePreferences>(_prefsPath);
        return string.IsNullOrWhiteSpace(prefs?.SkippedVersion) ? null : prefs.SkippedVersion;
    }

    public bool IsVersionSkipped(AppVersion version)
    {
        string? skipped = GetSkippedVersion();
        if (string.IsNullOrWhiteSpace(skipped)) return false;

        if (AppVersion.TryParse(skipped, out var skippedParsed))
        {
            return skippedParsed == version;
        }

        return string.Equals(skipped.Trim(), version.ToString(), StringComparison.OrdinalIgnoreCase);
    }

    public void SkipVersion(AppVersion version)
    {
        Persistence.Save(new UpdatePreferences { SkippedVersion = version.ToString() }, _prefsPath);

        UpdateCheckResult? updated = null;
        lock (_stateLock)
        {
            if (_latestCheckResult is { Status: UpdateCheckStatus.UpdateAvailable, Release: { } rel } current &&
                rel.Version == version)
            {
                updated = current with { Status = UpdateCheckStatus.Skipped };
                _latestCheckResult = updated;
            }
        }

        if (updated is not null)
        {
            UpdateStateChanged?.Invoke(this, updated);
        }
    }

    public void ClearSkippedVersion()
    {
        Persistence.Save(new UpdatePreferences { SkippedVersion = null }, _prefsPath);
    }

    public async Task DownloadAndApplyUpdateAsync(
        UpdateCheckResult checkResult,
        Action initiateShutdown,
        IProgress<UpdateProgress>? progress = null,
        CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(checkResult);
        ArgumentNullException.ThrowIfNull(initiateShutdown);

        if (checkResult.SelectedAsset is null)
        {
            throw new InvalidOperationException(
                IsPortable
                    ? "No portable (.zip) update package was found in this release."
                    : "No installer (.exe) update package was found in this release.");
        }

        if (checkResult.ChecksumAsset is null)
        {
            throw new InvalidDataException("SHA256SUMS.txt was not found in the release assets.");
        }

        Directory.CreateDirectory(_tempUpdatesDirectory);

        progress?.Report(new UpdateProgress(L.Get("Update_StageFetchingChecksums")));
        string checksumsText = await _http.GetStringAsync(checkResult.ChecksumAsset.DownloadUrl, ct).ConfigureAwait(false);
        var checksums = ParseSha256Sums(checksumsText);

        string assetFileName = Path.GetFileName(checkResult.SelectedAsset.Name);
        if (!checksums.TryGetValue(checkResult.SelectedAsset.Name, out string? expectedHash) &&
            !checksums.TryGetValue(assetFileName, out expectedHash))
        {
            throw new InvalidDataException($"SHA-256 checksum entry for '{checkResult.SelectedAsset.Name}' was not found in SHA256SUMS.txt.");
        }

        string downloadPath = Path.Combine(_tempUpdatesDirectory, assetFileName);
        await DownloadFileAsync(checkResult.SelectedAsset.DownloadUrl, downloadPath, checkResult.SelectedAsset.Size, progress, ct)
            .ConfigureAwait(false);

        progress?.Report(new UpdateProgress(L.Get("Update_StageVerifying")));
        string actualHash = await ComputeSha256HexAsync(downloadPath, ct).ConfigureAwait(false);
        if (!string.Equals(actualHash, expectedHash, StringComparison.OrdinalIgnoreCase))
        {
            TryDeleteFile(downloadPath);
            throw new InvalidDataException(
                $"SHA-256 verification failed for '{assetFileName}'. Expected {expectedHash}, got {actualHash}.");
        }

        if (!checkResult.IsPortable)
        {
            progress?.Report(new UpdateProgress(L.Get("Update_StageStartingInstaller")));
            var psi = new ProcessStartInfo
            {
                FileName = downloadPath,
                UseShellExecute = true
            };
            if (!_processStarter(psi))
            {
                throw new InvalidOperationException("Failed to launch the update installer.");
            }
            initiateShutdown();
            return;
        }

        progress?.Report(new UpdateProgress(L.Get("Update_StageExtracting")));
        string stagedDir = Path.Combine(_tempUpdatesDirectory, "staged");
        if (Directory.Exists(stagedDir))
        {
            Directory.Delete(stagedDir, recursive: true);
        }
        Directory.CreateDirectory(stagedDir);
        ZipFile.ExtractToDirectory(downloadPath, stagedDir, overwriteFiles: true);

        string sourceRoot = ResolveStagedRoot(stagedDir);
        string scriptPath = Path.Combine(_tempUpdatesDirectory, "apply-update.ps1");
        string scriptContent = BuildPortableUpdateScript(_currentProcessId, sourceRoot, _baseDirectory, _tempUpdatesDirectory);
        await File.WriteAllTextAsync(scriptPath, scriptContent, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false), ct)
            .ConfigureAwait(false);

        progress?.Report(new UpdateProgress(L.Get("Update_StageApplying")));
        var psInfo = new ProcessStartInfo
        {
            FileName = "powershell.exe",
            Arguments = $"-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File \"{scriptPath}\"",
            UseShellExecute = false,
            CreateNoWindow = true
        };
        if (!_processStarter(psInfo))
        {
            throw new InvalidOperationException("Failed to start the portable update helper script.");
        }
        initiateShutdown();
    }

    public static UpdateRelease ParseGitHubRelease(string json)
    {
        if (string.IsNullOrWhiteSpace(json))
        {
            throw new InvalidDataException("Empty GitHub release payload.");
        }

        using JsonDocument doc = JsonDocument.Parse(json);
        JsonElement root = doc.RootElement;

        string tagName = root.TryGetProperty("tag_name", out var tagEl) && tagEl.ValueKind == JsonValueKind.String
            ? tagEl.GetString() ?? string.Empty
            : string.Empty;

        if (!AppVersion.TryParse(tagName, out AppVersion version))
        {
            throw new InvalidDataException($"Unable to parse release tag_name '{tagName}'.");
        }

        string title = root.TryGetProperty("name", out var nameEl) && nameEl.ValueKind == JsonValueKind.String
            ? nameEl.GetString() ?? tagName
            : tagName;
        if (string.IsNullOrWhiteSpace(title)) title = tagName;

        string body = root.TryGetProperty("body", out var bodyEl) && bodyEl.ValueKind == JsonValueKind.String
            ? bodyEl.GetString() ?? string.Empty
            : string.Empty;

        string htmlUrl = root.TryGetProperty("html_url", out var htmlEl) && htmlEl.ValueKind == JsonValueKind.String
            ? htmlEl.GetString() ?? ReleasesPageUrl
            : ReleasesPageUrl;

        bool isDraft = root.TryGetProperty("draft", out var draftEl) && draftEl.ValueKind == JsonValueKind.True;
        bool isPrerelease = root.TryGetProperty("prerelease", out var preEl) && preEl.ValueKind == JsonValueKind.True;

        DateTimeOffset? publishedAt = null;
        if (root.TryGetProperty("published_at", out var pubEl) &&
            pubEl.ValueKind == JsonValueKind.String &&
            DateTimeOffset.TryParse(pubEl.GetString(), out var parsedPub))
        {
            publishedAt = parsedPub;
        }

        var assets = new List<UpdateAsset>();
        if (root.TryGetProperty("assets", out var assetsEl) && assetsEl.ValueKind == JsonValueKind.Array)
        {
            foreach (JsonElement item in assetsEl.EnumerateArray())
            {
                string? assetName = item.TryGetProperty("name", out var an) && an.ValueKind == JsonValueKind.String
                    ? an.GetString()
                    : null;
                string? url = item.TryGetProperty("browser_download_url", out var au) && au.ValueKind == JsonValueKind.String
                    ? au.GetString()
                    : null;
                long size = item.TryGetProperty("size", out var asz) && asz.TryGetInt64(out long s) ? s : 0;

                if (!string.IsNullOrWhiteSpace(assetName) && !string.IsNullOrWhiteSpace(url))
                {
                    assets.Add(new UpdateAsset(assetName, url, size));
                }
            }
        }

        return new UpdateRelease(version, tagName, title, body, htmlUrl, isDraft, isPrerelease, publishedAt, assets);
    }

    public static UpdateAsset? SelectAsset(IReadOnlyList<UpdateAsset> assets, bool isPortable)
    {
        if (assets.Count == 0) return null;

        if (!isPortable)
        {
            return assets.FirstOrDefault(a =>
                       a.Name.StartsWith("MusicoWindows-", StringComparison.OrdinalIgnoreCase) &&
                       a.Name.EndsWith("-win-x64-Setup.exe", StringComparison.OrdinalIgnoreCase))
                   ?? assets.FirstOrDefault(a =>
                       a.Name.EndsWith("Setup.exe", StringComparison.OrdinalIgnoreCase));
        }

        return assets.FirstOrDefault(a =>
                   a.Name.StartsWith("MusicoWindows-", StringComparison.OrdinalIgnoreCase) &&
                   a.Name.EndsWith("-win-x64-portable.zip", StringComparison.OrdinalIgnoreCase))
               ?? assets.FirstOrDefault(a =>
                   a.Name.EndsWith("portable.zip", StringComparison.OrdinalIgnoreCase));
    }

    public static UpdateAsset? SelectChecksumAsset(IReadOnlyList<UpdateAsset> assets)
    {
        if (assets.Count == 0) return null;

        return assets.FirstOrDefault(a => string.Equals(a.Name, "SHA256SUMS.txt", StringComparison.OrdinalIgnoreCase))
               ?? assets.FirstOrDefault(a =>
                   a.Name.Contains("SHA256SUMS", StringComparison.OrdinalIgnoreCase) &&
                   a.Name.EndsWith(".txt", StringComparison.OrdinalIgnoreCase));
    }

    public static Dictionary<string, string> ParseSha256Sums(string content)
    {
        var map = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        if (string.IsNullOrWhiteSpace(content)) return map;

        string[] lines = content.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries);
        foreach (string rawLine in lines)
        {
            string line = rawLine.Trim();
            if (line.Length == 0 || line.StartsWith('#')) continue;

            int sepIdx = line.IndexOfAny(new[] { ' ', '\t' });
            if (sepIdx != 64) continue;

            string hash = line[..64];
            if (!IsHexString(hash)) continue;

            string filePart = line[(sepIdx + 1)..].Trim();
            if (filePart.StartsWith('*'))
            {
                filePart = filePart[1..].TrimStart();
            }
            if (filePart.StartsWith("./", StringComparison.Ordinal) || filePart.StartsWith(".\\", StringComparison.Ordinal))
            {
                filePart = filePart[2..];
            }
            if (string.IsNullOrWhiteSpace(filePart)) continue;

            string normalizedHash = hash.ToLowerInvariant();
            map[filePart] = normalizedHash;

            string fileNameOnly = Path.GetFileName(filePart.Replace('\\', '/'));
            if (!string.IsNullOrWhiteSpace(fileNameOnly))
            {
                map[fileNameOnly] = normalizedHash;
            }
        }

        return map;
    }

    public static async Task<string> ComputeSha256HexAsync(string filePath, CancellationToken ct = default)
    {
        await using var fs = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read, 81920, useAsync: true);
        byte[] digest = await SHA256.HashDataAsync(fs, ct).ConfigureAwait(false);
        return Convert.ToHexString(digest).ToLowerInvariant();
    }

    public static async Task<bool> VerifySha256Async(string filePath, string expectedSha256Hex, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(expectedSha256Hex)) return false;
        string actual = await ComputeSha256HexAsync(filePath, ct).ConfigureAwait(false);
        return string.Equals(actual, expectedSha256Hex.Trim(), StringComparison.OrdinalIgnoreCase);
    }

    public static string ResolveStagedRoot(string stagedDir)
    {
        if (File.Exists(Path.Combine(stagedDir, "MusicoWindows.exe")))
        {
            return stagedDir;
        }

        string[] subDirs = Directory.GetDirectories(stagedDir);
        foreach (string dir in subDirs)
        {
            if (File.Exists(Path.Combine(dir, "MusicoWindows.exe")))
            {
                return dir;
            }
        }

        if (subDirs.Length == 1 && Directory.GetFiles(stagedDir).Length == 0)
        {
            return subDirs[0];
        }

        return stagedDir;
    }

    public static string BuildPortableUpdateScript(int processId, string sourceDir, string targetDir, string updatesTempDir)
    {
        string src = EscapePowerShellSingleQuoted(sourceDir.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
        string dst = EscapePowerShellSingleQuoted(targetDir.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
        string tmp = EscapePowerShellSingleQuoted(updatesTempDir.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));

        return $$"""
            $ErrorActionPreference = 'Stop'
            $pidToWait = {{processId}}
            $sourceDir = '{{src}}'
            $targetDir = '{{dst}}'
            $tempDir = '{{tmp}}'

            try {
                $proc = Get-Process -Id $pidToWait -ErrorAction SilentlyContinue
                if ($null -ne $proc) {
                    $null = $proc.WaitForExit(30000)
                }
            } catch {}
            Start-Sleep -Milliseconds 400

            $hasPortableMarker = Test-Path -LiteralPath (Join-Path $targetDir 'portable.txt')
            $files = Get-ChildItem -LiteralPath $sourceDir -Recurse -File
            foreach ($file in $files) {
                $rel = $file.FullName.Substring($sourceDir.Length).TrimStart('\', '/')
                if ($rel -ieq 'Data' -or $rel.StartsWith('Data\', [System.StringComparison]::OrdinalIgnoreCase) -or $rel.StartsWith('Data/', [System.StringComparison]::OrdinalIgnoreCase)) {
                    continue
                }
                if ($hasPortableMarker -and ($rel -ieq 'portable.txt')) {
                    continue
                }
                $destPath = Join-Path $targetDir $rel
                $destParent = Split-Path -Path $destPath -Parent
                if (-not (Test-Path -LiteralPath $destParent)) {
                    New-Item -ItemType Directory -Path $destParent -Force | Out-Null
                }
                for ($attempt = 0; $attempt -lt 6; $attempt++) {
                    try {
                        Copy-Item -LiteralPath $file.FullName -Destination $destPath -Force
                        break
                    } catch {
                        if ($attempt -eq 5) { throw }
                        Start-Sleep -Milliseconds 300
                    }
                }
            }

            $exePath = Join-Path $targetDir 'MusicoWindows.exe'
            if (Test-Path -LiteralPath $exePath) {
                Start-Process -FilePath $exePath -WorkingDirectory $targetDir
            }

            try {
                if (Test-Path -LiteralPath $tempDir) {
                    Remove-Item -LiteralPath $tempDir -Recurse -Force -ErrorAction SilentlyContinue
                }
            } catch {}
            """;
    }

    private async Task DownloadFileAsync(
        string url,
        string destinationPath,
        long fallbackTotalBytes,
        IProgress<UpdateProgress>? progress,
        CancellationToken ct)
    {
        using var response = await _http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        response.EnsureSuccessStatusCode();

        long? contentLength = response.Content.Headers.ContentLength ?? (fallbackTotalBytes > 0 ? fallbackTotalBytes : null);
        long totalRead = 0;
        progress?.Report(new UpdateProgress(L.Get("Update_StageDownloading"), 0, contentLength));

        string tempPart = $"{destinationPath}.{Guid.NewGuid():N}.part";
        try
        {
            await using (var remote = await response.Content.ReadAsStreamAsync(ct).ConfigureAwait(false))
            await using (var local = new FileStream(tempPart, FileMode.Create, FileAccess.Write, FileShare.None, 81920, useAsync: true))
            {
                byte[] buffer = new byte[81920];
                int read;
                while ((read = await remote.ReadAsync(buffer, ct).ConfigureAwait(false)) > 0)
                {
                    await local.WriteAsync(buffer.AsMemory(0, read), ct).ConfigureAwait(false);
                    totalRead += read;
                    progress?.Report(new UpdateProgress(L.Get("Update_StageDownloading"), totalRead, contentLength));
                }
                await local.FlushAsync(ct).ConfigureAwait(false);
            }

            File.Move(tempPart, destinationPath, overwrite: true);
        }
        catch
        {
            TryDeleteFile(tempPart);
            throw;
        }
    }

    private static string EscapePowerShellSingleQuoted(string value) => value.Replace("'", "''");

    private static bool IsHexString(string text)
    {
        foreach (char c in text)
        {
            bool isHex = c is (>= '0' and <= '9') or (>= 'a' and <= 'f') or (>= 'A' and <= 'F');
            if (!isHex) return false;
        }
        return true;
    }

    private static void TryDeleteFile(string path)
    {
        try
        {
            if (File.Exists(path)) File.Delete(path);
        }
        catch
        {
            // best effort
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _http.Dispose();
    }
}
