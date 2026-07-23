package com.ajcoder.quietshield.dormant.domain

data class AggressiveSignals(
    val restartAfterCloseCount: Int,
    val backgroundMinutes: Long,
    val activeServiceSamples: Int,
    val overnightSamples: Int,
    val lastSignalAt: Long,
)

object AggressiveAdvisor {
    private const val THREE_DAYS_MS = 3L * 24L * 60L * 60L * 1_000L

    fun evaluate(
        packageName: String,
        section: AppSection,
        policy: AppPolicy,
        signals: AggressiveSignals,
        now: Long,
    ): AggressiveSuggestion? {
        if (section != AppSection.USER) return null
        if (policy.sleepMode == SleepMode.PROTECTED || policy.neverSuggestAggressive) return null
        if (signals.lastSignalAt <= 0L || now - signals.lastSignalAt > THREE_DAYS_MS) return null

        val restartScore = (signals.restartAfterCloseCount * 25).coerceAtMost(75)
        val backgroundScore = (signals.backgroundMinutes / 30L).toInt().coerceAtMost(35)
        val serviceScore = (signals.activeServiceSamples / 4).coerceAtMost(25)
        val overnightScore = (signals.overnightSamples * 5).coerceAtMost(20)
        val score = (restartScore + backgroundScore + serviceScore + overnightScore).coerceAtMost(100)
        if (score < 55) return null

        val reason = when {
            signals.restartAfterCloseCount >= 3 ->
                "It returned ${signals.restartAfterCloseCount} times after being closed."
            signals.overnightSamples >= 4 ->
                "It repeatedly stayed active while the screen was off."
            signals.backgroundMinutes >= 120L ->
                "It stayed active in the background for ${signals.backgroundMinutes} minutes."
            else -> "It repeatedly kept working after you left it."
        }
        return AggressiveSuggestion(packageName, reason, score)
    }
}
