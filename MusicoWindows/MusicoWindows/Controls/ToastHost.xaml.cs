using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using MusicoWindows.Theme;

namespace MusicoWindows.Controls;

public partial class ToastHost : UserControl
{
    private readonly DispatcherTimer _timer;
    private Action? _actionCallback;

    public ToastHost()
    {
        InitializeComponent();
        _timer = new DispatcherTimer
        {
            Interval = TimeSpan.FromSeconds(3.5)
        };
        _timer.Tick += (_, _) => HideToast();
    }

    public void ShowToast(string message, string? actionLabel = null, Action? onAction = null)
    {
        if (string.IsNullOrWhiteSpace(message)) return;

        _timer.Stop();
        _actionCallback = onAction;

        MessageText.Text = message;
        if (!string.IsNullOrWhiteSpace(actionLabel) && onAction is not null)
        {
            ActionButton.Content = actionLabel;
            ActionButton.Visibility = Visibility.Visible;
        }
        else
        {
            ActionButton.Visibility = Visibility.Collapsed;
        }

        Visibility = Visibility.Visible;
        Win10Animations.AnimateFadeIn(ToastCard, 0.25, 160);
        _timer.Start();
    }

    public void HideToast()
    {
        _timer.Stop();
        _actionCallback = null;
        Visibility = Visibility.Collapsed;
    }

    private void Action_Click(object sender, RoutedEventArgs e)
    {
        var cb = _actionCallback;
        HideToast();
        cb?.Invoke();
    }

    private void Dismiss_Click(object sender, RoutedEventArgs e) => HideToast();
}
