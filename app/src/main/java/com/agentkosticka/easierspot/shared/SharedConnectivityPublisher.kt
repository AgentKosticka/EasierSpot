package com.agentkosticka.easierspot.shared

import android.content.Context
import com.agentkosticka.easierspot.service.ClientConnectionState
import java.lang.ref.WeakReference

internal interface SharedConnectivityPublishHost {
    fun requestRepublish()
    fun onClientConnectionStateChanged(state: ClientConnectionState)
}

object SharedConnectivityPublisher {
    @Volatile private var host = WeakReference<SharedConnectivityPublishHost>(null)

    internal fun attach(next: SharedConnectivityPublishHost) {
        host = WeakReference(next)
    }

    internal fun detach(current: SharedConnectivityPublishHost) {
        if (host.get() === current) host.clear()
    }

    fun reconcile(context: Context) {
        if (SharedConnectivityActivation.capability().isActive) host.get()?.requestRepublish()
    }

    fun onPresenceChanged(context: Context) {
        if (SharedConnectivityActivation.capability().isActive) host.get()?.requestRepublish()
    }

    fun onConnectionStateChanged(state: ClientConnectionState) {
        host.get()?.onClientConnectionStateChanged(state)
    }
}
