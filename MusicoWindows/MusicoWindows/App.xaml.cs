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

    public AppServices? Services { get; private set; }

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        string? startupMessage = null;
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

        _ = Services.InitializeAsync();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        try
        {
            Services?.Dispose();
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
