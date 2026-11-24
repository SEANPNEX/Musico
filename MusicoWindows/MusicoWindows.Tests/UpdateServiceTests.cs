using System.Diagnostics;
using System.IO.Compression;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using MusicoWindows.Core;
using MusicoWindows.Update;

namespace MusicoWindows.Tests;

public sealed class UpdateServiceTests
{
    private sealed class StubHttpHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, HttpResponseMessage> _responder;
        public List<HttpRequestMessage> Requests { get; } = new();

        public StubHttpHandler(Func<HttpRequestMessage, HttpResponseMessage> responder)
        {
            _responder = responder;
        }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Requests.Add(request);
            return Task.FromResult(_responder(request));
        }
    }

    private static HttpResponseMessage JsonResponse(string json, HttpStatusCode code = HttpStatusCode.OK) =>
        new(code) { Content = new StringContent(json, Encoding.UTF8, "application/json") };

    private static string ComputeSha256(byte[] data) =>
        Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant();

    private static string SampleReleaseJson(
        string tagName = "v1.2.0",
        bool draft = false,
        bool prerelease = false,
        string body = "Bug fixes and performance improvements.") =>
        $$"""
        {
          "tag_name": "{{tagName}}",
          "name": "Musico {{tagName}}",
          "body": "{{body}}",
          "html_url": "https://github.com/SEANPNEX/Musico/releases/tag/{{tagName}}",
          "draft": {{(draft ? "true" : "false")}},
          "prerelease": {{(prerelease ? "true" : "false")}},
          "published_at": "2026-11-22T00:00:00Z",
          "assets": [
            {
              "name": "MusicoWindows-1.2.0-win-x64-Setup.exe",
              "browser_download_url": "https://downloads.example/MusicoWindows-1.2.0-win-x64-Setup.exe",
              "size": 1024
            },
            {
              "name": "MusicoWindows-1.2.0-win-x64-portable.zip",
              "browser_download_url": "https://downloads.example/MusicoWindows-1.2.0-win-x64-portable.zip",
              "size": 2048
            },
            {
              "name": "SHA256SUMS.txt",
              "browser_download_url": "https://downloads.example/SHA256SUMS.txt",
              "size": 256
            }
          ]
        }
        """;

    [Fact]
    public void AppVersionParsesPrefixesAndComparesNumerically()
    {
        Assert.True(AppVersion.TryParse("1.1.1", out var v1));
        Assert.True(AppVersion.TryParse("v1.1.1", out var v2));
        Assert.True(AppVersion.TryParse("V1.1.1", out var v3));
        Assert.True(AppVersion.TryParse("v.1.1.1", out var v4));
        Assert.True(AppVersion.TryParse("V.1.1.1", out var v5));
        Assert.True(AppVersion.TryParse("1.1.1.0", out var v6));

        Assert.Equal(v1, v2);
        Assert.Equal(v1, v3);
        Assert.Equal(v1, v4);
        Assert.Equal(v1, v5);
        Assert.Equal(v1, v6);
        Assert.Equal("1.1.1", v4.ToString());

        var v19 = AppVersion.Parse("1.9.0");
        var v110 = AppVersion.Parse("1.10.0");
        Assert.True(v110 > v19);
        Assert.True(v19 < v110);

        var pre = AppVersion.Parse("v1.2.0-beta.1");
        var stable = AppVersion.Parse("1.2.0");
        Assert.True(stable > pre);

        Assert.False(AppVersion.TryParse("", out _));
        Assert.False(AppVersion.TryParse("v", out _));
        Assert.False(AppVersion.TryParse("abc", out _));
        Assert.False(AppVersion.TryParse("1", out _));
        Assert.False(AppVersion.TryParse("-1.0.0", out _));
        Assert.False(AppVersion.TryParse("1.0.0-", out _));
    }

    [Fact]
    public void ParseGitHubReleaseAndSelectModeAwareAssets()
    {
        UpdateRelease release = UpdateService.ParseGitHubRelease(SampleReleaseJson("V.1.2.0"));

        Assert.Equal(AppVersion.Parse("1.2.0"), release.Version);
        Assert.Equal("V.1.2.0", release.TagName);
        Assert.False(release.IsDraft);
        Assert.False(release.IsPrerelease);
        Assert.Equal(3, release.Assets.Count);

        var installer = UpdateService.SelectAsset(release.Assets, isPortable: false);
        Assert.NotNull(installer);
        Assert.Equal("MusicoWindows-1.2.0-win-x64-Setup.exe", installer!.Name);

        var portable = UpdateService.SelectAsset(release.Assets, isPortable: true);
        Assert.NotNull(portable);
        Assert.Equal("MusicoWindows-1.2.0-win-x64-portable.zip", portable!.Name);

        var checksum = UpdateService.SelectChecksumAsset(release.Assets);
        Assert.NotNull(checksum);
        Assert.Equal("SHA256SUMS.txt", checksum!.Name);

        // Fallback asset matching
        var fallbackAssets = new[]
        {
            new UpdateAsset("CustomSetup.exe", "https://example.com/CustomSetup.exe", 10),
            new UpdateAsset("App-portable.zip", "https://example.com/App-portable.zip", 20)
        };
        Assert.Equal("CustomSetup.exe", UpdateService.SelectAsset(fallbackAssets, isPortable: false)?.Name);
        Assert.Equal("App-portable.zip", UpdateService.SelectAsset(fallbackAssets, isPortable: true)?.Name);
    }

    [Fact]
    public async Task CheckForUpdatesSendsUserAgentAndIgnoresDraftOrPrerelease()
    {
        string stateDir = TestHelpers.MakeTempDir();
        var handler = new StubHttpHandler(_ => JsonResponse(SampleReleaseJson("v1.2.0")));
        using var service = new UpdateService(
            stateDirectory: stateDir,
            isPortable: false,
            currentVersion: AppVersion.Parse("1.1.1"),
            handler: handler);

        UpdateCheckResult available = await service.CheckForUpdatesAsync(manual: false);
        Assert.Equal(UpdateCheckStatus.UpdateAvailable, available.Status);
        Assert.True(available.IsUpdateAvailable);
        Assert.Equal("MusicoWindows-1.2.0-win-x64-Setup.exe", available.SelectedAsset?.Name);
        Assert.Single(handler.Requests);
        Assert.Equal(UpdateService.LatestReleaseUrl, handler.Requests[0].RequestUri?.ToString());
        Assert.Equal("Musico/1.1.1", handler.Requests[0].Headers.UserAgent.ToString());

        // Draft release is ignored
        var draftHandler = new StubHttpHandler(_ => JsonResponse(SampleReleaseJson("v1.3.0", draft: true)));
        using var draftService = new UpdateService(
            stateDirectory: stateDir,
            isPortable: false,
            currentVersion: AppVersion.Parse("1.1.1"),
            handler: draftHandler);
        UpdateCheckResult draftResult = await draftService.CheckForUpdatesAsync(manual: true);
        Assert.Equal(UpdateCheckStatus.UpToDate, draftResult.Status);
        Assert.False(draftResult.IsUpdateAvailable);

        // Prerelease is ignored
        var preHandler = new StubHttpHandler(_ => JsonResponse(SampleReleaseJson("v1.3.0", prerelease: true)));
        using var preService = new UpdateService(
            stateDirectory: stateDir,
            isPortable: false,
            currentVersion: AppVersion.Parse("1.1.1"),
            handler: preHandler);
        UpdateCheckResult preResult = await preService.CheckForUpdatesAsync(manual: true);
        Assert.Equal(UpdateCheckStatus.UpToDate, preResult.Status);
        Assert.False(preResult.IsUpdateAvailable);
    }

    [Fact]
    public async Task ParseSha256SumsAndVerifyFileChecksum()
    {
        string content = """
            # Release checksums
            ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789  ./dist/MusicoWindows-1.2.0-win-x64-Setup.exe
            0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef *MusicoWindows-1.2.0-win-x64-portable.zip
            """;

        var parsed = UpdateService.ParseSha256Sums(content);
        Assert.Equal(
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
            parsed["MusicoWindows-1.2.0-win-x64-Setup.exe"]);
        Assert.Equal(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            parsed["MusicoWindows-1.2.0-win-x64-portable.zip"]);

        string dir = TestHelpers.MakeTempDir();
        string file = Path.Combine(dir, "payload.bin");
        byte[] bytes = Encoding.UTF8.GetBytes("musico-update-binary");
        await File.WriteAllBytesAsync(file, bytes);

        string expected = ComputeSha256(bytes);
        Assert.True(await UpdateService.VerifySha256Async(file, expected.ToUpperInvariant()));
        Assert.False(await UpdateService.VerifySha256Async(file, new string('0', 64)));
    }

    [Fact]
    public async Task SkipVersionPersistsAndRespectsSilentVsManualChecks()
    {
        string stateDir = TestHelpers.MakeTempDir();
        var handler = new StubHttpHandler(_ => JsonResponse(SampleReleaseJson("v1.2.0")));
        using var service = new UpdateService(
            stateDirectory: stateDir,
            isPortable: false,
            currentVersion: AppVersion.Parse("1.1.1"),
            handler: handler);

        UpdateCheckResult first = await service.CheckForUpdatesAsync(manual: false);
        Assert.Equal(UpdateCheckStatus.UpdateAvailable, first.Status);

        bool notifiedCleared = false;
        service.UpdateStateChanged += (_, res) =>
        {
            if (res.Status == UpdateCheckStatus.Skipped) notifiedCleared = true;
        };
        service.SkipVersion(AppVersion.Parse("1.2.0"));
        Assert.True(notifiedCleared);
        Assert.True(File.Exists(Path.Combine(stateDir, "update_prefs.json")));

        // New instance reads persisted skippedVersion
        using var reloaded = new UpdateService(
            stateDirectory: stateDir,
            isPortable: false,
            currentVersion: AppVersion.Parse("1.1.1"),
            handler: handler);

        UpdateCheckResult silentAfterSkip = await reloaded.CheckForUpdatesAsync(manual: false);
        Assert.Equal(UpdateCheckStatus.Skipped, silentAfterSkip.Status);
        Assert.False(silentAfterSkip.IsUpdateAvailable);

        // Manual check still reports UpdateAvailable
        UpdateCheckResult manualAfterSkip = await reloaded.CheckForUpdatesAsync(manual: true);
        Assert.Equal(UpdateCheckStatus.UpdateAvailable, manualAfterSkip.Status);
        Assert.True(manualAfterSkip.IsUpdateAvailable);
    }

    [Fact]
    public async Task DownloadAndApplyInstallerVerifiesSha256LaunchesSetupAndShutsDown()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string tempUpdates = TestHelpers.MakeTempDir();
        byte[] exePayload = Encoding.UTF8.GetBytes("MZ-fake-setup-installer");
        string exeSha = ComputeSha256(exePayload);
        string sumsText = $"{exeSha}  MusicoWindows-1.2.0-win-x64-Setup.exe\n";

        var handler = new StubHttpHandler(req =>
        {
            string url = req.RequestUri!.ToString();
            if (url == UpdateService.LatestReleaseUrl) return JsonResponse(SampleReleaseJson("v1.2.0"));
            if (url.EndsWith("SHA256SUMS.txt", StringComparison.Ordinal)) return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(sumsText) };
            if (url.EndsWith("Setup.exe", StringComparison.Ordinal)) return new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(exePayload) };
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        ProcessStartInfo? startedPsi = null;
        bool shutdownCalled = false;

        using var service = new UpdateService(
            stateDirectory: stateDir,
            isPortable: false,
            currentVersion: AppVersion.Parse("1.1.1"),
            tempUpdatesDirectory: tempUpdates,
            handler: handler,
            processStarter: psi => { startedPsi = psi; return true; });

        var check = await service.CheckForUpdatesAsync(manual: true);
        await service.DownloadAndApplyUpdateAsync(check, () => shutdownCalled = true);

        Assert.True(shutdownCalled);
        Assert.NotNull(startedPsi);
        Assert.True(startedPsi!.UseShellExecute);
        Assert.Equal(Path.Combine(tempUpdates, "MusicoWindows-1.2.0-win-x64-Setup.exe"), startedPsi.FileName);
        Assert.True(File.Exists(startedPsi.FileName));
    }

    [Fact]
    public async Task DownloadAndApplyRejectsSha256MismatchAndDeletesCorruptDownload()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string tempUpdates = TestHelpers.MakeTempDir();
        byte[] exePayload = Encoding.UTF8.GetBytes("tampered-binary");
        string wrongSha = new string('a', 64);
        string sumsText = $"{wrongSha}  MusicoWindows-1.2.0-win-x64-Setup.exe\n";

        var handler = new StubHttpHandler(req =>
        {
            string url = req.RequestUri!.ToString();
            if (url == UpdateService.LatestReleaseUrl) return JsonResponse(SampleReleaseJson("v1.2.0"));
            if (url.EndsWith("SHA256SUMS.txt", StringComparison.Ordinal)) return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(sumsText) };
            if (url.EndsWith("Setup.exe", StringComparison.Ordinal)) return new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(exePayload) };
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        bool started = false;
        bool shutdownCalled = false;

        using var service = new UpdateService(
            stateDirectory: stateDir,
            isPortable: false,
            currentVersion: AppVersion.Parse("1.1.1"),
            tempUpdatesDirectory: tempUpdates,
            handler: handler,
            processStarter: _ => { started = true; return true; });

        var check = await service.CheckForUpdatesAsync(manual: true);
        await Assert.ThrowsAsync<InvalidDataException>(() =>
            service.DownloadAndApplyUpdateAsync(check, () => shutdownCalled = true));

        Assert.False(started);
        Assert.False(shutdownCalled);
        Assert.False(File.Exists(Path.Combine(tempUpdates, "MusicoWindows-1.2.0-win-x64-Setup.exe")));
    }

    [Fact]
    public async Task DownloadAndApplyPortableExtractsStagesWritesHelperScriptAndShutsDown()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string baseDir = TestHelpers.MakeTempDir();
        string tempUpdates = TestHelpers.MakeTempDir();

        // Build a realistic portable zip containing nested folder MusicoWindows-1.2.0-win-x64-portable/
        string zipSource = TestHelpers.MakeTempDir();
        string nested = Path.Combine(zipSource, "MusicoWindows-1.2.0-win-x64-portable");
        Directory.CreateDirectory(nested);
        File.WriteAllText(Path.Combine(nested, "MusicoWindows.exe"), "new-exe-bytes");
        File.WriteAllText(Path.Combine(nested, "portable.txt"), "new-portable-txt");
        string zipPath = Path.Combine(TestHelpers.MakeTempDir(), "MusicoWindows-1.2.0-win-x64-portable.zip");
        ZipFile.CreateFromDirectory(zipSource, zipPath);

        byte[] zipBytes = await File.ReadAllBytesAsync(zipPath);
        string zipSha = ComputeSha256(zipBytes);
        string sumsText = $"{zipSha}  MusicoWindows-1.2.0-win-x64-portable.zip\n";

        var handler = new StubHttpHandler(req =>
        {
            string url = req.RequestUri!.ToString();
            if (url == UpdateService.LatestReleaseUrl) return JsonResponse(SampleReleaseJson("v1.2.0"));
            if (url.EndsWith("SHA256SUMS.txt", StringComparison.Ordinal)) return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(sumsText) };
            if (url.EndsWith("portable.zip", StringComparison.Ordinal)) return new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(zipBytes) };
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        ProcessStartInfo? startedPsi = null;
        bool shutdownCalled = false;

        using var service = new UpdateService(
            stateDirectory: stateDir,
            isPortable: true,
            currentVersion: AppVersion.Parse("1.1.1"),
            baseDirectory: baseDir,
            tempUpdatesDirectory: tempUpdates,
            handler: handler,
            processStarter: psi => { startedPsi = psi; return true; },
            currentProcessId: 424242);

        var check = await service.CheckForUpdatesAsync(manual: true);
        Assert.Equal("MusicoWindows-1.2.0-win-x64-portable.zip", check.SelectedAsset?.Name);

        await service.DownloadAndApplyUpdateAsync(check, () => shutdownCalled = true);

        Assert.True(shutdownCalled);
        Assert.NotNull(startedPsi);
        Assert.Equal("powershell.exe", startedPsi!.FileName);
        Assert.Contains("apply-update.ps1", startedPsi.Arguments);

        string stagedExe = Path.Combine(tempUpdates, "staged", "MusicoWindows-1.2.0-win-x64-portable", "MusicoWindows.exe");
        Assert.True(File.Exists(stagedExe));

        string scriptPath = Path.Combine(tempUpdates, "apply-update.ps1");
        Assert.True(File.Exists(scriptPath));
        string script = await File.ReadAllTextAsync(scriptPath);
        Assert.Contains("424242", script);
        Assert.Contains("portable.txt", script);
        Assert.Contains("'Data'", script);
        Assert.Contains("MusicoWindows.exe", script);
        Assert.Contains(tempUpdates.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar), script);
    }

    [Fact]
    public void PersistenceExposesIsPortableMode()
    {
        bool portable = Persistence.IsPortableMode;
        Assert.Equal(File.Exists(Path.Combine(AppContext.BaseDirectory, "portable.txt")), portable);
    }
}
