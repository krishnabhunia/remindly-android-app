using Remindly.Core.Models;

namespace Remindly.Core.Logic;

public sealed record DayGroup<T>(string Key, string Label, List<T> Items);
public sealed record MonthGroup<T>(string Key, string Label, List<DayGroup<T>> Days)
{
    public int Count => Days.Sum(d => d.Items.Count);
}
public sealed record YearGroup<T>(string Key, string Label, List<MonthGroup<T>> Months)
{
    public int Count => Months.Sum(m => m.Count);
    public IEnumerable<T> AllItems => Months.SelectMany(m => m.Days.SelectMany(d => d.Items));
}

/// <summary>Year → Month → Day grouping (buildYearGroups) and the sort/basis rules that feed it.</summary>
public static class Grouping
{
    /// <summary>Active: due date (or created). Done: completion date.</summary>
    public static long Basis(Item item, bool done) => done ? item.DoneAt ?? item.CreatedAt : item.DueAt ?? item.CreatedAt;

    public static long CallBasis(CallReminder c, bool done)
    {
        if (done) return c.DoneAt ?? c.CreatedAt;
        if (c.RepeatMode != "OFF" && c.RecurAt != null) return c.RecurAt ?? c.LastMissedAt ?? c.CreatedAt;
        return c.LastMissedAt ?? c.CreatedAt;
    }

    public static List<YearGroup<T>> BuildYearGroups<T>(IEnumerable<T> list, bool descending, Func<T, long> tieBreak, Func<T, long> basis)
    {
        var sorted = descending
            ? list.OrderByDescending(basis).ThenByDescending(tieBreak).ToList()
            : list.OrderBy(basis).ThenBy(tieBreak).ToList();
        var years = new Dictionary<string, Dictionary<string, Dictionary<string, List<T>>>>();
        var yearOrder = new List<string>(); var monthOrder = new Dictionary<string, List<string>>(); var dayOrder = new Dictionary<string, List<string>>();
        var monthLabels = new Dictionary<string, string>(); var dayLabels = new Dictionary<string, string>();
        foreach (var item in sorted)
        {
            var b = basis(item);
            var d = Time.LocalDate(b);
            var yKey = d.Year.ToString("0000");
            var mKey = $"{yKey}-{d.Month:00}";
            var dKey = $"{mKey}-{d.Day:00}";
            monthLabels[mKey] = Time.FormatMonth(b);
            dayLabels[dKey] = Time.FormatDay(b);
            if (!years.TryGetValue(yKey, out var months)) { months = new(); years[yKey] = months; yearOrder.Add(yKey); monthOrder[yKey] = new(); }
            if (!months.TryGetValue(mKey, out var days)) { days = new(); months[mKey] = days; monthOrder[yKey].Add(mKey); dayOrder[mKey] = new(); }
            if (!days.TryGetValue(dKey, out var items)) { items = new(); days[dKey] = items; dayOrder[mKey].Add(dKey); }
            items.Add(item);
        }
        return yearOrder.Select(y => new YearGroup<T>(y, y,
            monthOrder[y].Select(m => new MonthGroup<T>(m, monthLabels[m],
                dayOrder[m].Select(dk => new DayGroup<T>(dk, dayLabels[dk], years[y][m][dk])).ToList())).ToList())).ToList();
    }

    /// <summary>Group by the item's group/topic (v1.6 "By group" sort); "No group" last.</summary>
    public static List<(string key, List<Item> items)> ByGroup(IEnumerable<Item> items, Func<Item, string?> keyOf, string noneLabel = "No group")
    {
        var by = items.GroupBy(i => string.IsNullOrWhiteSpace(keyOf(i)) ? null : keyOf(i)!.Trim()).ToList();
        var named = by.Where(g => g.Key != null).OrderBy(g => g.Key!.ToLowerInvariant()).Select(g => (g.Key!, g.ToList())).ToList();
        var none = by.FirstOrDefault(g => g.Key == null);
        if (none != null) named.Add((noneLabel, none.ToList()));
        return named;
    }

    public static string MonthKey(long ms) { var d = Time.LocalDate(ms); return $"{d.Year:0000}-{d.Month:00}"; }
}
