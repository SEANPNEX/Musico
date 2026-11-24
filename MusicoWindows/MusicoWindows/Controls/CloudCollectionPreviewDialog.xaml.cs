using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using MusicoWindows.Cloud;
using MusicoWindows.Converters;
using MusicoWindows.Extensions;
using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Theme;
using MusicoWindows.Views;

namespace MusicoWindows.Controls;

public partial class CloudCollectionPreviewDialog : UserControl
{
    public sealed class PreviewTrackRowItem
    {
        public required int Index { get; init; }
        public required string NumberText { get; init; }
        public required Track Track { get; init; }
        public required string Subtitle { get; init; }
        public required string DurationText { get; init; }
    }

    private ViewContext? _ctx;
    private string? _extensionId;
    private string? _extensionName;
    private ExtSearchResultItem? _item;
    private string _collectionUrl = string.Empty;
    private string _collectionTitle = string.Empty;
    private List<Track> _tracks = new();
    private CancellationTokenSource? _fetchCts;
    private bool _isAddingFolder;
    private bool _supportsCollectionImport = true;

    public CloudCollectionPreviewDialog()
    {
        InitializeComponent();
        Unloaded += (_, _) => CancelFetch();
    }

    public bool IsOpen => Visibility == Visibility.Visible;

    public async void Show(
        ViewContext ctx,
        string extensionId,
        string extensionDisplayName,
        ExtSearchResultItem item,
        bool supportsCollectionImport = true)
    {
        CancelFetch();
        _ctx = ctx;
        _extensionId = extensionId;
        _extensionName = extensionDisplayName;
        _item = item;
        _supportsCollectionImport = supportsCollectionImport;
        _collectionUrl = !string.IsNullOrWhiteSpace(item.SourceUrl) ? item.SourceUrl!.Trim() : item.Id;
        _collectionTitle = !string.IsNullOrWhiteSpace(item.Title) ? item.Title : L.Get("Preview_DefaultTitle");
        _tracks = new();
        _isAddingFolder = false;

        KindBadgeText.Text = item.Kind == ExtensionSearchKind.Album
            ? L.Get("Songs_KindBadge_Album")
            : L.Get("Songs_KindBadge_Playlist");
        TitleText.Text = _collectionTitle;
        SubtitleText.Text = !string.IsNullOrWhiteSpace(item.Artist)
            ? $"{extensionDisplayName} · {item.Artist}"
            : extensionDisplayName;
        CoverArt.SetSource(ctx.Services.Library.Artwork, artworkKey: null, decodeSize: 160, remoteArtworkUrl: item.ArtworkUrl, thumbnailCache: ctx.Services.Thumbnails);

        PlayAllButton.IsEnabled = false;
        AddFolderButton.Visibility = supportsCollectionImport ? Visibility.Visible : Visibility.Collapsed;
        AddFolderButton.IsEnabled = supportsCollectionImport;
        AddFolderButtonText.Text = L.Get("Preview_AddAsCloudFolder");
        AddStatusText.Text = string.Empty;
        ErrorBanner.Visibility = Visibility.Collapsed;
        EmptyText.Visibility = Visibility.Collapsed;
        TracksList.ItemsSource = null;

        Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(BackdropGrid, DialogCard);

        await LoadCollectionAsync();
    }

    public void Close()
    {
        CancelFetch();
        Visibility = Visibility.Collapsed;
    }

    private void CancelFetch()
    {
        var cts = _fetchCts;
        _fetchCts = null;
        if (cts is not null)
        {
            try { cts.Cancel(); } catch { /* ignore */ }
            cts.Dispose();
        }
    }

    private async Task LoadCollectionAsync()
    {
        if (_ctx is null || string.IsNullOrWhiteSpace(_extensionId) || string.IsNullOrWhiteSpace(_collectionUrl))
        {
            return;
        }

        CancelFetch();
        var cts = new CancellationTokenSource();
        _fetchCts = cts;

        LoadingText.Text = L.Get("Preview_Loading");
        LoadingPanel.Visibility = Visibility.Visible;
        TracksList.Visibility = Visibility.Collapsed;
        EmptyText.Visibility = Visibility.Collapsed;
        ErrorBanner.Visibility = Visibility.Collapsed;
        PlayAllButton.IsEnabled = false;

        try
        {
            CloudPlaylistResult res = await _ctx.Services.Extensions.FetchCollectionPreviewAsync(
                _extensionId,
                _collectionUrl,
                proxyUrl: null,
                ct: cts.Token);

            if (cts.IsCancellationRequested || _ctx is null) return;

            if (!string.IsNullOrWhiteSpace(res.Title))
            {
                _collectionTitle = res.Title;
                TitleText.Text = res.Title;
            }

            _tracks = res.Items.Select((t, idx) => Track.MakeEphemeral(
                extensionId: _extensionId,
                remoteTrackId: t.RemoteTrackId,
                title: t.Title,
                artist: t.Artist,
                album: t.Album,
                durationSeconds: t.DurationSeconds,
                remoteArtworkUrl: t.ArtworkUrl,
                trackNumber: t.TrackNumber > 0 ? t.TrackNumber : idx + 1,
                relativePath: t.RelativePath)).ToList();

            string countLabel = L.Format("Songs_TrackCountShort", _tracks.Count);
            SubtitleText.Text = !string.IsNullOrWhiteSpace(_item?.Artist)
                ? $"{_extensionName} · {_item!.Artist} · {countLabel}"
                : $"{_extensionName} · {countLabel}";

            LoadingPanel.Visibility = Visibility.Collapsed;
            if (_tracks.Count == 0)
            {
                EmptyText.Text = L.Get("Preview_Empty");
                EmptyText.Visibility = Visibility.Visible;
                PlayAllButton.IsEnabled = false;
                return;
            }

            TracksList.ItemsSource = _tracks.Select((t, idx) => new PreviewTrackRowItem
            {
                Index = idx,
                NumberText = (idx + 1).ToString(),
                Track = t,
                Subtitle = string.IsNullOrWhiteSpace(t.Album)
                    ? L.DisplayArtist(t.Artist)
                    : $"{L.DisplayArtist(t.Artist)} — {t.Album}",
                DurationText = TimeFormatter.Format(t.DurationSeconds)
            }).ToList();

            TracksList.Visibility = Visibility.Visible;
            PlayAllButton.IsEnabled = true;
        }
        catch (OperationCanceledException) when (cts.IsCancellationRequested)
        {
            // Cancelled or closed cleanly.
        }
        catch (Exception ex)
        {
            if (cts.IsCancellationRequested) return;
            LoadingPanel.Visibility = Visibility.Collapsed;
            ErrorText.Text = ex is CloudServiceException cse && cse.Code == CloudErrorCode.AuthRequired
                ? L.Get("Songs_CloudAuthRequiredHint")
                : ex.Message;
            ErrorBanner.Visibility = Visibility.Visible;
        }
    }

    private void PlayAll_Click(object sender, RoutedEventArgs e) => PlayAtIndex(0);

    private void PlayAtIndex(int index)
    {
        if (_ctx is null || _tracks.Count == 0) return;
        int clamped = Math.Clamp(index, 0, _tracks.Count - 1);
        _ctx.Services.Library.RegisterEphemeralTracks(_tracks);
        var ids = _tracks.Select(t => t.Id).ToList();
        _ctx.Services.Player.PlayInNewQueue(ids, _collectionTitle, clamped);
    }

    private async void AddFolder_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _isAddingFolder || !_supportsCollectionImport || string.IsNullOrWhiteSpace(_extensionId))
            return;

        _isAddingFolder = true;
        AddFolderButton.IsEnabled = false;
        AddFolderButtonText.Text = L.Get("Preview_AddingCloudFolder");
        AddStatusText.Text = string.Empty;
        ErrorBanner.Visibility = Visibility.Collapsed;

        try
        {
            await _ctx.Services.Library.AddCloudRootAsync(
                _collectionUrl,
                cookie: null,
                proxyUrl: null,
                ct: CancellationToken.None,
                preferredProviderId: CloudSources.Extension(_extensionId));

            AddStatusText.Text = L.Get("Preview_AddedCloudFolder");
            AddFolderButtonText.Text = L.Get("Preview_AddedCloudFolder");
        }
        catch (Exception ex)
        {
            AddFolderButton.IsEnabled = true;
            AddFolderButtonText.Text = L.Get("Preview_AddAsCloudFolder");
            ErrorText.Text = ex.Message;
            ErrorBanner.Visibility = Visibility.Visible;
        }
        finally
        {
            _isAddingFolder = false;
        }
    }

    private async void Retry_Click(object sender, RoutedEventArgs e) => await LoadCollectionAsync();

    private void TracksList_SelectionChanged(object sender, SelectionChangedEventArgs e) =>
        TracksList.SelectedItem = null;

    private void TrackRow_MouseEnter(object sender, MouseEventArgs e)
    {
        if (sender is Border border)
        {
            border.SetResourceReference(Border.BackgroundProperty, "SubtleHoverBrush");
            border.SetResourceReference(Border.BorderBrushProperty, "RevealBorderBrush");
        }
    }

    private void TrackRow_MouseLeave(object sender, MouseEventArgs e)
    {
        if (sender is Border border)
        {
            border.Background = Brushes.Transparent;
            border.BorderBrush = Brushes.Transparent;
        }
    }

    private void TrackRow_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (sender is Border { DataContext: PreviewTrackRowItem row })
        {
            PlayAtIndex(row.Index);
        }
    }

    private void TrackRow_MouseRightButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (_ctx is null || sender is not Border { DataContext: PreviewTrackRowItem row } border)
            return;

        var menu = TrackMenuHelper.BuildTrackMenu(
            _ctx.Services,
            [row.Track.Id],
            row.Track.Id,
            _ctx.OpenTextPrompt,
            appendExtra: null,
            openSaveCloudTrack: _ctx.OpenSaveCloudTrack,
            openSongInfo: _ctx.OpenSongInfo,
            explicitTrack: row.Track);
        menu.PlacementTarget = border;
        menu.IsOpen = true;
        e.Handled = true;
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();

    private void Overlay_MouseDown(object sender, MouseButtonEventArgs e) => Close();

    private void Card_MouseDown(object sender, MouseButtonEventArgs e) => e.Handled = true;
}
