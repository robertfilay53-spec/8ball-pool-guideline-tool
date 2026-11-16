package com.poolguide.tool.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Uncaught-crash persistence (v1.0.68). Before the fix a crash
 * before the first session left no trace at all: the process died
 * before DiagnosticsLog ever started, so "Export diagnostics" only
 * ever said "No session yet" after the very crash the user was
 * trying to report. The global handler writes the stack trace to
 * the crash file, and the next launch carries it into the export. */
class CrashDiagnosticsTest {

    private var tempFile: File? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        DiagnosticsLog.end()
        // The log is a shared singleton: a previous test's events
        // would otherwise still be in it, and the gate test below
        // needs a genuinely empty log.
        DiagnosticsLog.resetForTests()
        tempFile = File.createTempFile("crash", ".txt")
        DiagnosticsLog.attachCrashFile(tempFile)
        DiagnosticsLog.installCrashHandler()
    }

    @After
    fun tearDown() {
        DiagnosticsLog.end()
        DiagnosticsLog.resetForTests()
        DiagnosticsLog.attachFile(null)
        DiagnosticsLog.attachCrashFile(null)
        DiagnosticsLog.resetCrashHandlerForTests()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        tempFile?.delete()
        tempFile = null
    }

    private fun header() = DiagnosticsLog.Header(
        appVersion = "1.0.67",
        appVersionCode = 68,
        gameVersion = "56.35.0",
        deviceModel = "Test Device",
        androidVersion = "API 34 (14)",
        sessionStartIso = "2026-11-16T09:00:00+0500"
    )

    @Test
    fun `crash record survives a relaunch and appears in the export`() {
        DiagnosticsLog.recordCrash(
            Thread.currentThread(),
            RuntimeException("boom")
        )
        val text = tempFile!!.readText()
        assertTrue(text.contains("RuntimeException: boom"))
        assertTrue(text.contains("CRASH"))

        // Simulate the next launch: the record is read back, and the
        // export carries it even though no session ever started.
        DiagnosticsLog.loadPreviousCrash()
        assertTrue(DiagnosticsLog.hasPreviousCrash())
        val report = DiagnosticsLog.buildReport(header())
        assertTrue(report.contains("Previous crash"))
        assertTrue(report.contains("RuntimeException: boom"))

        // Starting a session consumes the file, so the crash is shown
        // exactly once — but the report still carries the lines.
        DiagnosticsLog.start()
        assertFalse(tempFile!!.exists())
        val afterStart = DiagnosticsLog.buildReport(header())
        assertTrue(afterStart.contains("RuntimeException: boom"))
    }

    @Test
    fun `export gate opens when only a crash record exists`() {
        // This is the exact condition MainActivity's export gate
        // checks: no session events, no previous session, but a crash
        // record waiting. Before the fix the gate stayed shut here.
        DiagnosticsLog.recordCrash(
            Thread.currentThread(),
            IllegalStateException("demo-crash")
        )
        DiagnosticsLog.loadPreviousCrash()
        assertEquals(0, DiagnosticsLog.eventCount())
        assertTrue(DiagnosticsLog.previousSessionEvents().isEmpty())
        assertTrue(DiagnosticsLog.hasPreviousCrash())
    }

    @Test
    fun `uncaught handler records the throw and chains to the previous handler`() {
        val chained = AtomicBoolean(false)
        DiagnosticsLog.resetCrashHandlerForTests()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> chained.set(true) }
        DiagnosticsLog.installCrashHandler()

        val t = Thread { throw IllegalStateException("demo-crash") }
        t.start()
        t.join()

        assertTrue("previous handler must still be invoked", chained.get())
        val text = tempFile!!.readText()
        assertTrue(text.contains("IllegalStateException: demo-crash"))
        // The stack trace is the useful part: at least one frame.
        assertTrue(text.contains("    at "))
    }

    @Test
    fun `recordCrash without a file attached is a silent no-op`() {
        DiagnosticsLog.attachCrashFile(null)
        // Must not throw: the handler calls this on every crash.
        DiagnosticsLog.recordCrash(
            Thread.currentThread(),
            RuntimeException("boom")
        )
    }

    @Test
    fun `recordCrash never throws on a broken sink`() {
        DiagnosticsLog.attachCrashFile(File("/nonexistent-dir-xyz/crash.txt"))
        // The append fails, but the recorder must swallow it — a
        // throwing crash recorder would mask the real crash.
        DiagnosticsLog.recordCrash(
            Thread.currentThread(),
            RuntimeException("boom")
        )
    }
}
