package com.companyname.appname

import android.app.Application

class AudioCutterApp : Application() {

    companion object {
        var instance: AudioCutterApp? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ErrorLog.init(this)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                ErrorLog.logException(this, "CRASH", throwable)
            } catch (_: Exception) {}
            TelegramReporter.sendException("CRASH", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
