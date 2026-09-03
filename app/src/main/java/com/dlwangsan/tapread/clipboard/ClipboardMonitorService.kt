package com.dlwangsan.tapread.clipboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.dlwangsan.tapread.ClipboardHelper
import com.dlwangsan.tapread.DeviceCapabilities
import com.dlwangsan.tapread.MainActivity
import com.dlwangsan.tapread.Prefs
import com.dlwangsan.tapread.R
import com.dlwangsan.tapread.TextCleaner
import com.dlwangsan.tapread.TtsManager

/**
 * Android 9 及以下：前台服务直接监听剪贴板，复制即朗读。
 * Android 10+ 不应启动此服务（系统会拒绝后台读剪贴板）。
 */
class ClipboardMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastSpoken: String? = null
    private var lastSpokenAt: Long = 0L

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        handler.post { onClipboardChanged() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!DeviceCapabilities.canBackgroundClipboardRead) {
            stopSelf()
            return
        }
        createChannel()
        startAsForeground()
        TtsManager.init(applicationContext)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener(clipboardListener)
        Prefs.clipboardMonitorWanted = true
        isRunning = true
    }

    override fun onDestroy() {
        runCatching {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.removePrimaryClipChangedListener(clipboardListener)
        }
        Prefs.clipboardMonitorWanted = false
        isRunning = false
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

    private fun startAsForeground() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.clipboard_monitor_notification_title))
            .setContentText(getString(R.string.clipboard_monitor_notification_text))
            .setSmallIcon(R.drawable.ic_speaker)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.clipboard_monitor_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "tapread_clipboard"
        const val NOTIFICATION_ID = 1002

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            if (!DeviceCapabilities.canBackgroundClipboardRead) return
            context.startForegroundService(Intent(context, ClipboardMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ClipboardMonitorService::class.java))
        }
    }
}
