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
            ownPackage -> "QuietShield Dormant protects itself so management remains available."
            launcherPackage -> "This is the current Home launcher. Disabling it can remove the Home screen."
            inputMethodPackage -> "This is the current keyboard. Disabling it can prevent text input."
            dialerPackage -> "This is the current default phone app."
            smsPackage -> "This is the current default SMS app."
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
                "Preinstalled or manufacturer-updated app. Review carefully before changing its policy."
            AppSection.USER -> "Installed user app. Eligible for user-selected background policies."
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
