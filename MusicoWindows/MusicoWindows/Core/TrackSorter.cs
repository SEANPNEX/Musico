using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Stores;

namespace MusicoWindows.Core;

/// <summary>
/// Shared track sorting helpers for Queue, Playlist, and Folder views/stores.
/// </summary>
public static class TrackSorter
{
    public static IReadOnlyList<TrackSortMode> AllModes { get; } =
    [
        TrackSortMode.Custom,
        TrackSortMode.NameAscending,
        TrackSortMode.NameDescending,
        TrackSortMode.DateModified,
        TrackSortMode.MostPlayed,
        TrackSortMode.LeastPlayed
    ];

    public static string Label(TrackSortMode mode) => mode switch
    {
        TrackSortMode.Custom => L.Get("Sort_Custom"),
        TrackSortMode.NameAscending => L.Get("Sort_NameAsc"),
        TrackSortMode.NameDescending => L.Get("Sort_NameDesc"),
        TrackSortMode.DateModified => L.Get("Sort_DateModified"),
        TrackSortMode.MostPlayed => L.Get("Sort_MostPlayed"),
        TrackSortMode.LeastPlayed => L.Get("Sort_LeastPlayed"),
        _ => L.Get("Sort_Custom")
    };

    public static List<Track> Sort(
        IEnumerable<Track> tracks,
        TrackSortMode mode,
        PlayHistoryStore? history = null)
    {
        return Sort(tracks, mode, id => history?.GetCount(id) ?? 0);
    }

    public static List<Track> Sort(
        IEnumerable<Track> tracks,
        TrackSortMode mode,
        Func<string, int> getPlayCount)
    {
        var list = tracks.ToList();
        return mode switch
        {
            TrackSortMode.NameAscending => list
                .OrderBy(t => t.Title, StringComparer.CurrentCultureIgnoreCase)
                .ThenBy(t => t.FileName, StringComparer.CurrentCultureIgnoreCase)
                .ToList(),

            TrackSortMode.NameDescending => list
                .OrderByDescending(t => t.Title, StringComparer.CurrentCultureIgnoreCase)
                .ThenByDescending(t => t.FileName, StringComparer.CurrentCultureIgnoreCase)
                .ToList(),

            TrackSortMode.DateModified => list
                .OrderByDescending(t => t.ModifiedUtc)
                .ThenBy(t => t.Title, StringComparer.CurrentCultureIgnoreCase)
                .ToList(),

            TrackSortMode.MostPlayed => list
                .OrderByDescending(t => getPlayCount(t.Id))
                .ThenBy(t => t.Title, StringComparer.CurrentCultureIgnoreCase)
                .ToList(),

            TrackSortMode.LeastPlayed => list
                .OrderBy(t => getPlayCount(t.Id))
                .ThenBy(t => t.Title, StringComparer.CurrentCultureIgnoreCase)
                .ToList(),

            _ => list
        };
    }

    public static List<Track> ApplyCustomOrder(
        IEnumerable<Track> tracks,
        IReadOnlyList<string>? customOrder)
    {
        var list = tracks.ToList();
        if (customOrder is null || customOrder.Count == 0)
        {
            return Sort(list, TrackSortMode.NameAscending);
        }

        var byId = new Dictionary<string, Track>(list.Count, StringComparer.Ordinal);
        foreach (var t in list)
        {
            byId.TryAdd(t.Id, t);
        }

        var ordered = new List<Track>(list.Count);
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (string id in customOrder)
        {
            if (byId.TryGetValue(id, out var t) && seen.Add(id))
            {
                ordered.Add(t);
            }
        }

        var remaining = list
            .Where(t => !seen.Contains(t.Id))
            .OrderBy(t => t.Title, StringComparer.CurrentCultureIgnoreCase)
            .ThenBy(t => t.FileName, StringComparer.CurrentCultureIgnoreCase);
        ordered.AddRange(remaining);
        return ordered;
    }

    public static int CompareNullableTracks(
        Track? a,
        string idA,
        Track? b,
        string idB,
        TrackSortMode mode,
        Func<string, int> getPlayCount)
    {
        string titleA = a?.Title ?? idA;
        string titleB = b?.Title ?? idB;
        string fileA = a?.FileName ?? idA;
        string fileB = b?.FileName ?? idB;

        return mode switch
        {
            TrackSortMode.NameAscending => CompareTitleAndFile(titleA, fileA, titleB, fileB, descending: false),
            TrackSortMode.NameDescending => CompareTitleAndFile(titleA, fileA, titleB, fileB, descending: true),
            TrackSortMode.DateModified => CompareDateModified(a, b, titleA, fileA, titleB, fileB),
            TrackSortMode.MostPlayed => ComparePlayCount(idA, idB, titleA, fileA, titleB, fileB, getPlayCount, descending: true),
            TrackSortMode.LeastPlayed => ComparePlayCount(idA, idB, titleA, fileA, titleB, fileB, getPlayCount, descending: false),
            _ => 0
        };
    }

    private static int CompareTitleAndFile(string titleA, string fileA, string titleB, string fileB, bool descending)
    {
        int cmp = StringComparer.CurrentCultureIgnoreCase.Compare(titleA, titleB);
        if (cmp == 0) cmp = StringComparer.CurrentCultureIgnoreCase.Compare(fileA, fileB);
        return descending ? -cmp : cmp;
    }

    private static int CompareDateModified(Track? a, Track? b, string titleA, string fileA, string titleB, string fileB)
    {
        var modA = a?.ModifiedUtc ?? DateTimeOffset.MinValue;
        var modB = b?.ModifiedUtc ?? DateTimeOffset.MinValue;
        int cmp = modB.CompareTo(modA);
        return cmp != 0 ? cmp : CompareTitleAndFile(titleA, fileA, titleB, fileB, descending: false);
    }

    private static int ComparePlayCount(
        string idA,
        string idB,
        string titleA,
        string fileA,
        string titleB,
        string fileB,
        Func<string, int> getPlayCount,
        bool descending)
    {
        int cA = getPlayCount(idA);
        int cB = getPlayCount(idB);
        int cmp = descending ? cB.CompareTo(cA) : cA.CompareTo(cB);
        return cmp != 0 ? cmp : CompareTitleAndFile(titleA, fileA, titleB, fileB, descending: false);
    }
}
