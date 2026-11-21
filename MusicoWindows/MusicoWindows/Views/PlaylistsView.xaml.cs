using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using MusicoWindows.Controls;
using MusicoWindows.Models;
using MusicoWindows.Stores;

namespace MusicoWindows.Views;

public partial class PlaylistsView : UserControl
{
    private ViewContext? _ctx;

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
        Refresh();
    }

    private void OnUnloaded(object sender, RoutedEventArgs e) => Detach();

    private void Detach()
    {
        if (_ctx is null) return;
        _ctx.Services.Library.PropertyChanged -= OnLibraryChanged;
    }

    private void OnLibraryChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(LibraryStore.Playlists) or nameof(LibraryStore.Favourites))
            Refresh();
    }

    public void Refresh()
    {
        if (_ctx is null) return;
        var lib = _ctx.Services.Library;

        int total = 1 + lib.Playlists.Count;
        CountText.Text = $"{total} {(total == 1 ? "playlist" : "playlists")}";

        PlaylistsStack.Children.Clear();

        // Favourites built-in playlist
        var favIds = lib.Tracks.Where(t => lib.Favourites.Contains(t.Id)).Select(t => t.Id).ToList();
        PlaylistsStack.Children.Add(CreatePlaylistCard(
            title: "Favourites",
            count: lib.Favourites.Count,
            glyph: "\uEB52",
            trackIds: favIds,
            onClick: () => _ctx.OpenPlaylist(PlaylistRef.Favourites),
            playlist: null));

        // User playlists
        foreach (var pl in lib.Playlists)
        {
            var captured = pl;
            PlaylistsStack.Children.Add(CreatePlaylistCard(
                title: pl.Name,
                count: pl.TrackIds.Count,
                glyph: "\uE8D6",
                trackIds: pl.TrackIds,
                onClick: () => _ctx.OpenPlaylist(PlaylistRef.User(captured.Id)),
                playlist: captured));
        }
    }

    private Border CreatePlaylistCard(
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
            CornerRadius = new CornerRadius(0),
            Cursor = Cursors.Hand
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
            Text = $"{count} {(count == 1 ? "song" : "songs")}",
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
                ToolTip = "Rename Playlist",
                Style = (Style)FindResource("Win10IconButtonStyle"),
                Margin = new Thickness(0, 0, 4, 0)
            };
            renameBtn.Click += (_, e) =>
            {
                e.Handled = true;
                PromptRename(playlist);
            };
            var delBtn = new Button
            {
                Content = "\uE74D",
                ToolTip = "Delete Playlist",
                Style = (Style)FindResource("Win10IconButtonStyle")
            };
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
            card.SetResourceReference(Border.BackgroundProperty, "CardHoverBrush");
            card.SetResourceReference(Border.BorderBrushProperty, "RevealBorderBrush");
        };
        card.MouseLeave += (_, _) =>
        {
            card.SetResourceReference(Border.BackgroundProperty, "CardBackgroundBrush");
            card.SetResourceReference(Border.BorderBrushProperty, "ControlBorderBrush");
        };
        card.MouseLeftButtonUp += (_, _) => onClick();

        card.ContextMenu = TrackMenuHelper.BuildTrackMenu(
            _ctx!.Services,
            trackIds,
            null,
            _ctx.OpenTextPrompt,
            playlist is null ? null : menu =>
            {
                menu.Items.Add(new Separator());
                menu.Items.Add(TrackMenuHelper.CreateItem("Rename...", "\uE70F", () => PromptRename(playlist)));
                menu.Items.Add(TrackMenuHelper.CreateItem("Delete", "\uE74D", () => ConfirmDelete(playlist)));
            });

        return card;
    }

    private void PromptRename(Playlist playlist)
    {
        if (_ctx is null) return;
        Guid pid = playlist.Id;
        _ctx.OpenTextPrompt(
            "Rename Playlist",
            "Enter a new name for this playlist.",
            playlist.Name,
            "Rename",
            name => _ctx.Services.Library.RenamePlaylist(pid, name));
    }

    private void ConfirmDelete(Playlist playlist)
    {
        if (_ctx is null) return;
        Guid pid = playlist.Id;
        _ctx.OpenConfirm(
            $"Delete \"{playlist.Name}\"?",
            "This removes the playlist. Your music files are not affected.",
            "Delete",
            () => _ctx.Services.Library.DeletePlaylist(pid),
            true);
    }

    private void NewPlaylist_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null) return;
        _ctx.OpenTextPrompt(
            "New Playlist",
            "Enter a name for the playlist.",
            string.Empty,
            "Create",
            name => _ctx.Services.Library.CreatePlaylist(name));
    }
}
