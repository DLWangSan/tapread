package com.dlwangsan.tapread

import android.app.Application

class TapReadApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        // Don't init TTS here: some OEMs fail when binding from Application.
        // MainActivity / services will init when needed.
    }

    companion object {
        lateinit var instance: TapReadApp
            private set
    }
}
