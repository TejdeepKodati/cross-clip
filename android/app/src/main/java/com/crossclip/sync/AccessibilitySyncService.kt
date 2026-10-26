package com.crossclip.sync

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AccessibilitySyncService : AccessibilityService() {

    companion object {
        var lastSelectedText: String? = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val source: AccessibilityNodeInfo? = event.source
                if (source != null && source.text != null) {
                    val full = source.text.toString()
                    val s = event.fromIndex
                    val e = event.toIndex
                    if (s in 0..e && e <= full.length) {
                        val sel = full.substring(s, e)
                        if (sel.isNotBlank()) {
                            lastSelectedText = sel
                            Log.d("CrossClip", "Accessibility tracked selection: ${sel.take(15)}...")
                        }
                    }
                    source.recycle()
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val text = event.text?.joinToString("") ?: ""
                val desc = event.contentDescription?.toString() ?: ""
                val res = event.source?.viewIdResourceName ?: ""

                val isCopy = text.equals("Copy", ignoreCase = true) ||
                        desc.equals("Copy", ignoreCase = true) ||
                        res.endsWith(":id/copy")

                if (isCopy) {
                    Log.d("CrossClip", "Copy button tapped! Selection available: ${!lastSelectedText.isNullOrEmpty()}")
                    if (!lastSelectedText.isNullOrEmpty()) {
                        SyncService.instance?.sendLocalClip(lastSelectedText!!)
                    }
                    // Also trigger the focus trampoline as reinforcement
                    launchTrampoline()
                }
            }
        }
    }

    private fun launchTrampoline() {
        val intent = Intent(this, TransparentActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        startActivity(intent)
    }

    override fun onInterrupt() {}
}
