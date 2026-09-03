package com.dlwangsan.tapread

import android.app.Application

class TapReadApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        TtsManager.init(this)
    }

    companion object {
        lateinit var instance: TapReadApp
            private set
    }
}
