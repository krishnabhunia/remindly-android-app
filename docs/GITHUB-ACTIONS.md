# Building Remindly on GitHub Actions

Workflow: `.github/workflows/build-release.yml` — **Build & release APK**.

GitHub builds, tests, signs and publishes the APK. The repository holds **no** signing material or keys:
everything confidential lives in encrypted repository secrets. The runner only sees them while the job runs, and they are masked in logs.

## 1 · One-time setup: add 6 repository secrets

GitHub → repository → **Settings → Secrets and variables → Actions → New repository secret**

| SrNo. | Secret name | Value |
|---|---|---|
| 1 | `REMINDLY_KEYSTORE_BASE64` | the keystore file as one base64 line (commands below) |
| 2 | `REMINDLY_STORE_PASSWORD` | keystore password (`storePassword` in your `keystore.properties`) |
| 3 | `REMINDLY_KEY_ALIAS` | `remindly` |
| 4 | `REMINDLY_KEY_PASSWORD` | key password (`keyPassword` in your `keystore.properties`) |
| 5 | `MAPS_API_KEY` | the Android-restricted Google Maps SDK key (Key A) |
| 6 | `GOOGLE_SERVICES_JSON` | the whole contents of `app/google-services.json` (paste as-is; base64 also accepted) |

Base64 of the keystore:

```bash
base64 -w0 remindly.keystore            # Linux
base64 -i remindly.keystore | tr -d '\n' # macOS
```
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("remindly.keystore"))   # Windows PowerShell
```

**Always use the same keystore.** Android installs an update only when it carries the same signature. The workflow checks the
certificate fingerprint (`a3b944da…ff645c55`) and refuses to publish an APK signed with any other key.

## 2 · Running it

| SrNo. | How | What happens |
|---|---|---|
| 1 | **Automatic**: push to `main` changing `app/build.gradle.kts` | tests → signed build → if `v<versionName>` has no Release yet: Release + feed update |
| 2 | **Manual**: Actions → *Build & release APK* → Run workflow, `publish` off | tests → signed build → APK downloadable from the run page (Artifacts, 30 days) |
| 3 | **Manual**, `publish` on | as above + Release + feed update (only for a version not yet released) |

Publishing is skipped automatically, with the build still running, when the Release tag already exists or when the versionCode is not
above the one in `releases/version.json`. A version bump is the only thing that publishes.

## 3 · What a publish does, in order

1. Unit tests (`testDebugUnitTest`) must pass.
2. `assembleRelease`, signed from the secrets. The build is rejected if the certificate is not the permanent Remindly key or the Maps key is missing.
3. The APK is renamed to `Remindly-<version>.apk`, and its SHA-256 and size are computed.
4. **Release `v<version>`** is created with the APK. Its notes are the top section of `docs/RELEASE-NOTES.md`.
5. **`releases/version.json`** is rewritten, uploaded to the Release and committed to `main` by `github-actions[bot]`.
   Its `apkUrl` points at the Release asset. Installed apps read this file and offer the update (Settings → Updates).

The feed is written last, so it never points at an APK that is not there yet.

## 4 · Troubleshooting

| SrNo. | Message | Fix |
|---|---|---|
| 1 | `Missing repository secrets: …` | add the named secrets (section 1) |
| 2 | `Keystore was tampered with, or password was incorrect` | the base64 was cut or the password is wrong: re-create secret 1/2/4 |
| 3 | `APK is not signed with the permanent Remindly key` | secret 1 holds a different keystore |
| 4 | `GOOGLE_SERVICES_JSON is not a valid google-services.json` | paste the entire file, including the outer `{ }` |
| 5 | `Release vX already exists — building only` | expected: bump `versionCode`/`versionName` to publish |

`publish-release.yml` is a fallback kept for an APK built locally and committed under `releases/`. It does nothing when the APK is not committed.
