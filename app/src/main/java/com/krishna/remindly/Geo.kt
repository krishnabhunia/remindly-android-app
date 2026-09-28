package com.krishna.remindly

import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/*
 * v2.02 (N33) — LocStack provider logic. Krishna's rule: Google Maps is the default provider, OSM the
 * fallback; a user-set monthly limit on Google geocoding; once the limit is hit (by our counter or by
 * a quota/denied answer from Google) the month is LOCKED — Google cannot be selected again until the
 * 1st (Pacific midnight, same as Google's reset). Everything Google-side is guarded and logged;
 * every failure degrades to the OSM path (the platform Geocoder the app used before 2.02).
 * KEYS: the Geocoding key comes from KeyStore (device-only) and is never logged; the Maps SDK key is
 * read from the manifest meta-data only to know whether it is present.
 */
object Geo {

    data class Hit(val lat: Double, val lng: Double, val label: String, val provider: String)

    fun nowMonthKey(): String = monthKeyPacific(System.currentTimeMillis())

    /** Is the Maps SDK key baked into this build (manifest meta-data non-blank)? */
    fun sdkKeyPresent(context: Context): Boolean = runCatching {
        val ai = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        !ai.metaData?.getString("com.google.android.geo.API_KEY").isNullOrBlank()
    }.getOrDefault(false)

    fun playServicesOk(context: Context): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    fun geoKeyPresent(): Boolean = KeyStore.isSet(KeyStore.GOOGLE_GEOCODING)

    /** Provider the MAP PICKER should use right now. */
    fun mapProviderNow(context: Context): String = runCatching {
        resolveMapProvider(SettingsStore.s.value, UiStore.s.value.geoUsage, nowMonthKey(), sdkKeyPresent(context), playServicesOk(context))
    }.getOrDefault(PROVIDER_OSM)

    /** Provider the NEXT geocoding call would use. */
    fun geoProviderNow(): String = runCatching {
        resolveGeoProvider(SettingsStore.s.value, UiStore.s.value.geoUsage, nowMonthKey(), geoKeyPresent())
    }.getOrDefault(PROVIDER_OSM)

    fun locked(): Boolean = runCatching { isGeoLocked(UiStore.s.value.geoUsage, nowMonthKey()) }.getOrDefault(false)

    /** Human reason for a fallback (shown on the picker badge / status lines). */
    fun fallbackReason(context: Context, forMap: Boolean): String? {
        val s = SettingsStore.s.value
        if (mapProviderNormalized(s.mapProvider) != PROVIDER_GOOGLE) return "manual"
        if (locked()) return "Google limit reached"
        if (forMap) {
            if (!sdkKeyPresent(context)) return "Google map key not configured"
            if (!playServicesOk(context)) return "no Google Play services"
        } else {
            if (!geoKeyPresent()) return "Google key not added"
            val u = usageForMonth(UiStore.s.value.geoUsage, nowMonthKey())
            if (u.count >= s.geoLimit) return "Google limit reached"
        }
        return null
    }

    /** Lock the rest of the month; one toast + one log line, never a key. */
    fun lockNow(context: Context, reason: String) {
        runCatching {
            val key = nowMonthKey()
            UiStore.update {
                it.copy(
                    geoUsage = usageForMonth(it.geoUsage, key).copy(lockedMonth = key),
                    pendingNotice = "Google limit reached ($reason) — using OSM until ${nextMonthLabel(key)}. Change in Settings › Maps & Location."
                )
            }
            Logger.e(context, "GEO", null, "Google geocoding locked for $key: $reason")
        }.onFailure { Logger.e(context, "GEO", it, "lock write failed") }
    }

    private fun countCall(context: Context) {
        runCatching {
            val key = nowMonthKey()
            val limit = SettingsStore.s.value.geoLimit
            UiStore.update { st ->
                val after = usageAfterCall(st.geoUsage, key, limit)
                val justLocked = after.lockedMonth == key && usageForMonth(st.geoUsage, key).lockedMonth != key
                st.copy(
                    geoUsage = after,
                    pendingNotice = if (justLocked)
                        "Google limit reached ($limit this month) — using OSM until ${nextMonthLabel(key)}. Change in Settings › Maps & Location."
                    else st.pendingNotice
                )
            }
        }.onFailure { Logger.e(context, "GEO", it, "usage count failed") }
    }

    /**
     * Address → coordinates. Google first when the rule allows, else / on failure the OSM path
     * (platform Geocoder — what the app used before 2.02). Never throws; null = nothing found.
     */
    suspend fun lookup(context: Context, query: String): Hit? = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext null
        if (geoProviderNow() == PROVIDER_GOOGLE) {
            val g = googleLookup(context, q)
            if (g != null) return@withContext g
        }
        osmLookup(context, q)
    }

    private fun googleLookup(context: Context, q: String): Hit? {
        val key = KeyStore.valueOrNull(KeyStore.GOOGLE_GEOCODING) ?: return null
        countCall(context)
        var conn: HttpURLConnection? = null
        return runCatching {
            val url = URL("https://maps.googleapis.com/maps/api/geocode/json?address=" + URLEncoder.encode(q, "UTF-8") + "&key=" + key)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000; requestMethod = "GET"
            }
            val code = conn!!.responseCode
            val body = runCatching { (if (code in 200..299) conn!!.inputStream else conn!!.errorStream)?.bufferedReader()?.readText() }.getOrNull() ?: ""
            val json = runCatching { JSONObject(body) }.getOrNull()
            val status = json?.optString("status")
            when (classifyGeoStatus(status, code)) {
                GeoOutcome.OK -> {
                    val r = json!!.getJSONArray("results").getJSONObject(0)
                    val loc = r.getJSONObject("geometry").getJSONObject("location")
                    Hit(loc.getDouble("lat"), loc.getDouble("lng"), r.optString("formatted_address", q), PROVIDER_GOOGLE)
                }
                GeoOutcome.EMPTY -> null
                GeoOutcome.QUOTA_LOCK -> { lockNow(context, "quota answer from Google"); null }
                GeoOutcome.DENIED_LOCK -> { lockNow(context, "request denied by Google — check the key"); null }
                GeoOutcome.TRANSIENT -> { Logger.e(context, "GEO", null, "Google geocoding transient failure (http $code, ${status ?: "no status"}) — OSM for this call"); null }
            }
        }.onFailure {
            // never let a URL (with the key) reach the log: message redacted, throwable NOT attached
            Logger.e(context, "GEO", null, "Google geocoding call failed — OSM for this call: " + redactKey(it.toString()))
        }.getOrNull().also { runCatching { conn?.disconnect() } }
    }

    private fun osmLookup(context: Context, q: String): Hit? = runCatching {
        @Suppress("DEPRECATION")
        val hit = Geocoder(context).getFromLocationName(q, 1)?.firstOrNull() ?: return null
        Hit(hit.latitude, hit.longitude, hit.getAddressLine(0) ?: q, PROVIDER_OSM)
    }.onFailure { Logger.e(context, "GEO", it, "OSM (device geocoder) lookup failed") }.getOrNull()
}
