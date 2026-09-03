package com.dlwangsan.tapread.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.dlwangsan.tapread.ClipboardHelper
import com.dlwangsan.tapread.Prefs
import com.dlwangsan.tapread.TextCleaner
import com.dlwangsan.tapread.TtsManager

class TapReadAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastSpoken: String? = null
    private var lastSpokenAt: Long = 0L

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        handler.post { onClipboardChanged() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        TtsManager.init(applicationContext)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener(clipboardListener)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Clipboard listener is the primary trigger. Events are declared for service validity.
    }

    override fun onInterrupt() {
        TtsManager.stop()
    }

    override fun onDestroy() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        runCatching { clipboard.removePrimaryClipChangedListener(clipboardListener) }
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun onClipboardChanged() {
        if (!Prefs.autoReadEnabled) return
        val text = ClipboardHelper.readText(this) ?: return
        if (TextCleaner.shouldSkipAutoRead(text)) return

        val cleaned = TextCleaner.clean(text)
        val now = System.currentTimeMillis()
        if (cleaned == lastSpoken && now - lastSpokenAt < 1500) return

        lastSpoken = cleaned
        lastSpokenAt = now
        TtsManager.speak(cleaned)
    }

    companion object {
        @Volatile
        var instance: TapReadAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
