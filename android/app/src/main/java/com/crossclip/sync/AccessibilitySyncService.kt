package com.crossclip.sync

import android.accessibilityservice.AccessibilityService
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

                if (isCopy && !lastSelectedText.isNullOrEmpty()) {
                    SyncService.instance?.sendLocalClip(lastSelectedText!!)
                }
            }
        }
    }

    override fun onInterrupt() {}
}
