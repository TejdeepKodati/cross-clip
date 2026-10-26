package com.crossclip.sync

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle

class TransparentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).coerceToText(this).toString()
                    if (text.isNotBlank()) {
                        SyncService.instance?.sendLocalClip(text)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            finish()
            overridePendingTransition(0, 0)
        }
    }
}
