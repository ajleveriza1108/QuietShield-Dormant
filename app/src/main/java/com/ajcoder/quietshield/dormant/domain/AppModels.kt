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
        "Standby → Force Stop",
        "Place the app in standby, then force-stop it after the inactive timeout.",
    ),
    STANDBY_ONLY(
        "Standby Only",
        "Restrict background activity without force-stopping the app.",
    ),
    FORCE_STOP_ONLY(
        "Force Stop Only",
        "Skip standby and force-stop the app after the background timeout.",
    ),
    PROTECTED(
        "Protected",
        "Never manage this app automatically.",
    ),
}

enum class SyncMode(val label: String) {
    ALLOW("Allow Sync"),
    SMART("Smart Sync"),
    BLOCK("No Background Sync"),
}

enum class ThemeChoice(val label: String) {
    AMOLED("Dark AMOLED"),
    OLED("Dark OLED"),
    DIRTY_WHITE("Dirty White"),
    SYSTEM("Follow System"),
}

data class AppPolicy(
    val packageName: String,
    val sleepMode: SleepMode = SleepMode.STANDBY_THEN_FORCE_STOP,
    val backgroundTimeoutMinutes: Int = 10,
    val inactiveTimeoutMinutes: Int = 30,
    val syncMode: SyncMode = SyncMode.SMART,
    val mediaProtection: Boolean = true,
    val aggressive: Boolean = false,
) {
    companion object {
        fun defaultFor(app: InstalledApp): AppPolicy = when (app.section) {
            AppSection.CORE -> AppPolicy(
                packageName = app.packageName,
                sleepMode = SleepMode.PROTECTED,
                syncMode = SyncMode.ALLOW,
                mediaProtection = true,
            )
            AppSection.SYSTEM -> AppPolicy(
                packageName = app.packageName,
                sleepMode = SleepMode.STANDBY_ONLY,
                backgroundTimeoutMinutes = 30,
                inactiveTimeoutMinutes = 60,
                syncMode = SyncMode.SMART,
                mediaProtection = true,
            )
            AppSection.USER -> AppPolicy(packageName = app.packageName)
        }
    }
}
