package com.agentkosticka.easierspot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

class NearbyConnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(EXTRA_TOKEN)?.takeIf(String::isNotBlank) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, Int.MIN_VALUE)
        if (notificationId != Int.MIN_VALUE) {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }
        TrustedConnectLauncher.connect(context, token, ConnectTrigger.NEARBY_NOTIFICATION)
    }

    companion object {
        const val EXTRA_TOKEN = "server_token"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
