# Remindly — Android and Windows

Personal reminders app: Task mode (Tasks · Learn · Calls) ⇄ Shop mode (Buy · Shops · Products), geofenced shop arrivals, call-back reminders, Google Maps / OSM, cloud sync.

| SrNo. | App | Folder | Stack | Release tags | CI (runs by itself on every PR and on merge to `main`) |
|---|---|---|---|---|---|
| 1 | Android | `app/` | Kotlin / Jetpack Compose | `vX.Y` | [`build-release.yml`](.github/workflows/build-release.yml) |
| 2 | Windows 10/11 | [`windows/`](windows/ReadMe.md) | C# / .NET 8 / WPF, Inno Setup | `win-vX.Y.Z` | [`windows.yml`](.github/workflows/windows.yml) |

A merge to `main` that bumps an app's version builds, tests and publishes that app's release; both apps then offer the
update in-app. The Windows download is one zip with two folders: `installer/` and `portable/` — see [`windows/ReadMe.md`](windows/ReadMe.md).

**Install:** download the latest APK from the [Releases](../../releases/latest) page. Installed copies update themselves (Settings → Updates). `releases/version.json` (versionCode, SHA-256, download URL) is the feed the app reads to offer in-app updates from 2.9 on.

**Builds run on GitHub Actions** (`.github/workflows/build-release.yml`): tests → signed release APK → GitHub Release → feed update. Signing keys, the Maps key and `google-services.json` come from repository secrets — see [`docs/GITHUB-ACTIONS.md`](docs/GITHUB-ACTIONS.md).

## Building from source
Three files are deliberately **not** in this repository. Add them locally (all are git-ignored):

| File | Purpose |
|---|---|
| `keystore.properties` | Signing — copy `keystore.properties.example`, point `storeFile` at your keystore. Without it the build is unsigned. |
| `local.properties` | `sdk.dir=…` plus `MAPS_API_KEY=…` (Android-restricted Google Maps SDK key). Without the key the map picker runs on OpenStreetMap. |
| `app/google-services.json` | Firebase config for cloud sync — download from your Firebase project. |

Then `gradle assembleRelease` (Gradle 8.7, compileSdk 34, JDK 17). The signing values and `MAPS_API_KEY` can also be given as environment variables (`REMINDLY_STOREFILE`, `REMINDLY_STOREPASSWORD`, `REMINDLY_KEYALIAS`, `REMINDLY_KEYPASSWORD`, `MAPS_API_KEY`) — that is how the Action builds.

## Docs
- `docs/RELEASE-NOTES.md` — what each version changed
- `docs/BACKLOG.md` — the working log, queue and standing rules
- `docs/DEVICE-CHECKLIST.md` — device verification sections per release
- `docs/GITHUB-ACTIONS.md` — building and releasing on GitHub Actions (secrets, triggers)
- `docs/GOOGLE-CLOUD-SETUP-v2.02.md` — Maps / Geocoding key setup
- `docs/design/` — the HTML design rounds each feature was approved from
