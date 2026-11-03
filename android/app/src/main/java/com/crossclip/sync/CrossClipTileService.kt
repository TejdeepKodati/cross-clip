package com.crossclip.sync

import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class CrossClipTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences(SyncService.PREFS_NAME, Context.MODE_PRIVATE)
        val isPaused = prefs.getBoolean(SyncService.KEY_PAUSED, false)
        val newPaused = !isPaused

        prefs.edit().putBoolean(SyncService.KEY_PAUSED, newPaused).apply()
        SyncService.instance?.setPaused(newPaused)

        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val prefs = getSharedPreferences(SyncService.PREFS_NAME, Context.MODE_PRIVATE)
        val isPaused = prefs.getBoolean(SyncService.KEY_PAUSED, false)

        if (isPaused) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "cross-clip (Paused)"
        } else {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "cross-clip (Active)"
        }
        tile.updateTile()
    }
}
