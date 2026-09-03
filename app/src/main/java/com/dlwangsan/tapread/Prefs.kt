package com.dlwangsan.tapread

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object Prefs {
    private const val NAME = "tapread_prefs"
    private const val KEY_AUTO_READ = "auto_read_enabled"
    private const val KEY_SPEECH_RATE = "speech_rate"
    private const val KEY_OVERLAY_WANTED = "overlay_wanted"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    var autoReadEnabled: Boolean
        get() = sp.getBoolean(KEY_AUTO_READ, true)
        set(value) = sp.edit { putBoolean(KEY_AUTO_READ, value) }

    var speechRate: Float
        get() = sp.getFloat(KEY_SPEECH_RATE, 0.95f)
        set(value) = sp.edit { putFloat(KEY_SPEECH_RATE, value) }

    var overlayWanted: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_WANTED, false)
        set(value) = sp.edit { putBoolean(KEY_OVERLAY_WANTED, value) }
}
