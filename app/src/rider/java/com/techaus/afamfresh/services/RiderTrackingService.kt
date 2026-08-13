package com.techaus.afamfresh.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.techaus.afamfresh.MainActivity
import com.techaus.afamfresh.R
import com.techaus.afamfresh.api.ApiClient
import com.techaus.afamfresh.repository.RiderRepository

/**
 * Keeps posting the rider's position while a delivery is in progress, even if
 * the app is backgrounded or the screen locks.
 *
 * [RiderLocationUpdates] (a Compose `DisposableEffect`) covers the "on duty,
 * app open" case, but stops the instant its screen leaves composition — which
 * is exactly when a rider is most likely to lock their phone and drive. This
 * service is what keeps the customer's tracking map from going stale for that
 * stretch.
 *
 * Started and stopped from [com.techaus.afamfresh.ui.screens.rider.RiderDeliveryDetailScreen]
 * as the delivery's status changes, not tied to that screen's own lifecycle:
 * once started, this keeps running after the rider navigates away, and only
 * [stop] (called on "delivered") or going off duty ends it.
 *
 * No offline buffering: a dropped fix during a signal gap is simply skipped,
 * same as [RiderLocationUpdates]. The server already tolerates gaps in the
 * trail — customers see the last known position and a "Reconnecting…" state,
 * not an error.
 */
class RiderTrackingService : Service() {

    companion object {
        private const val CHANNEL_ID = "afamfresh_rider_tracking"
        private const val NOTIF_ID = 5001
        private const val EXTRA_STATUS = "status"

        /** [status] is the delivery's current stage — governs update cadence. */
        fun start(context: Context, status: String) {
            val intent = Intent(context, RiderTrackingService::class.java)
                .putExtra(EXTRA_STATUS, status)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RiderTrackingService::class.java))
        }
    }

    private val repository by lazy { RiderRepository(ApiClient.apiService, applicationContext) }
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var callback: LocationCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        // "on_way" means the rider is actively moving toward the customer, so
        // it gets the tighter cadence; "picked_up" (still at the vendor, or
        // just leaving) does not need it yet.
        val status = intent?.getStringExtra(EXTRA_STATUS) ?: "picked_up"
        restartLocationUpdates(status)
        return START_STICKY
    }

    private fun restartLocationUpdates(status: String) {
        callback?.let { fusedClient.removeLocationUpdates(it) }

        val request = if (status == "on_way") {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
                .setMinUpdateDistanceMeters(20f)
                .build()
        } else {
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L)
                .setMinUpdateDistanceMeters(50f)
                .build()
        }

        val newCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let {
                    repository.postLocation(
                        it.latitude, it.longitude,
                        accuracy = if (it.hasAccuracy()) it.accuracy else null,
                        speed = if (it.hasSpeed()) it.speed else null,
                        heading = if (it.hasBearing()) it.bearing else null
                    )
                }
            }
        }
        callback = newCallback

        try {
            fusedClient.requestLocationUpdates(request, newCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // Permission revoked while a delivery was in progress. Nothing to
            // post; the service stays alive so it resumes if it's re-granted.
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        callback?.let { fusedClient.removeLocationUpdates(it) }
    }

    private fun buildNotification(): Notification {
        createChannel()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.logo)
            .setContentTitle("Delivering an order")
            .setContentText("Sharing your location with the customer")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Delivery tracking", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while you have an active delivery"
            }
        )
    }
}