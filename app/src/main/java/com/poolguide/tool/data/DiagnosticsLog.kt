package com.poolguide.tool.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local session diagnostics log (v1.0.6).
 *
 * Records what the tool saw during a live or demo session — detection
 * states, frame stats, table-lock events, fallback usage, black-frame /
 * FLAG_SECURE warnings, and overlay state changes — into a small ring
 * buffer (last 500 events) held in memory only. Nothing is sent
 * anywhere: the user exports the report to a file of their choosing
 * from MainActivity ("Export diagnostics") and decides whether to
 * share it. This exists so a real-device test can be debugged after
 * the fact, when the live state is gone.
 *
 * Pure Kotlin + JDK only (no Android dependency), so it is fully
 * unit-testable on the JVM.
 */
object DiagnosticsLog {

    /** What happened, in session order. */
    enum class Kind {
        SESSION_START,
        SESSION_END,
        FRAME_STATS,
        TABLE_LOCKED,
        TABLE_LOST,
        FALLBACK_START,
        FALLBACK_END,
        BLACK_FRAME,
        BLACK_FRAME_CLEARED,
        GUIDES_HIDDEN,
        GUIDES_SHOWN,
        EDIT_ENTER,
        EDIT_EXIT,
        /** v1.0.31: a post-confirm capture-startup step threw
         * (getMediaProjection / createVirtualDisplay / CvPipeline
         * init). The detail names the failing call, so the next
         * cloud-emulator run can say what threw. */
        CAPTURE_START_FAILED
    }

    /** One recorded event. [elapsedMs] is milliseconds since [start]. */
    data class Event(val elapsedMs: Long, val kind: Kind, val detail: String)

    /** The report header: what build, what game, what device. */
    data class Header(
        val appVersion: String,
        val appVersionCode: Long,
        val gameVersion: String,
        val deviceModel: String,
        val androidVersion: String,
        val sessionStartIso: String
    )

    const val MAX_EVENTS = 500

    private val events = ArrayDeque<Event>()
    private var sessionStartMs: Long = 0L
    private var active = false

    /** Start a session. A second start while one is active is a no-op,
     * so CaptureService and OverlayService starting together cannot
     * wipe each other's first events. */
    @Synchronized
    fun start() {
        if (active) return
        events.clear()
        sessionStartMs = System.currentTimeMillis()
        active = true
        record(Kind.SESSION_START, "session started")
    }

    /** End the current session. Events are kept so the user can still
     * export them afterwards; a later [start] clears them. */
    @Synchronized
    fun end() {
        if (!active) return
        record(Kind.SESSION_END, "session ended")
        active = false
    }

    @Synchronized
    fun record(kind: Kind, detail: String = "") {
        if (!active && kind != Kind.SESSION_START) return
        val elapsed = System.currentTimeMillis() - sessionStartMs
        if (events.size >= MAX_EVENTS) events.removeFirst()
        events.addLast(Event(elapsed, kind, detail))
    }

    @Synchronized
    fun snapshot(): List<Event> = events.toList()

    @Synchronized
    fun eventCount(): Int = events.size

    @Synchronized
    fun isActive(): Boolean = active

    /** ISO-8601 session start for the report header. */
    @Synchronized
    fun sessionStartIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
            .format(Date(sessionStartMs))

    /** Full plain-text report: header, then one line per event,
     * then the shot history when any shots were recorded (the strip
     * MainActivity shows live, so the file carries the same record
     * after the fact). */
    @Synchronized
    fun buildReport(header: Header, shots: List<ShotHistory.ShotEntry> = emptyList()): String {
        val sb = StringBuilder()
        sb.appendLine("Pool Guide diagnostics report")
        sb.appendLine("App version: ${header.appVersion} (${header.appVersionCode})")
        sb.appendLine("Game version: ${header.gameVersion}")
        sb.appendLine("Device: ${header.deviceModel}")
        sb.appendLine("Android: ${header.androidVersion}")
        sb.appendLine("Session start: ${header.sessionStartIso}")
        sb.appendLine("Events: ${events.size}")
        sb.appendLine("---")
        for (e in events) {
            val detail = if (e.detail.isEmpty()) "" else " ${e.detail}"
            sb.appendLine("[+${e.elapsedMs}ms] ${e.kind}$detail")
        }
        if (shots.isNotEmpty()) {
            sb.appendLine("---")
            sb.appendLine("Shots: ${shots.size}")
            for (s in shots) sb.appendLine(ShotHistory.format(s))
        }
        return sb.toString()
    }
}
