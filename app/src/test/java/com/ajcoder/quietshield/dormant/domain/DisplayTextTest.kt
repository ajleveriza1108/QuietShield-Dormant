package com.ajcoder.quietshield.dormant.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayTextTest {
    @Test
    fun oneMinuteUsesSingularGrammar() {
        assertEquals("1 minute", formatMinutes(1))
    }

    @Test
    fun multipleMinutesUsePluralGrammar() {
        assertEquals("2 minutes", formatMinutes(2))
        assertEquals("60 minutes", formatMinutes(60))
    }

    @Test
    fun closeOnlySummaryUsesFriendlyWording() {
        val policy = AppPolicy(
            packageName = "example",
            sleepMode = SleepMode.FORCE_STOP_ONLY,
            backgroundTimeoutMinutes = 1,
        )
        assertEquals("Closes after 1 minute", policySummary(policy))
    }
}
