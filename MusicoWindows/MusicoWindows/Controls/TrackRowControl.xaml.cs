using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using MusicoWindows.Converters;
using MusicoWindows.Models;
using MusicoWindows.Stores;

namespace MusicoWindows.Controls;

public partial class TrackRowControl : UserControl
{
    private Action? _onClick;
    private bool _isAvailable = true;
    private bool _isCurrent;

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
        Action<Track, string>? openSaveCloudTrack = null)
    {
        _onClick = onClick;
        _isAvailable = isAvailable;
        _isCurrent = isCurrent;

        CoverArt.SetSource(services.Library.Artwork, track.ArtworkKey, 96);
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
            track.Id,
            openTextPrompt,
            extraMenuItems,
            openSaveCloudTrack);
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
        btn.Click += (_, e) =>
        {
            e.Handled = true;
            onClick();
        };
        ActionHost.Children.Add(btn);
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
        if (!_isAvailable) return;
        _onClick?.Invoke();
    }
}
