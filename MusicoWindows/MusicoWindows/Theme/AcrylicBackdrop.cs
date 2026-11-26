using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using MusicoWindows.Core;
using MusicoWindows.Models;

namespace MusicoWindows.Theme;

/// <summary>
/// Applies Windows 11 native DWM rounded corners / system backdrop (when opaque default)
/// and Windows 10 Acrylic / BlurBehind window composition via SetWindowCompositionAttribute
/// (for custom opacity or tinted translucency across Windows 10 and Windows 11).
/// </summary>
public static class AcrylicBackdrop
{
    private const int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private const int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
    private const int DWMWA_SYSTEMBACKDROP_TYPE = 38;

    private const int DWMWCP_ROUND = 2;

    private const int DWMSBT_NONE = 1;
    private const int DWMSBT_TRANSIENTWINDOW = 3;

    private enum AccentState
    {
        ACCENT_DISABLED = 0,
        ACCENT_ENABLE_GRADIENT = 1,
        ACCENT_ENABLE_TRANSPARENTGRADIENT = 2,
        ACCENT_ENABLE_BLURBEHIND = 3,
        ACCENT_ENABLE_ACRYLICBLURBEHIND = 4,
        ACCENT_INVALID_STATE = 5
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct AccentPolicy
    {
        public AccentState AccentState;
        public uint AccentFlags;
        public uint GradientColor;
        public int AnimationId;
    }

    private enum WindowCompositionAttribute
    {
        WCA_ACCENT_POLICY = 19
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct WindowCompositionAttributeData
    {
        public WindowCompositionAttribute Attribute;
        public IntPtr Data;
        public int SizeOfData;
    }

    [DllImport("user32.dll")]
    private static extern int SetWindowCompositionAttribute(IntPtr hwnd, ref WindowCompositionAttributeData data);

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int dwAttribute, ref int pvAttribute, int cbAttribute);

    /// <summary>
    /// Applies Windows 11 DWM attributes and Acrylic / BlurBehind composition to the window.
    /// </summary>
    public static void Apply(
        Window window,
        bool isDark,
        Color? tintBaseColor = null,
        double backgroundOpacity = 1.0,
        bool hasCustomBackgroundColor = false,
        bool hasBackgroundImage = false)
    {
        if (!OperatingSystem.IsWindows())
            return;

        try
        {
            var hwnd = new WindowInteropHelper(window).Handle;
            if (hwnd == IntPtr.Zero)
                return;

            var hwndSource = HwndSource.FromHwnd(hwnd);
            if (hwndSource?.CompositionTarget != null)
            {
                hwndSource.CompositionTarget.BackgroundColor = Colors.Transparent;
            }

            Version osVersion = Environment.OSVersion.Version;
            if (WindowsVersionHelper.IsWindows11(osVersion))
            {
                int darkMode = isDark ? 1 : 0;
                DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, ref darkMode, sizeof(int));

                int cornerPref = DWMWCP_ROUND;
                DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, ref cornerPref, sizeof(int));
            }

            WindowsBackdropKind kind = WindowsVersionHelper.ResolveBackdropKind(
                osVersion,
                backgroundOpacity,
                hasCustomBackgroundColor,
                hasBackgroundImage);

            if (kind == WindowsBackdropKind.DwmTransientWindow)
            {
                ApplyCompositionPolicy(hwnd, new AccentPolicy { AccentState = AccentState.ACCENT_DISABLED });
                int backdropType = DWMSBT_TRANSIENTWINDOW;
                DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, ref backdropType, sizeof(int));
                return;
            }

            if (WindowsVersionHelper.SupportsDwmSystemBackdrop(osVersion))
            {
                int noneBackdrop = DWMSBT_NONE;
                DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, ref noneBackdrop, sizeof(int));
            }

            Color resolvedTint = tintBaseColor ?? (isDark ? Color.FromRgb(0x18, 0x18, 0x18) : Color.FromRgb(0xF2, 0xF2, 0xF2));
            uint tintAbgr = ColorMath.ToAcrylicAbgr(
                new RgbColor(resolvedTint.R, resolvedTint.G, resolvedTint.B),
                backgroundOpacity);

            var accent = new AccentPolicy
            {
                AccentState = kind == WindowsBackdropKind.Acrylic
                    ? AccentState.ACCENT_ENABLE_ACRYLICBLURBEHIND
                    : AccentState.ACCENT_ENABLE_BLURBEHIND,
                AccentFlags = 2,
                GradientColor = tintAbgr,
                AnimationId = 0
            };

            ApplyCompositionPolicy(hwnd, accent);
        }
        catch
        {
            // Silently ignore if DWM composition is unavailable (e.g., RDP or Basic theme)
        }
    }

    private static void ApplyCompositionPolicy(IntPtr hwnd, AccentPolicy accent)
    {
        int size = Marshal.SizeOf(accent);
        IntPtr accentPtr = Marshal.AllocHGlobal(size);
        try
        {
            Marshal.StructureToPtr(accent, accentPtr, false);
            var data = new WindowCompositionAttributeData
            {
                Attribute = WindowCompositionAttribute.WCA_ACCENT_POLICY,
                SizeOfData = size,
                Data = accentPtr
            };
            SetWindowCompositionAttribute(hwnd, ref data);
        }
        finally
        {
            Marshal.FreeHGlobal(accentPtr);
        }
    }
}
