package com.dlwangsan.tapread

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object Prefs {
    private const val NAME = "tapread_prefs"
    private const val KEY_AUTO_READ = "auto_read_enabled"
    private const val KEY_SPEECH_RATE = "speech_rate"
    private const val KEY_OVERLAY_WANTED = "overlay_wanted"
    private const val KEY_CLIPBOARD_MONITOR_WANTED = "clipboard_monitor_wanted"

    /** Must land on Slider steps: valueFrom=0.5, stepSize=0.1 */
    const val DEFAULT_SPEECH_RATE = 1.0f

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    var autoReadEnabled: Boolean
        get() = sp.getBoolean(KEY_AUTO_READ, true)
        set(value) = sp.edit { putBoolean(KEY_AUTO_READ, value) }

    var speechRate: Float
        get() = snapSpeechRate(sp.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE))
        set(value) = sp.edit { putFloat(KEY_SPEECH_RATE, snapSpeechRate(value)) }

    var overlayWanted: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_WANTED, false)
        set(value) = sp.edit { putBoolean(KEY_OVERLAY_WANTED, value) }

    var clipboardMonitorWanted: Boolean
        get() = sp.getBoolean(KEY_CLIPBOARD_MONITOR_WANTED, false)
        set(value) = sp.edit { putBoolean(KEY_CLIPBOARD_MONITOR_WANTED, value) }

    fun snapSpeechRate(raw: Float): Float {
        val from = 0.5f
        val to = 1.8f
        val step = 0.1f
        val clamped = raw.coerceIn(from, to)
        val steps = ((clamped - from) / step).toInt()
        return from + steps * step
    }
}
