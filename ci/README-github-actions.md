# Building Remindly APKs on GitHub — no Android Studio, nothing installed locally

GitHub runs the build on their machines and hands you back a signed APK. One-time setup is
about 15 minutes; after that a build is two clicks.

---

## One-time setup

### 1. Create a **private** repository

The repo must be private. The source is yours, and the build config contains your keystore
passwords in plain text (`app/build.gradle.kts`).

```bash
cd remindly                       # the unzipped source folder
git init
git add .
git commit -m "Remindly v1.73"
git branch -M main
git remote add origin https://github.com/<your-username>/remindly.git
git push -u origin main
```

### 2. Keep the keystore OUT of the repository

This is the one step worth being careful about. Anyone holding `remindly.keystore` plus the
password can publish an app that Android treats as an update to yours.

Add a `.gitignore` before your first commit:

```gitignore
remindly.keystore
*.jks
build/
.gradle/
local.properties
app/google-services.json
```

> If you already committed the keystore, removing it in a later commit is **not** enough —
> it stays in the history. Delete the repo and start again with the `.gitignore` in place.

### 3. Turn the keystore into a secret

On the machine holding `remindly.keystore`:

```bash
# Linux / macOS
base64 -w0 remindly.keystore > keystore.b64

# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("remindly.keystore")) | Set-Content keystore.b64
```

Then in GitHub: **Settings → Secrets and variables → Actions → New repository secret**

| Field | Value |
|---|---|
| Name | `KEYSTORE_BASE64` |
| Secret | the entire contents of `keystore.b64` |

Delete `keystore.b64` afterwards. GitHub encrypts secrets and masks them in logs.

### 4. Add the workflow

Put `build-apk.yml` at **`.github/workflows/build-apk.yml`**, then commit and push:

```bash
mkdir -p .github/workflows
cp ci/build-apk.yml .github/workflows/
git add .github/workflows/build-apk.yml
git commit -m "Add APK build workflow"
git push
```

---

## Running a build

**Manually** — Actions tab → *Build Remindly APK* → **Run workflow** → pick `release` or `debug`.

**By tag** — also creates a permanent GitHub Release with the APK attached:

```bash
git tag v1.73
git push --tags
```

Either way the APK appears at the bottom of the run page under **Artifacts**, named
`Remindly-v1.73`. Download it, transfer it to the phone, install.

First run takes 5–7 minutes; later runs are 2–3 minutes thanks to the Gradle cache.

---

## What the workflow guarantees

| Step | Why |
|---|---|
| Unit tests run first | The APK is never produced if a test fails — the same tests-green gate used locally |
| Version read from the built APK | The filename can't disagree with what's inside it |
| Signature verified | Confirms it really was signed with your permanent keystore |
| SHA-256 printed in the summary | Lets you confirm the file downloaded intact |

Expect the SHA-1 digest to read `a3:b9:44:da:e2:0c:a2:3f:52:d1:34:36:64:22:92:6d:ff:64:5c:55`.
**If it ever differs, do not install the APK** — it was signed with the wrong key and Android
will refuse it as an update anyway.

---

## Cost

Private repos on the Free plan include 2,000 Actions minutes per month. At roughly 3 minutes a
build that is around 600 builds — far beyond what you'd use. Public repos are unlimited, but
don't make this one public.

---

## Troubleshooting

**"Secret KEYSTORE_BASE64 is not set"** — step 3 was missed, or the secret is named differently.
Names are case-sensitive.

**"Keystore was tampered with, or password was incorrect"** — the base64 didn't survive copying.
Re-run `base64 -w0` (the `-w0` matters: without it the output wraps and breaks).

**"SDK license not accepted"** — shouldn't happen, `setup-android` accepts them. If it does, add
`run: yes | sdkmanager --licenses` before the build step.

**Tests fail but you need the APK anyway** — change the test step to
`run: ./gradlew :app:testDebugUnitTest --console=plain || true`. Not recommended: that gate has
caught real bugs before shipping.

**Build succeeds but the app won't install on the phone** — almost always a versionCode that
didn't increase. Android refuses to install an APK whose versionCode is not higher than the
installed one.
