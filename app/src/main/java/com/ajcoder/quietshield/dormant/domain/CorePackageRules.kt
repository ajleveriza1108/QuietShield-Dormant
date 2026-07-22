package com.ajcoder.quietshield.dormant.domain

object CorePackageRules {
    private val exactCorePackages = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.providers.media",
        "com.android.providers.downloads",
        "com.android.providers.downloads.ui",
        "com.android.shell",
        "com.android.phone",
        "com.android.server.telecom",
        "com.google.android.gms",
        "com.google.android.gsf",
    )

    private val protectedPrefixes = listOf(
        "com.android.internal.",
        "com.android.systemui.",
        "com.android.permission.",
    )

    fun isKnownCore(packageName: String): Boolean {
        return packageName in exactCorePackages || protectedPrefixes.any(packageName::startsWith)
    }

    fun reasonFor(packageName: String): String = when (packageName) {
        "android" -> "Android framework package required by the operating system."
        "com.android.systemui" -> "Controls notifications, status bar, navigation, and system dialogs."
        "com.android.settings" -> "Provides the device Settings interface."
        "com.android.permissioncontroller", "com.google.android.permissioncontroller" ->
            "Controls runtime permissions and privacy decisions."
        "com.android.packageinstaller", "com.google.android.packageinstaller" ->
            "Installs and updates application packages."
        "com.android.providers.downloads", "com.android.providers.downloads.ui" ->
            "Provides Android's shared download service."
        "com.android.providers.media" -> "Indexes and serves shared photos, audio, and video."
        "com.android.phone", "com.android.server.telecom" ->
            "Provides calling and telephony services."
        "com.google.android.gms" ->
            "Provides core Google services used by notifications, accounts, location, and many apps."
        else -> "Protected because it matches a known Android core component rule."
    }
}
