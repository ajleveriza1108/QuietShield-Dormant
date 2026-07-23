package com.ajcoder.quietshield.dormant.domain

enum class AppSection(val title: String) {
    USER("User Apps"),
    SYSTEM("System Apps"),
    CORE("Core Apps"),
}

enum class SafetyLevel {
    STANDARD,
    CAUTION,
    RECOMMENDED_PROTECTION,
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
    val protectionReason: String? = null,
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
        "Wait while the app is doing useful work, then continue its timer.",
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

enum class AutoAggressiveMode(val label: String, val description: String) {
    OFF(
        "Off",
        "Do not look for apps that repeatedly keep themselves active.",
    ),
    SUGGEST(
        "Suggest only",
        "Show a suggestion and let you decide. This is the recommended choice.",
    ),
    AUTO_APPLY(
        "Apply automatically",
        "Automatically use Close sooner for eligible User Apps that repeatedly misbehave.",
    ),
}

enum class AppRuntimeState(val label: String) {
    OPEN_NOW("Open now"),
    PLAYING_MEDIA("Playing media"),
    WORKING_IN_BACKGROUND("Working in background"),
    KEPT_READY("Kept ready by Android"),
    SLEEPING("Sleeping"),
    CLOSED("Closed"),
    NOT_RUNNING("Not running"),
}

data class AggressiveSuggestion(
    val packageName: String,
    val reason: String,
    val score: Int,
)

data class AppPolicy(
    val packageName: String,
    val sleepMode: SleepMode = SleepMode.PROTECTED,
    val backgroundTimeoutMinutes: Int = 10,
    val inactiveTimeoutMinutes: Int = 30,
    val syncMode: SyncMode = SyncMode.SMART,
    val mediaProtection: Boolean = true,
    val aggressive: Boolean = false,
    val neverSuggestAggressive: Boolean = false,
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
