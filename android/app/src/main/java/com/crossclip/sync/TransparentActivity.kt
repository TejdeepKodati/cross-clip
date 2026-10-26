package com.crossclip.sync

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View

class TransparentActivity : Activity() {

    private var hasHandled = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        val v = View(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        setContentView(v)

        handler.postDelayed({
            if (!hasHandled) {
                readAndClose("timeout")
            }
        }, 150)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasHandled) {
            readAndClose("focus")
        }
    }

    private fun readAndClose(source: String) {
        if (hasHandled) return
        hasHandled = true
        handler.removeCallbacksAndMessages(null)

        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).coerceToText(this).toString()
                    Log.d("CrossClip", "Read clipboard via $source: ${text.take(15)}...")
                    if (text.isNotBlank()) {
                        SyncService.instance?.sendLocalClip(text)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CrossClip", "Error in readAndClose", e)
        } finally {
            finishAndRemoveTask()
            overridePendingTransition(0, 0)
        }
    }
}
