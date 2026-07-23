package com.ajcoder.quietshield.dormant.domain

import android.content.Context
import android.os.Build
import android.os.PowerManager

data class CompatibilityCheck(
    val title: String,
    val ready: Boolean,
    val message: String,
)

data class CompatibilityReport(
    val deviceSummary: String,
    val checks: List<CompatibilityCheck>,
    val phoneTip: String,
)

object CompatibilityAdvisor {
    fun create(
        context: Context,
        helperReady: Boolean,
        usageReady: Boolean,
        pairingSaved: Boolean,
    ): CompatibilityReport {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val unrestricted = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        val maker = Build.MANUFACTURER.orEmpty().trim().ifBlank { "Android" }
        val model = Build.MODEL.orEmpty().trim()
        val tip = when (maker.lowercase()) {
            "samsung" -> "On Samsung, keep QuietShield Dormant out of Sleeping apps if Android pauses it unexpectedly."
            "xiaomi", "redmi", "poco" -> "On this phone, allow Auto-start and choose No restrictions if Dormant stops unexpectedly."
            "oppo", "realme", "oneplus" -> "Allow Dormant to run in the background if the phone closes it unexpectedly."
            "vivo", "iqoo" -> "Allow background activity and Auto-start if Dormant stops unexpectedly."
            "motorola" -> "Use Unrestricted battery use only if Dormant stops unexpectedly."
            "google" -> "The default battery setting should normally work. Use Unrestricted only after a failed test."
            else -> "Keep the default battery setting first. Change it only if Dormant stops unexpectedly."
        }
        return CompatibilityReport(
            deviceSummary = "$maker $model · Android ${Build.VERSION.RELEASE}",
            checks = listOf(
                CompatibilityCheck("App activity access", usageReady, if (usageReady) "Ready" else "Needs setup"),
                CompatibilityCheck("Wireless pairing", pairingSaved, if (pairingSaved) "Saved" else "Not paired"),
                CompatibilityCheck("Automatic closing", helperReady, if (helperReady) "Ready" else "Needs restoration"),
                CompatibilityCheck(
                    "Battery setting",
                    true,
                    if (unrestricted) "Unrestricted" else "Using the phone's normal setting",
                ),
            ),
            phoneTip = tip,
        )
    }
}
