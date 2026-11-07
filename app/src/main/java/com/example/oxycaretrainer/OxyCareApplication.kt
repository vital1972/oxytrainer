package com.example.oxycaretrainer

import android.app.Application

class OxyCareApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLogger.initialize(this)
        DiagnosticLogger.installCrashHandler()
        DiagnosticLogger.info("process_started", "process=${android.os.Process.myPid()}")
    }
}
