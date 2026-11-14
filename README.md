# 8 Ball Pool Guideline Tool

A companion helper for 8 Ball Pool (Miniclip). It watches the game on your
screen and draws aim lines over it: the cue-ball aim line, the target ball's
path to the pocket, and the cue ball's deflection after impact, plus one-rail
bank shots when no direct shot exists.

**It is a separate app. You install the original 8 Ball Pool from the Play
Store (or APKPure) and this tool separately. The two work together on your
screen. Nothing about the game is changed.**

## What it is NOT

- **Not a mod.** It never touches the game's files, memory, or network. It
  never repacks or re-signs the game.
- **Not an autoplay / bot.** It never injects input, never plays a shot for
  you. There is no autoplay in any version.
- **Not affiliated with Miniclip**, and not endorsed by them. "8 Ball Pool"
  is a trademark of Miniclip SA.
- **Not on the Play Store, and never will be.** An app whose purpose is to
  overlay another app and give an in-game advantage will be flagged by Play
  Protect / Play policy. This tool is distributed sideloaded, from GitHub
  Releases, for your own device.

## Honest limits (read this before you install)

1. **The game can detect the overlay.** Android reports
   `FLAG_WINDOW_IS_OBSCURED` to the game for any touch that lands on a
   visible window above it, even if that window is `FLAG_NOT_TOUCHABLE`.
   There is no legitimate way to hide an overlay from the game. This tool
   reduces the footprint as far as legally possible: each aim line is drawn
   in its own small window sized to just that line's bounding box (never one
   full-screen overlay), and the control bubble has a **Hide guides** toggle
   that removes every line window while you shoot. But if you tap exactly on
   a line, the game sees the obscured flag. That is unavoidable.
2. **The game can black out screen capture.** If 8 Ball Pool sets
   `FLAG_SECURE`, MediaProjection captures a solid black frame and this tool
   cannot see the table at all. There is no legitimate bypass without root,
   and this tool does not attempt one. The app has a **diagnostic preview**
   (Main screen, "Show captured frame") so you can see immediately whether
   the capture is black. Whether 8 Ball Pool v56.35.0 sets FLAG_SECURE is
   still unconfirmed and is being tested on a real phone.
3. **No ban guarantee is possible.** Enforcement is mostly server-side
   (shot accuracy, win-rate jumps, reports). Use a **secondary or guest
   account first**. The first-run warning in the app says the same.
4. **Detection is approximate.** Standard ghost-ball geometry, no spin, no
   friction throw, no cushion compression. Cut angles above 80 degrees are
   flagged as thin cuts with very low margin. Table skins, resolutions, and
   game versions vary; the first-run calibration exists for that. Brown felt
   is detected with a tightly fitted window (the wooden rail is nearly the
   same hue) — it works on the synthetic tables, and the window is now
   recalibratable per game version from the Calibrate screen (HSV fields)
   if a real brown table needs it. The bank-shot cushion correction is
   fitted to the synthetic harness data, where it measures zero; real-table
   cushion behaviour is unmeasured.
5. **EDIT mode's anchor handles are touchable on purpose.** The Edit button
   on the floating bubble freezes the current shot and shows two draggable
   handles (cue + ghost) so you can correct a mis-detection by hand. Those
   two small windows ARE touchable (that is how dragging works), so a touch
   landing exactly on a handle sets `FLAG_WINDOW_IS_OBSCURED` — use EDIT
   mode to arrange, then tap Done and shoot in PLAY mode, where every line
   window is pass-through again. Since v1.0.6 the lock ring in EDIT mode
   shows a neutral grey full circle (a frozen state) instead of the last
   live lock progress, so it cannot be misread as "the table is 60%
   locked" while you edit a frozen shot.
6. **The ratio fallback is a guess — and it can never draw lines on its
   own anymore (FIXED in v1.0.5).** When the detector finds no table at
   all, the tool falls back to Bloro's screen-fraction table model (band
   0.237–0.913 of frame height, 2:1 table). In v1.0.4 that guess could
   lock the table by itself (it is deterministic from the frame size, so
   it is trivially "stable") and the lines drew on a guessed table on a
   fresh non-table screen. Since v1.0.5 the guess may only CONTINUE a
   lock a real detection already earned, for at most 10 s, and it is
   labelled in the status line ("table: ratio fallback — continuing
   last lock"); on a fresh screen with no real lock the tool reports no
   table and draws nothing ("Table not found — guides hidden"). If the
   lines sit on the wrong place, hide the guides and calibrate the
   corners by hand.
7. **Diagnostics export is local only.** Main screen, "Export diagnostics",
   saves a plain-text session log (detection states, frame stats, lock
   and fallback events, black-frame warnings, overlay state changes,
   app + game version, device model, Android version) to a file you
   pick. Nothing is uploaded and nothing leaves the device unless you
   choose to share the file. The log holds only the last 500 events.
8. **Practice mode is geometry only.** Practice mode now has Direct,
   Bank, Combo, and Kiss shots (same solver as the live overlay), but
   it is a drawing aid, not a physics simulator: no spin, no friction
   throw, no cushion compression, and the bank-shot cushion correction
   is fitted to synthetic data, so real-table cushion behaviour is
   unmeasured. What you practise here is the aim geometry of direct,
   bank, combo, and kiss shots, not the exact outcome on a real table.

## How it works

```
MediaProjection + VirtualDisplay (960x540, foreground service)
  -> ImageReader RGBA frames (15 FPS cap, frames dropped when behind)
  -> OpenCV: downscale to 720 wide, HSV felt mask -> table corners
     -> HSV contours (every frame) + HoughCircles (every 3rd frame) -> balls
        (green/blue ball windows are learned from the felt colour, so a
        ball the same hue as the cloth is still separated from it)
     -> the game's own white guideline: bilateral filter -> Canny (50, 150)
        -> HoughLinesP (30/15/8) -> verifier -> robust refit -> LineTracker
  -> shot solver (ghost ball, cut angle, tangent, mirror-pocket banks,
     combo + kiss shots; every reachable pocket ranked, best first)
  -> one small TYPE_APPLICATION_OVERLAY window per line (best shot in
     full colour, other candidates faint — PoolAimAssist UX)
```

Two foreground services (split in v1.0.2): `CaptureService` is
mediaProjection only (it holds the MediaProjection token); `OverlayService`
is specialUse only (it owns the overlay windows + Demo mode, which has no
token). They talk via `OverlayBus`. One service cannot be both types — on
Android 14+ a mediaProjection-type foreground service without a token throws
`SecurityException` and crashes the process (the v1.0.0 demo bug), so the
split makes that impossible by construction. Stopping either service stops
both, so the lines never outlive the capture.

Stability work (v1.0.3, learned from decoding the commercial tools):
the overlay draws nothing until the table rect has been stable for 5 s
(table-lock gate); the best shot's lines ease toward new detections
instead of snapping (40 px jitter gate + interpolation); every line is
drawn twice — a wide faint halo under a thin bright core — so it stays
readable on bright felt; bank shots carry a cushion-correction polynomial
whose form comes from Bloro but whose coefficients are re-fitted to our
own harness data (the fit is zero: after fixing the pocket model, no
systematic angle error is left to correct). The CV harness (15 synthetic
tables, `../cv-harness/RESULTS.md`) measures the v1.0.3 pipeline at
15/15 tables, 85/85 balls with 0 false positives, 15/15 shots, mean
cut-angle error 0.32 deg (v1.0.1 was 13/15, 76/85, 11/15, 1.46 deg).

Interaction work (v1.0.4, the remaining decode items): an **Edit/Play
toggle** on the floating bubble (Line Guide lead) that freezes the current
shot and shows two draggable anchor handles — cue and ghost — so a
mis-detection can be corrected by hand; a **ratio table-geometry
fallback** (Bloro lead) that guesses the table from the frame size when
the detector finds nothing, labelled in the status line; and an
**offline practice mode** (Cheto lead) with a draggable cue + target ball
and 1-px nudge buttons, so the solver can be learned without opening the
game. The brown felt window is now part of the per-version
`GameProfile` and editable from the Calibrate screen. The harness
numbers are unchanged in v1.0.4 (15/15, 85/85, 0 false positives,
15/15, 0.32 deg) — the fallback only fires when detection fails, which
never happens on the 15 harness tables.

No-guessed-table work (v1.0.5): the ratio fallback can no longer lock
the table by itself — it only continues a lock a real detection already
earned, for at most 10 s, and on a fresh non-table screen the tool
reports no table and draws nothing. The control bubble now follows
Bloro's touch pattern (drag after 10 dp, tap toggles the guides,
edge-snap on release, position saved), and carries a lock-progress ring
in place of Bloro's credit ring: ours has no credits or paywall, so the
ring fills as the table stabilises toward the 5 s lock and turns green
once locked. The harness numbers are unchanged in v1.0.5 (15/15, 85/85,
0 false positives, 15/15, 0.32 deg) — the gating only fires when
detection fails, which never happens on the 15 harness tables.

- **OpenCV**: Maven AAR `org.opencv:opencv:4.10.0` (4.9+ supports Android
  15's 16 KB page size). Fallback documented in `app/build.gradle.kts`:
  JitPack `com.github.opencv:opencv:4.9.0`.
- **Performance**: 15 FPS cap (Scugalug measured targets: capture scale
  0.75, 15 FPS — the table does not move between frames, and anything
  faster only heats the phone), ~720p downscale, Hough every 2nd-3rd frame,
  HSV + contours for coloured balls every frame, Hough restricted to the
  table ROI, Mat objects reused, detection on a background HandlerThread,
  never `bitmapToMat` per frame. Aim-fit error measured on the synthetic
  demo tables with a Python replica of the detector (`tools/measure_aim_fit.py`):
  **green 0.31 deg, blue 0.04 deg** at full resolution (16 px ball radius),
  against Scugalug's ~0.25 deg target. Note: the demo images draw the
  guideline as a chord from cue-edge to target-edge, which itself deviates
  3.01 deg (green) / 2.05 deg (blue) from the true aim ray — a fixture
  fidelity note, not a detector error; the guideline should be redrawn as
  the true aim ray in a later cycle.
- **Android 10-15 checklist** (all implemented in `CaptureService` /
  `OverlayService` / `MainActivity`): `FOREGROUND_SERVICE_MEDIA_PROJECTION`
  declared (12+); `POST_NOTIFICATIONS` requested at runtime (13+); the
  3-argument `startForeground(id, notification, ...)` overload (14+), with
  the mediaProjection type in the capture service and the specialUse type
  in the overlay service (the FGS split — see "How it works"); OpenCV
  warm-up **after** `startForeground` (5-second timeout on 14+); the
  MediaProjection token is created in the service's own process and passed
  to it immediately, never held in the Activity; partial screen sharing
  (14/15) is handled — whatever the user shares is what is captured, and
  the diagnostic preview shows it; overlay permission needs explicit user
  enablement (14+).
- **Why targetSdk 34**: it avoids the API 35 install-time foreground-service
  declaration friction for this sideloaded v1, while the code still handles
  Android 15 runtime behaviour (partial sharing, overlay rules) correctly.
  minSdk is 29 (Android 10).

## Install

1. Download the APK from the GitHub Releases page (or build it yourself,
   below).
2. On your phone, allow "Install unknown apps" for your browser / file
   manager, then open the APK.
3. Open **Pool Guide**, read the warning, grant:
   - **Display over other apps** (the aim lines),
   - **Notifications** (Android 13+, so the capture notification is visible),
   - **Screen capture** (the system dialog — start the capture from the app,
     then switch to the game).
4. First run: **Calibrate table** (drag the four red handles to the table
   corners, or tap Re-detect). Calibration is stored per game version.
5. Play. The green line is your aim, the yellow line is the target ball's
   path, the white line is the cue ball's deflection, the red ring is the
   pocket. Every other reachable pocket is also drawn, faint grey — the
   best shot is the highlighted one. Turn that off with **Show all shots**
   on the main screen if you only want the best shot. Use **Hide guides**
   on the floating bubble while shooting. If the lines are wrong, tap
   **Edit** on the bubble and drag the green (cue) and yellow (ghost)
   handles to correct them, then tap **Done**.

**Demo mode** (no game needed): the app ships with two synthetic table
images and runs the full pipeline + overlay on them, so you can verify the
tool works before ever opening the game. Rotate your phone to landscape
for the demo, so the lines land where the table would be.

**Practice mode** (no game needed): the main screen's **Practice mode**
button opens a self-contained table with a draggable cue ball and target
ball. The aim lines update live from the same solver, and the arrow
buttons nudge the selected ball 1 px at a time. This screen never touches
8 Ball Pool — there is nothing for the game to detect.

## Build it yourself

```sh
./gradlew assembleRelease          # APK: app/build/outputs/apk/release/
./gradlew testDebugUnitTest        # JVM unit tests (shot solver geometry)
./gradlew connectedDebugAndroidTest  # emulator tests (see below)
```

Requirements: Android SDK with `platforms;android-34` and
`build-tools;34.0.0`, JDK 17+. CI (GitHub Actions) builds on every push:
Gradle build + unit tests + `assembleRelease`, and an emulator job that
runs the instrumented tests on API 34 (x86_64). The workflow file is
`.github/workflows/android.yml`.

> Local-build note: Gradle's daemon protocol needs loopback TCP. On the
> development VM used for this project the runtime intercepts loopback
> connections, so local `./gradlew` runs fail with
> "Could not receive a message from the daemon". CI is the authority for
> builds. The pure-Kotlin geometry module (`geometry/`) can still be
> compiled and verified locally with the Kotlin compiler from the Gradle
> distribution (see `tools/`).

## Tests

- **Unit** (`app/src/test`): ghost-ball point, cut angles (0 / 73.3 / 84.3
  degrees), impossible shots (> 90 degrees, overlapping balls), tangent
  deflection, mirror-pocket and reflection math, bank contact point, pocket
  positions, allShots ranking + symmetric-tie order, combo shots (straight
  / cut / impossible / overlap), kiss shots (refined deflection, unrefined
  fallback, impossible approaches), per-version GameProfile defaults +
  validation + brown-window parse/serialise, FramePacer 15 FPS admission,
  table-lock gate (75 frames, tolerance, reset), jitter gate + lerp,
  cushion-correction fit, EDIT-mode manual aim (cue re-solve, ghost
  re-aim, impossible-drag guard, table clamp), ratio fallback geometry
  (Bloro band fractions, 2:1 centring, portrait clamp), practice-table
  state (1-px nudge, clamp, hit test, reset). 85 tests.
- **Instrumented** (`app/src/androidTest`): the real pipeline on the two
  synthetic tables (green + blue felt) — table, 6 pockets, cue ball, 8+
  balls, guideline, and a solved shot with the expected target and pocket,
  plus the allShots ranking (best = first, > 1 candidate); a pure-black
  frame is reported as secure; the overlay manager shows one small window
  per line (4 for the best shot, +2 faint windows per extra candidate when
  "Show all shots" is on, 4 when it is off) and hides them on toggle /
  black frame; EDIT mode freezes the shot, shows 2 anchor handles, follows
  a ghost-anchor drag, keeps drawing with no guideline, and resumes live
  detection on Done; the overlay service runs in demo mode; the practice
  activity launches, solves the default layout, and nudges 1 px. 10 tests.
- **Not yet tested (needs a real phone + the game)**: FLAG_SECURE blackout,
  `isObscured` behaviour while shooting, real-table CV accuracy across
  table skins and resolutions. See `../phone-test/PHONE-TEST-PLAN.md` in
  the research project for the phone test package.

## Project layout

```
app/src/main/java/com/poolguide/tool/
  geometry/   Vec2, ShotSolver            (pure Kotlin, JVM-testable)
  cv/         CvConfig, TableGeometry, TableDetector, BallDetector,
              GuidelineDetector, CvPipeline, OpenCvInit,
              RatioTableGeometry (fallback table from frame size)
  capture/    CaptureService, PreviewBus, OverlayBus, FramePacer
              (MediaProjection, foreground svc; 15 FPS pacing)
  overlay/    LineView, LineOverlayManager, OverlayService, ManualAim
              (one small window per line; specialUse foreground svc;
              EDIT mode: frozen shot + draggable cue/ghost anchors)
  data/       CalibrationStore, GameProfile (per-version calibration)
  ui/         MainActivity, CalibrationActivity, PracticeState,
              PracticeView, PracticeActivity (offline practice table)
app/src/main/assets/demo/  synthetic tables + black frame (tests + demo mode)
tools/        generate_demo_images.py, measure_aim_fit.py
```

## Research basis and acknowledgements

This tool was built from the Phase 1 / deep-dive reverse-engineering reports
in the companion research project (`8ball-pool-rea/`): the APK is a custom
native C++ engine with a DexGuard packer, the table exists only as rendered
pixels (no view hierarchy), every touch carries an `isObscured` flag, and
the anti-cheat layers are Anybrain behaviour analysis + Play Integrity +
packer integrity. The companion-overlay + MediaProjection + CV architecture
was chosen because it is the lowest-ban-risk family that works without root.

Code and ideas reused (with thanks):

- **kaigiii/8-Ball-Pool-Aim-Assist-for-macOS** (CC BY-NC-SA 4.0) — the
  guideline detector is a port of its `vision_core.py` / `verifier.py` /
  `line_tracker.py`: bilateral filter (d=20, sigmaColor=200, sigmaSpace=1),
  verifier (edge points within 1.5 px, min 20 inliers, top 3), LineTracker
  (alpha 40, fade 8/frame), HSV white window (0,0,200)-(180,30,255). The
  Canny thresholds and Hough parameters now come from
  workua94-afk/8ball-aim-master instead (Canny 50/150, HoughLinesP
  threshold 30 / minLen 15 / maxGap 8): the kaigiii Canny (500, 350) kept
  almost no edges for the ~2 px guideline after the 720-wide downscale.
- **workua94-afk/8ball-aim-master** — guideline detector parameters
  (above) and the 15-frame cache idea; **Scugalug/8-ball-pool-aim-assistant**
  — adaptive cloth-colour learning (the felt's mean HSV tightens the
  green/blue ball windows), and the measured performance targets used in
  v1.0.2 (capture scale 0.75, 15 FPS cap, aim fit ~0.25 deg at 16 px ball
  radius); **yasin4815162342/Aim** — the IRLS/Tukey robust line fit (one
  guarded Tukey-weighted refit of each verified segment), and the combo /
  kiss / bank shot geometry (closed-form ghost points, mirror contact
  normals, e=0.95 bisection refinement).
- **abwahidmoto-beep/PoolAimAssist** — the all-shots UX: every candidate
  shot drawn faint, the best highlighted; **projectgtp/aimcall** — the
  per-game-version calibration profiles (pocket fractions + ball radius
  per version, never hardcoded geometry).
- **jonathansilva/8BallPool** (306 stars) — the earliest Android overlay
  approach; its per-device tuning notes and its warning not to reuse the
  package name or publish on the Play Store shaped this project's
  distribution choice.
- **Sushang-Agnihotri/8-Ball-Pool-Aim-tool** — passive overlay, ghost ball +
  cut angle + banks up to 4 rails, pocket snap.
- **lecepin/billiard-aim-calculation** — ghost-ball visualiser.
- **ChatGPT (Nov 13, 2026)** — the Android 10-15 MediaProjection /
  foreground-service checklist, the per-line-window overlay design (the
  single most important change in this build), the FLAG_SECURE diagnostic,
  the OpenCV Maven route and performance budget, and the ghost-ball /
  mirror-pocket formulas. Full verbatim answer:
  `../chatgpt-overlay-guide-2026-11-13.md`.

## License

MIT. See `LICENSE`. This project is not affiliated with Miniclip SA.
