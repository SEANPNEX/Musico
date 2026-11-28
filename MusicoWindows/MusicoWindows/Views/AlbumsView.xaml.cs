using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
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
    private bool _isSelectionMode;
    private bool _suppressSelectionChanged;

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
        SelectAlbumsButton.Visibility = albums.Count > 0 ? Visibility.Visible : Visibility.Collapsed;

        var prevSelectedIds = AlbumsList.SelectedItems
            .OfType<Border>()
            .Select(b => (b.Tag as AlbumInfo)?.Id)
            .Where(id => !string.IsNullOrEmpty(id))
            .ToHashSet(StringComparer.Ordinal);

        _suppressSelectionChanged = true;
        try
        {
            AlbumsList.UnselectAll();
            AlbumsList.Items.Clear();

            if (albums.Count == 0)
            {
                _isSelectionMode = false;
                GridScroll.Visibility = Visibility.Collapsed;
                EmptyState.Visibility = Visibility.Visible;
                UpdateSelectionBar();
                return;
            }

            GridScroll.Visibility = Visibility.Visible;
            EmptyState.Visibility = Visibility.Collapsed;

            foreach (var album in albums)
            {
                var card = CreateAlbumCard(
                    _ctx,
                    album,
                    isSelected: b => AlbumsList.SelectedItems.Contains(b),
                    isSelectionMode: () => _isSelectionMode,
                    onSelectionToggle: b => MultiSelectUiHelper.ToggleListBoxItemSelection(AlbumsList, b),
                    multiSelectMenuFactory: b => BuildMultiSelectContextMenu(b));
                AlbumsList.Items.Add(card);
                if (prevSelectedIds.Contains(album.Id))
                {
                    AlbumsList.SelectedItems.Add(card);
                }
            }
        }
        finally
        {
            _suppressSelectionChanged = false;
        }

        SyncAlbumCards();
        UpdateSelectionBar();
    }

    public static Border CreateAlbumCard(ViewContext ctx, AlbumInfo album) =>
        CreateAlbumCard(ctx, album, null, null, null, null);

    private static Border CreateAlbumCard(
        ViewContext ctx,
        AlbumInfo album,
        Func<Border, bool>? isSelected,
        Func<bool>? isSelectionMode,
        Action<Border>? onSelectionToggle,
        Func<Border, ContextMenu?>? multiSelectMenuFactory)
    {
        var card = new Border
        {
            Width = 172,
            Margin = new Thickness(0, 0, 14, 16),
            Padding = new Thickness(8),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(4),
            Cursor = Cursors.Hand,
            Tag = album
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
            if (isSelected?.Invoke(card) == true) return;
            card.SetResourceReference(Border.BackgroundProperty, "CardHoverBrush");
            card.SetResourceReference(Border.BorderBrushProperty, "RevealBorderBrush");
        };
        card.MouseLeave += (_, _) =>
        {
            ApplyAlbumCardVisual(card, isSelected?.Invoke(card) == true);
        };
        card.MouseLeftButtonDown += (_, e) =>
        {
            if (isSelectionMode?.Invoke() == true && !MultiSelectUiHelper.IsSelectionModifierHeld())
            {
                e.Handled = true;
            }
        };
        card.MouseLeftButtonUp += (_, e) =>
        {
            if (MultiSelectUiHelper.IsSelectionModifierHeld()) return;
            if (isSelectionMode?.Invoke() == true)
            {
                onSelectionToggle?.Invoke(card);
                e.Handled = true;
                return;
            }
            ctx.OpenAlbum(album);
        };

        card.ContextMenu = TrackMenuHelper.BuildTrackMenu(
            ctx.Services,
            album.TrackIds,
            null,
            ctx.OpenTextPrompt,
            openBatchTagEdit: ctx.OpenBatchTagEdit);

        if (multiSelectMenuFactory is not null)
        {
            card.ContextMenuOpening += (_, e) =>
            {
                if (multiSelectMenuFactory(card) is { } multiMenu)
                {
                    e.Handled = true;
                    multiMenu.PlacementTarget = card;
                    multiMenu.IsOpen = true;
                }
            };
        }

        return card;
    }

    private static void ApplyAlbumCardVisual(Border card, bool selected)
    {
        if (selected)
        {
            card.SetResourceReference(Border.BackgroundProperty, "AccentSubtleBrush");
            card.SetResourceReference(Border.BorderBrushProperty, "AccentBrush");
        }
        else
        {
            card.SetResourceReference(Border.BackgroundProperty, "CardBackgroundBrush");
            card.SetResourceReference(Border.BorderBrushProperty, "ControlBorderBrush");
        }
    }

    private ContextMenu? BuildMultiSelectContextMenu(Border targetCard)
    {
        if (_ctx is null) return null;
        if (AlbumsList.SelectedItems.Count <= 1 || !AlbumsList.SelectedItems.Contains(targetCard))
        {
            return null;
        }

        var trackIds = GetSelectedTrackIds();
        if (trackIds.Count == 0) return null;

        return TrackMenuHelper.BuildTrackMenu(
            _ctx.Services,
            trackIds,
            null,
            _ctx.OpenTextPrompt,
            openBatchTagEdit: _ctx.OpenBatchTagEdit);
    }

    private List<string> GetSelectedTrackIds()
    {
        var seen = new HashSet<string>(StringComparer.Ordinal);
        var ids = new List<string>();
        foreach (var card in AlbumsList.SelectedItems.OfType<Border>())
        {
            if (card.Tag is not AlbumInfo album) continue;
            foreach (var id in album.TrackIds)
            {
                if (seen.Add(id)) ids.Add(id);
            }
        }
        return ids;
    }

    private List<Track> GetSelectedEditableTracks()
    {
        if (_ctx is null) return [];
        return _ctx.Services.Library.GetTracks(GetSelectedTrackIds())
            .Where(t => !t.IsEphemeral && !Track.HasEphemeralIdPrefix(t.Id))
            .ToList();
    }

    private void SyncAlbumCards()
    {
        var selectedSet = new HashSet<object>(AlbumsList.SelectedItems.Cast<object>());
        foreach (var card in AlbumsList.Items.OfType<Border>())
        {
            ApplyAlbumCardVisual(card, selectedSet.Contains(card));
        }
    }

    private void UpdateSelectionBar()
    {
        int selectedAlbums = AlbumsList.SelectedItems.Count;
        bool showBar = AlbumsList.Items.Count > 0 && (_isSelectionMode || selectedAlbums > 0);
        SelectionBar.Visibility = showBar ? Visibility.Visible : Visibility.Collapsed;
        SelectionCountText.Text = L.Format("Select_SelectedCount", selectedAlbums);
        BatchEditTagsButton.IsEnabled = GetSelectedEditableTracks().Count > 0;
        SelectAllButton.IsEnabled = AlbumsList.Items.Count > 0;
        SelectAlbumsButton.Style = (Style)FindResource(_isSelectionMode ? "Win10PrimaryButtonStyle" : "Win10ButtonStyle");
    }

    private void AlbumsList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressSelectionChanged) return;
        SyncAlbumCards();
        UpdateSelectionBar();
    }

    private void AlbumsList_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        MultiSelectUiHelper.HandleListKeyboardShortcuts(AlbumsList, e, () => CancelSelection_Click(sender, e));
    }

    private void SelectAlbums_Click(object sender, RoutedEventArgs e)
    {
        _isSelectionMode = !_isSelectionMode;
        if (!_isSelectionMode)
        {
            CancelSelection_Click(sender, e);
        }
        else
        {
            UpdateSelectionBar();
        }
    }

    private void SelectAll_Click(object sender, RoutedEventArgs e)
    {
        if (AlbumsList.Items.Count == 0) return;
        _isSelectionMode = true;
        AlbumsList.SelectAll();
        SyncAlbumCards();
        UpdateSelectionBar();
    }

    private void BatchEditTags_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null) return;
        var tracks = GetSelectedEditableTracks();
        if (tracks.Count == 0) return;
        MultiSelectUiHelper.OpenBatchTagEdit(_ctx, tracks);
    }

    private void CancelSelection_Click(object sender, RoutedEventArgs e)
    {
        _isSelectionMode = false;
        _suppressSelectionChanged = true;
        try
        {
            AlbumsList.UnselectAll();
        }
        finally
        {
            _suppressSelectionChanged = false;
        }
        SyncAlbumCards();
        UpdateSelectionBar();
    }
}
