package com.agentkosticka.easierspot.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.agentkosticka.easierspot.R
import com.agentkosticka.easierspot.ble.BleConstants
import com.agentkosticka.easierspot.ble.BleSessionCrypto
import com.agentkosticka.easierspot.ble.client.BackgroundBleScanner
import com.agentkosticka.easierspot.ble.client.BleDiscoveryRegistrar
import com.agentkosticka.easierspot.ble.client.BleDistressAdvertiser
import com.agentkosticka.easierspot.ble.client.BleWakeAdvertiser
import com.agentkosticka.easierspot.ble.client.DiscoveredServer
import com.agentkosticka.easierspot.ble.client.GattClient
import com.agentkosticka.easierspot.ble.client.TrustedServerProfile
import com.agentkosticka.easierspot.ble.client.TrustedServerStore
import com.agentkosticka.easierspot.ble.client.RecentBleAddressCache
import com.agentkosticka.easierspot.control.UdpControlClient
import com.agentkosticka.easierspot.data.model.HotspotCredentials
import com.agentkosticka.easierspot.hotspot.HotspotManager
import com.agentkosticka.easierspot.hotspot.WifiSuggestionInstaller
import com.agentkosticka.easierspot.privileged.ShizukuState
import com.agentkosticka.easierspot.privileged.ShizukuStateMonitor
import com.agentkosticka.easierspot.ui.MainActivity
import com.agentkosticka.easierspot.ui.client.ClientActivity
import com.agentkosticka.easierspot.ui.settings.AppPreferences
import com.agentkosticka.easierspot.util.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap

/** Owns every active client connection. Idle discovery is OS-owned by [BleDiscoveryRegistrar]. */
@SuppressLint("MissingPermission")
class BleClientService : Service() {
    companion object {
        private const val TAG = "BleClientService"
        private const val CHANNEL_ID = "active_connection_v4"
        private const val NOTIFICATION_ID = 20
        private const val SESSION_PREFS = "active_client_session"
        private const val KEY_TOKEN = "token"
        const val ACTION_STOP = "com.agentkosticka.easierspot.CLIENT_STOP_DISCOVERY"
        const val ACTION_CONNECT = "com.agentkosticka.easierspot.CLIENT_CONNECT"
        const val ACTION_DISCONNECT = "com.agentkosticka.easierspot.CLIENT_DISCONNECT"
        const val ACTION_RETRY = "com.agentkosticka.easierspot.CLIENT_RETRY"
        const val EXTRA_TOKEN = "server_token"
        const val EXTRA_ADDRESS = "server_address"
        const val EXTRA_NAME = "server_name"
        const val EXTRA_NEW_PAIR = "new_pair"
        const val EXTRA_MONITOR_ONLY = "monitor_only"
        const val EXTRA_ALERT_NOTIFICATION_ID = "alert_notification_id"

        private val _connectionState = MutableStateFlow<ClientConnectionState>(ClientConnectionState.Idle)
        val connectionState: StateFlow<ClientConnectionState> = _connectionState.asStateFlow()
        private val discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun start(context: Context) {
            if (!AppPreferences.isBackgroundDiscoveryEnabled(context)) return
            val app = context.applicationContext
            discoveryScope.launch { BleDiscoveryRegistrar.reconcile(app) }
        }

        fun isBackgroundEnabled(context: Context): Boolean =
            AppPreferences.isBackgroundDiscoveryEnabled(context)

        fun connectTrusted(context: Context, token: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BleClientService::class.java).apply {
                    action = ACTION_CONNECT
                    putExtra(EXTRA_TOKEN, token)
                }
            )
        }

        fun pair(context: Context, server: DiscoveredServer) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BleClientService::class.java).apply {
                    action = ACTION_CONNECT
                    putExtra(EXTRA_TOKEN, server.deviceId)
                    putExtra(EXTRA_ADDRESS, server.bluetoothDevice.address)
                    putExtra(EXTRA_NAME, server.deviceName)
                    putExtra(EXTRA_NEW_PAIR, true)
                }
            )
        }

        fun monitorConnectedServer(context: Context, server: DiscoveredServer) {
            connectTrusted(context, server.deviceId)
        }

        fun stop(context: Context) {
            AppPreferences.setBackgroundDiscoveryEnabled(context, false)
            BleDiscoveryRegistrar.stop(context)
            runCatching {
                context.startService(
                    Intent(context, BleClientService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: TrustedServerStore
    private lateinit var scanner: BackgroundBleScanner
    private lateinit var gattClient: GattClient
    private lateinit var wakeAdvertiser: BleWakeAdvertiser
    private lateinit var distressAdvertiser: BleDistressAdvertiser
    private lateinit var connectivity: ConnectivityManager
    private lateinit var diagnostics: ConnectionDiagnostics
    private lateinit var recentAddressCache: RecentBleAddressCache
    private var targetToken: String? = null
    private var targetName: String = "shared phone"
    private var expectedProfile: TrustedServerProfile? = null
    private var targetDevice: BluetoothDevice? = null
    private var credentials: HotspotCredentials? = null
    private var observedRevision: Int = 0
    private var isNewPair = false
    private var attempt = 0
    private var sessionGeneration = 0L
    private var locateJob: Job? = null
    private var phaseTimeoutJob: Job? = null
    private var sessionDeadlineJob: Job? = null
    private var wifiJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var targetNetwork: Network? = null
    private var connected = false
    private var fastPath = false
    private var needsGattFallback = false
    private var hotspotReady = false
    private var shizukuIssued = false
    private var shizukuDelayAfterReadyMs = 1_000L
    private var udpMisses = 0
    private var ending = false
    private var controlOverGatt = false
    private var joinStartedAt = 0L
    private var suggestionOwnedEvidence = false
    private var privilegedSsidMatch = false
    private var evidence: ConnectionEvidence? = null
    private var udpClient: UdpControlClient? = null
    private var udpHelloJob: Job? = null
    private var shizukuRaceJob: Job? = null
    private var fastGattRescueJob: Job? = null
    private var fastGattRescueDue = false
    private val linkPropertiesByNetwork = ConcurrentHashMap<Network, LinkProperties>()

    private val networkCallback = object : ConnectivityManager.NetworkCallback(
        ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
    ) {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val expected = credentials?.ssid ?: return
            val wifiInfo = capabilities.transportInfo as? WifiInfo
            val rawSsid = wifiInfo?.ssid?.removeSurrounding("\"")
            val ssid = rawSsid?.takeUnless {
                it.isBlank() || it == WifiManager.UNKNOWN_SSID || it == "<unknown ssid>"
            }
            val ownedByApp = capabilities.ownerUid == applicationInfo.uid ||
                suggestionOwnerPackage(wifiInfo) == packageName
            if (ssid != expected && !ownedByApp) return
            targetNetwork = network
            if (ssid == expected) {
                diagnostics.event("wifi_associated ssid=$expected")
            }
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                diagnostics.event("internet_validated")
            }
            updateEvidence {
                copy(
                    network = network,
                    observedSsid = ssid,
                    networkOwnedByApp = ownedByApp,
                    internetValidated = capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    )
                )
            }
            linkPropertiesByNetwork[network]?.let { applyLinkProperties(network, it) }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            linkPropertiesByNetwork[network] = linkProperties
            if (network == targetNetwork) applyLinkProperties(network, linkProperties)
        }

        override fun onLost(network: Network) {
            if (network != targetNetwork) return
            linkPropertiesByNetwork.remove(network)
            targetNetwork = null
            updateEvidence {
                copy(
                    network = null,
                    observedSsid = null,
                    hasAssignedAddress = false,
                    dhcpServer = null,
                    authenticatedAck = false,
                    internetValidated = false
                )
            }
            if (!connected) return
            val generation = sessionGeneration
            scope.launch {
                delay(10_000L)
                if (generation == sessionGeneration && targetNetwork == null) {
                    endSession("Shared Wi-Fi disconnected")
                }
            }
        }
    }

    private fun applyLinkProperties(network: Network, linkProperties: LinkProperties) {
        updateEvidence {
            copy(
                network = network,
                hasAssignedAddress = linkProperties.linkAddresses.any { address ->
                    !address.address.isAnyLocalAddress && !address.address.isLinkLocalAddress
                },
                dhcpServer = linkProperties.dhcpServerAddress ?: linkProperties.routes
                    .asSequence()
                    .filter { it.isDefaultRoute }
                    .mapNotNull { it.gateway as? Inet4Address }
                    .firstOrNull()
            )
        }
    }

    private fun suggestionOwnerPackage(wifiInfo: WifiInfo?): String? {
        if (wifiInfo == null) return null
        return runCatching {
            wifiInfo.javaClass.methods.firstOrNull {
                it.name == "getNetworkSuggestionPackageName" && it.parameterTypes.isEmpty()
            }?.invoke(wifiInfo) as? String
        }.getOrNull()
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        store = TrustedServerStore(this)
        gattClient = GattClient(this)
        wakeAdvertiser = BleWakeAdvertiser(this)
        distressAdvertiser = BleDistressAdvertiser(this)
        scanner = BackgroundBleScanner(this, ::onServerSeen)
        connectivity = getSystemService(ConnectivityManager::class.java)
        diagnostics = ConnectionDiagnostics(this)
        recentAddressCache = RecentBleAddressCache(this)
        runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                networkCallback
            )
        }
        observeGatt()
        scope.launch {
            ShizukuStateMonitor.state.collect { state ->
                if (state == ShizukuState.READY && credentials != null && !connected) {
                    diagnostics.event("shizuku_ready retrying_acceleration=true")
                    launchShizukuAcceleration()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val savedToken = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
            .getString(KEY_TOKEN, null)
        val action = intent?.action ?: if (!savedToken.isNullOrBlank()) ACTION_CONNECT else ACTION_STOP
        if (action == ACTION_STOP) {
            AppPreferences.setBackgroundDiscoveryEnabled(this, false)
            BleDiscoveryRegistrar.stop(this)
            if (!connected) finishService()
            return START_NOT_STICKY
        }
        if (action == ACTION_DISCONNECT) {
            endSession(null)
            return START_NOT_STICKY
        }
        if (action == ACTION_RETRY) {
            val retryToken = intent?.getStringExtra(EXTRA_TOKEN) ?: savedToken
            if (retryToken.isNullOrBlank()) {
                finishService()
                return START_NOT_STICKY
            }
            startForegroundState(ClientConnectionState.Locating("shared phone"))
            scope.launch { beginSession(retryToken, null, null, false) }
            return START_REDELIVER_INTENT
        }
        intent?.takeIf { it.hasExtra(EXTRA_ALERT_NOTIFICATION_ID) }
            ?.getIntExtra(EXTRA_ALERT_NOTIFICATION_ID, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.let { notificationId ->
                getSystemService(NotificationManager::class.java).cancel(notificationId)
            }
        startForegroundState(ClientConnectionState.Locating("shared phone"))
        val restoredToken = savedToken
        val token = intent?.getStringExtra(EXTRA_TOKEN) ?: restoredToken
        if (token.isNullOrBlank()) {
            fail("Could not connect", "The paired phone is no longer available")
            return START_NOT_STICKY
        }
        scope.launch {
            beginSession(
                token = token,
                address = intent?.getStringExtra(EXTRA_ADDRESS),
                name = intent?.getStringExtra(EXTRA_NAME),
                newPair = intent?.getBooleanExtra(EXTRA_NEW_PAIR, false) == true
            )
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Synchronized
    private fun beginSession(token: String, address: String?, name: String?, newPair: Boolean) {
        cleanupSession(keepForeground = true)
        ending = false
        sessionGeneration++
        val generation = sessionGeneration
        targetToken = token
        expectedProfile = if (newPair) null else store.findByToken(token)
        observedRevision = expectedProfile?.advertisedRevision ?: 0
        isNewPair = newPair || expectedProfile == null
        targetName = expectedProfile?.label ?: name ?: "EasierSpot phone"
        diagnostics.begin(targetName)
        getSharedPreferences(SESSION_PREFS, MODE_PRIVATE).edit { putString(KEY_TOKEN, token) }
        startForegroundState(ClientConnectionState.Locating(targetName))
        sessionDeadlineJob?.cancel()
        sessionDeadlineJob = scope.launch {
            delay(if (newPair) ClientConnectionPolicy.APPROVAL_MS else ClientConnectionPolicy.WHOLE_ATTEMPT_MS)
            if (generation == sessionGeneration && !connected) {
                fail(
                    "Connection took too long",
                    "Android did not finish the connection. Nothing was disconnected; tap Retry to try again."
                )
            }
        }
        val profile = expectedProfile
        profile?.let {
            store.nextWakePayload(profile.fingerprint)?.let { payload ->
                diagnostics.event("wake_transmitted")
                wakeAdvertiser.request(payload)
            }
        }
        val candidateAddress = address ?: recentAddressCache.get(token)
        if (profile != null) {
            val suggestionExists = WifiSuggestionInstaller.contains(this, profile.ssid)
            val decision = FastConnectCoordinator.decide(profile, suggestionExists)
            if (!decision.refreshCredentials) {
                val securityType = runCatching {
                    HotspotCredentials.SecurityType.valueOf(profile.securityType)
                }.getOrDefault(HotspotCredentials.SecurityType.WPA2_PSK)
                val runtime = WifiSuggestionInstaller.runtimeCredentials(
                    this,
                    profile.ssid,
                    securityType,
                    profile.isHidden
                )
                if (runtime != null) {
                    fastPath = true
                    credentials = runtime
                    suggestionOwnedEvidence = true
                    evidence = ConnectionEvidence(
                        generation = sessionGeneration,
                        expectedSsid = runtime.ssid,
                        suggestionOwned = true
                    )
                    udpClient = createUdpClient(profile)
                    startForegroundState(ClientConnectionState.StartingHotspot(targetName))
                    candidateAddress?.let { cached ->
                        targetDevice = runCatching {
                            getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(cached)
                        }.getOrNull()
                    }
                    scanner.start()
                    beginTrustedFastPath(profile, runtime, decision.shizukuDelayAfterReadyMs)
                    scheduleFastGattRescue()
                    return
                }
            }
        }
        if (!candidateAddress.isNullOrBlank()) {
            val device = runCatching {
                getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(candidateAddress)
            }.getOrNull()
            if (device != null) {
                // Keep reacquisition running so a rejected rotating address is replaced immediately.
                scanner.start()
                connectGatt(device)
                return
            }
        }
        scanner.start()
        locateJob = scope.launch {
            delay(ClientConnectionPolicy.BLE_REACQUIRE_MS)
            if (generation == sessionGeneration && targetDevice == null) {
                fail("Could not find $targetName", "Move the devices closer and try Connect again")
            }
        }
    }

    private fun onServerSeen(server: DiscoveredServer) {
        if (!server.deviceId.equals(targetToken, ignoreCase = true)) return
        observedRevision = server.networkRevision
        locateJob?.cancel()
        recentAddressCache.record(server.deviceId, server.bluetoothDevice.address)
        targetDevice = server.bluetoothDevice
        if (fastPath) {
            if (server.networkRevision != expectedProfile?.provisionedRevision) {
                fastPath = false
                needsGattFallback = false
                scanner.stop()
                connectGatt(server.bluetoothDevice)
                return
            }
            if (server.flags and BleConstants.FLAG_HOTSPOT_STARTING != 0) {
                fastGattRescueDue = false
                fastGattRescueJob?.cancel()
                diagnostics.event("server_activation_accepted")
                startForegroundState(ClientConnectionState.StartingHotspot(targetName))
            }
            if (server.flags and BleConstants.FLAG_HOTSPOT_ACTIVE != 0) {
                fastGattRescueDue = false
                fastGattRescueJob?.cancel()
                hotspotReady = true
                diagnostics.event("hotspot_ready")
                scanner.stop()
                launchShizukuAcceleration()
            } else if (needsGattFallback || fastGattRescueDue) {
                scanner.stop()
                connectGatt(server.bluetoothDevice)
            }
            return
        }
        scanner.stop()
        connectGatt(server.bluetoothDevice)
    }

    @Synchronized
    private fun connectGatt(device: BluetoothDevice) {
        if (fastGattRescueDue || needsGattFallback) wakeAdvertiser.stop()
        targetDevice = device
        attempt++
        startForegroundState(ClientConnectionState.BleConnecting(targetName, attempt))
        gattClient.connect(device, expectedProfile?.fingerprint)
        schedulePhaseTimeout(ClientConnectionPolicy.GATT_ATTEMPT_MS) {
            if (attempt < ClientConnectionPolicy.GATT_MAX_ATTEMPTS) {
                gattClient.disconnect()
                scope.launch { delay(if (attempt == 1) 1_000L else 3_000L); connectGatt(device) }
            } else fail("Could not reach $targetName", "Bluetooth did not complete the connection")
        }
    }

    private fun beginTrustedFastPath(
        profile: TrustedServerProfile,
        target: HotspotCredentials,
        shizukuDelayMs: Long
    ) {
        wifiJob?.cancel()
        val generation = sessionGeneration
        shizukuDelayAfterReadyMs = shizukuDelayMs
        joinStartedAt = SystemClock.elapsedRealtime()
        wifiJob = scope.launch {
            val manager = HotspotManager(applicationContext)
            manager.enableClientWifi()
            diagnostics.event("fast_path wake_transmitted suggestion_ready=true")
            val preference = AppPreferences.getWifiConnectionMode(applicationContext)
            WifiSuggestionInstaller.ensureAutojoin(
                applicationContext,
                target,
                preference != AppPreferences.WifiConnectionMode.SHIZUKU_FORCE
            )
            if (preference != AppPreferences.WifiConnectionMode.SHIZUKU_FORCE) {
                manager.prepareSuggestionSelection(target.ssid)
            }
            startForegroundState(
                ClientConnectionState.JoiningWifi(target.ssid, WifiJoinMethod.SUGGESTION)
            )
            // Sparse privileged observations cover OEMs that delay callback metadata. They never
            // declare success without the callback Network/LinkProperties and authenticated ACK.
            for (delayMs in longArrayOf(0L, 400L, 1_000L, 2_000L, 5_000L)) {
                if (delayMs > 0) delay(delayMs)
                if (generation != sessionGeneration || connected) return@launch
                val status = manager.verifyConnectedWifi(target.ssid)
                if (status.authoritative && status.connectedToTarget) {
                    privilegedSsidMatch = true
                    updateEvidence { copy(privilegedSsidMatch = true) }
                }
            }
            delay(10_000L)
            if (generation == sessionGeneration && !connected) {
                startForegroundState(
                    ClientConnectionState.JoiningWifi(
                        target.ssid,
                        if (shizukuIssued) WifiJoinMethod.SHIZUKU else WifiJoinMethod.SUGGESTION,
                        takingLonger = true,
                        fallbackActive = shizukuIssued
                    )
                )
            }
            val remaining = ClientConnectionPolicy.WHOLE_ATTEMPT_MS -
                (SystemClock.elapsedRealtime() - joinStartedAt)
            if (remaining > 0L) delay(remaining)
            if (generation != sessionGeneration || connected) return@launch
            val first = verifyConnected(target.ssid)
            delay(2_000L)
            val second = verifyConnected(target.ssid)
            if (first == true || second == true) {
                completeWifiConnection(target.ssid, "terminal_wifi_check")
                requestGattControlFallback()
                delay(10_000L)
            }
            if (generation == sessionGeneration && !connected) {
                fail(
                    "Could not confirm ${target.ssid}",
                    "Wi-Fi and authenticated phone verification did not complete"
                )
            }
        }
    }

    private fun scheduleFastGattRescue() {
        val generation = sessionGeneration
        fastGattRescueJob?.cancel()
        fastGattRescueJob = scope.launch {
            delay(BleConstants.FAST_GATT_RESCUE_MS)
            if (generation != sessionGeneration || hotspotReady || connected) return@launch
            fastGattRescueDue = true
            diagnostics.event("wake_unconfirmed gatt_rescue=true")
            targetDevice?.let { device ->
                scanner.stop()
                connectGatt(device)
            }
        }
    }

    private fun launchShizukuAcceleration() {
        if (shizukuRaceJob?.isActive == true || connected) return
        val target = credentials ?: return
        val generation = sessionGeneration
        shizukuRaceJob = scope.launch {
            delay(shizukuDelayAfterReadyMs)
            if (generation != sessionGeneration || connected) return@launch
            val preference = AppPreferences.getWifiConnectionMode(applicationContext)
            if (preference == AppPreferences.WifiConnectionMode.SUGGESTION) return@launch
            val manager = HotspotManager(applicationContext)
            if (!manager.isShizukuAvailable()) return@launch
            shizukuIssued = true
            val result = manager.connectDeviceToWifi(target)
            diagnostics.event("fast_path shizuku_accepted=${result.accepted}")
            startForegroundState(
                ClientConnectionState.JoiningWifi(
                    target.ssid,
                    WifiJoinMethod.SHIZUKU,
                    fallbackActive = true
                )
            )
        }
    }

    private fun observeGatt() {
        scope.launch {
            gattClient.phase.collect { phase ->
                when (phase) {
                    GattClient.GattPhase.Idle -> Unit
                    GattClient.GattPhase.Connecting -> Unit
                    GattClient.GattPhase.DiscoveringServices,
                    GattClient.GattPhase.ReadingServerIdentity,
                    GattClient.GattPhase.Authenticating -> {
                        phaseTimeoutJob?.cancel()
                        startForegroundState(ClientConnectionState.Authenticating(targetName))
                        schedulePhaseTimeout(ClientConnectionPolicy.GATT_ATTEMPT_MS) { retryGattOrFail() }
                    }
                    GattClient.GattPhase.WaitingForServer -> {
                        phaseTimeoutJob?.cancel()
                        if (isNewPair) {
                            startForegroundState(
                                ClientConnectionState.AwaitingApproval(
                                    targetName,
                                    gattClient.pairingCode.value
                                )
                            )
                            schedulePhaseTimeout(ClientConnectionPolicy.APPROVAL_MS) {
                                fail("Approval timed out", "Approve the pairing request on $targetName and try again")
                            }
                        } else {
                            startForegroundState(ClientConnectionState.StartingHotspot(targetName))
                            scheduleHotspotProgressTimeout()
                        }
                    }
                    GattClient.GattPhase.ReceivingCredentials -> {
                        phaseTimeoutJob?.cancel()
                        startForegroundState(ClientConnectionState.ReceivingCredentials(targetName))
                            schedulePhaseTimeout(ClientConnectionPolicy.HOTSPOT_TOTAL_MS) {
                            fail("Hotspot did not become ready", "$targetName could not provide its Wi-Fi details")
                        }
                    }
                    GattClient.GattPhase.Ready -> phaseTimeoutJob?.cancel()
                    is GattClient.GattPhase.Failed -> retryGattOrFail(phase.message)
                }
            }
        }
        scope.launch {
            gattClient.pairingCode.filterNotNull().collect { code ->
                if (isNewPair && _connectionState.value is ClientConnectionState.AwaitingApproval) {
                    startForegroundState(ClientConnectionState.AwaitingApproval(targetName, code))
                }
            }
        }
        scope.launch {
            gattClient.approvalStatus.filterNotNull().collect { status ->
                when (status) {
                    GattClient.ApprovalStatus.DENIED -> fail("Connection denied", "$targetName rejected the request")
                    GattClient.ApprovalStatus.APPROVED -> {
                        phaseTimeoutJob?.cancel()
                        startForegroundState(ClientConnectionState.StartingHotspot(targetName))
                        scheduleHotspotProgressTimeout()
                    }
                    GattClient.ApprovalStatus.HOTSPOT_STARTING -> {
                        isNewPair = false
                        phaseTimeoutJob?.cancel()
                        startForegroundState(ClientConnectionState.StartingHotspot(targetName))
                        scheduleHotspotProgressTimeout()
                    }
                    GattClient.ApprovalStatus.ACTIVATION_FAILED ->
                        fail("Hotspot could not start", "$targetName reported a hotspot activation failure")
                }
            }
        }
        scope.launch { gattClient.receivedCredentials.filterNotNull().collect(::onCredentials) }
        scope.launch {
            gattClient.connectionState.collect { state ->
                if ((state == GattClient.ConnectionState.ERROR ||
                        state == GattClient.ConnectionState.DISCONNECTED) &&
                    targetDevice != null && credentials != null && connected
                ) scheduleControlReconnect()
            }
        }
    }

    private fun scheduleHotspotProgressTimeout() {
        val generation = sessionGeneration
        phaseTimeoutJob = scope.launch {
            delay(ClientConnectionPolicy.HOTSPOT_SOFT_MS)
            if (generation != sessionGeneration || credentials != null) return@launch
            startForegroundState(ClientConnectionState.StartingHotspot(targetName, takingLonger = true))
            delay(ClientConnectionPolicy.HOTSPOT_TOTAL_MS - ClientConnectionPolicy.HOTSPOT_SOFT_MS)
            if (generation == sessionGeneration && credentials == null) {
                fail("Hotspot did not become ready", "$targetName did not finish hotspot activation")
            }
        }
    }

    private fun retryGattOrFail(detail: String? = null) {
        if (connected) return
        phaseTimeoutJob?.cancel()
        val device = targetDevice ?: return
        if (attempt < ClientConnectionPolicy.GATT_MAX_ATTEMPTS) {
            scope.launch { delay(if (attempt == 1) 1_000L else 3_000L); connectGatt(device) }
        } else fail("Bluetooth connection failed", detail ?: "Could not authenticate $targetName")
    }

    private fun onCredentials(received: HotspotCredentials) {
        phaseTimeoutJob?.cancel()
        credentials = received
        val fingerprint = gattClient.serverDeviceId.value ?: expectedProfile?.fingerprint
        if (fingerprint.isNullOrBlank()) {
            fail("Server identity missing", "The secure Bluetooth handshake did not complete")
            return
        }
        val profile = TrustedServerProfile(
            fingerprint = fingerprint,
            discoveryToken = targetToken.orEmpty(),
            displayName = expectedProfile?.displayName ?: targetName,
            ssid = received.ssid,
            advertisedRevision = observedRevision,
            provisionedRevision = observedRevision,
            securityType = received.securityType.name,
            isHidden = received.isHidden,
            lastSeen = System.currentTimeMillis(),
            serverPublicKey = gattClient.serverPublicKeyEncoded()?.let {
                Base64.encodeToString(it, Base64.NO_WRAP)
            } ?: expectedProfile?.serverPublicKey.orEmpty(),
            wakeCounter = expectedProfile?.wakeCounter ?: 0,
            nickname = expectedProfile?.nickname,
            alertsEnabled = expectedProfile?.alertsEnabled ?: true,
            lastSuccessfulMethod = expectedProfile?.lastSuccessfulMethod,
            suggestionLatencyMs = expectedProfile?.suggestionLatencyMs ?: 0L,
            shizukuLatencyMs = expectedProfile?.shizukuLatencyMs ?: 0L,
            controlCounter = expectedProfile?.controlCounter ?: 0L
        )
        store.remember(profile)
        expectedProfile = profile
        udpClient = createUdpClient(profile)
        scope.launch { BleDiscoveryRegistrar.reconcile(this@BleClientService) }
        gattClient.requestLowPowerConnection()
        if (fastPath && suggestionOwnedEvidence) {
            hotspotReady = true
            fastGattRescueDue = false
            fastGattRescueJob?.cancel()
            diagnostics.event("hotspot_ready source=gatt_credentials")
            if (needsGattFallback) activateGattControlFallback()
            launchShizukuAcceleration()
            startHeartbeat()
            return
        }
        if (connected) {
            controlOverGatt = true
            udpClient?.close()
            udpClient = null
            diagnostics.event("control_fallback=authenticated_gatt")
            startForegroundState(
                ClientConnectionState.Connected(
                    received.ssid,
                    evidence?.let {
                        if (it.internetValidated) InternetStatus.READY else InternetStatus.NOT_CONFIRMED
                    } ?: InternetStatus.NOT_CONFIRMED
                )
            )
            return
        }
        startHeartbeat()
        beginWifiJoin(profile, received)
    }

    private fun beginWifiJoin(profile: TrustedServerProfile, target: HotspotCredentials) {
        wifiJob?.cancel()
        val generation = sessionGeneration
        joinStartedAt = SystemClock.elapsedRealtime()
        evidence = ConnectionEvidence(
            generation = generation,
            expectedSsid = target.ssid,
            suggestionOwned = false
        )
        wifiJob = scope.launch {
            val manager = HotspotManager(applicationContext)
            if (verifyConnected(target.ssid) == true) {
                completeWifiConnection(target.ssid, "initial_status_check")
                return@launch
            }
            manager.enableClientWifi()
            val preference = AppPreferences.getWifiConnectionMode(applicationContext)
            val preferSuggestion = preference == AppPreferences.WifiConnectionMode.SUGGESTION ||
                (preference == AppPreferences.WifiConnectionMode.AUTO &&
                    profile.lastSuccessfulMethod == WifiJoinMethod.SUGGESTION.name)
            var method = if (!preferSuggestion && manager.isShizukuAvailable()) {
                WifiJoinMethod.SHIZUKU
            } else WifiJoinMethod.SUGGESTION
            var fallbackActive = false

            fun installSuggestion(): Boolean {
                val result = WifiSuggestionInstaller.installDetailed(
                    applicationContext,
                    target,
                    autojoinEnabled = preference != AppPreferences.WifiConnectionMode.SHIZUKU_FORCE
                )
                if (result.accepted) {
                    suggestionOwnedEvidence = true
                    updateEvidence { copy(suggestionOwned = true) }
                }
                if (!result.accepted) {
                    diagnostics.event("suggestion_rejected type=${result.javaClass.simpleName}")
                }
                return result.accepted
            }

            // The app-owned suggestion is the durable network record even when Shizuku wins.
            if (!installSuggestion()) {
                val approval = WifiSuggestionInstaller.approvalStatus(applicationContext)
                fail(
                    if (approval == WifiSuggestionInstaller.ApprovalStatus.REJECTED) {
                        "Allow EasierSpot Wi-Fi control"
                    } else {
                        "Could not prepare Wi-Fi"
                    },
                    if (approval == WifiSuggestionInstaller.ApprovalStatus.REJECTED) {
                        "Open Wi-Fi settings and allow EasierSpot to manage suggested networks"
                    } else {
                        "Android rejected the network suggestion; open Wi-Fi settings to repair it"
                    },
                    ClientRecoveryAction.WIFI_SETTINGS
                )
                return@launch
            }

            if (!manager.isShizukuAvailable()) {
                when (WifiSuggestionInstaller.approvalStatus(applicationContext)) {
                    WifiSuggestionInstaller.ApprovalStatus.REJECTED -> {
                        fail(
                            "Allow EasierSpot Wi-Fi control",
                            "Android has blocked EasierSpot network suggestions. Open Wi-Fi settings to allow them.",
                            ClientRecoveryAction.WIFI_SETTINGS
                        )
                        return@launch
                    }
                    WifiSuggestionInstaller.ApprovalStatus.PENDING -> diagnostics.event(
                        "suggestion_approval=pending"
                    )
                    else -> Unit
                }
            }

            if (method == WifiJoinMethod.SHIZUKU) {
                val accepted = manager.connectDeviceToWifi(target).accepted
                diagnostics.event("wifi_method=SHIZUKU command_accepted=$accepted")
                if (ClientConnectionPolicy.shouldStartSuggestionFallback(preference, accepted)) {
                    method = WifiJoinMethod.SUGGESTION
                    fallbackActive = true
                    diagnostics.event("wifi_fallback=SUGGESTION")
                    manager.prepareSuggestionSelection(target.ssid)
                } else if (!accepted) {
                    // Shell status is advisory. Several OEM WifiService implementations report
                    // failure after accepting the asynchronous network switch. Keep the
                    // Shizuku-only preference, but let callbacks and authenticated control prove
                    // success before deciding anything terminal.
                    diagnostics.event("wifi_command_inconclusive awaiting_connection_evidence=true")
                }
            } else {
                diagnostics.event("wifi_method=SUGGESTION")
                if (manager.isShizukuAvailable()) manager.prepareSuggestionSelection(target.ssid)
            }
            startForegroundState(ClientConnectionState.JoiningWifi(target.ssid, method, fallbackActive = fallbackActive))

            repeat(ClientConnectionPolicy.WIFI_CHECKS) { check ->
                if (generation != sessionGeneration) return@launch
                if (verifyConnected(target.ssid) == true) {
                    store.recordSuccessfulMethod(profile.fingerprint, method.name)
                    completeWifiConnection(target.ssid, "wifi_status_check")
                    return@launch
                }
                when (check) {
                    ClientConnectionPolicy.WIFI_NUDGE_CHECK -> {
                        startForegroundState(
                            ClientConnectionState.JoiningWifi(target.ssid, method, takingLonger = true, fallbackActive)
                        )
                        if (manager.isShizukuAvailable()) manager.reconnectClientWifi()
                    }
                    ClientConnectionPolicy.WIFI_FALLBACK_CHECK -> if (preference == AppPreferences.WifiConnectionMode.AUTO &&
                        method == WifiJoinMethod.SHIZUKU && !fallbackActive
                    ) {
                        fallbackActive = installSuggestion()
                        if (fallbackActive) manager.prepareSuggestionSelection(target.ssid)
                        startForegroundState(
                            ClientConnectionState.JoiningWifi(target.ssid, method, takingLonger = true, fallbackActive)
                        )
                    }
                }
                delay(ClientConnectionPolicy.WIFI_POLL_MS)
            }
            delay(2_000L)
            val first = verifyConnected(target.ssid)
            delay(2_000L)
            val second = verifyConnected(target.ssid)
            if (first == true || second == true) completeWifiConnection(target.ssid, "final_status_checks") else {
                fail("Could not join ${target.ssid}", "Both automatic connection methods finished without a confirmed Wi-Fi connection")
            }
        }
    }

    private fun verifyConnected(ssid: String): Boolean? {
        val privileged = HotspotManager(applicationContext).verifyConnectedWifi(ssid)
        if (privileged.authoritative) return privileged.connectedToTarget
        @Suppress("DEPRECATION")
        val current = getSystemService(WifiManager::class.java).connectionInfo.ssid
            ?.removeSurrounding("\"")
        return if (current.isNullOrBlank() || current == WifiManager.UNKNOWN_SSID) null else current == ssid
    }

    private fun createUdpClient(profile: TrustedServerProfile): UdpControlClient? = runCatching {
        val serverPublic = BleSessionCrypto.decodePeerPublicKey(
            Base64.decode(profile.serverPublicKey, Base64.NO_WRAP)
        )
        val clientKeys = BleSessionCrypto.clientKeyPair(this)
        val route = BleSessionCrypto.fingerprint(clientKeys.public).take(4).toInt(16)
        UdpControlClient(
            key = BleSessionCrypto.controlKey(clientKeys.private, serverPublic),
            route = route,
            nextCounter = {
                val updated = store.nextControlProfile(profile.fingerprint)
                    ?: error("Trusted server was removed")
                updated.controlCounter
            }
        )
    }.onFailure { LogUtils.w(TAG, "Could not prepare authenticated Wi-Fi control", it) }
        .getOrNull()

    @Synchronized
    private fun updateEvidence(transform: ConnectionEvidence.() -> ConnectionEvidence) {
        val current = evidence ?: credentials?.let { target ->
            ConnectionEvidence(
                generation = sessionGeneration,
                expectedSsid = target.ssid,
                suggestionOwned = suggestionOwnedEvidence,
                privilegedSsidMatch = privilegedSsidMatch
            )
        } ?: return
        if (current.generation != sessionGeneration) return
        evidence = current.transform().copy(
            suggestionOwned = suggestionOwnedEvidence,
            privilegedSsidMatch = privilegedSsidMatch
        )
        evaluateEvidence()
    }

    @Synchronized
    private fun evaluateEvidence() {
        val current = evidence ?: return
        when (val verdict = ConnectionEvidenceReducer.reduce(current)) {
            ConnectionVerdict.WaitingForWifi -> Unit
            is ConnectionVerdict.WaitingForPhone -> startUdpHello(
                current.generation,
                verdict.network,
                verdict.gateway
            )
            is ConnectionVerdict.Connected -> completeVerifiedConnection(
                current.expectedSsid,
                verdict.internet
            )
        }
    }

    private fun startUdpHello(generation: Long, network: Network, gateway: java.net.InetAddress) {
        if (udpHelloJob?.isActive == true || evidence?.authenticatedAck == true) return
        val client = udpClient ?: expectedProfile?.let(::createUdpClient)?.also { udpClient = it }
        if (client == null) {
            requestGattControlFallback()
            return
        }
        udpHelloJob = scope.launch {
            diagnostics.event("udp_hello gateway=${gateway.hostAddress}")
            val acknowledged = client.hello(network, gateway)
            if (generation != sessionGeneration) return@launch
            if (acknowledged) {
                diagnostics.event("authenticated_udp_ack")
                updateEvidence { copy(authenticatedAck = true) }
                gattClient.disconnect()
            } else {
                diagnostics.event("udp_ack_unavailable gatt_fallback=true")
                requestGattControlFallback()
            }
        }
    }

    private fun requestGattControlFallback() {
        needsGattFallback = true
        if (gattClient.connectionState.value == GattClient.ConnectionState.CONNECTED &&
            gattClient.serverDeviceId.value == expectedProfile?.fingerprint && credentials != null
        ) {
            activateGattControlFallback()
            return
        }
        targetDevice?.let { device ->
            scanner.stop()
            connectGatt(device)
        } ?: scanner.start()
    }

    private fun activateGattControlFallback() {
        controlOverGatt = true
        udpClient?.close()
        udpClient = null
        diagnostics.event("authenticated_control=gatt_fallback")
        updateEvidence { copy(authenticatedGattFallback = true) }
    }

    @Synchronized
    private fun completeWifiConnection(ssid: String, verificationSource: String) {
        diagnostics.event("wifi_evidence=$verificationSource")
        privilegedSsidMatch = true
        updateEvidence { copy(privilegedSsidMatch = true) }
    }

    @Synchronized
    private fun completeVerifiedConnection(ssid: String, internet: InternetStatus) {
        if (!connected) {
        connected = true
        sessionDeadlineJob?.cancel()
            wifiJob?.cancel()
            shizukuRaceJob?.cancel()
            phaseTimeoutJob?.cancel()
            locateJob?.cancel()
            val method = if (shizukuIssued) WifiJoinMethod.SHIZUKU else WifiJoinMethod.SUGGESTION
            expectedProfile?.let { profile ->
                store.recordMethodSuccess(
                    profile.fingerprint,
                    method.name,
                    SystemClock.elapsedRealtime() - joinStartedAt
                )
            }
            diagnostics.finish(
                "connected verification=${if (controlOverGatt) "authenticated_gatt" else "authenticated_udp"} method=$method"
            )
            if (!controlOverGatt) gattClient.disconnect()
            startHeartbeat()
        }
        startForegroundState(ClientConnectionState.Connected(ssid, internet))
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (true) {
                delay(
                    if (connected && !controlOverGatt) BleConstants.UDP_HEARTBEAT_INTERVAL_MS
                    else BleConstants.HEARTBEAT_INTERVAL_MS
                )
                if (connected) {
                    if (controlOverGatt) {
                        gattClient.sendHeartbeat()
                    } else if (udpClient?.heartbeat() == true) {
                        udpMisses = 0
                    } else if (++udpMisses >= 3) {
                        requestGattControlFallback()
                    }
                } else {
                    gattClient.sendHeartbeat()
                }
            }
        }
    }

    private fun scheduleControlReconnect() {
        if (reconnectJob?.isActive == true) return
        val device = targetDevice ?: return
        reconnectJob = scope.launch {
            repeat(3) { index ->
                startForegroundState(ClientConnectionState.Recovering(targetName, index + 1))
                delay(if (index == 0) 2_000L else 5_000L)
                gattClient.connect(device, expectedProfile?.fingerprint)
                delay(8_000L)
                if (gattClient.connectionState.value == GattClient.ConnectionState.CONNECTED) return@launch
            }
            credentials?.ssid?.let {
                startForegroundState(ClientConnectionState.Connected(it, controlAvailable = false))
            }
        }
    }

    private fun schedulePhaseTimeout(delayMs: Long, action: () -> Unit) {
        val generation = sessionGeneration
        phaseTimeoutJob?.cancel()
        phaseTimeoutJob = scope.launch {
            delay(delayMs)
            if (generation == sessionGeneration) action()
        }
    }

    private fun fail(
        title: String,
        detail: String,
        recovery: ClientRecoveryAction = ClientRecoveryAction.RETRY
    ) {
        if (connected) return
        diagnostics.finish("failed $title: $detail")
        startForegroundState(ClientConnectionState.Failed(title, detail, recovery))
        scope.launch {
            delay(30_000L)
            if (_connectionState.value is ClientConnectionState.Failed) endSession(null)
        }
    }

    @Synchronized
    private fun endSession(reason: String?) {
        if (ending) return
        ending = true
        if (reason != null) startForegroundState(ClientConnectionState.Failed(reason, "EasierSpot returned to nearby discovery"))
        gattClient.sendGoodbye()
        gattClient.distressPayload()?.let(distressAdvertiser::scream)
        sessionGeneration++
        val udp = udpClient
        if (udp != null) {
            scope.launch {
                udp.goodbye()
                withContext(Dispatchers.Main) { finishEndedSession(reason) }
            }
        } else finishEndedSession(reason)
    }

    private fun finishEndedSession(reason: String?) {
        cleanupSession(keepForeground = reason != null)
        getSharedPreferences(SESSION_PREFS, MODE_PRIVATE).edit { remove(KEY_TOKEN) }
        val app = applicationContext
        discoveryScope.launch { BleDiscoveryRegistrar.reconcile(app) }
        if (reason != null) scope.launch { delay(8_000L); finishService() } else finishService()
    }

    private fun cleanupSession(keepForeground: Boolean) {
        locateJob?.cancel(); phaseTimeoutJob?.cancel(); sessionDeadlineJob?.cancel(); wifiJob?.cancel()
        heartbeatJob?.cancel(); reconnectJob?.cancel()
        udpHelloJob?.cancel(); shizukuRaceJob?.cancel(); fastGattRescueJob?.cancel()
        scanner.stop(); wakeAdvertiser.stop(); distressAdvertiser.stop(); gattClient.disconnect()
        udpClient?.close(); udpClient = null
        linkPropertiesByNetwork.clear()
        targetDevice = null; credentials = null; connected = false; attempt = 0; targetNetwork = null
        observedRevision = 0
        fastPath = false; needsGattFallback = false; hotspotReady = false; shizukuIssued = false
        fastGattRescueDue = false
        udpMisses = 0
        controlOverGatt = false
        suggestionOwnedEvidence = false; privilegedSsidMatch = false; evidence = null
        if (!keepForeground) _connectionState.value = ClientConnectionState.Idle
    }

    private fun startForegroundState(state: ClientConnectionState) {
        _connectionState.value = state
        val (title, text) = state.titleAndText()
        diagnostics.event("phase=${state.javaClass.simpleName} $title")
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, if (state is ClientConnectionState.AwaitingApproval) ClientActivity::class.java else MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val actionIntent = when (state) {
            is ClientConnectionState.Failed -> when (state.recovery) {
                ClientRecoveryAction.RETRY -> PendingIntent.getForegroundService(
                    this,
                    1,
                    Intent(this, BleClientService::class.java).apply {
                        action = ACTION_RETRY
                        putExtra(EXTRA_TOKEN, targetToken)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                ClientRecoveryAction.WIFI_SETTINGS -> PendingIntent.getActivity(
                    this,
                    1,
                    Intent(Settings.ACTION_WIFI_SETTINGS),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                ClientRecoveryAction.SHIZUKU,
                ClientRecoveryAction.PERMISSIONS -> open
            }
            else -> PendingIntent.getService(
                this,
                1,
                Intent(this, BleClientService::class.java).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val actionLabel = when (state) {
            is ClientConnectionState.Failed -> when (state.recovery) {
                ClientRecoveryAction.RETRY -> "Retry"
                ClientRecoveryAction.WIFI_SETTINGS -> "Open Wi-Fi settings"
                ClientRecoveryAction.SHIZUKU -> "Fix Shizuku"
                ClientRecoveryAction.PERMISSIONS -> "Fix permissions"
            }
            else -> "Disconnect"
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(state !is ClientConnectionState.Failed)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, actionLabel, actionIntent)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Active EasierSpot connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress while connecting to or using a shared hotspot"
            }
        )
    }

    private fun finishService() {
        if (AppPreferences.isBackgroundDiscoveryEnabled(this)) {
            val app = applicationContext
            discoveryScope.launch { BleDiscoveryRegistrar.reconcile(app) }
        }
        _connectionState.value = ClientConnectionState.Idle
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cleanupSession(keepForeground = false)
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        scope.cancel()
        super.onDestroy()
    }
}
