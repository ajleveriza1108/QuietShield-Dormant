package com.ajcoder.quietshield.dormant.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DormantNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshActivePackages()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshActivePackages()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshActivePackages()
    }

    override fun onListenerDisconnected() {
        activePackagesMutable.value = emptySet()
        super.onListenerDisconnected()
    }

    private fun refreshActivePackages() {
        activePackagesMutable.value = runCatching {
            activeNotifications
                .asSequence()
                .map { it.packageName }
                .filter { it != packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    companion object {
        private val activePackagesMutable = MutableStateFlow<Set<String>>(emptySet())
        val activePackages: StateFlow<Set<String>> = activePackagesMutable
    }
}
