package com.rishabh.clockdown

import android.app.Application

/** Loads the shared styling defaults before any screen, widget or receiver needs them. */
class ClockdownApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Defaults.init(this)
    }
}
