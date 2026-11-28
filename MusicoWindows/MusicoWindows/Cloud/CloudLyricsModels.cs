using System.Globalization;
using MusicoWindows.Core;
using MusicoWindows.Models;

namespace MusicoWindows.Cloud;

public static class CloudLyricsProviderIds
{
    public const string Lrclib = "builtin.lrclib";
}

public enum CloudLyricsSourceKind
{
    Auto,
    Lrclib,
    Extension
}

public sealed record CloudLyricsSourceSelection(CloudLyricsSourceKind Kind, string? ExtensionId = null)
{
    public static CloudLyricsSourceSelection Auto { get; } = new(CloudLyricsSourceKind.Auto);
    public static CloudLyricsSourceSelection Lrclib { get; } = new(CloudLyricsSourceKind.Lrclib);
    public static CloudLyricsSourceSelection Extension(string extensionId) => new(CloudLyricsSourceKind.Extension, extensionId);
}

public sealed record CloudLyricsSourceOption(
    string Id,
    CloudLyricsSourceSelection Selection,
    string Label);

public sealed record CloudLyricsExtensionDescriptor(string Id, string Name);

public sealed record CloudLyricsQuery(
    string Title,
    string Artist,
    string? Album = null,
    double? DurationSec = null)
{
    public string TrimmedTitle => (Title ?? string.Empty).Trim();
    public string TrimmedArtist => (Artist ?? string.Empty).Trim();
    public string? TrimmedAlbum => string.IsNullOrWhiteSpace(Album) ? null : Album.Trim();
    public bool IsEmpty => TrimmedTitle.Length == 0 && TrimmedArtist.Length == 0;
}

public sealed record CloudLyricsCandidate(
    string Id,
    string ProviderId,
    string SourceName,
    string Title,
    string Artist,
    string? Album = null,
    double? DurationSec = null,
    bool IsInstrumental = false,
    string? SyncedLyrics = null,
    string? PlainLyrics = null,
    string? TranslatedLyrics = null)
{
    public bool IsTimed =>
        !string.IsNullOrWhiteSpace(SyncedLyrics) && LrcParser.Parse(SyncedLyrics).IsTimed;

    public bool HasTranslation =>
        !string.IsNullOrWhiteSpace(TranslatedLyrics) && LrcParser.Parse(TranslatedLyrics).Lines.Count > 0;

    public bool HasUsableContent =>
        IsInstrumental ||
        (!string.IsNullOrWhiteSpace(SyncedLyrics) && LrcParser.Parse(SyncedLyrics).Lines.Count > 0) ||
        (!string.IsNullOrWhiteSpace(PlainLyrics) && LrcParser.Parse(PlainLyrics).Lines.Count > 0);

    public string FormattedText(bool includeTranslation)
    {
        string? synced = SyncedLyrics?.Trim();
        string? trans = TranslatedLyrics?.Trim();
        if (!string.IsNullOrEmpty(synced))
        {
            if (includeTranslation && !string.IsNullOrEmpty(trans))
            {
                return CloudLyricsFormatter.MergeSyncedWithTranslation(synced, trans);
            }
            return synced;
        }

        string? plain = PlainLyrics?.Trim();
        if (!string.IsNullOrEmpty(plain))
        {
            if (includeTranslation && !string.IsNullOrEmpty(trans))
            {
                return $"{plain}\n\n{trans}";
            }
            return plain;
        }

        return IsInstrumental ? CloudLyricsFormatter.InstrumentalLrc : string.Empty;
    }
}

public sealed class CloudLyricsException : Exception
{
    public CloudLyricsException(string message, int? retryAfterSeconds = null, Exception? inner = null)
        : base(message, inner)
    {
        RetryAfterSeconds = retryAfterSeconds;
    }

    public int? RetryAfterSeconds { get; }

    public static CloudLyricsException EmptyQuery() =>
        new("Enter a song title or artist to search.");

    public static CloudLyricsException RateLimited(int? retryAfterSeconds) =>
        new(
            retryAfterSeconds is > 0
                ? $"Lyrics service is busy. Try again in {retryAfterSeconds}s."
                : "Lyrics service rate limited. Please try again shortly.",
            retryAfterSeconds);

    public static CloudLyricsException ProviderUnavailable(string name) =>
        new($"Lyrics provider '{name}' is unavailable.");
}

public sealed record CloudLyricsSourceFailure(
    string ProviderId,
    string SourceName,
    string Message);

public sealed record CloudLyricsSearchResponse(
    IReadOnlyList<CloudLyricsCandidate> Candidates,
    IReadOnlyList<CloudLyricsSourceFailure> Failures);

public static class CloudLyricsFormatter
{
    public const string InstrumentalLrc = "[00:00.00]♪ Instrumental ♪";

    public static string? FormatDurationAndDelta(double? candidateSec, double? targetSec)
    {
        if (candidateSec is not double c || double.IsNaN(c) || double.IsInfinity(c) || c <= 0)
        {
            return null;
        }
        string clock = FormatClock(c);
        if (targetSec is not double t || double.IsNaN(t) || double.IsInfinity(t) || t <= 0)
        {
            return clock;
        }
        int delta = (int)Math.Round(c - t);
        if (delta == 0) return clock;
        string sign = delta > 0 ? "+" : "";
        return $"{clock} ({sign}{delta}s)";
    }

    public static string FormatClock(double seconds)
    {
        int total = Math.Max(0, (int)Math.Round(seconds));
        return string.Create(CultureInfo.InvariantCulture, $"{total / 60}:{total % 60:D2}");
    }

    public static string FormatLrcTimestamp(double seconds)
    {
        int totalHundredths = Math.Max(0, (int)Math.Round(seconds * 100.0));
        int mins = totalHundredths / 6000;
        int rem = totalHundredths % 6000;
        int secs = rem / 100;
        int cs = rem % 100;
        return string.Create(CultureInfo.InvariantCulture, $"[{mins:D2}:{secs:D2}.{cs:D2}]");
    }

    public static string MergeSyncedWithTranslation(string primary, string translation)
    {
        Lyrics primaryParsed = LrcParser.Parse(primary);
        Lyrics transParsed = LrcParser.Parse(translation);
        if (!primaryParsed.IsTimed || primaryParsed.Lines.Count == 0) return primary;
        if (!transParsed.IsTimed || transParsed.Lines.Count == 0) return primary;

        var transTimed = new List<(double Time, string Text)>();
        foreach (LyricLine line in transParsed.Lines)
        {
            if (line.TimeSeconds is not double t) continue;
            string txt = line.Text.Trim();
            if (txt.Length == 0 || txt == "//") continue;
            transTimed.Add((t, txt));
        }
        if (transTimed.Count == 0) return primary;

        var outLines = new List<string>(primaryParsed.Lines.Count * 2);
        foreach (LyricLine line in primaryParsed.Lines)
        {
            if (line.TimeSeconds is not double time)
            {
                string trimmed = line.Text.Trim();
                if (trimmed.Length > 0) outLines.Add(trimmed);
                continue;
            }
            string tag = FormatLrcTimestamp(time);
            outLines.Add($"{tag}{line.Text}");

            (double Time, string Text)? match = ClosestTranslation(time, transTimed, toleranceSec: 0.05);
            if (match is not null && !string.Equals(match.Value.Text, line.Text.Trim(), StringComparison.Ordinal))
            {
                outLines.Add($"{tag}{match.Value.Text}");
            }
        }
        return string.Join("\n", outLines);
    }

    private static (double Time, string Text)? ClosestTranslation(
        double targetTime,
        List<(double Time, string Text)> lines,
        double toleranceSec)
    {
        (double Time, string Text)? best = null;
        double bestDiff = toleranceSec + 0.0001;
        foreach (var item in lines)
        {
            double diff = Math.Abs(item.Time - targetTime);
            if (diff <= toleranceSec && diff < bestDiff)
            {
                bestDiff = diff;
                best = item;
            }
        }
        return best;
    }
}

public static class CloudLyricsScorer
{
    public static double Score(CloudLyricsCandidate candidate, CloudLyricsQuery query)
    {
        double total = 0.0;
        string qTitle = query.TrimmedTitle;
        string qArtist = query.TrimmedArtist;
        string? qAlbum = query.TrimmedAlbum;

        if (!string.IsNullOrEmpty(qTitle))
        {
            total += LyricsNameMatcher.Similarity(candidate.Title, qTitle) * 42.0;
        }
        if (!string.IsNullOrEmpty(qArtist))
        {
            total += LyricsNameMatcher.Similarity(candidate.Artist, qArtist) * 28.0;
        }
        if (!string.IsNullOrEmpty(qAlbum) && !string.IsNullOrWhiteSpace(candidate.Album))
        {
            total += LyricsNameMatcher.Similarity(candidate.Album, qAlbum) * 5.0;
        }

        if (query.DurationSec is double qDur && !double.IsNaN(qDur) && !double.IsInfinity(qDur) && qDur > 0 &&
            candidate.DurationSec is double cDur && !double.IsNaN(cDur) && !double.IsInfinity(cDur) && cDur > 0)
        {
            double diff = Math.Abs(cDur - qDur);
            total += diff switch
            {
                <= 2.0 => 20.0,
                <= 5.0 => 14.0,
                <= 10.0 => 7.0,
                <= 20.0 => 1.0,
                _ => -Math.Min(18.0, (diff - 20.0) * 0.4)
            };
        }

        if (candidate.IsTimed)
        {
            total += 15.0;
        }
        else if (candidate.IsInstrumental)
        {
            total -= 6.0;
        }
        return total;
    }

    public static List<CloudLyricsCandidate> RankAndDeduplicate(
        IEnumerable<CloudLyricsCandidate> candidates,
        CloudLyricsQuery query)
    {
        var seen = new HashSet<string>(StringComparer.Ordinal);
        var filtered = new List<(int Index, CloudLyricsCandidate Item)>();
        int idx = 0;

        foreach (CloudLyricsCandidate c in candidates)
        {
            idx++;
            if (!c.HasUsableContent) continue;
            string snippet = c.SyncedLyrics ?? c.PlainLyrics ?? string.Empty;
            if (snippet.Length > 80) snippet = snippet[..80];

            string key = string.Join("|",
                c.ProviderId,
                LyricsNameMatcher.NormalizeKey(c.Title),
                LyricsNameMatcher.NormalizeKey(c.Artist),
                c.IsTimed ? "1" : "0",
                snippet);

            if (seen.Add(key))
            {
                filtered.Add((idx, c));
            }
        }

        filtered.Sort((a, b) =>
        {
            double sa = Score(a.Item, query);
            double sb = Score(b.Item, query);
            if (Math.Abs(sa - sb) > 0.001) return sb.CompareTo(sa);
            if (a.Item.IsTimed != b.Item.IsTimed) return b.Item.IsTimed ? 1 : -1;
            return a.Index.CompareTo(b.Index);
        });

        return filtered.Select(x => x.Item).ToList();
    }
}
