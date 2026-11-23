using System;
using System.ComponentModel;
using System.Windows.Threading;
using MusicoWindows.Localization;
using MusicoWindows.Stores;
using DrawingIcon = System.Drawing.Icon;
using Forms = System.Windows.Forms;
using WpfApplication = System.Windows.Application;

namespace MusicoWindows;

internal sealed class TrayIconService : IDisposable
{
    private readonly PlayerController _player;
    private readonly Action _restoreWindow;
    private readonly Dispatcher _dispatcher;
    private readonly DrawingIcon _icon;
    private readonly Forms.ContextMenuStrip _menu;
    private readonly Forms.NotifyIcon _notifyIcon;
    private readonly Forms.ToolStripItem _playPause;
    private readonly Forms.ToolStripItem _previous;
    private readonly Forms.ToolStripItem _next;
    private readonly Forms.ToolStripItem _restore;
    private readonly Forms.ToolStripItem _exit;
    private bool _disposed;

    public TrayIconService(PlayerController player, Action restoreWindow, Action exitApplication)
    {
        ArgumentNullException.ThrowIfNull(player);
        ArgumentNullException.ThrowIfNull(restoreWindow);
        ArgumentNullException.ThrowIfNull(exitApplication);

        _player = player;
        _restoreWindow = restoreWindow;
        _dispatcher = WpfApplication.Current?.Dispatcher
            ?? throw new InvalidOperationException("A WPF application is required for the tray icon.");
        // WinForms resources must share the WPF UI thread and its message pump.
        _dispatcher.VerifyAccess();
        if (_dispatcher.HasShutdownStarted || _dispatcher.HasShutdownFinished)
            throw new InvalidOperationException("Cannot create the tray icon during shutdown.");

        try
        {
            var resource = WpfApplication.GetResourceStream(
                new Uri("pack://application:,,,/MusicoWindows;component/Assets/AppIcon.ico", UriKind.Absolute))
                ?? throw new InvalidOperationException("The embedded application icon is missing.");
            using (var stream = resource.Stream)
            using (var sourceIcon = new DrawingIcon(stream))
                _icon = (DrawingIcon)sourceIcon.Clone();

            _menu = new Forms.ContextMenuStrip();
            _menu.RenderMode = Forms.ToolStripRenderMode.System;
            _playPause = _menu.Items.Add(string.Empty, null,
                (_, _) => QueueCallback(_player.TogglePlayPause, requiresTrack: true));
            _previous = _menu.Items.Add(string.Empty, null,
                (_, _) => QueueCallback(_player.Previous, requiresTrack: true));
            _next = _menu.Items.Add(string.Empty, null,
                (_, _) => QueueCallback(_player.Next, requiresTrack: true));
            _menu.Items.Add(new Forms.ToolStripSeparator());
            _restore = _menu.Items.Add(string.Empty, null,
                (_, _) => QueueCallback(_restoreWindow));
            _exit = _menu.Items.Add(string.Empty, null,
                (_, _) => QueueCallback(exitApplication));
            _menu.Opening += OnMenuOpening;

            _notifyIcon = new Forms.NotifyIcon();
            _notifyIcon.Icon = _icon;
            _notifyIcon.Text = "Musico";
            _notifyIcon.ContextMenuStrip = _menu;
            _notifyIcon.MouseDoubleClick += OnMouseDoubleClick;
            _notifyIcon.Visible = true;
        }
        catch
        {
            Dispose();
            throw;
        }
    }

    private bool CanInvoke => !_disposed
        && !_dispatcher.HasShutdownStarted
        && !_dispatcher.HasShutdownFinished;

    private void OnMenuOpening(object? sender, CancelEventArgs e)
    {
        // Opening is synchronous and runs on the thread that owns the menu.
        _dispatcher.VerifyAccess();
        if (!CanInvoke)
        {
            e.Cancel = true;
            return;
        }

        _playPause.Text = L.Get(_player.IsLoadingCloud
            ? "Tray_PlayPause"
            : _player.IsPlaying ? "Tray_Pause" : "Common_Play");
        _previous.Text = L.Get("Tray_Previous");
        _next.Text = L.Get("Tray_Next");
        _restore.Text = L.Get("Tray_Restore");
        _exit.Text = L.Get("Tray_Exit");

        bool hasTrack = _player.CurrentTrack is not null;
        _playPause.Enabled = hasTrack;
        _previous.Enabled = hasTrack;
        _next.Enabled = hasTrack;
    }

    private void OnMouseDoubleClick(object? sender, Forms.MouseEventArgs e)
    {
        if (e.Button == Forms.MouseButtons.Left)
            QueueCallback(_restoreWindow);
    }

    private void QueueCallback(Action callback, bool requiresTrack = false)
    {
        if (!CanInvoke)
            return;

        try
        {
            // Let WinForms finish closing its menu before Restore or Exit can dispose it.
            _dispatcher.BeginInvoke(DispatcherPriority.Normal, new Action(() =>
            {
                if (CanInvoke && (!requiresTrack || _player.CurrentTrack is not null))
                    callback();
            }));
        }
        catch (InvalidOperationException) when (_dispatcher.HasShutdownStarted || _dispatcher.HasShutdownFinished)
        {
            // Shutdown can race with posting the operation.
        }
    }

    public void Dispose()
    {
        _dispatcher.VerifyAccess();
        if (_disposed)
            return;

        _disposed = true;
        try
        {
            if (_notifyIcon is not null)
            {
                _notifyIcon.MouseDoubleClick -= OnMouseDoubleClick;
                try
                {
                    _notifyIcon.Visible = false;
                }
                finally
                {
                    _notifyIcon.Dispose();
                }
            }
        }
        finally
        {
            try
            {
                if (_menu is not null)
                {
                    _menu.Opening -= OnMenuOpening;
                    _menu.Dispose();
                }
            }
            finally
            {
                _icon?.Dispose();
            }
        }
    }
}
