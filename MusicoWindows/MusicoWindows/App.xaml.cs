using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;
using MusicoWindows.Core;
using MusicoWindows.Localization;
using MusicoWindows.Stores;
using MusicoWindows.Theme;

namespace MusicoWindows;

public partial class App : Application
{
    private Mutex? _instanceMutex;
    private bool _ownsInstanceMutex;
    private int _fatalDialogShown;
    private readonly bool _isSmokeTest;
    private string? _smokeStateDir;
    private string? _smokeMusicDir;
    private string? _smokeErrorFile;

    public AppServices? Services { get; private set; }

    public App()
    {
        ParseSmokeArgs(Environment.GetCommandLineArgs(), out _isSmokeTest, out _smokeStateDir, out _smokeMusicDir, out _smokeErrorFile);
        AppDomain.CurrentDomain.UnhandledException += OnDomainUnhandledException;
        DispatcherUnhandledException += OnDispatcherUnhandledException;
        TaskScheduler.UnobservedTaskException += OnUnobservedTaskException;
    }

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        ParseSmokeArgs(e.Args, out bool smokeFromEvent, out string? stateDirArg, out string? musicDirArg, out string? errFileArg);
        bool isSmokeTest = _isSmokeTest || smokeFromEvent;
        if (!string.IsNullOrWhiteSpace(stateDirArg)) _smokeStateDir = stateDirArg;
        if (!string.IsNullOrWhiteSpace(musicDirArg)) _smokeMusicDir = musicDirArg;
        if (!string.IsNullOrWhiteSpace(errFileArg)) _smokeErrorFile = errFileArg;

        if (!isSmokeTest)
        {
            string? startupMessageKey = null;
            try
            {
                // Installed and portable copies share one instance per Windows session.
                _instanceMutex = new Mutex(false, @"Local\MusicoWindows.SingleInstance");
                try
                {
                    _ownsInstanceMutex = _instanceMutex.WaitOne(0);
                }
                catch (AbandonedMutexException)
                {
                    // The previous owner crashed; this thread now owns the mutex.
                    _ownsInstanceMutex = true;
                }

                if (!_ownsInstanceMutex)
                    startupMessageKey = "App_AlreadyRunning";
            }
            catch (Exception ex) when (ex is UnauthorizedAccessException or IOException or WaitHandleCannotBeOpenedException)
            {
                CrashLogger.WriteCrashLog(ex, "App.SingleInstanceMutex");
                startupMessageKey = "App_InstanceCheckFailed";
            }

            if (startupMessageKey is not null)
            {
                _instanceMutex?.Dispose();
                _instanceMutex = null;
                string message = startupMessageKey == "App_AlreadyRunning"
                    ? "Musico is already running."
                    : "Unable to verify whether another Musico instance is already running.";
                try
                {
                    L.SetLanguage(LocalizationManager.ResolveLanguage(LocalizationManager.LoadSavedPreference()));
                    message = L.Get(startupMessageKey);
                }
                catch
                {
                    // Keep English fallback message if localization fails during early startup.
                }

                MessageBox.Show(
                    message,
                    "Musico",
                    MessageBoxButton.OK,
                    startupMessageKey == "App_AlreadyRunning" ? MessageBoxImage.Information : MessageBoxImage.Error);
                Shutdown();
                return;
            }
        }

        try
        {
            if (isSmokeTest)
            {
                string stateDir = string.IsNullOrWhiteSpace(_smokeStateDir)
                    ? Path.Combine(Path.GetTempPath(), $"MusicoSmoke_{Environment.ProcessId}")
                    : _smokeStateDir;
                string musicDir = string.IsNullOrWhiteSpace(_smokeMusicDir)
                    ? Path.Combine(stateDir, "Music")
                    : _smokeMusicDir;
                Directory.CreateDirectory(stateDir);
                Directory.CreateDirectory(musicDir);
                _smokeStateDir = stateDir;
                _smokeMusicDir = musicDir;

                string themePrefPath = Path.Combine(stateDir, "theme.txt");
                string langPrefPath = Path.Combine(stateDir, "language.txt");
                ThemeManager.Initialize(this, themePrefPath);
                LocalizationManager.Initialize(this, langPrefPath);

                Services = new AppServices(stateDirectory: stateDir, musicDirectory: musicDir);
                var window = new MainWindow(Services);
                MainWindow = window;
                window.Show();

                Dispatcher.BeginInvoke(
                    DispatcherPriority.ApplicationIdle,
                    new Action(() => _ = RunStartupSmokeAsync(window, langPrefPath)));
                return;
            }

            ThemeManager.Initialize(this);
            LocalizationManager.Initialize(this);

            Services = new AppServices();
            var mainWindow = new MainWindow(Services);
            MainWindow = mainWindow;
            mainWindow.Show();

            _ = Services.InitializeAsync();
        }
        catch (Exception ex)
        {
            HandleFatalException(ex, "App.OnStartup");
            Shutdown(1);
        }
    }

    private async Task RunStartupSmokeAsync(MainWindow window, string langPrefPath)
    {
        try
        {
            if (Services is null)
            {
                throw new InvalidOperationException("AppServices was not initialized during startup smoke test.");
            }

            await Services.InitializeAsync();
            if (Services.Library.Tracks.Count > 0)
            {
                var trackIds = Services.Library.Tracks.Select(t => t.Id).ToList();
                Services.Queues.UpdateActive(q => q.Replace(trackIds, 0));
            }

            await Dispatcher.Yield(DispatcherPriority.Background);

            window.FullPlayer.Show();
            await Dispatcher.Yield(DispatcherPriority.Background);
            window.FullPlayer.Close();

            window.CloudDialog.Show(Services);
            await Dispatcher.Yield(DispatcherPriority.Background);
            window.CloudDialog.Close();

            if (Services.Library.Roots.FirstOrDefault() is { } firstRoot)
            {
                window.EditCloudDialog.Show(Services, firstRoot);
                await Dispatcher.Yield(DispatcherPriority.Background);
                window.EditCloudDialog.Close();
            }

            ThemeManager.ToggleTheme(this);
            ThemeManager.ToggleTheme(this);
            LocalizationManager.SetMode(this, AppLanguage.SimplifiedChinese, langPrefPath);
            LocalizationManager.SetMode(this, AppLanguage.English, langPrefPath);
            await Dispatcher.Yield(DispatcherPriority.Background);

            foreach (var navButton in new[]
                     {
                         window.NavFolders,
                         window.NavSongs,
                         window.NavAlbums,
                         window.NavArtists,
                         window.NavPlaylists,
                         window.NavStats,
                         window.NavExtensions,
                         window.NavQueues
                     })
            {
                navButton.IsChecked = true;
                await Dispatcher.Yield(DispatcherPriority.Background);
            }

            window.Close();
            Shutdown(0);
        }
        catch (Exception ex)
        {
            HandleFatalException(ex, "App.StartupSmokeTest");
            try { window.Close(); } catch { /* ignore */ }
            Shutdown(1);
        }
    }

    private static void ParseSmokeArgs(
        string[] args,
        out bool isSmokeTest,
        out string? stateDir,
        out string? musicDir,
        out string? errorFile)
    {
        isSmokeTest = false;
        stateDir = null;
        musicDir = null;
        errorFile = null;

        if (args is null) return;
        foreach (string raw in args)
        {
            if (string.IsNullOrWhiteSpace(raw)) continue;
            string arg = raw.Trim();
            if (string.Equals(arg, "--startup-smoke-test", StringComparison.OrdinalIgnoreCase))
            {
                isSmokeTest = true;
            }
            else if (TryExtractArgValue(arg, "--state-dir=", out string? sDir))
            {
                stateDir = sDir;
            }
            else if (TryExtractArgValue(arg, "--music-dir=", out string? mDir))
            {
                musicDir = mDir;
            }
            else if (TryExtractArgValue(arg, "--smoke-error-file=", out string? eFile))
            {
                errorFile = eFile;
            }
        }
    }

    private static bool TryExtractArgValue(string arg, string prefix, out string? value)
    {
        if (arg.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
        {
            value = arg[prefix.Length..].Trim().Trim('"');
            return !string.IsNullOrWhiteSpace(value);
        }
        value = null;
        return false;
    }

    private void OnDispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        HandleFatalException(e.Exception, "Application.DispatcherUnhandledException");
        e.Handled = true;
        Shutdown(1);
    }

    private void OnDomainUnhandledException(object sender, UnhandledExceptionEventArgs e)
    {
        if (e.ExceptionObject is Exception ex)
        {
            HandleFatalException(ex, "AppDomain.UnhandledException");
        }
        else
        {
            CrashLogger.WriteCrashLog(
                new InvalidOperationException($"Non-CLS unhandled exception: {e.ExceptionObject}"),
                "AppDomain.UnhandledException",
                preferredDirectory: _smokeStateDir);
        }
    }

    private static void OnUnobservedTaskException(object? sender, UnobservedTaskExceptionEventArgs e)
    {
        CrashLogger.WriteCrashLog(e.Exception, "TaskScheduler.UnobservedTaskException");
        e.SetObserved();
    }

    private void HandleFatalException(Exception ex, string context)
    {
        string? logPath = CrashLogger.WriteCrashLog(ex, context, preferredDirectory: _smokeStateDir);

        if (_isSmokeTest)
        {
            try
            {
                string report = CrashLogger.FormatReport(ex, context);
                if (!string.IsNullOrWhiteSpace(_smokeErrorFile))
                {
                    string? dir = Path.GetDirectoryName(_smokeErrorFile);
                    if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
                    File.WriteAllText(_smokeErrorFile, report);
                }
                Console.Error.WriteLine(report);
            }
            catch
            {
                // Ignore secondary logging failures.
            }
            Environment.ExitCode = 1;
            return;
        }

        if (Interlocked.Exchange(ref _fatalDialogShown, 1) != 0)
        {
            return;
        }

        try
        {
            Exception innermost = CrashLogger.UnwrapInnermost(ex);
            string summary = ReferenceEquals(innermost, ex)
                ? $"{ex.GetType().Name}: {ex.Message}"
                : $"{ex.GetType().Name}: {ex.Message}\n\nCause ({innermost.GetType().Name}): {innermost.Message}";

            string logNotice = string.IsNullOrWhiteSpace(logPath)
                ? "Could not write crash.log."
                : $"Crash details were saved to:\n{logPath}";

            MessageBox.Show(
                $"Musico encountered a startup or runtime error and must close.\n\n{summary}\n\n{logNotice}",
                "Musico — Startup Error",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
        }
        catch
        {
            // Ignore secondary UI failures when reporting a fatal crash.
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        try
        {
            try
            {
                Services?.Dispose();
            }
            catch (Exception ex)
            {
                CrashLogger.WriteCrashLog(ex, "App.OnExit.Dispose", preferredDirectory: _smokeStateDir);
            }
        }
        finally
        {
            if (_ownsInstanceMutex)
            {
                try { _instanceMutex?.ReleaseMutex(); } catch { /* ignore */ }
            }
            _instanceMutex?.Dispose();
            _instanceMutex = null;
            _ownsInstanceMutex = false;
            base.OnExit(e);
        }
    }
}
