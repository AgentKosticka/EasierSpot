package com.agentkosticka.easierspot.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.agentkosticka.easierspot.util.LogUtils
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    const val LATEST_RELEASE_URL = "https://github.com/AgentKosticka/EasierSpot/releases/latest"
    private const val RELEASE_TAG_SEGMENT = "/releases/tag/"
    private const val STALE_AFTER_MS = 60 * 60 * 1000L
    private const val MIN_TRIGGER_GAP_MS = 5_000L
    private val checkInFlight = AtomicBoolean(false)
    @Volatile
    private var lastTriggerEpochMs: Long = 0L

    data class State(
        val latestVersionName: String?,
        val updateAvailable: Boolean,
        val checkedAtEpochMs: Long
    )

    fun getState(context: Context): State {
        return State(
            latestVersionName = UpdateCheckPreferences.getLatestVersionName(context),
            updateAvailable = UpdateCheckPreferences.isUpdateAvailable(context),
            checkedAtEpochMs = UpdateCheckPreferences.getLastCheckEpochMs(context)
        )
    }

    fun refreshIfStale(context: Context): State {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val lastCheck = UpdateCheckPreferences.getLastCheckEpochMs(appContext)
        if (now - lastCheck < STALE_AFTER_MS) {
            return getState(appContext)
        }
        return runSingleCheck(appContext, now)
    }

    fun refreshNow(context: Context): State {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        return runSingleCheck(appContext, now)
    }

    private fun runSingleCheck(appContext: Context, now: Long): State {
        if (now - lastTriggerEpochMs < MIN_TRIGGER_GAP_MS) {
            return getState(appContext)
        }
        if (!checkInFlight.compareAndSet(false, true)) {
            return getState(appContext)
        }
        lastTriggerEpochMs = now
        try {
            return refreshNowInternal(appContext, now)
        } finally {
            checkInFlight.set(false)
        }
    }

    private fun refreshNowInternal(appContext: Context, now: Long): State {
        if (!isOnline(appContext)) {
            LogUtils.d(TAG, "Skipping update check: offline")
            return getState(appContext)
        }

        val latest = fetchLatestVersionName(appContext)
        if (latest == null) {
            LogUtils.w(TAG, "Failed to resolve latest release tag")
            return getState(appContext)
        }

        val currentVersionName = getCurrentVersionName(appContext)
        val updateAvailable = compareVersions(latest, currentVersionName) > 0
        UpdateCheckPreferences.setState(
            context = appContext,
            lastCheckEpochMs = now,
            latestVersionName = latest,
            updateAvailable = updateAvailable
        )
        LogUtils.i(
            TAG,
            "Update check complete. current=$currentVersionName, latest=$latest, updateAvailable=$updateAvailable"
        )

        return getState(appContext)
    }

    private fun fetchLatestVersionName(context: Context): String? {
        return try {
            val finalUrl = resolveFinalUrl(context, LATEST_RELEASE_URL)
            parseVersionFromFinalUrl(finalUrl) ?: run {
                val fallbackFinalUrl = resolveFinalUrlWithGet(context, LATEST_RELEASE_URL)
                parseVersionFromFinalUrl(fallbackFinalUrl)
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "Error fetching latest release URL", e)
            null
        }
    }

    private fun resolveFinalUrl(context: Context, startUrl: String): String {
        var currentUrl = startUrl
        repeat(6) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(currentUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = TimeUnit.SECONDS.toMillis(8).toInt()
                conn.readTimeout = TimeUnit.SECONDS.toMillis(8).toInt()
                conn.requestMethod = "HEAD"
                conn.setRequestProperty("User-Agent", "${context.packageName}/update-check")
                conn.connect()

                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    if (location.isNullOrBlank()) return currentUrl
                    currentUrl = URL(URL(currentUrl), location).toString()
                } else {
                    return conn.url?.toString().orEmpty().ifBlank { currentUrl }
                }
            } finally {
                conn?.disconnect()
            }
        }
        return currentUrl
    }

    private fun parseVersionFromFinalUrl(finalUrl: String): String? {
        val markerIndex = finalUrl.indexOf(RELEASE_TAG_SEGMENT)
        if (markerIndex < 0) {
            LogUtils.d(TAG, "Final URL does not contain tag segment: $finalUrl")
            return null
        }

        val tag = finalUrl
            .substring(markerIndex + RELEASE_TAG_SEGMENT.length)
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore('/')
            .trim()
        if (tag.isEmpty()) {
            return null
        }

        LogUtils.d(TAG, "Resolved latest release tag: $tag")
        return tag.removePrefix("v").removePrefix("V")
    }

    private fun resolveFinalUrlWithGet(context: Context, startUrl: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(startUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = TimeUnit.SECONDS.toMillis(8).toInt()
            conn.readTimeout = TimeUnit.SECONDS.toMillis(8).toInt()
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "${context.packageName}/update-check")
            conn.connect()
            conn.url?.toString().orEmpty().ifBlank { startUrl }
        } finally {
            conn?.disconnect()
        }
    }

    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun getCurrentVersionName(context: Context): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName.orEmpty()
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to read current versionName", e)
            ""
        }
    }

    private fun compareVersions(candidate: String, current: String): Int {
        val candidateParts = candidate.split('.')
        val currentParts = current.split('.')
        val max = maxOf(candidateParts.size, currentParts.size)
        for (i in 0 until max) {
            val left = parseVersionPart(candidateParts.getOrNull(i))
            val right = parseVersionPart(currentParts.getOrNull(i))
            if (left != right) {
                return left.compareTo(right)
            }
        }
        return 0
    }

    private fun parseVersionPart(part: String?): Int {
        if (part.isNullOrBlank()) return 0
        val digits = part.takeWhile { it.isDigit() }
        return digits.toIntOrNull() ?: 0
    }
}
