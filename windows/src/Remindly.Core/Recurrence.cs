namespace Remindly.Core;

/// <summary>
/// Repeat rules — a line-by-line port of Android's nextOccurrenceCore / repeatLabel (Model.kt), so a
/// recurring task returns on the same day on the phone and on the PC.
/// Modes: OFF DAILY WEEKLY MONTHLY_DAY MONTHLY_ORD QUARTERLY HALFYEARLY YEARLY EVERY_N SPACED.
/// </summary>
public static class Recurrence
{
    public static readonly int[] SpacedGaps = { 3, 7, 14, 30 };
    public static readonly string[] Modes = { "OFF", "DAILY", "WEEKLY", "MONTHLY_DAY", "MONTHLY_ORD", "QUARTERLY", "HALFYEARLY", "YEARLY", "EVERY_N", "SPACED" };
    private static readonly string[] DayNames = { "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun" };
    private static readonly string[] OrdNames = { "First", "Second", "Third", "Fourth", "Last" };
    private const long DayMs = 86_400_000L;

    public static int SpacedGapDays(int step) => step >= 0 && step < SpacedGaps.Length ? SpacedGaps[step] : 30;

    /// <summary>ISO weekday 1=Mon..7=Sun.</summary>
    public static int IsoDow(DateTime d) => d.DayOfWeek == DayOfWeek.Sunday ? 7 : (int)d.DayOfWeek;

    public static long? NextOccurrence(Item item, long after, TimeZoneInfo? zone = null)
    {
        if (item.RepeatMode == "SPACED")
        {
            if (item.DueAt is not long due) return null;
            long next = due;
            while (next <= after) next += SpacedGapDays(item.SpacedStep) * DayMs;
            return next;
        }
        return Core(item.RepeatMode, item.RepeatDays, item.RepeatN, item.RepeatUnit, item.RepeatOrd, item.RepeatDow,
            item.RepeatOrdList, item.DueAt, after, zone);
    }

    public static long? NextOccurrence(CallReminder r, long after, TimeZoneInfo? zone = null) =>
        Core(r.RepeatMode, r.RepeatDays, r.RepeatN, r.RepeatUnit, r.RepeatOrd, r.RepeatDow, r.RepeatOrdList,
            r.RecurAt ?? r.CreatedAt, after, zone);

    /// <summary>The next [count] occurrences from max(now, startFrom) — the editor preview.</summary>
    public static List<long> Preview(Item draft, long now, int count = 3, TimeZoneInfo? zone = null)
    {
        var outList = new List<long>();
        if (draft.DueAt is not long anchor || draft.RepeatMode == "OFF") return outList;
        long after = Math.Max(now, anchor - 1);
        bool daySet = draft.RepeatMode is "WEEKLY" or "MONTHLY_DAY" or "MONTHLY_ORD";
        var probe = daySet ? draft with { DueAt = anchor - DayMs } : draft;
        int step = draft.SpacedStep;
        for (int i = 0; i < count; i++)
        {
            long? next;
            if (draft.RepeatMode == "SPACED")
            {
                long nx = anchor;
                while (nx <= after) nx += SpacedGapDays(step) * DayMs;
                step++;
                next = nx;
            }
            else next = NextOccurrence(probe, after, zone);
            if (next is not long n) break;
            outList.Add(n);
            after = n;
        }
        return outList;
    }

    private static long? Core(string mode, List<int> repeatDays, int repeatN, string repeatUnit, int repeatOrd, int repeatDow,
        List<int> repeatOrdList, long? dueAnchor, long after, TimeZoneInfo? zone)
    {
        if (dueAnchor is not long due) return null;
        if (mode == "OFF") return null;
        var z = zone ?? TimeZoneInfo.Local;
        var t = Clock.ToLocal(due, z);
        var afterLocal = Clock.ToLocal(after, z);
        var dueLocal = t;
        int guard = 0;
        while (!(t > afterLocal) && guard < 1000)
        {
            t = Bump(t);
            guard++;
        }
        return Clock.FromLocal(t, z);

        DateTime Bump(DateTime cur)
        {
            switch (mode)
            {
                case "DAILY": return cur.AddDays(1);
                case "WEEKLY":
                {
                    var days = (repeatDays.Count == 0 ? new List<int> { IsoDow(cur) } : repeatDays).ToHashSet();
                    var c = cur.AddDays(1);
                    for (int g = 0; g < 8 && !days.Contains(IsoDow(c)); g++) c = c.AddDays(1);
                    return c;
                }
                case "MONTHLY_DAY":
                {
                    var daySet = repeatDays.Where(d => d is >= 1 and <= 31).OrderBy(d => d).ToList();
                    if (daySet.Count == 0) daySet.Add(dueLocal.Day);
                    int len = DateTime.DaysInMonth(cur.Year, cur.Month);
                    var later = daySet.Select(d => Math.Min(d, len)).Where(d => d > cur.Day).DefaultIfEmpty(0).Min();
                    DateTime nextDate;
                    if (later > 0) nextDate = new DateTime(cur.Year, cur.Month, later);
                    else
                    {
                        var m = new DateTime(cur.Year, cur.Month, 1).AddMonths(1);
                        nextDate = new DateTime(m.Year, m.Month, Math.Min(daySet[0], DateTime.DaysInMonth(m.Year, m.Month)));
                    }
                    return nextDate + cur.TimeOfDay;
                }
                case "MONTHLY_ORD":
                {
                    var pats = repeatOrdList.Count == 0 ? new List<int> { repeatOrd * 10 + repeatDow } : repeatOrdList;
                    var curDate = cur.Date;
                    var monthFirst = new DateTime(curDate.Year, curDate.Month, 1);
                    DateTime? best = null;
                    for (int g2 = 0; best == null && g2 < 24; g2++)
                    {
                        var mf = monthFirst;
                        best = pats.Select(p => Hit(mf, p)).Where(d => d > curDate).DefaultIfEmpty(DateTime.MaxValue).Min();
                        if (best == DateTime.MaxValue) best = null;
                        monthFirst = monthFirst.AddMonths(1);
                    }
                    return (best ?? curDate.AddMonths(1)) + cur.TimeOfDay;
                }
                case "QUARTERLY": return cur.AddMonths(3);
                case "HALFYEARLY": return cur.AddMonths(6);
                case "YEARLY": return cur.AddMonths(12);
                case "EVERY_N":
                    return repeatUnit switch
                    {
                        "W" => cur.AddDays(7 * Math.Max(1, repeatN)),
                        "M" => cur.AddMonths(Math.Max(1, repeatN)),
                        _ => cur.AddDays(Math.Max(1, repeatN)),
                    };
                default: return cur.AddDays(1);
            }
        }
    }

    /// <summary>ord 1..4 = First..Fourth, 5 = Last; dow ISO 1..7.</summary>
    private static DateTime Hit(DateTime monthFirst, int pat)
    {
        int ord = pat / 10;
        int dow = Math.Clamp(pat % 10, 1, 7);
        if (ord >= 5)
        {
            var x = new DateTime(monthFirst.Year, monthFirst.Month, DateTime.DaysInMonth(monthFirst.Year, monthFirst.Month));
            while (IsoDow(x) != dow) x = x.AddDays(-1);
            return x;
        }
        var y = monthFirst;
        while (IsoDow(y) != dow) y = y.AddDays(1);
        return y.AddDays(7 * (Math.Max(1, ord) - 1));
    }

    public static string? Label(Item item) => item.RepeatMode switch
    {
        "SPACED" => $"Spaced · next gap {SpacedGapDays(item.SpacedStep)}d",
        "DAILY" => "Daily",
        "WEEKLY" => "Weekly · " + (item.RepeatDays.Count == 0 ? "—" : string.Join(",", item.RepeatDays.OrderBy(d => d).Select(d => DayNames[Math.Clamp(d - 1, 0, 6)]))),
        "MONTHLY_DAY" => item.RepeatDays.Any(d => d is >= 1 and <= 31)
            ? "Monthly · " + string.Join(",", item.RepeatDays.Where(d => d is >= 1 and <= 31).OrderBy(d => d))
            : "Monthly · same day",
        "MONTHLY_ORD" => string.Join(" + ", (item.RepeatOrdList.Count == 0 ? new List<int> { item.RepeatOrd * 10 + item.RepeatDow } : item.RepeatOrdList)
            .Select(p => OrdNames[Math.Clamp(p / 10 - 1, 0, 4)] + " " + DayNames[Math.Clamp(p % 10 - 1, 0, 6)])),
        "QUARTERLY" => "Quarterly",
        "HALFYEARLY" => "Half-yearly",
        "YEARLY" => "Yearly",
        "EVERY_N" => $"Every {item.RepeatN} " + (item.RepeatUnit switch { "W" => "week(s)", "M" => "month(s)", _ => "day(s)" }),
        _ => null,
    };

    public static bool Exhausted(int? count, int done) => count is int c && done >= c;

    /// <summary>A repeat count must be ≥ 2 to mean anything; anything else is "never ends".</summary>
    public static int? SanitizeCount(int? n) => n is int v && v >= 2 ? Math.Min(v, 999) : null;

    /// <summary>Buy lapse-return: the day a bought staple comes back to To buy.</summary>
    public static long ComputeReturnAt(long from, int value, LapseUnit unit, TimeZoneInfo? zone = null)
    {
        var d = Clock.ToLocal(from, zone);
        var r = unit == LapseUnit.MONTHS ? d.AddMonths(value) : d.AddDays(value);
        return Clock.FromLocal(r, zone);
    }
}
