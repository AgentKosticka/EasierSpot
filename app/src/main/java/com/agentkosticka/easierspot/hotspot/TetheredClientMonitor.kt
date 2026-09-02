package com.agentkosticka.easierspot.hotspot

import android.content.Context
import com.agentkosticka.easierspot.util.LogUtils
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.util.concurrent.Executor

/** Best-effort system view of clients currently attached to Wi-Fi tethering. */
data class TetheredWifiClient(
    val stableId: String,
    val hostname: String?,
    val macAddress: String?,
    val addresses: Set<String>
)

/**
 * Bridges the system-only TetheringEventCallback through reflection on Android 12+.
 *
 * Android explicitly documents the client list as best effort, so callers must treat a failed
 * registration / not-yet-delivered initial callback as unknown rather than as an empty client list.
 */
class TetheredClientMonitor(
    context: Context,
    private val onClientsChanged: (List<TetheredWifiClient>) -> Unit
) {
    companion object {
        private const val TAG = "TetheredClientMonitor"
        private const val TETHERING_WIFI = 0
    }

    private val appContext = context.applicationContext
    private var manager: Any? = null
    private var callback: Any? = null

    @Synchronized
    fun start(): Boolean {
        if (callback != null) return true
        return runCatching {
            val managerClass = Class.forName("android.net.TetheringManager")
            val callbackClass = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
            val service = appContext.getSystemService("tethering")
                ?: error("TetheringManager service unavailable")
            val proxy = Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { instance, method, args ->
                when (method.name) {
                    "onClientsChanged" -> {
                        val clients = (args?.getOrNull(0) as? Collection<*>)
                            .orEmpty()
                            .mapNotNull(::parseWifiClient)
                            .distinctBy(TetheredWifiClient::stableId)
                        onClientsChanged(clients)
                        null
                    }
                    "equals" -> instance === args?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(instance)
                    "toString" -> "EasierSpotTetheringEventCallback"
                    else -> null
                }
            }
            val register = managerClass.getMethod(
                "registerTetheringEventCallback",
                Executor::class.java,
                callbackClass
            )
            register.invoke(service, Executor(Runnable::run), proxy)
            manager = service
            callback = proxy
        }.onFailure {
            LogUtils.w(TAG, "System tethered-client monitoring unavailable", it)
        }.isSuccess
    }

    @Synchronized
    fun stop() {
        val service = manager ?: return
        val proxy = callback ?: return
        runCatching {
            val callbackClass = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
            service.javaClass.getMethod("unregisterTetheringEventCallback", callbackClass)
                .invoke(service, proxy)
        }.onFailure {
            LogUtils.d(TAG, "Could not unregister tethered-client callback: ${it.javaClass.simpleName}")
        }
        callback = null
        manager = null
    }

    private fun parseWifiClient(client: Any?): TetheredWifiClient? {
        client ?: return null
        val tetheringType = invokeNoArgs(client, "getTetheringType") as? Int ?: return null
        if (tetheringType != TETHERING_WIFI) return null

        val mac = invokeNoArgs(client, "getMacAddress")?.toString()?.takeIf(String::isNotBlank)
        val addressInfos = invokeNoArgs(client, "getAddresses") as? Collection<*> ?: emptyList<Any>()
        val addresses = linkedSetOf<String>()
        var hostname: String? = null
        addressInfos.forEach { info ->
            info ?: return@forEach
            if (hostname == null) {
                hostname = (invokeNoArgs(info, "getHostname") as? String)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            }
            val linkAddress = invokeNoArgs(info, "getAddress") ?: return@forEach
            val address = invokeNoArgs(linkAddress, "getAddress") as? InetAddress ?: return@forEach
            address.hostAddress?.takeIf(String::isNotBlank)?.let(addresses::add)
        }
        val stableId = mac ?: addresses.sorted().joinToString("|").takeIf(String::isNotBlank)
            ?: return null
        return TetheredWifiClient(stableId, hostname, mac, addresses)
    }

    private fun invokeNoArgs(target: Any, methodName: String): Any? = runCatching {
        target.javaClass.getMethod(methodName).invoke(target)
    }.getOrNull()
}
