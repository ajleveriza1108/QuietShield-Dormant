package com.ajcoder.quietshield.dormant.ui

import android.app.Application
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ajcoder.quietshield.dormant.BuildConfig
import com.ajcoder.quietshield.dormant.data.ActionEvent
import com.ajcoder.quietshield.dormant.data.AppCatalogRepository
import com.ajcoder.quietshield.dormant.data.BetaMetricsRepository
import com.ajcoder.quietshield.dormant.data.BetaSummary
import com.ajcoder.quietshield.dormant.data.PolicyRepository
import com.ajcoder.quietshield.dormant.domain.AggressiveSuggestion
import com.ajcoder.quietshield.dormant.domain.AppPolicy
import com.ajcoder.quietshield.dormant.domain.AppRuntimeState
import com.ajcoder.quietshield.dormant.domain.AppSection
import com.ajcoder.quietshield.dormant.domain.AutoAggressiveMode
import com.ajcoder.quietshield.dormant.domain.CompatibilityAdvisor
import com.ajcoder.quietshield.dormant.domain.CompatibilityReport
import com.ajcoder.quietshield.dormant.domain.InstalledApp
import com.ajcoder.quietshield.dormant.domain.ThemeChoice
import com.ajcoder.quietshield.dormant.engine.DormantEngineClient
import com.ajcoder.quietshield.dormant.engine.EngineRuntimeSnapshot
import com.ajcoder.quietshield.dormant.service.BatteryBaselineService
import com.ajcoder.quietshield.dormant.service.DormantMonitorService
import com.ajcoder.quietshield.dormant.wireless.WirelessActivationManager
import com.ajcoder.quietshield.dormant.wireless.WirelessActivationResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RuntimeState(
    val setupReady: Boolean = false,
    val engineSnapshot: EngineRuntimeSnapshot = EngineRuntimeSnapshot(),
    val currentForegroundPackage: String? = null,
    val lastActionStates: Map<String, AppRuntimeState> = emptyMap(),
    val showRunningOnly: Boolean = false,
    val hasUsageAccess: Boolean = false,
    val wirelessBusy: Boolean = false,
    val wirelessMessage: String? = null,
    val hasSavedPairing: Boolean = false,
    val suggestions: Map<String, AggressiveSuggestion> = emptyMap(),
    val betaSummary: BetaSummary = BetaSummary(0, 0, 0, null, null, 0, 0),
    val recentActions: List<ActionEvent> = emptyList(),
    val compatibility: CompatibilityReport? = null,
)

private data class UserSettingsState(
    val autoAggressiveMode: AutoAggressiveMode,
    val restoreAfterRestart: Boolean,
    val baselineUntil: Long,
)

data class AppUiState(
    val apps: List<InstalledApp> = emptyList(),
    val policies: Map<String, AppPolicy> = emptyMap(),
    val selectedSection: AppSection = AppSection.USER,
    val query: String = "",
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val automaticClosing: Boolean = false,
    val setupReady: Boolean = false,
    val engineSnapshot: EngineRuntimeSnapshot = EngineRuntimeSnapshot(),
    val currentForegroundPackage: String? = null,
    val lastActionStates: Map<String, AppRuntimeState> = emptyMap(),
    val showRunningOnly: Boolean = false,
    val hasUsageAccess: Boolean = false,
    val wirelessBusy: Boolean = false,
    val wirelessMessage: String? = null,
    val hasSavedPairing: Boolean = false,
    val suggestions: Map<String, AggressiveSuggestion> = emptyMap(),
    val betaSummary: BetaSummary = BetaSummary(0, 0, 0, null, null, 0, 0),
    val recentActions: List<ActionEvent> = emptyList(),
    val compatibility: CompatibilityReport? = null,
    val autoAggressiveMode: AutoAggressiveMode = AutoAggressiveMode.SUGGEST,
    val restoreAfterRestart: Boolean = false,
    val baselineUntil: Long = 0L,
) {
    val visibleApps: List<InstalledApp>
        get() = apps.filter { app ->
            app.section == selectedSection &&
                (!showRunningOnly || runtimeStateFor(app) in runningStates) &&
                (
                    query.isBlank() ||
                        app.label.contains(query, ignoreCase = true) ||
                        app.packageName.contains(query, ignoreCase = true)
                    )
        }

    fun policyFor(app: InstalledApp): AppPolicy =
        policies[app.packageName] ?: AppPolicy.defaultFor(app)

    fun runtimeStateFor(app: InstalledApp): AppRuntimeState {
        val packageName = app.packageName
        return when {
            packageName == currentForegroundPackage -> AppRuntimeState.OPEN_NOW
            packageName in engineSnapshot.mediaPackages -> AppRuntimeState.PLAYING_MEDIA
            packageName in engineSnapshot.activeServicePackages -> AppRuntimeState.WORKING_IN_BACKGROUND
            packageName in engineSnapshot.runningPackages -> AppRuntimeState.KEPT_READY
            else -> lastActionStates[packageName] ?: AppRuntimeState.NOT_RUNNING
        }
    }

    fun isDisabled(app: InstalledApp): Boolean =
        app.packageName in engineSnapshot.disabledPackages || !app.enabled

    companion object {
        private val runningStates = setOf(
            AppRuntimeState.OPEN_NOW,
            AppRuntimeState.PLAYING_MEDIA,
            AppRuntimeState.WORKING_IN_BACKGROUND,
            AppRuntimeState.KEPT_READY,
        )
    }
}

class QuietShieldViewModel(application: Application) : AndroidViewModel(application) {
    private val catalogRepository = AppCatalogRepository(application)
    private val policyRepository = PolicyRepository(application)
    private val metricsRepository = BetaMetricsRepository(application)
    private val engineClient = DormantEngineClient(application)
    private val wirelessActivation = WirelessActivationManager(application)

    private val apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val selectedSection = MutableStateFlow(AppSection.USER)
    private val query = MutableStateFlow("")
    private val loading = MutableStateFlow(true)
    private val errorMessage = MutableStateFlow<String?>(null)
    private val runtime = MutableStateFlow(
        RuntimeState(
            hasUsageAccess = hasUsageStatsAccess(application),
            hasSavedPairing = wirelessActivation.hasSavedPairing(),
        ),
    )

    val theme: StateFlow<ThemeChoice> = policyRepository.theme.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ThemeChoice.AMOLED,
    )

    private val automaticClosing = policyRepository.automaticClosing.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        false,
    )

    private val settings = combine(
        policyRepository.autoAggressiveMode,
        policyRepository.restoreAfterRestart,
        policyRepository.baselineUntil,
    ) { aggressiveMode, restore, baseline ->
        UserSettingsState(aggressiveMode, restore, baseline)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        UserSettingsState(AutoAggressiveMode.SUGGEST, false, 0L),
    )

    private val baseUiState = combine(
        apps,
        policyRepository.policies,
        selectedSection,
        query,
        loading,
    ) { appList, policyMap, section, searchQuery, isLoading ->
        AppUiState(
            apps = appList,
            policies = policyMap,
            selectedSection = section,
            query = searchQuery,
            loading = isLoading,
        )
    }

    val uiState: StateFlow<AppUiState> = combine(
        baseUiState,
        errorMessage,
        automaticClosing,
        runtime,
        settings,
    ) { state, error, enabled, runtimeState, userSettings ->
        state.copy(
            errorMessage = error,
            automaticClosing = enabled && runtimeState.setupReady,
            setupReady = runtimeState.setupReady,
            engineSnapshot = runtimeState.engineSnapshot,
            currentForegroundPackage = runtimeState.currentForegroundPackage,
            lastActionStates = runtimeState.lastActionStates,
            showRunningOnly = runtimeState.showRunningOnly,
            hasUsageAccess = runtimeState.hasUsageAccess,
            wirelessBusy = runtimeState.wirelessBusy,
            wirelessMessage = runtimeState.wirelessMessage,
            hasSavedPairing = runtimeState.hasSavedPairing,
            suggestions = runtimeState.suggestions,
            betaSummary = runtimeState.betaSummary,
            recentActions = runtimeState.recentActions,
            compatibility = runtimeState.compatibility,
            autoAggressiveMode = userSettings.autoAggressiveMode,
            restoreAfterRestart = userSettings.restoreAfterRestart,
            baselineUntil = userSettings.baselineUntil,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppUiState(),
    )

    init {
        refreshApps()
        viewModelScope.launch { refreshRuntimeLoop() }
    }

    fun refreshApps() {
        viewModelScope.launch {
            loading.value = true
            errorMessage.value = null
            runCatching { catalogRepository.loadInstalledApps() }
                .onSuccess { apps.value = it }
                .onFailure { errorMessage.value = it.message ?: "Unable to load installed apps." }
            refreshPermissionState()
            loading.value = false
            updateRuntimeSnapshot()
        }
    }

    fun selectSection(section: AppSection) {
        selectedSection.value = section
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun clearQuery() {
        query.value = ""
    }

    fun setTheme(themeChoice: ThemeChoice) {
        viewModelScope.launch { policyRepository.setTheme(themeChoice) }
    }

    fun savePolicy(policy: AppPolicy) {
        viewModelScope.launch {
            policyRepository.savePolicy(policy)
            updateRuntimeSnapshot()
        }
    }

    fun savePolicies(apps: List<InstalledApp>, template: AppPolicy) {
        val policies = apps
            .filter { it.section != AppSection.CORE }
            .map { app -> template.copy(packageName = app.packageName) }
        viewModelScope.launch {
            policyRepository.savePolicies(policies)
            updateRuntimeSnapshot()
        }
    }

    fun resetSection(section: AppSection) {
        val packageNames = apps.value
            .filter { it.section == section }
            .mapTo(mutableSetOf()) { it.packageName }
        viewModelScope.launch {
            policyRepository.resetPolicies(packageNames)
            updateRuntimeSnapshot()
        }
    }

    fun toggleRunningOnly() {
        runtime.value = runtime.value.copy(showRunningOnly = !runtime.value.showRunningOnly)
    }

    fun refreshPermissionState() {
        val context = getApplication<Application>()
        runtime.value = runtime.value.copy(
            hasUsageAccess = hasUsageStatsAccess(context),
            hasSavedPairing = wirelessActivation.hasSavedPairing(),
        )
    }

    fun setAutomaticClosing(enabled: Boolean) {
        viewModelScope.launch {
            errorMessage.value = null
            val context = getApplication<Application>()
            if (enabled) {
                val hasUsageAccess = hasUsageStatsAccess(context)
                val setupReady = engineClient.ping()
                runtime.value = runtime.value.copy(
                    hasUsageAccess = hasUsageAccess,
                    setupReady = setupReady,
                    engineSnapshot = if (setupReady) engineClient.runtimeSnapshot() else EngineRuntimeSnapshot(),
                )
                if (!hasUsageAccess) {
                    errorMessage.value = "Allow app activity access, then tap the switch again."
                    return@launch
                }
                if (!setupReady) {
                    errorMessage.value = "Complete Wireless setup, then tap the switch again."
                    return@launch
                }
                BatteryBaselineService.stopTest(context)
                policyRepository.setAutomaticClosing(true)
                DormantMonitorService.start(context)
            } else {
                policyRepository.setAutomaticClosing(false)
                DormantMonitorService.stop(context)
            }
            updateRuntimeSnapshot()
        }
    }

    fun activateAfterSetup() {
        setAutomaticClosing(true)
    }

    fun pairWireless(pairingAddress: String, pairingCode: String) {
        if (runtime.value.wirelessBusy) return
        viewModelScope.launch {
            runtime.value = runtime.value.copy(
                wirelessBusy = true,
                wirelessMessage = "Pairing with this phone…",
            )
            finishWirelessActivation(
                wirelessActivation.pairAndStart(pairingAddress.trim(), pairingCode.trim()),
            )
        }
    }

    fun restoreWireless() {
        if (runtime.value.wirelessBusy) return
        viewModelScope.launch {
            runtime.value = runtime.value.copy(
                wirelessBusy = true,
                wirelessMessage = "Restoring automatic closing…",
            )
            finishWirelessActivation(wirelessActivation.restoreAndStart())
        }
    }

    fun clearWirelessMessage() {
        runtime.value = runtime.value.copy(wirelessMessage = null)
    }

    fun forgetWirelessPairing() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            policyRepository.setAutomaticClosing(false)
            DormantMonitorService.stop(context)
            val removed = wirelessActivation.forgetPairing()
            runtime.value = runtime.value.copy(
                setupReady = false,
                engineSnapshot = EngineRuntimeSnapshot(),
                hasSavedPairing = false,
                wirelessMessage = if (removed) {
                    "Wireless setup was removed."
                } else {
                    "Some wireless setup files could not be removed."
                },
            )
            updateRuntimeSnapshot()
        }
    }

    fun acceptSuggestion(packageName: String) {
        val app = apps.value.firstOrNull { it.packageName == packageName } ?: return
        val current = uiState.value.policyFor(app)
        savePolicy(current.copy(aggressive = true))
    }

    fun dismissSuggestion(packageName: String, neverAgain: Boolean) {
        val app = apps.value.firstOrNull { it.packageName == packageName } ?: return
        viewModelScope.launch {
            if (neverAgain) {
                val current = uiState.value.policyFor(app)
                policyRepository.savePolicy(current.copy(neverSuggestAggressive = true))
            } else {
                metricsRepository.dismissSuggestion(packageName)
            }
            updateRuntimeSnapshot()
        }
    }

    fun setAutoAggressiveMode(mode: AutoAggressiveMode) {
        viewModelScope.launch { policyRepository.setAutoAggressiveMode(mode) }
    }

    fun setRestoreAfterRestart(enabled: Boolean) {
        viewModelScope.launch { policyRepository.setRestoreAfterRestart(enabled) }
    }

    fun setAppEnabled(app: InstalledApp, enabled: Boolean) {
        if (app.section == AppSection.CORE || !runtime.value.setupReady) return
        viewModelScope.launch {
            val success = if (enabled) engineClient.enableApp(app.packageName)
            else engineClient.disableApp(app.packageName)
            errorMessage.value = if (success) {
                if (enabled) "${app.label} was enabled." else "${app.label} was disabled."
            } else {
                "The phone did not allow that change."
            }
            refreshApps()
        }
    }

    fun startBaselineTest() {
        viewModelScope.launch {
            BatteryBaselineService.startThreeDayTest(getApplication())
        }
    }

    fun stopBaselineTest() {
        viewModelScope.launch {
            BatteryBaselineService.stopTest(getApplication())
        }
    }

    fun buildBetaReport(): String {
        val state = uiState.value
        val policyCount = state.policies.values.count { it.sleepMode != com.ajcoder.quietshield.dormant.domain.SleepMode.PROTECTED }
        return metricsRepository.exportReport(
            appVersion = BuildConfig.VERSION_NAME,
            deviceSummary = state.compatibility?.deviceSummary ?: "Android device",
            helperReady = state.setupReady,
            usageReady = state.hasUsageAccess,
            policyCount = policyCount,
            suggestionCount = state.suggestions.size,
        )
    }

    private suspend fun finishWirelessActivation(result: WirelessActivationResult) {
        val context = getApplication<Application>()
        when (result) {
            WirelessActivationResult.Success -> {
                val hasUsageAccess = hasUsageStatsAccess(context)
                runtime.value = runtime.value.copy(
                    wirelessBusy = false,
                    wirelessMessage = if (hasUsageAccess) {
                        "Wireless setup is ready. Automatic closing is on."
                    } else {
                        "Wireless setup is ready. Allow app activity access to finish."
                    },
                    hasSavedPairing = true,
                    hasUsageAccess = hasUsageAccess,
                    setupReady = true,
                )
                metricsRepository.recordHelper(true, "Wireless automatic closing was activated.")
                if (hasUsageAccess) {
                    BatteryBaselineService.stopTest(context)
                    policyRepository.setAutomaticClosing(true)
                    DormantMonitorService.start(context)
                }
            }
            is WirelessActivationResult.Failure -> {
                runtime.value = runtime.value.copy(
                    wirelessBusy = false,
                    wirelessMessage = result.message,
                    hasSavedPairing = wirelessActivation.hasSavedPairing(),
                )
            }
        }
        updateRuntimeSnapshot()
    }

    private suspend fun refreshRuntimeLoop() {
        while (viewModelScope.isActive) {
            updateRuntimeSnapshot()
            delay(10_000L)
        }
    }

    private suspend fun updateRuntimeSnapshot() {
        val context = getApplication<Application>()
        val setupReady = engineClient.ping()
        val engineSnapshot = if (setupReady) engineClient.runtimeSnapshot() else EngineRuntimeSnapshot()
        if (!setupReady && automaticClosing.value) {
            policyRepository.setRuntimeAutomaticClosing(false)
            DormantMonitorService.stop(context)
        }
        val policyMap = uiState.value.policies
        val appList = apps.value
        val suggestions = metricsRepository.suggestions(appList, policyMap)
        val states = appList.mapNotNull { app ->
            metricsRepository.lastActionState(app.packageName)?.let { app.packageName to it }
        }.toMap()
        val usageReady = hasUsageStatsAccess(context)
        val pairingSaved = wirelessActivation.hasSavedPairing()
        runtime.value = runtime.value.copy(
            setupReady = setupReady,
            engineSnapshot = engineSnapshot,
            currentForegroundPackage = if (usageReady) currentForegroundPackage(context) else null,
            lastActionStates = states,
            hasUsageAccess = usageReady,
            hasSavedPairing = pairingSaved,
            suggestions = suggestions,
            betaSummary = metricsRepository.summary(),
            recentActions = metricsRepository.recentActions(),
            compatibility = CompatibilityAdvisor.create(context, setupReady, usageReady, pairingSaved),
        )
        DormantQuickTileRequest.requestTileRefresh(context)
    }

    private fun currentForegroundPackage(context: Context): String? {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = runCatching { manager.queryEvents(now - 60_000L, now) }.getOrNull() ?: return null
        val event = UsageEvents.Event()
        var current: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> current = event.packageName
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> if (current == event.packageName) current = null
            }
        }
        return current
    }

    private fun hasUsageStatsAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
