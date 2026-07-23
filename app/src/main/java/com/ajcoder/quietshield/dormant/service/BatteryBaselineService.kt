package com.ajcoder.quietshield.dormant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import com.ajcoder.quietshield.dormant.MainActivity
import com.ajcoder.quietshield.dormant.R
import com.ajcoder.quietshield.dormant.data.BetaMetricsRepository
import com.ajcoder.quietshield.dormant.data.PolicyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Lightweight optional 3-day measurement used before automatic closing is enabled. */
class BatteryBaselineService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var metrics: BetaMetricsRepository
    private lateinit var policies: PolicyRepository
    private lateinit var powerManager: PowerManager

    override fun onCreate() {
        super.onCreate()
        metrics = BetaMetricsRepository(applicationContext)
        policies = PolicyRepository(applicationContext)
        powerManager = getSystemService(PowerManager::class.java)
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        scope.launch { loop() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun loop() {
        while (scope.isActive) {
            val until = policies.baselineUntil.first()
            val now = System.currentTimeMillis()
            if (until <= now) {
                policies.setBaselineUntil(0L)
                stopSelf()
                break
            }
            sample(now)
            delay(15L * 60L * 1_000L)
        }
    }

    private fun sample(now: Long) {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        if (level < 0) return
        metrics.recordBatterySample(
            level = level * 100 / scale,
            charging = charging,
            screenOn = powerManager.isInteractive,
            managed = false,
            now = now,
        )
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Battery comparison",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Measures screen-off battery use before automatic closing is enabled."
                setSound(null, null)
            },
        )
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_quick_dormant)
        .setContentTitle("QuietShield Dormant is measuring battery use")
        .setContentText("This lightweight measurement will stop automatically after three days.")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    companion object {
        private const val CHANNEL_ID = "battery_baseline"
        private const val NOTIFICATION_ID = 4102
        private const val THREE_DAYS = 3L * 24L * 60L * 60L * 1_000L

        suspend fun startThreeDayTest(context: Context) {
            PolicyRepository(context).setBaselineUntil(System.currentTimeMillis() + THREE_DAYS)
            ContextCompat.startForegroundService(context, Intent(context, BatteryBaselineService::class.java))
        }

        suspend fun stopTest(context: Context) {
            PolicyRepository(context).setBaselineUntil(0L)
            context.stopService(Intent(context, BatteryBaselineService::class.java))
        }
    }
}
