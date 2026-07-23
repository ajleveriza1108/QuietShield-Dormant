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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppUiState(
    val apps: List<InstalledApp> = emptyList(),
    val policies: Map<String, AppPolicy> = emptyMap(),
    val selectedSection: AppSection = AppSection.USER,
    val query: String = "",
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val hasUsageAccess: Boolean = false,
) {
    val visibleApps: List<InstalledApp>
        get() = apps.filter { app ->
            app.section == selectedSection && (
                query.isBlank() ||
                    app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
                )
        }

    fun policyFor(app: InstalledApp): AppPolicy {
        return policies[app.packageName] ?: AppPolicy.defaultFor(app)
    }
}

class QuietShieldViewModel(application: Application) : AndroidViewModel(application) {
    private val catalogRepository = AppCatalogRepository(application)
    private val policyRepository = PolicyRepository(application)

    private val apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val selectedSection = MutableStateFlow(AppSection.USER)
    private val query = MutableStateFlow("")
    private val loading = MutableStateFlow(true)
    private val errorMessage = MutableStateFlow<String?>(null)
    private val usageAccess = MutableStateFlow(hasUsageStatsAccess(application))

    val theme: StateFlow<ThemeChoice> = policyRepository.theme.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ThemeChoice.AMOLED,
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

    private val uiStateWithError = combine(baseUiState, errorMessage) { state, error ->
        state.copy(errorMessage = error)
    }

    val uiState: StateFlow<AppUiState> = combine(
        uiStateWithError,
        usageAccess,
    ) { state, hasUsageAccess ->
        state.copy(hasUsageAccess = hasUsageAccess)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppUiState(),
    )

    init {
        refreshApps()
    }

    fun refreshApps() {
        viewModelScope.launch {
            loading.value = true
            errorMessage.value = null
            runCatching { catalogRepository.loadInstalledApps() }
                .onSuccess { apps.value = it }
                .onFailure { errorMessage.value = it.message ?: "Unable to load installed apps." }
            usageAccess.value = hasUsageStatsAccess(getApplication())
            loading.value = false
        }
    }

    fun selectSection(section: AppSection) {
        selectedSection.value = section
    }

    fun setQuery(value: String) {
        query.value = value
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

    fun refreshPermissionState() {
        usageAccess.value = hasUsageStatsAccess(getApplication())
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
