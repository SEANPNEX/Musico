using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Theme;
using MusicoWindows.Views;

namespace MusicoWindows.Controls;

public partial class BatchTagEditDialog : UserControl
{
    private ViewContext? _ctx;
    private List<Track> _tracks = new();
    private string? _initialSharedArtist;
    private string? _initialSharedAlbum;
    private Action<int>? _onApplied;
    private bool _isSaving;

    public BatchTagEditDialog()
    {
        InitializeComponent();
    }

    public bool IsOpen => Visibility == Visibility.Visible;

    public void Show(ViewContext ctx, IReadOnlyList<Track> tracks, Action<int>? onApplied = null)
    {
        var editable = new List<Track>();
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var raw in tracks)
        {
            var live = ctx.Services.Library.GetTrack(raw.Id) ?? raw;
            if (live.IsEphemeral || Track.HasEphemeralIdPrefix(live.Id)) continue;
            if (seen.Add(live.Id))
            {
                editable.Add(live);
            }
        }
        if (editable.Count == 0) return;

        _ctx = ctx;
        _tracks = editable;
        _onApplied = onApplied;
        _isSaving = false;

        var distinctArtists = editable
            .Select(t => t.Artist.Trim())
            .Distinct(StringComparer.Ordinal)
            .ToList();
        _initialSharedArtist = distinctArtists.Count == 1 && !string.IsNullOrWhiteSpace(distinctArtists[0])
            ? distinctArtists[0]
            : null;

        var distinctAlbums = editable
            .Select(t => t.Album.Trim())
            .Distinct(StringComparer.Ordinal)
            .ToList();
        _initialSharedAlbum = distinctAlbums.Count == 1 && !string.IsNullOrWhiteSpace(distinctAlbums[0])
            ? distinctAlbums[0]
            : null;

        SubtitleText.Text = L.Format("BatchTag_SelectedCount", editable.Count);
        ArtistPlaceholder.Text = _initialSharedArtist is null
            ? L.Get("BatchTag_MixedPlaceholder")
            : L.Get("BatchTag_KeepExistingPlaceholder");
        AlbumPlaceholder.Text = _initialSharedAlbum is null
            ? L.Get("BatchTag_MixedPlaceholder")
            : L.Get("BatchTag_KeepExistingPlaceholder");

        ArtistBox.Text = _initialSharedArtist ?? string.Empty;
        AlbumBox.Text = _initialSharedAlbum ?? string.Empty;
        UpdatePlaceholders();
        SetBusy(false);

        Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(BackdropGrid, DialogCard);
        _ = Dispatcher.BeginInvoke(() =>
        {
            ArtistBox.Focus();
            ArtistBox.SelectAll();
        });
    }

    public void Close()
    {
        if (_isSaving) return;
        Visibility = Visibility.Collapsed;
    }

    private void UpdatePlaceholders()
    {
        ArtistPlaceholder.Visibility = string.IsNullOrEmpty(ArtistBox.Text) ? Visibility.Visible : Visibility.Collapsed;
        AlbumPlaceholder.Visibility = string.IsNullOrEmpty(AlbumBox.Text) ? Visibility.Visible : Visibility.Collapsed;
    }

    private void SetBusy(bool busy)
    {
        _isSaving = busy;
        ArtistBox.IsEnabled = !busy;
        AlbumBox.IsEnabled = !busy;
        ApplyButton.IsEnabled = !busy;
        CancelButton.IsEnabled = !busy;
        CloseIconButton.IsEnabled = !busy;
    }

    private void ArtistBox_TextChanged(object sender, TextChangedEventArgs e) => UpdatePlaceholders();

    private void AlbumBox_TextChanged(object sender, TextChangedEventArgs e) => UpdatePlaceholders();

    private void InputBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            e.Handled = true;
            Apply_Click(sender, e);
        }
    }

    private async void Apply_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _tracks.Count == 0 || _isSaving) return;

        string? rawArtist = string.IsNullOrWhiteSpace(ArtistBox.Text) ? null : ArtistBox.Text.Trim();
        string? rawAlbum = string.IsNullOrWhiteSpace(AlbumBox.Text) ? null : AlbumBox.Text.Trim();

        string? artistToApply = rawArtist is not null &&
                                (_initialSharedArtist is null || !string.Equals(rawArtist, _initialSharedArtist, StringComparison.Ordinal))
            ? rawArtist
            : null;
        string? albumToApply = rawAlbum is not null &&
                               (_initialSharedAlbum is null || !string.Equals(rawAlbum, _initialSharedAlbum, StringComparison.Ordinal))
            ? rawAlbum
            : null;

        if (artistToApply is null && albumToApply is null)
        {
            Visibility = Visibility.Collapsed;
            return;
        }

        SetBusy(true);
        try
        {
            int count = await _ctx.Services.Library.BatchUpdateTrackMetadataAsync(
                _tracks.Select(t => t.Id),
                artistToApply,
                albumToApply);
            _isSaving = false;
            Visibility = Visibility.Collapsed;
            _onApplied?.Invoke(count);
            if (count > 0)
            {
                _ctx.ShowToast?.Invoke(L.Format("Toast_BatchTagsUpdated", count), null, null);
            }
        }
        catch (Exception ex)
        {
            _isSaving = false;
            SetBusy(false);
            _ctx.OpenConfirm(L.Get("BatchTag_CouldNotSaveTitle"), ex.Message, L.Get("Common_OK"), null, false);
        }
    }

    private void Cancel_Click(object sender, RoutedEventArgs e) => Close();
    private void CloseIcon_Click(object sender, RoutedEventArgs e) => Close();
    private void Overlay_MouseDown(object sender, MouseButtonEventArgs e) => Close();
    private void Card_MouseDown(object sender, MouseButtonEventArgs e) => e.Handled = true;
}
