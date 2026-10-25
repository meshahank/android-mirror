package com.shahan.androidmirror.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.shahan.androidmirror.protocol.Control

class RemoteControlService : AccessibilityService() {
    companion object { @Volatile var instance: RemoteControlService? = null }
    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    fun execute(c: Control, width: Int, height: Int): Boolean = when (c.kind) {
        "global" -> performGlobalAction(when (c.action) { "home" -> GLOBAL_ACTION_HOME; "recents" -> GLOBAL_ACTION_RECENTS; else -> GLOBAL_ACTION_BACK })
        "tap", "swipe", "longPress", "scroll" -> {
            val path = Path().apply { moveTo((c.x ?: .5f) * width, (c.y ?: .5f) * height); if (c.kind != "tap" && c.kind != "longPress") lineTo((c.endX ?: c.x ?: .5f) * width, (c.endY ?: c.y ?: .5f) * height) }
            dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, c.durationMs ?: if (c.kind == "longPress") 650 else 80)).build(), null, null)
        }
        else -> false
    }
}
