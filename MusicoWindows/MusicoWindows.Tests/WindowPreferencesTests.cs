using MusicoWindows.Core;
using Xunit;

namespace MusicoWindows.Tests;

public sealed class WindowPreferencesTests
{
    [Fact]
    public void DefaultsToExitWithoutCreatingState()
    {
        string dir = Path.Combine(TestHelpers.MakeTempDir(), "state");

        var preferences = new WindowPreferences(dir, "1.2.1");

        Assert.Equal(WindowCloseBehavior.Exit, preferences.Behavior);
        Assert.True(preferences.RequiresConfirmation);
        Assert.False(Directory.Exists(dir));
    }

    [Theory]
    [InlineData(WindowCloseBehavior.Exit)]
    [InlineData(WindowCloseBehavior.MinimizeToTray)]
    public void ConfirmedChoiceSurvivesReload(WindowCloseBehavior behavior)
    {
        string dir = Path.Combine(TestHelpers.MakeTempDir(), "state");
        var preferences = new WindowPreferences(dir, "1.2.1");

        preferences.Save(behavior, confirmVersion: true);

        Assert.Equal(behavior, preferences.Behavior);
        Assert.False(preferences.RequiresConfirmation);
        Assert.True(File.Exists(Path.Combine(dir, "window_prefs.json")));
        var reloaded = new WindowPreferences(dir, "1.2.1");
        Assert.Equal(behavior, reloaded.Behavior);
        Assert.False(reloaded.RequiresConfirmation);
    }

    [Theory]
    [InlineData("1.2.2")]
    [InlineData("1.2.0")]
    [InlineData("1.2.1-beta")]
    [InlineData("1.2.1.1")]
    public void VersionChangePreservesChoiceButRequiresConfirmation(string version)
    {
        string dir = TestHelpers.MakeTempDir();
        new WindowPreferences(dir, "1.2.1").Save(WindowCloseBehavior.MinimizeToTray, confirmVersion: true);

        var preferences = new WindowPreferences(dir, version);

        Assert.Equal(WindowCloseBehavior.MinimizeToTray, preferences.Behavior);
        Assert.True(preferences.RequiresConfirmation);
        preferences.Save(WindowCloseBehavior.Exit, confirmVersion: true);
        Assert.False(new WindowPreferences(dir, version).RequiresConfirmation);
        Assert.True(new WindowPreferences(dir, "1.2.1").RequiresConfirmation);
    }

    [Fact]
    public void SettingsBeforeFirstConfirmationDoesNotConfirmVersion()
    {
        string dir = TestHelpers.MakeTempDir();
        var preferences = new WindowPreferences(dir, "1.2.1");

        preferences.Save(WindowCloseBehavior.MinimizeToTray, confirmVersion: false);

        Assert.Equal(WindowCloseBehavior.MinimizeToTray, preferences.Behavior);
        Assert.True(preferences.RequiresConfirmation);
        var reloaded = new WindowPreferences(dir, "1.2.1");
        Assert.Equal(WindowCloseBehavior.MinimizeToTray, reloaded.Behavior);
        Assert.True(reloaded.RequiresConfirmation);
    }

    [Fact]
    public void SettingsAfterConfirmationPreservesConfirmedVersion()
    {
        string dir = TestHelpers.MakeTempDir();
        var preferences = new WindowPreferences(dir, "1.2.1");
        preferences.Save(WindowCloseBehavior.Exit, confirmVersion: true);

        preferences.Save(WindowCloseBehavior.MinimizeToTray, confirmVersion: false);

        Assert.False(preferences.RequiresConfirmation);
        var reloaded = new WindowPreferences(dir, "1.2.1");
        Assert.Equal(WindowCloseBehavior.MinimizeToTray, reloaded.Behavior);
        Assert.False(reloaded.RequiresConfirmation);
    }

    [Fact]
    public void SettingsOnNewVersionPreservesPreviousConfirmation()
    {
        string dir = TestHelpers.MakeTempDir();
        new WindowPreferences(dir, "1.2.1").Save(WindowCloseBehavior.Exit, confirmVersion: true);
        var preferences = new WindowPreferences(dir, "1.2.2");

        preferences.Save(WindowCloseBehavior.MinimizeToTray, confirmVersion: false);

        Assert.True(preferences.RequiresConfirmation);
        Assert.True(new WindowPreferences(dir, "1.2.2").RequiresConfirmation);
        var previousVersion = new WindowPreferences(dir, "1.2.1");
        Assert.Equal(WindowCloseBehavior.MinimizeToTray, previousVersion.Behavior);
        Assert.False(previousVersion.RequiresConfirmation);
    }

    [Fact]
    public void CancelWithoutSaveLeavesPersistedChoiceAndConfirmationUnchanged()
    {
        string dir = TestHelpers.MakeTempDir();
        new WindowPreferences(dir, "1.2.1").Save(WindowCloseBehavior.MinimizeToTray, confirmVersion: true);
        string path = Path.Combine(dir, "window_prefs.json");
        byte[] before = File.ReadAllBytes(path);

        var preferences = new WindowPreferences(dir, "1.2.2");

        Assert.True(preferences.RequiresConfirmation);
        Assert.Equal(before, File.ReadAllBytes(path));
        var reloaded = new WindowPreferences(dir, "1.2.2");
        Assert.Equal(WindowCloseBehavior.MinimizeToTray, reloaded.Behavior);
        Assert.True(reloaded.RequiresConfirmation);
        Assert.False(new WindowPreferences(dir, "1.2.1").RequiresConfirmation);
    }

    [Theory]
    [InlineData("{")]
    [InlineData("null")]
    [InlineData("[]")]
    [InlineData("{}")]
    [InlineData("{\"lastConfirmedVersion\":\"1.2.1\"}")]
    [InlineData("{\"behavior\":null,\"lastConfirmedVersion\":\"1.2.1\"}")]
    [InlineData("{\"behavior\":\"Unknown\",\"lastConfirmedVersion\":\"1.2.1\"}")]
    [InlineData("{\"behavior\":99,\"lastConfirmedVersion\":\"1.2.1\"}")]
    [InlineData("{\"behavior\":-1,\"lastConfirmedVersion\":\"1.2.1\"}")]
    [InlineData("{\"behavior\":true,\"lastConfirmedVersion\":\"1.2.1\"}")]
    public void InvalidPreferencesFallBackToUnconfirmedExit(string json)
    {
        string dir = TestHelpers.MakeTempDir();
        File.WriteAllText(Path.Combine(dir, "window_prefs.json"), json);

        var preferences = new WindowPreferences(dir, "1.2.1");

        Assert.Equal(WindowCloseBehavior.Exit, preferences.Behavior);
        Assert.True(preferences.RequiresConfirmation);
        preferences.Save(WindowCloseBehavior.Exit, confirmVersion: false);
        Assert.True(new WindowPreferences(dir, "1.2.1").RequiresConfirmation);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("invalid")]
    public void InvalidConfirmedVersionPreservesValidBehavior(string? version)
    {
        string dir = TestHelpers.MakeTempDir();
        Persistence.Save(new { Behavior = WindowCloseBehavior.MinimizeToTray, LastConfirmedVersion = version },
            Path.Combine(dir, "window_prefs.json"));

        var preferences = new WindowPreferences(dir, "1.2.1");

        Assert.Equal(WindowCloseBehavior.MinimizeToTray, preferences.Behavior);
        Assert.True(preferences.RequiresConfirmation);
    }

    [Fact]
    public void UnreadablePreferencesFallBackSafely()
    {
        string dir = TestHelpers.MakeTempDir();
        new WindowPreferences(dir, "1.2.1").Save(WindowCloseBehavior.MinimizeToTray, confirmVersion: true);
        using var locked = new FileStream(Path.Combine(dir, "window_prefs.json"),
            FileMode.Open, FileAccess.ReadWrite, FileShare.None);

        var preferences = new WindowPreferences(dir, "1.2.1");

        Assert.Equal(WindowCloseBehavior.Exit, preferences.Behavior);
        Assert.True(preferences.RequiresConfirmation);
    }

    [Theory]
    [InlineData(false, false)]
    [InlineData(false, true)]
    [InlineData(true, false)]
    [InlineData(true, true)]
    public void FailedSaveLeavesMemoryAndPersistedStateUnchanged(bool initiallyConfirmed, bool confirmVersion)
    {
        string dir = Path.Combine(TestHelpers.MakeTempDir(), "state");
        var preferences = new WindowPreferences(dir, "1.2.1");
        preferences.Save(WindowCloseBehavior.MinimizeToTray, confirmVersion: initiallyConfirmed);
        string path = Path.Combine(dir, "window_prefs.json");
        byte[] before = File.ReadAllBytes(path);
        Directory.Move(dir, dir + ".previous");
        File.WriteAllText(dir, "A file blocks creation of the state directory.");

        Assert.ThrowsAny<IOException>(() => preferences.Save(WindowCloseBehavior.Exit, confirmVersion));

        Assert.Equal(WindowCloseBehavior.MinimizeToTray, preferences.Behavior);
        Assert.Equal(!initiallyConfirmed, preferences.RequiresConfirmation);
        File.Delete(dir);
        Directory.Move(dir + ".previous", dir);
        Assert.Equal(before, File.ReadAllBytes(path));
        Assert.Empty(Directory.GetFiles(dir, "*.tmp"));
    }

    [Fact]
    public void InvalidBehaviorCannotBeSaved()
    {
        string dir = TestHelpers.MakeTempDir();
        var preferences = new WindowPreferences(dir, "1.2.1");

        Assert.Throws<ArgumentOutOfRangeException>(() => preferences.Save((WindowCloseBehavior)99, confirmVersion: true));

        Assert.Equal(WindowCloseBehavior.Exit, preferences.Behavior);
        Assert.True(preferences.RequiresConfirmation);
        Assert.False(File.Exists(Path.Combine(dir, "window_prefs.json")));
    }

    [Fact]
    public void EquivalentVersionsAreNormalized()
    {
        string dir = TestHelpers.MakeTempDir();
        new WindowPreferences(dir, "v.1.2.1.0+build").Save(WindowCloseBehavior.Exit, confirmVersion: true);

        Assert.False(new WindowPreferences(dir, "1.2.1").RequiresConfirmation);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("invalid")]
    public void InvalidCurrentVersionIsRejected(string? version)
    {
        string dir = TestHelpers.MakeTempDir();

        Assert.Throws<ArgumentException>(() => new WindowPreferences(dir, version!));
        Assert.False(File.Exists(Path.Combine(dir, "window_prefs.json")));
    }
}
