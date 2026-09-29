using System.Text.Json;
using System.Text.RegularExpressions;

namespace Remindly.Core.Updates;

public sealed record ReleaseAsset(string Name, string DownloadUrl, long Size);

public sealed record ReleaseInfo(Version Version, string TagName, string Name, string HtmlUrl, string? Body, bool Prerelease, List<ReleaseAsset> Assets);

public enum UpdateStatus { UpToDate, UpdateAvailable, Unavailable }

public sealed record UpdateCheckResult(UpdateStatus Status, Version Current, ReleaseInfo? Release, string Message);

/// <summary>
/// Pure logic behind the Windows updates (unit-tested): versions, the win-v tag filter, GitHub
/// release / Atom parsing, the release-zip layout and the once-a-day ("next day") check rule.
///
/// The repository also publishes the Android app (tags vX.Y). Windows releases are tagged
/// win-vX.Y.Z and carry ONE zip, Remindly-Windows-X.Y.Z.zip, holding exactly two folders:
///   installer/Remindly-Setup-X.Y.Z.exe   — installs (Start menu, Apps &amp; features, uninstaller)
///   portable/Remindly.exe                — runs from anywhere, nothing installed
/// plus Remindly-Windows-X.Y.Z.zip.sha256 beside it for verification.
/// </summary>
public static class UpdateLogic
{
    public const string RepoOwner = "krishnabhunia";
    public const string RepoName = "remindly-app";
    public const string TagPrefix = "win-v";
    public const string InstallerFolder = "installer";
    public const string PortableFolder = "portable";
    public const string PortableExeName = "Remindly.exe";

    public static string RepoUrl => $"https://github.com/{RepoOwner}/{RepoName}";
    public static string ReleasesUrl => RepoUrl + "/releases";
    public static string ReleasesApiUrl => $"https://api.github.com/repos/{RepoOwner}/{RepoName}/releases?per_page=40";
    public static string ReleasesAtomUrl => ReleasesUrl + ".atom";

    public static bool IsWindowsTag(string? tag) =>
        !string.IsNullOrWhiteSpace(tag) && tag.Trim().StartsWith(TagPrefix, StringComparison.OrdinalIgnoreCase) && ParseVersion(tag) != null;

    /// <summary>"win-v2.11.0" / "2.11" / "v2.11.1-beta" → 2.11.0 / 2.11.0 / 2.11.1 (always three parts).</summary>
    public static Version? ParseVersion(string? tag)
    {
        if (string.IsNullOrWhiteSpace(tag)) return null;
        var m = Regex.Match(tag, @"(\d+)(?:\.(\d+))?(?:\.(\d+))?");
        if (!m.Success) return null;
        int P(int g) => m.Groups[g].Success ? int.Parse(m.Groups[g].Value) : 0;
        return new Version(P(1), P(2), P(3));
    }

    public static Version Normalize(Version v) => new(v.Major, Math.Max(0, v.Minor), Math.Max(0, v.Build));

    public static bool IsNewer(Version current, Version candidate) => Normalize(candidate) > Normalize(current);

    public static string ZipName(Version v) => $"Remindly-Windows-{v.ToString(3)}.zip";

    public static string SetupName(Version v) => $"Remindly-Setup-{v.ToString(3)}.exe";

    /// <summary>Parses GitHub's "list releases" JSON; only published Windows releases are kept.</summary>
    public static List<ReleaseInfo> ParseReleaseList(string json)
    {
        var result = new List<ReleaseInfo>();
        using var doc = JsonDocument.Parse(json);
        if (doc.RootElement.ValueKind != JsonValueKind.Array) return result;
        foreach (var r in doc.RootElement.EnumerateArray())
            if (ParseRelease(r) is ReleaseInfo info) result.Add(info);
        return result;
    }

    public static ReleaseInfo? ParseRelease(JsonElement root)
    {
        if (root.ValueKind != JsonValueKind.Object) return null;
        var tag = root.TryGetProperty("tag_name", out var t) ? t.GetString() : null;
        if (tag == null || !IsWindowsTag(tag)) return null;
        if (root.TryGetProperty("draft", out var d) && d.ValueKind == JsonValueKind.True) return null;
        var assets = new List<ReleaseAsset>();
        if (root.TryGetProperty("assets", out var arr) && arr.ValueKind == JsonValueKind.Array)
        {
            foreach (var a in arr.EnumerateArray())
            {
                var name = a.TryGetProperty("name", out var n) ? n.GetString() : null;
                var url = a.TryGetProperty("browser_download_url", out var u) ? u.GetString() : null;
                long size = a.TryGetProperty("size", out var sz) && sz.ValueKind == JsonValueKind.Number ? sz.GetInt64() : 0;
                if (!string.IsNullOrEmpty(name) && !string.IsNullOrEmpty(url)) assets.Add(new ReleaseAsset(name, url, size));
            }
        }
        string? S(string p) => root.TryGetProperty(p, out var e) && e.ValueKind == JsonValueKind.String ? e.GetString() : null;
        return new ReleaseInfo(ParseVersion(tag)!, tag, S("name") ?? tag, S("html_url") ?? ReleasesUrl, S("body"),
            root.TryGetProperty("prerelease", out var pr) && pr.ValueKind == JsonValueKind.True, assets);
    }

    /// <summary>The newest non-prerelease Windows release, or null.</summary>
    public static ReleaseInfo? PickLatest(IEnumerable<ReleaseInfo> releases) =>
        releases.Where(r => !r.Prerelease && IsWindowsTag(r.TagName)).OrderByDescending(r => Normalize(r.Version)).FirstOrDefault();

    /// <summary>Fallback without the API (rate limit): the releases Atom feed lists the recent tags.</summary>
    public static List<string> ParseAtomTags(string atom)
    {
        var tags = new List<string>();
        foreach (Match m in Regex.Matches(atom ?? "", @"/releases/tag/([^""<>\s?#]+)"))
        {
            var tag = Uri.UnescapeDataString(m.Groups[1].Value);
            if (IsWindowsTag(tag) && !tags.Contains(tag)) tags.Add(tag);
        }
        return tags;
    }

    /// <summary>A release built from its tag alone (Atom fallback): the asset URLs follow GitHub's download pattern.</summary>
    public static ReleaseInfo? ReleaseFromTag(string tag)
    {
        if (!IsWindowsTag(tag)) return null;
        var v = ParseVersion(tag)!;
        string Dl(string name) => $"{RepoUrl}/releases/download/{Uri.EscapeDataString(tag)}/{name}";
        var zip = ZipName(v);
        return new ReleaseInfo(v, tag, tag, $"{RepoUrl}/releases/tag/{Uri.EscapeDataString(tag)}", null, false,
            new List<ReleaseAsset> { new(zip, Dl(zip), 0), new(zip + ".sha256", Dl(zip + ".sha256"), 0) });
    }

    public static ReleaseAsset? PickZip(ReleaseInfo r) =>
        r.Assets.FirstOrDefault(a => a.Name.Equals(ZipName(r.Version), StringComparison.OrdinalIgnoreCase))
        ?? r.Assets.FirstOrDefault(a => a.Name.StartsWith("Remindly-Windows", StringComparison.OrdinalIgnoreCase) && a.Name.EndsWith(".zip", StringComparison.OrdinalIgnoreCase));

    public static ReleaseAsset? PickChecksum(ReleaseInfo r, ReleaseAsset asset) =>
        r.Assets.FirstOrDefault(a => a.Name.Equals(asset.Name + ".sha256", StringComparison.OrdinalIgnoreCase));

    /// <summary>The first 64-hex token of a checksum file ("ABC…", "abc…  file.zip", PowerShell output…).</summary>
    public static string? ParseSha256(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return null;
        foreach (var token in text.Split(new[] { ' ', '\t', '\r', '\n', '*', '=' }, StringSplitOptions.RemoveEmptyEntries))
            if (token.Length == 64 && token.All(Uri.IsHexDigit)) return token.ToLowerInvariant();
        return null;
    }

    /// <summary>
    /// The deployment zip holds ONLY the folders "installer" and "portable" (no other folder, no loose
    /// files at the root). Returns the problems found (empty = valid).
    /// </summary>
    public static List<string> ValidateZipLayout(IEnumerable<string> entryNames)
    {
        var problems = new List<string>();
        bool setup = false, portable = false;
        foreach (var raw in entryNames)
        {
            var e = raw.Replace('\\', '/');
            if (e.EndsWith('/')) e = e.TrimEnd('/') + "/";
            var slash = e.IndexOf('/');
            if (slash < 0) { problems.Add($"file at the zip root: {e}"); continue; }
            var top = e[..slash];
            if (top != InstallerFolder && top != PortableFolder) { problems.Add($"unexpected folder: {top}"); continue; }
            var rest = e[(slash + 1)..];
            if (rest.Contains('/')) { problems.Add($"nested folder inside {top}: {e}"); continue; }
            if (top == InstallerFolder && rest.StartsWith("Remindly-Setup-", StringComparison.OrdinalIgnoreCase) && rest.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) setup = true;
            if (top == PortableFolder && rest.Equals(PortableExeName, StringComparison.OrdinalIgnoreCase)) portable = true;
        }
        if (!setup) problems.Add("installer/Remindly-Setup-<version>.exe is missing");
        if (!portable) problems.Add("portable/Remindly.exe is missing");
        return problems.Distinct().ToList();
    }

    /// <summary>The zip entry an update needs: the Setup for an installed copy, the exe for a portable one.</summary>
    public static string? PickZipEntry(IEnumerable<string> entryNames, bool installedMode)
    {
        foreach (var raw in entryNames)
        {
            var e = raw.Replace('\\', '/');
            if (installedMode && e.StartsWith(InstallerFolder + "/Remindly-Setup-", StringComparison.OrdinalIgnoreCase) && e.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) return raw;
            if (!installedMode && e.Equals(PortableFolder + "/" + PortableExeName, StringComparison.OrdinalIgnoreCase)) return raw;
        }
        return null;
    }

    /// <summary>
    /// The "Auto update" rule: check once a day, the first time on the day AFTER the last check
    /// (calendar day, local time). Never checked yet → not due (the first check happens tomorrow).
    /// </summary>
    public static bool IsCheckDue(long lastCheckMs, long nowMs, bool autoUpdate, TimeZoneInfo? zone = null)
    {
        if (!autoUpdate || lastCheckMs <= 0) return false;
        return Clock.LocalDate(nowMs, zone) > Clock.LocalDate(lastCheckMs, zone);
    }

    /// <summary>When the next automatic check will happen (local midnight after the last check).</summary>
    public static long NextCheckAt(long lastCheckMs, long nowMs, TimeZoneInfo? zone = null) =>
        Clock.StartOfNextDay(lastCheckMs > 0 ? lastCheckMs : nowMs, zone);

    public static string Describe(Version current, Version latest) => $"{current.ToString(3)} → {latest.ToString(3)}";
}
