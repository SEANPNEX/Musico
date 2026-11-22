using System;
using System.Collections.Generic;
using MusicoWindows.Models;
using MusicoWindows.Stores;

namespace MusicoWindows.Views;

public enum PlaylistKind
{
    Favourites,
    User
}

public sealed record PlaylistRef(PlaylistKind Kind, Guid? Id = null)
{
    public static PlaylistRef Favourites { get; } = new(PlaylistKind.Favourites);
    public static PlaylistRef User(Guid id) => new(PlaylistKind.User, id);
}

public sealed class ViewContext
{
    public required AppServices Services { get; init; }
    public required Action<string, string, string, string, Action<string>> OpenTextPrompt { get; init; }
    public required Action<string, string, string, Action?, bool> OpenConfirm { get; init; }
    public required Action<AlbumInfo> OpenAlbum { get; init; }
    public required Action<string> OpenArtist { get; init; }
    public required Action<PlaylistRef> OpenPlaylist { get; init; }
    public Action? OpenAddCloudFolder { get; init; }
    public Action<LibraryRoot, IReadOnlyList<Track>>? OpenSaveCloudPlaylist { get; init; }
    public Action<Track, string>? OpenSaveCloudTrack { get; init; }
    public Action<LibraryRoot>? OpenUpdateTrackedFolder { get; init; }
    public Action<Track>? OpenSongInfo { get; init; }
    public Action<string, SearchField>? OpenSearch { get; init; }
}
