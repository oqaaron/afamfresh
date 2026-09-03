package com.techaus.afamfresh.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.techaus.afamfresh.MainActivity
import com.techaus.afamfresh.R
import com.techaus.afamfresh.utils.DeliveryPushBus
import com.techaus.afamfresh.utils.FirebaseTokenManager
import kotlin.random.Random

class AfamFreshMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_URGENT = "afamfresh_urgent_alerts"
        const val CHANNEL_DEFAULT = "afamfresh_orders"

        const val EXTRA_ORDER_ID = "notification_order_id"
        const val EXTRA_SOURCE = "notification_source"
        private const val TAG = "FCMService"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token refreshed")
        FirebaseTokenManager.initialize(applicationContext)
        FirebaseTokenManager.registerTokenWithBackend()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        if (!FirebaseTokenManager.areNotificationsEnabled()) {
            Log.d(TAG, "Notification suppressed — disabled in Settings")
            return
        }

        val data = message.data
        val orderId = data["order_id"]
        val source = data["source"]

        if (data["type"] == "delivery_status") {
            val id = orderId?.toIntOrNull()
            if (id != null) {
                DeliveryPushBus.publish(id, source ?: "order")
            }
        }

        val title: String = data["title"]
            ?: message.notification?.title
            ?: "AfamFresh"

        val body: String = data["body"]
            ?: message.notification?.body
            ?: data["message"]
            ?: return

        val isUrgentExplicit = data["is_urgent"].equals("true", ignoreCase = true)
        val isUrgentKeyword = title.contains("Arrived", ignoreCase = true) ||
                title.contains("Ready", ignoreCase = true) ||
                body.contains("collection code", ignoreCase = true) ||
                body.contains("doorstep", ignoreCase = true)

        val isUrgent = isUrgentExplicit || isUrgentKeyword

        showNotification(title, body, orderId, source, isUrgent)
    }

    private fun showNotification(
        title: String,
        body: String,
        orderId: String?,
        source: String?,
        isUrgent: Boolean
    ) {
        createChannels()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            orderId?.let { putExtra(EXTRA_ORDER_ID, it) }
            source?.let { putExtra(EXTRA_SOURCE, it) }
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            orderId?.hashCode() ?: Random.nextInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = if (isUrgent) CHANNEL_URGENT else CHANNEL_DEFAULT
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val vibratePattern = longArrayOf(0, 350, 200, 350)

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.logo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setSound(soundUri)

        if (isUrgent) {
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVibrate(vibratePattern)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
        } else {
            builder.setPriority(NotificationCompat.PRIORITY_DEFAULT)
        }

        try {
            NotificationManagerCompat.from(this)
                .notify(orderId?.toIntOrNull() ?: Random.nextInt(), builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted, skipping: ${e.message}")
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()

        if (manager.getNotificationChannel(CHANNEL_URGENT) == null) {
            val urgentChannel = NotificationChannel(
                CHANNEL_URGENT,
                "Arrival & Critical Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows live pop-up alerts for arrivals, dispatches, and pickup codes"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 350, 200, 350)
                enableLights(true)
                setSound(soundUri, audioAttributes)
            }
            manager.createNotificationChannel(urgentChannel)
        }

        if (manager.getNotificationChannel(CHANNEL_DEFAULT) == null) {
            val defaultChannel = NotificationChannel(
                CHANNEL_DEFAULT,
                "Order updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Order status changes and Bulk deals"
                enableVibration(true)
                setSound(soundUri, audioAttributes)
            }
            manager.createNotificationChannel(defaultChannel)
        }
    }
}