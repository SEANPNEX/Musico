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

public partial class PlaylistsView : UserControl
{
    private sealed record PlaylistCardState(string Key, IReadOnlyList<string> TrackIds);

    private ViewContext? _ctx;
    private bool _isSelectionMode;
    private bool _suppressSelectionChanged;

    public PlaylistsView()
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
        if (e.PropertyName is nameof(LibraryStore.Playlists) or nameof(LibraryStore.Favourites) or nameof(LibraryStore.Tracks))
            Refresh();
    }

    public void Refresh()
    {
        if (_ctx is null) return;
        var lib = _ctx.Services.Library;

        var prevSelectedKeys = PlaylistsList.SelectedItems
            .OfType<Border>()
            .Select(b => (b.Tag as PlaylistCardState)?.Key)
            .Where(k => !string.IsNullOrEmpty(k))
            .ToHashSet(StringComparer.Ordinal);

        int total = 1 + lib.Playlists.Count;
        CountText.Text = L.PlaylistsCount(total);

        _suppressSelectionChanged = true;
        try
        {
            PlaylistsList.UnselectAll();
            PlaylistsList.Items.Clear();

            // Favourites built-in playlist
            var favIds = lib.GetFavouriteTracks(_ctx.Services.History).Select(t => t.Id).ToList();
            var favCard = CreatePlaylistCard(
                key: "favourites",
                title: L.Get("Nav_Favourites"),
                count: lib.Favourites.Count,
                glyph: "\uEB52",
                trackIds: favIds,
                onClick: () => _ctx.OpenPlaylist(PlaylistRef.Favourites),
                playlist: null);
            PlaylistsList.Items.Add(favCard);
            if (prevSelectedKeys.Contains("favourites"))
            {
                PlaylistsList.SelectedItems.Add(favCard);
            }

            // User playlists
            foreach (var pl in lib.Playlists)
            {
                var captured = pl;
                string key = pl.Id.ToString("D");
                var plTrackIds = lib.GetPlaylistTracks(pl.Id, _ctx.Services.History).Select(t => t.Id).ToList();
                var card = CreatePlaylistCard(
                    key: key,
                    title: pl.Name,
                    count: pl.TrackIds.Count,
                    glyph: "\uE8D6",
                    trackIds: plTrackIds,
                    onClick: () => _ctx.OpenPlaylist(PlaylistRef.User(captured.Id)),
                    playlist: captured);
                PlaylistsList.Items.Add(card);
                if (prevSelectedKeys.Contains(key))
                {
                    PlaylistsList.SelectedItems.Add(card);
                }
            }
        }
        finally
        {
            _suppressSelectionChanged = false;
        }

        SyncPlaylistCards();
        UpdateSelectionBar();
    }

    private Border CreatePlaylistCard(
        string key,
        string title,
        int count,
        string glyph,
        IReadOnlyList<string> trackIds,
        Action onClick,
        Playlist? playlist)
    {
        var card = new Border
        {
            Margin = new Thickness(0, 0, 0, 8),
            Padding = new Thickness(16, 12, 16, 12),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(4),
            Cursor = Cursors.Hand,
            Tag = new PlaylistCardState(key, trackIds)
        };
        card.SetResourceReference(Border.BackgroundProperty, "CardBackgroundBrush");
        card.SetResourceReference(Border.BorderBrushProperty, "ControlBorderBrush");

        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(36) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var icon = new TextBlock
        {
            Text = glyph,
            FontFamily = (FontFamily)FindResource("Win10GlyphFont"),
            FontSize = 18,
            VerticalAlignment = VerticalAlignment.Center
        };
        icon.SetResourceReference(TextBlock.ForegroundProperty, "AccentBrush");
        Grid.SetColumn(icon, 0);

        var info = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(6, 0, 12, 0) };
        var titleTb = new TextBlock
        {
            Text = title,
            FontFamily = (FontFamily)FindResource("Win10Font"),
            FontSize = 15,
            FontWeight = FontWeights.SemiBold
        };
        titleTb.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");

        var countTb = new TextBlock
        {
            Text = L.SongsCount(count),
            FontFamily = (FontFamily)FindResource("Win10Font"),
            FontSize = 12,
            Margin = new Thickness(0, 2, 0, 0)
        };
        countTb.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");

        info.Children.Add(titleTb);
        info.Children.Add(countTb);
        Grid.SetColumn(info, 1);

        if (playlist is not null)
        {
            var actions = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            var renameBtn = new Button
            {
                Content = "\uE70F",
                ToolTip = L.Get("Playlists_RenameTooltip"),
                Style = (Style)FindResource("Win10IconButtonStyle"),
                Margin = new Thickness(0, 0, 4, 0)
            };
            System.Windows.Automation.AutomationProperties.SetName(renameBtn, L.Get("Playlists_RenameTooltip"));
            renameBtn.Click += (_, e) =>
            {
                e.Handled = true;
                PromptRename(playlist);
            };
            var delBtn = new Button
            {
                Content = "\uE74D",
                ToolTip = L.Get("Playlists_DeleteTooltip"),
                Style = (Style)FindResource("Win10IconButtonStyle")
            };
            System.Windows.Automation.AutomationProperties.SetName(delBtn, L.Get("Playlists_DeleteTooltip"));
            delBtn.Click += (_, e) =>
            {
                e.Handled = true;
                ConfirmDelete(playlist);
            };
            actions.Children.Add(renameBtn);
            actions.Children.Add(delBtn);
            Grid.SetColumn(actions, 2);
            grid.Children.Add(actions);
        }

        grid.Children.Add(icon);
        grid.Children.Add(info);
        card.Child = grid;

        card.MouseEnter += (_, _) =>
        {
            if (PlaylistsList.SelectedItems.Contains(card)) return;
            card.SetResourceReference(Border.BackgroundProperty, "CardHoverBrush");
            card.SetResourceReference(Border.BorderBrushProperty, "RevealBorderBrush");
        };
        card.MouseLeave += (_, _) =>
        {
            ApplyPlaylistCardVisual(card, PlaylistsList.SelectedItems.Contains(card));
        };
        card.MouseLeftButtonDown += (_, e) =>
        {
            if (_isSelectionMode && !MultiSelectUiHelper.IsSelectionModifierHeld())
            {
                e.Handled = true;
            }
        };
        card.MouseLeftButtonUp += (_, e) =>
        {
            if (MultiSelectUiHelper.IsSelectionModifierHeld()) return;
            if (_isSelectionMode)
            {
                MultiSelectUiHelper.ToggleListBoxItemSelection(PlaylistsList, card);
                e.Handled = true;
                return;
            }
            onClick();
        };

        card.ContextMenu = TrackMenuHelper.BuildTrackMenu(
            _ctx!.Services,
            trackIds,
            null,
            _ctx.OpenTextPrompt,
            menu =>
            {
                if (trackIds.Count > 0)
                {
                    menu.Items.Insert(0, TrackMenuHelper.CreateItem(
                        L.Get("Common_Play"),
                        "\uE768",
                        () => _ctx.Services.Player.PlayInNamedQueue(title, trackIds)));
                    menu.Items.Insert(1, new Separator());
                }
                if (playlist is not null)
                {
                    menu.Items.Add(new Separator());
                    menu.Items.Add(TrackMenuHelper.CreateItem(L.Get("Common_RenameEllipsis"), "\uE70F", () => PromptRename(playlist)));
                    menu.Items.Add(TrackMenuHelper.CreateItem(L.Get("Common_Delete"), "\uE74D", () => ConfirmDelete(playlist)));
                }
            },
            openBatchTagEdit: _ctx.OpenBatchTagEdit);

        card.ContextMenuOpening += (_, e) =>
        {
            if (PlaylistsList.SelectedItems.Count <= 1 || !PlaylistsList.SelectedItems.Contains(card))
            {
                return;
            }

            var selectedTrackIds = GetSelectedTrackIds();
            if (selectedTrackIds.Count == 0) return;

            var multiMenu = TrackMenuHelper.BuildTrackMenu(
                _ctx!.Services,
                selectedTrackIds,
                null,
                _ctx.OpenTextPrompt,
                openBatchTagEdit: _ctx.OpenBatchTagEdit);
            e.Handled = true;
            multiMenu.PlacementTarget = card;
            multiMenu.IsOpen = true;
        };

        return card;
    }

    private static void ApplyPlaylistCardVisual(Border card, bool selected)
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

    private List<string> GetSelectedTrackIds()
    {
        var seen = new HashSet<string>(StringComparer.Ordinal);
        var ids = new List<string>();
        foreach (var card in PlaylistsList.SelectedItems.OfType<Border>())
        {
            if (card.Tag is not PlaylistCardState state) continue;
            foreach (var id in state.TrackIds)
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

    private void SyncPlaylistCards()
    {
        var selectedSet = new HashSet<object>(PlaylistsList.SelectedItems.Cast<object>());
        foreach (var card in PlaylistsList.Items.OfType<Border>())
        {
            ApplyPlaylistCardVisual(card, selectedSet.Contains(card));
        }
    }

    private void UpdateSelectionBar()
    {
        int selectedPlaylists = PlaylistsList.SelectedItems.Count;
        bool showBar = PlaylistsList.Items.Count > 0 && (_isSelectionMode || selectedPlaylists > 0);
        SelectionBar.Visibility = showBar ? Visibility.Visible : Visibility.Collapsed;
        SelectionCountText.Text = L.Format("Select_SelectedCount", selectedPlaylists);
        BatchEditTagsButton.IsEnabled = GetSelectedEditableTracks().Count > 0;
        SelectAllButton.IsEnabled = PlaylistsList.Items.Count > 0;
        SelectPlaylistsButton.Style = (Style)FindResource(_isSelectionMode ? "Win10PrimaryButtonStyle" : "Win10ButtonStyle");
    }

    private void PlaylistsList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressSelectionChanged) return;
        SyncPlaylistCards();
        UpdateSelectionBar();
    }

    private void PlaylistsList_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        MultiSelectUiHelper.HandleListKeyboardShortcuts(PlaylistsList, e, () => CancelSelection_Click(sender, e));
    }

    private void SelectPlaylists_Click(object sender, RoutedEventArgs e)
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
        if (PlaylistsList.Items.Count == 0) return;
        _isSelectionMode = true;
        PlaylistsList.SelectAll();
        SyncPlaylistCards();
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
            PlaylistsList.UnselectAll();
        }
        finally
        {
            _suppressSelectionChanged = false;
        }
        SyncPlaylistCards();
        UpdateSelectionBar();
    }

    private void PromptRename(Playlist playlist)
    {
        if (_ctx is null) return;
        Guid pid = playlist.Id;
        _ctx.OpenTextPrompt(
            L.Get("Playlists_RenameTitle"),
            L.Get("Playlists_RenameMessage"),
            playlist.Name,
            L.Get("Common_Rename"),
            name => _ctx.Services.Library.RenamePlaylist(pid, name));
    }

    private void ConfirmDelete(Playlist playlist)
    {
        if (_ctx is null) return;
        Guid pid = playlist.Id;
        _ctx.OpenConfirm(
            L.Format("Playlists_DeleteConfirmTitle", playlist.Name),
            L.Get("Playlists_DeleteConfirmMessage"),
            L.Get("Common_Delete"),
            () => _ctx.Services.Library.DeletePlaylist(pid),
            true);
    }

    private void NewPlaylist_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null) return;
        _ctx.OpenTextPrompt(
            L.Get("Nav_NewPlaylist"),
            L.Get("Playlists_NewMessage"),
            string.Empty,
            L.Get("Common_Create"),
            name => _ctx.Services.Library.CreatePlaylist(name));
    }
}
