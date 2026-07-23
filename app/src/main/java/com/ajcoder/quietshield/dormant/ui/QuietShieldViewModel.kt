package com.ajcoder.quietshield.dormant.ui

import android.app.Application
import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ajcoder.quietshield.dormant.data.AppCatalogRepository
import com.ajcoder.quietshield.dormant.data.PolicyRepository
import com.ajcoder.quietshield.dormant.domain.AppPolicy
import com.ajcoder.quietshield.dormant.domain.AppSection
import com.ajcoder.quietshield.dormant.domain.InstalledApp
import com.ajcoder.quietshield.dormant.domain.ThemeChoice
import com.ajcoder.quietshield.dormant.engine.DormantEngineClient
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
    val runningPackages: Set<String> = emptySet(),
    val showRunningOnly: Boolean = false,
    val hasUsageAccess: Boolean = false,
    val wirelessBusy: Boolean = false,
    val wirelessMessage: String? = null,
    val hasSavedPairing: Boolean = false,
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
    val runningPackages: Set<String> = emptySet(),
    val showRunningOnly: Boolean = false,
    val hasUsageAccess: Boolean = false,
    val wirelessBusy: Boolean = false,
    val wirelessMessage: String? = null,
    val hasSavedPairing: Boolean = false,
) {
    val visibleApps: List<InstalledApp>
        get() = apps.filter { app ->
            app.section == selectedSection &&
                (!showRunningOnly || app.packageName in runningPackages) &&
                (
                    query.isBlank() ||
                        app.label.contains(query, ignoreCase = true) ||
                        app.packageName.contains(query, ignoreCase = true)
                    )
        }

    fun policyFor(app: InstalledApp): AppPolicy {
        return policies[app.packageName] ?: AppPolicy.defaultFor(app)
    }

    fun isRunning(app: InstalledApp): Boolean = app.packageName in runningPackages
}

class QuietShieldViewModel(application: Application) : AndroidViewModel(application) {
    private val catalogRepository = AppCatalogRepository(application)
    private val policyRepository = PolicyRepository(application)
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
    ) { state, error, enabled, runtimeState ->
        state.copy(
            errorMessage = error,
            automaticClosing = enabled && runtimeState.setupReady,
            setupReady = runtimeState.setupReady,
            runningPackages = runtimeState.runningPackages,
            showRunningOnly = runtimeState.showRunningOnly,
            hasUsageAccess = runtimeState.hasUsageAccess,
            wirelessBusy = runtimeState.wirelessBusy,
            wirelessMessage = runtimeState.wirelessMessage,
            hasSavedPairing = runtimeState.hasSavedPairing,
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
        viewModelScope.launch { policyRepository.savePolicy(policy) }
    }

    fun savePolicies(apps: List<InstalledApp>, template: AppPolicy) {
        val policies = apps
            .filter { it.section != AppSection.CORE }
            .map { app -> template.copy(packageName = app.packageName) }
        viewModelScope.launch { policyRepository.savePolicies(policies) }
    }

    fun resetSection(section: AppSection) {
        val packageNames = apps.value
            .filter { it.section == section }
            .mapTo(mutableSetOf()) { it.packageName }
        viewModelScope.launch { policyRepository.resetPolicies(packageNames) }
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
                    runningPackages = if (setupReady) engineClient.runningPackages() else emptySet(),
                )
                if (!hasUsageAccess) {
                    errorMessage.value = "Allow app activity access, then tap the switch again."
                    return@launch
                }
                if (!setupReady) {
                    errorMessage.value = "Complete Wireless setup, then tap the switch again."
                    return@launch
                }
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
            val result = wirelessActivation.pairAndStart(
                pairingAddress = pairingAddress.trim(),
                pairingCode = pairingCode.trim(),
            )
            finishWirelessActivation(result)
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
                if (hasUsageAccess) {
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
            delay(5_000L)
        }
    }

    private suspend fun updateRuntimeSnapshot() {
        val context = getApplication<Application>()
        val setupReady = engineClient.ping()
        val runningPackages = if (setupReady) engineClient.runningPackages() else emptySet()
        if (!setupReady && automaticClosing.value) {
            policyRepository.setAutomaticClosing(false)
            DormantMonitorService.stop(context)
        }
        runtime.value = runtime.value.copy(
            setupReady = setupReady,
            runningPackages = runningPackages,
            hasUsageAccess = hasUsageStatsAccess(context),
            hasSavedPairing = wirelessActivation.hasSavedPairing(),
        )
        DormantQuickTileRequest.requestTileRefresh(context)
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
