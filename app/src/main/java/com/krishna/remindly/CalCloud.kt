package com.krishna.remindly

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * v1.56 PLAN B (Krishna approved): read Google Calendar DIRECTLY from the cloud, bypassing device
 * sync entirely — the root of the "new events never arrive" saga was that no app can force
 * another app's sync adapter to run. Read-only scope; the provider read remains the fallback for
 * offline / not-signed-in / console-not-configured, so the view is degraded, never blank.
 *
 * PREREQUISITE ON KRISHNA'S SIDE: Google Cloud console (remindly-5c1e6) must have the Calendar API
 * enabled and the calendar.readonly scope on the consent screen — until then requests 403 with
 * "accessNotConfigured", which is logged with that exact hint.
 *
 * Phase-1 limit, stated honestly: the cloud path reads ALL calendars of the signed-in account.
 * The picker's narrowing uses device-provider row IDs, which do not map to cloud calendar IDs —
 * cloud-side narrowing is phase-2 work.
 */
object CalCloud {

    const val SCOPE = "oauth2:https://www.googleapis.com/auth/calendar.readonly"

    /** Events for the window, or null when the cloud path is unavailable (caller falls back). */
    fun fetch(context: Context, days: Int, past: Boolean): List<CalEvent>? {
        val account = GoogleSignIn.getLastSignedInAccount(context)?.account ?: run {
            Logger.e(context, "CALREAD", null, "cloud: not signed in — provider fallback")
            return null
        }
        return try {
            val token = GoogleAuthUtil.getToken(context, account, SCOPE)
            val (from, to) = calendarWindowBounds(System.currentTimeMillis(), days, past)
            val ids = calendarIds(token) ?: return null
            // v1.57: honour the picker's cloud narrowing (empty = all; Select None = nothing).
            val st = SettingsStore.s.value
            if (st.calendarNone) {
                Logger.e(context, "CALREAD", null, "cloud: Select None — 0 events")
                return emptyList()
            }
            val use = filterCloudCalendars(ids, st.calendarCloudIds, st.calendarNone)
            if (use.isEmpty() && ids.isNotEmpty())
                Logger.e(context, "CALREAD", null, "cloud: selection matches 0 of ${ids.size} calendars — re-pick in settings")
            val out = mutableListOf<CalEvent>()
            use.forEach { id -> events(token, id, from, to)?.let(out::addAll) ?: return null }
            Logger.e(context, "CALREAD", null, "cloud: ${out.size} events from ${use.size} calendar(s) [${if (past) "PAST" else "ACTIVE"}]")
            out.sortedBy { it.start }
        } catch (e: UserRecoverableAuthException) {
            Logger.e(context, "CALREAD", e, "cloud: authorization needed — open Google Account settings and sign in again; provider fallback")
            null
        } catch (e: Exception) {
            Logger.e(context, "CALREAD", e, "cloud fetch failed — provider fallback")
            null
        }
    }

    /** v1.57: cloud calendar list (id → name) for the picker; null when unavailable. */
    fun listCalendars(context: Context): List<Pair<String, String>>? {
        val account = GoogleSignIn.getLastSignedInAccount(context)?.account ?: return null
        return try {
            val token = GoogleAuthUtil.getToken(context, account, SCOPE)
            val body = get(token, "https://www.googleapis.com/calendar/v3/users/me/calendarList?maxResults=50&fields=items(id,summary)") ?: return null
            val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
            (0 until items.length()).mapNotNull { i ->
                items.optJSONObject(i)?.let { o ->
                    val id = o.optString("id"); val nm = o.optString("summary")
                    if (id.isNotBlank()) id to nm.ifBlank { id } else null
                }
            }
        } catch (e: Exception) {
            Logger.e(context, "CALREAD", e, "cloud calendar list failed"); null
        }
    }

    private fun calendarIds(token: String): List<String>? {
        val body = get(token, "https://www.googleapis.com/calendar/v3/users/me/calendarList?maxResults=50&fields=items(id)") ?: return null
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() } }
    }

    private fun events(token: String, calId: String, from: Long, to: Long): List<CalEvent>? {
        val url = "https://www.googleapis.com/calendar/v3/calendars/${URLEncoder.encode(calId, "UTF-8")}/events" +
            "?singleEvents=true&orderBy=startTime&maxResults=250" +
            "&timeMin=${rfc3339(from)}&timeMax=${rfc3339(to)}" +
            "&fields=items(summary,status,start,end)"
        val body = get(token, url) ?: return null
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<CalEvent>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            if (o.optString("status") == "cancelled") continue
            val st = o.optJSONObject("start") ?: continue
            val en = o.optJSONObject("end")
            val allDay = st.has("date") && !st.has("dateTime")
            val start = parseWhen(st) ?: continue
            val end = en?.let { parseWhen(it) } ?: start
            val title = o.optString("summary").takeIf { it.isNotBlank() } ?: "(no title)"
            out.add(CalEvent(id = (calId + start + title).hashCode().toLong(), title = title, start = start, end = end, allDay = allDay))
        }
        return out
    }

    private fun get(token: String, url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Authorization", "Bearer $token")
            if (c.responseCode == 200) c.inputStream.bufferedReader().readText()
            else {
                val err = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull() ?: ""
                val hint = if ("accessNotConfigured" in err || c.responseCode == 403)
                    " — enable the Google Calendar API + calendar.readonly scope in the Cloud console (remindly-5c1e6)" else ""
                Logger.e(Stores.appContext, "CALREAD", null, "cloud HTTP ${c.responseCode}$hint")
                null
            }
        } finally { c.disconnect() }
    }

    private fun parseWhen(o: JSONObject): Long? {
        o.optString("dateTime").takeIf { it.isNotBlank() }?.let { dt ->
            for (p in arrayOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
                runCatching {
                    val f = SimpleDateFormat(p, Locale.US)
                    if (p.endsWith("'Z'")) f.timeZone = TimeZone.getTimeZone("UTC")
                    return f.parse(dt)!!.time
                }
            }
            return null
        }
        o.optString("date").takeIf { it.isNotBlank() }?.let { d ->
            runCatching { return SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d)!!.time }
            return null
        }
        return null
    }

    private fun rfc3339(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return URLEncoder.encode(f.format(java.util.Date(ms)), "UTF-8")
    }
}
