package com.ajcoder.quietshield.dormant.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ajcoder.quietshield.dormant.domain.AppPolicy
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

    suspend fun setTheme(themeChoice: ThemeChoice) {
        context.settingsDataStore.edit { it[Keys.theme] = themeChoice.name }
    }

    suspend fun savePolicy(policy: AppPolicy) {
        context.settingsDataStore.edit { preferences ->
            val current = decodePolicies(preferences[Keys.policies].orEmpty()).toMutableMap()
            current[policy.packageName] = policy
            preferences[Keys.policies] = encodePolicies(current)
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
                            sleepMode = enumOrDefault(
                                item.optString("sleepMode"),
                                SleepMode.STANDBY_THEN_FORCE_STOP,
                            ),
                            backgroundTimeoutMinutes = item
                                .optInt("backgroundTimeoutMinutes", 10)
                                .coerceAtLeast(1),
                            inactiveTimeoutMinutes = item
                                .optInt("inactiveTimeoutMinutes", 30)
                                .coerceAtLeast(1),
                            syncMode = enumOrDefault(
                                item.optString("syncMode"),
                                SyncMode.SMART,
                            ),
                            mediaProtection = item.optBoolean("mediaProtection", true),
                            aggressive = item.optBoolean("aggressive", false),
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
