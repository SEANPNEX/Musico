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

    [Fact]
    public void FolderMatchNormalizesTrackNumbersNoiseDiacriticsAndPreservesJapaneseKana()
    {
        string cafeSong = Path.Combine(_musicDir, "01 - Café Del Mar (Official Audio).mp3");
        File.WriteAllBytes(cafeSong, []);
        WriteSidecar("[00:05.00]Sunrise on the beach", "Artist - Cafe del Mar [FLAC].lrc");

        var resolved = _store.Resolve("cafe", cafeSong, "embedded fallback", "Café Del Mar", "Artist");
        Assert.NotNull(resolved);
        Assert.Equal(LyricsSource.FolderMatch, resolved!.Value.Source);
        Assert.Equal(["Sunrise on the beach"], resolved.Value.Lyrics.Lines.Select(l => l.Text));

        string jpSong = Path.Combine(_musicDir, "かがみ.mp3");
        File.WriteAllBytes(jpSong, []);
        WriteSidecar("[00:01.00]wrong kana", "かかみ.lrc");
        Assert.Null(_store.Resolve("jp", jpSong, null, "かがみ", ""));

        WriteSidecar("[00:02.00]exact kana", "01. かがみ.lrc");
        var jpResolved = _store.Resolve("jp", jpSong, null, "かがみ", "");
        Assert.NotNull(jpResolved);
        Assert.Equal(LyricsSource.FolderMatch, jpResolved!.Value.Source);
        Assert.Equal(["exact kana"], jpResolved.Value.Lyrics.Lines.Select(l => l.Text));

        string yearSong = Path.Combine(_musicDir, "1989.mp3");
        File.WriteAllBytes(yearSong, []);
        WriteSidecar("[00:03.00]welcome to 1989", "01 - 1989.lrc");
        var yearResolved = _store.Resolve("yr", yearSong, null, "1989", "Taylor");
        Assert.NotNull(yearResolved);
        Assert.Equal(LyricsSource.FolderMatch, yearResolved!.Value.Source);
        Assert.Equal(["welcome to 1989"], yearResolved.Value.Lyrics.Lines.Select(l => l.Text));
    }

    [Fact]
    public void FolderMatchSkipsGenericAndSubfolderAndPrefersTimedLrcOverTxt()
    {
        string trackPath = Path.Combine(_musicDir, "Nightcall.mp3");
        File.WriteAllBytes(trackPath, []);
        string subDir = Path.Combine(_musicDir, "Subfolder");
        Directory.CreateDirectory(subDir);
        File.WriteAllText(Path.Combine(subDir, "Nightcall.lrc"), "[00:01.00]sub");
        WriteSidecar("[00:01.00]generic", "lyrics.lrc");
        WriteSidecar("readme", "readme.txt");

        var embeddedOnly = _store.Resolve("nc", trackPath, "tag line", "Nightcall", "Kavinsky");
        Assert.Equal(LyricsSource.Embedded, embeddedOnly!.Value.Source);

        string genericSong = Path.Combine(_musicDir, "lyrics.mp3");
        File.WriteAllBytes(genericSong, []);
        Assert.Equal(LyricsSource.Sidecar, _store.Resolve("gen", genericSong, null)!.Value.Source);

        WriteSidecar("plain txt", "Kavinsky - Nightcall.txt");
        WriteSidecar("[00:04.00]timed lrc", "Kavinsky - Nightcall.lrc");
        var matched = _store.Resolve("nc", trackPath, "tag line", "Nightcall", "Kavinsky");
        Assert.Equal(LyricsSource.FolderMatch, matched!.Value.Source);
        Assert.Equal(["timed lrc"], matched.Value.Lyrics.Lines.Select(l => l.Text));
    }

    [Fact]
    public void SaveImportedExportSidecarAndLyricsChangedEventWork()
    {
        var changedIds = new List<string>();
        _store.LyricsChanged += id => changedIds.Add(id);

        Assert.True(_store.CanExportSidecar(_songPath));
        const string lrc = "[00:10.00]Cloud line";
        _store.SaveImportedLyrics(lrc, "t");
        Assert.True(_store.HasImported("t"));
        Assert.Equal(lrc, _store.ReadRawImportedText("t"));

        _store.ExportSidecarLyrics(lrc, _songPath);
        string sidecarPath = Path.ChangeExtension(_songPath, ".lrc");
        Assert.Equal(lrc, File.ReadAllText(sidecarPath));

        _store.RemoveImported("t");
        Assert.Equal(["t", "t"], changedIds);
    }
}
