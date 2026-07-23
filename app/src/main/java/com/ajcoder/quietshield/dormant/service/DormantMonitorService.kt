package com.ajcoder.quietshield.dormant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ajcoder.quietshield.dormant.MainActivity
import com.ajcoder.quietshield.dormant.R
import com.ajcoder.quietshield.dormant.data.AppCatalogRepository
import com.ajcoder.quietshield.dormant.data.PolicyRepository
import com.ajcoder.quietshield.dormant.domain.AppPolicy
import com.ajcoder.quietshield.dormant.domain.AppSection
import com.ajcoder.quietshield.dormant.domain.SleepMode
import com.ajcoder.quietshield.dormant.domain.SyncMode
import com.ajcoder.quietshield.dormant.engine.DormantEngineClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DormantMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var policyRepository: PolicyRepository
    private lateinit var engineClient: DormantEngineClient
    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager

    @Volatile
    private var policies: Map<String, AppPolicy> = emptyMap()

    @Volatile
    private var corePackages: Set<String> = emptySet()

    private var monitorJob: Job? = null
    private var lastEventTime = System.currentTimeMillis() - 15_000L
    private var currentForegroundPackage: String? = null
    private val backgroundSince = mutableMapOf<String, Long>()
    private val actionStates = mutableMapOf<String, ActionState>()

    override fun onCreate() {
        super.onCreate()
        policyRepository = PolicyRepository(applicationContext)
        engineClient = DormantEngineClient(applicationContext)
        usageStatsManager = getSystemService(UsageStatsManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)

        createNotificationChannel()
        startInForeground()

        serviceScope.launch {
            policyRepository.policies.collectLatest { policies = it }
        }
        monitorJob = serviceScope.launch(Dispatchers.IO) {
            corePackages = runCatching {
                AppCatalogRepository(application).loadInstalledApps()
                    .filter { it.section == AppSection.CORE }
                    .mapTo(mutableSetOf()) { it.packageName }
            }.getOrDefault(emptySet())
            monitorLoop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            serviceScope.launch {
                policyRepository.setAutomaticClosing(false)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun monitorLoop() {
        var lastEngineCheck = 0L
        while (serviceScope.isActive) {
            val now = System.currentTimeMillis()
            if (now - lastEngineCheck >= 10_000L) {
                lastEngineCheck = now
                if (!engineClient.ping()) {
                    policyRepository.setAutomaticClosing(false)
                    stopSelf()
                    break
                }
            }

            readUsageEvents(now)
            applyPolicies(now)
            delay(if (powerManager.isInteractive) 2_000L else 10_000L)
        }
    }

    private suspend fun readUsageEvents(now: Long) {
        val events = runCatching { usageStatsManager.queryEvents(lastEventTime, now) }.getOrNull()
        lastEventTime = now
        if (events == null) return

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> onPackageOpened(packageName, event.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> onPackageLeft(packageName, event.timeStamp)
            }
        }
    }

    private suspend fun onPackageOpened(packageName: String, time: Long) {
        val previous = currentForegroundPackage
        if (previous != null && previous != packageName && previous != applicationContext.packageName) {
            backgroundSince.putIfAbsent(previous, time)
        }
        currentForegroundPackage = packageName
        backgroundSince.remove(packageName)
        actionStates.remove(packageName)
    }

    private fun onPackageLeft(packageName: String, time: Long) {
        if (packageName == applicationContext.packageName) return
        backgroundSince.putIfAbsent(packageName, time)
        if (currentForegroundPackage == packageName) {
            currentForegroundPackage = null
        }
    }

    private suspend fun applyPolicies(now: Long) {
        if (policies.isEmpty()) return
        val mediaPlaying = isMediaPlaying()

        policies.values.forEach { policy ->
            val packageName = policy.packageName
            if (packageName == applicationContext.packageName || packageName in corePackages) return@forEach
            if (packageName == currentForegroundPackage || policy.sleepMode == SleepMode.PROTECTED) return@forEach
            if (policy.syncMode == SyncMode.ALLOW) return@forEach

            val protectedNow = policy.mediaProtection && mediaPlaying
            if (protectedNow) {
                backgroundSince[packageName] = now
                actionStates.remove(packageName)
                return@forEach
            }

            val since = backgroundSince.getOrPut(packageName) { now }
            val elapsed = now - since
            val state = actionStates.getOrPut(packageName) { ActionState() }
            val effectiveMode = if (policy.aggressive) SleepMode.FORCE_STOP_ONLY else policy.sleepMode

            when (effectiveMode) {
                SleepMode.PROTECTED -> Unit
                SleepMode.STANDBY_ONLY -> {
                    if (!state.standbyApplied && elapsed >= policy.backgroundTimeoutMinutes.minutes) {
                        if (engineClient.placeInStandby(packageName)) {
                            state.standbyApplied = true
                            state.standbyAt = now
                        }
                    }
                }
                SleepMode.FORCE_STOP_ONLY -> {
                    if (!state.closed && elapsed >= policy.backgroundTimeoutMinutes.minutes) {
                        if (engineClient.forceStop(packageName)) {
                            state.closed = true
                        }
                    }
                }
                SleepMode.STANDBY_THEN_FORCE_STOP -> {
                    if (!state.standbyApplied && elapsed >= policy.backgroundTimeoutMinutes.minutes) {
                        if (engineClient.placeInStandby(packageName)) {
                            state.standbyApplied = true
                            state.standbyAt = now
                        }
                    }
                    val standbyAt = state.standbyAt
                    if (state.standbyApplied && !state.closed && standbyAt != null &&
                        now - standbyAt >= policy.inactiveTimeoutMinutes.minutes
                    ) {
                        if (engineClient.forceStop(packageName)) {
                            state.closed = true
                        }
                    }
                }
            }
        }
    }

    private fun isMediaPlaying(): Boolean {
        return runCatching { audioManager.isMusicActive }.getOrDefault(false)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Automatic closing",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows when QuietShield Dormant is watching selected apps."
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startInForeground() {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DormantMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quick_dormant)
            .setContentTitle("QuietShield Dormant is on")
            .setContentText("Selected apps will follow their saved behavior.")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Pause", pauseIntent)
            .build()

        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private val Int.minutes: Long
        get() = this.coerceAtLeast(1) * 60_000L

    private data class ActionState(
        var standbyApplied: Boolean = false,
        var standbyAt: Long? = null,
        var closed: Boolean = false,
    )

    companion object {
        const val ACTION_STOP = "com.ajcoder.quietshield.dormant.STOP_AUTOMATIC_CLOSING"
        private const val CHANNEL_ID = "automatic_closing"
        private const val NOTIFICATION_ID = 4101

        fun start(context: Context) {
            val intent = Intent(context, DormantMonitorService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DormantMonitorService::class.java))
        }
    }
}
