using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using MusicoWindows.Models;

namespace MusicoWindows.Core;

public static partial class LrcParser
{
    static LrcParser()
    {
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
    }

    private static readonly Regex TimestampRegex = new(
        @"^\s*\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]",
        RegexOptions.Compiled);

    private static readonly Regex MetadataTagRegex = new(
        @"^\s*\[[A-Za-z#][^\]]*\]\s*$",
        RegexOptions.Compiled);

    public static Lyrics Parse(string text)
    {
        var timed = new List<(double Time, int Order, string Text)>();
        var plain = new List<string>();
        int seq = 0;

        foreach (string rawLine in text.Split(["\r\n", "\n", "\r"], StringSplitOptions.None))
        {
            string rest = rawLine;
            var times = new List<double>();

            while (true)
            {
                var match = TimestampRegex.Match(rest);
                if (!match.Success) break;

                double minutes = double.Parse(match.Groups[1].Value, CultureInfo.InvariantCulture);
                double seconds = double.Parse(match.Groups[2].Value, CultureInfo.InvariantCulture);
                double fraction = match.Groups[3].Success
                    ? double.Parse("0." + match.Groups[3].Value, CultureInfo.InvariantCulture)
                    : 0.0;
                times.Add(minutes * 60.0 + seconds + fraction);
                rest = rest[match.Length..];
            }

            string content = rest.Trim();
            if (times.Count > 0)
            {
                foreach (double t in times)
                {
                    timed.Add((t, seq++, content));
                }
            }
            else if (!MetadataTagRegex.IsMatch(rest) && content.Length > 0)
            {
                plain.Add(content);
            }
        }

        if (timed.Count == 0)
        {
            var plainLines = new LyricLine[plain.Count];
            for (int i = 0; i < plain.Count; i++)
            {
                plainLines[i] = new LyricLine(i, null, plain[i]);
            }
            return new Lyrics(plainLines, IsTimed: false);
        }

        timed.Sort((a, b) =>
        {
            int cmp = a.Time.CompareTo(b.Time);
            return cmp != 0 ? cmp : a.Order.CompareTo(b.Order);
        });

        var timedLines = new LyricLine[timed.Count];
        for (int i = 0; i < timed.Count; i++)
        {
            timedLines[i] = new LyricLine(i, timed[i].Time, timed[i].Text);
        }
        return new Lyrics(timedLines, IsTimed: true);
    }

    /// <summary>Decodes lyric file bytes: UTF-8, then UTF-16 (with BOM), then GB18030.</summary>
    public static string? Decode(byte[] data)
    {
        if (data is null) return null;

        string? decoded = null;

        try
        {
            var utf8Strict = new UTF8Encoding(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true);
            decoded = utf8Strict.GetString(data);
        }
        catch (DecoderFallbackException)
        {
            bool hasUtf16LeBom = data.Length >= 2 && data[0] == 0xFF && data[1] == 0xFE;
            bool hasUtf16BeBom = data.Length >= 2 && data[0] == 0xFE && data[1] == 0xFF;

            if (hasUtf16LeBom || hasUtf16BeBom)
            {
                if (data.Length % 2 != 0) return null;
                try
                {
                    var utf16 = new UnicodeEncoding(bigEndian: hasUtf16BeBom, byteOrderMark: true, throwOnInvalidBytes: true);
                    decoded = utf16.GetString(data, 2, data.Length - 2);
                }
                catch (DecoderFallbackException)
                {
                    return null;
                }
            }
            else
            {
                try
                {
                    var gb18030 = Encoding.GetEncoding(54936, EncoderFallback.ExceptionFallback, DecoderFallback.ExceptionFallback);
                    decoded = gb18030.GetString(data);
                }
                catch
                {
                    return null;
                }
            }
        }

        if (decoded is null) return null;
        return decoded.StartsWith('\uFEFF') ? decoded[1..] : decoded;
    }
}
