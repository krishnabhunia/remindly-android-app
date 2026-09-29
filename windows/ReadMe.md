# Remindly for Windows

The Windows 10 / 11 companion of the Remindly Android app — C# / .NET 8 / WPF, Inno Setup.
Current version: **2.11.0** (feature level of Android 2.11: the Buy tab opens on your Lists).

## Download

[Releases](https://github.com/krishnabhunia/remindly-app/releases) → **Remindly *X.Y.Z* for Windows** (tag `win-vX.Y.Z`)
→ `Remindly-Windows-X.Y.Z.zip`. The zip holds exactly two folders:

| SrNo. | Folder | File | Use it to |
|---|---|---|---|
| 1 | `installer/` | `Remindly-Setup-X.Y.Z.exe` | install for your user (Start menu, Apps & features, uninstaller, optional start with Windows) |
| 2 | `portable/` | `Remindly.exe` | run from anywhere (USB stick, any folder) — nothing is installed |

Both copies keep their data in `%LOCALAPPDATA%\Remindly` (a daily backup is kept for 7 days).

## What it does

| SrNo. | Mode | Tab | On Windows |
|---|---|---|---|
| 1 | Task | Tasks | quick add, Active / Done, day groups, priorities, repeats, snooze, Personal |
| 2 | Task | Learn | platform, topic, link, progress, hours, spaced revision |
| 3 | Task | Calls | call-back reminders by hand; Call (tel: → Phone Link) and WhatsApp buttons |
| 4 | Shop | Buy | **Lists first** (2.11): list cards, list menu, inside-a-list grouping, Buy Now, WhatsApp / copy sharing |
| 5 | Shop | Shops | shops by city and chain, default shop, "items to buy here" |
| 6 | Shop | Products | catalogue with categories and default units; suggestions while adding |
| 7 | both | Settings | Updates (**Auto update**), start with Windows, sharing format, backup / Android import, Bin |

Stays on the phone: geofenced arrivals, call-log detection, maps, widgets, cloud sync. Bring the phone's data
over with Settings → Backup → **Import a backup** (the Android `remindly-data-*.json`).

## Updates

| SrNo. | What | How |
|---|---|---|
| 1 | Version shown | header of the home window: "Version X.Y.Z · Installed / Portable" (also the title bar and Settings → Updates) |
| 2 | Auto update (checkbox, on by default) | checks GitHub once a day — the first time on the **next day** — for the newest `win-v*` release |
| 3 | Background install | download → SHA-256 check → installed copy: new Setup runs silently; portable copy: the exe is swapped. Remindly restarts itself when you are not using it |
| 4 | Manual | Settings → Updates → Check now / Install X.Y.Z now; tray menu → Check for updates |
| 5 | Setup while Remindly runs | Setup detects the running copy, asks it to save and close (`Remindly.exe --exit`), installs, and starts it again |

## Building

Needs the .NET 8 SDK on Windows (`winget install Microsoft.DotNet.SDK.8`) and Inno Setup 6 for the installer.

```powershell
cd windows
build\package.ps1          # tests → portable exe → smoke test → installer → out\dist\Remindly-Windows-X.Y.Z.zip
```

| SrNo. | Command | Does |
|---|---|---|
| 1 | `dotnet test tests/Remindly.Core.Tests` | unit tests (also run on Linux / macOS) |
| 2 | `dotnet publish src/Remindly.App/Remindly.App.csproj -c Release -o out/portable` | the single-file `Remindly.exe` |
| 3 | `out\portable\Remindly.exe --smoke-test out\smoke --data-dir out\smoke-data` | opens every screen and dialog, saves screenshots, exit 0 = OK |

## Releasing (continuous delivery)

| SrNo. | Step | Where |
|---|---|---|
| 1 | Bump `<Version>` (X.Y.Z) | `windows/Directory.Build.props` |
| 2 | Add a `## X.Y.Z — title (date)` section | `windows/CHANGELOG.md` |
| 3 | Open a pull request | CI (`.github/workflows/windows.yml`): unit tests, UI smoke test (screenshots as an artifact), installer install / update-while-running / uninstall test, zip layout check |
| 4 | Review, approve, **merge** | the push to `main` runs the same checks and publishes Release `win-vX.Y.Z` with the zip + `.sha256` |
| 5 | Nothing else | installed copies with Auto update on install it on their next daily check |

A merge without a version bump builds and tests but publishes nothing; an existing tag is never re-published.
Windows releases are not marked *Latest* — that slot belongs to the Android releases (`vX.Y`).

## Layout

| SrNo. | Path | Contains |
|---|---|---|
| 1 | `src/Remindly.Core/` | models (same JSON as Android), repeat rules, Lists logic, reminders, storage, update logic |
| 2 | `src/Remindly.App/` | the WPF app: screens, editors, reminder cards, tray, updater, `--smoke-test` |
| 3 | `tests/Remindly.Core.Tests/` | xUnit tests |
| 4 | `installer/Remindly.iss` | Inno Setup script |
| 5 | `build/package.ps1` | local end-to-end build |
