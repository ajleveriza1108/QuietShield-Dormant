package com.ajcoder.quietshield.dormant.domain

enum class AppSection(val title: String) {
    USER("User Apps"),
    SYSTEM("System Apps"),
    CORE("Core Apps"),
}

enum class SafetyLevel {
    STANDARD,
    CAUTION,
    LOCKED,
}

data class InstalledApp(
    val packageName: String,
    val label: String,
    val section: AppSection,
    val safetyLevel: SafetyLevel,
    val classificationReason: String,
    val enabled: Boolean,
    val isCurrentLauncher: Boolean = false,
    val isCurrentInputMethod: Boolean = false,
)

enum class SleepMode(val label: String, val description: String) {
    STANDBY_THEN_FORCE_STOP(
        "Sleep, then close",
        "Reduce background activity first, then close the app after the second timer.",
    ),
    STANDBY_ONLY(
        "Sleep only",
        "Reduce background activity without closing the app.",
    ),
    FORCE_STOP_ONLY(
        "Close only",
        "Close the app after the first timer without putting it to sleep first.",
    ),
    PROTECTED(
        "Leave this app alone",
        "QuietShield Dormant will never manage this app automatically.",
    ),
}

enum class SyncMode(val label: String, val description: String) {
    ALLOW(
        "Always let it work",
        "Best for messages, email, backups, and apps that must stay updated.",
    ),
    SMART(
        "Let it work when needed",
        "Give it extra time before closing it. Playing audio is always protected.",
    ),
    BLOCK(
        "Do not let it work",
        "Best for games and apps that do not need alerts or updates.",
    ),
}

enum class ThemeChoice(val label: String) {
    AMOLED("Dark AMOLED"),
    OLED("Dark OLED"),
    DIRTY_WHITE("Dirty White"),
    SYSTEM("Follow System"),
}

data class AppPolicy(
    val packageName: String,
    val sleepMode: SleepMode = SleepMode.PROTECTED,
    val backgroundTimeoutMinutes: Int = 10,
    val inactiveTimeoutMinutes: Int = 30,
    val syncMode: SyncMode = SyncMode.SMART,
    val mediaProtection: Boolean = true,
    val aggressive: Boolean = false,
) {
    companion object {
        fun defaultFor(app: InstalledApp): AppPolicy = AppPolicy(
            packageName = app.packageName,
            sleepMode = SleepMode.PROTECTED,
            backgroundTimeoutMinutes = if (app.section == AppSection.SYSTEM) 30 else 10,
            inactiveTimeoutMinutes = if (app.section == AppSection.SYSTEM) 60 else 30,
            syncMode = if (app.section == AppSection.CORE) SyncMode.ALLOW else SyncMode.SMART,
            mediaProtection = true,
        )
    }
}
