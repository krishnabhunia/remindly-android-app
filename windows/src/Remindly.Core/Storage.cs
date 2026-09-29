using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Remindly.Core;

public static class AppPaths
{
    /// <summary>Overridable (tests, --data-dir). Default: %LOCALAPPDATA%\Remindly — shared by the installed and the portable copy.</summary>
    public static string DataRoot { get; set; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData, Environment.SpecialFolderOption.DoNotVerify), "Remindly");

    public static string DataFile => Path.Combine(DataRoot, "remindly-data.json");
    public static string BackupDir => Path.Combine(DataRoot, "backups");
    public static string UpdatesDir => Path.Combine(DataRoot, "updates");
    public static string LogFile => Path.Combine(DataRoot, "remindly.log");

    public static void Ensure()
    {
        Directory.CreateDirectory(DataRoot);
        Directory.CreateDirectory(BackupDir);
    }
}

public static class Log
{
    private static readonly object Gate = new();

    public static void Info(string msg) => Write("INFO", msg);
    public static void Warn(string msg) => Write("WARN", msg);
    public static void Error(string msg, Exception? ex = null) => Write("ERROR", ex == null ? msg : $"{msg}: {ex}");

    private static void Write(string level, string msg)
    {
        try
        {
            lock (Gate)
            {
                Directory.CreateDirectory(AppPaths.DataRoot);
                var f = new FileInfo(AppPaths.LogFile);
                if (f.Exists && f.Length > 1_000_000) File.Move(f.FullName, f.FullName + ".1", overwrite: true);
                File.AppendAllText(AppPaths.LogFile, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {level} {msg}{Environment.NewLine}");
            }
        }
        catch { /* logging must never break the app */ }
    }
}

public static class Json
{
    /// <summary>camelCase names + enum names as strings — the Gson shape Android writes.</summary>
    public static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DictionaryKeyPolicy = null,
        WriteIndented = true,
        PropertyNameCaseInsensitive = true,
        NumberHandling = JsonNumberHandling.AllowReadingFromString | JsonNumberHandling.AllowNamedFloatingPointLiterals,
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        Converters = { new JsonStringEnumConverter(), new LenientStringListConverter() },
        ReadCommentHandling = JsonCommentHandling.Skip,
        AllowTrailingCommas = true,
    };

    public static string Serialize(RemindlyData d) => JsonSerializer.Serialize(d, Options);

    public static RemindlyData? Deserialize(string json) => JsonSerializer.Deserialize<RemindlyData>(json, Options);
}

/// <summary>Gson writes a null list as null; an old record may even hold a single string. Both read as a list.</summary>
internal sealed class LenientStringListConverter : JsonConverter<List<string>>
{
    public override List<string>? Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        if (reader.TokenType == JsonTokenType.Null) return new();
        if (reader.TokenType == JsonTokenType.String) return new() { reader.GetString() ?? "" };
        var list = new List<string>();
        if (reader.TokenType != JsonTokenType.StartArray) { reader.Skip(); return list; }
        while (reader.Read() && reader.TokenType != JsonTokenType.EndArray)
        {
            if (reader.TokenType == JsonTokenType.String) list.Add(reader.GetString() ?? "");
            else if (reader.TokenType is JsonTokenType.StartObject or JsonTokenType.StartArray) reader.Skip();
            else if (reader.TokenType != JsonTokenType.Null) list.Add(Encoding.UTF8.GetString(reader.ValueSpan));
        }
        return list;
    }

    public override void Write(Utf8JsonWriter writer, List<string> value, JsonSerializerOptions options)
    {
        writer.WriteStartArray();
        foreach (var s in value) writer.WriteStringValue(s);
        writer.WriteEndArray();
    }
}

/// <summary>What an import brought in.</summary>
public sealed record ImportSummary(int Items, int Calls, int Lists, int Shops, int Products, bool FromAndroid);

/// <summary>
/// Loads, heals and saves remindly-data.json. Saves are atomic (temp file + replace) and the previous
/// file is kept as a dated backup (last 7), exactly like Android's remindly-data-* rotation.
/// </summary>
public static class DataStore
{
    public static RemindlyData Load()
    {
        AppPaths.Ensure();
        RemindlyData? d = null;
        if (File.Exists(AppPaths.DataFile))
        {
            try { d = Json.Deserialize(File.ReadAllText(AppPaths.DataFile, Encoding.UTF8)); }
            catch (Exception ex)
            {
                Log.Error("remindly-data.json unreadable — kept aside, starting from the newest backup", ex);
                try { File.Copy(AppPaths.DataFile, AppPaths.DataFile + $".broken-{DateTime.Now:yyyyMMdd-HHmmss}", true); } catch { }
                d = NewestBackup();
            }
        }
        d ??= new RemindlyData();
        return Heal(d, Clock.NowMs());
    }

    private static RemindlyData? NewestBackup()
    {
        try
        {
            foreach (var f in Directory.GetFiles(AppPaths.BackupDir, "remindly-data-*.json").OrderByDescending(f => f))
            {
                try { if (Json.Deserialize(File.ReadAllText(f)) is RemindlyData d) return d; } catch { }
            }
        }
        catch { }
        return null;
    }

    public static void Save(RemindlyData d)
    {
        AppPaths.Ensure();
        d.ExportedAt = Clock.NowMs();
        var json = Json.Serialize(d);
        var tmp = AppPaths.DataFile + ".tmp";
        File.WriteAllText(tmp, json, new UTF8Encoding(false));
        if (File.Exists(AppPaths.DataFile))
        {
            // One backup per day: the first save of the day copies yesterday's state aside.
            var daily = Path.Combine(AppPaths.BackupDir, $"remindly-data-{DateTime.Now:yyyy-MM-dd}.json");
            try { if (!File.Exists(daily)) File.Copy(AppPaths.DataFile, daily); } catch { }
            File.Replace(tmp, AppPaths.DataFile, null);
            RotateBackups();
        }
        else File.Move(tmp, AppPaths.DataFile);
    }

    private static void RotateBackups()
    {
        try
        {
            foreach (var f in Directory.GetFiles(AppPaths.BackupDir, "remindly-data-*.json").OrderByDescending(f => f).Skip(7))
                File.Delete(f);
        }
        catch { }
    }

    /// <summary>
    /// Makes any loaded document safe: nulls from Gson become empties, names are trimmed, the Bin
    /// is purged after 30 days, Buy groups become lists (2.11) and the device tag is set.
    /// </summary>
    public static RemindlyData Heal(RemindlyData d, long now)
    {
        d.Items = (d.Items ?? new()).Where(i => i != null).Select(i => i with
        {
            Title = i.Title ?? "",
            Notes = i.Notes ?? "",
            AlertType = string.IsNullOrWhiteSpace(i.AlertType) ? "N" : i.AlertType,
            RepeatMode = string.IsNullOrWhiteSpace(i.RepeatMode) ? "OFF" : i.RepeatMode,
            RepeatUnit = string.IsNullOrWhiteSpace(i.RepeatUnit) ? "D" : i.RepeatUnit,
            RepeatDays = i.RepeatDays ?? new(),
            RepeatOrdList = i.RepeatOrdList ?? new(),
            MissedAt = i.MissedAt ?? new(),
            PriceHistory = (i.PriceHistory ?? new()).Where(p => p != null).ToList(),
            RepeatN = i.RepeatN <= 0 ? 1 : i.RepeatN,
        }).ToList();
        d.Calls = (d.Calls ?? new()).Where(c => c != null).Select(c => c with
        {
            Number = c.Number ?? "",
            AlertType = string.IsNullOrWhiteSpace(c.AlertType) ? "N" : c.AlertType,
            RepeatMode = string.IsNullOrWhiteSpace(c.RepeatMode) ? "OFF" : c.RepeatMode,
            RepeatUnit = string.IsNullOrWhiteSpace(c.RepeatUnit) ? "D" : c.RepeatUnit,
            RepeatDays = c.RepeatDays ?? new(),
            RepeatOrdList = c.RepeatOrdList ?? new(),
        }).ToList();
        d.Items = DedupeById(d.Items, i => i.Id, i => i.UpdatedAt);
        d.Calls = DedupeById(d.Calls, c => c.Id, c => c.UpdatedAt);
        d.Places ??= new();
        d.Shops = (d.Shops ?? new()).Where(s => s != null).Select(s => s with { Name = (s.Name ?? "").Trim(), Radius = s.Radius > 0 ? s.Radius : 150f }).ToList();
        d.Products = (d.Products ?? new()).Where(p => p != null).Select(p => p with
        {
            Name = (p.Name ?? "").Trim(),
            Category = string.IsNullOrWhiteSpace(p.Category) ? null : p.Category.Trim(),
            DefaultUnit = string.IsNullOrWhiteSpace(p.DefaultUnit) ? null : p.DefaultUnit.Trim(),
        }).ToList();
        d.Cities ??= new();
        d.Chains ??= new();
        d.FiredKeys ??= new();

        var s = d.Settings ?? new AppSettings();
        s = s with
        {
            ShopLists = (s.ShopLists ?? new()).Where(l => l != null).Select(ShopLists.Heal).ToList(),
            ShopGroups = s.ShopGroups ?? new(),
            ShopGroupOrder = s.ShopGroupOrder ?? new(),
            GroupIcons = s.GroupIcons ?? new(),
            ShopDefaultGroup = s.ShopDefaultGroup ?? "",
            ShareHeadingSuffix = s.ShareHeadingSuffix ?? ":-",
            ListSort = s.ListSort is "RECENT" or "AZ" or "CUSTOM" ? s.ListSort : "RECENT",
            ListInnerGroup = s.ListInnerGroup is "DATE" or "SHOP" or "CATEGORY" or "PRIORITY" or "NONE" ? s.ListInnerGroup : "DATE",
            LastMode = s.LastMode == "SHOP" ? "SHOP" : "TASK",
            SnoozeMinutes = Math.Clamp(s.SnoozeMinutes <= 0 ? 10 : s.SnoozeMinutes, 1, 24 * 60),
            DefaultDueHour = Math.Clamp(s.DefaultDueHour, 0, 23),
            DeviceTag = s.DeviceTag is > 0 and <= 0xFFF ? s.DeviceTag : Ids.NewDeviceTag(),
        };
        Ids.InitTag(s.DeviceTag);

        // The Bin keeps deleted records for 30 days.
        long cutoff = ItemRules.PurgeCutoff(now);
        d.Items = d.Items.Where(i => i.DeletedAt is not long t || t >= cutoff).ToList();
        d.Calls = d.Calls.Where(c => c.DeletedAt is not long t || t >= cutoff).ToList();

        // 2.11: Buy groups → lists; items stamped with their list.
        var seed = ShopLists.Seed(d.Items, s.ShopLists, s.ShopGroups, s.GroupIcons, s.ShopGroupOrder, s.ShopDefaultGroup, s.ShopDefaultListId, now);
        if (seed.Items.Count > 0)
        {
            var changed = seed.Items.ToDictionary(i => i.Id);
            d.Items = d.Items.Select(i => changed.TryGetValue(i.Id, out var c) ? c : i).ToList();
        }
        d.Settings = ShopLists.MirrorIntoSettings(s with { ShopLists = seed.Lists, ShopDefaultListId = seed.DefaultListId });
        return d;
    }

    /// <summary>Reads a backup: a Windows export or an Android "remindly-data-*.json". Null when the file is not Remindly's.</summary>
    public static RemindlyData? Parse(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json, new JsonDocumentOptions { AllowTrailingCommas = true, CommentHandling = JsonCommentHandling.Skip });
            if (doc.RootElement.ValueKind != JsonValueKind.Object || !doc.RootElement.TryGetProperty("items", out var items) || items.ValueKind != JsonValueKind.Array)
                return null;
            return Json.Deserialize(json);
        }
        catch (Exception ex)
        {
            Log.Warn("Not a Remindly backup: " + ex.Message);
            return null;
        }
    }

    public static bool LooksLikeAndroid(RemindlyData blob) => blob.Shops.Count == 0 && blob.Products.Count == 0 && blob.FiredKeys.Count == 0;

    /// <summary>
    /// Imports a backup INTO the current data: records merge by id, the newer updatedAt wins, so
    /// importing twice changes nothing. Windows-only settings (updates, tray, start-up) stay as they are.
    /// </summary>
    public static ImportSummary MergeInto(RemindlyData current, RemindlyData incoming, long now)
    {
        var inc = Heal(incoming, now);
        Ids.InitTag(current.Settings.DeviceTag);
        int items = MergeById(current.Items, inc.Items, i => i.Id, i => i.UpdatedAt, out var mergedItems);
        current.Items = mergedItems;
        int calls = MergeById(current.Calls ??= new(), inc.Calls ?? new(), c => c.Id, c => c.UpdatedAt, out var mergedCalls);
        current.Calls = mergedCalls;
        int shops = MergeById(current.Shops, inc.Shops, s => s.Id, s => s.UpdatedAt, out var mergedShops);
        current.Shops = mergedShops;
        int products = MergeById(current.Products, inc.Products, p => p.Id, p => p.UpdatedAt, out var mergedProducts);
        current.Products = mergedProducts;
        MergeById(current.Places, inc.Places, p => p.Id, p => p.UpdatedAt, out var mergedPlaces);
        current.Places = mergedPlaces;
        MergeById(current.Cities, inc.Cities, c => c.Id, c => c.UpdatedAt, out var mergedCities);
        current.Cities = mergedCities;
        MergeById(current.Chains, inc.Chains, c => c.Id, c => c.UpdatedAt, out var mergedChains);
        current.Chains = mergedChains;

        var s = current.Settings;
        int listsBefore = ShopLists.Live(s.ShopLists).Count;
        var mergedLists = ShopLists.Merge(s.ShopLists, inc.Settings.ShopLists);
        s = s with
        {
            ShopLists = mergedLists,
            ShopDefaultListId = s.ShopDefaultListId ?? inc.Settings.ShopDefaultListId,
            ShareIncludeDone = inc.Settings.ShareIncludeDone,
            ShareIncludeQty = inc.Settings.ShareIncludeQty,
            ShareUrgentTag = inc.Settings.ShareUrgentTag,
            ShareBoughtTag = inc.Settings.ShareBoughtTag,
            ShareHeadingSuffix = inc.Settings.ShareHeadingSuffix,
        };
        current.Settings = s;
        var healed = Heal(current, now);
        current.Items = healed.Items;
        current.Settings = healed.Settings;
        int lists = ShopLists.Live(current.Settings.ShopLists).Count - listsBefore;
        return new ImportSummary(items, calls, Math.Max(0, lists), shops, products, LooksLikeAndroid(incoming));
    }

    /// <summary>One record per id (the most recently updated), first-seen order kept.</summary>
    internal static List<T> DedupeById<T>(List<T> list, Func<T, long> id, Func<T, long> updatedAt)
    {
        var best = new Dictionary<long, T>();
        var order = new List<long>();
        foreach (var r in list)
        {
            if (!best.TryGetValue(id(r), out var ex)) { order.Add(id(r)); best[id(r)] = r; }
            else if (updatedAt(r) >= updatedAt(ex)) best[id(r)] = r;
        }
        return order.Count == list.Count ? list : order.Select(k => best[k]).ToList();
    }

    /// <summary>Latest-wins per id; returns how many records were added or replaced.</summary>
    internal static int MergeById<T>(List<T> local, List<T> incoming, Func<T, long> id, Func<T, long> updatedAt, out List<T> merged)
    {
        var byId = new Dictionary<long, T>();
        var order = new List<long>();
        foreach (var r in local) { if (!byId.ContainsKey(id(r))) order.Add(id(r)); byId[id(r)] = r; }
        int changed = 0;
        foreach (var r in incoming)
        {
            if (!byId.TryGetValue(id(r), out var ex)) { order.Add(id(r)); byId[id(r)] = r; changed++; }
            else if (updatedAt(r) > updatedAt(ex)) { byId[id(r)] = r; changed++; }
        }
        merged = order.Select(k => byId[k]).ToList();
        return changed;
    }
}
