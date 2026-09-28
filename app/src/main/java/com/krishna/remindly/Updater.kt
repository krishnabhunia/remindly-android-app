package com.krishna.remindly

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * v2.9 (N47) — in-app updates from the GitHub repository (krishnabhunia/remindly-android-app).
 *
 * Source of truth: releases/version.json in the repo (published with every release, mirrored into
 * a GitHub Release by an Action). The app CHECKS (once per 24 h and on demand), DOWNLOADS into its
 * own cache, VERIFIES the SHA-256 against the feed, and then hands the file to the system installer.
 * Android never installs silently for an ordinary app: the final install is one tap on the system
 * dialog, and the first time the user allows "install unknown apps" for Remindly. Same signing key
 * → in-place update, all data kept. Every path is guarded and logged; nothing here can crash the app.
 */
object Updater {

    private const val TAG = "UPDATE"
    private const val CACHE_DIR = "updates"

    fun installedCode(context: Context): Int = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    }.getOrDefault(0)

    fun installedName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    /** The newest feed seen (persisted), or null. */
    fun cachedFeed(): VersionFeed? = UiStore.s.value.updateFeedJson?.let { parseVersionFeed(it) }

    /** Is a newer build known right now? */
    fun pending(context: Context): VersionFeed? = cachedFeed()?.takeIf { updateAvailable(it, installedCode(context)) }

    private fun onWifi(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(false)

    /**
     * Fetch the feed. Returns the feed (newer or not) or null on any failure. Records the check time.
     * [notify] = raise the "update available" notification when newer (the automatic path).
     */
    suspend fun check(context: Context, notify: Boolean): VersionFeed? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val feed = runCatching {
            val conn = (URL(UPDATE_FEED_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000; requestMethod = "GET"
                setRequestProperty("Cache-Control", "no-cache")
            }
            try {
                if (conn.responseCode !in 200..299) { Logger.e(context, TAG, null, "feed http ${conn.responseCode}"); null }
                else parseVersionFeed(conn.inputStream.bufferedReader().readText())
            } finally { conn.disconnect() }
        }.onFailure { Logger.e(context, TAG, it, "feed fetch failed") }.getOrNull()
        runCatching {
            UiStore.update { it.copy(updateLastCheck = now, updateFeedJson = feed?.let { f -> feedToJson(f) } ?: it.updateFeedJson) }
        }
        if (feed != null) {
            val newer = updateAvailable(feed, installedCode(context))
            Logger.e(context, TAG, null, "feed ${feed.versionName} (${feed.versionCode}) vs installed ${installedCode(context)} → ${if (newer) "UPDATE AVAILABLE" else "up to date"}")
            if (newer && notify) runCatching {
                Alerts.infoNotification(context, "Remindly ${feed.versionName} is available", feed.notes.ifBlank { "Open Settings → Updates to install." }, Tab.TASKS)
            }
        }
        feed
    }

    /** Automatic path: once per 24 h, only if enabled. Never throws. */
    suspend fun checkIfDue(context: Context) {
        runCatching {
            val s = SettingsStore.s.value
            if (!updateCheckDue(UiStore.s.value.updateLastCheck, System.currentTimeMillis(), s.updateAutoCheck)) return
            check(context, notify = true)
        }.onFailure { Logger.e(context, TAG, it, "auto check failed") }
    }

    private fun feedToJson(f: VersionFeed): String = com.google.gson.Gson().toJson(f)

    sealed class Result {
        object Ok : Result()
        data class Blocked(val reason: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * Download the feed's APK into the app cache, verify SHA-256, then launch the installer.
     * Progress is reported through [onProgress] (0..100). Respects the Wi-Fi-only setting.
     */
    suspend fun downloadAndInstall(context: Context, feed: VersionFeed, onProgress: (Int) -> Unit): Result = withContext(Dispatchers.IO) {
        val s = SettingsStore.s.value
        if (s.updateWifiOnly && !onWifi(context)) return@withContext Result.Blocked("Wi-Fi only is on and you are on mobile data")
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val out = File(dir, feed.apk)
        runCatching {
            // a previous verified download is reused
            if (out.exists() && sha256Hex(out.readBytes()) == feed.sha256) { launchInstaller(context, out); return@withContext Result.Ok }
            out.delete()
            val conn = (URL(feed.apkUrl).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 30_000 }
            try {
                if (conn.responseCode !in 200..299) return@withContext Result.Failed("download http ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: feed.sizeBytes
                conn.inputStream.use { inp -> out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var read = 0L; var last = -1
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        o.write(buf, 0, n); read += n
                        val pct = if (total > 0) ((read * 100) / total).toInt().coerceIn(0, 99) else 0
                        if (pct != last) { last = pct; onProgress(pct) }
                    }
                } }
            } finally { conn.disconnect() }
            val sha = sha256Hex(out.readBytes())
            if (sha != feed.sha256) {
                out.delete()
                Logger.e(context, TAG, null, "SHA-256 mismatch — download discarded (expected ${feed.sha256.take(12)}, got ${sha.take(12)})")
                return@withContext Result.Failed("Downloaded file failed verification — not installed")
            }
            onProgress(100)
            Logger.e(context, TAG, null, "downloaded + verified ${feed.apk} (${out.length()} bytes)")
            launchInstaller(context, out)
            Result.Ok
        }.getOrElse { e ->
            Logger.e(context, TAG, e, "download/install failed")
            runCatching { out.delete() }
            Result.Failed(e.message ?: "download failed")
        }
    }

    private fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }

    /** Android 8+: the one-time "install unknown apps" grant lives in a system screen. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    fun openInstallPermission(context: Context) {
        runCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Logger.e(context, TAG, it, "open install-permission screen failed") }
    }

    fun openReleasesPage(context: Context) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UPDATE_RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Logger.e(context, TAG, it, "open releases page failed") }
    }
}
