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
   game versions vary; the first-run calibration exists for that.

## How it works

```
MediaProjection + VirtualDisplay (1280x720, foreground service)
  -> ImageReader RGBA frames (10-15 FPS, frames dropped when behind)
  -> OpenCV: downscale to 720 wide, HSV felt mask -> table corners
     -> HSV contours (every frame) + HoughCircles (every 3rd frame) -> balls
        (green/blue ball windows are learned from the felt colour, so a
        ball the same hue as the cloth is still separated from it)
     -> the game's own white guideline: bilateral filter -> Canny (50, 150)
        -> HoughLinesP (30/15/8) -> verifier -> robust refit -> LineTracker
  -> shot solver (ghost ball, cut angle, tangent, mirror-pocket banks)
  -> one small TYPE_APPLICATION_OVERLAY window per line
```

- **OpenCV**: Maven AAR `org.opencv:opencv:4.10.0` (4.9+ supports Android
  15's 16 KB page size). Fallback documented in `app/build.gradle.kts`:
  JitPack `com.github.opencv:opencv:4.9.0`.
- **Performance**: 10-15 FPS is realistic only at ~720p downscale, Hough
  every 2nd-3rd frame, HSV + contours for coloured balls every frame, Hough
  restricted to the table ROI, Mat objects reused, detection on a background
  HandlerThread, never `bitmapToMat` per frame.
- **Android 10-15 checklist** (all implemented in `CaptureService` /
  `MainActivity`): `FOREGROUND_SERVICE_MEDIA_PROJECTION` declared (12+);
  `POST_NOTIFICATIONS` requested at runtime (13+); the 3-argument
  `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)`
  overload (14+); OpenCV warm-up **after** `startForeground` (5-second
  timeout on 14+); the MediaProjection token is created in the service's own
  process and passed to it immediately, never held in the Activity; partial
  screen sharing (14/15) is handled — whatever the user shares is what is
  captured, and the diagnostic preview shows it; overlay permission needs
  explicit user enablement (14+).
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
   pocket. Use **Hide guides** on the floating bubble while shooting.

**Demo mode** (no game needed): the app ships with two synthetic table
images and runs the full pipeline + overlay on them, so you can verify the
tool works before ever opening the game. Rotate your phone to landscape
for the demo, so the lines land where the table would be.

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
  positions. The geometry module was also verified locally with a standalone
  harness: 14/14 checks pass, including the demo-table shot (cut 30.9 deg).
- **Instrumented** (`app/src/androidTest`): the real pipeline on the two
  synthetic tables (green + blue felt) — table, 6 pockets, cue ball, 8+
  balls, guideline, and a solved shot with the expected target and pocket;
  a pure-black frame is reported as secure; the overlay manager shows one
  small window per line and hides them on toggle / black frame; the capture
  service runs in demo mode.
- **Not yet tested (needs a real phone + the game)**: FLAG_SECURE blackout,
  `isObscured` behaviour while shooting, real-table CV accuracy across
  table skins and resolutions. See `../phone-test/PHONE-TEST-PLAN.md` in
  the research project for the phone test package.

## Project layout

```
app/src/main/java/com/poolguide/tool/
  geometry/   Vec2, ShotSolver            (pure Kotlin, JVM-testable)
  cv/         CvConfig, TableGeometry, TableDetector, BallDetector,
              GuidelineDetector, CvPipeline, OpenCvInit
  capture/    CaptureService, PreviewBus  (MediaProjection, foreground svc)
  overlay/    LineView, LineOverlayManager (one small window per line)
  data/       CalibrationStore           (per-version calibration)
  ui/         MainActivity, CalibrationActivity
app/src/main/assets/demo/  synthetic tables + black frame (tests + demo mode)
tools/        generate_demo_images.py
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
  green/blue ball windows); **yasin4815162342/Aim** — the IRLS/Tukey robust
  line fit (one guarded Tukey-weighted refit of each verified segment).
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
