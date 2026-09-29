using Remindly.Core.Models;

namespace Remindly.Core.Logic;

/// <summary>Local-time helpers (ZoneId.systemDefault() equivalents).</summary>
public static class Time
{
    public static TimeZoneInfo Zone { get; set; } = TimeZoneInfo.Local;

    public static DateTime ToLocal(long ms) => TimeZoneInfo.ConvertTime(DateTimeOffset.FromUnixTimeMilliseconds(ms), Zone).DateTime;
    public static long ToMs(DateTime local)
    {
        var unspecified = DateTime.SpecifyKind(local, DateTimeKind.Unspecified);
        var offset = Zone.GetUtcOffset(unspecified);
        return new DateTimeOffset(unspecified, offset).ToUnixTimeMilliseconds();
    }
    public static DateOnly LocalDate(long ms) => DateOnly.FromDateTime(ToLocal(ms));
    public static long StartOfDay(long ms) => ToMs(ToLocal(ms).Date);
    public static long StartOfTomorrow(long now) => ToMs(ToLocal(now).Date.AddDays(1));
    public static long AtMinuteOfDay(DateOnly date, int minutes) => ToMs(date.ToDateTime(new TimeOnly(minutes / 60 % 24, minutes % 60)));
    public static int MinuteOfDay(long ms) { var t = ToLocal(ms); return t.Hour * 60 + t.Minute; }

    /// <summary>Android formatDay: "Wed, 16 Sep".</summary>
    public static string FormatDay(long ms) => ToLocal(ms).ToString("ddd, dd MMM", System.Globalization.CultureInfo.InvariantCulture);
    public static string FormatDate(long ms) => ToLocal(ms).ToString("dd MMM yyyy", System.Globalization.CultureInfo.InvariantCulture);
    public static string FormatMonth(long ms) => ToLocal(ms).ToString("MMMM", System.Globalization.CultureInfo.InvariantCulture);

    /// <summary>"PHONE" follows the OS clock setting; "H12"/"H24" force.</summary>
    public static string FormatTime(long ms, string timeFormat, bool systemIs24h)
    {
        var t = ToLocal(ms);
        var h24 = timeFormat == "H24" || (timeFormat != "H12" && systemIs24h);
        return h24 ? t.ToString("HH:mm", System.Globalization.CultureInfo.InvariantCulture)
                   : t.ToString("h:mm tt", System.Globalization.CultureInfo.InvariantCulture);
    }
    public static string FormatDayTime(long ms, string timeFormat, bool systemIs24h) => FormatDay(ms) + " · " + FormatTime(ms, timeFormat, systemIs24h);
    public static string FormatDateTime(long ms, string timeFormat, bool systemIs24h) => FormatDate(ms) + ", " + FormatTime(ms, timeFormat, systemIs24h);
}

/// <summary>
/// Port of nextOccurrenceCore / nextOccurrence / nextOccurrenceCall / previewOccurrences /
/// occurrencesWithin / rollMissedRepeats / computeReturnAt (Model.kt). Same semantics, same
/// guard counts, so a repeat set on the phone lands on the same dates on the PC.
/// </summary>
public static class Recurrence
{
    public static readonly int[] SpacedGaps = { 3, 7, 14, 30 };
    public static int SpacedGapDays(int step) => step >= 0 && step < SpacedGaps.Length ? SpacedGaps[step] : 30;
    public static readonly HashSet<string> DaySetModes = new() { "WEEKLY", "MONTHLY_DAY", "MONTHLY_ORD" };
    private const long DayMs = 86_400_000L;

    public static long? NextOccurrence(Item item, long after)
    {
        if (item.RepeatMode == "SPACED")
        {
            if (item.DueAt is not long due) return null;
            var next = due;
            while (next <= after) next += SpacedGapDays(item.SpacedStep) * DayMs;
            return next;
        }
        return Core(item.RepeatMode, item.RepeatDays, item.RepeatN, item.RepeatUnit, item.RepeatOrd, item.RepeatDow, item.RepeatOrdList, item.DueAt, after);
    }

    public static long? NextOccurrenceCall(CallReminder r, long after)
        => Core(r.RepeatMode, r.RepeatDays, r.RepeatN, r.RepeatUnit, r.RepeatOrd, r.RepeatDow, r.RepeatOrdList, r.RecurAt ?? r.CreatedAt, after);

    public static List<long> Preview(string mode, IReadOnlyCollection<int> days, int n, string unit, int ord, int dow, IReadOnlyList<int> ordList,
        long anchor, int count = 3, long? startFrom = null, int spacedStep = 0, long? nowOverride = null)
    {
        var out_ = new List<long>();
        var now = nowOverride ?? Clock.Now();
        var after = Math.Max(now, (startFrom ?? 0L) - 1L);
        var anchorEff = DaySetModes.Contains(mode) ? anchor - DayMs : anchor;
        var step = spacedStep;
        for (var i = 0; i < count; i++)
        {
            long? next;
            if (mode == "SPACED")
            {
                var nx = anchor;
                while (nx <= after) nx += SpacedGapDays(step) * DayMs;
                step++;
                next = nx;
            }
            else next = Core(mode, days.ToList(), n, unit, ord, dow, ordList, anchorEff, after);
            if (next is not long v) return out_;
            out_.Add(v); after = v;
        }
        return out_;
    }

    public static List<long> Within(string mode, IReadOnlyCollection<int> days, int n, string unit, int ord, int dow, IReadOnlyList<int> ordList,
        long anchor, long? startFrom, long untilMs, int cap = 400, int spacedStep = 0, long? nowOverride = null)
    {
        var out_ = new List<long>();
        var now = nowOverride ?? Clock.Now();
        var after = Math.Max(now, (startFrom ?? 0L) - 1L);
        var anchorEff = DaySetModes.Contains(mode) ? anchor - DayMs : anchor;
        var step = spacedStep;
        while (out_.Count < cap)
        {
            long? next;
            if (mode == "SPACED")
            {
                var nx = anchor;
                while (nx <= after) nx += SpacedGapDays(step) * DayMs;
                step++;
                next = nx;
            }
            else next = Core(mode, days.ToList(), n, unit, ord, dow, ordList, anchorEff, after);
            if (next is not long v) break;
            if (v > untilMs) break;
            out_.Add(v); after = v;
        }
        return out_;
    }

    /// <summary>ISO weekday 1=Mon..7=Sun (java DayOfWeek.value).</summary>
    public static int IsoDow(DateTime d) => d.DayOfWeek == DayOfWeek.Sunday ? 7 : (int)d.DayOfWeek;

    private static DateTime Bump(string mode, DateTime cur, DateTime dueLocal, IReadOnlyList<int> repeatDays, int repeatN, string repeatUnit, int repeatOrd, int repeatDow, IReadOnlyList<int> ordList)
    {
        switch (mode)
        {
            case "DAILY": return cur.AddDays(1);
            case "WEEKLY":
            {
                var days = (repeatDays.Count == 0 ? new List<int> { IsoDow(cur) } : repeatDays.ToList()).OrderBy(x => x).ToList();
                var c = cur.AddDays(1);
                var guard = 0;
                while (!days.Contains(IsoDow(c)) && guard++ < 14) c = c.AddDays(1);
                return c;
            }
            case "MONTHLY_DAY":
            {
                var daySet = repeatDays.Where(d => d >= 1 && d <= 31).OrderBy(d => d).ToList();
                if (daySet.Count == 0) daySet = new List<int> { dueLocal.Day };
                var curDate = cur.Date;
                var len = DateTime.DaysInMonth(curDate.Year, curDate.Month);
                var laterSameMonth = daySet.Select(d => Math.Min(d, len)).Where(d => d > curDate.Day).DefaultIfEmpty(-1).Min();
                DateTime next;
                if (laterSameMonth > 0) next = new DateTime(curDate.Year, curDate.Month, laterSameMonth);
                else
                {
                    var m = new DateTime(curDate.Year, curDate.Month, 1).AddMonths(1);
                    next = new DateTime(m.Year, m.Month, Math.Min(daySet[0], DateTime.DaysInMonth(m.Year, m.Month)));
                }
                return next.Add(cur.TimeOfDay);
            }
            case "MONTHLY_ORD":
            {
                var pats = ordList.Count == 0 ? new List<int> { repeatOrd * 10 + repeatDow } : ordList.ToList();
                static DateTime Hit(DateTime monthFirst, int pat)
                {
                    var ord = pat / 10; var dowIso = Math.Clamp(pat % 10, 1, 7);
                    if (ord >= 5)
                    {
                        var x = new DateTime(monthFirst.Year, monthFirst.Month, DateTime.DaysInMonth(monthFirst.Year, monthFirst.Month));
                        while (IsoDow(x) != dowIso) x = x.AddDays(-1);
                        return x;
                    }
                    var y = monthFirst;
                    while (IsoDow(y) != dowIso) y = y.AddDays(1);
                    return y.AddDays(7 * (ord - 1));
                }
                var curDate = cur.Date;
                var monthFirst = new DateTime(curDate.Year, curDate.Month, 1);
                DateTime? best = null; var g2 = 0;
                while (best == null && g2 < 24)
                {
                    var cands = pats.Select(p => Hit(monthFirst, p)).Where(d => d > curDate).ToList();
                    if (cands.Count > 0) best = cands.Min();
                    monthFirst = monthFirst.AddMonths(1); g2++;
                }
                return (best ?? curDate.AddMonths(1)).Add(cur.TimeOfDay);
            }
            case "QUARTERLY": return cur.AddMonths(3);
            case "HALFYEARLY": return cur.AddMonths(6);
            case "YEARLY": return cur.AddMonths(12);
            case "EVERY_N":
                return repeatUnit switch { "W" => cur.AddDays(7 * repeatN), "M" => cur.AddMonths(repeatN), _ => cur.AddDays(repeatN) };
            default: return cur.AddDays(1);
        }
    }

    private static long? Core(string repeatMode, IReadOnlyList<int> repeatDays, int repeatN, string repeatUnit, int repeatOrd, int repeatDow, IReadOnlyList<int> ordList, long? dueAnchor, long after)
    {
        if (dueAnchor is not long due) return null;
        if (repeatMode == "OFF") return null;
        var t = Time.ToLocal(due);
        var dueLocal = t;
        var afterLocal = Time.ToLocal(after);
        var guard = 0;
        while (t <= afterLocal && guard < 1000) { t = Bump(repeatMode, t, dueLocal, repeatDays, repeatN, repeatUnit, repeatOrd, repeatDow, ordList); guard++; }
        return Time.ToMs(t);
    }

    public static string? RepeatLabel(Item item) => RepeatLabel(item.RepeatMode, item.RepeatDays, item.RepeatN, item.RepeatUnit, item.RepeatOrd, item.RepeatDow, item.RepeatOrdList, item.SpacedStep);

    public static string? RepeatLabel(string mode, IReadOnlyList<int> repeatDays, int repeatN, string repeatUnit, int repeatOrd, int repeatDow, IReadOnlyList<int> ordList, int spacedStep = 0)
    {
        string[] names = { "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun" };
        string[] ords = { "First", "Second", "Third", "Fourth", "Last" };
        switch (mode)
        {
            case "SPACED": return $"Spaced · next gap {SpacedGapDays(spacedStep)}d";
            case "DAILY": return "Daily";
            case "WEEKLY":
            {
                var s = string.Join(",", repeatDays.OrderBy(x => x).Select(d => names[Math.Clamp(d - 1, 0, 6)]));
                return "Weekly · " + (s.Length == 0 ? "—" : s);
            }
            case "MONTHLY_DAY":
            {
                var ds = repeatDays.Where(d => d >= 1 && d <= 31).OrderBy(d => d).ToList();
                return ds.Count == 0 ? "Monthly · same day" : "Monthly · " + string.Join(",", ds);
            }
            case "MONTHLY_ORD":
            {
                var pats = ordList.Count == 0 ? new List<int> { repeatOrd * 10 + repeatDow } : ordList.ToList();
                return string.Join(" + ", pats.Select(p => ords[Math.Clamp(p / 10 - 1, 0, 4)] + " " + names[Math.Clamp(p % 10 - 1, 0, 6)]));
            }
            case "QUARTERLY": return "Quarterly";
            case "HALFYEARLY": return "Half-yearly";
            case "YEARLY": return "Yearly";
            case "EVERY_N": return $"Every {repeatN} " + (repeatUnit switch { "W" => "week(s)", "M" => "month(s)", _ => "day(s)" });
            default: return null;
        }
    }

    public static bool RepeatExhausted(int? count, int done) => count != null && done >= count.Value;
    public static int? SanitizeRepeatCount(int? n) => n == null ? null : n < 2 ? 2 : n;

    public static long ComputeReturnAt(long from, int value, LapseUnit unit)
    {
        var b = Time.ToLocal(from);
        var t = unit == LapseUnit.DAYS ? b.AddDays(value) : b.AddMonths(value);
        return Time.ToMs(t);
    }

    public static string LapseLabel(int value, LapseUnit unit)
    {
        var u = unit.Label();
        return $"{value} " + (value == 1 ? u[..^1] : u).ToLowerInvariant();
    }

    /// <summary>resurrectDue: done recurring items whose next-occurrence DAY has arrived come back to Active.</summary>
    public static List<Item> ResurrectDue(IEnumerable<Item> items, long now)
    {
        var today = Time.LocalDate(now);
        return items.Where(i => i.Done && i.DeletedAt == null && i.RepeatMode != "OFF" && i.DueAt != null && Time.LocalDate(i.DueAt.Value) <= today)
                    .Select(i => i with { Done = false, DoneAt = null }).ToList();
    }

    public static List<CallReminder> ResurrectCallsDue(IEnumerable<CallReminder> calls, long now)
    {
        var today = Time.LocalDate(now);
        return calls.Where(r => r.Done && r.DeletedAt == null && r.RepeatMode != "OFF" && r.RecurAt != null && Time.LocalDate(r.RecurAt.Value) <= today)
                    .Select(r => r with { Done = false, DoneAt = null }).ToList();
    }

    public static List<Item> ClearStaleOos(IEnumerable<Item> items, long now)
    {
        var today = Time.LocalDate(now);
        return items.Where(i => i.OosAt != null && Time.LocalDate(i.OosAt.Value) < today).Select(i => i with { OosAt = null }).ToList();
    }

    private const int RollMaxSteps = 400;

    /// <summary>N20: ACTIVE repeating items whose due day has passed roll forward; misses logged into missedAt.</summary>
    public static List<Item> RollMissedRepeats(IEnumerable<Item> items, long now)
    {
        var today = Time.StartOfDay(now);
        var out_ = new List<Item>();
        foreach (var i in items)
        {
            if (i.Done || i.DeletedAt != null) continue;
            if (i.RepeatMode == "OFF") continue;
            if (i.DueAt is not long due) continue;
            if (Time.StartOfDay(due) >= today) continue;
            if ((i.SnoozedUntil ?? 0L) > now) continue;
            var cur = due; var missed = new List<long>(); var steps = 0;
            while (Time.StartOfDay(cur) < today && steps < RollMaxSteps)
            {
                missed.Add(cur);
                var nx = NextOccurrence(i with { DueAt = cur }, cur);
                if (nx is not long v) break;
                cur = v; steps++;
            }
            if (steps >= RollMaxSteps) continue;
            if (Time.StartOfDay(cur) < today || missed.Count == 0) continue;
            missed.Reverse();
            out_.Add(i with { DueAt = cur, SnoozedUntil = null, MissedAt = missed.Concat(i.MissedAt).Take(Constants.MissedCap).ToList() });
        }
        return out_;
    }

    public static List<long> MissedRows(Item i) => i.MissedAt.OrderByDescending(x => x).Take(Constants.MissedCap).ToList();
}
