using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using MusicoWindows.Theme;
using MusicoWindows.Views;

namespace MusicoWindows.Controls;

public partial class ConfirmDialog : UserControl
{
    private Action? _onConfirm;
    private Action? _onRemoveFromLibrary;
    private Action? _onMoveToRecycleBin;

    public ConfirmDialog()
    {
        InitializeComponent();
    }

    public bool IsOpen => Visibility == Visibility.Visible;

    public void Show(string title, string message, string confirmLabel, Action? onConfirm, bool showCancel = true)
    {
        _onConfirm = onConfirm;
        _onRemoveFromLibrary = null;
        _onMoveToRecycleBin = null;

        TitleText.Text = title;
        MessageText.Text = message;
        DetailsPanel.Visibility = Visibility.Collapsed;
        StandardButtonsGrid.Visibility = Visibility.Visible;
        DeleteChoiceButtonsPanel.Visibility = Visibility.Collapsed;

        ConfirmButton.Content = confirmLabel;
        CancelButton.Visibility = showCancel ? Visibility.Visible : Visibility.Collapsed;
        Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(BackdropGrid, DialogCard);
    }

    public void ShowDeleteChoice(DeleteChoiceRequest request)
    {
        ArgumentNullException.ThrowIfNull(request);

        _onConfirm = null;
        _onRemoveFromLibrary = request.OnRemoveFromLibrary;
        _onMoveToRecycleBin = request.OnMoveToRecycleBin;

        TitleText.Text = request.Title;
        MessageText.Text = request.Message;

        DetailsPanel.Visibility = Visibility.Visible;
        ItemNameText.Text = request.ItemName;

        if (!string.IsNullOrWhiteSpace(request.SongCountText))
        {
            ItemCountText.Text = request.SongCountText;
            ItemCountText.Visibility = Visibility.Visible;
        }
        else
        {
            ItemCountText.Visibility = Visibility.Collapsed;
        }

        if (!string.IsNullOrWhiteSpace(request.ExactPath))
        {
            ItemPathText.Text = request.ExactPath;
            ItemPathText.Visibility = Visibility.Visible;
        }
        else
        {
            ItemPathText.Visibility = Visibility.Collapsed;
        }

        StandardButtonsGrid.Visibility = Visibility.Collapsed;
        DeleteChoiceButtonsPanel.Visibility = Visibility.Visible;

        RemoveFromLibraryButton.Content = request.RemoveFromLibraryLabel;
        if (request.OnMoveToRecycleBin is not null && !string.IsNullOrWhiteSpace(request.MoveToRecycleBinLabel))
        {
            MoveToRecycleBinButton.Content = request.MoveToRecycleBinLabel;
            MoveToRecycleBinButton.Visibility = Visibility.Visible;
        }
        else
        {
            MoveToRecycleBinButton.Visibility = Visibility.Collapsed;
        }

        Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(BackdropGrid, DialogCard);
    }

    public void Close()
    {
        Visibility = Visibility.Collapsed;
        _onConfirm = null;
        _onRemoveFromLibrary = null;
        _onMoveToRecycleBin = null;
    }

    private void Confirm_Click(object sender, RoutedEventArgs e)
    {
        var cb = _onConfirm;
        Close();
        cb?.Invoke();
    }

    private void RemoveFromLibrary_Click(object sender, RoutedEventArgs e)
    {
        var cb = _onRemoveFromLibrary;
        Close();
        cb?.Invoke();
    }

    private void MoveToRecycleBin_Click(object sender, RoutedEventArgs e)
    {
        var cb = _onMoveToRecycleBin;
        Close();
        cb?.Invoke();
    }

    private void Cancel_Click(object sender, RoutedEventArgs e) => Close();

    private void Overlay_MouseDown(object sender, MouseButtonEventArgs e) => Close();

    private void Card_MouseDown(object sender, MouseButtonEventArgs e) => e.Handled = true;
}
