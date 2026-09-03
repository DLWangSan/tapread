package com.dlwangsan.tapread.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import com.dlwangsan.tapread.ClipboardHelper
import com.dlwangsan.tapread.Prefs
import com.dlwangsan.tapread.TextCleaner
import com.dlwangsan.tapread.TtsManager

/**
 * Android 10+ 复制即读：
 * 1) 监听剪贴板变化
 * 2) 监听“已复制”等无障碍事件
 * 3) 通过 TYPE_ACCESSIBILITY_OVERLAY 短暂获焦后读取剪贴板（绕过后台剪贴板限制）
 */
class TapReadAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastSpoken: String? = null
    private var lastSpokenAt: Long = 0L
    private var lastScheduleAt: Long = 0L
    private var reading = false

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        Log.i(TAG, "clipboard listener fired")
        scheduleClipboardRead("clip-listener")
    }

    private val readRunnable = Runnable { readClipboardAndSpeak() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        configureServiceInfo()
        Prefs.init(applicationContext)
        TtsManager.init(applicationContext)

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        runCatching { clipboard.addPrimaryClipChangedListener(clipboardListener) }
            .onFailure { Log.e(TAG, "addPrimaryClipChangedListener failed", it) }

        Log.i(TAG, "accessibility service connected, autoRead=${Prefs.autoReadEnabled}")
    }

    private fun configureServiceInfo() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                AccessibilityEvent.TYPE_ANNOUNCEMENT or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 50
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !Prefs.autoReadEnabled) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
            AccessibilityEvent.TYPE_ANNOUNCEMENT -> {
                val text = eventText(event)
                if (looksLikeCopyFeedback(text)) {
                    Log.i(TAG, "copy-like announcement: $text")
                    scheduleClipboardRead("announce")
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val text = eventText(event)
                val desc = event.contentDescription?.toString().orEmpty()
                if (looksLikeCopyAction(text) || looksLikeCopyAction(desc)) {
                    Log.i(TAG, "copy-like click: text=$text desc=$desc")
                    scheduleClipboardRead("click", delayMs = 180)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val text = eventText(event)
                if (looksLikeCopyFeedback(text) || looksLikeCopyAction(text)) {
                    scheduleClipboardRead("window", delayMs = 180)
                }
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // Weak signal only; many apps fire this while selecting before copy.
            }
        }
    }

    override fun onInterrupt() {
        TtsManager.stop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(readRunnable)
        runCatching {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.removePrimaryClipChangedListener(clipboardListener)
        }
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun scheduleClipboardRead(reason: String, delayMs: Long = 120) {
        if (!Prefs.autoReadEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastScheduleAt < 250) return
        lastScheduleAt = now
        Log.i(TAG, "schedule read reason=$reason delay=$delayMs")
        handler.removeCallbacks(readRunnable)
        handler.postDelayed(readRunnable, delayMs)
    }

    private fun readClipboardAndSpeak() {
        if (!Prefs.autoReadEnabled || reading) return
        reading = true
        try {
            val direct = ClipboardHelper.readText(this)
            if (!direct.isNullOrBlank()) {
                speakIfNeeded(direct, "direct")
                return
            }
            readViaAccessibilityOverlay()
        } finally {
            // overlay path clears reading itself; for direct path clear now
            if (overlayView == null) reading = false
        }
    }

    private var overlayView: View? = null

    private fun readViaAccessibilityOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = FrameLayout(this).apply {
            // 1px focusable overlay — enough to satisfy clipboard focus check.
            isFocusable = true
            isFocusableInTouchMode = true
        }
        overlayView = view

        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            wm.addView(view, params)
            view.requestFocus()
            handler.postDelayed({
                try {
                    val text = ClipboardHelper.readText(this)
                    Log.i(TAG, "overlay read length=${text?.length ?: -1}")
                    if (!text.isNullOrBlank()) {
                        speakIfNeeded(text, "overlay")
                    }
                } finally {
                    runCatching { wm.removeView(view) }
                    overlayView = null
                    reading = false
                }
            }, 80)
        } catch (t: Throwable) {
            Log.e(TAG, "accessibility overlay failed", t)
            overlayView = null
            reading = false
        }
    }

    private fun speakIfNeeded(raw: String, source: String) {
        if (TextCleaner.shouldSkipAutoRead(raw)) {
            Log.i(TAG, "skip text from $source")
            return
        }
        val cleaned = TextCleaner.clean(raw)
        val now = System.currentTimeMillis()
        if (cleaned == lastSpoken && now - lastSpokenAt < 1500) return
        lastSpoken = cleaned
        lastSpokenAt = now
        Log.i(TAG, "speak from $source: ${cleaned.take(40)}")
        TtsManager.init(applicationContext)
        TtsManager.speak(cleaned)
    }

    private fun eventText(event: AccessibilityEvent): String {
        val parts = mutableListOf<String>()
        event.text?.forEach { parts += it?.toString().orEmpty() }
        event.contentDescription?.let { parts += it.toString() }
        return parts.joinToString(" ").trim()
    }

    private fun looksLikeCopyFeedback(text: String): Boolean {
        if (text.isBlank()) return false
        val t = text.lowercase()
        return COPY_FEEDBACK.any { t.contains(it) }
    }

    private fun looksLikeCopyAction(text: String): Boolean {
        if (text.isBlank()) return false
        val t = text.trim().lowercase()
        return COPY_ACTIONS.any { t == it || t.contains(it) }
    }

    companion object {
        private const val TAG = "TapReadA11y"

        private val COPY_FEEDBACK = listOf(
            "已复制", "复制成功", "copied", "copy to clipboard", "copied to clipboard", "已拷贝"
        )
        private val COPY_ACTIONS = listOf(
            "复制", "拷贝", "copy", "copy text", "复制文字", "复制链接"
        )

        @Volatile
        var instance: TapReadAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
