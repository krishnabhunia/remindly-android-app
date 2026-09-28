# Remindly — Android

Personal reminders app: Task mode (Tasks · Learn · Calls) ⇄ Shop mode (Buy · Shops · Products), geofenced shop arrivals, call-back reminders, Google Maps / OSM, cloud sync.

**Install:** download the latest APK from the [Releases](../../releases/latest) page — or directly: [`releases/Remindly-2.9.apk`](releases/Remindly-2.9.apk). Every release is also committed under `releases/` with a `version.json` (versionCode, SHA-256) that the app reads to offer in-app updates from 2.9 on. A GitHub Action mirrors each new `version.json` into a Release.

## Building from source
Three files are deliberately **not** in this repository. Add them locally (all are git-ignored):

| File | Purpose |
|---|---|
| `keystore.properties` | Signing — copy `keystore.properties.example`, point `storeFile` at your keystore. Without it the build is unsigned. |
| `local.properties` | `sdk.dir=…` plus `MAPS_API_KEY=…` (Android-restricted Google Maps SDK key). Without the key the map picker runs on OpenStreetMap. |
| `app/google-services.json` | Firebase config for cloud sync — download from your Firebase project. |

Then `./gradlew assembleRelease` (Gradle 8.7, compileSdk 34, JDK 17).

## Docs
- `docs/RELEASE-NOTES.md` — what each version changed
- `docs/BACKLOG.md` — the working log, queue and standing rules
- `docs/DEVICE-CHECKLIST.md` — device verification sections per release
- `docs/GOOGLE-CLOUD-SETUP-v2.02.md` — Maps / Geocoding key setup
- `docs/design/` — the HTML design rounds each feature was approved from
