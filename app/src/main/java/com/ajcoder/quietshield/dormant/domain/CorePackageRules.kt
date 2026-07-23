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
        "com.android.providers.settings",
        "com.android.providers.telephony",
        "com.android.externalstorage",
        "com.android.documentsui",
        "com.android.keychain",
        "com.android.inputdevices",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.networkstack",
        "com.google.android.networkstack",
        "com.android.shell",
        "com.android.phone",
        "com.android.server.telecom",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.modulemetadata",
    )

    private val protectedPrefixes = listOf(
        "com.android.internal.",
        "com.android.systemui.",
        "com.android.permission.",
        "com.android.networkstack.",
        "com.google.android.networkstack.",
    )

    fun isKnownCore(packageName: String): Boolean {
        return packageName in exactCorePackages || protectedPrefixes.any(packageName::startsWith)
    }

    fun reasonFor(packageName: String): String = when (packageName) {
        "android" -> "Your phone needs this app to run Android."
        "com.android.systemui" -> "This app shows notifications, the status bar, and phone controls."
        "com.android.settings" -> "This app opens and manages your phone settings."
        "com.android.permissioncontroller", "com.google.android.permissioncontroller" ->
            "This app controls permission and privacy choices."
        "com.android.packageinstaller", "com.google.android.packageinstaller" ->
            "This app installs and updates other apps."
        "com.android.providers.downloads", "com.android.providers.downloads.ui" ->
            "This app handles downloads for your phone."
        "com.android.providers.media" -> "This app helps your phone find photos, music, and videos."
        "com.android.phone", "com.android.server.telecom" ->
            "This app handles calls and mobile service."
        "com.google.android.gms" ->
            "Many apps need this for alerts, accounts, location, and sign-in."
        else -> "Your phone needs this app, so QuietShield Dormant will always leave it alone."
    }
}
