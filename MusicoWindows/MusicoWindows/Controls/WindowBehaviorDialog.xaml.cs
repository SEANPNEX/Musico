using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using MusicoWindows.Core;
using MusicoWindows.Theme;

namespace MusicoWindows.Controls;

public partial class WindowBehaviorDialog : UserControl
{
    private Func<WindowCloseBehavior, bool>? _onConfirm;
    private Action? _onCancel;
    private IInputElement? _previousFocus;

    public WindowBehaviorDialog() => InitializeComponent();

    public bool IsOpen => Visibility == Visibility.Visible;

    public void Show(WindowCloseBehavior behavior, bool closing, Func<WindowCloseBehavior, bool> onConfirm, Action? onCancel = null)
    {
        if (IsOpen) return;

        _previousFocus = Keyboard.FocusedElement;
        _onConfirm = onConfirm;
        _onCancel = onCancel;
        TitleText.SetResourceReference(TextBlock.TextProperty,
            closing ? "Str_WindowBehavior_CloseTitle" : "Str_WindowBehavior_SettingsTitle");
        MessageText.SetResourceReference(TextBlock.TextProperty,
            closing ? "Str_WindowBehavior_CloseMessage" : "Str_WindowBehavior_SettingsMessage");
        TrayOption.IsChecked = behavior == WindowCloseBehavior.MinimizeToTray;
        ExitOption.IsChecked = behavior == WindowCloseBehavior.Exit;
        Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(BackdropGrid, DialogCard);
        Dispatcher.BeginInvoke(() =>
        {
            if (IsOpen)
                (TrayOption.IsChecked == true ? TrayOption : ExitOption).Focus();
        });
    }

    public void Close()
    {
        var onCancel = _onCancel;
        _onCancel = null;
        Visibility = Visibility.Collapsed;
        _onConfirm = null;
        if (_previousFocus is UIElement { IsVisible: true, IsEnabled: true } element)
            element.Focus();
        _previousFocus = null;
        onCancel?.Invoke();
    }

    private void Confirm_Click(object sender, RoutedEventArgs e)
    {
        var behavior = TrayOption.IsChecked == true ? WindowCloseBehavior.MinimizeToTray : WindowCloseBehavior.Exit;
        if (_onConfirm?.Invoke(behavior) == true)
        {
            _onCancel = null;
            Close();
        }
    }

    private void Cancel_Click(object sender, RoutedEventArgs e) => Close();
    private void Overlay_MouseDown(object sender, MouseButtonEventArgs e) => Close();
    private void Card_MouseDown(object sender, MouseButtonEventArgs e) => e.Handled = true;
}
