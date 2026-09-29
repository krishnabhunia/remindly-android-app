namespace Remindly.Core;

// The Windows data model mirrors the Android app's records FIELD FOR FIELD (same names, same
// meaning, epoch-millisecond times, enum names as strings). That keeps one JSON shape for both:
// an Android backup ("remindly-data-*.json") loads straight into the Windows store.

public enum Tab { TASKS, SHOP, LEARN }

public enum Priority { LOW, MEDIUM, HIGH, URGENT }

public enum LapseUnit { DAYS, MONTHS }

public enum CallSource { AUTO, MANUAL }

public static class TabNames
{
    /// <summary>User-facing name. Tab.SHOP is the Buy tab (its internal name predates Shop mode).</summary>
    public static string Title(Tab t) => t switch { Tab.SHOP => "Buy", Tab.LEARN => "Learn", _ => "Tasks" };
}

public static class PriorityNames
{
    public static string Label(Priority? p) => p switch
    {
        Priority.LOW => "Low", Priority.MEDIUM => "Medium", Priority.HIGH => "High", Priority.URGENT => "Urgent", _ => "None",
    };

    /// <summary>Urgent first, None last (Android rankOf).</summary>
    public static int Rank(Priority? p) => p switch
    {
        Priority.URGENT => 0, Priority.HIGH => 1, Priority.MEDIUM => 2, Priority.LOW => 3, _ => 4,
    };
}

/// <summary>A recorded purchase (last 12 kept per item).</summary>
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
}

/// <summary>A Tasks / Learn / Buy card (Android: data class Item).</summary>
public sealed record Item
{
    public long Id { get; init; }
    public Tab Tab { get; init; }
    public string Title { get; init; } = "";
    public string Notes { get; init; } = "";
    public long CreatedAt { get; init; }
    public long? DueAt { get; init; }
    public Priority? Priority { get; init; }
    /// <summary>"N" notify · "R" ring · "A" alarm (any combination) · "OFF" muted.</summary>
    public string AlertType { get; init; } = "N";
    public long? SnoozedUntil { get; init; }
    // Buy extras
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
    // Recurrence
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
    /// <summary>Buy grouping label; mirrors the list NAME (2.11) so the Classic view keeps working.</summary>
    public string? Group { get; init; }
    /// <summary>2.11 (N48): the ShopList this Buy item belongs to (null = Unsorted).</summary>
    public long? ListId { get; init; }
    public bool Done { get; init; }
    public long? DoneAt { get; init; }
    public long? ReturnAt { get; init; }
}

/// <summary>A call-back reminder (Calls tab).</summary>
public sealed record CallReminder
{
    public long Id { get; init; }
    public string Number { get; init; } = "";
    public string? Name { get; init; }
    public string AlertType { get; init; } = "N";
    public long? SnoozedUntil { get; init; }
    /// <summary>Optional WhatsApp message offered with the reminder.</summary>
    public string? Message { get; init; }
    public string? FirstName { get; init; }
    public string? LastName { get; init; }
    public string? Company { get; init; }
    public CallSource Source { get; init; } = CallSource.MANUAL;
    public long CreatedAt { get; init; }
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
    /// <summary>When the call-back is due (next occurrence for a recurring one).</summary>
    public long? RecurAt { get; init; }
    public string? Label { get; init; }
    public int? RepeatCount { get; init; }
    public int RepeatDone { get; init; }
    public int MissedCount { get; init; }
    public bool Done { get; init; }
    public long? DoneAt { get; init; }

    public string Display => CallText.DisplayOf(FirstName, LastName, Name, Number);
}

public static class CallText
{
    public static string? FullName(string? first, string? last)
    {
        var s = ((first ?? "").Trim() + " " + (last ?? "").Trim()).Trim();
        return s.Length == 0 ? null : s;
    }

    public static string DisplayOf(string? first, string? last, string? name, string number) =>
        FullName(first, last) ?? (string.IsNullOrWhiteSpace(name) ? null : name.Trim()) ?? number;

    /// <summary>Digits and a leading + only ("+91 98300-12345" → "+919830012345").</summary>
    public static string NormalizePhone(string n)
    {
        var t = (n ?? "").Trim();
        var digits = new string(t.Where(char.IsDigit).ToArray());
        return t.StartsWith('+') ? "+" + digits : digits;
    }

    /// <summary>wa.me wants digits only, country code included, no +.</summary>
    public static string WhatsAppUrl(string number, string? text)
    {
        var d = new string((number ?? "").Where(char.IsDigit).ToArray());
        var baseUrl = d.Length > 0 ? $"https://wa.me/{d}" : "https://wa.me/";
        return string.IsNullOrEmpty(text) ? baseUrl : baseUrl + "?text=" + Uri.EscapeDataString(text);
    }
}

/// <summary>A geofenced place (Android only — kept so an imported backup is not lossy).</summary>
public sealed record GeoPlace
{
    public long Id { get; init; }
    public string Name { get; init; } = "";
    public double Lat { get; init; }
    public double Lng { get; init; }
    public float Radius { get; init; }
    public string Trigger { get; init; } = "ARRIVE";
    public bool Enabled { get; init; } = true;
    public long LastFired { get; init; }
    public List<string> GroupFilter { get; init; } = new();
    public long UpdatedAt { get; init; }
    public long? DeletedAt { get; init; }
}

/// <summary>A registered shop (Shops tab). The geofence fields are kept for Android parity.</summary>
public sealed record Shop
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
    public string? ArriveTypes { get; init; }
    public long LastArriveFired { get; init; }
    public long UpdatedAt { get; init; }
    public long? DeletedAt { get; init; }
}

public sealed record City
{
    public long Id { get; init; }
    public string Name { get; init; } = "";
    public long UpdatedAt { get; init; }
    public long? DeletedAt { get; init; }
}

public sealed record Chain
{
    public long Id { get; init; }
    public string Name { get; init; } = "";
    public long UpdatedAt { get; init; }
    public long? DeletedAt { get; init; }
}

/// <summary>A catalogued product the Buy lists can reference (Products tab).</summary>
public sealed record Product
{
    public long Id { get; init; }
    public string Name { get; init; } = "";
    public string? Category { get; init; }
    public string? DefaultUnit { get; init; }
    public string? Note { get; init; }
    public long UpdatedAt { get; init; }
    public long? DeletedAt { get; init; }
}

/// <summary>2.11 (N48): a Buy list (Groceries, Monthly stock, a party…).</summary>
public sealed record ShopList
{
    public long Id { get; init; }
    public string Name { get; init; } = "";
    public string? Icon { get; init; }
    public bool Pinned { get; init; }
    public int Order { get; init; }
    public long? UsualShopId { get; init; }
    /// <summary>Any instant on the shopping DAY (local); a reminder fires at 09:00 that day.</summary>
    public long? ShoppingDay { get; init; }
    /// <summary>Private list: every item in it is Personal.</summary>
    public bool Personal { get; init; }
    public long CreatedAt { get; init; }
    public long UpdatedAt { get; init; }
    public long? DeletedAt { get; init; }
}

/// <summary>
/// Settings. The Buy-list and sharing fields carry the Android names (they arrive with an Android
/// backup); the rest are Windows-only.
/// </summary>
public sealed record AppSettings
{
    // ── shared with Android (2.11) ──
    public List<ShopList> ShopLists { get; init; } = new();
    public long? ShopDefaultListId { get; init; }
    public bool ShareIncludeDone { get; init; }
    public bool ShareIncludeQty { get; init; } = true;
    public bool ShareUrgentTag { get; init; } = true;
    public bool ShareBoughtTag { get; init; } = true;
    public string ShareHeadingSuffix { get; init; } = ":-";
    public List<string> ShopGroups { get; init; } = new();
    public List<string> ShopGroupOrder { get; init; } = new();
    public Dictionary<string, string> GroupIcons { get; init; } = new();
    public string ShopDefaultGroup { get; init; } = "";

    // ── Windows ──
    /// <summary>The "Auto update" checkbox: check GitHub once a day and install new versions in the background.</summary>
    public bool UpdateAutoCheck { get; init; } = true;
    /// <summary>Epoch ms of the last update check (0 = never).</summary>
    public long UpdateLastCheck { get; init; }
    public bool StartWithWindows { get; init; }
    public bool CloseToTray { get; init; } = true;
    /// <summary>"TASK" or "SHOP" — the mode reopened at the next start.</summary>
    public string LastMode { get; init; } = "TASK";
    /// <summary>RECENT · AZ · CUSTOM (Lists screen sort chip).</summary>
    public string ListSort { get; init; } = "RECENT";
    /// <summary>DATE · SHOP · CATEGORY · PRIORITY · NONE (grouping inside a list).</summary>
    public string ListInnerGroup { get; init; } = "DATE";
    public int SnoozeMinutes { get; init; } = 10;
    /// <summary>Hour a date-only due rings at.</summary>
    public int DefaultDueHour { get; init; } = 9;
    public int DeviceTag { get; init; }
}

/// <summary>Everything Remindly for Windows stores, in one JSON document.</summary>
public sealed class RemindlyData
{
    public int Version { get; set; } = 2;
    public string App { get; set; } = "Remindly";
    public long ExportedAt { get; set; }
    public List<Item> Items { get; set; } = new();
    public List<GeoPlace> Places { get; set; } = new();
    public List<CallReminder>? Calls { get; set; } = new();
    public AppSettings Settings { get; set; } = new();
    // Windows keeps the Shop-mode catalogues in the same document (Android keeps separate files).
    public List<Shop> Shops { get; set; } = new();
    public List<Product> Products { get; set; } = new();
    public List<City> Cities { get; set; } = new();
    public List<Chain> Chains { get; set; } = new();
    /// <summary>Alerts already shown ("I123@1727600000000"), so a restart never repeats one.</summary>
    public List<string> FiredKeys { get; set; } = new();
}
