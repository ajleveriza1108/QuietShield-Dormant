package com.ajcoder.quietshield.dormant.domain

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.provider.AlarmClock
import android.view.accessibility.AccessibilityManager

/**
 * Builds a conservative safety snapshot. Hard-protected packages are never sent
 * to the privileged command engine. Recommended packages remain user-controlled but
 * begin with a clear warning and a Leave this app alone default.
 */
class SafetyAdvisor(private val application: Application) {
    data class Snapshot(
        val hardProtected: Set<String>,
        val recommendedProtection: Map<String, String>,
    )

    fun snapshot(): Snapshot {
        val hard = mutableSetOf<String>()
        val recommended = mutableMapOf<String, String>()

        val accessibility = application.getSystemService(AccessibilityManager::class.java)
        accessibility?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.mapNotNullTo(hard) { it.resolveInfo?.serviceInfo?.packageName }

        val devicePolicy = application.getSystemService(DevicePolicyManager::class.java)
        devicePolicy?.activeAdmins
            ?.mapTo(hard) { it.packageName }

        queryServicePackages(VpnService.SERVICE_INTERFACE).forEach { packageName ->
            recommended.putIfAbsent(
                packageName,
                "This app can protect network traffic. Closing it may disconnect its protection.",
            )
        }

        queryActivityPackages(Intent(AlarmClock.ACTION_SHOW_ALARMS)).forEach { packageName ->
            recommended.putIfAbsent(
                packageName,
                "This app may provide alarms. Closing it may delay an alarm.",
            )
        }

        return Snapshot(hardProtected = hard, recommendedProtection = recommended)
    }

    fun heuristicProtectionReason(label: String, packageName: String): String? {
        val text = "$label $packageName".lowercase()
        val keywordGroups = listOf(
            listOf("bank", "banking") to "This may be a banking app. Leaving it alone is safer.",
            listOf("wallet", "payment", "paypal", "paymaya", "gcash") to
                "This may handle payments. Leaving it alone is safer.",
            listOf("authenticator", "authy", "otp", "token") to
                "This may provide sign-in codes. Leaving it alone is safer.",
            listOf("password", "vault", "bitwarden", "1password", "lastpass") to
                "This may store passwords. Leaving it alone is safer.",
            listOf("alarm", "clock") to
                "This may provide alarms or reminders. Leaving it alone is safer.",
        )
        return keywordGroups.firstOrNull { (keywords, _) -> keywords.any(text::contains) }?.second
    }

    private fun queryServicePackages(action: String): Set<String> {
        val intent = Intent(action)
        val services = if (Build.VERSION.SDK_INT >= 33) {
            application.packageManager.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            application.packageManager.queryIntentServices(intent, PackageManager.MATCH_ALL)
        }
        return services.mapNotNullTo(mutableSetOf()) { it.serviceInfo?.packageName }
    }

    private fun queryActivityPackages(intent: Intent): Set<String> {
        val activities = if (Build.VERSION.SDK_INT >= 33) {
            application.packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            application.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        return activities.mapNotNullTo(mutableSetOf()) { it.activityInfo?.packageName }
    }
}
