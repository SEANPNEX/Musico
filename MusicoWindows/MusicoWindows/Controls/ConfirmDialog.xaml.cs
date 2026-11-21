using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;

namespace MusicoWindows.Controls;

public partial class ConfirmDialog : UserControl
{
    private Action? _onConfirm;

    public ConfirmDialog()
    {
        InitializeComponent();
    }

    public bool IsOpen => Visibility == Visibility.Visible;

    public void Show(string title, string message, string confirmLabel, Action? onConfirm, bool showCancel = true)
    {
        _onConfirm = onConfirm;
        TitleText.Text = title;
        MessageText.Text = message;
        ConfirmButton.Content = confirmLabel;
        CancelButton.Visibility = showCancel ? Visibility.Visible : Visibility.Collapsed;
        Visibility = Visibility.Visible;
    }

    public void Close()
    {
        Visibility = Visibility.Collapsed;
        _onConfirm = null;
    }

    private void Confirm_Click(object sender, RoutedEventArgs e)
    {
        var cb = _onConfirm;
        Close();
        cb?.Invoke();
    }

    private void Cancel_Click(object sender, RoutedEventArgs e) => Close();

    private void Overlay_MouseDown(object sender, MouseButtonEventArgs e) => Close();

    private void Card_MouseDown(object sender, MouseButtonEventArgs e) => e.Handled = true;
}
