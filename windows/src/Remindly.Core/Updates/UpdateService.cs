using System.IO.Compression;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;

namespace Remindly.Core.Updates;

/// <summary>
/// Network side of the updates: asks GitHub for the newest win-v release, downloads its zip,
/// verifies the SHA-256 and extracts the file this copy needs. Never throws on a network problem —
/// the check reports "unavailable" instead. Applying the update (running Setup / swapping the
/// portable exe) is done by the app, which owns the process.
/// </summary>
public sealed class UpdateService
{
    private static readonly HttpClient Http = CreateClient();

    public static Version CurrentVersion
    {
        get
        {
            // Every project shares <Version> from Directory.Build.props, so the Core assembly carries the app version.
            var v = typeof(UpdateService).Assembly.GetName().Version ?? new Version(0, 0, 0);
            return UpdateLogic.Normalize(v);
        }
    }

    private static HttpClient CreateClient()
    {
        var handler = new HttpClientHandler { AllowAutoRedirect = true, AutomaticDecompression = DecompressionMethods.All };
        var c = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(60) };
        c.DefaultRequestHeaders.UserAgent.ParseAdd($"Remindly-Windows/{CurrentVersion.ToString(3)} (+{UpdateLogic.RepoUrl})");
        c.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        c.DefaultRequestHeaders.Add("X-GitHub-Api-Version", "2022-11-28");
        return c;
    }

    public async Task<UpdateCheckResult> CheckAsync(CancellationToken ct)
    {
        var current = CurrentVersion;
        ReleaseInfo? latest = null;
        string? failure = null;
        try
        {
            using var resp = await Http.GetAsync(UpdateLogic.ReleasesApiUrl, ct).ConfigureAwait(false);
            if (resp.IsSuccessStatusCode)
            {
                latest = UpdateLogic.PickLatest(UpdateLogic.ParseReleaseList(await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false)));
                if (latest == null) failure = "No Windows release has been published yet.";
            }
            else failure = $"GitHub answered {(int)resp.StatusCode} {resp.ReasonPhrase}.";
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch (Exception ex) { failure = "Could not reach GitHub: " + (ex.InnerException?.Message ?? ex.Message); }

        if (latest == null)
        {
            // Rate limit / API trouble: the Atom feed has no limit.
            try
            {
                var atom = await Http.GetStringAsync(UpdateLogic.ReleasesAtomUrl, ct).ConfigureAwait(false);
                latest = UpdateLogic.PickLatest(UpdateLogic.ParseAtomTags(atom).Select(UpdateLogic.ReleaseFromTag).Where(r => r != null)!);
                if (latest != null) failure = null;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch (Exception ex) { Log.Warn("Atom fallback failed: " + ex.Message); }
        }

        if (latest == null)
        {
            Log.Warn("Update check: " + failure);
            return new UpdateCheckResult(UpdateStatus.Unavailable, current, null, failure ?? "Update information is not available.");
        }
        if (UpdateLogic.IsNewer(current, latest.Version))
        {
            Log.Info($"Update check: {latest.TagName} available (installed {current.ToString(3)})");
            return new UpdateCheckResult(UpdateStatus.UpdateAvailable, current, latest,
                $"Remindly {latest.Version.ToString(3)} is available (you have {current.ToString(3)}).");
        }
        Log.Info($"Update check: up to date ({current.ToString(3)}; latest {latest.TagName})");
        return new UpdateCheckResult(UpdateStatus.UpToDate, current, latest, $"You have the latest version ({current.ToString(3)}).");
    }

    /// <summary>Downloads the release zip into the updates folder and verifies its SHA-256. Returns the zip path.</summary>
    public async Task<string> DownloadAsync(ReleaseInfo release, IProgress<int>? progress, CancellationToken ct)
    {
        var asset = UpdateLogic.PickZip(release) ?? throw new InvalidOperationException($"Release {release.TagName} has no Remindly-Windows zip.");
        Directory.CreateDirectory(AppPaths.UpdatesDir);
        var target = Path.Combine(AppPaths.UpdatesDir, asset.Name);
        var partial = target + ".partial";

        string? expected = null;
        if (UpdateLogic.PickChecksum(release, asset) is ReleaseAsset sum)
        {
            try { expected = UpdateLogic.ParseSha256(await Http.GetStringAsync(sum.DownloadUrl, ct).ConfigureAwait(false)); }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch (Exception ex) { Log.Warn("Checksum download failed: " + ex.Message); }
        }
        if (expected == null) throw new InvalidDataException("The release has no readable SHA-256 checksum — the update was not downloaded.");

        // A previous verified download is reused.
        if (File.Exists(target) && string.Equals(await Sha256Async(target, ct).ConfigureAwait(false), expected, StringComparison.OrdinalIgnoreCase))
        {
            progress?.Report(100);
            return target;
        }

        using (var resp = await Http.GetAsync(asset.DownloadUrl, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false))
        {
            resp.EnsureSuccessStatusCode();
            long total = resp.Content.Headers.ContentLength ?? asset.Size;
            await using var src = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
            await using var dst = new FileStream(partial, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16, useAsync: true);
            var buf = new byte[1 << 16];
            long done = 0;
            int last = -1, read;
            while ((read = await src.ReadAsync(buf, ct).ConfigureAwait(false)) > 0)
            {
                await dst.WriteAsync(buf.AsMemory(0, read), ct).ConfigureAwait(false);
                done += read;
                int pct = total > 0 ? (int)Math.Min(99, done * 100 / total) : 0;
                if (pct != last) { last = pct; progress?.Report(pct); }
            }
        }
        var actual = await Sha256Async(partial, ct).ConfigureAwait(false);
        if (!string.Equals(actual, expected, StringComparison.OrdinalIgnoreCase))
        {
            try { File.Delete(partial); } catch { }
            throw new InvalidDataException("The downloaded update failed verification (SHA-256 mismatch) and was discarded.");
        }
        File.Move(partial, target, overwrite: true);
        progress?.Report(100);
        Log.Info($"Downloaded + verified {asset.Name}");
        return target;
    }

    /// <summary>Extracts installer/Remindly-Setup-*.exe (installed copy) or portable/Remindly.exe (portable copy).</summary>
    public static string ExtractForMode(string zipPath, bool installedMode)
    {
        using var zip = ZipFile.OpenRead(zipPath);
        var names = zip.Entries.Select(e => e.FullName).ToList();
        var problems = UpdateLogic.ValidateZipLayout(names);
        if (problems.Count > 0) throw new InvalidDataException("The update zip is not laid out as expected: " + string.Join("; ", problems));
        var entryName = UpdateLogic.PickZipEntry(names, installedMode) ?? throw new InvalidDataException("The update zip does not contain the needed file.");
        var entry = zip.Entries.First(e => e.FullName == entryName);
        var outDir = Path.Combine(AppPaths.UpdatesDir, installedMode ? UpdateLogic.InstallerFolder : UpdateLogic.PortableFolder);
        Directory.CreateDirectory(outDir);
        var outPath = Path.Combine(outDir, Path.GetFileName(entry.FullName.Replace('\\', '/')));
        entry.ExtractToFile(outPath, overwrite: true);
        return outPath;
    }

    public static async Task<string> Sha256Async(string path, CancellationToken ct)
    {
        await using var fs = File.OpenRead(path);
        return Convert.ToHexString(await SHA256.HashDataAsync(fs, ct).ConfigureAwait(false)).ToLowerInvariant();
    }

    /// <summary>Old downloads are removed once a newer version runs.</summary>
    public static void CleanDownloads()
    {
        try { if (Directory.Exists(AppPaths.UpdatesDir)) Directory.Delete(AppPaths.UpdatesDir, recursive: true); } catch { }
    }
}
