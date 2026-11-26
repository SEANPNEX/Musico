using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using MusicoWindows.Converters;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using MusicoWindows.Views;

namespace MusicoWindows.Controls;

public partial class TrackRowControl : UserControl
{
    private const string DragDataFormat = "MusicoWindows.TrackReorderPayload";

    private sealed record DragPayload(string ScopeKey, int SourceIndex);

    private Action? _onClick;
    private bool _isAvailable = true;
    private bool _isCurrent;
    private string? _dragScopeKey;
    private int _dragIndex = -1;
    private int _dragItemCount;
    private Action<int, int>? _onDropReorder;
    private Point _dragStartPoint;
    private bool _dragArmed;

    public TrackRowControl()
    {
        InitializeComponent();
    }

    public void Bind(
        AppServices services,
        Track track,
        string subtitle,
        bool isCurrent,
        bool isPlaying,
        bool isAvailable,
        Action onClick,
        Action<string, string, string, string, Action<string>> openTextPrompt,
        Action<ContextMenu>? extraMenuItems = null,
        Action<Track, string>? openSaveCloudTrack = null,
        Action<Track>? openSongInfo = null,
        Guid? targetQueueId = null,
        Action<DeleteChoiceRequest>? openDeleteChoice = null,
        Action<string, string?, Action?>? showToast = null)
    {
        _onClick = onClick;
        _isAvailable = isAvailable;
        _isCurrent = isCurrent;

        CoverArt.SetSource(services.Library.Artwork, track.ArtworkKey, 96, track.RemoteArtworkUrl, services.Thumbnails);
        TitleText.Text = track.Title;
        SubtitleText.Text = subtitle;
        DurationText.Text = TimeFormatter.Format(track.DurationSeconds);

        Opacity = isAvailable ? 1.0 : 0.42;
        IsHitTestVisible = true;

        ActiveBar.Visibility = isCurrent ? Visibility.Visible : Visibility.Collapsed;
        TitleText.SetResourceReference(TextBlock.ForegroundProperty, isCurrent ? "AccentBrush" : "TextPrimaryBrush");
        TitleText.FontWeight = isCurrent ? FontWeights.SemiBold : FontWeights.Normal;
        RowBorder.SetResourceReference(Border.BackgroundProperty, isCurrent ? "AccentSubtleBrush" : "SubtleFillBrush");

        if (isCurrent)
        {
            StateGlyph.Text = isPlaying ? "\uE768" : "\uE769";
            StateGlyph.Visibility = Visibility.Visible;
        }
        else
        {
            StateGlyph.Visibility = Visibility.Collapsed;
        }

        ContextMenu = TrackMenuHelper.BuildTrackMenu(
            services,
            [track.Id],
            track.IsEphemeral ? null : track.Id,
            openTextPrompt,
            extraMenuItems,
            openSaveCloudTrack,
            openSongInfo,
            explicitTrack: track,
            targetQueueId: targetQueueId,
            openDeleteChoice: openDeleteChoice,
            showToast: showToast);
    }

    public void EnableDragReorder(string scopeKey, int index, int itemCount, Action<int, int> onDropReorder)
    {
        _dragScopeKey = scopeKey;
        _dragIndex = index;
        _dragItemCount = itemCount;
        _onDropReorder = onDropReorder;
        DragHandle.Visibility = Visibility.Visible;
        RowBorder.AllowDrop = true;
    }

    public void AddRowActionButton(string mdl2Glyph, string tooltip, Action onClick, bool isEnabled = true)
    {
        ActionHost.Visibility = Visibility.Visible;
        var btn = new Button
        {
            Content = mdl2Glyph,
            ToolTip = tooltip,
            IsEnabled = isEnabled,
            Width = 28,
            Height = 28,
            FontSize = 12,
            Margin = new Thickness(2, 0, 0, 0),
            Style = (Style)FindResource("Win10IconButtonStyle")
        };
        System.Windows.Automation.AutomationProperties.SetName(btn, tooltip);
        btn.Click += (_, e) =>
        {
            e.Handled = true;
            onClick();
        };
        ActionHost.Children.Add(btn);
    }

    private void DragHandle_PreviewMouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (_onDropReorder is null || _dragIndex < 0) return;
        _dragStartPoint = e.GetPosition(null);
        _dragArmed = true;
        e.Handled = true;
    }

    private void DragHandle_PreviewMouseMove(object sender, MouseEventArgs e)
    {
        if (!_dragArmed || e.LeftButton != MouseButtonState.Pressed || _onDropReorder is null || string.IsNullOrEmpty(_dragScopeKey))
        {
            if (e.LeftButton != MouseButtonState.Pressed) _dragArmed = false;
            return;
        }

        Point pos = e.GetPosition(null);
        if (Math.Abs(pos.X - _dragStartPoint.X) < SystemParameters.MinimumHorizontalDragDistance &&
            Math.Abs(pos.Y - _dragStartPoint.Y) < SystemParameters.MinimumVerticalDragDistance)
        {
            return;
        }

        _dragArmed = false;
        var data = new DataObject(DragDataFormat, new DragPayload(_dragScopeKey, _dragIndex));
        DragDrop.DoDragDrop(this, data, DragDropEffects.Move);
    }

    private void Row_DragOver(object sender, DragEventArgs e)
    {
        if (!TryGetMatchingPayload(e, out _))
        {
            e.Effects = DragDropEffects.None;
            HideDropIndicators();
            return;
        }

        e.Effects = DragDropEffects.Move;
        e.Handled = true;

        bool insertBefore = e.GetPosition(RowBorder).Y < RowBorder.ActualHeight / 2.0;
        DropTopIndicator.Visibility = insertBefore ? Visibility.Visible : Visibility.Collapsed;
        DropBottomIndicator.Visibility = insertBefore ? Visibility.Collapsed : Visibility.Visible;
    }

    private void Row_DragLeave(object sender, DragEventArgs e)
    {
        HideDropIndicators();
    }

    private void Row_Drop(object sender, DragEventArgs e)
    {
        HideDropIndicators();
        if (!TryGetMatchingPayload(e, out var payload) || _onDropReorder is null) return;

        bool insertBefore = e.GetPosition(RowBorder).Y < RowBorder.ActualHeight / 2.0;
        int fromIndex = payload.SourceIndex;
        int targetIndex = insertBefore
            ? (fromIndex < _dragIndex ? _dragIndex - 1 : _dragIndex)
            : (fromIndex < _dragIndex ? _dragIndex : _dragIndex + 1);

        if (_dragItemCount > 0)
        {
            targetIndex = Math.Clamp(targetIndex, 0, _dragItemCount - 1);
        }

        e.Handled = true;
        if (fromIndex != targetIndex && targetIndex >= 0)
        {
            _onDropReorder(fromIndex, targetIndex);
        }
    }

    private bool TryGetMatchingPayload(DragEventArgs e, out DragPayload payload)
    {
        if (_onDropReorder is not null &&
            !string.IsNullOrEmpty(_dragScopeKey) &&
            e.Data.GetDataPresent(DragDataFormat) &&
            e.Data.GetData(DragDataFormat) is DragPayload p &&
            string.Equals(p.ScopeKey, _dragScopeKey, StringComparison.Ordinal))
        {
            payload = p;
            return true;
        }

        payload = null!;
        return false;
    }

    private void HideDropIndicators()
    {
        DropTopIndicator.Visibility = Visibility.Collapsed;
        DropBottomIndicator.Visibility = Visibility.Collapsed;
    }

    private void Row_MouseEnter(object sender, MouseEventArgs e)
    {
        if (!_isCurrent)
        {
            RowBorder.SetResourceReference(Border.BackgroundProperty, "SubtleHoverBrush");
        }
        RowBorder.SetResourceReference(Border.BorderBrushProperty, "RevealBorderBrush");
    }

    private void Row_MouseLeave(object sender, MouseEventArgs e)
    {
        RowBorder.SetResourceReference(Border.BackgroundProperty, _isCurrent ? "AccentSubtleBrush" : "SubtleFillBrush");
        RowBorder.BorderBrush = Brushes.Transparent;
    }

    private void Row_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
    {
        _dragArmed = false;
        if (!_isAvailable) return;
        _onClick?.Invoke();
    }
}
