package com.ajcoder.quietshield.dormant.service

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import com.ajcoder.quietshield.dormant.MainActivity
import com.ajcoder.quietshield.dormant.R
import com.ajcoder.quietshield.dormant.data.BetaMetricsRepository
import com.ajcoder.quietshield.dormant.data.PolicyRepository
import com.ajcoder.quietshield.dormant.wireless.WirelessActivationManager
import com.ajcoder.quietshield.dormant.wireless.WirelessActivationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DormantRecoveryService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Checking automatic closing…", ongoing = true),
            type,
        )
        scope.launch { restore() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun restore() {
        val repository = PolicyRepository(applicationContext)
        if (!repository.restoreAfterRestart.first()) {
            stopSelf()
            return
        }

        val result = WirelessActivationManager(applicationContext).restoreAndStart()
        when (result) {
            WirelessActivationResult.Success -> {
                if (hasUsageAccess()) {
                    repository.setRuntimeAutomaticClosing(true)
                    BetaMetricsRepository(applicationContext).recordHelper(
                        restored = true,
                        detail = "Automatic closing was restored after restart.",
                    )
                    BatteryBaselineService.stopTest(applicationContext)
                    DormantMonitorService.start(applicationContext)
                    notify("Automatic closing was restored.", ongoing = false)
                } else {
                    repository.setRuntimeAutomaticClosing(false)
                    notify("Tap to finish automatic closing setup.", ongoing = false)
                }
            }
            is WirelessActivationResult.Failure -> {
                repository.setRuntimeAutomaticClosing(false)
                BetaMetricsRepository(applicationContext).recordHelper(
                    restored = false,
                    detail = result.message,
                )
                notify("Tap to restore automatic closing.", ongoing = false)
            }
        }
        stopSelf()
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java) ?: return false
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Restore automatic closing",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Shows when automatic closing needs to be restored after a restart."
            },
        )
    }

    private fun notify(text: String, ongoing: Boolean) {
        getSystemService(NotificationManager::class.java).notify(
            RESULT_NOTIFICATION_ID,
            buildNotification(text, ongoing),
        )
    }

    private fun buildNotification(text: String, ongoing: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quick_dormant)
            .setContentTitle("QuietShield Dormant")
            .setContentText(text)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "automatic_closing_recovery"
        private const val NOTIFICATION_ID = 4103
        private const val RESULT_NOTIFICATION_ID = 4104

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DormantRecoveryService::class.java))
        }
    }
}
