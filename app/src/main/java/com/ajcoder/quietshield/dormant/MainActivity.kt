package com.ajcoder.quietshield.dormant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import com.ajcoder.quietshield.dormant.ui.QuietShieldDormantApp
import com.ajcoder.quietshield.dormant.ui.QuietShieldViewModel

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: QuietShieldViewModel
    private var activateAfterNotificationPermission = false
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        if (activateAfterNotificationPermission) {
            activateAfterNotificationPermission = false
            viewModel.activateAfterSetup()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[QuietShieldViewModel::class.java]
        setContent {
            QuietShieldDormantApp(viewModel)
        }
        handleStartRequest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleStartRequest(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::viewModel.isInitialized) {
            viewModel.refreshPermissionState()
        }
    }

    private fun handleStartRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START_AUTOMATIC, false) != true) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            activateAfterNotificationPermission = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.activateAfterSetup()
        }
        intent.removeExtra(EXTRA_START_AUTOMATIC)
    }

    companion object {
        const val EXTRA_START_AUTOMATIC = "start_automatic_closing"
    }
}
