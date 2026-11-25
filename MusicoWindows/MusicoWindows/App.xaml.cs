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

    public AppServices? Services { get; private set; }

    public App()
    {
        AppDomain.CurrentDomain.UnhandledException += OnDomainUnhandledException;
        DispatcherUnhandledException += OnDispatcherUnhandledException;
        TaskScheduler.UnobservedTaskException += OnUnobservedTaskException;
    }

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

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
        catch (Exception ex) when (ex is UnauthorizedAccessException or System.IO.IOException or WaitHandleCannotBeOpenedException)
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

        try
        {
            ThemeManager.Initialize(this);
            LocalizationManager.Initialize(this);

            Services = new AppServices();
            var window = new MainWindow(Services);
            MainWindow = window;
            window.Show();

            _ = Services.InitializeAsync();
        }
        catch (Exception ex)
        {
            HandleFatalException(ex, "App.OnStartup");
            Shutdown(1);
        }
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
                "AppDomain.UnhandledException");
        }
    }

    private static void OnUnobservedTaskException(object? sender, UnobservedTaskExceptionEventArgs e)
    {
        CrashLogger.WriteCrashLog(e.Exception, "TaskScheduler.UnobservedTaskException");
        e.SetObserved();
    }

    private void HandleFatalException(Exception ex, string context)
    {
        string? logPath = CrashLogger.WriteCrashLog(ex, context);

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
                CrashLogger.WriteCrashLog(ex, "App.OnExit.Dispose");
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
