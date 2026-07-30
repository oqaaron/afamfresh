package com.techaus.afamfresh.viewmodel

import androidx.lifecycle.ViewModel
import com.techaus.afamfresh.models.AppNotification
import com.techaus.afamfresh.repository.NotificationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NotificationViewModel(
    private val notificationRepository: NotificationRepository
) : ViewModel() {

    private val _notifications = MutableStateFlow<List<AppNotification>>(emptyList())
    val notifications: StateFlow<List<AppNotification>> = _notifications.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Drives the badge on the home screen. */
    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    /**
     * Deliberately NOT loaded from an init block. This endpoint is
     * authenticated, so firing it at construction time would 401 for a user
     * who has not logged in yet. MainActivity calls refresh() once the user
     * is known.
     */
    fun refresh() {
        _isLoading.value = true
        _error.value = null
        notificationRepository.getNotifications { list ->
            _isLoading.value = false
            if (list == null) {
                _error.value = "Couldn't load notifications. Check your connection and try again."
            } else {
                _notifications.value = list
                _unreadCount.value = list.count { !it.isRead }
            }
        }
    }

    /** `user_notifications.id` is an Int, not a String. */
    fun markRead(id: Int) {
        // Update locally first so the list and badge respond immediately; the
        // server call is confirmation, not the source of truth for the UI.
        val alreadyRead = _notifications.value.firstOrNull { it.id == id }?.isRead ?: return
        if (alreadyRead) return

        _notifications.value = _notifications.value.map {
            if (it.id == id) it.copy(isRead = true) else it
        }
        _unreadCount.value = _notifications.value.count { !it.isRead }

        notificationRepository.markRead(id) { ok ->
            // If the server rejected it, put it back so the badge stays honest
            // rather than silently under-reporting unread items.
            if (!ok) {
                _notifications.value = _notifications.value.map {
                    if (it.id == id) it.copy(isRead = false) else it
                }
                _unreadCount.value = _notifications.value.count { !it.isRead }
            }
        }
    }

    /**
     * Uses the server's single `mark-all-read` action rather than looping one
     * request per notification, then reflects the result locally.
     */
    fun markAllRead() {
        val unread = _notifications.value.filterNot { it.isRead }
        if (unread.isEmpty()) return

        _notifications.value = _notifications.value.map {
            if (it.isRead) it else it.copy(isRead = true)
        }
        _unreadCount.value = 0

        notificationRepository.markAllRead { ok ->
            if (!ok) {
                // Restore exactly the ones that were unread before.
                val unreadIds = unread.map { it.id }.toSet()
                _notifications.value = _notifications.value.map {
                    if (it.id in unreadIds) it.copy(isRead = false) else it
                }
                _unreadCount.value = _notifications.value.count { !it.isRead }
            }
        }
    }

    /** Called when a push arrives while the app is open. */
    fun onPushReceived() = refresh()
}
