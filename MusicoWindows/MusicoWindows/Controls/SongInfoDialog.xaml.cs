using System;
using System.IO;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media.Imaging;
using Microsoft.Win32;
using MusicoWindows.Converters;
using MusicoWindows.Core;
using MusicoWindows.Models;
using MusicoWindows.Theme;
using MusicoWindows.Views;

namespace MusicoWindows.Controls;

public partial class SongInfoDialog : UserControl
{
    private ViewContext? _ctx;
    private Track? _track;
    private byte[]? _pendingCoverBytes;
    private bool _isSaving;

    public SongInfoDialog()
    {
        InitializeComponent();
    }

    public bool IsOpen => Visibility == Visibility.Visible;

    public async void Show(ViewContext ctx, Track track)
    {
        _ctx = ctx;
        _track = ctx.Services.Library.GetTrack(track.Id) ?? track;
        _pendingCoverBytes = null;
        _isSaving = false;

        TitleBox.Text = _track.Title;
        ArtistBox.Text = _track.Artist;
        AlbumBox.Text = _track.Album;
        GenreBox.Text = _track.Genre ?? string.Empty;
        SubtitleText.Text = _track.FileName;

        PreviewCoverImage.Source = null;
        PreviewCoverImage.Visibility = Visibility.Collapsed;
        CoverArt.Visibility = Visibility.Visible;
        CoverArt.SetSource(ctx.Services.Library.Artwork, _track.ArtworkKey, 240);
        CoverStatusText.Text = string.IsNullOrEmpty(_track.ArtworkKey)
            ? "No cover artwork"
            : "Embedded or cached artwork";

        UpdateQuickSearchLinks();
        PopulateBasePlaybackInfo(_track);
        SetBusy(false);

        Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(BackdropGrid, DialogCard);
        _ = Dispatcher.BeginInvoke(() => TitleBox.Focus());

        string? path = !_track.IsCloud
            ? ctx.Services.Library.GetFilePath(_track)
            : ctx.Services.CloudCache.TryGetCachedPath(_track.Id);

        if (!string.IsNullOrWhiteSpace(path))
        {
            string capturedId = _track.Id;
            var details = await Task.Run(() => TagLibMetadataReader.ReadAudioDetails(path));
            if (_track?.Id == capturedId && IsOpen)
            {
                ApplyAudioDetails(details, _track);
            }
        }
    }

    public void Close()
    {
        if (_isSaving) return;
        Visibility = Visibility.Collapsed;
    }

    private void PopulateBasePlaybackInfo(Track track)
    {
        if (_ctx is null) return;
        var history = _ctx.Services.History;

        PrecisionValue.Text = "—";
        FrequencyValue.Text = "—";
        BitRateValue.Text = "—";
        FileSizeValue.Text = track.FileSize > 0 ? FormatFileSize(track.FileSize) : "—";
        LengthValue.Text = TimeFormatter.Format(track.DurationSeconds);
        ChannelsValue.Text = "—";

        TimeAddedValue.Text = FormatTimestamp(track.DateAddedUtc);
        TimeEditedValue.Text = FormatTimestamp(track.ModifiedUtc);

        DateTimeOffset? lastPlayed = history.GetLastPlayed(track.Id);
        LastPlayedValue.Text = lastPlayed is DateTimeOffset lp ? FormatTimestamp(lp) : "Never";
        PlayedTimesValue.Text = history.GetCount(track.Id).ToString();

        string? filePath = !track.IsCloud ? _ctx.Services.Library.GetFilePath(track) : null;
        string sourceText = !string.IsNullOrWhiteSpace(filePath)
            ? filePath
            : track.IsCloud
                ? $"{track.CloudSource} ({track.RemoteTrackId})"
                : track.RelativePath;
        LocationValue.Text = sourceText;
        LocationValue.ToolTip = sourceText;
    }

    private void ApplyAudioDetails(AudioFileDetails details, Track track)
    {
        PrecisionValue.Text = details.BitDepth is int bits ? $"{bits}-bit" : "—";
        FrequencyValue.Text = details.SampleRateHz is int hz ? FormatSampleRate(hz) : "—";
        BitRateValue.Text = details.BitRateKbps is int kbps ? $"{kbps} kbps" : "—";

        long size = track.FileSize > 0 ? track.FileSize : (details.FileSize ?? 0);
        FileSizeValue.Text = size > 0 ? FormatFileSize(size) : "—";

        double duration = track.DurationSeconds > 0 ? track.DurationSeconds : details.DurationSeconds;
        LengthValue.Text = TimeFormatter.Format(duration);

        ChannelsValue.Text = details.Channels is int ch ? FormatChannels(ch) : "—";
    }

    private static string FormatSampleRate(int hz)
    {
        double khz = hz / 1000.0;
        string khzText = Math.Abs(khz - Math.Round(khz)) < 0.001
            ? $"{khz:0} kHz"
            : $"{khz:0.#} kHz";
        return $"{khzText} ({hz:N0} Hz)";
    }

    private static string FormatChannels(int channels) => channels switch
    {
        1 => "1 (Mono)",
        2 => "2 (Stereo)",
        6 => "6 (5.1)",
        8 => "8 (7.1)",
        _ => channels.ToString()
    };

    private static string FormatFileSize(long bytes)
    {
        if (bytes < 1024) return $"{bytes:N0} B";
        if (bytes < 1024 * 1024) return $"{bytes / 1024.0:0.##} KB ({bytes:N0} bytes)";
        if (bytes < 1024L * 1024L * 1024L) return $"{bytes / (1024.0 * 1024.0):0.##} MB ({bytes:N0} bytes)";
        return $"{bytes / (1024.0 * 1024.0 * 1024.0):0.##} GB ({bytes:N0} bytes)";
    }

    private static string FormatTimestamp(DateTimeOffset timestamp) =>
        timestamp.ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss");

    private void UpdateQuickSearchLinks()
    {
        UpdateLink(ArtistLinkButton, ArtistLinkText, "Artist", ArtistBox.Text);
        UpdateLink(AlbumLinkButton, AlbumLinkText, "Album", AlbumBox.Text);
        UpdateLink(GenreLinkButton, GenreLinkText, "Genre", GenreBox.Text);
    }

    private static void UpdateLink(Button button, TextBlock label, string fieldName, string value)
    {
        string trimmed = value.Trim();
        bool hasValue = trimmed.Length > 0;
        button.Visibility = hasValue ? Visibility.Visible : Visibility.Collapsed;
        if (hasValue)
        {
            label.Text = trimmed;
            button.ToolTip = $"Search {fieldName}: {trimmed}";
        }
    }

    private void SetBusy(bool busy)
    {
        _isSaving = busy;
        TitleBox.IsEnabled = !busy;
        ArtistBox.IsEnabled = !busy;
        AlbumBox.IsEnabled = !busy;
        GenreBox.IsEnabled = !busy;
        ChangeCoverButton.IsEnabled = !busy;
        SaveButton.IsEnabled = !busy;
        CancelButton.IsEnabled = !busy;
        CloseIconButton.IsEnabled = !busy;
    }

    private void MetadataInput_TextChanged(object sender, TextChangedEventArgs e)
    {
        UpdateQuickSearchLinks();
    }

    private void ChangeCover_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog
        {
            Title = "Select Cover Image",
            Filter = "Image Files|*.png;*.jpg;*.jpeg;*.bmp;*.gif;*.webp|All Files|*.*"
        };
        if (dlg.ShowDialog() != true) return;

        try
        {
            byte[] bytes = File.ReadAllBytes(dlg.FileName);
            using var ms = new MemoryStream(bytes, writable: false);
            var bmp = new BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = BitmapCacheOption.OnLoad;
            bmp.DecodePixelWidth = 240;
            bmp.StreamSource = ms;
            bmp.EndInit();
            bmp.Freeze();

            _pendingCoverBytes = bytes;
            PreviewCoverImage.Source = bmp;
            PreviewCoverImage.Visibility = Visibility.Visible;
            CoverArt.Visibility = Visibility.Collapsed;
            CoverStatusText.Text = Path.GetFileName(dlg.FileName);
        }
        catch (Exception ex)
        {
            _ctx?.OpenConfirm("Invalid Image", $"Could not load that image: {ex.Message}", "OK", null, false);
        }
    }

    private async void Save_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _track is null || _isSaving) return;

        SetBusy(true);
        try
        {
            await _ctx.Services.Library.UpdateTrackMetadataAsync(
                _track.Id,
                TitleBox.Text,
                ArtistBox.Text,
                AlbumBox.Text,
                GenreBox.Text,
                _pendingCoverBytes);
            _isSaving = false;
            Visibility = Visibility.Collapsed;
        }
        catch (Exception ex)
        {
            _isSaving = false;
            SetBusy(false);
            _ctx.OpenConfirm("Couldn't Save Song Info", ex.Message, "OK", null, false);
        }
    }

    private void ArtistLink_Click(object sender, RoutedEventArgs e) =>
        NavigateToSearch(ArtistBox.Text, SearchField.Artist);

    private void AlbumLink_Click(object sender, RoutedEventArgs e) =>
        NavigateToSearch(AlbumBox.Text, SearchField.Album);

    private void GenreLink_Click(object sender, RoutedEventArgs e) =>
        NavigateToSearch(GenreBox.Text, SearchField.Genre);

    private void NavigateToSearch(string value, SearchField field)
    {
        string trimmed = value.Trim();
        if (trimmed.Length == 0 || _ctx is null || _isSaving) return;

        Visibility = Visibility.Collapsed;
        _ctx.OpenSearch?.Invoke(trimmed, field);
    }

    private void Cancel_Click(object sender, RoutedEventArgs e) => Close();
    private void CloseIcon_Click(object sender, RoutedEventArgs e) => Close();
    private void Overlay_MouseDown(object sender, MouseButtonEventArgs e) => Close();
    private void Card_MouseDown(object sender, MouseButtonEventArgs e) => e.Handled = true;
}
