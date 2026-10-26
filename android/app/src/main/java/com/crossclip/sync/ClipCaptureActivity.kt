package com.crossclip.sync

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * Invisible activity that reads the clipboard (Android allows this since we have window focus)
 * and immediately finishes. Launched via fullScreenIntent from SyncService when clipboard changes.
 * Uses no UI - transparent theme + no animations.
 */
class ClipCaptureActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No setContentView - we are fully transparent
    }

    override fun onResume() {
        super.onResume()
        Log.d("CrossClip", "ClipCaptureActivity onResume - reading clipboard with focus")

        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).coerceToText(this).toString()
                    Log.d("CrossClip", "ClipCaptureActivity got text: ${text.take(20)}")
                    if (text.isNotBlank()) {
                        SyncService.instance?.sendLocalClip(text)
                    }
                }
            } else {
                Log.d("CrossClip", "ClipCaptureActivity: no primary clip")
            }
        } catch (e: Exception) {
            Log.e("CrossClip", "ClipCaptureActivity clipboard read error", e)
        }

        // Dismiss immediately - user never sees this
        finish()
    }

    companion object {
        fun getLaunchIntent(context: Context): Intent {
            return Intent(context, ClipCaptureActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_HISTORY
            }
        }
    }
}
