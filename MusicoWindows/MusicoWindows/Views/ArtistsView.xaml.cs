using System;
using System.ComponentModel;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using MusicoWindows.Controls;
using MusicoWindows.Localization;
using MusicoWindows.Stores;

namespace MusicoWindows.Views;

public partial class ArtistsView : UserControl
{
    private ViewContext? _ctx;

    public ArtistsView()
    {
        InitializeComponent();
        Unloaded += OnUnloaded;
    }

    public void Bind(ViewContext ctx)
    {
        Detach();
        _ctx = ctx;
        _ctx.Services.Library.PropertyChanged += OnLibraryChanged;
        LocalizationManager.LanguageChanged += OnLanguageChanged;
        Refresh();
    }

    private void OnUnloaded(object sender, RoutedEventArgs e) => Detach();

    private void Detach()
    {
        LocalizationManager.LanguageChanged -= OnLanguageChanged;
        if (_ctx is null) return;
        _ctx.Services.Library.PropertyChanged -= OnLibraryChanged;
    }

    private void OnLanguageChanged(ResolvedLanguage _) => Refresh();

    private void OnLibraryChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(LibraryStore.Artists) or nameof(LibraryStore.Tracks))
            Refresh();
    }

    public void Refresh()
    {
        if (_ctx is null) return;
        var lib = _ctx.Services.Library;
        var artists = lib.Artists;
        CountText.Text = L.ArtistsCount(artists.Count);

        ArtistsStack.Children.Clear();
        if (artists.Count == 0)
        {
            ArtistsScroll.Visibility = Visibility.Collapsed;
            EmptyState.Visibility = Visibility.Visible;
            return;
        }

        ArtistsScroll.Visibility = Visibility.Visible;
        EmptyState.Visibility = Visibility.Collapsed;

        foreach (string artist in artists)
        {
            var tracks = lib.GetTracksByArtist(artist);
            var albums = lib.GetAlbumsByArtist(artist);
            string capturedArtist = artist;

            var row = new Border
            {
                Background = Brushes.Transparent,
                BorderBrush = Brushes.Transparent,
                BorderThickness = new Thickness(1),
                Padding = new Thickness(12, 10, 12, 10),
                Margin = new Thickness(0, 0, 0, 2),
                Cursor = Cursors.Hand
            };

            var grid = new Grid();
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(36) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var icon = new TextBlock
            {
                Text = "\uE720",
                FontFamily = (FontFamily)FindResource("Win10GlyphFont"),
                FontSize = 16,
                VerticalAlignment = VerticalAlignment.Center
            };
            icon.SetResourceReference(TextBlock.ForegroundProperty, "AccentBrush");
            Grid.SetColumn(icon, 0);

            var nameTb = new TextBlock
            {
                Text = L.DisplayArtist(artist),
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 14,
                FontWeight = FontWeights.SemiBold,
                VerticalAlignment = VerticalAlignment.Center
            };
            nameTb.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");
            Grid.SetColumn(nameTb, 1);

            var metaTb = new TextBlock
            {
                Text = $"{L.AlbumsCount(albums.Count)} · {L.SongsCount(tracks.Count)}",
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 12,
                VerticalAlignment = VerticalAlignment.Center
            };
            metaTb.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
            Grid.SetColumn(metaTb, 2);

            grid.Children.Add(icon);
            grid.Children.Add(nameTb);
            grid.Children.Add(metaTb);
            row.Child = grid;

            row.MouseEnter += (_, _) =>
            {
                row.SetResourceReference(Border.BackgroundProperty, "SubtleHoverBrush");
                row.SetResourceReference(Border.BorderBrushProperty, "RevealBorderBrush");
            };
            row.MouseLeave += (_, _) =>
            {
                row.Background = Brushes.Transparent;
                row.BorderBrush = Brushes.Transparent;
            };
            row.MouseLeftButtonUp += (_, _) => _ctx.OpenArtist(capturedArtist);

            row.ContextMenu = TrackMenuHelper.BuildTrackMenu(
                _ctx.Services,
                tracks.Select(t => t.Id).ToList(),
                null,
                _ctx.OpenTextPrompt);

            ArtistsStack.Children.Add(row);
        }
    }
}
