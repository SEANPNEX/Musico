using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Microsoft.Win32;
using MusicoWindows.Extensions;
using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using MusicoWindows.Views;

namespace MusicoWindows.Controls;

/// <summary>
/// Builds authentic Windows 10 ContextMenus for single tracks or track collections
/// (Play Next, Add to Queue, Add to Another Queue, Play in New Queue, Add to Playlist, Toggle Favourite, Delete).
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
        Action<Track>? openSongInfo = null,
        Track? explicitTrack = null,
        Guid? targetQueueId = null,
        Action<DeleteChoiceRequest>? openDeleteChoice = null,
        Action<string, string?, Action?>? showToast = null,
        Action<IReadOnlyList<Track>>? openBatchTagEdit = null)
    {
        var menu = new ContextMenu();
        var ids = trackIds.ToList();
        bool hasTracks = ids.Count > 0;
        Track? singleTrack = explicitTrack ?? (ids.Count == 1 ? services.Library.GetTrack(ids[0]) : null);
        bool isEphemeral = singleTrack?.IsEphemeral == true || (ids.Count == 1 && Track.HasEphemeralIdPrefix(ids[0]));

        void EnsureEphemeralRegistered()
        {
            if (singleTrack is { IsEphemeral: true })
            {
                services.Library.RegisterEphemeralTrack(singleTrack);
            }
        }

        void NotifyToast(string message)
        {
            if (showToast is not null)
            {
                showToast(message, null, null);
            }
            else if (Application.Current?.MainWindow is MainWindow mw)
            {
                mw.ShowToastMessage(message);
            }
        }

        Guid resolvedTargetQueueId = targetQueueId ?? services.Queues.ActiveId;

        var playNext = CreateItem(L.Get("Menu_PlayNext"), "\uE8B2", () =>
        {
            EnsureEphemeralRegistered();
            services.Player.PlayNext(ids);
            NotifyToast(ids.Count == 1
                ? L.Get("Toast_AddedPlayNext")
                : L.Format("Toast_AddedPlayNextCount", ids.Count));
        }, hasTracks);
        var addToQueue = CreateItem(L.Get("Menu_AddToQueue"), "\uE710", () =>
        {
            EnsureEphemeralRegistered();
            services.Player.AddToQueue(ids, targetQueueId);
            NotifyToast(ids.Count == 1
                ? L.Get("Toast_AddedToQueue")
                : L.Format("Toast_AddedToQueueCount", ids.Count));
        }, hasTracks);
        menu.Items.Add(playNext);
        menu.Items.Add(addToQueue);

        if (services.Queues.Queues.Count > 1)
        {
            var otherQueues = new MenuItem
            {
                Header = L.Get("Menu_AddToAnotherQueue"),
                Icon = CreateGlyph("\uE90B"),
                IsEnabled = hasTracks
            };
            foreach (var queue in services.Queues.Queues.Where(q => q.Id != resolvedTargetQueueId))
            {
                Guid queueId = queue.Id;
                string queueName = queue.Name;
                otherQueues.Items.Add(CreateItem(queueName, null, () =>
                {
                    EnsureEphemeralRegistered();
                    services.Player.AddToQueue(ids, queueId);
                    NotifyToast(L.Format("Toast_AddedToNamedQueue", queueName));
                }, hasTracks));
            }
            menu.Items.Add(otherQueues);
        }

        var playInNew = CreateItem(L.Get("Menu_PlayInNewQueue"), "\uE8A9", () =>
        {
            EnsureEphemeralRegistered();
            services.Player.PlayInNewQueue(ids);
        }, hasTracks);
        menu.Items.Add(playInNew);

        if (!isEphemeral)
        {
            menu.Items.Add(new Separator());

            var addToPlaylist = new MenuItem
            {
                Header = L.Get("Menu_AddToPlaylist"),
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
            addToPlaylist.Items.Add(CreateItem(L.Get("Menu_NewPlaylistEllipsis"), "\uE710", () =>
            {
                openTextPrompt(
                    L.Get("Nav_NewPlaylist"),
                    L.Get("Playlists_NewMessage"),
                    string.Empty,
                    L.Get("Common_Create"),
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
                    isFav ? L.Get("Menu_RemoveFromFavourites") : L.Get("Menu_AddToFavourites"),
                    isFav ? "\uEB52" : "\uEB51",
                    () => services.Library.ToggleFavourite(favouriteTrackId),
                    true));
            }
        }

        if (ids.Count == 1 && singleTrack is not null)
        {
            menu.Items.Add(CreateItem(
                L.Get("SongInfo_Title"),
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
        else if (ids.Count > 1 && !isEphemeral)
        {
            var editableTracks = services.Library.GetTracks(ids)
                .Where(t => !t.IsEphemeral && !Track.HasEphemeralIdPrefix(t.Id))
                .ToList();
            if (editableTracks.Count > 0)
            {
                menu.Items.Add(CreateItem(
                    L.Get("Menu_EditTagsEllipsis"),
                    "\uE70F",
                    () =>
                    {
                        if (openBatchTagEdit is not null)
                        {
                            openBatchTagEdit(editableTracks);
                        }
                        else if (Application.Current?.MainWindow is MainWindow mw)
                        {
                            mw.ShowBatchTagEdit(editableTracks);
                        }
                    }));
            }
        }

        if (ids.Count == 1 && singleTrack is { IsCloud: true } cloudTrack && CanExportCloudTrack(services, cloudTrack))
        {
            menu.Items.Add(new Separator());
            menu.Items.Add(CreateItem(
                L.Get("Folders_SaveToLocal"),
                "\uE896",
                () => PromptAndExportCloudTrack(services, cloudTrack, openSaveCloudTrack)));
        }

        if (ids.Count == 1 &&
            singleTrack is { IsCloud: false, IsEphemeral: false } localTrack &&
            !Track.HasEphemeralIdPrefix(localTrack.Id))
        {
            menu.Items.Add(new Separator());
            menu.Items.Add(CreateItem(
                L.Get("Menu_DeleteSongEllipsis"),
                "\uE74D",
                () => PromptDeleteLocalTrack(services, localTrack, openDeleteChoice, showToast)));
        }

        appendExtra?.Invoke(menu);
        return menu;
    }

    private static void PromptDeleteLocalTrack(
        AppServices services,
        Track track,
        Action<DeleteChoiceRequest>? openDeleteChoice,
        Action<string, string?, Action?>? showToast)
    {
        string? fullPath = services.Library.GetFilePath(track);
        var request = new DeleteChoiceRequest(
            Title: L.Format("Delete_SongConfirmTitle", track.Title),
            Message: L.Get("Delete_SongConfirmMessage"),
            ItemName: track.FileName,
            ExactPath: fullPath,
            SongCountText: null,
            RemoveFromLibraryLabel: L.Get("Delete_RemoveFromLibrary"),
            OnRemoveFromLibrary: () =>
            {
                services.Library.RemoveTrackFromLibrary(track.Id);
                services.Lyrics.RemoveImported(track.Id);
                string msg = L.Format("Toast_RemovedFromLibrary", track.Title);
                if (showToast is not null) showToast(msg, null, null);
                else if (Application.Current?.MainWindow is MainWindow mw) mw.ShowToastMessage(msg);
            },
            MoveToRecycleBinLabel: !string.IsNullOrWhiteSpace(fullPath) ? L.Get("Delete_MoveToRecycleBin") : null,
            OnMoveToRecycleBin: !string.IsNullOrWhiteSpace(fullPath) ? () =>
            {
                try
                {
                    services.Library.DeleteTrackFile(track.Id);
                    services.Lyrics.RemoveImported(track.Id);
                    string msg = L.Format("Toast_MovedToRecycleBin", track.Title);
                    if (showToast is not null) showToast(msg, null, null);
                    else if (Application.Current?.MainWindow is MainWindow mw) mw.ShowToastMessage(msg);
                }
                catch (Exception ex)
                {
                    if (Application.Current?.MainWindow is MainWindow mw)
                    {
                        mw.ShowErrorDialog(L.Get("Delete_FailedTitle"), ex.Message);
                    }
                }
            } : null);

        if (openDeleteChoice is not null)
        {
            openDeleteChoice(request);
        }
        else if (Application.Current?.MainWindow is MainWindow mw)
        {
            mw.ShowDeleteChoice(request);
        }
    }

    public static bool CanExportCloudTrack(AppServices services, Track track)
    {
        if (!track.IsCloud) return false;
        var provider = services.CloudProviders.GetBySourceOrProviderId(track.CloudSource);
        if (provider is null || !provider.IsAvailable) return false;
        if (provider is JsExtensionCloudProvider jsExt)
        {
            return jsExt.Manifest.HasCapability(ExtensionCapability.Stream);
        }
        return true;
    }

    private static void PromptAndExportCloudTrack(
        AppServices services,
        Track track,
        Action<Track, string>? openSaveCloudTrack)
    {
        var dlg = new OpenFolderDialog
        {
            Title = L.Format("Menu_SaveTrackToLocalTitle", track.Title)
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
