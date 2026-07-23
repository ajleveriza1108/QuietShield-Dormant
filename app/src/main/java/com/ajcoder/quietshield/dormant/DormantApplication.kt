package com.ajcoder.quietshield.dormant

import android.app.Application
import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.PRNGFixes
import org.lsposed.hiddenapibypass.HiddenApiBypass

class DormantApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }
    }

    override fun onCreate() {
        super.onCreate()
        PRNGFixes.apply()
    }
}
