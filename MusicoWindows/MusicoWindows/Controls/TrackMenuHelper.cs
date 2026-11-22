using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Microsoft.Win32;
using MusicoWindows.Models;
using MusicoWindows.Stores;

namespace MusicoWindows.Controls;

/// <summary>
/// Builds authentic Windows 10 ContextMenus for single tracks or track collections
/// (Play Next, Add to Queue, Add to Another Queue, Play in New Queue, Add to Playlist, Toggle Favourite).
/// </summary>
public static class TrackMenuHelper
{
    public static ContextMenu BuildTrackMenu(
        AppServices services,
        IReadOnlyList<string> trackIds,
        string? favouriteTrackId,
        Action<string, string, string, string, Action<string>> openTextPrompt,
        Action<ContextMenu>? appendExtra = null,
        Action<Track, string>? openSaveCloudTrack = null,
        Action<Track>? openSongInfo = null)
    {
        var menu = new ContextMenu();
        var ids = trackIds.ToList();
        bool hasTracks = ids.Count > 0;

        var playNext = CreateItem("Play Next", "\uE8B2", () => services.Player.PlayNext(ids), hasTracks);
        var addToQueue = CreateItem("Add to Queue", "\uE710", () => services.Player.AddToQueue(ids, null), hasTracks);
        menu.Items.Add(playNext);
        menu.Items.Add(addToQueue);

        if (services.Queues.Queues.Count > 1)
        {
            var otherQueues = new MenuItem
            {
                Header = "Add to Another Queue",
                Icon = CreateGlyph("\uE90B"),
                IsEnabled = hasTracks
            };
            foreach (var queue in services.Queues.Queues.Where(q => q.Id != services.Queues.ActiveId))
            {
                Guid queueId = queue.Id;
                otherQueues.Items.Add(CreateItem(queue.Name, null, () => services.Player.AddToQueue(ids, queueId), hasTracks));
            }
            menu.Items.Add(otherQueues);
        }

        var playInNew = CreateItem("Play in New Queue", "\uE8A9", () => services.Player.PlayInNewQueue(ids), hasTracks);
        menu.Items.Add(playInNew);

        menu.Items.Add(new Separator());

        var addToPlaylist = new MenuItem
        {
            Header = "Add to Playlist",
            Icon = CreateGlyph("\uE8D6"),
            IsEnabled = hasTracks
        };
        foreach (var pl in services.Library.Playlists)
        {
            Guid playlistId = pl.Id;
            addToPlaylist.Items.Add(CreateItem(pl.Name, null, () => services.Library.AddToPlaylist(ids, playlistId), hasTracks));
        }
        if (services.Library.Playlists.Count > 0)
        {
            addToPlaylist.Items.Add(new Separator());
        }
        addToPlaylist.Items.Add(CreateItem("New Playlist...", "\uE710", () =>
        {
            openTextPrompt(
                "New Playlist",
                "Enter a name for the playlist.",
                string.Empty,
                "Create",
                name =>
                {
                    var id = services.Library.CreatePlaylist(name);
                    services.Library.AddToPlaylist(ids, id);
                });
        }, hasTracks));
        menu.Items.Add(addToPlaylist);

        if (!string.IsNullOrEmpty(favouriteTrackId))
        {
            bool isFav = services.Library.IsFavourite(favouriteTrackId);
            menu.Items.Add(CreateItem(
                isFav ? "Remove from Favourites" : "Add to Favourites",
                isFav ? "\uEB52" : "\uEB51",
                () => services.Library.ToggleFavourite(favouriteTrackId),
                true));
        }

        if (ids.Count == 1 &&
            !string.IsNullOrEmpty(favouriteTrackId) &&
            services.Library.GetTrack(ids[0]) is { } singleTrack)
        {
            menu.Items.Add(CreateItem(
                "Song Info",
                "\uE946",
                () =>
                {
                    if (openSongInfo is not null)
                    {
                        openSongInfo(singleTrack);
                    }
                    else if (Application.Current?.MainWindow is MainWindow mw)
                    {
                        mw.ShowSongInfo(singleTrack);
                    }
                }));
        }

        if (ids.Count == 1 && services.Library.GetTrack(ids[0]) is { IsCloud: true } cloudTrack)
        {
            menu.Items.Add(new Separator());
            menu.Items.Add(CreateItem(
                "Save to Local Folder...",
                "\uE896",
                () => PromptAndExportCloudTrack(services, cloudTrack, openSaveCloudTrack)));
        }

        appendExtra?.Invoke(menu);
        return menu;
    }

    private static void PromptAndExportCloudTrack(
        AppServices services,
        Track track,
        Action<Track, string>? openSaveCloudTrack)
    {
        var dlg = new OpenFolderDialog
        {
            Title = $"Save \"{track.Title}\" to Local Folder"
        };
        if (!string.IsNullOrWhiteSpace(services.MusicDirectory) && Directory.Exists(services.MusicDirectory))
        {
            dlg.InitialDirectory = services.MusicDirectory;
        }
        if (dlg.ShowDialog() != true) return;

        if (openSaveCloudTrack is not null)
        {
            openSaveCloudTrack(track, dlg.FolderName);
        }
        else if (Application.Current?.MainWindow is MainWindow mw)
        {
            mw.StartSingleTrackExport(track, dlg.FolderName);
        }
    }

    public static MenuItem CreateItem(string header, string? mdl2Glyph, Action onClick, bool isEnabled = true)
    {
        var item = new MenuItem
        {
            Header = header,
            IsEnabled = isEnabled
        };
        if (!string.IsNullOrEmpty(mdl2Glyph))
        {
            item.Icon = CreateGlyph(mdl2Glyph);
        }
        item.Click += (_, _) => onClick();
        return item;
    }

    private static TextBlock CreateGlyph(string glyph) => new()
    {
        Text = glyph,
        FontFamily = new FontFamily("Segoe MDL2 Assets"),
        FontSize = 14,
        VerticalAlignment = VerticalAlignment.Center,
        HorizontalAlignment = HorizontalAlignment.Center
    };
}
