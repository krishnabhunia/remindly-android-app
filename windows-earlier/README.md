# Remindly for Windows — 1.0.1 (Build 1, hot-fix) — 16-Sep-2026

> **Archive — the earlier Windows build (1.0.1, 16–17 Sep 2026).** Kept for reference; the maintained
> Windows app is [`../windows/`](../windows/ReadMe.md). Not built by any workflow in this repo.
> `src/Remindly.Core/Sync/google-services.json` is deliberately not committed (repo rule — Firebase
> config never goes in git); copy the Android app's `google-services.json` there before building.
> Its own CI workflow (`build-windows.yml`) and the "private repo" notes below refer to the standalone
> repo plan that was replaced by this monorepo.


The Windows companion to **Remindly** (Android 2.9). Same Google account, same Firestore root,
live sync both ways. Task mode (Tasks · Learn · Calls) and Shop mode (Buy · Shops · Products),
alerts on the PC, portable single EXE.

| File | What it is |
|---|---|
| `Remindly.exe` | Portable app, ~68 MB, self-contained (no .NET install needed). Windows 10 1809+ / 11, x64. Delivered as 4 parts + `Join-Remindly.cmd` (double-click once) because uploads cap at ~20–30 MB per file. |
| `Remindly.exe.sha256` | Hash of the joined EXE — compare with `certutil -hashfile Remindly.exe SHA256`. |
| `Remindly-source-v1.0.1.zip` | Full source (C# / .NET 8 / WPF), tests, build scripts. |
| `README.md` | This file. |
| `GOOGLE-SIGN-IN-SETUP.md` | The one-time Google Cloud console step, with exact clicks. |
| `DEVICE-CHECKLIST-WINDOWS.md` | What to test first on your laptop (see *Honesty* below for what has and has not run on real Windows). |

## 1.0.1 hot-fix — what was wrong in 1.0.0

| | |
|---|---|
| Symptom | Clicking **Sign in with Google** (and opening ANY dialog: shop editor, product editor, checkout, PIN, map picker) showed *"Provide value on 'System.Windows.StaticResourceExtension' threw an exception"*. The main window itself worked. |
| Root cause | The shared `Window.Dialog` style contained `<Setter Property="WindowStartupLocation" …>`. `Window.WindowStartupLocation` is a plain CLR property, **not** a DependencyProperty, so WPF rejects the Setter the first time the style is realised — which happens on first use, not at start-up, and not at compile time. Every dialog window used that style, so every dialog failed. Confirmed by reflecting the .NET 8 WPF reference assembly (no `WindowStartupLocationProperty` exists). |
| Fix | Setter removed; `WindowStartupLocation="CenterOwner"` is now set directly on each dialog window (5 XAML windows + the code-built PIN/text dialogs). |
| Guard | `build/xaml_check.py` now validates every Style/Trigger `Setter.Property`, `Trigger.Property` and `TemplateBinding` against a table of real DependencyProperties reflected from the WPF reference assemblies (`build/DpDump` → `build/wpf-dps.json`), checks `BasedOn` type compatibility and forbids forward `StaticResource` references inside dictionaries. It is now a **blocking** step in `publish.sh` / `publish.ps1`, and it flags the 1.0.0 bug when re-introduced. |
| Logging | `remindly.log` now records the whole exception chain (inner exceptions + stack trace, XAML line numbers when available) and the error dialog shows the root cause, so the next failure names the culprit directly. |
| Same bug elsewhere | The Evict app shares this theme file: its File Shredder, Force Uninstall, Uninstall Wizard and Windows Updates windows use the same `Window.Dialog` style and will fail the same way until Evict gets the same one-line fix. |

---

## 1. First run (5 minutes)

| Step | What to do | What you should see |
|---|---|---|
| 1 | Run `Join-Remindly.cmd` once, then `Remindly.exe` | The window opens in Task mode; Windows SmartScreen may ask once ("More info → Run anyway" — the EXE is unsigned, like Evict). |
| 2 | Sidebar → **Sign in with Google** | A setup dialog explains the Google Desktop client (needed once). Follow `GOOGLE-SIGN-IN-SETUP.md`, drop the downloaded JSON in, **Save client**. |
| 3 | Click **Sign in with Google** again | Your browser opens → choose the Gmail the phone uses → "Signed in to Remindly" page → back in the app it asks to turn on Cloud sync → **Yes**. |
| 4 | Watch the sidebar | ● Connecting… → ● **Live**. Every list from the phone appears within seconds. |
| 5 | Settings → *Alerts on this PC* | Decide whether the PC should ring (default: yes), start with Windows, and stay in the tray on close. |

Close (✕) keeps Remindly in the tray so alerts still fire; right-click the tray icon → **Quit** to exit.

---

## 2. What Build 1 does (feature parity with Android 2.9)

| Android feature | Windows | Notes |
|---|---|---|
| Task mode · Tasks / Learn | ✅ | Active \| Done, Date / Group / Priority sort, search, quick add (Enter), full editor, group-header checkbox (N43) |
| Item editor: No reminders / One-time / Repeat (Daily, Weekly, Monthly-dates, Monthly-weekday, Quarterly, Half-yearly, Yearly, Every N, Spaced) + "Next: 3 dates" preview, ends-after-N | ✅ | Same engine as the phone; identical dates (unit-tested in IST) |
| Reminder Type 🔔 Notify · 🔊 Ring · ⏰ Alarm · 🔕 Muted (N44) | ✅ | |
| Snooze (synced length), Quiet (N13 — permanent demote to Notify), cancel snooze, Coming up, Missed alerts (N20) | ✅ | |
| Learn extras: topic, platform, link, progress %, hours | ✅ | |
| Buy list: shop, qty, unit, ₹ price, staple, personal (PIN), buy-again lapse, expiry | ✅ | Expiry alert at 09:00 on the day |
| Checkout calculator on completion → purchase history (last 12) + product price memory | ✅ | Skip button available, as on the phone |
| Cheapest-shop hint (unit-family normalised: kg↔g, L↔ml, dozen↔pcs) | ✅ | |
| Product autocomplete 📦 in the Buy editor | ✅ | picks unit + cheapest shop |
| Shops by City · Unassigned · Chains · branches "Chain – City" · default shop ⭐ | ✅ | |
| Geofence: pin + radius (13 stops) + per-shop arrival alert type | ✅ edit | Rings on the **phone** only — a PC doesn't arrive at shops |
| Map picker | ✅ OSM (Leaflet + Nominatim search) | Needs the Edge WebView2 runtime (in Windows 11; free from Microsoft on Windows 10). Google map = phone only (Key A is Android-restricted) |
| Buy Now view | ✅ manual | "Buy Now" button on a shop row filters Buy to that shop; Hide ✕ to leave |
| Products: catalogue, categories, shop links with prices, best-price line, "＋ Buy" | ✅ | Syncs via the new N29 collections (needs the 2.9 Firestore rules) |
| Calls: mirror of the phone's missed calls + manual call reminders, repeat, note, label, WhatsApp message | ✅ | Windows **cannot** read a phone's call log — detection stays on the phone. **Call** hands off to Phone Link (`tel:`), **WhatsApp** opens `wa.me` |
| Scheduled alerts page (N42): every armed trigger, 24 h / 7 d / All, amber flags | ✅ | "Delete this alert" (mute / drop snooze) and "Delete the item — and every future alert" (N44), with Undo |
| Recently deleted (30-day bin), restore, delete forever, empty bin | ✅ | |
| Alerts on the PC: Notify popup card, Ring (looping sound, auto-stop after *Ring seconds*), full-screen red **Alarm** card (Done / Snooze / Quiet / Dismiss, double-click outside = Quiet) | ✅ | Device-local master switch; per-tab Inherit/On/Off from the phone honoured; "Pause 1 hour" in the tray |
| Missed-while-closed summary on start-up | ✅ | Reminders that came due while the PC was off become ONE card, not a barrage |
| Midnight sweeps: resurrect done repeats, lapse-return, roll missed repeats, done auto-clear, bin purge | ✅ | Idempotent; merges cleanly if the phone also ran them |
| Settings: synced (theme, time format, alerts, snooze, ring/alarm seconds, adding defaults, tabs, badges, groups/topics, shop-mode defaults, WhatsApp code) + this-PC (sync toggle, alerts, startup, tray, sounds, PIN, theme override) | ✅ | Per-tab settings pages: phone only in Build 1 (the PC honours them) |
| Backup: export/import the phone's `remindly-data` JSON, settings JSON | ✅ | Import = merge, newer wins |
| Personal PIN | ✅ local | Per device, like the phone; items marked Personal hide behind it |
| Cloud sync | ✅ | Live gRPC listener + writes, offline queue, reconcile on first connect, self-heal |
| List sharing (/shares) | ⏳ Build 2 | |
| Google Calendar "From Calendar" view | ⏳ Build 2 | The OAuth scope is already requested at sign-in |
| Extra alert rules (`B30,A60=RN…`), overdue nag hours | ⏳ Build 2 | Only the due alert fires on the PC today |
| Widgets, geofence firing, call-log scan, Google Maps SDK | ✗ | Phone-only by nature |

---

## 3. How sync works (the contract with the phone)

| Aspect | Implementation |
|---|---|
| Identity | Google OAuth 2.0 loopback + PKCE in your browser → Firebase `signInWithIdp` → **the same uid** the phone has (same Google Cloud project `remindly-5c1e6`) |
| Storage | Firestore `/users/{uid}/{items,calls,places,shops,cities,chains,products,productlinks}/{id}` and `/users/{uid}/settings/app` — each document `{ json: "<record>", updatedAt, schemaVer: 71 }`, byte-compatible with Android's Gson |
| Merge | Last-writer-wins on `updatedAt` (fallback `createdAt`); soft-delete tombstones (`deletedAt`); the v1.70 field-preserving overlay when a writer's `schemaVer` < 71 |
| Field safety | Every record keeps unknown keys (a field a future Android build adds is never dropped by the PC); every field is always written (never a missing key) |
| IDs | 12-bit device tag ‹‹ 44-bit millis, like the phone — the PC picks its own random tag at first run |
| Settings | Device-local fields (`cloudSync`, `lastSyncAt`, `lastDataBackupAt`) never leave; groups/topics union; a settings doc from an older schema is never adopted |
| First connect | The PC first reads the cloud, then pushes only records the cloud lacks or that are newer — a fresh install can never overwrite the phone with stale copies |
| Self-heal | If the cloud ever holds an older copy than the PC (a race the phone lost), the PC re-pushes its newer copy |
| Offline | Writes queue in `pending-writes.json` and flush on reconnect (never before the first snapshot) |
| Catalogue (N29) | Opt-in switch in Settings; if the 2.9 rules aren't published yet the four collections show "rules missing" and the PC keeps them local — everything else keeps syncing |

Verified end-to-end against a **Firestore emulator** (phone side simulated with the exact REST
envelope Android writes): live phone→PC, PC→phone format, newer wins / stale ignored, tombstone →
bin, settings merge + older-schema rejection, reconcile without clobbering, N29 both ways.

---

## 4. Where things live on the PC

| Path | Contents |
|---|---|
| `%LOCALAPPDATA%\Remindly\data\*.json` | items, calls, places, shops, cities, chains, products, productlinks, settings — **same file names and shapes as the phone's** `filesDir`, so a phone backup drops straight in |
| `%LOCALAPPDATA%\Remindly\data\local.json` | This-PC preferences (device tag, alert switch, tray, PIN hash, fired-alert ledger, window size) |
| `%LOCALAPPDATA%\Remindly\secure\*.bin` | OAuth client + refresh tokens, DPAPI-encrypted (this Windows user only) |
| `%LOCALAPPDATA%\Remindly\state\pending-writes.json` | Offline write queue |
| `%LOCALAPPDATA%\Remindly\remindly.log` | Error log (Settings → *Show error log*) — the PC's "Error Logs card" |
| `HKCU\…\Run\Remindly` | Start-with-Windows entry (only when the switch is on) |

Uninstall = delete `Remindly.exe` and the folder above (and switch off *Start with Windows* first).

---

## 5. Build it yourself

Source: private repo `krishnabhunia/remindly-windows` (Android app: `krishnabhunia/remindly-android`).
Easiest build: GitHub → **Actions → Build Remindly for Windows → Run workflow**, then download the EXE from the run's *Artifacts*.

| Layer | Project | Notes |
|---|---|---|
| Contract + logic | `src/Remindly.Core` (net8.0) | Models, Gson-compatible JSON, IDs, recurrence, merge, heal, repository, Firebase auth, Firestore sync |
| UI | `src/Remindly.App` (net8.0-windows, WPF, CommunityToolkit.Mvvm) | Fluent theme shared with Evict, tray, alerts, map picker (WebView2) |
| Tests | `tests/Remindly.Core.Tests` (xunit) | 49 unit tests + 4 emulator tests (`FIRESTORE_EMULATOR_HOST=127.0.0.1:8080` to run them) |
| CI | `.github/workflows/build-windows.yml` | Every push to `main`: Firestore-emulator tests, static XAML checks, `-warnaserror` build, single-file publish → EXE + SHA-256 as a run artifact. Tag `v*` → GitHub Release. |
| Build | `build/publish.sh` / `build/publish.ps1` / `build/xaml_check.py` (+ `build/DpDump`) | Cross-compiled from Linux with `EnableWindowsTargeting`; single-file compressed publish; blocking static XAML checks |

---

## 6. Honesty — what has and has not been verified

| Check | Result |
|---|---|
| C# compile (Release), 0 warnings | ✅ |
| 49 xunit tests (wire parity with the Kotlin classes field-by-field, recurrence in IST, LWW, IDs, shop maths, repository, backup import) | ✅ |
| 4 Firestore-emulator scenarios (real gRPC Listen/Commit) | ✅ |
| XAML static checks (resources + lexical order, Setter/Trigger/TemplateBinding vs real DependencyProperties, BasedOn compatibility, theme parity, binding roots) | ✅ 0 problems (the checker catches the 1.0.0 bug when it is re-introduced) |
| PE inspection of the EXE (x64, GUI, icon, version info, asInvoker + PerMonitorV2 manifest) | ✅ |
| **Running the UI on Windows** | ⚠️ 1.0.0 launched on your laptop (main window OK, log shows OS 10.0.26200 / .NET 8.0.31); every dialog failed → fixed in 1.0.1. The 1.0.1 dialogs have NOT yet been opened on real Windows; the fix is proven by reflection + compiled-BAML inspection, not by a screenshot. |
| **Google sign-in against the real project** | ❌ needs your Desktop OAuth client + your browser |
| **Live sync against your real Firestore** | ❌ emulator only until you sign in |

Known limitations to test first: WebView2 map picker on a machine without the runtime falls back
to lat/lng typing; Segoe Fluent Icons glyphs show as boxes if the font is missing (Windows 10 →
Segoe MDL2 fallback is configured); SmartScreen warning on first launch (unsigned EXE).
