using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;

namespace MusicoWindows.Theme;

/// <summary>
/// Enables authentic Windows 10 Acrylic / BlurBehind window composition using
/// SetWindowCompositionAttribute P/Invoke, with graceful fallback on unsupported builds.
/// </summary>
public static class AcrylicBackdrop
{
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

    /// <summary>
    /// Applies Windows 10 Acrylic blur (Win10 1803+) or standard BlurBehind fallback to the window.
    /// </summary>
    public static void Apply(Window window, bool isDark)
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

            // Windows 10 Acrylic tint in ABGR format (Alpha, Blue, Green, Red)
            // Dark: ~82% #181818, Light: ~82% #F2F2F2
            uint tintAbgr = isDark ? 0xD2181818u : 0xD2F2F2F2u;

            // Use Acrylic on Windows 10 1803 (build 17134) and later; fallback to BlurBehind earlier
            bool supportsAcrylic = Environment.OSVersion.Version.Major > 10 ||
                                   (Environment.OSVersion.Version.Major == 10 && Environment.OSVersion.Version.Build >= 17134);

            var accent = new AccentPolicy
            {
                AccentState = supportsAcrylic
                    ? AccentState.ACCENT_ENABLE_ACRYLICBLURBEHIND
                    : AccentState.ACCENT_ENABLE_BLURBEHIND,
                AccentFlags = 2,
                GradientColor = tintAbgr,
                AnimationId = 0
            };

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
        catch
        {
            // Silently ignore if DWM composition is unavailable (e.g., RDP or Basic theme)
        }
    }
}
