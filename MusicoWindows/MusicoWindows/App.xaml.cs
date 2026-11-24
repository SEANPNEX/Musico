using System.Windows;
using MusicoWindows.Localization;
using MusicoWindows.Stores;
using MusicoWindows.Theme;

namespace MusicoWindows;

public partial class App : Application
{
    public AppServices? Services { get; private set; }

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

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
        Services?.Dispose();
        base.OnExit(e);
    }
}
