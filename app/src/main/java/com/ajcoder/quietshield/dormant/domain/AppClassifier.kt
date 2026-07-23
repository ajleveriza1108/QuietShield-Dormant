package com.ajcoder.quietshield.dormant.domain

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager

class AppClassifier(private val application: Application) {
    private val packageManager = application.packageManager

    private val ownPackage = application.packageName
    private val launcherPackage: String? by lazy { resolveLauncherPackage() }
    private val inputMethodPackage: String? by lazy { resolveInputMethodPackage() }
    private val dialerPackage: String? by lazy { resolveDialerPackage() }
    private val smsPackage: String? by lazy { Telephony.Sms.getDefaultSmsPackage(application) }

    fun classify(info: ApplicationInfo): InstalledApp {
        val packageName = info.packageName
        val label = runCatching { packageManager.getApplicationLabel(info).toString() }
            .getOrDefault(packageName)
        val isLauncher = packageName == launcherPackage
        val isInputMethod = packageName == inputMethodPackage

        val dynamicCoreReason = when (packageName) {
            ownPackage -> "QuietShield Dormant leaves itself alone so it can keep working."
            launcherPackage -> "This app shows your Home screen. Your phone needs it."
            inputMethodPackage -> "This is your current keyboard. Your phone needs it for typing."
            dialerPackage -> "This is your current Phone app. QuietShield Dormant will leave it alone."
            smsPackage -> "This is your current Messages app. QuietShield Dormant will leave it alone."
            else -> null
        }

        val knownCore = CorePackageRules.isKnownCore(packageName)
        val section = when {
            dynamicCoreReason != null || knownCore -> AppSection.CORE
            info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 ->
                AppSection.SYSTEM
            else -> AppSection.USER
        }

        val reason = when (section) {
            AppSection.CORE -> dynamicCoreReason ?: CorePackageRules.reasonFor(packageName)
            AppSection.SYSTEM ->
                "This app came with your phone. Change it carefully."
            AppSection.USER -> "You installed this app. You can choose how QuietShield Dormant handles it."
        }

        return InstalledApp(
            packageName = packageName,
            label = label,
            section = section,
            safetyLevel = when (section) {
                AppSection.CORE -> SafetyLevel.LOCKED
                AppSection.SYSTEM -> SafetyLevel.CAUTION
                AppSection.USER -> SafetyLevel.STANDARD
            },
            classificationReason = reason,
            enabled = info.enabled,
            isCurrentLauncher = isLauncher,
            isCurrentInputMethod = isInputMethod,
        )
    }

    private fun resolveLauncherPackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return if (Build.VERSION.SDK_INT >= 33) {
            packageManager.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )?.activityInfo?.packageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }
    }

    private fun resolveInputMethodPackage(): String? {
        return Settings.Secure.getString(
            application.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        )?.substringBefore('/')
    }

    private fun resolveDialerPackage(): String? {
        val telecomManager = application.getSystemService(TelecomManager::class.java)
        return telecomManager?.defaultDialerPackage
    }
}
