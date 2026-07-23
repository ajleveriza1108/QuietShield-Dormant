package com.ajcoder.quietshield.dormant.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPolicySafetyTest {
    @Test
    fun userAppsAreLeftAloneUntilUserChoosesBehavior() {
        val app = InstalledApp(
            packageName = "com.example.app",
            label = "Example",
            section = AppSection.USER,
            safetyLevel = SafetyLevel.STANDARD,
            classificationReason = "Installed app",
            enabled = true,
        )

        assertEquals(SleepMode.PROTECTED, AppPolicy.defaultFor(app).sleepMode)
    }

    @Test
    fun systemAppsAreLeftAloneUntilUserChoosesBehavior() {
        val app = InstalledApp(
            packageName = "com.example.system",
            label = "Example System",
            section = AppSection.SYSTEM,
            safetyLevel = SafetyLevel.CAUTION,
            classificationReason = "Built-in app",
            enabled = true,
        )

        assertEquals(SleepMode.PROTECTED, AppPolicy.defaultFor(app).sleepMode)
    }
}
