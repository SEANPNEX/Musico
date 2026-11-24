using System;
using System.Collections.Concurrent;
using System.IO;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Imaging;
using MusicoWindows.Cloud;
using MusicoWindows.Core;

namespace MusicoWindows.Controls;

public partial class ArtworkImage : UserControl
{
    private static readonly ConcurrentDictionary<string, BitmapImage> ImageCache = new(StringComparer.Ordinal);
    private CancellationTokenSource? _remoteCts;

    public static readonly DependencyProperty ArtworkKeyProperty =
        DependencyProperty.Register(nameof(ArtworkKey), typeof(string), typeof(ArtworkImage),
            new PropertyMetadata(null, OnArtworkChanged));

    public static readonly DependencyProperty ArtworkCacheProperty =
        DependencyProperty.Register(nameof(ArtworkCache), typeof(ArtworkCache), typeof(ArtworkImage),
            new PropertyMetadata(null, OnArtworkChanged));

    public static readonly DependencyProperty RemoteArtworkUrlProperty =
        DependencyProperty.Register(nameof(RemoteArtworkUrl), typeof(string), typeof(ArtworkImage),
            new PropertyMetadata(null, OnArtworkChanged));

    public static readonly DependencyProperty ThumbnailCacheProperty =
        DependencyProperty.Register(nameof(ThumbnailCache), typeof(CloudThumbnailCache), typeof(ArtworkImage),
            new PropertyMetadata(null, OnArtworkChanged));

    public static readonly DependencyProperty DecodeSizeProperty =
        DependencyProperty.Register(nameof(DecodeSize), typeof(int), typeof(ArtworkImage),
            new PropertyMetadata(160, OnArtworkChanged));

    public static readonly DependencyProperty GlyphSizeProperty =
        DependencyProperty.Register(nameof(GlyphSize), typeof(double), typeof(ArtworkImage),
            new PropertyMetadata(20.0, OnGlyphSizeChanged));

    public ArtworkImage()
    {
        InitializeComponent();
        Unloaded += (_, _) => CancelRemoteLoad();
    }

    public string? ArtworkKey
    {
        get => (string?)GetValue(ArtworkKeyProperty);
        set => SetValue(ArtworkKeyProperty, value);
    }

    public ArtworkCache? ArtworkCache
    {
        get => (ArtworkCache?)GetValue(ArtworkCacheProperty);
        set => SetValue(ArtworkCacheProperty, value);
    }

    public string? RemoteArtworkUrl
    {
        get => (string?)GetValue(RemoteArtworkUrlProperty);
        set => SetValue(RemoteArtworkUrlProperty, value);
    }

    public CloudThumbnailCache? ThumbnailCache
    {
        get => (CloudThumbnailCache?)GetValue(ThumbnailCacheProperty);
        set => SetValue(ThumbnailCacheProperty, value);
    }

    public int DecodeSize
    {
        get => (int)GetValue(DecodeSizeProperty);
        set => SetValue(DecodeSizeProperty, value);
    }

    public double GlyphSize
    {
        get => (double)GetValue(GlyphSizeProperty);
        set => SetValue(GlyphSizeProperty, value);
    }

    private static void OnGlyphSizeChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is ArtworkImage control)
        {
            control.PlaceholderGlyph.FontSize = (double)e.NewValue;
        }
    }

    private static void OnArtworkChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is ArtworkImage control)
        {
            control.Refresh();
        }
    }

    public void SetSource(
        ArtworkCache? cache,
        string? artworkKey,
        int decodeSize = 160,
        string? remoteArtworkUrl = null,
        CloudThumbnailCache? thumbnailCache = null)
    {
        ArtworkCache = cache;
        DecodeSize = decodeSize;
        RemoteArtworkUrl = remoteArtworkUrl;
        ThumbnailCache = thumbnailCache;
        ArtworkKey = artworkKey;
        Refresh();
    }

    private void CancelRemoteLoad()
    {
        var cts = _remoteCts;
        _remoteCts = null;
        if (cts is not null)
        {
            try { cts.Cancel(); } catch { /* ignore */ }
            cts.Dispose();
        }
    }

    private void Refresh()
    {
        CancelRemoteLoad();
        int bucket = DecodeSize <= 96 ? 96 : DecodeSize <= 240 ? 240 : 600;

        if (!string.IsNullOrWhiteSpace(ArtworkKey) && ArtworkCache is not null)
        {
            string cacheKey = $"{ArtworkKey}:{bucket}";
            if (ImageCache.TryGetValue(cacheKey, out var cached))
            {
                ShowBitmap(cached);
                return;
            }

            string? path = ArtworkCache.GetImagePath(ArtworkKey);
            if (!string.IsNullOrEmpty(path) && File.Exists(path))
            {
                try
                {
                    var bitmap = new BitmapImage();
                    bitmap.BeginInit();
                    bitmap.UriSource = new Uri(path, UriKind.Absolute);
                    bitmap.CacheOption = BitmapCacheOption.OnLoad;
                    bitmap.DecodePixelWidth = bucket;
                    bitmap.EndInit();
                    bitmap.Freeze();

                    if (ImageCache.Count > 500)
                    {
                        ImageCache.Clear();
                    }
                    ImageCache.TryAdd(cacheKey, bitmap);

                    ShowBitmap(bitmap);
                    return;
                }
                catch
                {
                    // Fall through to remote or placeholder.
                }
            }
        }

        if (CloudThumbnailCache.IsAllowedHttpsUrl(RemoteArtworkUrl) && ThumbnailCache is { } thumbCache)
        {
            string remoteUrl = RemoteArtworkUrl!.Trim();
            if (thumbCache.TryGetBitmap(remoteUrl, bucket, out var cachedRemote) && cachedRemote is not null)
            {
                ShowBitmap(cachedRemote);
                return;
            }

            ShowPlaceholder();
            var cts = new CancellationTokenSource();
            _remoteCts = cts;
            _ = LoadRemoteThumbnailAsync(thumbCache, remoteUrl, bucket, cts.Token);
            return;
        }

        ShowPlaceholder();
    }

    private async System.Threading.Tasks.Task LoadRemoteThumbnailAsync(
        CloudThumbnailCache cache,
        string url,
        int bucket,
        CancellationToken ct)
    {
        try
        {
            var bmp = await cache.GetBitmapAsync(url, bucket, ct);
            if (ct.IsCancellationRequested || bmp is null) return;
            if (!string.Equals(RemoteArtworkUrl?.Trim(), url, StringComparison.Ordinal)) return;
            if (!string.IsNullOrWhiteSpace(ArtworkKey) && ArtworkCache?.GetImagePath(ArtworkKey) is not null) return;

            ShowBitmap(bmp);
        }
        catch
        {
            // Keep placeholder on cancelled/failed thumbnail fetch.
        }
    }

    private void ShowBitmap(BitmapImage bitmap)
    {
        CoverImage.Source = bitmap;
        CoverImage.Visibility = Visibility.Visible;
        PlaceholderGlyph.Visibility = Visibility.Collapsed;
    }

    private void ShowPlaceholder()
    {
        CoverImage.Source = null;
        CoverImage.Visibility = Visibility.Collapsed;
        PlaceholderGlyph.Visibility = Visibility.Visible;
    }
}
