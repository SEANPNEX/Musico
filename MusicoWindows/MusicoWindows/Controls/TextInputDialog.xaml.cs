using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;

namespace MusicoWindows.Controls;

public partial class TextInputDialog : UserControl
{
    private Action<string>? _onConfirm;
    private bool _allowEmpty;

    public TextInputDialog()
    {
        InitializeComponent();
    }

    public bool IsOpen => Visibility == Visibility.Visible;

    public void Show(
        string title,
        string message,
        string initialText,
        string confirmLabel,
        Action<string> onConfirm,
        bool allowEmpty = false)
    {
        _onConfirm = onConfirm;
        _allowEmpty = allowEmpty;

        TitleText.Text = title;
        MessageText.Text = message;
        MessageText.Visibility = string.IsNullOrWhiteSpace(message) ? Visibility.Collapsed : Visibility.Visible;
        ConfirmButton.Content = confirmLabel;

        InputBox.Text = initialText ?? string.Empty;
        UpdateConfirmEnabled();

        Visibility = Visibility.Visible;
        Dispatcher.BeginInvoke(() =>
        {
            InputBox.Focus();
            InputBox.SelectAll();
        });
    }

    public void Close()
    {
        Visibility = Visibility.Collapsed;
        _onConfirm = null;
    }

    private void UpdateConfirmEnabled()
    {
        ConfirmButton.IsEnabled = _allowEmpty || !string.IsNullOrWhiteSpace(InputBox.Text);
    }

    private void Confirm()
    {
        var trimmed = InputBox.Text.Trim();
        if (!_allowEmpty && string.IsNullOrEmpty(trimmed))
            return;

        var callback = _onConfirm;
        Close();
        callback?.Invoke(trimmed);
    }

    private void Confirm_Click(object sender, RoutedEventArgs e) => Confirm();

    private void Cancel_Click(object sender, RoutedEventArgs e) => Close();

    private void InputBox_TextChanged(object sender, TextChangedEventArgs e) => UpdateConfirmEnabled();

    private void InputBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            Confirm();
            e.Handled = true;
        }
        else if (e.Key == Key.Escape)
        {
            Close();
            e.Handled = true;
        }
    }

    private void Overlay_MouseDown(object sender, MouseButtonEventArgs e) => Close();

    private void Card_MouseDown(object sender, MouseButtonEventArgs e) => e.Handled = true;
}
