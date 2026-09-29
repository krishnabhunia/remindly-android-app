using Remindly.Core.Models;

namespace Remindly.Core.Logic;

public enum SchedKind { NONE, ONCE, REPEAT }
public enum ComingUpKind { SNOOZE, DUE, RETURNS, LAPSE_RETURN }
public sealed record ComingUpRow(long? At, string What, ComingUpKind Kind)
{
    public bool Cancellable => Kind == ComingUpKind.SNOOZE;
}

/// <summary>Pure alert vocabulary shared with the phone (Model.kt v1.56–v2.8).</summary>
public static class Alerts
{
    public static string AlertTypeLabel(string t) => t switch { "A" => "Alarm", "R" => "Ring", "OFF" => "Muted", _ => "Notify" };
    public static string AlertTypeIcon(string t) => t switch { "A" => "⏰", "R" => "🔊", "OFF" => "🔕", _ => "🔔" };

    /// <summary>alertsEnabledFor: per-tab ON/OFF wins, INHERIT follows the global master.</summary>
    public static bool EnabledFor(Tab? tab, AppSettings s)
    {
        var per = tab switch { Tab.SHOP => s.ShopAlertsOn, Tab.LEARN => s.LearnAlertsOn, Tab.TASKS => s.TasksAlertsOn, _ => s.CallsAlertsOn };
        return AppSettings.Tri(per, s.AlertsEnabled);
    }

    /// <summary>resolveAlertTypes: the item's own letter; muted = "".</summary>
    public static string ResolveItem(Item i) => i.AlertType == Constants.AlertMuted ? "" : (i.AlertType is "A" or "R" or "N" ? i.AlertType : "N");
    public static string ResolveCall(CallReminder r) => r.AlertType is "A" or "R" or "N" ? r.AlertType : "N";

    public static int CoerceRingSeconds(int v) => v <= 0 ? 7 : v < 3 ? 3 : v > 180 ? 180 : v;
    public static int SnoozeMinutes(AppSettings s) => Math.Clamp(s.SnoozeM1, 1, 24 * 60);
    public static long SnoozeTargetMs(AppSettings s, long now) => now + SnoozeMinutes(s) * 60_000L;
    public static long? LiveSnooze(long? at, long now) => at is long a && a > now ? a : null;
    public static string SnoozeMinLabel(int m) => $"{m} m";

    /// <summary>quietDemote (N13): Quiet = "this reminder becomes a Notification" — permanent on the record.</summary>
    public static Item QuietDemote(Item i, long fireAt) => i with { AlertType = "N", SnoozedUntil = fireAt };

    public static List<ComingUpRow> ComingUp(Item i, long now)
    {
        var rows = new List<ComingUpRow>();
        var snooze = LiveSnooze(i.SnoozedUntil, now);
        if (snooze != null) rows.Add(new ComingUpRow(snooze, "Snoozed · " + AlertTypeLabel(i.AlertType), ComingUpKind.SNOOZE));
        else if (!i.Done && i.DueAt is long d && d > now) rows.Add(new ComingUpRow(d, "Due · " + AlertTypeLabel(i.AlertType), ComingUpKind.DUE));
        if (i.Done && i.RepeatMode != "OFF" && i.DueAt is long r) rows.Add(new ComingUpRow(r, "Returns", ComingUpKind.RETURNS));
        if (i.ReturnAt is long ra && ra > now) rows.Add(new ComingUpRow(ra, "Back on your list", ComingUpKind.LAPSE_RETURN));
        return rows;
    }

    // ── schedule kinds (N24/N25) ──
    public static SchedKind ToKind(string? v) => v switch { "NONE" => SchedKind.NONE, "REPEAT" => SchedKind.REPEAT, _ => SchedKind.ONCE };
    public static SchedKind KindOf(Item i) => i.RepeatMode != "OFF" ? SchedKind.REPEAT : i.DueAt == null ? SchedKind.NONE : SchedKind.ONCE;

    public static Item ClearSchedule(Item i) => i with
    {
        DueAt = null, DueHasTime = false, SnoozedUntil = null,
        RepeatMode = "OFF", RepeatDays = new List<int>(), RepeatN = 1, RepeatUnit = "D",
        RepeatOrd = 1, RepeatDow = 1, RepeatOrdList = new List<int>(), RepeatCount = null, RepeatDone = 0,
    };

    public static SchedKind SchedDefaultFor(AppSettings s, Tab tab)
    {
        var ov = tab switch { Tab.SHOP => s.ShopSchedDefault, Tab.LEARN => s.LearnSchedDefault, _ => s.TasksSchedDefault };
        var k = ov != "INHERIT" ? ToKind(ov) : ToKind(s.SchedDefault);
        return tab == Tab.SHOP && k == SchedKind.NONE ? SchedKind.ONCE : k;
    }

    // ── new-item defaults (v1.8 / v1.47) ──
    public static bool NewDueTimedFor(Tab tab, AppSettings s)
    {
        var per = tab switch { Tab.SHOP => s.ShopNewDueTimed, Tab.LEARN => s.LearnNewDueTimed, _ => s.TasksNewDueTimed };
        return AppSettings.Tri(per, s.GlobalNewDueTimed);
    }

    public static long? DefaultNewDue(Tab tab, AppSettings s, long? nowOverride = null)
    {
        (string mode, int days, int mins) = tab switch
        {
            Tab.SHOP => (s.ShopNewDueMode, s.ShopNewDueDays, s.ShopNewDueMinutes),
            Tab.LEARN => (s.LearnNewDueMode, s.LearnNewDueDays, s.LearnNewDueMinutes),
            _ => (s.TasksNewDueMode, s.TasksNewDueDays, s.TasksNewDueMinutes),
        };
        if (mode == "INHERIT") { mode = s.GlobalNewDueMode; days = s.GlobalNewDueDays; mins = s.GlobalNewDueMinutes; }
        if (mode == "OFF") return null;
        if (!NewDueTimedFor(tab, s)) mins = s.DefaultDueMinutes;
        var addDays = mode switch { "TODAY" => 0, "IN_N" => days, _ => 1 };
        var today = Time.LocalDate(nowOverride ?? Clock.Now());
        return Time.AtMinuteOfDay(today.AddDays(addDays), mins);
    }

    public static int ClearFor(AppSettings s, Tab? tab)
    {
        var per = tab switch { Tab.SHOP => s.ShopDoneClearDays, Tab.LEARN => s.LearnDoneClearDays, Tab.TASKS => s.TasksDoneClearDays, _ => s.CallsDoneClearDays };
        return per > 0 ? per : s.GlobalDoneClearDays;
    }

    public static string SortOf(AppSettings s, Tab tab) => tab switch { Tab.SHOP => s.ShopSort, Tab.LEARN => s.LearnSort, _ => s.TasksSort };

    public static int RankOf(Priority? p) => p switch { Priority.URGENT => 0, Priority.HIGH => 1, Priority.LOW => 3, _ => 2 };

    public static bool ShowPriorityTag(Priority? p, AppSettings s) => p != null && (p != Priority.MEDIUM || s.ShowMediumTag);

    public static bool IsOverdueDay(long? dueAt, long now) => dueAt is long d && Time.LocalDate(d) < Time.LocalDate(now);

    public static int PendingTodayCount(IEnumerable<Item> items, Tab tab, long startOfTomorrow)
        => items.Count(i => i.Tab == tab && !i.Done && i.DeletedAt == null && i.DueAt != null && i.DueAt.Value < startOfTomorrow);

    public static bool DupActiveMatch(IEnumerable<Item> items, Tab tab, string title)
    {
        var t = title.Trim().ToLowerInvariant();
        if (t.Length == 0) return false;
        return items.Any(i => i.Tab == tab && !i.Done && i.DeletedAt == null && i.Title.Trim().ToLowerInvariant() == t);
    }

    /// <summary>Snooze / dismiss toast texts kept in sync with the phone's wording.</summary>
    public static string SnoozeToast(int minutes, long fireAt, string clock) => $"Snoozed {minutes} m · back at {clock}";
    public static string QuietToast(long fireAt, string clock) => $"Quiet · notifies at {clock}";

    // ── shop arrival vocabulary (N37) ──
    public static string ResolveShopArriveTypes(Shop shop, AppSettings s)
        => Heal.NormalizeAlertTypes(shop.ArriveTypes) ?? (Heal.NormalizeAlertTypes(s.ShopArriveTypes) ?? "N");

    public static string AlertTypesLabel(string? types)
    {
        var t = Heal.NormalizeAlertTypes(types);
        if (t == null) return "Default";
        if (t.Length == 0) return "Silent";
        var parts = new List<string>();
        if (t.Contains('A')) parts.Add("Alarm");
        if (t.Contains('R') && !t.Contains('A')) parts.Add("Ring");
        if (t.Contains('N')) parts.Add("Notify");
        return string.Join(" + ", parts);
    }

    public static List<Item> BuyNowItems(IEnumerable<Item> items, Shop? shop)
    {
        if (shop == null || shop.DeletedAt != null) return new List<Item>();
        var name = shop.Name.Trim();
        return items.Where(i => i.Tab == Tab.SHOP && i.DeletedAt == null && !i.Done &&
            (i.ShopId == shop.Id || (i.ShopId == null && string.Equals((i.ShopName ?? "").Trim(), name, StringComparison.OrdinalIgnoreCase)))).ToList();
    }
}
