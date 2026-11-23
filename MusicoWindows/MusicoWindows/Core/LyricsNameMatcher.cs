using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace MusicoWindows.Core;

/// <summary>
/// Fuzzy stem matcher for locating matching `.lrc`/`.txt` lyrics in the same folder
/// as a playing audio file.
/// </summary>
public static partial class LyricsNameMatcher
{
    public const double MinimumMatchScore = 0.72;

    private static readonly HashSet<string> GenericStems = new(StringComparer.Ordinal)
    {
        "lyrics", "lyric", "words", "readme", "info", "notes", "note",
        "track", "song", "audio", "music", "index", "playlist", "album",
        "untitled", "unknown", "歌词"
    };

    private static readonly HashSet<string> NoiseWords = new(StringComparer.Ordinal)
    {
        "official", "audio", "video", "music", "mv", "pv", "lyric", "lyrics",
        "visualizer", "hd", "hq", "hi", "res", "hires", "flac", "mp3",
        "aac", "wav", "alac", "lossless", "explicit", "clean", "remaster",
        "remastered", "24bit", "16bit"
    };

    private static readonly (char Open, char Close)[] BracketPairs =
    [
        ('(', ')'),
        ('[', ']'),
        ('【', '】'),
        ('（', '）')
    ];

    private static readonly Regex[] TrackNumberPatterns =
    [
        new(@"^(?i:(?:disc|cd)\s*\d{1,2}\s*[-._\s]+)?\d{1,3}\s*[-._)\]]+\s*(?=.+)", RegexOptions.Compiled),
        new(@"^(?i:track\s*#?\s*\d{1,3})\s*[-._)\s:]+\s*(?=.+)", RegexOptions.Compiled),
        new(@"^\d{1,2}\s+(?=[\p{L}\p{N}])", RegexOptions.Compiled)
    ];

    public static bool IsGenericStem(string stem)
    {
        string norm = NormalizeKey(stem);
        return string.IsNullOrEmpty(norm) || GenericStems.Contains(norm);
    }

    public static string NormalizeKey(string? raw) =>
        string.Join(" ", Tokenize(raw));

    public static List<string> Tokenize(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return [];

        string nfkc = raw.Normalize(NormalizationForm.FormKC);
        string folded = FoldLatinDiacriticsPreservingKana(nfkc)
            .Replace('\u3000', ' ')
            .Trim();
        if (folded.Length == 0) return [];

        string cleaned = StripLeadingTrackNumber(StripNoiseBrackets(folded));
        var tokens = new List<string>();
        var current = new StringBuilder();

        foreach (Rune rune in cleaned.EnumerateRunes())
        {
            if (Rune.IsLetterOrDigit(rune))
            {
                current.Append(Rune.ToLowerInvariant(rune).ToString());
            }
            else if (current.Length > 0)
            {
                tokens.Add(current.ToString());
                current.Clear();
            }
        }
        if (current.Length > 0)
        {
            tokens.Add(current.ToString());
        }
        return tokens;
    }

    public static double MatchScore(
        string candidateStem,
        string audioStem,
        string? trackTitle,
        string? trackArtist)
    {
        List<string> candTokens = Tokenize(candidateStem);
        string candNorm = string.Join(" ", candTokens);
        if (string.IsNullOrEmpty(candNorm) || GenericStems.Contains(candNorm))
        {
            return 0.0;
        }

        var references = new List<(string Norm, List<string> Tokens, double Weight)>(4);
        List<string> audioTokens = Tokenize(audioStem);
        string audioNorm = string.Join(" ", audioTokens);
        if (!string.IsNullOrEmpty(audioNorm))
        {
            references.Add((audioNorm, audioTokens, 1.0));
        }

        List<string> titleTokens = Tokenize(trackTitle);
        string titleNorm = string.Join(" ", titleTokens);
        List<string> artistTokens = Tokenize(trackArtist);

        if (!string.IsNullOrEmpty(titleNorm))
        {
            references.Add((titleNorm, titleTokens, 0.98));
            if (artistTokens.Count > 0)
            {
                var atTokens = artistTokens.Concat(titleTokens).ToList();
                var taTokens = titleTokens.Concat(artistTokens).ToList();
                references.Add((string.Join(" ", atTokens), atTokens, 0.99));
                references.Add((string.Join(" ", taTokens), taTokens, 0.97));
            }
        }

        if (references.Count == 0) return 0.0;

        var candSet = new HashSet<string>(candTokens, StringComparer.Ordinal);
        double best = 0.0;

        foreach (var reference in references)
        {
            if (candNorm == reference.Norm)
            {
                best = Math.Max(best, reference.Weight);
                continue;
            }
            var refSet = new HashSet<string>(reference.Tokens, StringComparer.Ordinal);
            if (candSet.Count > 0 && candSet.SetEquals(refSet))
            {
                best = Math.Max(best, 0.96 * reference.Weight);
                continue;
            }
            double jaccard = TokenJaccard(candSet, refSet);
            double containment = Math.Max(TokenContainment(candSet, refSet), TokenContainment(refSet, candSet));
            double tokenSim = jaccard * 0.55 + containment * 0.45;
            double charSim = BigramDiceSimilarity(candNorm, reference.Norm);
            double combined = Math.Max(tokenSim, charSim * 0.92) * reference.Weight;
            best = Math.Max(best, combined);
        }

        if (artistTokens.Count > 0 && !string.IsNullOrEmpty(titleNorm) && candNorm != audioNorm)
        {
            string? prefix = SplitHyphenPrefix(candidateStem);
            if (prefix is not null)
            {
                var prefixSet = new HashSet<string>(Tokenize(prefix), StringComparer.Ordinal);
                var artistSet = new HashSet<string>(artistTokens, StringComparer.Ordinal);
                var titleSet = new HashSet<string>(titleTokens, StringComparer.Ordinal);
                if (prefixSet.Count > 0 && !prefixSet.Overlaps(artistSet) && !prefixSet.SetEquals(titleSet))
                {
                    best *= 0.65;
                }
            }
        }

        return best;
    }

    public static double Similarity(string a, string b)
    {
        List<string> ta = Tokenize(a);
        List<string> tb = Tokenize(b);
        string na = string.Join(" ", ta);
        string nb = string.Join(" ", tb);
        if (string.IsNullOrEmpty(na) || string.IsNullOrEmpty(nb)) return 0.0;
        if (na == nb) return 1.0;

        var sa = new HashSet<string>(ta, StringComparer.Ordinal);
        var sb = new HashSet<string>(tb, StringComparer.Ordinal);
        if (sa.SetEquals(sb)) return 0.96;

        double jaccard = TokenJaccard(sa, sb);
        double containment = Math.Max(TokenContainment(sa, sb), TokenContainment(sb, sa));
        double charDice = BigramDiceSimilarity(na, nb);
        return Math.Max(jaccard * 0.6 + containment * 0.4, charDice * 0.9);
    }

    private static string FoldLatinDiacriticsPreservingKana(string text)
    {
        string decomposed = text.Normalize(NormalizationForm.FormD);
        var sb = new StringBuilder(decomposed.Length);
        foreach (char ch in decomposed)
        {
            if (ch >= '\u0300' && ch <= '\u036F')
            {
                continue;
            }
            sb.Append(ch);
        }
        return sb.ToString().Normalize(NormalizationForm.FormC);
    }

    private static string StripNoiseBrackets(string text)
    {
        string current = text;
        foreach (var (open, close) in BracketPairs)
        {
            current = RemoveMatchingNoiseSegments(current, open, close);
        }
        return current.Trim();
    }

    private static string RemoveMatchingNoiseSegments(string text, char open, char close)
    {
        var result = new StringBuilder(text.Length);
        int i = 0;
        while (i < text.Length)
        {
            if (text[i] == open)
            {
                int end = text.IndexOf(close, i + 1);
                if (end > i)
                {
                    string inner = text.Substring(i + 1, end - (i + 1));
                    if (IsPureNoiseSegment(inner))
                    {
                        string remaining = string.Concat(result.ToString(), text.AsSpan(end + 1));
                        if (!string.IsNullOrWhiteSpace(remaining))
                        {
                            i = end + 1;
                            continue;
                        }
                    }
                }
            }
            result.Append(text[i]);
            i++;
        }
        return result.ToString();
    }

    private static bool IsPureNoiseSegment(string inner)
    {
        var words = new List<string>();
        var cur = new StringBuilder();
        foreach (Rune rune in inner.EnumerateRunes())
        {
            if (Rune.IsLetterOrDigit(rune))
            {
                cur.Append(Rune.ToLowerInvariant(rune).ToString());
            }
            else if (cur.Length > 0)
            {
                words.Add(cur.ToString());
                cur.Clear();
            }
        }
        if (cur.Length > 0) words.Add(cur.ToString());
        if (words.Count == 0) return true;

        return words.All(word =>
        {
            if (NoiseWords.Contains(word)) return true;
            if (word.EndsWith("kbps", StringComparison.Ordinal) ||
                word.EndsWith("khz", StringComparison.Ordinal) ||
                word.EndsWith("bit", StringComparison.Ordinal))
            {
                int suffixLen = word.EndsWith("bit", StringComparison.Ordinal) ? 3 : 4;
                ReadOnlySpan<char> prefix = word.AsSpan(0, word.Length - suffixLen);
                if (!prefix.IsEmpty)
                {
                    foreach (char c in prefix)
                    {
                        if (!char.IsDigit(c)) return false;
                    }
                    return true;
                }
            }
            if (word.Length == 4 && int.TryParse(word, NumberStyles.None, CultureInfo.InvariantCulture, out int year))
            {
                return year is >= 1900 and <= 2099 && words.Count > 1;
            }
            return false;
        });
    }

    private static string StripLeadingTrackNumber(string text)
    {
        string working = text.Trim();
        foreach (Regex pattern in TrackNumberPatterns)
        {
            Match m = pattern.Match(working);
            if (m.Success)
            {
                string candidate = working[(m.Index + m.Length)..].Trim();
                bool hasAlnum = false;
                foreach (Rune r in candidate.EnumerateRunes())
                {
                    if (Rune.IsLetterOrDigit(r))
                    {
                        hasAlnum = true;
                        break;
                    }
                }
                if (hasAlnum)
                {
                    working = candidate;
                    break;
                }
            }
        }
        return working;
    }

    private static string? SplitHyphenPrefix(string raw)
    {
        foreach (string sep in (string[])[" - ", " — ", " – "])
        {
            int idx = raw.IndexOf(sep, StringComparison.Ordinal);
            if (idx > 0) return raw[..idx];
        }
        return null;
    }

    private static double TokenJaccard(HashSet<string> a, HashSet<string> b)
    {
        if (a.Count == 0 || b.Count == 0) return 0.0;
        int inter = a.Count(b.Contains);
        int union = a.Count + b.Count - inter;
        return union > 0 ? (double)inter / union : 0.0;
    }

    private static double TokenContainment(HashSet<string> inner, HashSet<string> outer)
    {
        if (inner.Count == 0 || outer.Count == 0) return 0.0;
        int hits = inner.Count(outer.Contains);
        return (double)hits / inner.Count;
    }

    private static double BigramDiceSimilarity(string a, string b)
    {
        string ca = a.Replace(" ", string.Empty, StringComparison.Ordinal);
        string cb = b.Replace(" ", string.Empty, StringComparison.Ordinal);
        if (ca.Length < 2 || cb.Length < 2)
        {
            return ca == cb && ca.Length > 0 ? 1.0 : 0.0;
        }

        var bgA = new Dictionary<string, int>(StringComparer.Ordinal);
        for (int i = 0; i < ca.Length - 1; i++)
        {
            string g = ca.Substring(i, 2);
            bgA[g] = bgA.TryGetValue(g, out int prev) ? prev + 1 : 1;
        }

        var bgB = new Dictionary<string, int>(StringComparer.Ordinal);
        for (int i = 0; i < cb.Length - 1; i++)
        {
            string g = cb.Substring(i, 2);
            bgB[g] = bgB.TryGetValue(g, out int prev) ? prev + 1 : 1;
        }

        int overlap = 0;
        foreach (var (g, cA) in bgA)
        {
            if (bgB.TryGetValue(g, out int cB))
            {
                overlap += Math.Min(cA, cB);
            }
        }
        return (2.0 * overlap) / ((ca.Length - 1) + (cb.Length - 1));
    }
}
