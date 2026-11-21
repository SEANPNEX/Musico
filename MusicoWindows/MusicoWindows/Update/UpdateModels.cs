using System.Globalization;

namespace MusicoWindows.Update;

/// <summary>
/// Semantic version parser and numeric comparator supporting optional leading
/// v / V / v. / V. prefixes, build metadata, and pre-release tags.
/// </summary>
public readonly struct AppVersion : IComparable<AppVersion>, IEquatable<AppVersion>
{
    public AppVersion(int major, int minor, int patch, string? preRelease = null, int revision = 0)
    {
        if (major < 0) throw new ArgumentOutOfRangeException(nameof(major));
        if (minor < 0) throw new ArgumentOutOfRangeException(nameof(minor));
        if (patch < 0) throw new ArgumentOutOfRangeException(nameof(patch));
        if (revision < 0) throw new ArgumentOutOfRangeException(nameof(revision));

        Major = major;
        Minor = minor;
        Patch = patch;
        Revision = revision;
        PreRelease = string.IsNullOrWhiteSpace(preRelease) ? null : preRelease.Trim();
    }

    public int Major { get; }
    public int Minor { get; }
    public int Patch { get; }
    public int Revision { get; }
    public string? PreRelease { get; }

    public static bool TryParse(string? input, out AppVersion version)
    {
        version = default;
        if (string.IsNullOrWhiteSpace(input)) return false;

        string s = input.Trim();
        if (s.StartsWith("v.", StringComparison.OrdinalIgnoreCase))
        {
            s = s[2..];
        }
        else if (s.StartsWith("v", StringComparison.OrdinalIgnoreCase))
        {
            s = s[1..];
        }

        if (string.IsNullOrWhiteSpace(s)) return false;

        int plusIdx = s.IndexOf('+');
        if (plusIdx >= 0)
        {
            if (plusIdx == s.Length - 1) return false;
            s = s[..plusIdx];
        }

        string? preRelease = null;
        int dashIdx = s.IndexOf('-');
        if (dashIdx >= 0)
        {
            preRelease = s[(dashIdx + 1)..];
            if (string.IsNullOrWhiteSpace(preRelease)) return false;
            s = s[..dashIdx];
        }

        string[] parts = s.Split('.');
        if (parts.Length is < 2 or > 4) return false;

        Span<int> nums = stackalloc int[4];
        for (int i = 0; i < parts.Length; i++)
        {
            string part = parts[i];
            if (part.Length == 0 || !IsAllAsciiDigits(part)) return false;
            if (!int.TryParse(part, NumberStyles.None, CultureInfo.InvariantCulture, out int parsed) || parsed < 0)
            {
                return false;
            }
            nums[i] = parsed;
        }

        version = new AppVersion(nums[0], nums[1], nums[2], preRelease, nums[3]);
        return true;
    }

    public static AppVersion Parse(string input)
    {
        if (!TryParse(input, out var version))
        {
            throw new FormatException($"Invalid version string: '{input}'.");
        }
        return version;
    }

    public int CompareTo(AppVersion other)
    {
        int cmp = Major.CompareTo(other.Major);
        if (cmp != 0) return cmp;

        cmp = Minor.CompareTo(other.Minor);
        if (cmp != 0) return cmp;

        cmp = Patch.CompareTo(other.Patch);
        if (cmp != 0) return cmp;

        cmp = Revision.CompareTo(other.Revision);
        if (cmp != 0) return cmp;

        bool hasPre = !string.IsNullOrEmpty(PreRelease);
        bool otherHasPre = !string.IsNullOrEmpty(other.PreRelease);

        if (!hasPre && !otherHasPre) return 0;
        if (!hasPre) return 1;
        if (!otherHasPre) return -1;

        return ComparePreRelease(PreRelease!, other.PreRelease!);
    }

    public bool Equals(AppVersion other) =>
        Major == other.Major &&
        Minor == other.Minor &&
        Patch == other.Patch &&
        Revision == other.Revision &&
        string.Equals(PreRelease, other.PreRelease, StringComparison.Ordinal);

    public override bool Equals(object? obj) => obj is AppVersion other && Equals(other);

    public override int GetHashCode() => HashCode.Combine(Major, Minor, Patch, Revision, PreRelease);

    public override string ToString()
    {
        string core = Revision > 0
            ? $"{Major}.{Minor}.{Patch}.{Revision}"
            : $"{Major}.{Minor}.{Patch}";
        return string.IsNullOrEmpty(PreRelease) ? core : $"{core}-{PreRelease}";
    }

    public static bool operator ==(AppVersion left, AppVersion right) => left.Equals(right);
    public static bool operator !=(AppVersion left, AppVersion right) => !left.Equals(right);
    public static bool operator <(AppVersion left, AppVersion right) => left.CompareTo(right) < 0;
    public static bool operator <=(AppVersion left, AppVersion right) => left.CompareTo(right) <= 0;
    public static bool operator >(AppVersion left, AppVersion right) => left.CompareTo(right) > 0;
    public static bool operator >=(AppVersion left, AppVersion right) => left.CompareTo(right) >= 0;

    private static bool IsAllAsciiDigits(string text)
    {
        foreach (char c in text)
        {
            if (c is < '0' or > '9') return false;
        }
        return true;
    }

    private static int ComparePreRelease(string left, string right)
    {
        string[] leftParts = left.Split('.');
        string[] rightParts = right.Split('.');
        int count = Math.Min(leftParts.Length, rightParts.Length);

        for (int i = 0; i < count; i++)
        {
            string l = leftParts[i];
            string r = rightParts[i];

            bool lNum = int.TryParse(l, NumberStyles.None, CultureInfo.InvariantCulture, out int li) && IsAllAsciiDigits(l);
            bool rNum = int.TryParse(r, NumberStyles.None, CultureInfo.InvariantCulture, out int ri) && IsAllAsciiDigits(r);

            if (lNum && rNum)
            {
                int numCmp = li.CompareTo(ri);
                if (numCmp != 0) return numCmp;
            }
            else if (lNum != rNum)
            {
                return lNum ? -1 : 1;
            }
            else
            {
                int strCmp = string.CompareOrdinal(l, r);
                if (strCmp != 0) return strCmp;
            }
        }

        return leftParts.Length.CompareTo(rightParts.Length);
    }
}

public sealed record UpdateAsset(
    string Name,
    string DownloadUrl,
    long Size);

public sealed record UpdateRelease(
    AppVersion Version,
    string TagName,
    string Title,
    string ReleaseNotes,
    string HtmlUrl,
    bool IsDraft,
    bool IsPrerelease,
    DateTimeOffset? PublishedAt,
    IReadOnlyList<UpdateAsset> Assets);

public enum UpdateCheckStatus
{
    UpdateAvailable,
    UpToDate,
    Skipped,
    Error
}

public sealed record UpdateCheckResult(
    UpdateCheckStatus Status,
    AppVersion CurrentVersion,
    UpdateRelease? Release = null,
    UpdateAsset? SelectedAsset = null,
    UpdateAsset? ChecksumAsset = null,
    bool IsPortable = false,
    string? ErrorMessage = null)
{
    public bool IsUpdateAvailable => Status == UpdateCheckStatus.UpdateAvailable;
}

public sealed record UpdateProgress(
    string Stage,
    long BytesReceived = 0,
    long? TotalBytes = null)
{
    public double? Percent => TotalBytes is > 0
        ? Math.Clamp((double)BytesReceived / TotalBytes.Value * 100.0, 0.0, 100.0)
        : null;
}

public sealed class UpdatePreferences
{
    public string? SkippedVersion { get; set; }
}
