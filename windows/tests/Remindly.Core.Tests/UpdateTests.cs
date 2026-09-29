using Remindly.Core;
using Remindly.Core.Updates;
using Xunit;

namespace Remindly.Core.Tests;

public class UpdateLogicTests
{
    [Theory]
    [InlineData("win-v2.11.0", true)]
    [InlineData("WIN-V3.0.1", true)]
    [InlineData("v2.11", false)]     // Android release in the same repository
    [InlineData("mac-v1.0.0", false)]
    [InlineData("win-vX", false)]
    [InlineData(null, false)]
    public void Only_windows_tags_count(string? tag, bool expected) => Assert.Equal(expected, UpdateLogic.IsWindowsTag(tag));

    [Fact]
    public void Versions_parse_to_three_parts_and_compare()
    {
        Assert.Equal(new Version(2, 11, 0), UpdateLogic.ParseVersion("win-v2.11"));
        Assert.Equal(new Version(2, 11, 1), UpdateLogic.ParseVersion("win-v2.11.1-beta"));
        Assert.True(UpdateLogic.IsNewer(new Version(2, 11, 0, 0), new Version(2, 11, 1)));
        Assert.False(UpdateLogic.IsNewer(new Version(2, 11, 0, 0), new Version(2, 11, 0)));
    }

    private const string ListJson = """
    [
      {"tag_name":"v2.12","name":"Remindly 2.12","draft":false,"prerelease":false,"assets":[{"name":"Remindly-2.12.apk","browser_download_url":"https://x/apk","size":1}]},
      {"tag_name":"win-v2.12.0","name":"Remindly 2.12.0 for Windows","draft":false,"prerelease":false,"html_url":"https://github.com/krishnabhunia/remindly-app/releases/tag/win-v2.12.0",
       "assets":[{"name":"Remindly-Windows-2.12.0.zip","browser_download_url":"https://x/zip","size":123},{"name":"Remindly-Windows-2.12.0.zip.sha256","browser_download_url":"https://x/sha","size":64}]},
      {"tag_name":"win-v2.13.0","draft":true,"prerelease":false,"assets":[]},
      {"tag_name":"win-v2.14.0","draft":false,"prerelease":true,"assets":[]},
      {"tag_name":"win-v2.11.0","draft":false,"prerelease":false,"assets":[]}
    ]
    """;

    [Fact]
    public void Latest_skips_android_drafts_and_prereleases()
    {
        var latest = UpdateLogic.PickLatest(UpdateLogic.ParseReleaseList(ListJson))!;
        Assert.Equal("win-v2.12.0", latest.TagName);
        var zip = UpdateLogic.PickZip(latest)!;
        Assert.Equal("Remindly-Windows-2.12.0.zip", zip.Name);
        Assert.Equal("https://x/sha", UpdateLogic.PickChecksum(latest, zip)!.DownloadUrl);
    }

    [Fact]
    public void Atom_fallback_finds_windows_tags()
    {
        const string atom = """
            <feed><entry><link rel="alternate" type="text/html" href="https://github.com/krishnabhunia/remindly-app/releases/tag/v2.12"/></entry>
            <entry><link rel="alternate" type="text/html" href="https://github.com/krishnabhunia/remindly-app/releases/tag/win-v2.12.0"/></entry></feed>
            """;
        var tags = UpdateLogic.ParseAtomTags(atom);
        Assert.Equal(new[] { "win-v2.12.0" }, tags.ToArray());
        var r = UpdateLogic.ReleaseFromTag(tags[0])!;
        Assert.Equal("https://github.com/krishnabhunia/remindly-app/releases/download/win-v2.12.0/Remindly-Windows-2.12.0.zip", UpdateLogic.PickZip(r)!.DownloadUrl);
    }

    [Fact]
    public void Checksum_parsing()
    {
        var h = new string('a', 64);
        Assert.Equal(h, UpdateLogic.ParseSha256(h.ToUpperInvariant() + "\r\n"));
        Assert.Equal(h, UpdateLogic.ParseSha256($"{h}  Remindly-Windows-2.12.0.zip"));
        Assert.Null(UpdateLogic.ParseSha256("nope"));
    }

    [Fact]
    public void Zip_layout_allows_only_installer_and_portable()
    {
        var good = new[] { "installer/", "installer/Remindly-Setup-2.12.0.exe", "portable/", "portable/Remindly.exe" };
        Assert.Empty(UpdateLogic.ValidateZipLayout(good));
        Assert.Equal("installer/Remindly-Setup-2.12.0.exe", UpdateLogic.PickZipEntry(good, installedMode: true));
        Assert.Equal("portable/Remindly.exe", UpdateLogic.PickZipEntry(good, installedMode: false));

        var bad = good.Append("docs/readme.txt").Append("README.md").Append("portable/x/y.dll");
        var problems = UpdateLogic.ValidateZipLayout(bad);
        Assert.Contains(problems, p => p.Contains("unexpected folder: docs"));
        Assert.Contains(problems, p => p.Contains("file at the zip root"));
        Assert.Contains(problems, p => p.Contains("nested folder"));
        Assert.Contains("portable/Remindly.exe is missing", UpdateLogic.ValidateZipLayout(new[] { "installer/Remindly-Setup-1.0.0.exe" }));
    }

    [Fact]
    public void Auto_update_checks_once_on_the_next_day()
    {
        long last = T.At(2026, 9, 29, 8);
        Assert.False(UpdateLogic.IsCheckDue(last, T.At(2026, 9, 29, 23, 59), true, T.Zone));   // same day: no
        Assert.True(UpdateLogic.IsCheckDue(last, T.At(2026, 9, 30, 0, 1), true, T.Zone));      // next day: yes
        Assert.False(UpdateLogic.IsCheckDue(last, T.At(2026, 10, 5), false, T.Zone));          // box unticked: never
        Assert.False(UpdateLogic.IsCheckDue(0, T.At(2026, 10, 5), true, T.Zone));              // first run: tomorrow
        Assert.Equal(T.At(2026, 9, 30), UpdateLogic.NextCheckAt(last, last, T.Zone));
    }

    [Fact]
    public void The_assembly_carries_the_directory_build_props_version()
    {
        var v = UpdateService.CurrentVersion;
        Assert.True(v.Major >= 2, $"version {v}");
        Assert.Equal(-1, v.Revision);
    }
}
