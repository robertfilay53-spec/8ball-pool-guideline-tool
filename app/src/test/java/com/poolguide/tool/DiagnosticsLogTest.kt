package com.poolguide.tool.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Local session diagnostics log (v1.0.6). Pure Kotlin, JVM-tested. */
class DiagnosticsLogTest {

    private var tempFile: File? = null

    @Before
    fun setUp() {
        DiagnosticsLog.end()
        DiagnosticsLog.start()
    }

    @After
    fun tearDown() {
        DiagnosticsLog.end()
        // v1.0.35: detach the sink so no test inherits another
        // test's mirror file, and delete the temp file.
        DiagnosticsLog.attachFile(null)
        tempFile?.delete()
        tempFile = null
    }

    /** A fresh temp file, attached as the sink, returned for asserts. */
    private fun attachTempFile(): File {
        val file = File.createTempFile("diagnostics", ".txt")
        tempFile = file
        DiagnosticsLog.attachFile(file)
        return file
    }

    @Test
    fun `start records a session start event`() {
        val events = DiagnosticsLog.snapshot()
        assertEquals(1, events.size)
        assertEquals(DiagnosticsLog.Kind.SESSION_START, events[0].kind)
        assertTrue(DiagnosticsLog.isActive())
    }

    @Test
    fun `a second start while active does not wipe the log`() {
        DiagnosticsLog.record(DiagnosticsLog.Kind.FRAME_STATS, "frame=0")
        DiagnosticsLog.start()
        assertEquals(2, DiagnosticsLog.eventCount())
        assertEquals(DiagnosticsLog.Kind.FRAME_STATS, DiagnosticsLog.snapshot()[1].kind)
    }

    @Test
    fun `recorded events keep their order and details`() {
        DiagnosticsLog.record(DiagnosticsLog.Kind.TABLE_LOCKED, "lock=1.00")
        DiagnosticsLog.record(DiagnosticsLog.Kind.BLACK_FRAME, "capture is black")
        val events = DiagnosticsLog.snapshot()
        assertEquals(3, events.size)
        assertEquals(DiagnosticsLog.Kind.TABLE_LOCKED, events[1].kind)
        assertEquals("lock=1.00", events[1].detail)
        assertEquals(DiagnosticsLog.Kind.BLACK_FRAME, events[2].kind)
        // Elapsed times never go backwards.
        assertTrue(events[1].elapsedMs >= events[0].elapsedMs)
        assertTrue(events[2].elapsedMs >= events[1].elapsedMs)
    }

    @Test
    fun `persistent black frame event records with its context`() {
        // v1.0.37, REA hardening R2: the persistent event carries the
        // frame it crossed the threshold on, the run length, and
        // whether the table was locked just before the capture went
        // black — the context the next phone test needs to map WHEN
        // FLAG_SECURE is on. The service builds the detail; the log
        // only has to carry the kind and the detail verbatim.
        DiagnosticsLog.record(
            DiagnosticsLog.Kind.BLACK_FRAME_PERSISTENT,
            "frame=1234 run=30 frames tableLockedBefore=true"
        )
        val events = DiagnosticsLog.snapshot()
        assertEquals(2, events.size)
        assertEquals(DiagnosticsLog.Kind.BLACK_FRAME_PERSISTENT, events[1].kind)
        assertEquals(
            "frame=1234 run=30 frames tableLockedBefore=true",
            events[1].detail
        )
    }

    @Test
    fun `ring buffer keeps only the last 500 events`() {
        repeat(600) { i ->
            DiagnosticsLog.record(DiagnosticsLog.Kind.FRAME_STATS, "frame=$i")
        }
        assertEquals(DiagnosticsLog.MAX_EVENTS, DiagnosticsLog.eventCount())
        val events = DiagnosticsLog.snapshot()
        // 601 events recorded (start + 600 frames), last 500 kept:
        // frames 100..599.
        assertEquals(DiagnosticsLog.Kind.FRAME_STATS, events[0].kind)
        assertEquals("frame=100", events[0].detail)
        assertEquals("frame=599", events.last().detail)
    }

    @Test
    fun `end records session end and further records are dropped`() {
        DiagnosticsLog.end()
        assertFalse(DiagnosticsLog.isActive())
        assertEquals(DiagnosticsLog.Kind.SESSION_END, DiagnosticsLog.snapshot().last().kind)
        val count = DiagnosticsLog.eventCount()
        DiagnosticsLog.record(DiagnosticsLog.Kind.FRAME_STATS, "frame=999")
        assertEquals(count, DiagnosticsLog.eventCount())
    }

    @Test
    fun `capture start failure breadcrumb records the failing call`() {
        // v1.0.31: the breadcrumb names the failing call, so the next
        // cloud-emulator run can say what threw. Numbers pinned by a
        // kotlinc probe before this test was written.
        DiagnosticsLog.record(
            DiagnosticsLog.Kind.CAPTURE_START_FAILED,
            "getMediaProjection: SecurityException: Media projection requires a valid token"
        )
        val events = DiagnosticsLog.snapshot()
        assertEquals(2, events.size)
        assertEquals(DiagnosticsLog.Kind.CAPTURE_START_FAILED, events.last().kind)
        assertEquals(
            "getMediaProjection: SecurityException: Media projection requires a valid token",
            events.last().detail
        )
    }

    @Test
    fun `capture start failure breadcrumb survives into the exported report`() {
        DiagnosticsLog.record(
            DiagnosticsLog.Kind.CAPTURE_START_FAILED,
            "getMediaProjection: SecurityException: Media projection requires a valid token"
        )
        val header = DiagnosticsLog.Header(
            appVersion = "1.0.31",
            appVersionCode = 32L,
            gameVersion = "56.35.0",
            deviceModel = "Test Device",
            androidVersion = "API 34 (14)",
            sessionStartIso = "2026-11-15T06:00:00+0500"
        )
        val report = DiagnosticsLog.buildReport(header)
        assertTrue(report.contains("Events: 2"))
        assertTrue(
            report.contains(
                "CAPTURE_START_FAILED getMediaProjection: SecurityException"
            )
        )
    }

    @Test
    fun `capture start failure is dropped with no active session`() {
        // Callers must start() first: MainActivity's failServiceStart
        // and CaptureService's startCapture both do. A record with no
        // session is dropped, like every other kind.
        DiagnosticsLog.end()
        val count = DiagnosticsLog.eventCount()
        DiagnosticsLog.record(
            DiagnosticsLog.Kind.CAPTURE_START_FAILED,
            "startForegroundService(CaptureService): IllegalStateException: x"
        )
        assertEquals(count, DiagnosticsLog.eventCount())
    }

    @Test
    fun `buildReport contains the header and every event`() {
        DiagnosticsLog.record(DiagnosticsLog.Kind.TABLE_LOCKED, "lock=1.00")
        DiagnosticsLog.record(DiagnosticsLog.Kind.GUIDES_HIDDEN)
        val header = DiagnosticsLog.Header(
            appVersion = "1.0.6",
            appVersionCode = 7L,
            gameVersion = "56.35.0",
            deviceModel = "Test Device",
            androidVersion = "API 34 (14)",
            sessionStartIso = "2026-11-14T09:00:00+0500"
        )
        val report = DiagnosticsLog.buildReport(header)
        assertTrue(report.contains("App version: 1.0.6 (7)"))
        assertTrue(report.contains("Game version: 56.35.0"))
        assertTrue(report.contains("Device: Test Device"))
        assertTrue(report.contains("Android: API 34 (14)"))
        assertTrue(report.contains("Session start: 2026-11-14T09:00:00+0500"))
        assertTrue(report.contains("Events: 3"))
        assertTrue(report.contains("SESSION_START"))
        assertTrue(report.contains("TABLE_LOCKED lock=1.00"))
        assertTrue(report.contains("GUIDES_HIDDEN"))
    }

    // ---- v1.0.35: on-disk persistence ----

    @Test
    fun `recorded events are appended to the attached file synchronously`() {
        // End the setUp session first: the file must see the whole
        // session from its SESSION_START on.
        DiagnosticsLog.end()
        val file = attachTempFile()
        DiagnosticsLog.start()
        DiagnosticsLog.record(
            DiagnosticsLog.Kind.CAPTURE_START_FAILED,
            "startForeground: SecurityException: boom"
        )
        // No end(), no flush call: the write must already be on disk,
        // because a hard crash right after this point is the case the
        // mirror exists for.
        val text = file.readText()
        assertTrue(text.contains("CAPTURE_START_FAILED startForeground: SecurityException: boom"))
        assertTrue(text.contains("SESSION_START"))
    }

    @Test
    fun `previous session lines are loaded on the next start`() {
        // A transcript cut short (no SESSION_END): the crashed run.
        // Written directly, standing in for the file a real crash
        // leaves behind. The setUp session ends first, so its
        // SESSION_END is not appended to the simulated transcript.
        DiagnosticsLog.end()
        val file = attachTempFile()
        file.writeText(
            "[+0ms] SESSION_START session started\n" +
                "[+10ms] CAPTURE_START_FAILED startForeground: SecurityException: boom\n"
        )
        DiagnosticsLog.start()
        val prev = DiagnosticsLog.previousSessionEvents()
        assertEquals(2, prev.size)
        assertTrue(prev[1].contains("CAPTURE_START_FAILED startForeground: SecurityException: boom"))
        // The new session owns a fresh transcript now: the old lines
        // are gone from the file, only the new SESSION_START remains.
        assertFalse(file.readText().contains("SecurityException"))
        assertTrue(file.readText().contains("SESSION_START"))
    }

    @Test
    fun `previous session is discarded when it ended cleanly`() {
        DiagnosticsLog.end()
        val file = attachTempFile()
        file.writeText(
            "[+0ms] SESSION_START session started\n" +
                "[+10ms] CAPTURE_START_FAILED startForeground: SecurityException: boom\n" +
                "[+20ms] SESSION_END session ended\n"
        )
        DiagnosticsLog.start()
        assertTrue(DiagnosticsLog.previousSessionEvents().isEmpty())
    }

    @Test
    fun `exported report includes the previous session section`() {
        DiagnosticsLog.end()
        val file = attachTempFile()
        file.writeText(
            "[+0ms] SESSION_START session started\n" +
                "[+10ms] CAPTURE_START_FAILED startForeground: SecurityException: boom\n"
        )
        DiagnosticsLog.start()
        val header = DiagnosticsLog.Header(
            appVersion = "1.0.35",
            appVersionCode = 36L,
            gameVersion = "56.35.0",
            deviceModel = "Test Device",
            androidVersion = "API 34 (14)",
            sessionStartIso = "2026-11-15T08:00:00+0500"
        )
        val report = DiagnosticsLog.buildReport(header)
        assertTrue(report.contains("Previous session (process ended without a clean stop):"))
        assertTrue(report.contains("CAPTURE_START_FAILED startForeground: SecurityException: boom"))
    }

    @Test
    fun `exported report omits the previous session section when there is none`() {
        val header = DiagnosticsLog.Header(
            appVersion = "1.0.35",
            appVersionCode = 36L,
            gameVersion = "56.35.0",
            deviceModel = "Test Device",
            androidVersion = "API 34 (14)",
            sessionStartIso = "2026-11-15T08:00:00+0500"
        )
        assertFalse(DiagnosticsLog.buildReport(header).contains("Previous session"))
    }

    @Test
    fun `recording never breaks when the attached file is unwritable`() {
        // A sink under a directory that does not exist: every append
        // fails, and recording must carry on in memory regardless.
        DiagnosticsLog.attachFile(File("/nonexistent-dir-xyz/f.txt"))
        DiagnosticsLog.record(DiagnosticsLog.Kind.TABLE_LOCKED, "lock=1.00")
        assertTrue(DiagnosticsLog.snapshot().any { it.kind == DiagnosticsLog.Kind.TABLE_LOCKED })
    }
}
