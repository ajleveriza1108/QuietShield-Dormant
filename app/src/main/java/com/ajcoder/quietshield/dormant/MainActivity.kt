package com.ajcoder.quietshield.dormant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.ajcoder.quietshield.dormant.ui.QuietShieldDormantApp
import com.ajcoder.quietshield.dormant.ui.QuietShieldViewModel

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: QuietShieldViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[QuietShieldViewModel::class.java]
        setContent {
            QuietShieldDormantApp(viewModel)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::viewModel.isInitialized) {
            viewModel.refreshPermissionState()
        }
    }
}
