using System;
using System.Collections.Generic;
using System.IO;

namespace MusicoWindows.Core;

public sealed record WindowBounds(double Left, double Top, double Width, double Height);

public sealed record WindowStateData
{
    public const double DefaultWidth = 1180;
    public const double DefaultHeight = 760;
    public const double MinWidth = 820;
    public const double MinHeight = 560;

    public double Width { get; init; } = DefaultWidth;
    public double Height { get; init; } = DefaultHeight;
    public double? Left { get; init; }
    public double? Top { get; init; }
    public bool IsMaximized { get; init; }
    public bool NavExpanded { get; init; } = true;
    public string? SidePanel { get; init; }
    public string LastRoute { get; init; } = "queues";

    private static readonly HashSet<string> ValidRoutes = new(StringComparer.OrdinalIgnoreCase)
    {
        "folders",
        "songs",
        "albums",
        "artists",
        "playlists",
        "favourites",
        "queues",
        "stats",
        "extensions",
        "settings"
    };

    public WindowStateData Normalize()
    {
        double w = double.IsFinite(Width) && Width > 0 ? Math.Max(Width, MinWidth) : DefaultWidth;
        double h = double.IsFinite(Height) && Height > 0 ? Math.Max(Height, MinHeight) : DefaultHeight;
        double? left = Left is double l && double.IsFinite(l) ? l : null;
        double? top = Top is double t && double.IsFinite(t) ? t : null;

        string? sidePanel = SidePanel?.Trim().ToLowerInvariant() switch
        {
            "lyrics" => "lyrics",
            "queue" => "queue",
            _ => null
        };

        string route = !string.IsNullOrWhiteSpace(LastRoute) && ValidRoutes.Contains(LastRoute.Trim())
            ? LastRoute.Trim().ToLowerInvariant()
            : "queues";

        return new WindowStateData
        {
            Width = w,
            Height = h,
            Left = left,
            Top = top,
            IsMaximized = IsMaximized,
            NavExpanded = NavExpanded,
            SidePanel = sidePanel,
            LastRoute = route
        };
    }
}

/// <summary>
/// Persists main window geometry and shell layout state in <c>window-state.json</c>.
/// Pure C# (no WPF dependencies) so bounds clamping and serialization are unit-tested cross-platform.
/// </summary>
public sealed class WindowStateStore
{
    public string FilePath { get; }

    public WindowStateStore(string stateDirectory)
    {
        FilePath = Path.Combine(stateDirectory, "window-state.json");
    }

    public WindowStateData Load()
    {
        var loaded = Persistence.Load<WindowStateData>(FilePath);
        return (loaded ?? new WindowStateData()).Normalize();
    }

    public WindowStateData Save(WindowStateData state)
    {
        var normalized = (state ?? new WindowStateData()).Normalize();
        try
        {
            Persistence.Save(normalized, FilePath);
        }
        catch
        {
            // Best-effort persistence on close.
        }
        return normalized;
    }

    /// <summary>
    /// Clamps persisted window bounds into the virtual screen work area so windows never restore off-screen.
    /// </summary>
    public static WindowBounds ClampToBounds(
        double? left,
        double? top,
        double width,
        double height,
        double screenLeft,
        double screenTop,
        double screenWidth,
        double screenHeight,
        double minWidth = WindowStateData.MinWidth,
        double minHeight = WindowStateData.MinHeight)
    {
        double safeScreenWidth = double.IsFinite(screenWidth) && screenWidth > 0 ? screenWidth : WindowStateData.DefaultWidth;
        double safeScreenHeight = double.IsFinite(screenHeight) && screenHeight > 0 ? screenHeight : WindowStateData.DefaultHeight;
        double safeScreenLeft = double.IsFinite(screenLeft) ? screenLeft : 0;
        double safeScreenTop = double.IsFinite(screenTop) ? screenTop : 0;

        double effectiveMinW = Math.Min(Math.Max(100, minWidth), safeScreenWidth);
        double effectiveMinH = Math.Min(Math.Max(100, minHeight), safeScreenHeight);

        double rawW = double.IsFinite(width) && width > 0 ? width : WindowStateData.DefaultWidth;
        double rawH = double.IsFinite(height) && height > 0 ? height : WindowStateData.DefaultHeight;

        double clampedW = Math.Clamp(rawW, effectiveMinW, safeScreenWidth);
        double clampedH = Math.Clamp(rawH, effectiveMinH, safeScreenHeight);

        double maxLeft = safeScreenLeft + Math.Max(0, safeScreenWidth - clampedW);
        double maxTop = safeScreenTop + Math.Max(0, safeScreenHeight - clampedH);

        double clampedLeft = left is double l && double.IsFinite(l)
            ? Math.Clamp(l, safeScreenLeft, maxLeft)
            : safeScreenLeft + Math.Max(0, (safeScreenWidth - clampedW) * 0.5);

        double clampedTop = top is double t && double.IsFinite(t)
            ? Math.Clamp(t, safeScreenTop, maxTop)
            : safeScreenTop + Math.Max(0, (safeScreenHeight - clampedH) * 0.5);

        return new WindowBounds(clampedLeft, clampedTop, clampedW, clampedH);
    }
}
