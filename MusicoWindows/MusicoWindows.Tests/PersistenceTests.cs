using MusicoWindows.Core;
using MusicoWindows.Models;
using Xunit;

namespace MusicoWindows.Tests;

public sealed class PersistenceTests
{
    [Fact]
    public void RoundTripPreservesTracks()
    {
        string path = Path.Combine(TestHelpers.MakeTempDir(), "nested", "x.json");
        var tracks = new[]
        {
            TestHelpers.MakeTrack("a.mp3"),
            TestHelpers.MakeTrack("日本語/b.flac")
        };

        Persistence.Save(tracks, path);
        var loaded = Persistence.Load<Track[]>(path);

        Assert.NotNull(loaded);
        Assert.Equal(tracks, loaded);
    }

    [Fact]
    public void MissingFileReturnsNull()
    {
        string path = Path.Combine(TestHelpers.MakeTempDir(), "nope.json");
        Assert.Null(Persistence.Load<Track[]>(path));
    }

    [Fact]
    public void CorruptFileIsRenamedToCorrupt()
    {
        string dir = TestHelpers.MakeTempDir();
        string path = Path.Combine(dir, "x.json");
        File.WriteAllText(path, "{");

        Assert.Null(Persistence.Load<Track[]>(path));
        Assert.False(File.Exists(path));
        Assert.True(File.Exists(path + ".corrupt"));
    }

    [Fact]
    public void TrackIdIsStableSha256Hex()
    {
        Guid root = Guid.NewGuid();
        string a = Track.MakeId(root, "a/b.mp3");
        Assert.Equal(a, Track.MakeId(root, "a/b.mp3"));
        Assert.Equal(64, a.Length);
        Assert.NotEqual(a, Track.MakeId(root, "a/c.mp3"));
    }

    [Fact]
    public void EqPresetsMatchMusicoSpec()
    {
        Assert.Equal(
            ["Flat", "Bass Boost", "Treble Boost", "Rock", "Pop", "Jazz", "Classical", "Vocal"],
            EqPreset.All.Select(p => p.Name));
        Assert.All(EqPreset.All, p => Assert.Equal(EqSettings.Frequencies.Length, p.Gains.Length));
        Assert.Equal(10, EqSettings.Frequencies.Length);
    }

    [Fact]
    public void ResolveAppDataDirectoryUsesLocalAppDataByDefault()
    {
        string baseDir = TestHelpers.MakeTempDir();
        string localAppData = TestHelpers.MakeTempDir();

        string resolved = Persistence.ResolveAppDataDirectory(baseDir, localAppData);

        Assert.Equal(Path.Combine(localAppData, "Musico"), resolved);
    }

    [Fact]
    public void ResolveAppDataDirectoryUsesPortableDataWhenMarkerExists()
    {
        string baseDir = TestHelpers.MakeTempDir();
        string localAppData = TestHelpers.MakeTempDir();
        File.WriteAllText(Path.Combine(baseDir, "portable.txt"), "portable");

        string resolved = Persistence.ResolveAppDataDirectory(baseDir, localAppData);

        Assert.Equal(Path.Combine(baseDir, "Data"), resolved);
    }
}
