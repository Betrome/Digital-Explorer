package com.betrome.proximitysaver

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile that switches Proximity Saver on and off. */
class ToggleTile : TileService() {
    override fun onStartListening() = update()

    override fun onClick() {
        val settings = Settings(this)
        val on = !settings.enabled
        settings.enabled = on
        val i = Intent(this, ProximityService::class.java)
        if (!on) {
            stopService(i)
        } else {
            // Some Android versions refuse a foreground-service start from a tile; the app then starts it on open.
            try {
                startForegroundService(i)
            } catch (e: IllegalStateException) {
                openApp()
            }
        }
        update()
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }

    private fun update() {
        val tile = qsTile ?: return
        tile.state = if (Settings(this).enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    companion object {
        /** Ask the system to redraw the tile, e.g. after the notification's "Turn off". */
        fun refresh(ctx: Context) = requestListeningState(ctx, ComponentName(ctx, ToggleTile::class.java))
    }
}
