package org.syncloud.claudevoice

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class KeyService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false
        val p = getSharedPreferences("cv", MODE_PRIVATE)
        if (p.getString("trigger", "accessibility") != "accessibility") return false
        if (!p.getBoolean("running", false)) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) send(VoiceService.ACTION_TALK_START)
            }
            KeyEvent.ACTION_UP -> send(VoiceService.ACTION_TALK_STOP)
        }
        return true
    }

    private fun send(action: String) {
        try {
            startService(Intent(this, VoiceService::class.java).setAction(action))
        } catch (e: Exception) { /* service not running */ }
    }
}
