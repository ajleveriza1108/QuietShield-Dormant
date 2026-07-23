package com.ajcoder.quietshield.dormant.data

import android.content.Context
import com.ajcoder.quietshield.dormant.domain.AggressiveAdvisor
import com.ajcoder.quietshield.dormant.domain.AggressiveSignals
import com.ajcoder.quietshield.dormant.domain.AggressiveSuggestion
import com.ajcoder.quietshield.dormant.domain.AppPolicy
import com.ajcoder.quietshield.dormant.domain.AppRuntimeState
import com.ajcoder.quietshield.dormant.domain.InstalledApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

enum class ActionKind {
    SLEPT,
    CLOSED,
    SKIPPED,
    AUTO_AGGRESSIVE,
    HELPER_RESTORED,
    HELPER_LOST,
    APP_OPENED,
}

data class ActionEvent(
    val timestamp: Long,
    val packageName: String,
    val kind: ActionKind,
    val detail: String,
)

data class AppObservation(
    var restartAfterCloseCount: Int = 0,
    var backgroundMillis: Long = 0L,
    var activeServiceSamples: Int = 0,
    var overnightSamples: Int = 0,
    var lastSignalAt: Long = 0L,
    var lastObservedAt: Long = 0L,
    var lastClosedAt: Long = 0L,
    var restartCountedForCloseAt: Long = 0L,
    var suggestionDismissedUntil: Long = 0L,
)

data class BatterySample(
    val timestamp: Long,
    val level: Int,
    val charging: Boolean,
    val screenOn: Boolean,
    val managed: Boolean,
)

data class BetaSummary(
    val sleptCount: Int,
    val closedCount: Int,
    val skippedCount: Int,
    val baselineDrainPerHour: Double?,
    val managedDrainPerHour: Double?,
    val batterySampleCount: Int,
    val actionCount: Int,
)

class BetaMetricsRepository(context: Context) {
    private val file: File = context.filesDir.resolve("beta_metrics.json")
    private val lock = Any()
    private val actions = ArrayDeque<ActionEvent>()
    private val observations = mutableMapOf<String, AppObservation>()
    private val batterySamples = ArrayDeque<BatterySample>()
    private var lastPersistAt = 0L

    init {
        load()
    }

    fun recordOpened(packageName: String, now: Long) = synchronized(lock) {
        actions.addLast(ActionEvent(now, packageName, ActionKind.APP_OPENED, "Opened by the user"))
        trimActions()
        persist(now, force = false)
    }

    fun recordSlept(packageName: String, now: Long) = synchronized(lock) {
        actions.addLast(ActionEvent(now, packageName, ActionKind.SLEPT, "Put to sleep"))
        trimActions()
        persist(now, force = true)
    }

    fun recordClosed(packageName: String, now: Long) = synchronized(lock) {
        val observation = observations.getOrPut(packageName, ::AppObservation)
        observation.lastClosedAt = now
        observation.lastSignalAt = now
        actions.addLast(ActionEvent(now, packageName, ActionKind.CLOSED, "Closed after its saved timer"))
        trimActions()
        persist(now, force = true)
    }

    fun recordAutoAggressive(packageName: String, reason: String, now: Long) = synchronized(lock) {
        actions.addLast(ActionEvent(now, packageName, ActionKind.AUTO_AGGRESSIVE, reason))
        trimActions()
        persist(now, force = true)
    }

    fun recordHelper(restored: Boolean, detail: String, now: Long = System.currentTimeMillis()) =
        synchronized(lock) {
            actions.addLast(
                ActionEvent(
                    now,
                    "com.ajcoder.quietshield.dormant",
                    if (restored) ActionKind.HELPER_RESTORED else ActionKind.HELPER_LOST,
                    detail,
                ),
            )
            trimActions()
            persist(now, force = true)
        }

    fun recordSkipped(packageName: String, detail: String, now: Long) = synchronized(lock) {
        val recentDuplicate = actions.lastOrNull { event ->
            event.packageName == packageName &&
                event.kind == ActionKind.SKIPPED &&
                event.detail == detail &&
                now - event.timestamp < 30L * 60L * 1_000L
        }
        if (recentDuplicate == null) {
            actions.addLast(ActionEvent(now, packageName, ActionKind.SKIPPED, detail))
            trimActions()
            persist(now, force = false)
        }
    }

    fun recordRuntimeSnapshot(
        runningPackages: Set<String>,
        activeServicePackages: Set<String>,
        screenOn: Boolean,
        now: Long,
    ) = synchronized(lock) {
        val observed = runningPackages + activeServicePackages
        observed.forEach { packageName ->
            val item = observations.getOrPut(packageName, ::AppObservation)
            val previous = item.lastObservedAt
            if (previous > 0L) {
                val elapsed = (now - previous).coerceIn(0L, 5L * 60L * 1_000L)
                if (packageName in activeServicePackages) item.backgroundMillis += elapsed
            }
            if (packageName in activeServicePackages) item.activeServiceSamples += 1
            if (!screenOn && packageName in activeServicePackages) item.overnightSamples += 1
            if (item.lastClosedAt > 0L &&
                now - item.lastClosedAt <= 10L * 60L * 1_000L &&
                item.restartCountedForCloseAt != item.lastClosedAt
            ) {
                item.restartAfterCloseCount += 1
                item.restartCountedForCloseAt = item.lastClosedAt
                item.lastSignalAt = now
            }
            if (packageName in activeServicePackages) item.lastSignalAt = now
            item.lastObservedAt = now
        }
        persist(now, force = false)
    }

    fun recordBatterySample(
        level: Int,
        charging: Boolean,
        screenOn: Boolean,
        managed: Boolean,
        now: Long,
    ) = synchronized(lock) {
        val last = batterySamples.lastOrNull()
        if (last == null ||
            last.level != level ||
            last.charging != charging ||
            last.screenOn != screenOn ||
            last.managed != managed ||
            now - last.timestamp >= 15L * 60L * 1_000L
        ) {
            batterySamples.addLast(BatterySample(now, level.coerceIn(0, 100), charging, screenOn, managed))
            while (batterySamples.size > 1_000) batterySamples.removeFirst()
            persist(now, force = false)
        }
    }

    fun dismissSuggestion(packageName: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        observations.getOrPut(packageName, ::AppObservation).suggestionDismissedUntil =
            now + 24L * 60L * 60L * 1_000L
        persist(now, force = true)
    }

    fun suggestions(
        apps: List<InstalledApp>,
        policies: Map<String, AppPolicy>,
        now: Long = System.currentTimeMillis(),
    ): Map<String, AggressiveSuggestion> = synchronized(lock) {
        buildMap {
            apps.forEach { app ->
                val policy = policies[app.packageName] ?: AppPolicy.defaultFor(app)
                val observation = observations[app.packageName] ?: return@forEach
                if (observation.suggestionDismissedUntil > now) return@forEach
                val suggestion = AggressiveAdvisor.evaluate(
                    packageName = app.packageName,
                    section = app.section,
                    policy = policy,
                    signals = AggressiveSignals(
                        restartAfterCloseCount = observation.restartAfterCloseCount,
                        backgroundMinutes = observation.backgroundMillis / 60_000L,
                        activeServiceSamples = observation.activeServiceSamples,
                        overnightSamples = observation.overnightSamples,
                        lastSignalAt = observation.lastSignalAt,
                    ),
                    now = now,
                )
                if (suggestion != null) put(app.packageName, suggestion)
            }
        }
    }

    fun lastActionState(packageName: String): AppRuntimeState? = synchronized(lock) {
        val now = System.currentTimeMillis()
        actions.toList().asReversed().firstOrNull { it.packageName == packageName }?.let { event ->
            when (event.kind) {
                ActionKind.SLEPT -> if (now - event.timestamp <= 6L * 60L * 60L * 1_000L) {
                    AppRuntimeState.SLEEPING
                } else null
                ActionKind.CLOSED -> if (now - event.timestamp <= 24L * 60L * 60L * 1_000L) {
                    AppRuntimeState.CLOSED
                } else null
                ActionKind.APP_OPENED -> null
                else -> null
            }
        }
    }

    fun summary(): BetaSummary = synchronized(lock) {
        BetaSummary(
            sleptCount = actions.count { it.kind == ActionKind.SLEPT },
            closedCount = actions.count { it.kind == ActionKind.CLOSED },
            skippedCount = actions.count { it.kind == ActionKind.SKIPPED },
            baselineDrainPerHour = calculateDrain(managed = false),
            managedDrainPerHour = calculateDrain(managed = true),
            batterySampleCount = batterySamples.size,
            actionCount = actions.size,
        )
    }

    fun recentActions(limit: Int = 50): List<ActionEvent> = synchronized(lock) {
        actions.takeLast(limit.coerceIn(1, 300)).reversed()
    }

    fun exportReport(
        appVersion: String,
        deviceSummary: String,
        helperReady: Boolean,
        usageReady: Boolean,
        policyCount: Int,
        suggestionCount: Int,
    ): String = synchronized(lock) {
        val summary = summary()
        buildString {
            appendLine("QuietShield Dormant Beta Report")
            appendLine("Generated: ${formatTime(System.currentTimeMillis())}")
            appendLine("Version: $appVersion")
            appendLine("Device: $deviceSummary")
            appendLine("Automatic closing helper: ${if (helperReady) "Ready" else "Not ready"}")
            appendLine("App activity access: ${if (usageReady) "Ready" else "Not ready"}")
            appendLine("Managed app rules: $policyCount")
            appendLine("Close-sooner suggestions: $suggestionCount")
            appendLine()
            appendLine("Results")
            appendLine("Apps put to sleep: ${summary.sleptCount}")
            appendLine("Apps closed: ${summary.closedCount}")
            appendLine("Actions skipped for safety: ${summary.skippedCount}")
            appendLine("Screen-off battery use before management: ${formatDrain(summary.baselineDrainPerHour)}")
            appendLine("Screen-off battery use with management: ${formatDrain(summary.managedDrainPerHour)}")
            appendLine()
            appendLine("Recent activity")
            recentActions(80).forEach { event ->
                appendLine("${formatTime(event.timestamp)} | ${event.kind.name} | ${event.packageName} | ${event.detail}")
            }
        }
    }

    private fun calculateDrain(managed: Boolean): Double? {
        val samples = batterySamples.filter {
            it.managed == managed && !it.charging && !it.screenOn
        }.sortedBy { it.timestamp }
        if (samples.size < 3) return null
        var drop = 0.0
        var hours = 0.0
        samples.zipWithNext().forEach { (first, second) ->
            val delta = second.timestamp - first.timestamp
            if (delta in 5L * 60L * 1_000L..2L * 60L * 60L * 1_000L && second.level <= first.level) {
                drop += (first.level - second.level).toDouble()
                hours += delta / 3_600_000.0
            }
        }
        return if (hours >= 0.5) max(0.0, drop / hours) else null
    }

    private fun formatDrain(value: Double?): String =
        value?.let { String.format(Locale.US, "%.2f%% per hour", it) } ?: "Not enough samples yet"

    private fun formatTime(timestamp: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        format.timeZone = TimeZone.getDefault()
        return format.format(Date(timestamp))
    }

    private fun trimActions() {
        while (actions.size > 300) actions.removeFirst()
    }

    private fun persist(now: Long, force: Boolean) {
        if (!force && now - lastPersistAt < 5L * 60L * 1_000L) return
        lastPersistAt = now
        runCatching {
            val root = JSONObject()
            root.put("actions", JSONArray().apply {
                actions.forEach { event ->
                    put(JSONObject().apply {
                        put("timestamp", event.timestamp)
                        put("packageName", event.packageName)
                        put("kind", event.kind.name)
                        put("detail", event.detail)
                    })
                }
            })
            root.put("observations", JSONObject().apply {
                observations.forEach { (packageName, item) ->
                    put(packageName, JSONObject().apply {
                        put("restartAfterCloseCount", item.restartAfterCloseCount)
                        put("backgroundMillis", item.backgroundMillis)
                        put("activeServiceSamples", item.activeServiceSamples)
                        put("overnightSamples", item.overnightSamples)
                        put("lastSignalAt", item.lastSignalAt)
                        put("lastObservedAt", item.lastObservedAt)
                        put("lastClosedAt", item.lastClosedAt)
                        put("restartCountedForCloseAt", item.restartCountedForCloseAt)
                        put("suggestionDismissedUntil", item.suggestionDismissedUntil)
                    })
                }
            })
            root.put("batterySamples", JSONArray().apply {
                batterySamples.forEach { sample ->
                    put(JSONObject().apply {
                        put("timestamp", sample.timestamp)
                        put("level", sample.level)
                        put("charging", sample.charging)
                        put("screenOn", sample.screenOn)
                        put("managed", sample.managed)
                    })
                }
            })
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(root.toString(), Charsets.UTF_8)
            if (!temporary.renameTo(file)) {
                file.writeText(root.toString(), Charsets.UTF_8)
                temporary.delete()
            }
        }
    }

    private fun load() = synchronized(lock) {
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
        if (raw.isBlank()) return@synchronized
        runCatching {
            val root = JSONObject(raw)
            val actionArray = root.optJSONArray("actions") ?: JSONArray()
            repeat(actionArray.length()) { index ->
                val item = actionArray.optJSONObject(index) ?: return@repeat
                actions.addLast(
                    ActionEvent(
                        timestamp = item.optLong("timestamp"),
                        packageName = item.optString("packageName"),
                        kind = runCatching { ActionKind.valueOf(item.optString("kind")) }
                            .getOrDefault(ActionKind.SKIPPED),
                        detail = item.optString("detail"),
                    ),
                )
            }
            val observationRoot = root.optJSONObject("observations") ?: JSONObject()
            val keys = observationRoot.keys()
            while (keys.hasNext()) {
                val packageName = keys.next()
                val item = observationRoot.optJSONObject(packageName) ?: continue
                observations[packageName] = AppObservation(
                    restartAfterCloseCount = item.optInt("restartAfterCloseCount"),
                    backgroundMillis = item.optLong("backgroundMillis"),
                    activeServiceSamples = item.optInt("activeServiceSamples"),
                    overnightSamples = item.optInt("overnightSamples"),
                    lastSignalAt = item.optLong("lastSignalAt"),
                    lastObservedAt = item.optLong("lastObservedAt"),
                    lastClosedAt = item.optLong("lastClosedAt"),
                    restartCountedForCloseAt = item.optLong("restartCountedForCloseAt"),
                    suggestionDismissedUntil = item.optLong("suggestionDismissedUntil"),
                )
            }
            val batteryArray = root.optJSONArray("batterySamples") ?: JSONArray()
            repeat(batteryArray.length()) { index ->
                val item = batteryArray.optJSONObject(index) ?: return@repeat
                batterySamples.addLast(
                    BatterySample(
                        timestamp = item.optLong("timestamp"),
                        level = item.optInt("level"),
                        charging = item.optBoolean("charging"),
                        screenOn = item.optBoolean("screenOn"),
                        managed = item.optBoolean("managed"),
                    ),
                )
            }
            trimActions()
            while (batterySamples.size > 1_000) batterySamples.removeFirst()
        }
    }
}
