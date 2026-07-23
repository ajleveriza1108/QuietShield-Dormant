package com.ajcoder.quietshield.dormant.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.Toast
import com.ajcoder.quietshield.dormant.R
import com.ajcoder.quietshield.dormant.service.DormantQuickTileService

object DormantQuickTileRequest {
    fun addTile(context: Context) {
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(StatusBarManager::class.java)
            manager?.requestAddTileService(
                ComponentName(context, DormantQuickTileService::class.java),
                "Dormant",
                Icon.createWithResource(context, R.drawable.ic_quick_dormant),
                context.mainExecutor,
            ) { }
        } else {
            Toast.makeText(
                context,
                "Swipe down, tap Edit, then add the Dormant button.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun requestTileRefresh(context: Context) {
        TileService.requestListeningState(
            context,
            ComponentName(context, DormantQuickTileService::class.java),
        )
    }
}
