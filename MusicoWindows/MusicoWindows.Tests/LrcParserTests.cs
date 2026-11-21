using System.Text;
using MusicoWindows.Core;
using Xunit;

namespace MusicoWindows.Tests;

public sealed class LrcParserTests
{
    [Fact]
    public void ParsesTimestampFormats()
    {
        var lyrics = LrcParser.Parse("[00:12.50]Hello\n[01:02]World\n[01:02.345]Milli");
        Assert.True(lyrics.IsTimed);
        Assert.Equal(["Hello", "World", "Milli"], lyrics.Lines.Select(l => l.Text));
        Assert.Equal(12.5, lyrics.Lines[0].TimeSeconds!.Value, precision: 3);
        Assert.Equal(62.0, lyrics.Lines[1].TimeSeconds!.Value, precision: 3);
        Assert.Equal(62.345, lyrics.Lines[2].TimeSeconds!.Value, precision: 3);
    }

    [Fact]
    public void MultipleTimestampsOnOneLineAndSorting()
    {
        var lyrics = LrcParser.Parse("[00:30.00]Verse\n[00:10.00][00:40.00]Chorus");
        Assert.Equal(["Chorus", "Verse", "Chorus"], lyrics.Lines.Select(l => l.Text));
        Assert.Equal([10.0, 30.0, 40.0], lyrics.Lines.Select(l => l.TimeSeconds!.Value));
        Assert.Equal([0, 1, 2], lyrics.Lines.Select(l => l.Id));
    }

    [Fact]
    public void MetadataTagsIgnored()
    {
        var lyrics = LrcParser.Parse("[ar:Someone]\n[ti:Song]\n[offset:500]\n[00:01.00]Line");
        Assert.Equal(["Line"], lyrics.Lines.Select(l => l.Text));
    }

    [Fact]
    public void CrlfLineEndings()
    {
        var lyrics = LrcParser.Parse("[00:01.00]One\r\n[00:02.00]Two\r\n");
        Assert.Equal(["One", "Two"], lyrics.Lines.Select(l => l.Text));
    }

    [Fact]
    public void TimedLineWithEmptyTextIsKeptAsGap()
    {
        var lyrics = LrcParser.Parse("[00:01.00]One\n[00:05.00]\n[00:09.00]Two");
        Assert.Equal(["One", "", "Two"], lyrics.Lines.Select(l => l.Text));
    }

    [Fact]
    public void PlainTextIsUntimed()
    {
        var lyrics = LrcParser.Parse("First line\n\nSecond line\n");
        Assert.False(lyrics.IsTimed);
        Assert.Equal(["First line", "Second line"], lyrics.Lines.Select(l => l.Text));
        Assert.Null(lyrics.Lines[0].TimeSeconds);
        Assert.Null(lyrics.LineIndexAt(10));
    }

    [Fact]
    public void LineIndexAtTime()
    {
        var lyrics = LrcParser.Parse("[00:10.00]A\n[00:20.00]B");
        Assert.Null(lyrics.LineIndexAt(5));
        Assert.Equal(0, lyrics.LineIndexAt(10));
        Assert.Equal(0, lyrics.LineIndexAt(19.9));
        Assert.Equal(1, lyrics.LineIndexAt(500));
    }

    [Fact]
    public void DecodesUtf8Gb18030AndUtf16()
    {
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
        const string text = "[00:01.00]你好，世界";

        byte[] utf8 = Encoding.UTF8.GetBytes(text);
        byte[] gb18030 = Encoding.GetEncoding(54936).GetBytes(text);
        byte[] utf16 = Encoding.Unicode.GetPreamble().Concat(Encoding.Unicode.GetBytes(text)).ToArray();

        Assert.Equal(text, LrcParser.Decode(utf8));
        Assert.Equal(text, LrcParser.Decode(gb18030));
        Assert.Equal(text, LrcParser.Decode(utf16));
    }

    [Fact]
    public void DecodeStripsUtf8Bom()
    {
        byte[] data = [0xEF, 0xBB, 0xBF, .. Encoding.UTF8.GetBytes("[00:01.00]Hi")];
        string? decoded = LrcParser.Decode(data);
        Assert.NotNull(decoded);
        Assert.Equal(["Hi"], LrcParser.Parse(decoded!).Lines.Select(l => l.Text));
    }
}
