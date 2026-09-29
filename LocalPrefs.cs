using System.Text.Json.Nodes;

namespace Remindly.Core.Store;

/// <summary>
/// Device-local state that must NEVER sync (mirrors Android's SharedPreferences/UiStore role):
/// the id device tag, PC alert switch, tray/startup choices, the local Personal PIN hash, and
/// the fired-alert ledger that stops a reminder ringing twice on this PC.
/// </summary>
public sealed class LocalPrefs
{
    public int DeviceTag { get; set; }
    public bool AlertsOnThisPc { get; set; } = true;
    public bool StartWithWindows { get; set; }
    public bool CloseToTray { get; set; } = true;
    public bool StartMinimized { get; set; }
    public string LastMode { get; set; } = "TASK";            // TASK | SHOP (v2.04: cold start reopens the last mode)
    public int LastTaskTab { get; set; }                        // 0 Tasks 1 Learn 2 Calls
    public int LastShopTab { get; set; }                        // 0 Buy 1 Shops 2 Products
    public string? PinHash { get; set; }                        // SHA-256(salt + pin), base64
    public string? PinSalt { get; set; }
    public bool MinimizeOnStartupDone { get; set; }
    public double WindowWidth { get; set; } = 1240;
    public double WindowHeight { get; set; } = 820;
    public double? WindowLeft { get; set; }
    public double? WindowTop { get; set; }
    public bool WindowMaximized { get; set; }
    public long LastMidnightSweep { get; set; }
    public long? PauseAlertsUntil { get; set; }
    /// <summary>"kind:id:fireAt" keys already alerted on this PC (pruned to the last 7 days).</summary>
    public List<string> FiredKeys { get; set; } = new();
    public string? SignedInEmail { get; set; }
    public string? SignedInUid { get; set; }
    public bool ThemeFollowsPhone { get; set; } = true;
    public string LocalTheme { get; set; } = "SYSTEM";
    public long? BuyNowShopId { get; set; }
    public int AlertVolumePct { get; set; } = 80;
    public bool SoundOnNotify { get; set; } = true;
    public bool ShowMissedSummaryOnStart { get; set; } = true;
    public bool CatalogueSyncEnabled { get; set; } = true;     // N29 collections (needs Android 2.9 rules)
    [JsonExtensionData] public Dictionary<string, JsonElement>? Extra { get; set; }

    public static LocalPrefs Load(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                var p = JsonSerializer.Deserialize<LocalPrefs>(File.ReadAllText(path), Json.Wire.Options);
                if (p != null) return p;
            }
        }
        catch { /* corrupt → defaults */ }
        return new LocalPrefs();
    }

    public void Save(string path)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var tmp = path + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(this, Json.Wire.Pretty));
        File.Move(tmp, path, overwrite: true);
    }

    public bool AlertsPaused(long now) => PauseAlertsUntil is long p && p > now;
}
