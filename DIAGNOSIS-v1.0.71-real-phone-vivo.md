# Diagnosis: v1.0.70 fails on the real phone (Vivo Y03) — why, with code evidence

Date: 2026-11-17. Phone: Vivo Y03 (verified specs: MediaTek Helio G85,
Android 14 / Funtouch OS 14, 4 GB RAM, 6.56" 720x1612 display, 90 Hz).
Correction to the task brief: the SoC is the Helio G85, not the P35
(vivo.com + Gadgets360).

Symptoms (Jawad, Nov 17): v1.0.69 showed NO lines at all, and the side
logo (the control bubble) appeared for a few seconds then disappeared.
v1.0.70 fails the same way on the same phone.

## Ranked root causes

### R1 (most likely): the process is killed after Jawad leaves the app, and the services never come back

Evidence in code:

- The bubble is removed only by `LineOverlayManager.removeAll()`, which
  is called only from `OverlayService.stopOverlay()` (service stop /
  destroy). The 8 s auto-collapse (`BUBBLE_MENU_AUTO_COLLAPSE_MS`) applies
  ONLY to the expanded menu window, never to the bubble itself. So "logo
  appeared for a few seconds then disappeared" is NOT any designed
  behaviour: it is the service stopping or the process dying.
- Both services return `START_STICKY`, but `onStartCommand` with a null
  intent (the system restart after a kill) returned `START_NOT_STICKY`
  and restarted nothing. Even when Android restarts the service, the
  bubble never returned and capture never resumed.
- A capture-side failure has the same visible symptom: any
  `failCaptureStart` calls `stopSelf()`, whose `onDestroy()` runs
  `stopCapture()`, which sends `ACTION_STOP` to the OverlayService, so
  the bubble vanishes as a side effect of a capture failure. The logo
  disappearing therefore tells us the process/service died, not which
  half failed first.
- Vivo Funtouch OS 14 aggressively kills background apps (background
  power-consumption management, auto-start control, recents swipe kill)
  unless the app is whitelisted. The Y03 has 4 GB RAM; 8 Ball Pool plus
  OpenCV plus two foreground services plus MediaProjection is exactly
  the memory-pressure shape the low-memory killer targets.
- Nothing in the app asked for the battery-optimisation exemption
  (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` /
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), and nothing told the
  user to whitelist the app or lock it in recents.

Fix (v1.0.71): a persisted session-active flag plus null-intent recovery
in both services (the overlay re-shows the bubble; the capture service
explains the MediaProjection token is gone and asks for a re-tap), a
battery-optimisation exemption request flow in MainActivity, Vivo setup
guidance in the README, and new diagnostics events so the next failure
leaves a readable trail.

### R2: an uncaught WindowManager failure on the main thread kills the process

Evidence: `LineOverlayManager.applyLines/applyLabels/applyTags` call
`windowManager.addView/updateViewLayout` with NO try/catch, and
`ensureBubble`'s `addView` is also unwrapped. The drag handler and
`healBubblePosition` DO wrap these calls, so the pattern exists, it just
is not applied to the per-frame window ops. A `SecurityException` /
`BadTokenException` (overlay permission revoked mid-session by Vivo, or
a stale window token) on the main thread is uncaught, so the process
dies, the bubble vanishes, and there are no lines. The v1.0.68 crash
handler records it to the crash file, but only an export after relaunch
surfaces it.

Fix (v1.0.71): wrap every `addView`/`updateViewLayout` in the overlay
manager; on failure record `OVERLAY_WINDOW_FAILED` and keep the service
alive (drop the stale window entry so the next frame re-creates it;
retry bubble creation throttled to once per second).

### R3 (plausible, unproven): the table lock is never earned on real frames

The overlay draws nothing until the table rect has been stable within
2 px on all four corners for 75 consecutive frames (5 s). On real
compressed frames the felt detector's corners may jitter past 2 px, so
the lock may never be earned even while capture flows. The harness pins
"1 px jitter keeps counting" and "10 px move resets", so the tolerance
is NOT changed without real-frame jitter evidence. Instead v1.0.71 adds
first-frame and capture-started events to the diagnostics export
(`FRAME_STATS` already logs lock progress every 150 frames), so the next
test can prove or rule this out. The bubble's lock ring already shows
lock progress live.

### R4 (ruled out): overlay permission handling

MainActivity already gates start on `Settings.canDrawOverlays` and
routes to Settings. The only gap is mid-session revocation, covered by
the R2 fix. No separate change.

### R5 (ruled out): FLAG_SECURE black capture

Already ruled out in v1.0.69: Jawad's own screenshots show the game, so
the game does not set FLAG_SECURE. The pure-black test (mean grey < 1.0)
stands.

## What v1.0.71 changes

- `CalibrationStore`: `sessionActive`, `sessionDemo`, `batteryOptAsked`
  flags (SharedPreferences, same "poolguide" file both services use).
- New `SessionRecovery` (pure Kotlin): the null-intent recovery decision,
  JVM-tested.
- `OverlayService` / `CaptureService`: null-intent recovery when a
  session was active. The overlay re-shows the bubble; the capture
  service cannot restore a MediaProjection token, so it records
  `CAPTURE_TOKEN_LOST`, foregrounds with a "tap Start to resume"
  notification, and waits for a fresh token.
- `LineOverlayManager`: every window op wrapped; failures recorded,
  never fatal.
- `MainActivity`: battery-optimisation exemption dialog on Start (asked
  once); the standard `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  flow.
- `DiagnosticsLog`: new kinds `SESSION_RESTARTED`, `CAPTURE_TOKEN_LOST`,
  `OVERLAY_WINDOW_FAILED`, `CAPTURE_STARTED`, `CAPTURE_FIRST_FRAME`.
- Manifest: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (a settings request,
  not a data permission; no new detection surface). `ManifestGuardTest`
  updated deliberately in the same PR, as that test requires.
- Version 1.0.71 (versionCode 72). No CV pipeline code changed, so the
  harness figures (15/15 tables, 85/85 balls, adversarial 11/11) are
  expected unchanged.

## What Jawad must do on the phone (only he can)

1. When the app asks, allow "ignore battery optimisation" for Pool Guide.
2. Vivo Settings > Battery > Background power consumption management >
   Pool Guide > Allow (and enable Auto-start for Pool Guide if shown).
3. After tapping Start, lock Pool Guide in the recents screen (tap the
   lock icon on its card) so a swipe does not kill it.
4. If the bubble ever vanishes again: reopen Pool Guide, tap Export
   diagnostics, and share the file — it now names the exact failure.
