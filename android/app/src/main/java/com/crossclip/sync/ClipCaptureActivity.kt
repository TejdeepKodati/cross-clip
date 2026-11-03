package com.crossclip.sync

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

class ClipCaptureActivity : Activity() {

    private var hasCaptured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasCaptured) {
            hasCaptured = true
            readAndFinish()
        }
    }

    override fun onResume() {
        super.onResume()
        Handler(Looper.getMainLooper()).postDelayed({
            if (!hasCaptured && !isFinishing) {
                hasCaptured = true
                readAndFinish()
            }
        }, 50)
    }

    private fun readAndFinish() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0)?.coerceToText(this)?.toString()
                if (!text.isNullOrEmpty()) {
                    Log.d("CrossClip", "ClipCaptureActivity read: ${text.take(20)}...")
                    SyncService.instance?.sendLocalClip(text)
                }
            }
        } catch (e: Exception) {
            Log.e("CrossClip", "Error in ClipCaptureActivity", e)
        } finally {
            finish()
            overridePendingTransition(0, 0)
        }
    }
}
