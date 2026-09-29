using System.Text.Json.Nodes;

namespace Remindly.Core.Models;

// ─────────────────────────────────────────────────────────────────────────────
// WIRE CONTRACT — mirrors Remindly Android 2.8 (Model.kt) field-for-field.
//
// Every record the phone writes to Firestore is a Gson string:
//   { "json": "<record>", "updatedAt": <ms>, "schemaVer": 71 }
// Field names are the Kotlin property names (camelCase), enums travel as their
// NAMES ("TASKS", "HIGH", "DAYS", "ARRIVE", "MANUAL"), Long → integer, Double →
// number, List → array, Map → object, Set<Long> → array.
//
// Rules this port keeps (see Sync.kt on the Android side):
//  • ALWAYS write every field (Android's healers assume non-null lists/strings).
//  • Unknown keys are preserved via [JsonExtensionData] so a field added by a
//    future Android build is never dropped when Windows re-writes the record.
//  • updatedAt is the merge stamp; deletedAt is the soft-delete tombstone.
// ─────────────────────────────────────────────────────────────────────────────

public enum Tab { TASKS, SHOP, LEARN }
public enum Priority { LOW, MEDIUM, HIGH, URGENT }
public enum LapseUnit { DAYS, MONTHS }
public enum TriggerType { LEAVE, ARRIVE }
public enum CallSource { AUTO, MANUAL }

public static class EnumLabels
{
    public static string Label(this Tab t) => t switch { Tab.TASKS => "Tasks", Tab.SHOP => "Buy", Tab.LEARN => "Learn", _ => t.ToString() };
    public static string Label(this Priority p) => p switch { Priority.LOW => "Low", Priority.MEDIUM => "Medium", Priority.HIGH => "High", Priority.URGENT => "Urgent", _ => p.ToString() };
    public static string Label(this LapseUnit u) => u == LapseUnit.DAYS ? "Days" : "Months";
    public static string Label(this TriggerType t) => t == TriggerType.LEAVE ? "When I leave" : "When I arrive";
    public static string Label(this CallSource s) => s == CallSource.AUTO ? "Auto" : "Manual";
}

public static class Constants
{
    public static readonly string[] PlatformOptions = { "Online", "Offline", "Internet", "LinkedIn Learning", "Udemy" };
    public static readonly string[] Units = { "kg", "g", "L", "ml", "pcs", "dozen", "strip", "pack" };
    public static readonly string[] RepeatModes = { "OFF", "DAILY", "WEEKLY", "MONTHLY_DAY", "MONTHLY_ORD", "QUARTERLY", "HALFYEARLY", "YEARLY", "EVERY_N", "SPACED" };
    public const string AlertMuted = "OFF";
    public const int MissedCap = 10;
    public const long BinKeepMs = 30L * 24 * 60 * 60 * 1000;
    /// <summary>Android SyncRepo.SYNC_SCHEMA — the build's wire schema. Bump only when Android bumps it.</summary>
    public const int SyncSchema = 71;
}

/// <summary>A recorded purchase (Item.priceHistory, last 12).</summary>
public sealed record PricePoint
{
    public long At { get; init; }
    public double Price { get; init; }
    public string Shop { get; init; } = "";
    public double Qty { get; init; }
    public string Unit { get; init; } = "";
    public double UnitPrice { get; init; }
    public double Paid { get; init; }
    public double DiscountPct { get; init; }
    [JsonExtensionData] public Dictionary<string, JsonElement>? Extra { get; init; }
}

public interface ISynced
{
    long Id { get; }
    long UpdatedAt { get; }
    long? DeletedAt { get; }
}

/// <summary>Tasks / Learn / Buy card. Order and defaults follow Item(...) in Model.kt.</summary>
public sealed record Item : ISynced
{
    public long Id { get; init; }
    public Tab Tab { get; init; } = Tab.TASKS;
    public string Title { get; init; } = "";
    public string Notes { get; init; } = "";
    public long CreatedAt { get; init; } = Clock.Now();
    public long? DueAt { get; init; }
    public Priority? Priority { get; init; }
    /// <summary>"N" notify · "R" ring · "A" alarm · "OFF" muted (v2.8).</summary>
    public string AlertType { get; init; } = "N";
    public long? SnoozedUntil { get; init; }
    // Shop extras
    public string? Quantity { get; init; }
    public string? Price { get; init; }
    public string? ShopName { get; init; }
    public int? LapseValue { get; init; }
    public LapseUnit? LapseUnit { get; init; }
    public long? ExpiryAt { get; init; }
    public bool Personal { get; init; }
    // Learn extras
    public string? Platform { get; init; }
    public string? Url { get; init; }
    public string? Topic { get; init; }
    public List<long> MissedAt { get; init; } = new();
    public bool DueHasTime { get; init; } = true;
    // recurrence
    public string RepeatMode { get; init; } = "OFF";
    public List<int> RepeatDays { get; init; } = new();
    public int RepeatN { get; init; } = 1;
    public string RepeatUnit { get; init; } = "D";
    public int RepeatOrd { get; init; } = 1;
    public int RepeatDow { get; init; } = 1;
    public List<int> RepeatOrdList { get; init; } = new();
    public int? RepeatCount { get; init; }
    public int RepeatDone { get; init; }
    public long UpdatedAt { get; init; }
    public long? DeletedAt { get; init; }
    public int Progress { get; init; }
    public int SpacedStep { get; init; }
    public double HoursSpent { get; init; }
    public long? OosAt { get; init; }
    public string? Unit { get; init; }
    public long? ProductId { get; init; }
    public long? ShopId { get; init; }
    public bool Staple { get; init; }
    public List<PricePoint> PriceHistory { get; init; } = new();
    public long? CalEventId { get; init; }
    public string? Group { get; init; }
    public bool Done { get; init; }
    public long? DoneAt { get; init; }
    public long? ReturnAt { get; init; }
    [JsonExtensionData] public Dictionary<string, JsonElement>? Extra { get; init; }

    [JsonIgnore] public bool IsLive => DeletedAt == null;
    [JsonIgnore] public bool IsMuted => AlertType == Constants.AlertMuted;
}

public sealed record GeoPlace : ISynced
{
    public long Id { get; init; }
    public string Name { get; init; } = "";
    public double Lat { get; init; }
    public double Lng { get; init; }
    public float Radius { get; init; } = 150f;
    public TriggerType Trigger { get; init; } = TriggerType.LEAVE;
    public bool Enabled { get; init; } = true;
    public long LastFired { get; init; }
    public List<string> GroupFilter { get; init; } = new();
    public long UpdatedAt { get; init; }
    public long? DeletedAt { get; init; }
    [JsonExtensionData] public Dictionary<string, JsonElement>? Extra { get; init; }
}

public sealed record Shop : ISynced
{
    public long Id { get; init; }
    public string Name { get; init; } = "";
    public string? Area { get; init; }
    public double? Lat { get; init; }
    public double? Lng { get; init; }
    public float Radius { get; init; } = 150f;
    public bool IsDefault { get; init; }
    public long? CityId { get; init; }
    public long? ChainId { get; init; }
    /// <summary>Subset of "NRA"; null = follow the Shops ⚙ default; "" = silent.</summary>
    public string? ArriveTypes { get; init; }
    public long LastArriveFired { get; init; }
    public long UpdatedAt { get; init; }
    public long? DeletedAt { get; init; }
    [JsonExtensionData] public Dictionary<string, JsonElement>? Extra { get; init; }

    [JsonIgnore] public bool HasGeofence => Lat != null && Lng != null;
}

public sealed record City : ISynced
{
    public long Id { get; init; }
    public string Name { get; init; } = "";
    public long UpdatedAt { get; init; }
    public long? DeletedAt { get; init; }
    [JsonExtensionData] public Dictionary<string, JsonElement>? Extra { get; init; }
}

public sealed record Chain : ISynced
{
    public long Id { get; init; }
    public string Name { get; init; } = "";
    public long UpdatedAt { get; init; }
    public long? DeletedAt { get; init; }
    [JsonExtensionData] public Dictionary<string, JsonElement>? Extra { get; init; }
}

public sealed record Product : ISynced
{
    public long Id { get; init; }
    public string Name { get; init; } = "";
    public string? Category { get; init; }
    public string? DefaultUnit { get; init; }
    public string? Note { get; init; }
    public long UpdatedAt { get; init; }
    public long? DeletedAt { get; init; }
    [JsonExtensionData] public Dictionary<string, JsonElement>? Extra { get; init; }
}

/// <summary>
/// Product ↔ Shop link. Document id = "{productId}_{shopId}" (Android linkDocId). The merge stamp
/// is the latest of updatedAt (added in Android 2.9 / N29), lastAt and deletedAt — see Merge.LinkStamp
/// and Android linkStamp — so links written by 2.8 (no updatedAt) still merge sensibly.
/// </summary>
public sealed record ProductLink
{
    public long ProductId { get; init; }
    public long ShopId { get; init; }
    public double LastPrice { get; init; }
    public double LastUnitPrice { get; init; }
    public long LastAt { get; init; }
    public long? DeletedAt { get; init; }
    public long UpdatedAt { get; init; }
    [JsonExtensionData] public Dictionary<string, JsonElement>? Extra { get; init; }

    [JsonIgnore] public string Key => $"{ProductId}_{ShopId}";
}

public sealed record CallReminder : ISynced
{
    public long Id { get; init; }
    public string Number { get; init; } = "";
    public string? Name { get; init; }
    public string AlertType { get; init; } = "N";
    public long? SnoozedUntil { get; init; }
    public string? Message { get; init; }
    public string? FirstName { get; init; }
    public string? LastName { get; init; }
    public string? Company { get; init; }
    public CallSource Source { get; init; } = CallSource.MANUAL;
    public long CreatedAt { get; init; } = Clock.Now();
    public long? LastMissedAt { get; init; }
    public string? Note { get; init; }
    public long UpdatedAt { get; init; }
    public long? DeletedAt { get; init; }
    public string RepeatMode { get; init; } = "OFF";
    public List<int> RepeatDays { get; init; } = new();
    public int RepeatN { get; init; } = 1;
    public string RepeatUnit { get; init; } = "D";
    public int RepeatOrd { get; init; } = 1;
    public int RepeatDow { get; init; } = 1;
    public List<int> RepeatOrdList { get; init; } = new();
    public long? RecurAt { get; init; }
    public string? Label { get; init; }
    public List<string> SavedGroups { get; init; } = new();
    public int? RepeatCount { get; init; }
    public int RepeatDone { get; init; }
    public long? CalEventId { get; init; }
    public int MissedCount { get; init; }
    public bool Done { get; init; }
    public long? DoneAt { get; init; }
    public string? ClearedNote { get; init; }
    public long? NagAt { get; init; }
    public bool NagFired { get; init; }
    [JsonExtensionData] public Dictionary<string, JsonElement>? Extra { get; init; }

    [JsonIgnore] public string Display => Calls.DisplayOf(FirstName, LastName, Name, Number);
}

public static class Calls
{
    public static string? FullName(string? first, string? last)
    {
        var s = string.Join(" ", new[] { first?.Trim(), last?.Trim() }.Where(x => !string.IsNullOrEmpty(x)));
        return s.Length == 0 ? null : s;
    }

    public static string DisplayOf(string? first, string? last, string? name, string number)
        => FullName(first, last) ?? (string.IsNullOrWhiteSpace(name) ? number : name!.Trim());

    /// <summary>Digits only, last 10 kept — matches Indian numbers with/without +91.</summary>
    public static string NormalizePhone(string n)
    {
        var digits = new string(n.Where(char.IsDigit).ToArray());
        return digits.Length > 10 ? digits[^10..] : digits;
    }
}

/// <summary>Android Backup.DataBlob / BackupBlob — the JSON files the phone exports.</summary>
public sealed class DataBlob
{
    public string Kind { get; set; } = "remindly-data";
    public List<Item> Items { get; set; } = new();
    public List<CallReminder> Calls { get; set; } = new();
    public List<GeoPlace> Places { get; set; } = new();
    public List<string> TasksGroups { get; set; } = new();
    public List<string> ShopGroups { get; set; } = new();
    public List<string> LearnTopics { get; set; } = new();
    // Windows extension (ignored by Android, harmless): the shop-mode catalogue.
    public List<Shop>? Shops { get; set; }
    public List<City>? Cities { get; set; }
    public List<Chain>? Chains { get; set; }
    public List<Product>? Products { get; set; }
    public List<ProductLink>? ProductLinks { get; set; }
    [JsonExtensionData] public Dictionary<string, JsonElement>? Extra { get; set; }
}

/// <summary>Testable clock. Production = wall clock; tests pin it.</summary>
public static class Clock
{
    public static Func<long> Provider { get; set; } = () => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    public static long Now() => Provider();
}
