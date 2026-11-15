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
 *
 * v1.0.35: every event is also mirrored to a file in the app's files
 * dir (see [PREVIOUS_SESSION_FILE]) the moment it is recorded
 * (append + close per event, so the write is flushed to the OS
 * immediately). A hard crash used to lose the whole log with the
 * process, so the v1.0.31 CAPTURE_START_FAILED breadcrumb could never
 * be read back on the next launch. Now the file survives the crash:
 * the next [start] reads it back, and the export carries it in a
 * "Previous session" section. A previous session that ended cleanly
 * wrote SESSION_END as its last line and is discarded, so only a
 * session cut short (crash, process kill) is surfaced.
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
        /** v1.0.37: a black run that persisted past
         * [DetectionResult.BLACK_FRAME_PERSISTENT_AFTER_FRAMES]
         * frames. The detail carries the frame it crossed the
         * threshold on, the run length, and whether the table was
         * locked just before the capture went black — the context
         * the next phone test needs to map WHEN FLAG_SECURE is on. */
        BLACK_FRAME_PERSISTENT,
        GUIDES_HIDDEN,
        GUIDES_SHOWN,
        EDIT_ENTER,
        EDIT_EXIT,
        /** v1.0.41, REA hardening R4: EDIT mode exited on its own
         * after 20 s without a handle drag (no Done tap). The
         * export can tell a forgotten session from a deliberate
         * Done (EDIT_EXIT). */
        EDIT_AUTO_EXIT,
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

    /** On-disk mirror of the current session's events (v1.0.35).
     * Lives in the app's files dir; callers attach it with
     * [attachFile] before [start]. One session's transcript, at most
     * [MAX_EVENTS] lines; the next [start] deletes it after reading
     * any previous-session lines back. */
    const val PREVIOUS_SESSION_FILE = "diagnostics-previous-session.txt"

    private val events = ArrayDeque<Event>()
    private var sessionStartMs: Long = 0L
    private var active = false

    /** Optional on-disk sink, set by [attachFile]. Null in unit tests
     * (and when no file was attached), so recording stays purely
     * in-memory there. */
    private var sinkFile: java.io.File? = null

    /** Lines carried over from a previous session that ended without
     * a clean stop (v1.0.35). Loaded by [start], rendered by
     * [buildReport]. */
    private val previousSessionLines = ArrayList<String>()

    /** Attach the on-disk mirror file. Pass null to detach (tests).
     * Must be called before [start] for the previous-session lines
     * to be read back; attaching after a session started only affects
     * recording from that point on. */
    @Synchronized
    fun attachFile(file: java.io.File?) {
        sinkFile = file
    }

    /** Lines from a previous session that ended without a clean stop
     * (v1.0.35). Empty when there is no such session, or when the
     * previous session ended cleanly. */
    @Synchronized
    fun previousSessionEvents(): List<String> = previousSessionLines.toList()

    /** Start a session. A second start while one is active is a no-op,
     * so CaptureService and OverlayService starting together cannot
     * wipe each other's first events. */
    @Synchronized
    fun start() {
        if (active) return
        previousSessionLines.clear()
        loadPreviousSession()
        events.clear()
        sessionStartMs = System.currentTimeMillis()
        active = true
        record(Kind.SESSION_START, "session started")
    }

    /** Read the previous session's transcript back from the sink file
     * (v1.0.35). A transcript whose last line is SESSION_END ended
     * cleanly and is discarded; anything else was cut short (crash,
     * process kill) and is kept for the report. The file is deleted
     * either way: the new session owns the transcript from here on.
     * File I/O failure is swallowed, the in-memory log still works. */
    private fun loadPreviousSession() {
        val file = sinkFile ?: return
        val lines = try {
            if (!file.exists()) return
            file.readLines().takeLast(MAX_EVENTS)
        } catch (_: Exception) {
            return
        }
        if (lines.lastOrNull()?.contains("] SESSION_END") != true) {
            previousSessionLines.addAll(lines)
        }
        try {
            file.delete()
        } catch (_: Exception) {
        }
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
        val event = Event(elapsed, kind, detail)
        events.addLast(event)
        // v1.0.35: mirror the event to the attached file the moment it
        // is recorded. appendText opens, writes and closes per event,
        // so the write is flushed to the OS immediately and survives a
        // hard crash right after. Disk failure is swallowed: the
        // in-memory log must never break because the disk did.
        val file = sinkFile
        if (file != null) {
            try {
                file.appendText(formatEvent(event) + "\n", Charsets.UTF_8)
            } catch (_: Exception) {
            }
        }
    }

    /** One event as it appears in the report and in the on-disk
     * mirror (v1.0.35). The loadPreviousSession clean-stop check
     * matches on "] SESSION_END", so this format is load-bearing. */
    private fun formatEvent(e: Event): String {
        val detail = if (e.detail.isEmpty()) "" else " ${e.detail}"
        return "[+${e.elapsedMs}ms] ${e.kind}$detail"
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
            sb.appendLine(formatEvent(e))
        }
        if (shots.isNotEmpty()) {
            sb.appendLine("---")
            sb.appendLine("Shots: ${shots.size}")
            for (s in shots) sb.appendLine(ShotHistory.format(s))
        }
        // v1.0.35: the previous session's transcript, when it ended
        // without a clean stop (crash, process kill). This is the
        // section that finally answers "what threw" after a hard
        // crash: the breadcrumb is on disk even though the process
        // died before anyone could export.
        if (previousSessionLines.isNotEmpty()) {
            sb.appendLine("---")
            sb.appendLine("Previous session (process ended without a clean stop):")
            for (line in previousSessionLines) sb.appendLine(line)
        }
        return sb.toString()
    }
}
