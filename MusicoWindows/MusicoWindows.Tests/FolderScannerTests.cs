using System.Text;
using MusicoWindows.Core;
using Xunit;

namespace MusicoWindows.Tests;

public sealed class FolderScannerTests
{
    [Fact]
    public void FindsNestedAudioOnly()
    {
        string root = TestHelpers.MakeTempDir();
        foreach (string p in (string[])["a.mp3", "sub/b.FLAC", "sub/deep/c.m4a", "notes.txt", ".hidden.mp3", "sub/cover.jpg"])
        {
            TestHelpers.TouchFile(root, p);
        }

        var found = FolderScanner.Scan(root);
        Assert.NotNull(found);
        Assert.Equal(["a.mp3", "sub/b.FLAC", "sub/deep/c.m4a"], found!.Select(f => f.RelativePath));
        Assert.Equal(1, found![0].FileSize);
    }

    [Fact]
    public void UnicodeAndSpecialNamesRoundTrip()
    {
        string root = TestHelpers.MakeTempDir();
        const string relPath = "日本語/曲 [live] #1 100%.mp3";
        TestHelpers.TouchFile(root, relPath);

        var found = FolderScanner.Scan(root);
        Assert.NotNull(found);
        Assert.Equal([relPath], found!.Select(f => f.RelativePath));
        string combined = FolderScanner.CombinePath(root, found![0].RelativePath);
        Assert.True(File.Exists(combined));
    }

    [Fact]
    public void DecomposedUnicodeNameIsNormalisedToNfc()
    {
        string root = TestHelpers.MakeTempDir();
        const string decomposed = "Cafe\u0301.mp3";
        TestHelpers.TouchFile(root, decomposed);

        var found = FolderScanner.Scan(root);
        Assert.NotNull(found);
        string expected = "Café.mp3".Normalize(NormalizationForm.FormC);
        Assert.Equal([expected], found!.Select(f => f.RelativePath));
    }

    [Fact]
    public void EmptyRootReturnsEmptyList()
    {
        string root = TestHelpers.MakeTempDir();
        var found = FolderScanner.Scan(root);
        Assert.NotNull(found);
        Assert.Empty(found!);
    }

    [Fact]
    public void MissingRootReturnsNullNotEmpty()
    {
        string missing = Path.Combine(TestHelpers.MakeTempDir(), "missing");
        Assert.Null(FolderScanner.Scan(missing));
    }
}
