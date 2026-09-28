package com.krishna.remindly

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * v1.5: the view never resets. Last-open tab, each tab's Active/Done filter,
 * the open Settings section, every collapse state and every scroll position
 * survive tab switches AND app restarts.
 *
 * Deliberately separate from AppSettings so view-state never shows up in the
 * settings-change confirmation popup and never pollutes JSON backups.
 */
data class UiState(
    val lastTab: Int = 0,
    val doneFilter: Map<String, Boolean> = emptyMap(),      // "TASKS"/"SHOP"/"LEARN"/"CALLS" -> showing Done?
    val settingsOpenKey: String? = "alerts",                // which Settings accordion is open ("" = none)
    val collapsed: Map<String, Set<String>> = emptyMap(),   // "cy-TASKS-A" etc -> collapsed keys
    val scroll: Map<String, List<Int>> = emptyMap(),        // "sc-TASKS-A" -> [index, offset]
    val savedNotice: List<String> = emptyList(),            // settings auto-saved on app close — shown once
    val openSubs: Set<String> = emptySet(),                 // open Appearance subgroups
    // v1.90 mode system (view-state, deliberately NOT in AppSettings — see class doc above).
    val appMode: String = "TASK",                           // "TASK" / "SHOP"
    val lastShopTab: Int = 0,                               // shop-mode nav: 0 Buy · 1 Shops · 2 Products · 3 Settings
    // v2.02 (N33): per-device Google geocoding usage + lock (deliberately not synced) and a
    // one-time notice shown as a toast on the next foreground (radius snap, limit lock…).
    val geoUsage: GeoUsage = GeoUsage(),
    val pendingNotice: String? = null,
    // v2.05 (N38): the "Buy Now" view — armed by a shop geofence, hidden only by the user.
    // Per-device view state (never synced), survives restarts until hidden.
    val buyNowShopId: Long? = null,
    val buyNowAt: Long = 0L,
    // v2.9 (N47): update feed state — last check time and the newest feed seen (per device).
    val updateLastCheck: Long = 0L,
    val updateFeedJson: String? = null
)

// Gson-safe twin: every field nullable, defaults reapplied on load.
private data class UiStateRaw(
    val lastTab: Int?,
    val doneFilter: Map<String, Boolean>?,
    val settingsOpenKey: String?,
    val collapsed: Map<String, Set<String>>?,
    val scroll: Map<String, List<Int>>?,
    val savedNotice: List<String>?,
    val openSubs: Set<String>?,
    val appMode: String?,
    val lastShopTab: Int?,
    val geoUsage: GeoUsageRaw?,
    val pendingNotice: String?,
    val buyNowShopId: Long?,
    val buyNowAt: Long?,
    val updateLastCheck: Long?,
    val updateFeedJson: String?
)

/** Gson-safe twin of GeoUsage (fields may be null in old/partial JSON). */
data class GeoUsageRaw(val monthKey: String?, val count: Int?, val lockedMonth: String?)

object UiStore {
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    val s = MutableStateFlow(UiState())

    fun init(context: Context) {
        prefs = context.getSharedPreferences("remindly_ui", Context.MODE_PRIVATE)
        val raw = prefs.getString("ui", null) ?: return
        runCatching {
            val r = gson.fromJson(raw, UiStateRaw::class.java)
            s.value = UiState(
                lastTab = (r.lastTab ?: 0).coerceIn(0, 4),
                doneFilter = r.doneFilter ?: emptyMap(),
                settingsOpenKey = r.settingsOpenKey,
                collapsed = r.collapsed ?: emptyMap(),
                scroll = r.scroll ?: emptyMap(),
                savedNotice = r.savedNotice ?: emptyList(),
                openSubs = r.openSubs ?: emptySet(),
                appMode = modeNormalized(r.appMode),
                lastShopTab = (r.lastShopTab ?: 0).coerceIn(0, 3),
                geoUsage = r.geoUsage?.let { GeoUsage(it.monthKey ?: "", (it.count ?: 0).coerceAtLeast(0), it.lockedMonth) } ?: GeoUsage(),
                pendingNotice = r.pendingNotice,
                buyNowShopId = r.buyNowShopId,
                buyNowAt = r.buyNowAt ?: 0L,
                updateLastCheck = r.updateLastCheck ?: 0L,
                updateFeedJson = r.updateFeedJson
            )
        }
    }

    fun update(transform: (UiState) -> UiState) {
        s.value = transform(s.value)
        runCatching { prefs.edit().putString("ui", gson.toJson(s.value)).apply() }
    }

    // ---- convenience ----

    fun doneFor(tabKey: String): Boolean = s.value.doneFilter[tabKey] ?: false

    fun setDone(tabKey: String, done: Boolean) =
        update { it.copy(doneFilter = it.doneFilter + (tabKey to done)) }

    fun collapsedFor(key: String): Set<String> = s.value.collapsed[key] ?: emptySet()

    fun setCollapsed(key: String, value: Set<String>) =
        update { it.copy(collapsed = it.collapsed + (key to value)) }

    fun scrollFor(key: String): List<Int> = s.value.scroll[key] ?: listOf(0, 0)

    fun setScroll(key: String, index: Int, offset: Int) =
        update { it.copy(scroll = it.scroll + (key to listOf(index, offset))) }

    fun toggleSub(key: String) = update {
        it.copy(openSubs = if (key in it.openSubs) it.openSubs - key else it.openSubs + key)
    }
}
