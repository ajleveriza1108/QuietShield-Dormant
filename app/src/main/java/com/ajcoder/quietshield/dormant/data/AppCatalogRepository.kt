package com.ajcoder.quietshield.dormant.data

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import com.ajcoder.quietshield.dormant.domain.AppClassifier
import com.ajcoder.quietshield.dormant.domain.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppCatalogRepository(private val application: Application) {
    private val classifier = AppClassifier(application)

    suspend fun loadInstalledApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val packageManager = application.packageManager
        val apps = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(
                    PackageManager.MATCH_DISABLED_COMPONENTS.toLong(),
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
        }

        apps.asSequence()
            .map(classifier::classify)
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }
}
