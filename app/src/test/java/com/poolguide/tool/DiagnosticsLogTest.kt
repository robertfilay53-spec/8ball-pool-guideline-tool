package com.poolguide.tool.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Local session diagnostics log (v1.0.6). Pure Kotlin, JVM-tested. */
class DiagnosticsLogTest {

    @Before
    fun setUp() {
        DiagnosticsLog.end()
        DiagnosticsLog.start()
    }

    @After
    fun tearDown() {
        DiagnosticsLog.end()
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
}
