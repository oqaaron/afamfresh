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
                _unreadCount.value = list.count { !it.read }
            }
        }
    }

    fun markRead(id: String) {
        // Update locally first so the list and badge respond immediately; the
        // server call is confirmation, not the source of truth for the UI.
        val alreadyRead = _notifications.value.firstOrNull { it.id == id }?.read ?: return
        if (alreadyRead) return

        _notifications.value = _notifications.value.map {
            if (it.id == id) it.copy(read = true) else it
        }
        _unreadCount.value = _notifications.value.count { !it.read }

        notificationRepository.markRead(id) { ok ->
            // If the server rejected it, put it back so the badge stays honest
            // rather than silently under-reporting unread items.
            if (!ok) {
                _notifications.value = _notifications.value.map {
                    if (it.id == id) it.copy(read = false) else it
                }
                _unreadCount.value = _notifications.value.count { !it.read }
            }
        }
    }

    fun markAllRead() {
        _notifications.value.filterNot { it.read }.forEach { markRead(it.id) }
    }

    /** Called when a push arrives while the app is open. */
    fun onPushReceived() = refresh()
}
