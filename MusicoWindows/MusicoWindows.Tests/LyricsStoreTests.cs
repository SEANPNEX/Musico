using MusicoWindows.Core;
using MusicoWindows.Models;
using Xunit;

namespace MusicoWindows.Tests;

public sealed class LyricsStoreTests : IDisposable
{
    private readonly string _musicDir;
    private readonly string _lyricsDir;
    private readonly LyricsStore _store;
    private readonly string _songPath;

    public LyricsStoreTests()
    {
        _musicDir = TestHelpers.MakeTempDir();
        _lyricsDir = Path.Combine(TestHelpers.MakeTempDir(), "Lyrics");
        _store = new LyricsStore(_lyricsDir);
        _songPath = Path.Combine(_musicDir, "song.mp3");
        File.WriteAllBytes(_songPath, []);
    }

    public void Dispose()
    {
        try { Directory.Delete(_musicDir, recursive: true); } catch { /* ignore */ }
    }

    private string WriteSidecar(string text, string fileName)
    {
        string path = Path.Combine(_musicDir, fileName);
        File.WriteAllText(path, text);
        return path;
    }

    [Fact]
    public void NothingAvailableReturnsNull()
    {
        Assert.Null(_store.Resolve("t", _songPath, null));
        Assert.Null(_store.Resolve("t", null, "  \n"));
    }

    [Fact]
    public void EmbeddedUsedWhenNothingElse()
    {
        var result = _store.Resolve("t", _songPath, "embedded words");
        Assert.NotNull(result);
        Assert.Equal(LyricsSource.Embedded, result!.Value.Source);
        Assert.Equal(["embedded words"], result.Value.Lyrics.Lines.Select(l => l.Text));
    }

    [Fact]
    public void SidecarBeatsEmbeddedAndLrcBeatsTxt()
    {
        WriteSidecar("txt words", "song.txt");
        Assert.Equal(["txt words"], _store.Resolve("t", _songPath, "e")!.Value.Lyrics.Lines.Select(l => l.Text));

        WriteSidecar("[00:01.00]lrc words", "song.lrc");
        var result = _store.Resolve("t", _songPath, "e");
        Assert.NotNull(result);
        Assert.Equal(LyricsSource.Sidecar, result!.Value.Source);
        Assert.Equal(["lrc words"], result.Value.Lyrics.Lines.Select(l => l.Text));
    }

    [Fact]
    public void ImportedBeatsSidecarAndRemoveFallsBack()
    {
        WriteSidecar("[00:01.00]sidecar", "song.lrc");
        string picked = WriteSidecar("[00:02.00]imported", "elsewhere.lrc");

        Assert.False(_store.HasImported("t"));
        _store.ImportLyrics(picked, "t");
        Assert.True(_store.HasImported("t"));

        var result = _store.Resolve("t", _songPath, null);
        Assert.NotNull(result);
        Assert.Equal(LyricsSource.Imported, result!.Value.Source);
        Assert.Equal(["imported"], result.Value.Lyrics.Lines.Select(l => l.Text));
        Assert.Null(_store.Resolve("other", null, null));

        _store.RemoveImported("t");
        Assert.False(_store.HasImported("t"));
        Assert.Equal(LyricsSource.Sidecar, _store.Resolve("t", _songPath, null)!.Value.Source);
    }

    [Fact]
    public void ImportRejectsUndecodableFile()
    {
        string badPath = Path.Combine(_musicDir, "bad.lrc");
        File.WriteAllBytes(badPath, [0xFF, 0xFE, 0xFD]);
        Assert.Throws<InvalidDataException>(() => _store.ImportLyrics(badPath, "t"));
        Assert.False(_store.HasImported("t"));
    }
}
