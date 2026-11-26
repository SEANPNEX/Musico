using System;
using MusicoWindows.Models;

namespace MusicoWindows.Core;

public enum WindowsBackdropKind
{
    BlurBehind,
    Acrylic,
    DwmTransientWindow
}

/// <summary>
/// Pure-C# Windows build and backdrop capability detection helpers.
/// </summary>
public static class WindowsVersionHelper
{
    public const int Win10AcrylicMinBuild = 17134;
    public const int Win11MinBuild = 22000;
    public const int Win11BackdropMinBuild = 22621;

    public static bool IsWindows11(Version osVersion) =>
        osVersion is not null &&
        (osVersion.Major > 10 || (osVersion.Major == 10 && osVersion.Build >= Win11MinBuild));

    public static bool SupportsDwmSystemBackdrop(Version osVersion) =>
        osVersion is not null &&
        (osVersion.Major > 10 || (osVersion.Major == 10 && osVersion.Build >= Win11BackdropMinBuild));

    public static bool SupportsWin10Acrylic(Version osVersion) =>
        osVersion is not null &&
        (osVersion.Major > 10 || (osVersion.Major == 10 && osVersion.Build >= Win10AcrylicMinBuild));

    public static WindowsBackdropKind ResolveBackdropKind(
        Version osVersion,
        double backgroundOpacity,
        bool hasCustomBackgroundColor,
        bool hasBackgroundImage,
        double? sidebarOpacity = null,
        bool hasCustomSidebarColor = false)
    {
        double clampedOpacity = AppearanceSettings.ClampBackgroundOpacity(backgroundOpacity);
        double clampedSidebarOpacity = sidebarOpacity.HasValue
            ? AppearanceSettings.ClampSidebarOpacity(sidebarOpacity.Value)
            : clampedOpacity;
        double effectiveMinOpacity = Math.Min(clampedOpacity, clampedSidebarOpacity);

        if (SupportsDwmSystemBackdrop(osVersion) &&
            effectiveMinOpacity >= 0.995 &&
            !hasCustomBackgroundColor &&
            !hasBackgroundImage &&
            !hasCustomSidebarColor)
        {
            return WindowsBackdropKind.DwmTransientWindow;
        }

        return SupportsWin10Acrylic(osVersion)
            ? WindowsBackdropKind.Acrylic
            : WindowsBackdropKind.BlurBehind;
    }
}
