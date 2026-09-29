using System.Text.Json.Nodes;
using Remindly.Core.Json;
using Remindly.Core.Models;

namespace Remindly.Core.Logic;

/// <summary>
/// Last-writer-wins exactly as SyncRepo.applyItems/applyCalls/applyPlaces/applyShops and
/// Backup.mergeData do it on Android: stamp = updatedAt if &gt; 0 else createdAt; an incoming
/// record replaces the local one when its stamp is &gt;= the local stamp. Tombstones carry a fresh
/// updatedAt, so a delete always propagates. The field-preserving overlay (v1.70 Q10) is applied
/// when the remote writer's schemaVer is LOWER than ours.
/// </summary>
public static class Merge
{
    public static long Stamp(long updatedAt, long createdAt) => updatedAt > 0 ? updatedAt : createdAt;

    public static long StampOf(Item i) => Stamp(i.UpdatedAt, i.CreatedAt);
    public static long StampOf(CallReminder c) => Stamp(c.UpdatedAt, c.CreatedAt);
    public static long StampOf(GeoPlace p) => p.UpdatedAt;
    public static long StampOf(Shop s) => s.UpdatedAt;
    public static long StampOf(City c) => c.UpdatedAt;
    public static long StampOf(Chain c) => c.UpdatedAt;
    public static long StampOf(Product p) => p.UpdatedAt;
    /// <summary>Android linkStamp (N29): the latest of updatedAt, lastAt and the tombstone.</summary>
    public static long LinkStamp(ProductLink l) => Math.Max(l.UpdatedAt, Math.Max(l.LastAt, l.DeletedAt ?? 0));

    /// <summary>Does <paramref name="incoming"/> win over <paramref name="local"/>? (null local always loses.)</summary>
    public static bool IncomingWins(long incomingStamp, long? localStamp) => localStamp == null || incomingStamp >= localStamp.Value;

    /// <summary>
    /// Generic per-id LWW fold: returns the merged list and whether anything changed.
    /// </summary>
    public static (List<T> merged, bool changed, List<T> applied) Fold<T>(
        IReadOnlyList<T> local, IEnumerable<T> incoming, Func<T, long> id, Func<T, long> stamp)
    {
        var cur = local.ToDictionary(id);
        var applied = new List<T>();
        var changed = false;
        foreach (var inc in incoming)
        {
            var k = id(inc);
            if (!cur.TryGetValue(k, out var ex) || stamp(inc) >= stamp(ex))
            {
                if (ex == null || !Wire.ToJson(ex).Equals(Wire.ToJson(inc), StringComparison.Ordinal))
                {
                    cur[k] = inc; changed = true;
                }
                applied.Add(inc);
            }
        }
        return (cur.Values.ToList(), changed, applied);
    }

    /// <summary>
    /// mergeRecord (Sync.kt): when the remote writer is OLDER than our schema, overlay only the
    /// keys the remote JSON actually carries onto the local record's JSON, so a stale build can't
    /// reset fields it never knew about. Returns the JSON to deserialise.
    /// </summary>
    public static string OverlayIfOlder(string remoteJson, int remoteVer, string? localJson, out List<string> keptLocal)
    {
        keptLocal = new List<string>();
        if (localJson == null || remoteVer >= Constants.SyncSchema) return remoteJson;
        var baseObj = JsonNode.Parse(localJson) as JsonObject;
        var remote = JsonNode.Parse(remoteJson) as JsonObject;
        if (baseObj == null || remote == null) return remoteJson;
        foreach (var k in baseObj.Select(kv => kv.Key).ToList())
            if (!remote.ContainsKey(k)) keptLocal.Add(k);
        foreach (var kv in remote.ToList())
            baseObj[kv.Key] = kv.Value?.DeepClone();
        return baseObj.ToJsonString();
    }

    /// <summary>applySettings (Sync.kt): device-local fields stay, group lists union.</summary>
    public static AppSettings MergeSettings(AppSettings local, AppSettings remote)
    {
        if (remote.SettingsUpdatedAt <= local.SettingsUpdatedAt) return local;
        var merged = remote.Clone();
        merged.CloudSync = local.CloudSync;
        merged.LastSyncAt = local.LastSyncAt;
        merged.LastDataBackupAt = local.LastDataBackupAt;
        merged.TasksGroups = local.TasksGroups.Concat(remote.TasksGroups).Distinct().ToList();
        merged.ShopGroups = local.ShopGroups.Concat(remote.ShopGroups).Distinct().ToList();
        merged.LearnTopics = local.LearnTopics.Concat(remote.LearnTopics).Distinct().ToList();
        return merged;
    }
}
