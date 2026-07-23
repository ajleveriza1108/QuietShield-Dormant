package com.ajcoder.quietshield.dormant.service

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.ajcoder.quietshield.dormant.data.PolicyRepository
import com.ajcoder.quietshield.dormant.engine.DormantEngineClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DormantQuickTileService : TileService() {
    private var scope = newScope()
    private var updateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        if (scope.coroutineContext[Job]?.isActive != true) scope = newScope()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        updateJob?.cancel()
        updateJob = scope.launch {
            val repository = PolicyRepository(applicationContext)
            val engineAvailable = DormantEngineClient(applicationContext).ping()
            val ready = engineAvailable && hasUsageAccess()
            val enabled = repository.automaticClosing.first()

            when {
                !ready -> {
                    repository.setAutomaticClosing(false)
                    DormantMonitorService.stop(applicationContext)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            applicationContext,
                            "Open QuietShield Dormant and finish setup.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                enabled -> {
                    repository.setAutomaticClosing(false)
                    DormantMonitorService.stop(applicationContext)
                }
                else -> {
                    repository.setAutomaticClosing(true)
                    DormantMonitorService.start(applicationContext)
                }
            }

            withContext(Dispatchers.Main) {
                updateTileState(ready, !enabled && ready)
            }
        }
    }

    override fun onStopListening() {
        updateJob?.cancel()
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshTile() {
        updateJob?.cancel()
        updateJob = scope.launch {
            val repository = PolicyRepository(applicationContext)
            val enabled = repository.automaticClosing.first()
            val ready = DormantEngineClient(applicationContext).ping() &&
                hasUsageAccess()
            withContext(Dispatchers.Main) {
                updateTileState(ready, enabled && ready)
            }
        }
    }

    private fun updateTileState(ready: Boolean, enabled: Boolean) {
        val tile = qsTile ?: return
        tile.state = when {
            !ready -> Tile.STATE_UNAVAILABLE
            enabled -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "Dormant"
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                !ready -> "Setup needed"
                enabled -> "On"
                else -> "Paused"
            }
        }
        tile.contentDescription = when {
            !ready -> "QuietShield Dormant setup needed"
            enabled -> "QuietShield Dormant automatic closing on"
            else -> "QuietShield Dormant automatic closing paused"
        }
        tile.updateTile()
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java) ?: return false
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }


    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
