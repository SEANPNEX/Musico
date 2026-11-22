using System;
using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using MusicoWindows.Controls;
using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Stores;

namespace MusicoWindows.Views;

public partial class AlbumsView : UserControl
{
    private ViewContext? _ctx;

    public AlbumsView()
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
        if (e.PropertyName is nameof(LibraryStore.Albums) or nameof(LibraryStore.Tracks))
            Refresh();
    }

    public void Refresh()
    {
        if (_ctx is null) return;
        var albums = _ctx.Services.Library.Albums;
        CountText.Text = L.AlbumsCount(albums.Count);

        AlbumsWrap.Children.Clear();
        if (albums.Count == 0)
        {
            GridScroll.Visibility = Visibility.Collapsed;
            EmptyState.Visibility = Visibility.Visible;
            return;
        }

        GridScroll.Visibility = Visibility.Visible;
        EmptyState.Visibility = Visibility.Collapsed;

        foreach (var album in albums)
        {
            AlbumsWrap.Children.Add(CreateAlbumCard(_ctx, album));
        }
    }

    public static Border CreateAlbumCard(ViewContext ctx, AlbumInfo album)
    {
        var card = new Border
        {
            Width = 172,
            Margin = new Thickness(0, 0, 14, 16),
            Padding = new Thickness(8),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(0),
            Cursor = Cursors.Hand
        };
        card.SetResourceReference(Border.BackgroundProperty, "CardBackgroundBrush");
        card.SetResourceReference(Border.BorderBrushProperty, "ControlBorderBrush");

        var stack = new StackPanel();
        var art = new ArtworkImage
        {
            Width = 154,
            Height = 154,
            GlyphSize = 36
        };
        art.SetSource(ctx.Services.Library.Artwork, album.ArtworkKey, 240);

        var titleTb = new TextBlock
        {
            Text = L.DisplayAlbum(album.Title),
            FontFamily = new FontFamily("Segoe UI"),
            FontSize = 13,
            FontWeight = FontWeights.SemiBold,
            TextTrimming = TextTrimming.CharacterEllipsis,
            Margin = new Thickness(0, 8, 0, 0)
        };
        titleTb.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");

        var artistTb = new TextBlock
        {
            Text = L.DisplayArtist(album.Artist),
            FontFamily = new FontFamily("Segoe UI"),
            FontSize = 12,
            TextTrimming = TextTrimming.CharacterEllipsis,
            Margin = new Thickness(0, 2, 0, 0)
        };
        artistTb.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");

        stack.Children.Add(art);
        stack.Children.Add(titleTb);
        stack.Children.Add(artistTb);
        card.Child = stack;

        card.MouseEnter += (_, _) =>
        {
            card.SetResourceReference(Border.BackgroundProperty, "CardHoverBrush");
            card.SetResourceReference(Border.BorderBrushProperty, "RevealBorderBrush");
        };
        card.MouseLeave += (_, _) =>
        {
            card.SetResourceReference(Border.BackgroundProperty, "CardBackgroundBrush");
            card.SetResourceReference(Border.BorderBrushProperty, "ControlBorderBrush");
        };
        card.MouseLeftButtonUp += (_, _) => ctx.OpenAlbum(album);

        card.ContextMenu = TrackMenuHelper.BuildTrackMenu(
            ctx.Services,
            album.TrackIds,
            null,
            ctx.OpenTextPrompt);

        return card;
    }
}
