package com.techaus.afamfresh.repository

import android.util.Log
import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.AppNotification
import com.techaus.afamfresh.models.BaseResponse
import com.techaus.afamfresh.models.NotificationsResponse
import com.techaus.afamfresh.models.UnreadCountResponse
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Wraps `notifications.php`. Both endpoints already existed in ApiService but
 * nothing called them — there was no notifications UI at all.
 */
class NotificationRepository(private val apiService: ApiService) {

    private companion object {
        const val TAG = "NotificationRepo"
    }

    fun getNotifications(callback: (List<AppNotification>?) -> Unit) {
        apiService.getNotifications().enqueue(object : Callback<NotificationsResponse> {
            override fun onResponse(
                call: Call<NotificationsResponse>,
                response: Response<NotificationsResponse>
            ) {
                if (response.isSuccessful && response.body()?.success == true) {
                    callback(response.body()?.notifications ?: emptyList())
                } else {
                    Log.e(TAG, "getNotifications HTTP ${response.code()}")
                    callback(null)
                }
            }

            override fun onFailure(call: Call<NotificationsResponse>, t: Throwable) {
                Log.e(TAG, "getNotifications network failure: ${t.message}", t)
                callback(null)
            }
        })
    }

    /**
     * `user_notifications.id` is int(11), and the endpoint reads $_POST['id'].
     * This used to send a String to a parameter named `notification_id` against
     * action `mark_read` — wrong action, wrong field name, so nothing was ever
     * marked read.
     */
    fun markRead(notificationId: Int, callback: (Boolean) -> Unit) {
        apiService.markNotificationRead(notificationId = notificationId)
            .enqueue(object : Callback<BaseResponse> {
                override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                    callback(response.isSuccessful && response.body()?.success == true)
                }

                override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                    Log.e(TAG, "markRead network failure: ${t.message}", t)
                    callback(false)
                }
            })
    }

    fun markAllRead(callback: (Boolean) -> Unit) {
        apiService.markAllNotificationsRead().enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                callback(response.isSuccessful && response.body()?.success == true)
            }

            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                Log.e(TAG, "markAllRead network failure: ${t.message}", t)
                callback(false)
            }
        })
    }

    /** Drives the unread badge. Reports 0 on any failure rather than blocking. */
    fun getUnreadCount(callback: (Int) -> Unit) {
        apiService.getUnreadNotificationCount().enqueue(object : Callback<UnreadCountResponse> {
            override fun onResponse(
                call: Call<UnreadCountResponse>,
                response: Response<UnreadCountResponse>
            ) {
                val body = response.body()
                callback(if (response.isSuccessful && body?.success == true) body.count else 0)
            }

            override fun onFailure(call: Call<UnreadCountResponse>, t: Throwable) {
                Log.e(TAG, "getUnreadCount network failure: ${t.message}", t)
                callback(0)
            }
        })
    }
}
