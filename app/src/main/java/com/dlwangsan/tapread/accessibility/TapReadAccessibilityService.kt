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
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import com.dlwangsan.tapread.ClipboardHelper
import com.dlwangsan.tapread.Prefs
import com.dlwangsan.tapread.TextCleaner
import com.dlwangsan.tapread.TtsManager

/**
 * 只在剪贴板内容真正变化后朗读，避免选中文字弹出「复制」菜单时误读上一次内容。
 */
class TapReadAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastSpoken: String? = null
    private var lastSpokenAt: Long = 0L
    private var lastScheduleAt: Long = 0L
    private var lastKnownClipboard: String? = null
    private var clipboardSnapshotReady = false
    private var reading = false
    private var overlayView: View? = null

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        Log.i(TAG, "clipboard listener fired")
        scheduleClipboardRead("clip-listener", delayMs = 80)
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

        // Baseline current clipboard so we never speak stale content on first false trigger.
        handler.postDelayed({
            snapshotClipboardBaseline()
        }, 200)

        Log.i(TAG, "accessibility service connected, autoRead=${Prefs.autoReadEnabled}")
    }

    private fun configureServiceInfo() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                AccessibilityEvent.TYPE_ANNOUNCEMENT or
                AccessibilityEvent.TYPE_VIEW_CLICKED
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
                    Log.i(TAG, "copy feedback: $text")
                    scheduleClipboardRead("announce", delayMs = 120)
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // Only when the clicked node itself is a Copy action — not when menu is merely shown.
                if (isCopyActionNode(event)) {
                    Log.i(TAG, "copy action clicked")
                    scheduleClipboardRead("copy-click", delayMs = 280)
                }
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
        removeOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun snapshotClipboardBaseline() {
        readClipboardRaw { text ->
            lastKnownClipboard = text?.let { TextCleaner.clean(it) }
            clipboardSnapshotReady = true
            Log.i(TAG, "baseline clipboard len=${lastKnownClipboard?.length ?: -1}")
        }
    }

    private fun scheduleClipboardRead(reason: String, delayMs: Long) {
        if (!Prefs.autoReadEnabled) return
        val now = System.currentTimeMillis()
        // Allow copy-click / clip-listener to supersede an earlier schedule.
        if (now - lastScheduleAt < 80 && reason == "announce") return
        lastScheduleAt = now
        Log.i(TAG, "schedule read reason=$reason delay=$delayMs")
        handler.removeCallbacks(readRunnable)
        handler.postDelayed(readRunnable, delayMs)
    }

    private fun readClipboardAndSpeak() {
        if (!Prefs.autoReadEnabled || reading) return
        reading = true
        readClipboardRaw { text ->
            try {
                if (text.isNullOrBlank()) return@readClipboardRaw
                val cleaned = TextCleaner.clean(text)
                if (cleaned.isBlank()) return@readClipboardRaw

                if (!clipboardSnapshotReady) {
                    lastKnownClipboard = cleaned
                    clipboardSnapshotReady = true
                    Log.i(TAG, "skip speak before baseline")
                    return@readClipboardRaw
                }

                if (cleaned == lastKnownClipboard) {
                    Log.i(TAG, "clipboard unchanged, skip speak")
                    return@readClipboardRaw
                }

                lastKnownClipboard = cleaned
                speakIfNeeded(cleaned)
            } finally {
                reading = false
            }
        }
    }

    private fun readClipboardRaw(onResult: (String?) -> Unit) {
        val direct = ClipboardHelper.readText(this)
        if (!direct.isNullOrBlank()) {
            onResult(direct)
            return
        }
        readViaAccessibilityOverlay(onResult)
    }

    private fun readViaAccessibilityOverlay(onResult: (String?) -> Unit) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        removeOverlay()
        val view = FrameLayout(this).apply {
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
                val text = ClipboardHelper.readText(this)
                removeOverlay()
                onResult(text)
            }, 80)
        } catch (t: Throwable) {
            Log.e(TAG, "accessibility overlay failed", t)
            removeOverlay()
            onResult(null)
        }
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        overlayView = null
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        }
    }

    private fun speakIfNeeded(cleaned: String) {
        if (TextCleaner.shouldSkipAutoRead(cleaned)) {
            Log.i(TAG, "skip text by filter")
            return
        }
        val now = System.currentTimeMillis()
        if (cleaned == lastSpoken && now - lastSpokenAt < 1200) return
        lastSpoken = cleaned
        lastSpokenAt = now
        Log.i(TAG, "speak: ${cleaned.take(40)}")
        TtsManager.init(applicationContext)
        TtsManager.speak(cleaned)
    }

    private fun isCopyActionNode(event: AccessibilityEvent): Boolean {
        val source = event.source
        try {
            if (source != null && nodeLooksLikeCopyAction(source)) return true
        } finally {
            source?.recycle()
        }

        // Fallback: event text/contentDescription themselves are exactly copy actions.
        val label = buildString {
            event.text?.forEach { append(it?.toString().orEmpty()).append(' ') }
            event.contentDescription?.let { append(it) }
        }.trim()
        return isExactCopyLabel(label)
    }

    private fun nodeLooksLikeCopyAction(node: AccessibilityNodeInfo): Boolean {
        val candidates = listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString() else null,
            node.viewIdResourceName
        )
        if (candidates.any { isExactCopyLabel(it) || isCopyViewId(it) }) return true

        // Some OEMs put "复制" on a child TextView.
        for (i in 0 until node.childCount.coerceAtMost(6)) {
            val child = node.getChild(i) ?: continue
            try {
                val childText = child.text?.toString().orEmpty()
                val childDesc = child.contentDescription?.toString().orEmpty()
                if (isExactCopyLabel(childText) || isExactCopyLabel(childDesc)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    private fun isExactCopyLabel(raw: String): Boolean {
        val t = raw.trim().lowercase()
        if (t.isEmpty()) return false
        return EXACT_COPY_LABELS.any { t == it }
    }

    private fun isCopyViewId(raw: String): Boolean {
        val t = raw.lowercase()
        return t.contains(":id/copy") || t.endsWith("/copy") || t.contains("copy_btn")
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

    companion object {
        private const val TAG = "TapReadA11y"

        private val COPY_FEEDBACK = listOf(
            "已复制", "复制成功", "copied to clipboard", "copied", "已拷贝"
        )

        private val EXACT_COPY_LABELS = listOf(
            "复制", "拷贝", "copy", "copy text", "复制文字", "复制链接", "复制全部"
        )

        @Volatile
        var instance: TapReadAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
