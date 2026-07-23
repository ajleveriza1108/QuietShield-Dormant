package com.ajcoder.quietshield.dormant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ajcoder.quietshield.dormant.MainActivity
import com.ajcoder.quietshield.dormant.R
import com.ajcoder.quietshield.dormant.data.AppCatalogRepository
import com.ajcoder.quietshield.dormant.data.BetaMetricsRepository
import com.ajcoder.quietshield.dormant.data.PolicyRepository
import com.ajcoder.quietshield.dormant.domain.AppPolicy
import com.ajcoder.quietshield.dormant.domain.AppSection
import com.ajcoder.quietshield.dormant.domain.AutoAggressiveMode
import com.ajcoder.quietshield.dormant.domain.SafetyAdvisor
import com.ajcoder.quietshield.dormant.domain.SleepMode
import com.ajcoder.quietshield.dormant.domain.SyncMode
import com.ajcoder.quietshield.dormant.engine.DormantEngineClient
import com.ajcoder.quietshield.dormant.engine.EngineRuntimeSnapshot
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
    private lateinit var metricsRepository: BetaMetricsRepository
    private lateinit var engineClient: DormantEngineClient
    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager

    @Volatile
    private var policies: Map<String, AppPolicy> = emptyMap()

    @Volatile
    private var autoAggressiveMode: AutoAggressiveMode = AutoAggressiveMode.SUGGEST

    private var appSections: Map<String, AppSection> = emptyMap()
    private var hardProtectedPackages: Set<String> = emptySet()
    private var runtimeSnapshot = EngineRuntimeSnapshot()
    private var monitorJob: Job? = null
    private var lastEventTime = System.currentTimeMillis() - 30_000L
    private var currentForegroundPackage: String? = null
    private val backgroundSince = mutableMapOf<String, Long>()
    private val actionStates = mutableMapOf<String, ActionState>()

    override fun onCreate() {
        super.onCreate()
        policyRepository = PolicyRepository(applicationContext)
        metricsRepository = BetaMetricsRepository(applicationContext)
        engineClient = DormantEngineClient(applicationContext)
        usageStatsManager = getSystemService(UsageStatsManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)

        createNotificationChannel()
        startInForeground()

        serviceScope.launch {
            policyRepository.policies.collectLatest { policies = it }
        }
        serviceScope.launch {
            policyRepository.autoAggressiveMode.collectLatest { autoAggressiveMode = it }
        }
        monitorJob = serviceScope.launch(Dispatchers.IO) {
            loadSafetyData()
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

    private suspend fun loadSafetyData() {
        val apps = runCatching { AppCatalogRepository(application).loadInstalledApps() }
            .getOrDefault(emptyList())
        appSections = apps.associate { it.packageName to it.section }
        val dynamicHard = SafetyAdvisor(application).snapshot().hardProtected
        hardProtectedPackages = apps
            .filter { it.section == AppSection.CORE }
            .mapTo(mutableSetOf()) { it.packageName }
            .apply { addAll(dynamicHard) }
    }

    private suspend fun monitorLoop() {
        var lastEngineCheck = 0L
        var lastRuntimeRefresh = 0L
        var lastMetricsSnapshot = 0L
        var lastBatterySample = 0L
        var lastSafetyRefresh = 0L
        var lastSuggestionCheck = 0L

        while (serviceScope.isActive) {
            val now = System.currentTimeMillis()
            val interactive = powerManager.isInteractive
            val engineInterval = if (interactive) 15_000L else 60_000L
            val runtimeInterval = if (interactive) 15_000L else 45_000L

            if (now - lastEngineCheck >= engineInterval) {
                lastEngineCheck = now
                if (!engineClient.ping()) {
                    policyRepository.setRuntimeAutomaticClosing(false)
                    metricsRepository.recordHelper(false, "Automatic closing stopped because its helper did not respond.", now)
                    stopSelf()
                    break
                }
            }

            if (now - lastRuntimeRefresh >= runtimeInterval) {
                lastRuntimeRefresh = now
                runtimeSnapshot = engineClient.runtimeSnapshot()
            }

            if (now - lastSafetyRefresh >= 5L * 60L * 1_000L) {
                lastSafetyRefresh = now
                val dynamicHard = SafetyAdvisor(application).snapshot().hardProtected
                hardProtectedPackages = hardProtectedPackages + dynamicHard
            }

            readUsageEvents(now)
            applyPolicies(now)

            if (now - lastMetricsSnapshot >= 60_000L) {
                lastMetricsSnapshot = now
                metricsRepository.recordRuntimeSnapshot(
                    runningPackages = runtimeSnapshot.runningPackages,
                    activeServicePackages = runtimeSnapshot.activeServicePackages,
                    screenOn = interactive,
                    now = now,
                )
            }

            if (now - lastBatterySample >= 15L * 60L * 1_000L) {
                lastBatterySample = now
                recordBatterySample(now)
            }

            if (now - lastSuggestionCheck >= 5L * 60L * 1_000L) {
                lastSuggestionCheck = now
                applyAutomaticSuggestions(now)
            }

            delay(calculateNextDelay(now, interactive))
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
        val state = actionStates.remove(packageName)
        if (state?.standbyApplied == true) runCatching { engineClient.markActive(packageName) }
        metricsRepository.recordOpened(packageName, time)
    }

    private fun onPackageLeft(packageName: String, time: Long) {
        if (packageName == applicationContext.packageName) return
        backgroundSince.putIfAbsent(packageName, time)
        if (currentForegroundPackage == packageName) currentForegroundPackage = null
    }

    private suspend fun applyPolicies(now: Long) {
        if (policies.isEmpty()) return
        val globalAudioActive = runCatching { audioManager.isMusicActive }.getOrDefault(false)

        policies.values.toList().forEach { policy ->
            val packageName = policy.packageName
            val section = appSections[packageName]
            when {
                packageName == applicationContext.packageName || packageName in hardProtectedPackages -> {
                    metricsRepository.recordSkipped(packageName, "Always protected by the phone safety list.", now)
                    return@forEach
                }
                packageName == currentForegroundPackage -> return@forEach
                policy.sleepMode == SleepMode.PROTECTED -> return@forEach
                policy.syncMode == SyncMode.ALLOW -> {
                    metricsRepository.recordSkipped(packageName, "Allowed to keep working in the background.", now)
                    return@forEach
                }
                policy.mediaProtection && packageName in runtimeSnapshot.mediaPackages -> {
                    postpone(packageName, now)
                    metricsRepository.recordSkipped(packageName, "Left active because it is playing media.", now)
                    return@forEach
                }
                policy.mediaProtection && globalAudioActive &&
                    packageName in runtimeSnapshot.activeServicePackages -> {
                    postpone(packageName, now)
                    metricsRepository.recordSkipped(packageName, "Left active while audio is playing.", now)
                    return@forEach
                }
                policy.syncMode == SyncMode.SMART &&
                    packageName in runtimeSnapshot.activeServicePackages -> {
                    postpone(packageName, now)
                    metricsRepository.recordSkipped(packageName, "Waiting because it is doing useful background work.", now)
                    return@forEach
                }
            }

            val since = backgroundSince.getOrPut(packageName) { now }
            val elapsed = now - since
            val state = actionStates.getOrPut(packageName) { ActionState() }
            val effectiveMode = if (policy.aggressive && section == AppSection.USER) {
                SleepMode.FORCE_STOP_ONLY
            } else policy.sleepMode

            when (effectiveMode) {
                SleepMode.PROTECTED -> Unit
                SleepMode.STANDBY_ONLY -> {
                    if (!state.standbyApplied && elapsed >= policy.backgroundTimeoutMinutes.minutes) {
                        if (engineClient.placeInStandby(packageName)) {
                            state.standbyApplied = true
                            state.standbyAt = now
                            metricsRepository.recordSlept(packageName, now)
                        }
                    }
                }
                SleepMode.FORCE_STOP_ONLY -> {
                    if (!state.closed && elapsed >= policy.backgroundTimeoutMinutes.minutes) {
                        if (engineClient.forceStop(packageName)) {
                            state.closed = true
                            state.closedAt = now
                            metricsRepository.recordClosed(packageName, now)
                        }
                    }
                }
                SleepMode.STANDBY_THEN_FORCE_STOP -> {
                    if (!state.standbyApplied && elapsed >= policy.backgroundTimeoutMinutes.minutes) {
                        if (engineClient.placeInStandby(packageName)) {
                            state.standbyApplied = true
                            state.standbyAt = now
                            metricsRepository.recordSlept(packageName, now)
                        }
                    }
                    val standbyAt = state.standbyAt
                    if (state.standbyApplied && !state.closed && standbyAt != null &&
                        now - standbyAt >= policy.inactiveTimeoutMinutes.minutes
                    ) {
                        if (engineClient.forceStop(packageName)) {
                            state.closed = true
                            state.closedAt = now
                            metricsRepository.recordClosed(packageName, now)
                        }
                    }
                }
            }
        }
    }

    private suspend fun applyAutomaticSuggestions(now: Long) {
        if (autoAggressiveMode != AutoAggressiveMode.AUTO_APPLY) return
        val apps = runCatching { AppCatalogRepository(application).loadInstalledApps() }
            .getOrDefault(emptyList())
        val suggestions = metricsRepository.suggestions(apps, policies, now)
        suggestions.values.forEach { suggestion ->
            val app = apps.firstOrNull { it.packageName == suggestion.packageName } ?: return@forEach
            val current = policies[suggestion.packageName] ?: AppPolicy.defaultFor(app)
            if (app.section == AppSection.USER && !current.aggressive && !current.neverSuggestAggressive) {
                policyRepository.savePolicy(current.copy(aggressive = true))
                metricsRepository.recordAutoAggressive(
                    suggestion.packageName,
                    "Close sooner was applied automatically: ${suggestion.reason}",
                    now,
                )
            }
        }
    }

    private fun postpone(packageName: String, now: Long) {
        backgroundSince[packageName] = now
        actionStates.remove(packageName)
    }

    private fun recordBatterySample(now: Long) {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val percent = if (level >= 0) (level * 100 / scale) else return
        metricsRepository.recordBatterySample(
            level = percent,
            charging = charging,
            screenOn = powerManager.isInteractive,
            managed = true,
            now = now,
        )
    }

    private fun calculateNextDelay(now: Long, interactive: Boolean): Long {
        val defaultDelay = if (interactive) 15_000L else 60_000L
        var nearest = defaultDelay
        policies.values.forEach { policy ->
            if (policy.sleepMode == SleepMode.PROTECTED || policy.syncMode == SyncMode.ALLOW) return@forEach
            val since = backgroundSince[policy.packageName] ?: return@forEach
            val state = actionStates[policy.packageName]
            val remaining = when {
                state?.closed == true -> defaultDelay
                policy.sleepMode == SleepMode.STANDBY_THEN_FORCE_STOP && state?.standbyApplied == true -> {
                    val standbyAt = state.standbyAt ?: now
                    policy.inactiveTimeoutMinutes.minutes - (now - standbyAt)
                }
                else -> policy.backgroundTimeoutMinutes.minutes - (now - since)
            }
            nearest = minOf(nearest, remaining.coerceAtLeast(2_000L))
        }
        return nearest.coerceIn(2_000L, defaultDelay)
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
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private val Int.minutes: Long
        get() = this.coerceAtLeast(1) * 60_000L

    private data class ActionState(
        var standbyApplied: Boolean = false,
        var standbyAt: Long? = null,
        var closed: Boolean = false,
        var closedAt: Long? = null,
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
