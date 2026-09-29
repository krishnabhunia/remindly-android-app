using Remindly.Core.Models;

namespace Remindly.Core.Logic;

/// <summary>
/// Port of the heal* functions (Model.kt "v1.10 load-healing"): every record that enters from
/// disk or from Firestore is rebuilt with explicit fallbacks so a field a writer omitted never
/// leaves a null where the model expects a value. Radii snap to the 13-stop scale (v2.02).
/// </summary>
public static class Heal
{
    public static readonly float[] RadiusStops = { 50f, 100f, 150f, 200f, 250f, 300f, 400f, 500f, 750f, 1000f, 1500f, 2000f, 3000f };

    public static float SnapRadius(float v)
    {
        if (!float.IsFinite(v) || v <= 0f) return RadiusStops[0];
        float best = RadiusStops[0], bestD = float.MaxValue;
        foreach (var stop in RadiusStops)
        {
            var d = Math.Abs(stop - v);
            if (d < bestD) { best = stop; bestD = d; }   // strict < keeps the LOWER stop on a tie
        }
        return best;
    }

    public static string RadiusLabel(float v)
    {
        var r = float.IsFinite(v) && v > 0f ? v : RadiusStops[0];
        if (r < 1000f) return $"{(int)r} m";
        var km = r / 1000f;
        return km == (int)km ? $"{(int)km} km" : $"{km:0.0} km";
    }

    /// <summary>normalizeAlertTypes: null = follow default; "" = deliberately silent; else subset of "NRA".</summary>
    public static string? NormalizeAlertTypes(string? raw)
    {
        if (raw == null) return null;
        if (raw.Length > 0 && string.IsNullOrWhiteSpace(raw)) return null;
        var up = raw.ToUpperInvariant();
        return new string("NRA".Where(c => up.Contains(c)).ToArray());
    }

    public static Item Item(Item i) => i with
    {
        AlertType = i.AlertType ?? "N",
        Title = i.Title ?? "",
        Notes = i.Notes ?? "",
        RepeatMode = i.RepeatMode ?? "OFF",
        RepeatDays = i.RepeatDays ?? new List<int>(),
        RepeatUnit = i.RepeatUnit ?? "D",
        RepeatOrdList = i.RepeatOrdList ?? new List<int>(),
        PriceHistory = i.PriceHistory ?? new List<PricePoint>(),
        MissedAt = i.MissedAt ?? new List<long>(),
    };

    public static CallReminder Call(CallReminder r) => r with
    {
        AlertType = r.AlertType ?? "N",
        Number = r.Number ?? "",
        RepeatMode = r.RepeatMode ?? "OFF",
        RepeatDays = r.RepeatDays ?? new List<int>(),
        RepeatUnit = r.RepeatUnit ?? "D",
        RepeatOrdList = r.RepeatOrdList ?? new List<int>(),
        SavedGroups = r.SavedGroups ?? new List<string>(),
    };

    public static GeoPlace Place(GeoPlace p) => p with
    {
        GroupFilter = p.GroupFilter ?? new List<string>(),
        Name = p.Name ?? "",
        Radius = SnapRadius(p.Radius),
    };

    public static Shop Shop(Shop s) => s with
    {
        Name = s.Name ?? "",
        ArriveTypes = NormalizeAlertTypes(s.ArriveTypes),
        Area = string.IsNullOrWhiteSpace(s.Area) ? null : s.Area!.Trim(),
        Radius = SnapRadius(s.Radius > 0f ? s.Radius : 150f),
    };

    public static City City(City c) => c with { Name = (c.Name ?? "").Trim() };
    public static Chain Chain(Chain c) => c with { Name = (c.Name ?? "").Trim() };

    public static Product Product(Product p) => p with
    {
        Name = (p.Name ?? "").Trim(),
        Category = string.IsNullOrWhiteSpace(p.Category) ? null : p.Category!.Trim(),
        DefaultUnit = string.IsNullOrWhiteSpace(p.DefaultUnit) ? null : p.DefaultUnit!.Trim(),
    };

    public static ProductLink Link(ProductLink l) => l with
    {
        LastPrice = double.IsFinite(l.LastPrice) && l.LastPrice >= 0 ? l.LastPrice : 0.0,
        LastUnitPrice = double.IsFinite(l.LastUnitPrice) && l.LastUnitPrice >= 0 ? l.LastUnitPrice : 0.0,
    };

    /// <summary>healSettings — only the coats that matter to what Windows reads; the rest rides through raw.</summary>
    public static AppSettings Settings(AppSettings a)
    {
        var s = a.Clone();
        if (s.Raw["theme"] is null) s.Theme = "SYSTEM";
        if (s.Raw["timeFormat"] is null) s.TimeFormat = "PHONE";
        s.ShopArriveTypes = NormalizeAlertTypes(s.ShopArriveTypes) ?? "N";
        s.DefaultRadius = SnapRadius(s.DefaultRadius);
        s.ShopNewRadius = SnapRadius(s.ShopNewRadius > 0f ? s.ShopNewRadius : 150f);
        s.AlarmRingSeconds = Math.Clamp(Alerts.CoerceRingSeconds(s.AlarmRingSeconds), 0, 1800);
        s.RingRingSeconds = Math.Clamp(Alerts.CoerceRingSeconds(s.RingRingSeconds), 0, 1800);
        if (s.Raw["tasksGroups"] is null) s.TasksGroups = new List<string>();
        if (s.Raw["shopGroups"] is null) s.ShopGroups = new List<string>();
        if (s.Raw["learnTopics"] is null) s.LearnTopics = new List<string>();
        return s;
    }
}
