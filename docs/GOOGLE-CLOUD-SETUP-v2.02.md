# Remindly 2.02 — Google Maps setup (one-time, ~15 minutes)

Remindly 2.02 uses **Google Maps as the default provider** and **OSM (OpenStreetMap) as the fallback**.
The app works fully on OSM out of the box. Google switches on in two steps:

| Step | Where | Effect |
|---|---|---|
| A | You send me **Key A** (Maps SDK key, Android-restricted) → I bake it into the next build | Map picker shows Google Maps |
| B | You add **Key B** (Geocoding key) **on the phone**: Settings → API keys → Google Geocoding API key → Add new | Address lookups use Google (counted against your limit) |

## 1. Google Cloud Console (once)
1. https://console.cloud.google.com → **New project** (e.g., "Remindly"). Attach a **billing account** (card) — required even for the free tier.
2. **APIs & Services → Library** → enable **Maps SDK for Android** and **Geocoding API**.
3. **Credentials → Create credentials → API key** — this is **Key A**. Click *Edit key*:
   - Application restrictions: **Android apps** → add package `com.krishna.remindly`, SHA-1 `A3:B9:44:DA:E2:0C:A2:3F:52:D1:34:36:64:22:92:6D:FF:64:5C:55`
   - API restrictions: **Maps SDK for Android** only.
4. **Create another API key** — this is **Key B**. *Edit key*: API restrictions: **Geocoding API** only. (Web-service keys cannot be Android-restricted; that is why Key B lives on the phone and never in the app package.)
5. **APIs & Services → Enabled APIs → Geocoding API → Quotas** → set a per-day cap (e.g., **300 requests/day**). **Billing → Budgets & alerts** → a ₹0 or small budget alert. This is the hard guarantee of ₹0; the app's own limit is the second guard.

## 2. Give me Key A (for the build)
Paste Key A in chat when you say "release" for the next build. It is Android-restricted (package + your signing SHA-1), so it is useless to anyone else even if seen. I put it in `local.properties` as `MAPS_API_KEY=...` (never committed, never in the source zip); the manifest reads it as `com.google.android.geo.API_KEY`.

## 3. Add Key B on the phone
Settings (Task mode) → **API keys** → **Google Geocoding API key** → **Add new** → paste (input stays hidden) → Confirm 1 → type **SAVE** → Add key.
Status turns **SET**. It is stored encrypted, never shown again, excluded from backups. To change it: **Remove** (type **Remove**) → **Add new**.

## 4. Limits & the lock (how it protects you)
- Free tier: **10,000 geocoding calls/month** globally, **70,000 for India-billed** accounts (toggle in Maps & Location).
- App-side limit slider defaults to **80 %** of the cap and cannot exceed the cap.
- When the limit is hit — by the counter or by a quota/denied answer from Google — Google is **locked until the 1st** (Pacific midnight, Google's own reset). The Google chip greys out; everything runs on OSM; one toast tells you.
- Map loads (Maps SDK) are unlimited and never counted.
