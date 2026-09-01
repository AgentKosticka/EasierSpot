package com.agentkosticka.easierspot.shared

import android.content.Context
import android.os.Build
import com.agentkosticka.easierspot.service.ClientConnectionState

interface SharedConnectivityBackend {
    fun reconcile(context: Context): SharedConnectivityCapability
    fun onPresenceChanged(context: Context)
    fun onConnectionStateChanged(state: ClientConnectionState)
    fun capability(): SharedConnectivityCapability
}

private object NoopSharedConnectivityBackend : SharedConnectivityBackend {
    override fun reconcile(context: Context) = SharedConnectivityCapability.ApiUnavailable
    override fun onPresenceChanged(context: Context) = Unit
    override fun onConnectionStateChanged(state: ClientConnectionState) = Unit
    override fun capability() = SharedConnectivityCapability.ApiUnavailable
}

private object PlatformSharedConnectivityBackend : SharedConnectivityBackend {
    override fun reconcile(context: Context): SharedConnectivityCapability {
        val capability = SharedConnectivityActivation.reconcile(context)
        if (capability.isActive) SharedConnectivityPublisher.reconcile(context)
        return capability
    }

    override fun onPresenceChanged(context: Context) = SharedConnectivityPublisher.onPresenceChanged(context)
    override fun onConnectionStateChanged(state: ClientConnectionState) =
        SharedConnectivityPublisher.onConnectionStateChanged(state)
    override fun capability(): SharedConnectivityCapability = SharedConnectivityActivation.capability()
}

object SharedConnectivityBackends {
    val current: SharedConnectivityBackend =
        if (Build.VERSION.SDK_INT >= 34) PlatformSharedConnectivityBackend
        else NoopSharedConnectivityBackend
}
