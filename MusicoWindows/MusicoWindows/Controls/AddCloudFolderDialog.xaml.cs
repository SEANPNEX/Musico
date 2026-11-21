using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using MusicoWindows.Cloud;
using MusicoWindows.Stores;
using MusicoWindows.Theme;

namespace MusicoWindows.Controls;

public partial class AddCloudFolderDialog : UserControl
{
    private AppServices? _services;
    private bool _isBusy;

    public AddCloudFolderDialog()
    {
        InitializeComponent();
    }

    public bool IsOpen => Visibility == Visibility.Visible;

    public void Show(AppServices services)
    {
        _services = services;
        _isBusy = false;

        UrlBox.Text = string.Empty;
        CookieBox.Text = string.Empty;
        ProxyBox.Text = string.Empty;
        ErrorBanner.Visibility = Visibility.Collapsed;
        ErrorText.Text = string.Empty;
        SetBusy(false);

        Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(BackdropGrid, DialogCard);
        Dispatcher.BeginInvoke(() => UrlBox.Focus());
    }

    public void Close()
    {
        if (_isBusy) return;
        Visibility = Visibility.Collapsed;
    }

    private void SetBusy(bool busy)
    {
        _isBusy = busy;
        UrlBox.IsEnabled = !busy;
        CookieBox.IsEnabled = !busy;
        ProxyBox.IsEnabled = !busy;
        CancelButton.IsEnabled = !busy;
        CloseIconButton.IsEnabled = !busy;
        BusyPanel.Visibility = busy ? Visibility.Visible : Visibility.Collapsed;
        SubmitButton.IsEnabled = !busy && !string.IsNullOrWhiteSpace(UrlBox.Text);
    }

    private async void Submit_Click(object sender, RoutedEventArgs e) => await SubmitAsync();

    private async System.Threading.Tasks.Task SubmitAsync()
    {
        if (_services is null || _isBusy) return;

        string url = UrlBox.Text.Trim();
        string? cookie = string.IsNullOrWhiteSpace(CookieBox.Text) ? null : CookieBox.Text.Trim();
        string? proxy = string.IsNullOrWhiteSpace(ProxyBox.Text) ? null : ProxyBox.Text.Trim();

        try
        {
            CloudUrlClassifier.Classify(url);
            YouTubeClient.NormalizeProxyUrl(proxy);
        }
        catch (Exception ex)
        {
            ShowError(ex.Message);
            return;
        }

        ErrorBanner.Visibility = Visibility.Collapsed;
        SetBusy(true);

        try
        {
            await _services.Library.AddCloudRootAsync(url, cookie, proxy);
            _isBusy = false;
            Visibility = Visibility.Collapsed;
        }
        catch (Exception ex)
        {
            SetBusy(false);
            ShowError(ex.Message);
        }
    }

    private void ShowError(string message)
    {
        ErrorText.Text = message;
        ErrorBanner.Visibility = Visibility.Visible;
    }

    private void UrlBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (!_isBusy)
        {
            SubmitButton.IsEnabled = !string.IsNullOrWhiteSpace(UrlBox.Text);
        }
    }

    private async void InputBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            e.Handled = true;
            await SubmitAsync();
        }
        else if (e.Key == Key.Escape && !_isBusy)
        {
            e.Handled = true;
            Close();
        }
    }

    private void Cancel_Click(object sender, RoutedEventArgs e) => Close();

    private void Overlay_MouseDown(object sender, MouseButtonEventArgs e) => Close();

    private void Card_MouseDown(object sender, MouseButtonEventArgs e) => e.Handled = true;
}
