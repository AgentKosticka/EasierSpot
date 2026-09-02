package com.agentkosticka.easierspot.shared

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.agentkosticka.easierspot.R
import com.agentkosticka.easierspot.ble.BleConstants
import com.agentkosticka.easierspot.ble.client.TrustedServerProfile
import com.agentkosticka.easierspot.ble.client.TrustedServerStore
import com.agentkosticka.easierspot.ble.client.isRecentlyPresent
import com.agentkosticka.easierspot.service.ConnectTrigger
import com.agentkosticka.easierspot.service.TrustedConnectLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Adds BLE-backed virtual EasierSpot rows to the Settings Wi-Fi picker when Android's hidden Shared
 * Connectivity provider cannot be selected. It consumes window-state metadata only; it never reads
 * accessibility node text/content and immediately removes its overlay outside a Wi-Fi picker.
 */
class EasierSpotWifiPickerAccessibilityService : AccessibilityService(), WifiPickerCompanionHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var overlay: View? = null
    private var pickerVisible = false
    private var refreshJob: Job? = null

    private val refreshRunnable = Runnable { loadAndRender() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WindowManager::class.java)
        WifiPickerCompanionBridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        pickerVisible = isLikelyWifiPickerWindow(
            event.packageName?.toString(),
            event.className?.toString()
        )
        if (pickerVisible) requestPickerRefresh() else hideOverlay()
    }

    override fun onInterrupt() {
        pickerVisible = false
        hideOverlay()
    }

    override fun requestPickerRefresh() {
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, 120L)
    }

    private fun loadAndRender() {
        if (!pickerVisible || SharedConnectivityBackends.current.capability().isActive) {
            hideOverlay()
            return
        }
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val now = System.currentTimeMillis()
            val present = TrustedServerStore(applicationContext).all()
                .filter { it.isRecentlyPresent(now) }
                .sortedWith(
                    compareByDescending<TrustedServerProfile> {
                        it.lastPresenceFlags and BleConstants.FLAG_HOTSPOT_ACTIVE != 0
                    }.thenByDescending { it.lastPresenceAt }
                )
            withContext(Dispatchers.Main) {
                if (!pickerVisible || SharedConnectivityBackends.current.capability().isActive) {
                    hideOverlay()
                    return@withContext
                }
                if (present.isEmpty()) hideOverlay() else showOverlay(present)
                mainHandler.removeCallbacks(refreshRunnable)
                mainHandler.postDelayed(refreshRunnable, POLL_INTERVAL_MS)
            }
        }
    }

    private fun showOverlay(profiles: List<TrustedServerProfile>) {
        hideOverlay()
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val surface = if (dark) Color.rgb(40, 40, 43) else Color.WHITE
        val primary = if (dark) Color.WHITE else Color.rgb(28, 28, 30)
        val secondary = if (dark) Color.rgb(190, 190, 196) else Color.rgb(92, 92, 98)
        val divider = if (dark) Color.rgb(70, 70, 74) else Color.rgb(225, 225, 230)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(10))
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(18).toFloat()
            }
            elevation = dp(10).toFloat()
        }
        card.addView(TextView(this).apply {
            text = getString(R.string.wifi_picker_companion_header)
            setTextColor(secondary)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(5))
        })

        val shown = profiles.take(MAX_ROWS)
        shown.forEachIndexed { index, profile ->
            if (index > 0) {
                card.addView(
                    View(this).apply { setBackgroundColor(divider) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                )
            }
            card.addView(networkRow(profile, primary, secondary))
        }
        if (profiles.size > shown.size) {
            card.addView(TextView(this).apply {
                text = getString(R.string.wifi_picker_companion_more, profiles.size - shown.size)
                setTextColor(secondary)
                textSize = 12f
                setPadding(0, dp(6), 0, 0)
            })
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val width = min(screenWidth - dp(24), dp(520)).coerceAtLeast(dp(240))
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(28)
        }
        runCatching {
            windowManager.addView(card, params)
            overlay = card
        }
    }

    private fun networkRow(
        profile: TrustedServerProfile,
        primary: Int,
        secondary: Int
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(9), 0, dp(9))
        isClickable = true
        isFocusable = true

        addView(TextView(this@EasierSpotWifiPickerAccessibilityService).apply {
            text = profile.label
            setTextColor(primary)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        })
        val ready = profile.lastPresenceFlags and BleConstants.FLAG_HOTSPOT_ACTIVE != 0
        addView(TextView(this@EasierSpotWifiPickerAccessibilityService).apply {
            text = getString(
                if (ready) R.string.wifi_picker_companion_ready
                else R.string.wifi_picker_companion_offline
            )
            setTextColor(secondary)
            textSize = 13f
            maxLines = 2
        })
        setOnClickListener {
            TrustedConnectLauncher.connect(
                applicationContext,
                profile.discoveryToken,
                ConnectTrigger.SYSTEM_WIFI_PICKER
            )
            Toast.makeText(
                applicationContext,
                getString(R.string.wifi_picker_companion_connecting, profile.label),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun hideOverlay() {
        val current = overlay ?: return
        overlay = null
        if (::windowManager.isInitialized) {
            runCatching { windowManager.removeViewImmediate(current) }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        mainHandler.removeCallbacks(refreshRunnable)
        refreshJob?.cancel()
        WifiPickerCompanionBridge.detach(this)
        hideOverlay()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val MAX_ROWS = 4
        private const val POLL_INTERVAL_MS = 1_500L
    }
}
