using MusicoWindows.Core;
using Xunit;
#if WINDOWS
using System.Windows;
using System.Windows.Threading;
using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using MusicoWindows.Theme;
#endif

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
    public void WpfApp_Startup_LoadsResourcesThemesLocalesAndMainWindow()
    {
        string stateDir = NewTempDir();
        string musicDir = NewTempDir();
        string themePrefPath = Path.Combine(stateDir, "theme.txt");
        string langPrefPath = Path.Combine(stateDir, "language.txt");

        // Seed a minimal valid MP3 so LibraryStore + QueuesView + TrackRowControl exercise track rendering.
        byte[] frame = new byte[417];
        frame[0] = 0xFF;
        frame[1] = 0xFB;
        frame[2] = 0x90;
        frame[3] = 0x00;
        byte[] mp3 = new byte[frame.Length * 4];
        for (int i = 0; i < 4; i++) Array.Copy(frame, 0, mp3, i * frame.Length, frame.Length);
        File.WriteAllBytes(Path.Combine(musicDir, "startup-smoke.mp3"), mp3);

        Exception? staException = null;
        using var done = new ManualResetEventSlim(false);

        var thread = new Thread(() =>
        {
            Dispatcher? dispatcher = null;
            AppServices? services = null;
            MainWindow? window = null;
            try
            {
                dispatcher = Dispatcher.CurrentDispatcher;
                if (Application.ResourceAssembly is null)
                {
                    Application.ResourceAssembly = typeof(App).Assembly;
                }

                App app;
                if (Application.Current is App existing)
                {
                    app = existing;
                }
                else
                {
                    app = new App
                    {
                        ShutdownMode = ShutdownMode.OnExplicitShutdown
                    };
                    app.InitializeComponent();
                }

                ThemeManager.Initialize(app, themePrefPath);
                LocalizationManager.Initialize(app, langPrefPath);

                services = new AppServices(stateDirectory: stateDir, musicDirectory: musicDir);
                services.InitializeAsync().GetAwaiter().GetResult();

                if (services.Library.Tracks.Count > 0)
                {
                    var trackIds = services.Library.Tracks.Select(t => t.Id).ToList();
                    services.Queues.UpdateActive(q => q.Replace(trackIds, 0));
                }

                window = new MainWindow(services);
                app.MainWindow = window;
                window.Show();

                // Pump WPF layout, bindings, styles, and dialogs across theme/locale switches.
                dispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);

                window.FullPlayer.Show();
                dispatcher.Invoke(() => { }, DispatcherPriority.Background);
                window.FullPlayer.Close();

                window.CloudDialog.Show(services);
                dispatcher.Invoke(() => { }, DispatcherPriority.Background);
                window.CloudDialog.Close();

                if (services.Library.Roots.FirstOrDefault() is { } firstRoot)
                {
                    window.EditCloudDialog.Show(services, firstRoot);
                    dispatcher.Invoke(() => { }, DispatcherPriority.Background);
                    window.EditCloudDialog.Close();
                }

                ThemeManager.ToggleTheme(app);
                ThemeManager.ToggleTheme(app);
                LocalizationManager.SetMode(app, AppLanguage.SimplifiedChinese, langPrefPath);
                LocalizationManager.SetMode(app, AppLanguage.English, langPrefPath);
                dispatcher.Invoke(() => { }, DispatcherPriority.Background);
            }
            catch (Exception ex)
            {
                staException = ex;
            }
            finally
            {
                try { window?.Close(); } catch { }
                try { services?.Dispose(); } catch { }
                try { dispatcher?.InvokeShutdown(); } catch { }
                done.Set();
            }
        });

        thread.SetApartmentState(ApartmentState.STA);
        thread.IsBackground = true;
        thread.Start();

        Assert.True(done.Wait(TimeSpan.FromSeconds(60)), "WPF startup smoke test timed out.");
        if (staException is not null)
        {
            System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(staException).Throw();
        }
    }
#endif
}
