package com.ajcoder.quietshield.dormant.domain

fun formatMinutes(minutes: Int): String {
    return if (minutes == 1) "1 minute" else "$minutes minutes"
}

fun policySummary(policy: AppPolicy): String = when (policy.sleepMode) {
    SleepMode.STANDBY_THEN_FORCE_STOP ->
        "Sleeps after ${formatMinutes(policy.backgroundTimeoutMinutes)} · closes ${formatMinutes(policy.inactiveTimeoutMinutes)} later"
    SleepMode.STANDBY_ONLY ->
        "Sleeps after ${formatMinutes(policy.backgroundTimeoutMinutes)}"
    SleepMode.FORCE_STOP_ONLY ->
        "Closes after ${formatMinutes(policy.backgroundTimeoutMinutes)}"
    SleepMode.PROTECTED -> "Always left alone"
}
