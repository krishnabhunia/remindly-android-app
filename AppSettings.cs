using System.Text.Json.Nodes;

namespace Remindly.Core.Models;

/// <summary>
/// AppSettings mirrors the ~200-field Kotlin data class WITHOUT re-typing it: the record is kept
/// as the raw JSON object the phone wrote, and typed accessors read/write only the keys the
/// Windows app understands. Everything else round-trips untouched, so a settings push from
/// Windows can never narrow the phone's settings (the v1.70 Q10 field-preserving rule, upheld
/// structurally). Defaults below are the Kotlin defaults.
/// </summary>
public sealed class AppSettings
{
    public JsonObject Raw { get; }

    public AppSettings() : this(new JsonObject()) { }
    public AppSettings(JsonObject raw) { Raw = raw; }

    public static AppSettings Parse(string json)
    {
        var node = JsonNode.Parse(json) as JsonObject ?? new JsonObject();
        return new AppSettings(node);
    }

    public string ToJson(bool indented = false) => Raw.ToJsonString(new JsonSerializerOptions { WriteIndented = indented });

    public AppSettings Clone() => new((JsonObject)Raw.DeepClone());

    /// <summary>Copy-on-write edit: returns a new settings object with the edits applied.</summary>
    public AppSettings With(Action<AppSettings> edit)
    {
        var c = Clone();
        edit(c);
        return c;
    }

    // ── generic accessors ──────────────────────────────────────────────────────────────────
    public bool GetBool(string key, bool def) => Raw[key] is JsonValue v && v.TryGetValue<bool>(out var b) ? b : def;
    public int GetInt(string key, int def) => Raw[key] is JsonValue v ? (v.TryGetValue<int>(out var i) ? i : v.TryGetValue<double>(out var d) ? (int)d : def) : def;
    public long GetLong(string key, long def) => Raw[key] is JsonValue v ? (v.TryGetValue<long>(out var l) ? l : v.TryGetValue<double>(out var d) ? (long)d : def) : def;
    public double GetDouble(string key, double def) => Raw[key] is JsonValue v && v.TryGetValue<double>(out var d) ? d : def;
    public float GetFloat(string key, float def) => (float)GetDouble(key, def);
    public string GetString(string key, string def) => Raw[key] is JsonValue v && v.TryGetValue<string>(out var s) && s != null ? s : def;
    public string? GetStringOrNull(string key) => Raw[key] is JsonValue v && v.TryGetValue<string>(out var s) ? s : null;
    public List<string> GetStringList(string key)
    {
        if (Raw[key] is not JsonArray arr) return new List<string>();
        return arr.Select(n => n?.GetValue<string>()).Where(s => s != null).Select(s => s!).ToList();
    }
    public Dictionary<string, string> GetStringMap(string key)
    {
        var d = new Dictionary<string, string>();
        if (Raw[key] is JsonObject o) foreach (var kv in o) if (kv.Value is JsonValue v && v.TryGetValue<string>(out var s)) d[kv.Key] = s;
        return d;
    }
    public void Set(string key, bool v) => Raw[key] = v;
    public void Set(string key, int v) => Raw[key] = v;
    public void Set(string key, long v) => Raw[key] = v;
    public void Set(string key, double v) => Raw[key] = v;
    public void Set(string key, float v) => Raw[key] = (double)v;
    public void Set(string key, string? v) => Raw[key] = v == null ? null : JsonValue.Create(v);
    public void SetStringList(string key, IEnumerable<string> v) => Raw[key] = new JsonArray(v.Select(s => (JsonNode?)JsonValue.Create(s)).ToArray());

    // ── the fields Windows uses ────────────────────────────────────────────────────────────
    public int Ver { get => GetInt("ver", 41); set => Set("ver", value); }
    public long SettingsUpdatedAt { get => GetLong("settingsUpdatedAt", 0); set => Set("settingsUpdatedAt", value); }
    public bool CloudSync { get => GetBool("cloudSync", false); set => Set("cloudSync", value); }
    public long LastSyncAt { get => GetLong("lastSyncAt", 0); set => Set("lastSyncAt", value); }
    public long LastDataBackupAt { get => GetLong("lastDataBackupAt", 0); set => Set("lastDataBackupAt", value); }

    public string Theme { get => GetString("theme", "SYSTEM"); set => Set("theme", value); }
    public string TimeFormat { get => GetString("timeFormat", "PHONE"); set => Set("timeFormat", value); }
    public string DefaultCountryCode { get => GetString("defaultCountryCode", "91"); set => Set("defaultCountryCode", value); }

    public List<string> TasksGroups { get => GetStringList("tasksGroups"); set => SetStringList("tasksGroups", value); }
    public List<string> ShopGroups { get => GetStringList("shopGroups"); set => SetStringList("shopGroups", value); }
    public List<string> LearnTopics { get => GetStringList("learnTopics"); set => SetStringList("learnTopics", value); }
    public List<string> ShopGroupOrder { get => GetStringList("shopGroupOrder"); set => SetStringList("shopGroupOrder", value); }

    public bool ShowTasks { get => GetBool("showTasks", true); set => Set("showTasks", value); }
    public bool ShowLearn { get => GetBool("showLearn", true); set => Set("showLearn", value); }
    public bool ShowCalls { get => GetBool("showCalls", true); set => Set("showCalls", value); }
    public bool ShowDeleteOnDone { get => GetBool("showDeleteOnDone", true); set => Set("showDeleteOnDone", value); }
    public bool Badges { get => GetBool("badges", true); set => Set("badges", value); }

    public string TasksSort { get => GetString("tasksSort", "DATE"); set => Set("tasksSort", value); }
    public string ShopSort { get => GetString("shopSort", "DATE"); set => Set("shopSort", value); }
    public string LearnSort { get => GetString("learnSort", "DATE"); set => Set("learnSort", value); }
    public bool TasksGroupByGroup { get => GetBool("tasksGroupByGroup", false); set => Set("tasksGroupByGroup", value); }
    public bool ShopGroupByGroup { get => GetBool("shopGroupByGroup", false); set => Set("shopGroupByGroup", value); }
    public bool LearnGroupByTopic { get => GetBool("learnGroupByTopic", false); set => Set("learnGroupByTopic", value); }

    public int DefaultDueMinutes { get => GetInt("defaultDueMinutes", 1080); set => Set("defaultDueMinutes", value); }
    public int CallReminderMinutes { get => GetInt("callReminderMinutes", 1080); set => Set("callReminderMinutes", value); }
    public string GlobalNewDueMode { get => GetString("globalNewDueMode", "TOMORROW"); set => Set("globalNewDueMode", value); }
    public int GlobalNewDueDays { get => GetInt("globalNewDueDays", 3); set => Set("globalNewDueDays", value); }
    public int GlobalNewDueMinutes { get => GetInt("globalNewDueMinutes", 600); set => Set("globalNewDueMinutes", value); }
    public bool GlobalNewDueTimed { get => GetBool("globalNewDueTimed", true); set => Set("globalNewDueTimed", value); }
    public string TasksNewDueMode { get => GetString("tasksNewDueMode", "TOMORROW"); set => Set("tasksNewDueMode", value); }
    public int TasksNewDueDays { get => GetInt("tasksNewDueDays", 2); set => Set("tasksNewDueDays", value); }
    public int TasksNewDueMinutes { get => GetInt("tasksNewDueMinutes", 600); set => Set("tasksNewDueMinutes", value); }
    public string ShopNewDueMode { get => GetString("shopNewDueMode", "TOMORROW"); set => Set("shopNewDueMode", value); }
    public int ShopNewDueDays { get => GetInt("shopNewDueDays", 2); set => Set("shopNewDueDays", value); }
    public int ShopNewDueMinutes { get => GetInt("shopNewDueMinutes", 600); set => Set("shopNewDueMinutes", value); }
    public string LearnNewDueMode { get => GetString("learnNewDueMode", "TOMORROW"); set => Set("learnNewDueMode", value); }
    public int LearnNewDueDays { get => GetInt("learnNewDueDays", 2); set => Set("learnNewDueDays", value); }
    public int LearnNewDueMinutes { get => GetInt("learnNewDueMinutes", 600); set => Set("learnNewDueMinutes", value); }
    public string TasksNewDueTimed { get => GetString("tasksNewDueTimed", "INHERIT"); set => Set("tasksNewDueTimed", value); }
    public string ShopNewDueTimed { get => GetString("shopNewDueTimed", "INHERIT"); set => Set("shopNewDueTimed", value); }
    public string LearnNewDueTimed { get => GetString("learnNewDueTimed", "INHERIT"); set => Set("learnNewDueTimed", value); }
    public string SchedDefault { get => GetString("schedDefault", "ONCE"); set => Set("schedDefault", value); }
    public string TasksSchedDefault { get => GetString("tasksSchedDefault", "INHERIT"); set => Set("tasksSchedDefault", value); }
    public string ShopSchedDefault { get => GetString("shopSchedDefault", "INHERIT"); set => Set("shopSchedDefault", value); }
    public string LearnSchedDefault { get => GetString("learnSchedDefault", "INHERIT"); set => Set("learnSchedDefault", value); }
    public string CallsNewDueMode { get => GetString("callsNewDueMode", "TODAY"); set => Set("callsNewDueMode", value); }
    public int CallsNewDueDays { get => GetInt("callsNewDueDays", 3); set => Set("callsNewDueDays", value); }

    public int GlobalDoneClearDays { get => GetInt("globalDoneClearDays", 0); set => Set("globalDoneClearDays", value); }
    public int TasksDoneClearDays { get => GetInt("tasksDoneClearDays", 0); set => Set("tasksDoneClearDays", value); }
    public int ShopDoneClearDays { get => GetInt("shopDoneClearDays", 0); set => Set("shopDoneClearDays", value); }
    public int LearnDoneClearDays { get => GetInt("learnDoneClearDays", 0); set => Set("learnDoneClearDays", value); }
    public int CallsDoneClearDays { get => GetInt("callsDoneClearDays", 0); set => Set("callsDoneClearDays", value); }

    // alerts
    public bool AlertsEnabled { get => GetBool("alertsEnabled", true); set => Set("alertsEnabled", value); }
    public string TasksAlertsOn { get => GetString("tasksAlertsOn", "INHERIT"); set => Set("tasksAlertsOn", value); }
    public string ShopAlertsOn { get => GetString("shopAlertsOn", "INHERIT"); set => Set("shopAlertsOn", value); }
    public string LearnAlertsOn { get => GetString("learnAlertsOn", "INHERIT"); set => Set("learnAlertsOn", value); }
    public string CallsAlertsOn { get => GetString("callsAlertsOn", "INHERIT"); set => Set("callsAlertsOn", value); }
    public int SnoozeM1 { get => GetInt("snoozeM1", 90); set => Set("snoozeM1", value); }
    public int AlarmRingSeconds { get => GetInt("alarmRingSeconds", 7); set => Set("alarmRingSeconds", value); }
    public int RingRingSeconds { get => GetInt("ringRingSeconds", 7); set => Set("ringRingSeconds", value); }
    public bool CardShowAlertType { get => GetBool("cardShowAlertType", false); set => Set("cardShowAlertType", value); }
    public string AlertColorA { get => GetString("alertColorA", "#D32F2F"); set => Set("alertColorA", value); }
    public string AlertColorR { get => GetString("alertColorR", "#EF6C00"); set => Set("alertColorR", value); }
    public bool ShowMediumTag { get => GetBool("showMediumTag", false); set => Set("showMediumTag", value); }
    public bool CardShowPriority { get => GetBool("cardShowPriority", true); set => Set("cardShowPriority", value); }
    public bool CardShowDateTime { get => GetBool("cardShowDateTime", true); set => Set("cardShowDateTime", value); }
    public bool CardShowCheckbox { get => GetBool("cardShowCheckbox", true); set => Set("cardShowCheckbox", value); }
    public bool CardShowRepeat { get => GetBool("cardShowRepeat", true); set => Set("cardShowRepeat", value); }
    public bool GroupHeaderCheck { get => GetBool("groupHeaderCheck", true); set => Set("groupHeaderCheck", value); }

    // shop mode
    public bool ShopCheckoutCalc { get => GetBool("shopCheckoutCalc", true); set => Set("shopCheckoutCalc", value); }
    public bool ShopCheapestHint { get => GetBool("shopCheapestHint", true); set => Set("shopCheapestHint", value); }
    public bool ShopArriveAlert { get => GetBool("shopArriveAlert", true); set => Set("shopArriveAlert", value); }
    public float ShopNewRadius { get => GetFloat("shopNewRadius", 150f); set => Set("shopNewRadius", value); }
    public string ShopArriveTypes { get => GetString("shopArriveTypes", "N"); set => Set("shopArriveTypes", value); }
    public int ShopArriveCooldownMin { get => GetInt("shopArriveCooldownMin", 10); set => Set("shopArriveCooldownMin", value); }
    public double ShopBudgetMonthly { get => GetDouble("shopBudgetMonthly", 0); set => Set("shopBudgetMonthly", value); }
    public float DefaultRadius { get => GetFloat("defaultRadius", 250f); set => Set("defaultRadius", value); }
    public string MapProvider { get => GetString("mapProvider", "GOOGLE"); set => Set("mapProvider", value); }
    public bool CitySeedDone { get => GetBool("citySeedDone", false); set => Set("citySeedDone", value); }

    // sharing
    public bool ShareOn { get => GetBool("shareOn", true); set => Set("shareOn", value); }

    // per-tab inherit helpers (mirror Model.kt)
    public static bool Tri(string per, bool global) => per switch { "ON" => true, "OFF" => false, _ => global };

    /// <summary>Android Backup.settingsForSync: zero the device-local bookkeeping before pushing.</summary>
    public AppSettings ForSync() => With(s => { s.CloudSync = false; s.LastSyncAt = 0; s.LastDataBackupAt = 0; });
}
