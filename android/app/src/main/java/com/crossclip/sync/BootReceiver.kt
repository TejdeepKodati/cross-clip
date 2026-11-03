package com.crossclip.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = context.getSharedPreferences(SyncService.PREFS_NAME, Context.MODE_PRIVATE)
            val secret = prefs.getString(SyncService.KEY_SECRET, null)
            val isPaused = prefs.getBoolean(SyncService.KEY_PAUSED, false)

            if (!secret.isNullOrEmpty() && !isPaused) {
                val serviceIntent = Intent(context, SyncService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
