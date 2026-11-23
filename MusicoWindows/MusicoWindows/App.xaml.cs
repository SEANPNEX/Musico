using System.Threading;
using System.Windows;
using MusicoWindows.Localization;
using MusicoWindows.Stores;
using MusicoWindows.Theme;

namespace MusicoWindows;

public partial class App : Application
{
    private Mutex? _instanceMutex;
    private bool _ownsInstanceMutex;
    private TrayIconService? _trayIcon;

    public AppServices? Services { get; private set; }
    internal bool IsExiting { get; private set; }

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        string? startupMessage = null;
        try
        {
            // Share one instance across installed and portable copies in this Windows session.
            _instanceMutex = new Mutex(false, @"Local\MusicoWindows.SingleInstance");
            try
            {
                _ownsInstanceMutex = _instanceMutex.WaitOne(0);
            }
            catch (AbandonedMutexException)
            {
                // A crashed owner releases ownership to this thread.
                _ownsInstanceMutex = true;
            }

            if (!_ownsInstanceMutex)
                startupMessage = "App_AlreadyRunning";
        }
        catch (Exception ex) when (ex is UnauthorizedAccessException or System.IO.IOException or WaitHandleCannotBeOpenedException)
        {
            startupMessage = "App_InstanceCheckFailed";
        }

        if (startupMessage is not null)
        {
            _instanceMutex?.Dispose();
            _instanceMutex = null;
            L.SetLanguage(LocalizationManager.ResolveLanguage(LocalizationManager.LoadSavedPreference()));
            MessageBox.Show(L.Get(startupMessage), "Musico", MessageBoxButton.OK,
                startupMessage == "App_AlreadyRunning" ? MessageBoxImage.Information : MessageBoxImage.Error);
            Shutdown();
            return;
        }

        ThemeManager.Initialize(this);
        LocalizationManager.Initialize(this);

        Services = new AppServices();
        var window = new MainWindow(Services);
        MainWindow = window;
        window.Show();
        EnsureTrayIcon();

        _ = Services.InitializeAsync();
    }

    internal bool EnsureTrayIcon()
    {
        if (IsExiting || Services is null || MainWindow is not MainWindow window) return false;
        if (_trayIcon is not null) return true;
        try
        {
            _trayIcon = new TrayIconService(Services.Player, window.RestoreFromTray, RequestExit);
            return true;
        }
        catch (Exception ex)
        {
            // Keep the main window accessible if the optional tray integration fails.
            System.Diagnostics.Trace.TraceError("Could not create Musico tray icon: {0}", ex);
            return false;
        }
    }

    internal void RequestExit()
    {
        if (!Dispatcher.CheckAccess())
        {
            Dispatcher.BeginInvoke(RequestExit);
            return;
        }
        if (IsExiting) return;
        IsExiting = true;
        Shutdown();
    }

    protected override void OnSessionEnding(SessionEndingCancelEventArgs e)
    {
        bool wasExiting = IsExiting;
        IsExiting = true;
        base.OnSessionEnding(e);
        if (e.Cancel) IsExiting = wasExiting;
    }

    protected override void OnExit(ExitEventArgs e)
    {
        IsExiting = true;
        try
        {
            try
            {
                _trayIcon?.Dispose();
                _trayIcon = null;
            }
            finally
            {
                Services?.Dispose();
            }
        }
        finally
        {
            if (_ownsInstanceMutex)
                _instanceMutex?.ReleaseMutex();
            _instanceMutex?.Dispose();
            _instanceMutex = null;
            _ownsInstanceMutex = false;
            base.OnExit(e);
        }
    }
}
