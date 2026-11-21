using System;
using System.Collections.Concurrent;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Imaging;
using MusicoWindows.Core;

namespace MusicoWindows.Controls;

public partial class ArtworkImage : UserControl
{
    private static readonly ConcurrentDictionary<string, BitmapImage> ImageCache = new(StringComparer.Ordinal);

    public static readonly DependencyProperty ArtworkKeyProperty =
        DependencyProperty.Register(nameof(ArtworkKey), typeof(string), typeof(ArtworkImage),
            new PropertyMetadata(null, OnArtworkChanged));

    public static readonly DependencyProperty ArtworkCacheProperty =
        DependencyProperty.Register(nameof(ArtworkCache), typeof(ArtworkCache), typeof(ArtworkImage),
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

    public void SetSource(ArtworkCache? cache, string? artworkKey, int decodeSize = 160)
    {
        ArtworkCache = cache;
        DecodeSize = decodeSize;
        ArtworkKey = artworkKey;
        Refresh();
    }

    private void Refresh()
    {
        if (string.IsNullOrWhiteSpace(ArtworkKey) || ArtworkCache is null)
        {
            CoverImage.Source = null;
            CoverImage.Visibility = Visibility.Collapsed;
            PlaceholderGlyph.Visibility = Visibility.Visible;
            return;
        }

        int bucket = DecodeSize <= 96 ? 96 : DecodeSize <= 240 ? 240 : 600;
        string cacheKey = $"{ArtworkKey}:{bucket}";

        if (ImageCache.TryGetValue(cacheKey, out var cached))
        {
            CoverImage.Source = cached;
            CoverImage.Visibility = Visibility.Visible;
            PlaceholderGlyph.Visibility = Visibility.Collapsed;
            return;
        }

        string? path = ArtworkCache.GetImagePath(ArtworkKey);
        if (string.IsNullOrEmpty(path) || !File.Exists(path))
        {
            CoverImage.Source = null;
            CoverImage.Visibility = Visibility.Collapsed;
            PlaceholderGlyph.Visibility = Visibility.Visible;
            return;
        }

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

            CoverImage.Source = bitmap;
            CoverImage.Visibility = Visibility.Visible;
            PlaceholderGlyph.Visibility = Visibility.Collapsed;
        }
        catch
        {
            CoverImage.Source = null;
            CoverImage.Visibility = Visibility.Collapsed;
            PlaceholderGlyph.Visibility = Visibility.Visible;
        }
    }
}
