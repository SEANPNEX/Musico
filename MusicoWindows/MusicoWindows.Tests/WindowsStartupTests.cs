using System.Diagnostics;
using System.Text;
using MusicoWindows.Core;
using Xunit;

namespace MusicoWindows.Tests;

public sealed class WindowsStartupTests : IDisposable
{
    private readonly List<string> _tempDirs = new();

    public void Dispose()
    {
        foreach (string dir in _tempDirs)
        {
            try
            {
                if (Directory.Exists(dir)) Directory.Delete(dir, recursive: true);
            }
            catch
            {
                // Best-effort temp cleanup.
            }
        }
    }

    private string NewTempDir()
    {
        string dir = TestHelpers.MakeTempDir();
        _tempDirs.Add(dir);
        return dir;
    }

    [Fact]
    public void CrashLogger_WritesReportToPreferredDirectory()
    {
        string dir = NewTempDir();
        var inner = new InvalidDataException("Missing resource dictionary key.");
        var outer = new InvalidOperationException("Window initialization failed.", inner);

        string? logPath = CrashLogger.WriteCrashLog(outer, "App.OnStartup", preferredDirectory: dir);

        Assert.NotNull(logPath);
        Assert.Equal(Path.Combine(dir, "crash.log"), logPath);
        Assert.True(File.Exists(logPath));

        string text = File.ReadAllText(logPath!);
        Assert.Contains("Context:         App.OnStartup", text, StringComparison.Ordinal);
        Assert.Contains("InvalidOperationException: Window initialization failed.", text, StringComparison.Ordinal);
        Assert.Contains("InvalidDataException: Missing resource dictionary key.", text, StringComparison.Ordinal);
        Assert.Contains("OS Architecture:", text, StringComparison.Ordinal);
        Assert.Contains("Runtime:", text, StringComparison.Ordinal);
    }

    [Fact]
    public void CrashLogger_FallsBackToFallbackDirectoryWhenPreferredIsBlocked()
    {
        string blockingFile = Path.Combine(NewTempDir(), "not-a-directory");
        File.WriteAllText(blockingFile, "occupied");
        string fallbackDir = NewTempDir();

        var ex = new TypeLoadException("Simulated WinRT projection load failure.");
        string? logPath = CrashLogger.WriteCrashLog(
            ex,
            "SmtcBridge.Ctor",
            preferredDirectory: blockingFile,
            fallbackDirectory: fallbackDir);

        Assert.NotNull(logPath);
        Assert.Equal(Path.Combine(fallbackDir, "MusicoWindows-crash.log"), logPath);
        Assert.True(File.Exists(logPath));

        string text = File.ReadAllText(logPath!);
        Assert.Contains("SmtcBridge.Ctor", text, StringComparison.Ordinal);
        Assert.Contains("Simulated WinRT projection load failure.", text, StringComparison.Ordinal);
    }

    [Fact]
    public void CrashLogger_UnwrapInnermost_ExtractsRootCause()
    {
        var root = new MissingMethodException("Method not found.");
        var wrapper = new System.Reflection.TargetInvocationException("Target invocation failed.", root);

        Exception unwrapped = CrashLogger.UnwrapInnermost(wrapper);
        Assert.Same(root, unwrapped);
    }

#if WINDOWS
    [Fact]
    public async Task WpfApp_Startup_LoadsResourcesThemesLocalesAndMainWindow()
    {
        string stateDir = NewTempDir();
        string musicDir = NewTempDir();
        string errFile = Path.Combine(stateDir, "smoke-error.log");

        // Seed a minimal valid MP3 so LibraryStore + QueuesView + TrackRowControl exercise track rendering.
        byte[] frame = new byte[417];
        frame[0] = 0xFF;
        frame[1] = 0xFB;
        frame[2] = 0x90;
        frame[3] = 0x00;
        byte[] mp3 = new byte[frame.Length * 4];
        for (int i = 0; i < 4; i++) Array.Copy(frame, 0, mp3, i * frame.Length, frame.Length);
        File.WriteAllBytes(Path.Combine(musicDir, "startup-smoke.mp3"), mp3);

        string exePath = Path.Combine(AppContext.BaseDirectory, "MusicoWindows.exe");
        string dllPath = Path.Combine(AppContext.BaseDirectory, "MusicoWindows.dll");

        string args =
            $"--startup-smoke-test --state-dir=\"{stateDir}\" --music-dir=\"{musicDir}\" --smoke-error-file=\"{errFile}\"";

        var psi = new ProcessStartInfo
        {
            WorkingDirectory = AppContext.BaseDirectory,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true
        };

        if (File.Exists(exePath))
        {
            psi.FileName = exePath;
            psi.Arguments = args;
        }
        else if (File.Exists(dllPath))
        {
            psi.FileName = "dotnet";
            psi.Arguments = $"\"{dllPath}\" {args}";
        }
        else
        {
            throw new FileNotFoundException($"Could not locate MusicoWindows.exe or MusicoWindows.dll in '{AppContext.BaseDirectory}'.");
        }

        using var proc = Process.Start(psi)
            ?? throw new InvalidOperationException($"Failed to start '{psi.FileName}'.");

        Task<string> stdoutTask = proc.StandardOutput.ReadToEndAsync();
        Task<string> stderrTask = proc.StandardError.ReadToEndAsync();

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(60));
        bool timedOut = false;
        try
        {
            await proc.WaitForExitAsync(cts.Token);
        }
        catch (OperationCanceledException)
        {
            timedOut = true;
            try { proc.Kill(entireProcessTree: true); } catch { }
        }

        string stdout = await stdoutTask;
        string stderr = await stderrTask;
        string errDetails = File.Exists(errFile) ? File.ReadAllText(errFile, Encoding.UTF8) : string.Empty;
        string crashDetails = File.Exists(Path.Combine(stateDir, "crash.log"))
            ? File.ReadAllText(Path.Combine(stateDir, "crash.log"), Encoding.UTF8)
            : string.Empty;

        Assert.False(timedOut, $"MusicoWindows --startup-smoke-test timed out after 60s.\nERRFILE:\n{errDetails}\nCRASH:\n{crashDetails}\nSTDERR:\n{stderr}\nSTDOUT:\n{stdout}");
        Assert.True(
            proc.ExitCode == 0,
            $"MusicoWindows --startup-smoke-test exited with code {proc.ExitCode}.\nERRFILE:\n{errDetails}\nCRASH:\n{crashDetails}\nSTDERR:\n{stderr}\nSTDOUT:\n{stdout}");
    }
#endif
}
