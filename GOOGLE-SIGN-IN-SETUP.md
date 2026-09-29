# Google sign-in for Remindly for Windows — the one-time console step

**Why this exists.** Your Android app signs in through Google Play Services with an *Android*
OAuth client (and a *Web* client for the Firebase token). A Windows desktop app cannot use either:
Google requires a client of type **Desktop app** for the browser-based sign-in it allows on
desktops. It must live in the **same Google Cloud project** as Remindly's Firebase (`remindly-5c1e6`)
so that the resulting Firebase user is the **same uid** your phone uses — that is what makes both
devices read and write the same `/users/{uid}/…` root. About two minutes, done once.

| # | Where | Click | Notes |
|---|---|---|---|
| 1 | https://console.cloud.google.com/apis/credentials?project=remindly-5c1e6 | Make sure the project selector (top bar) shows **remindly-5c1e6** | You'll see the existing Android client + Web client (auto-created by Firebase) listed under *OAuth 2.0 Client IDs* |
| 2 | *+ Create credentials* → **OAuth client ID** | Application type: **Desktop app** · Name: `Remindly Windows` → **Create** | No redirect URI is asked for a Desktop client — the app uses `http://127.0.0.1:<random port>` (loopback), which Google allows automatically for this type |
| 3 | The "OAuth client created" card | **Download JSON** (recommended) — or copy *Client ID* + *Client secret* | The file is named `client_secret_<id>.json`. Keep it private-ish (it is not a high-value secret for Desktop clients, but don't publish it) |
| 4 | Remindly for Windows → sidebar **Sign in with Google** (or Settings → *Google Desktop client…*) | Drop the JSON onto the field, or paste the two values → **Save client** | Stored DPAPI-encrypted in `%LOCALAPPDATA%\Remindly\secure\` — only your Windows account can read it |
| 5 | **Sign in with Google** | Your browser opens → pick the **same Gmail** as the phone → allow | The app asks for `openid email profile` + `calendar.readonly` (read-only calendar, for a later build). You land on a page saying "Signed in to Remindly" |
| 6 | Back in the app | Say **Yes** to "Turn on Cloud sync on this PC" | Sidebar goes ● Live |

## If the consent screen says "Access blocked" or "app is in testing"

| Symptom | Fix |
|---|---|
| "Access blocked: Remindly has not completed the Google verification process" / "app is in Testing" | *APIs & Services → OAuth consent screen → Test users → + Add users* → add your Gmail. (The consent screen already exists because the Android app uses it.) |
| "Error 400: redirect_uri_mismatch" | The client you created is not of type **Desktop app** (a Web client needs registered URIs). Create a Desktop one. |
| "This app isn't verified" warning page | Expected for a personal project — *Advanced → Go to Remindly (unsafe)*. Only you use it. |
| Sign-in succeeds but the app says "Permission denied by Firestore rules" | The Firebase uid differs from the phone's — check you signed in with the same Google account. Also confirm the Google provider is enabled in Firebase → Authentication → Sign-in method (it is, since the phone uses it). |
| Catalogue rows show "rules missing (Android 2.9)" | Publish `FIRESTORE-RULES-v2.9.rules` (from the Android 2.9 delivery) in Firebase → Firestore → Rules. Everything else syncs meanwhile. |

## What never leaves your PC

The OAuth client secret, the Firebase refresh token and the Google refresh token are encrypted
with Windows DPAPI for your user account. The Firebase **Web API key** that is embedded in the app
is the same public identifier already inside the Android APK — it identifies the project; access
is governed by your Firestore rules and your signed-in token, exactly as on the phone.
