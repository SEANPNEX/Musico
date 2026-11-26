using MusicoWindows.Core;
using Xunit;

namespace MusicoWindows.Tests;

public sealed class WindowStateStoreTests
{
    [Fact]
    public void MissingOrCorruptFileReturnsNormalizedDefaults()
    {
        string dir = TestHelpers.MakeTempDir();
        var store = new WindowStateStore(dir);

        WindowStateData defaults = store.Load();
        Assert.Equal(WindowStateData.DefaultWidth, defaults.Width);
        Assert.Equal(WindowStateData.DefaultHeight, defaults.Height);
        Assert.Null(defaults.Left);
        Assert.Null(defaults.Top);
        Assert.False(defaults.IsMaximized);
        Assert.True(defaults.NavExpanded);
        Assert.Null(defaults.SidePanel);
        Assert.Equal("queues", defaults.LastRoute);

        File.WriteAllText(store.FilePath, "{ not valid json");
        WindowStateData recovered = store.Load();
        Assert.Equal("queues", recovered.LastRoute);
        Assert.True(File.Exists(store.FilePath + ".corrupt"));
    }

    [Fact]
    public void SaveAndLoadRoundTripAndNormalizeInvalidFields()
    {
        string dir = TestHelpers.MakeTempDir();
        var store = new WindowStateStore(dir);

        var saved = store.Save(new WindowStateData
        {
            Width = 1440,
            Height = 900,
            Left = 120,
            Top = 80,
            IsMaximized = true,
            NavExpanded = false,
            SidePanel = "LYRICS",
            LastRoute = "ALBUMS"
        });

        Assert.Equal(1440, saved.Width);
        Assert.Equal(900, saved.Height);
        Assert.Equal(120, saved.Left);
        Assert.Equal(80, saved.Top);
        Assert.True(saved.IsMaximized);
        Assert.False(saved.NavExpanded);
        Assert.Equal("lyrics", saved.SidePanel);
        Assert.Equal("albums", saved.LastRoute);

        WindowStateData reloaded = store.Load();
        Assert.Equal(saved, reloaded);

        // Invalid route/side-panel and tiny dimensions normalize safely
        var invalid = store.Save(new WindowStateData
        {
            Width = 200,
            Height = double.NaN,
            Left = double.PositiveInfinity,
            Top = -50,
            SidePanel = "unknown",
            LastRoute = "invalid-route"
        });
        Assert.Equal(WindowStateData.MinWidth, invalid.Width);
        Assert.Equal(WindowStateData.DefaultHeight, invalid.Height);
        Assert.Null(invalid.Left);
        Assert.Equal(-50, invalid.Top);
        Assert.Null(invalid.SidePanel);
        Assert.Equal("queues", invalid.LastRoute);
    }

    [Fact]
    public void ClampToBoundsKeepsWindowsInsideVirtualScreen()
    {
        // Null position centers within screen
        var centered = WindowStateStore.ClampToBounds(
            null, null, 1180, 760,
            screenLeft: 0, screenTop: 0, screenWidth: 1920, screenHeight: 1080);
        Assert.Equal(370, centered.Left);
        Assert.Equal(160, centered.Top);
        Assert.Equal(1180, centered.Width);
        Assert.Equal(760, centered.Height);

        // Off-screen negative or far-right coordinates clamp back into screen
        var clampedOffscreen = WindowStateStore.ClampToBounds(
            -500, 2000, 1200, 800,
            screenLeft: 0, screenTop: 0, screenWidth: 1920, screenHeight: 1080);
        Assert.Equal(0, clampedOffscreen.Left);
        Assert.Equal(280, clampedOffscreen.Top);

        // Multi-monitor negative screenLeft is respected
        var multiMonitor = WindowStateStore.ClampToBounds(
            -1800, 100, 1000, 700,
            screenLeft: -1920, screenTop: 0, screenWidth: 3840, screenHeight: 1080);
        Assert.Equal(-1800, multiMonitor.Left);
        Assert.Equal(100, multiMonitor.Top);

        // Oversized saved window shrinks to screen bounds
        var oversized = WindowStateStore.ClampToBounds(
            100, 100, 3000, 2000,
            screenLeft: 0, screenTop: 0, screenWidth: 1366, screenHeight: 768);
        Assert.Equal(1366, oversized.Width);
        Assert.Equal(768, oversized.Height);
        Assert.Equal(0, oversized.Left);
        Assert.Equal(0, oversized.Top);
    }
}
