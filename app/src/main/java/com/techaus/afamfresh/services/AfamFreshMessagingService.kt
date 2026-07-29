package com.techaus.afamfresh.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.techaus.afamfresh.MainActivity
import com.techaus.afamfresh.R
import com.techaus.afamfresh.utils.FirebaseTokenManager
import kotlin.random.Random

/**
 * Receives push messages and posts them as system notifications.
 *
 * Previously the app fetched an FCM token but had nothing registered to
 * receive messages, so pushes with a `data` payload were silently dropped.
 *
 * Expected payload from the backend (all optional except title/body):
 *
 *   {
 *     "title":    "Order confirmed",
 *     "body":     "Your order #1234 is being prepared",
 *     "order_id": "1234"        // deep-links to that order when present
 *   }
 *
 * Send these as a `data` payload rather than a `notification` payload.
 * A `notification` payload is handled by the system directly while the app is
 * backgrounded, which means [onMessageReceived] never runs and the user's
 * notification preference below cannot be honoured.
 */
class AfamFreshMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "afamfresh_orders"
        const val EXTRA_ORDER_ID = "notification_order_id"
        private const val TAG = "FCMService"
    }

    /**
     * Fired when FCM issues a new token — on install, app data clear, or
     * periodic rotation. Without this the backend would keep a stale token and
     * silently stop reaching the device.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token refreshed")
        FirebaseTokenManager.initialize(applicationContext)
        // Only succeeds if there is a session; harmless otherwise, and
        // MainActivity re-registers after the next login.
        FirebaseTokenManager.registerTokenWithBackend()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        // Respect the Settings toggle. The server has no unregister endpoint
        // yet, so suppressing here is what makes "off" actually mean off.
        if (!FirebaseTokenManager.areNotificationsEnabled()) {
            Log.d(TAG, "Notification suppressed — disabled in Settings")
            return
        }

        val data = message.data
        val title = data["title"] ?: message.notification?.title ?: "AfamFresh"
        val body = data["body"] ?: message.notification?.body ?: return
        val orderId = data["order_id"]

        showNotification(title, body, orderId)
    }

    private fun showNotification(title: String, body: String, orderId: String?) {
        createChannel()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            orderId?.let { putExtra(EXTRA_ORDER_ID, it) }
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            // Unique per notification, otherwise a second notification would
            // reuse the first one's extras and open the wrong order.
            orderId?.hashCode() ?: Random.nextInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.logo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(this)
                .notify(Random.nextInt(), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted on Android 13+. Nothing to do but
            // skip it — the in-app notifications screen still shows the item.
            Log.w(TAG, "POST_NOTIFICATIONS not granted, skipping: ${e.message}")
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Order updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Order status changes and surplus deals"
            }
        )
    }
}
