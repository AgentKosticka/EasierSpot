package com.agentkosticka.easierspot.shared

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.sharedconnectivity.app.HotspotNetwork
import android.net.wifi.sharedconnectivity.app.HotspotNetworkConnectionStatus
import android.net.wifi.sharedconnectivity.app.KnownNetwork
import android.net.wifi.sharedconnectivity.app.SharedConnectivitySettingsState
import android.net.wifi.sharedconnectivity.service.SharedConnectivityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.agentkosticka.easierspot.ble.client.PRESENCE_WINDOW_MS
import com.agentkosticka.easierspot.ble.client.TrustedServerProfile
import com.agentkosticka.easierspot.ble.client.TrustedServerStore
import com.agentkosticka.easierspot.ble.client.isRecentlyPresent
import com.agentkosticka.easierspot.service.BleClientService
import com.agentkosticka.easierspot.service.ClientConnectionState
import com.agentkosticka.easierspot.service.ConnectTrigger
import com.agentkosticka.easierspot.service.TrustedConnectLauncher
import com.agentkosticka.easierspot.ui.client.ClientActivity
import com.agentkosticka.easierspot.util.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EasierSpotSharedConnectivityService : SharedConnectivityService(), SharedConnectivityPublishHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var store: TrustedServerStore
    private var selectedNetwork: HotspotNetwork? = null
    private var liveHotspotActive = false
    private val expiryRefresh = Runnable { requestRepublish() }

    override fun onCreate() {
        super.onCreate()
        store = TrustedServerStore(this)
        SharedConnectivityPublisher.attach(this)
        scope.launch {
            val capability = SharedConnectivityActivation.reconcile(applicationContext)
            withContext(Dispatchers.Main) {
                setSettingsState(buildSettingsState(capability.isActive))
            }
            requestRepublish()
        }
        scope.launch {
            BleClientService.connectionState.collect { state ->
                onClientConnectionStateChanged(state)
            }
        }
    }

    /**
     * Android 14 shipped a Context-taking Builder and an Intent settings setter. Newer releases
     * use a no-arg Builder and a PendingIntent setter. Construct this tiny framework object by
     * reflection so one APK remains binary-compatible with both shapes.
     */
    private fun buildSettingsState(enabled: Boolean): SharedConnectivitySettingsState {
        val builderClass = Class.forName(
            "android.net.wifi.sharedconnectivity.app.SharedConnectivitySettingsState\$Builder"
        )
        val builder = runCatching {
            builderClass.getConstructor(Context::class.java).newInstance(this)
        }.getOrElse {
            builderClass.getConstructor().newInstance()
        }

        builderClass.getMethod(
            "setInstantTetherEnabled",
            Boolean::class.javaPrimitiveType
        ).invoke(builder, enabled)
        builderClass.getMethod("setExtras", Bundle::class.java).invoke(builder, Bundle.EMPTY)

        if (enabled) {
            val settingsIntent = Intent(this, ClientActivity::class.java)
            val settingsMethod = builderClass.methods.firstOrNull {
                it.name == "setInstantTetherSettingsPendingIntent" && it.parameterTypes.size == 1
            }
            settingsMethod?.let { method ->
                runCatching {
                    val argument: Any = when (method.parameterTypes[0]) {
                        Intent::class.java -> settingsIntent
                        PendingIntent::class.java -> PendingIntent.getActivity(
                            this,
                            0,
                            settingsIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        else -> return@runCatching
                    }
                    method.invoke(builder, argument)
                }.onFailure {
                    LogUtils.d(TAG, "Shared Connectivity settings shortcut unavailable: ${it.message}")
                }
            }
        }

        return builderClass.getMethod("build").invoke(builder) as SharedConnectivitySettingsState
    }

    override fun requestRepublish() {
        mainHandler.removeCallbacks(expiryRefresh)
        scope.launch {
            if (!SharedConnectivityActivation.capability().isActive) {
                withContext(Dispatchers.Main) { setHotspotNetworks(emptyList()) }
                return@launch
            }
            val now = System.currentTimeMillis()
            val selectedDeviceId = selectedNetwork?.deviceId
            val present = store.all().filter { it.isRecentlyPresent(now) }
            val networks = present.map { profile ->
                HotspotNetworkMapper.map(
                    profile,
                    forceHotspotActive = liveHotspotActive &&
                        selectedDeviceId == stableSharedConnectivityDeviceId(profile.fingerprint)
                )
            }
            val earliestExpiry = present.minOfOrNull { it.lastPresenceAt + PRESENCE_WINDOW_MS }
            withContext(Dispatchers.Main) {
                setHotspotNetworks(networks)
                if (earliestExpiry != null) {
                    mainHandler.postDelayed(expiryRefresh, (earliestExpiry - now).coerceAtLeast(100L))
                }
            }
        }
    }

    override fun onConnectHotspotNetwork(network: HotspotNetwork) {
        selectedNetwork = network
        publishStatus(HotspotNetworkConnectionStatus.CONNECTION_STATUS_ENABLING_HOTSPOT, network)
        scope.launch {
            if (!SharedConnectivityActivation.capability().isActive) {
                publishStatus(HotspotNetworkConnectionStatus.CONNECTION_STATUS_UNKNOWN_ERROR, network)
                return@launch
            }
            val profile = resolveProfile(network)
            if (profile == null || !profile.isRecentlyPresent()) {
                publishStatus(
                    HotspotNetworkConnectionStatus.CONNECTION_STATUS_ENABLING_HOTSPOT_TIMEOUT,
                    network
                )
                return@launch
            }
            LogUtils.i(TAG, "System Wi-Fi picker requested ${profile.label}")
            TrustedConnectLauncher.connect(
                applicationContext,
                profile.discoveryToken,
                ConnectTrigger.SYSTEM_WIFI_PICKER
            )
        }
    }

    override fun onDisconnectHotspotNetwork(network: HotspotNetwork) {
        val selected = selectedNetwork ?: return
        if (selected.deviceId != network.deviceId) return
        applicationContext.startService(
            Intent(applicationContext, BleClientService::class.java)
                .setAction(BleClientService.ACTION_DISCONNECT)
        )
        selectedNetwork = null
        liveHotspotActive = false
        publishStatus(HotspotNetworkConnectionStatus.CONNECTION_STATUS_UNKNOWN, network)
        requestRepublish()
    }

    override fun onConnectKnownNetwork(network: KnownNetwork) = Unit
    override fun onForgetKnownNetwork(network: KnownNetwork) = Unit

    override fun onClientConnectionStateChanged(state: ClientConnectionState) {
        val network = selectedNetwork
        liveHotspotActive = state is ClientConnectionState.JoiningWifi ||
            state is ClientConnectionState.Connected
        val status = when (state) {
            ClientConnectionState.Idle,
            is ClientConnectionState.Connected -> HotspotNetworkConnectionStatus.CONNECTION_STATUS_UNKNOWN
            is ClientConnectionState.Failed -> failureStatus(state)
            else -> HotspotNetworkConnectionStatus.CONNECTION_STATUS_ENABLING_HOTSPOT
        }
        if (network != null) publishStatus(status, network)
        if (state is ClientConnectionState.JoiningWifi || state is ClientConnectionState.Connected ||
            state is ClientConnectionState.Idle || state is ClientConnectionState.Failed
        ) requestRepublish()
    }

    private fun resolveProfile(network: HotspotNetwork): TrustedServerProfile? {
        HotspotNetworkMapper.fingerprint(network)?.let(store::findByFingerprint)?.let { return it }
        return store.all().firstOrNull {
            stableSharedConnectivityDeviceId(it.fingerprint) == network.deviceId
        }
    }

    private fun failureStatus(state: ClientConnectionState.Failed): Int {
        val text = "${state.title} ${state.detail}".lowercase()
        return when {
            ("timeout" in text || "too long" in text) && ("hotspot" in text || "tether" in text) ->
                HotspotNetworkConnectionStatus.CONNECTION_STATUS_ENABLING_HOTSPOT_TIMEOUT
            "hotspot" in text || "tether" in text ->
                HotspotNetworkConnectionStatus.CONNECTION_STATUS_ENABLING_HOTSPOT_FAILED
            "wi-fi" in text || "wifi" in text || "network" in text ->
                HotspotNetworkConnectionStatus.CONNECTION_STATUS_CONNECT_TO_HOTSPOT_FAILED
            else -> HotspotNetworkConnectionStatus.CONNECTION_STATUS_UNKNOWN_ERROR
        }
    }

    private fun publishStatus(status: Int, network: HotspotNetwork?) {
        mainHandler.post {
            val builder = HotspotNetworkConnectionStatus.Builder()
                .setStatus(status)
                .setExtras(Bundle.EMPTY)
            if (network != null) builder.setHotspotNetwork(network)
            updateHotspotNetworkConnectionStatus(builder.build())
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(expiryRefresh)
        SharedConnectivityPublisher.detach(this)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SharedConnectivity"
    }
}
