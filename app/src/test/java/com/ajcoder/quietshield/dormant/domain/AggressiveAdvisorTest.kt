package com.ajcoder.quietshield.dormant.domain

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AggressiveAdvisorTest {
    @Test
    fun repeatedRestartCreatesSuggestionForUserApp() {
        val now = 10_000_000L
        val suggestion = AggressiveAdvisor.evaluate(
            packageName = "example.app",
            section = AppSection.USER,
            policy = AppPolicy("example.app", sleepMode = SleepMode.FORCE_STOP_ONLY),
            signals = AggressiveSignals(
                restartAfterCloseCount = 3,
                backgroundMinutes = 20,
                activeServiceSamples = 0,
                overnightSamples = 0,
                lastSignalAt = now,
            ),
            now = now,
        )
        assertNotNull(suggestion)
    }

    @Test
    fun coreAndProtectedAppsAreNeverSuggested() {
        val signals = AggressiveSignals(10, 500, 100, 20, 1_000L)
        assertNull(
            AggressiveAdvisor.evaluate(
                "android",
                AppSection.CORE,
                AppPolicy("android", sleepMode = SleepMode.FORCE_STOP_ONLY),
                signals,
                1_000L,
            ),
        )
        assertNull(
            AggressiveAdvisor.evaluate(
                "example.app",
                AppSection.USER,
                AppPolicy("example.app", sleepMode = SleepMode.PROTECTED),
                signals,
                1_000L,
            ),
        )
    }
}
