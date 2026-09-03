package com.dlwangsan.tapread.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.dlwangsan.tapread.ClipboardHelper
import com.dlwangsan.tapread.MainActivity
import com.dlwangsan.tapread.Prefs
import com.dlwangsan.tapread.R
import com.dlwangsan.tapread.TtsManager
import kotlin.math.abs

class OverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startAsForeground()
        showOverlay()
        Prefs.overlayWanted = true
        isRunning = true
    }

    override fun onDestroy() {
        removeOverlay()
        Prefs.overlayWanted = false
        isRunning = false
        super.onDestroy()
    }

    private fun startAsForeground() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.ic_speaker)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.overlay_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.view_overlay_ball, null)
        overlayView = view

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 48
            y = 360
        }

        val ball = view.findViewById<ImageButton>(R.id.btnBall)
        setupTouch(ball)
        ball.setOnClickListener { onBallClicked() }

        windowManager?.addView(view, params)
    }

    private fun setupTouch(ball: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        ball.setOnTouchListener { _, event ->
            val lp = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    moved = false
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    lp.x = startX + dx
                    lp.y = startY + dy
                    windowManager?.updateViewLayout(overlayView, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) ball.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun onBallClicked() {
        if (TtsManager.isSpeaking()) {
            TtsManager.stop()
            vibrateLight()
            return
        }
        // Android 10+ only allows clipboard reads for the focused app.
        // Temporarily drop NOT_FOCUSABLE so this overlay can read clipboard.
        withFocusForClipboard {
            val text = ClipboardHelper.readText(this)
            if (text.isNullOrBlank()) {
                Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
                return@withFocusForClipboard
            }
            val ok = TtsManager.speak(text)
            if (ok) {
                vibrateLight()
            } else {
                Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun withFocusForClipboard(block: () -> Unit) {
        val view = overlayView
        val lp = params
        val wm = windowManager
        if (view == null || lp == null || wm == null) {
            block()
            return
        }
        val originalFlags = lp.flags
        lp.flags = originalFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        runCatching { wm.updateViewLayout(view, lp) }
        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.post {
            try {
                block()
            } finally {
                lp.flags = originalFlags
                runCatching { wm.updateViewLayout(view, lp) }
            }
        }
    }

    private fun vibrateLight() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VibratorManager::class.java)
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {
        }
    }

    private fun removeOverlay() {
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
    }

    companion object {
        const val CHANNEL_ID = "tapread_overlay"
        const val NOTIFICATION_ID = 1001
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
