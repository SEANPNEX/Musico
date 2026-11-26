using System;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Effects;
using System.Windows.Media.Imaging;
using MusicoWindows.Core;

namespace MusicoWindows.Controls;

/// <summary>
/// Loads a blurred artwork wash for hero headers in album, artist, and playlist detail views.
/// </summary>
public static class HeroBackdropHelper
{
    public static void ApplyArtworkWash(Image target, ArtworkCache? cache, string? artworkKey, double blurRadius = 30.0)
    {
        if (target is null) return;

        string? path = !string.IsNullOrWhiteSpace(artworkKey) ? cache?.GetImagePath(artworkKey) : null;
        if (string.IsNullOrEmpty(path) || !File.Exists(path))
        {
            target.Source = null;
            target.Effect = null;
            target.Visibility = Visibility.Collapsed;
            return;
        }

        try
        {
            var bitmap = new BitmapImage();
            bitmap.BeginInit();
            bitmap.UriSource = new Uri(path, UriKind.Absolute);
            bitmap.CacheOption = BitmapCacheOption.OnLoad;
            bitmap.DecodePixelWidth = 240;
            bitmap.EndInit();
            bitmap.Freeze();

            target.Source = bitmap;
            target.Effect = new BlurEffect
            {
                Radius = blurRadius,
                KernelType = KernelType.Gaussian
            };
            target.Visibility = Visibility.Visible;
        }
        catch
        {
            target.Source = null;
            target.Effect = null;
            target.Visibility = Visibility.Collapsed;
        }
    }
}
