package com.example.myadblocker.tile

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.myadblocker.MainActivity
import com.example.myadblocker.vpn.AdBlockerState
import com.example.myadblocker.vpn.AdBlockerVpnService

class AdBlockerTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        if (AdBlockerState.isRunning.value) {
            AdBlockerVpnService.stop(this)
            updateTile(isActive = false)
        } else {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent == null) {
                AdBlockerVpnService.start(this)
                updateTile(isActive = true)
            } else {
                val activityIntent = Intent(this, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_REQUEST_VPN
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pendingIntent = PendingIntent.getActivity(
                        this,
                        0,
                        activityIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    startActivityAndCollapse(pendingIntent)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(activityIntent)
                }
            }
        }
    }

    private fun updateTileState() {
        updateTile(isActive = AdBlockerState.isRunning.value, isPaused = AdBlockerState.isPaused.value)
    }

    private fun updateTile(isActive: Boolean, isPaused: Boolean = false) {
        val tile = qsTile ?: return
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !isActive -> "停止中"
                isPaused -> "一時停止中"
                else -> "保護中"
            }
        }
        tile.updateTile()
    }
}
