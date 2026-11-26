using System;
using System.Collections.Concurrent;
using System.Globalization;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using MusicoWindows.Core;

namespace MusicoWindows.Controls;

public partial class ArtistAvatar : UserControl
{
    private static readonly ConcurrentDictionary<string, BitmapImage> AvatarImageCache = new(StringComparer.Ordinal);

    public ArtistAvatar()
    {
        InitializeComponent();
        SizeChanged += (_, _) => UpdateMonogramFontSize();
    }

    public void SetSource(ArtworkCache? cache, string? artworkKey, string displayArtist, int decodeSize = 96)
    {
        UpdateMonogramFontSize();

        int bucket = decodeSize <= 96 ? 96 : decodeSize <= 240 ? 240 : 600;
        if (!string.IsNullOrWhiteSpace(artworkKey) && cache is not null)
        {
            string cacheKey = $"{artworkKey}:{bucket}";
            if (AvatarImageCache.TryGetValue(cacheKey, out var cached))
            {
                ApplyBitmap(cached);
                return;
            }

            string? path = cache.GetImagePath(artworkKey);
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

                    if (AvatarImageCache.Count > 500)
                    {
                        AvatarImageCache.Clear();
                    }
                    AvatarImageCache.TryAdd(cacheKey, bitmap);
                    ApplyBitmap(bitmap);
                    return;
                }
                catch
                {
                    // Fall back to monogram badge if image decoding fails.
                }
            }
        }

        ApplyMonogram(displayArtist);
    }

    private void ApplyBitmap(BitmapImage bitmap)
    {
        var brush = new ImageBrush(bitmap)
        {
            Stretch = Stretch.UniformToFill
        };
        brush.Freeze();
        AvatarEllipse.Fill = brush;
        AvatarEllipse.SetResourceReference(System.Windows.Shapes.Shape.StrokeProperty, "ControlBorderBrush");
        MonogramText.Visibility = Visibility.Collapsed;
    }

    private void ApplyMonogram(string displayArtist)
    {
        AvatarEllipse.SetResourceReference(System.Windows.Shapes.Shape.FillProperty, "AccentSubtleBrush");
        AvatarEllipse.SetResourceReference(System.Windows.Shapes.Shape.StrokeProperty, "AccentBrush");
        MonogramText.Text = ExtractMonogram(displayArtist);
        MonogramText.Visibility = Visibility.Visible;
    }

    private void UpdateMonogramFontSize()
    {
        double basis = !double.IsNaN(Width) && Width > 0 ? Width : ActualWidth;
        if (basis > 0)
        {
            MonogramText.FontSize = Math.Max(11, basis * 0.40);
        }
    }

    private static string ExtractMonogram(string displayArtist)
    {
        if (string.IsNullOrWhiteSpace(displayArtist)) return "?";
        string trimmed = displayArtist.Trim();
        var enumerator = StringInfo.GetTextElementEnumerator(trimmed);
        if (enumerator.MoveNext())
        {
            return enumerator.GetTextElement().ToUpperInvariant();
        }
        return "?";
    }
}
