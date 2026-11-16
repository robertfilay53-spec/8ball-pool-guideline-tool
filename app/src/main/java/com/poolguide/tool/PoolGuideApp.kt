package com.poolguide.tool

import android.app.Application
import com.poolguide.tool.data.DiagnosticsLog
import java.io.File

/**
 * Application entry point (v1.0.68): installs the global uncaught-
 * exception handler before any activity or service runs, so a crash
 * anywhere — including a service constructor, which runs before
 * onStartCommand and outside every try/catch — leaves a crash record
 * on disk that Export diagnostics can show on the next launch.
 * Without this, a crash before the first session left no trace at
 * all: the Start-capture / Demo crash died before DiagnosticsLog
 * ever started, so "Export diagnostics" only ever said "No session
 * yet".
 */
class PoolGuideApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticsLog.attachCrashFile(File(filesDir, DiagnosticsLog.CRASH_FILE))
        DiagnosticsLog.installCrashHandler()
    }
}
