package com.ajcoder.quietshield.dormant.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ajcoder.quietshield.dormant.domain.AppPolicy
import com.ajcoder.quietshield.dormant.domain.AutoAggressiveMode
import com.ajcoder.quietshield.dormant.domain.SleepMode
import com.ajcoder.quietshield.dormant.domain.SyncMode
import com.ajcoder.quietshield.dormant.domain.ThemeChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.settingsDataStore by preferencesDataStore(name = "quietshield_dormant_settings")

class PolicyRepository(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val policies = stringPreferencesKey("policies_json")
        val automaticClosing = booleanPreferencesKey("automatic_closing")
        val restoreAfterRestart = booleanPreferencesKey("restore_after_restart")
        val autoAggressiveMode = stringPreferencesKey("auto_aggressive_mode")
        val baselineUntil = longPreferencesKey("baseline_until")
    }

    val theme: Flow<ThemeChoice> = context.settingsDataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { preferences ->
            preferences[Keys.theme]
                ?.let { runCatching { ThemeChoice.valueOf(it) }.getOrNull() }
                ?: ThemeChoice.AMOLED
        }

    val policies: Flow<Map<String, AppPolicy>> = context.settingsDataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { preferences -> decodePolicies(preferences[Keys.policies].orEmpty()) }

    val automaticClosing: Flow<Boolean> = context.settingsDataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { preferences -> preferences[Keys.automaticClosing] ?: false }

    val restoreAfterRestart: Flow<Boolean> = context.settingsDataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { preferences -> preferences[Keys.restoreAfterRestart] ?: false }

    val baselineUntil: Flow<Long> = context.settingsDataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { preferences -> preferences[Keys.baselineUntil] ?: 0L }

    val autoAggressiveMode: Flow<AutoAggressiveMode> = context.settingsDataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { preferences ->
            preferences[Keys.autoAggressiveMode]
                ?.let { runCatching { AutoAggressiveMode.valueOf(it) }.getOrNull() }
                ?: AutoAggressiveMode.SUGGEST
        }

    suspend fun setTheme(themeChoice: ThemeChoice) {
        context.settingsDataStore.edit { it[Keys.theme] = themeChoice.name }
    }

    /** User action: remembers whether automatic closing should be restored after restart. */
    suspend fun setAutomaticClosing(enabled: Boolean) {
        context.settingsDataStore.edit {
            it[Keys.automaticClosing] = enabled
            it[Keys.restoreAfterRestart] = enabled
        }
    }

    /** Runtime state change: keeps the user's restore preference unchanged. */
    suspend fun setRuntimeAutomaticClosing(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.automaticClosing] = enabled }
    }

    suspend fun setRestoreAfterRestart(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.restoreAfterRestart] = enabled }
    }

    suspend fun setBaselineUntil(timestamp: Long) {
        context.settingsDataStore.edit { it[Keys.baselineUntil] = timestamp.coerceAtLeast(0L) }
    }

    suspend fun setAutoAggressiveMode(mode: AutoAggressiveMode) {
        context.settingsDataStore.edit { it[Keys.autoAggressiveMode] = mode.name }
    }

    suspend fun savePolicy(policy: AppPolicy) {
        savePolicies(listOf(policy))
    }

    suspend fun savePolicies(updatedPolicies: List<AppPolicy>) {
        if (updatedPolicies.isEmpty()) return
        context.settingsDataStore.edit { preferences ->
            val current = decodePolicies(preferences[Keys.policies].orEmpty()).toMutableMap()
            updatedPolicies.forEach { policy -> current[policy.packageName] = policy }
            preferences[Keys.policies] = encodePolicies(current)
        }
    }

    suspend fun resetPolicies(packageNames: Set<String>) {
        if (packageNames.isEmpty()) return
        context.settingsDataStore.edit { preferences ->
            val current = decodePolicies(preferences[Keys.policies].orEmpty()).toMutableMap()
            packageNames.forEach(current::remove)
            if (current.isEmpty()) preferences.remove(Keys.policies)
            else preferences[Keys.policies] = encodePolicies(current)
        }
    }

    private fun encodePolicies(policies: Map<String, AppPolicy>): String {
        val root = JSONObject()
        policies.forEach { (packageName, policy) ->
            root.put(
                packageName,
                JSONObject().apply {
                    put("sleepMode", policy.sleepMode.name)
                    put("backgroundTimeoutMinutes", policy.backgroundTimeoutMinutes)
                    put("inactiveTimeoutMinutes", policy.inactiveTimeoutMinutes)
                    put("syncMode", policy.syncMode.name)
                    put("mediaProtection", policy.mediaProtection)
                    put("aggressive", policy.aggressive)
                    put("neverSuggestAggressive", policy.neverSuggestAggressive)
                },
            )
        }
        return root.toString()
    }

    private fun decodePolicies(raw: String): Map<String, AppPolicy> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            buildMap {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val packageName = keys.next()
                    val item = root.optJSONObject(packageName) ?: continue
                    put(
                        packageName,
                        AppPolicy(
                            packageName = packageName,
                            sleepMode = enumOrDefault(item.optString("sleepMode"), SleepMode.PROTECTED),
                            backgroundTimeoutMinutes = item
                                .optInt("backgroundTimeoutMinutes", 10)
                                .coerceAtLeast(1),
                            inactiveTimeoutMinutes = item
                                .optInt("inactiveTimeoutMinutes", 30)
                                .coerceAtLeast(1),
                            syncMode = enumOrDefault(item.optString("syncMode"), SyncMode.SMART),
                            mediaProtection = item.optBoolean("mediaProtection", true),
                            aggressive = item.optBoolean("aggressive", false),
                            neverSuggestAggressive = item.optBoolean("neverSuggestAggressive", false),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String, default: T): T {
        return enumValues<T>().firstOrNull { it.name == value } ?: default
    }
}
