# 8 Ball Pool Guideline Tool

A companion helper for 8 Ball Pool (Miniclip). It watches the game on your
screen and draws aim lines over it: the cue-ball aim line, the target ball's
path to the pocket, and the cue ball's deflection after impact, plus one-rail
bank shots when no direct shot exists, two-rail banks when no one-rail
bank solves, one-rail kick shots (cue ball off a rail first) when the
kick's cut angle beats the best one-rail bank's, two-rail kick shots
when nothing else solves, and combo and kiss shots as the last resort
when no other family solves.

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
   that removes every line window while you shoot. Since v1.0.37 the lines
   also auto-hide while you are aiming (the guideline angle swinging, or
   the cue ball travelling after a shot) and come back once the aim has
   been quiet for 5 frames, so no line window exists over the table at
   the moment you tap to shoot. But if you tap exactly on a line while
   it is drawn, the game sees the obscured flag. That is unavoidable.
2. **The game can black out screen capture.** If 8 Ball Pool sets
   `FLAG_SECURE`, MediaProjection captures a solid black frame and this tool
   cannot see the table at all. There is no legitimate bypass without root,
   and this tool does not attempt one. The app has a **diagnostic preview**
   (Main screen, "Show captured frame") so you can see immediately whether
   the capture is black. Whether 8 Ball Pool v56.35.0 sets FLAG_SECURE is
   still unconfirmed and is being tested on a real phone. Since v1.0.37 a
   black run that lasts 30 frames (about 2 seconds) shows "Capture
   blocked (FLAG_SECURE?)" on the control bubble and is recorded in the
   diagnostics export with the frame it started on and whether the table
   was locked just before, so the next test can map when the flag is on.
   The capture itself is named neutrally: since v1.0.37 the virtual
   display is "ScreenCapture" and the foreground notification reads
   "Guide active", so the tool's own name never appears in the system
   capture UI.
3. **No ban guarantee is possible.** Enforcement is mostly server-side
   (shot accuracy, win-rate jumps, reports). Use a **secondary or guest
   account first**. The first-run warning in the app says the same.
4. **Detection is approximate.** Standard ghost-ball geometry, no spin, no
   friction throw, no cushion compression. Cut angles above 80 degrees are
   flagged as thin cuts with very low margin. Table skins, resolutions, and
   game versions vary; the first-run calibration exists for that. Brown felt
   is detected with a tightly fitted window (the wooden rail is nearly the
   same hue) — it works on the synthetic tables, and the window is now
   recalibratable per game version from the Calibrate screen (sliders)
   if a real brown table needs it. Since v1.0.34 the white and
   stripe brightness floors are relative to the felt instead of
   fixed (the floor is the felt's brightness + 30, with a minimum
   of 90), so a dim table down to about 0.37x brightness still
   reads the cue ball; below that the cue reads SOLID by design
   and the tool reports no cue rather than guessing. The bank- and kick-shot cushion
   correction is fitted to the synthetic harness data, where it
   measures zero; real-table cushion behaviour is unmeasured. A
   stripe ball's white number band can also read as a second white
   ball: on the blue demo table it is detected at the stripe ball's
   centre, and before v1.0.20 the first-white pick took it as the cue,
   so the solved shot there was phantom-cue -> real-cue, not the
   depicted shot. Since v1.0.20 the cue is picked by group
   (CuePicker): the band's disc samples STRIPE while the real cue
   samples CUE, so the real cue wins. The band phantom itself is
   still detected (it IS the stripe ball, just mis-coloured) and is
   tagged STRIPE. If the real cue's group ever reads UNKNOWN
   (occluded, or cut off by the frame edge) the pick falls back to
   the guideline ray and then to the first white ball (see item 21),
   and a phantom listed first can win that last fallback. EDIT mode
   exists to correct a mis-detection by hand.
5. **EDIT mode's anchor handles are touchable on purpose.** The Edit button
   on the floating bubble freezes the current shot and shows two draggable
   handles (cue + ghost) so you can correct a mis-detection by hand. Those
   two small windows ARE touchable (that is how dragging works), so a touch
   landing exactly on a handle sets `FLAG_WINDOW_IS_OBSCURED` — use EDIT
   mode to arrange, then tap Done and shoot in PLAY mode, where every line
   window is pass-through again. Since v1.0.41 EDIT mode also exits
   on its own after 20 seconds without a handle drag, so a forgotten
   EDIT session cannot leave a touchable handle over the table while
   you shoot; the handles disappear and live detection resumes.
   Since v1.0.6 the lock ring in EDIT mode
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
   Bank, Combo, Kiss, and Kick shots (same solver as the live overlay), but
   it is a drawing aid, not a physics simulator: no spin, no friction
   throw, no cushion compression, and the bank- and kick-shot cushion
   correction is fitted to synthetic data, so real-table cushion behaviour
   is unmeasured. What you practise here is the aim geometry of direct,
   bank, combo, kiss, and kick shots, not the exact outcome on a real table.
9. **The calibration screen is a tuning aid, not a guarantee.** Since
   v1.0.8 the Calibrate screen has sliders for the brown felt window
   and the pocket/ball thresholds, a felt-mask preview, and a live
   status line ("Table found with these values" / "Table not found
   with these values — mask covers X% of the frame"). The mask shows
   what the current window matches on the preview frame, and the
   status reports what the preview frame shows — neither promises the
   live pipeline will lock on a real table. The defaults are
   unchanged, and calibration is stored per game version.
10. **Two-rail banks are geometry only, and they are the last resort.**
    Since v1.0.9 the tool also draws two-rail banks (target -> rail
    -> rail -> pocket, same cyan rendering, both contacts shown).
    In the live overlay a two-rail bank is only the best shot when
    no direct shot and no one-rail bank solves. A two-rail bank
    never displaces a simpler shot. In practice BANK mode a two-rail
    bank only wins when its cut angle beats the best one-rail bank
    by more than 30 degrees (each extra cushion compounds
    real-table error). The cushion correction at each rail is fitted
    to synthetic data, where it measures zero; real-table cushion
    behaviour is unmeasured, and it is unmeasured twice over on a
    two-rail bank. What you practise here is the aim geometry, not
    the exact outcome on a real table.
11. **Kick shots are geometry only, and they never displace a direct
    shot.** Since v1.0.10 the tool also draws one-rail kick shots (cue
    ball -> rail -> target -> pocket, magenta rendering: the cue's aim
    in green, the kicked cue ball in magenta, the target line in
    yellow — a bank stays cyan, so the two are never confusable). In
    the live overlay a kick is only the best shot when no direct shot
    solves; it is ranked against the best one-rail bank by the same
    number, the direct cut angle, so the smaller cut wins and a tie
    goes to the bank. The cushion correction at the rail is fitted to
    synthetic data, where it measures zero; real-table cushion
    behaviour is unmeasured. What you practise here is the aim
    geometry, not the exact outcome on a real table.
12. **Two-rail kicks are geometry only, and they are the last resort.**
    Since v1.0.11 the tool also draws two-rail kick shots (cue ball
    -> rail -> rail -> target -> pocket, same magenta rendering, all
    three cue-path segments shown). In the live overlay a two-rail
    kick is only the best shot when no direct shot, no one-rail bank,
    no one-rail kick, and no two-rail bank solves — it never
    displaces any other shot. In practice KICK mode a two-rail kick
    only wins when its cut angle beats the best one-rail kick by more
    than 30 degrees (each extra cushion compounds real-table error).
    The cushion correction at each rail is fitted to synthetic data,
    where it measures zero; real-table cushion behaviour is
    unmeasured, and it is unmeasured twice over on a two-rail kick.
    What you practise here is the aim geometry, not the exact outcome
    on a real table.
13. **The cut angle is the solver's geometry, not a make guarantee.**
    Since v1.0.12 the tool shows the cut angle beside the aim line
    (one decimal, in the shot family's colour: green for a direct
    shot, cyan for a bank, magenta for a kick) and a "Thin cut"
    warning above the cue ball when the cut is over 80 degrees — a
    thin cut has very little margin, and past 90 degrees the solver
    returns no shot at all. The angle is computed from the detected
    ball positions with no spin, no throw, and no cushion compression
    in the model, so on a real table the true cut differs by whatever
    the detection and the physics leave out. Practice mode shows the
    same readout and warning for every mode (direct, bank, kick,
    combo, kiss), one label per leg.
14. **Combo and kiss shots are the last resort, and they trigger
    rarely.** Since v1.0.13 the live overlay also draws combo shots
    (cue -> first ball -> second ball -> pocket) and kiss shots
    (cue deflects off the first ball into the second ball, then
    into the pocket) — but only when no direct shot, no one-rail
    bank, no one-rail kick, no two-rail bank, and no two-rail kick
    solves. A combo never displaces a simpler shot, and a kiss
    never displaces a combo. On the practice table the earlier
    families fail only when the cue sits nearly touching the target
    ball near a corner pocket (about 19 px apart) — on a real table
    that configuration is rare, so most of the time the overlay
    shows a simpler shot and the combo/kiss fallback never appears.
    Both are geometry only, like the other fallback families: no
    spin, no throw, no cushion compression, and the second leg's cut
    inherits whatever error the first leg's detection carries. Each
    leg gets its own cut-angle readout, and a leg over 80 degrees
    gets the thin-cut warning.
15. **Ball-group tags are classified from the disc, and the
    stripe/solid split is unproven on a real table.** Since v1.0.14
    the live overlay shows a small tag next to each detected ball
    ("C" cue, "8" eight, "S" solid, "ST" stripe, "?" unknown), in
    the ball's own colour family, placed 2.2 radii off the nearest
    shot line through the ball so it never covers the line. The
    classifier samples the ball's disc (2 px grid inside 0.85 of
    the radius): a ball that is (near-)all white is the cue, a
    near-black one is the 8-ball, a disc with a clear white band is
    a stripe, a coloured disc with almost no white is a solid. It
    says "?" instead of guessing when the disc is too small, cut
    off by the frame edge, occluded by another ball, or its white
    fraction sits in the ambiguous middle — a solid showing its
    number circle reads a white fraction around 0.05-0.16, so it
    can read "?" on a real table, and that is the honest answer,
    not a misread. On the 15 synthetic harness tables the
    classification is exact (see cv-harness/results-v1.0.14-after.json),
    but the synthetic balls have no number circle and perfect
    lighting — the phone test is still the real check. Practice mode
    shows the same tags; its groups are known by construction (cue
    "C", the other balls "S"). Tags show only while shot lines are
    drawn, and hide with them (auto-hide, hide toggle, table lock,
    black frame).

16. **The pocket distance is a power proxy, not a make guarantee.**
    Since v1.0.15 the live overlay shows the total shot distance
    beside the pocket ring ("29.6 bd" — ball diameters), in the
    shot family's colour, so the player judges power, not just
    direction. It is the total path the potting chain travels: cue
    ball to impact, then each struck ball to the next impact or the
    pocket (a table is about 44 ball diameters long, so the number
    reads at a glance). It does NOT account for spin, throw, rail
    friction, or speed loss at each impact — a long bank at "42.1
    bd" needs more than a straight "5.9 bd" pot, but the number
    alone cannot tell the player exactly how hard to hit. It is
    geometry, not physics. Practice mode shows the same number at
    the same pocket, so practice and live agree. The label hides
    with the lines (auto-hide, hide toggle, table lock, black
    frame) and follows the frozen shot in EDIT mode.

17. **The rail-contact marker shows where to hit the cushion, not
    how hard.** Since v1.0.16 every bank and kick shot draws a small
    ring on the cushion at each rail contact (cyan for banks,
    magenta for kicks), so the player aims at a visible spot
    instead of guessing where the bent line meets the rail. The
    spot is the solver's own contact point — the joint of the
    bank/kick path — not a second detection. It is still geometry,
    not physics: the cushion correction is fitted to synthetic
    harness data where it measures zero, and real-table cushion
    behaviour (speed loss, angle change off a rail) is unmeasured,
    so on a real table the true contact can sit a little off the
    ring. Practice mode shows the same rings at the same spots, so
    practice and live agree. Markers hide with the lines (auto-hide,
    hide toggle, table lock, black frame, bank lines off) and are
    never drawn for direct, combo, or kiss shots, which touch no rail.

18. **The shot history shows what the tool solved, not what you
    potted.** Since v1.0.17 the main screen keeps the last 8 solved
    shots the overlay drew ("14:32:05 DIRECT → TL, cut 24.6°,
    29.6 bd"), newest last, and the diagnostics export carries the
    same list. A new entry opens only when the solved shot changes —
    a steady aim records once, not once per frame — so the strip is
    a review of distinct aims, not a frame log. It records only
    shots the player actually saw (table locked, lines visible); a
    black capture, a hidden overlay, or a frozen EDIT-mode shot
    records nothing. The pocket is named by position (TL/TR/BR/BL/
    TM/BM), or "?" when the table is not detected. The numbers are
    the same geometry as the overlay labels — cut angle and total
    path in ball diameters — so they carry the same limits: no spin,
    throw, rail friction, or speed loss, and unproven on a real
    table until the phone test.

19. **Table-size presets are starting points, not measurements.**
    Since v1.0.18 the Calibrate screen has 7ft / 8ft / 9ft presets:
    tapping one loads that size's values into the sliders and
    handles, and "Save as preset" stores the current values under
    the chosen size. The built-ins scale the 9ft default by the
    table-length ratio (9/8 for 8ft, 9/7 for 7ft) on the
    size-dependent values only — ball radius and pocket radius;
    the brown felt window never scales, and the corners are the
    same defaults for every size. Pocket cut, cloth, and camera
    height all vary between real tables, so a preset never promises
    a lock: always check the felt mask and the status line, and
    tap Save. Applying a preset fills the screen only; nothing is
    kept until Save.

20. **A blocked path is flagged — and the shown shot is the first
    clear pocket.** Since v1.0.19 the tool checks whether another
    detected ball sits on the shown shot's path (within one ball
    diameter of the cue's aim line or the potted ball's path, for
    direct, bank, and kick shots) and flags it: a "Path blocked"
    label over the table in the warning amber, "— path blocked by
    another ball" on the status line, and "— blocked" on the
    shot-history line. Since v1.0.21 the shown DIRECT shot is the
    first clear pocket in cut order: a blocked easiest pocket no
    longer hides a makeable one, and the blocked best stays drawn
    faint among the candidates. When every direct shot is blocked
    the best stays the shown shot and is flagged, as before — the
    green and blue demo tables prove it (every reachable pocket
    there is blocked, so their shown shots are unchanged), while
    9 of the 15 harness tables now solve to the second pocket.
    Since v1.0.22 the same rule covers the ONE-RAIL families: the
    shown bank is the first clear bank in cut order and the shown
    kick is the first clear kick, and the bank-vs-kick choice is
    made on those first-clear cuts, not on the best cuts. A
    blocked easiest bank no longer hides a clear kick (or the
    other way round). When every bank or every kick is blocked the
    best of that family stays the shown shot and is flagged, as
    before. Since v1.0.23 the same rule covers the TWO-RAIL
    families: when no one-rail shot solves either, the shown
    two-rail bank is the first clear two-rail bank in cut order
    and the shown two-rail kick the first clear two-rail kick.
    The two-rail bank is still tried before the two-rail kick,
    so the re-ranking is within each family, not between them.
    Since v1.0.25 the same rule covers the COMBO and KISS
    families: when no two-rail shot solves either, the shown combo
    is the first clear combo in cut order and the shown kiss the
    first clear kiss. A combo is still tried before a kiss, so the
    re-ranking is within each family, not between them. Each
    candidate's own second ball is never counted as a blocker of
    its own shot (it sits at the start of its own target line);
    another candidate's second ball stays in the blocker list,
    because it is a real ball on the table. Since v1.0.24 the flag
    covers COMBO and KISS shots too: a combo is flagged when
    another ball sits on the cue's aim line, the first ball's
    path to the second ball's ghost point, or the second ball's
    path to the pocket; a kiss is flagged when another ball sits
    on the cue's aim line to the kiss ghost, the deflected cue
    path to the second ball's ghost point, or the second ball's
    path to the pocket. The kissed ball's own departure is not
    checked: it pots nothing and its departure is not on the
    potting chain. The travelling balls are never counted as
    blockers of their own shot (the second ball sits at the
    start of its own target line). Detection noise (~0.4 px on
    the synthetic tables) can flip the verdict for a ball sitting
    within a pixel or two of the one-diameter boundary, and EDIT mode
    never shows the flag (the frozen shot is the player's manual
    aim, not the live detection the flag belongs to).

21. **The cue ball is picked by group, not by colour.** Since v1.0.20
    the pipeline picks the ball the group classifier calls CUE; the
    old first-white-colour pick took a stripe ball's white number
    band as the cue on the blue demo table (see item 4). The band
    phantom is still detected and tagged STRIPE. If no ball carries
    the CUE group the pick falls back to the guideline ray (since
    v1.0.40): the white ball nearest the detected aim line, within
    20 px of it, wins, so a cue ball hidden in a cluster is picked
    by the ball on the actual aim line. Only if there is no guideline
    either does it fall back to the first white ball.

22. **The ghost-ball ring marks where the cue ball must arrive.**
    Since v1.0.26 the live overlay draws a green ring of one ball
    radius centred on the shown shot's ghost point, the cue ball's
    centre at impact, for every shot family (direct, bank, kick,
    combo, kiss) and in EDIT mode too. The ring touches the target
    ball exactly, so the player aims the cue ball at a visible
    circle that kisses the target instead of guessing where the aim
    line ends. Practice mode already drew this circle; the live
    overlay now agrees with it. Since v1.0.27 a combo or kiss draws
    a second ring on the second leg's ghost: the first ball's
    arrival point for a combo (the transfer point between the two
    balls), and the point the cue ball must reach after the kiss
    for a kiss. Non-best candidates get no ring, like they
    get no pocket ring and no labels. The ring hides with the lines
    (auto-hide, hide toggle, table lock, black frame) and follows
    the frozen shot in EDIT mode. It is geometry, like the lines:
    it shows where the cue ball must be at impact, not whether
    the shot will drop. Cut angle, thin-cut warning, and the
    blocked-path flag still carry the make judgement.

23. **The session pot rate counts what you potted, not how well
    you played.** Since v1.0.42 the control bubble shows
    "Pots 7/10 (70%)" once you have taken a shot while capture is
    locked, and the diagnostics export carries a "Session pot rate"
    line. A shot counts only after the cue ball jumps over 1.5 ball
    diameters and stays there for 2 frames, and a pot counts only
    after the balls settle (5 stable frames); a missing cue ball
    counts as a scratch, never a pot, and balls that never settle
    within 150 frames keep the shot count but get no pot count. At
    10 shots and 80% or more the bubble adds "Pot rate very high,
    vary your play". The tracker cannot tell whose turn it is, so a
    shot taken while capture runs through an opponent's turn counts
    too, and the numbers are a session tally, not a make guarantee.

24. **The app's permission footprint is pinned by a test.** Since
    v1.0.43 a unit test fails if the manifest ever gains INTERNET,
    QUERY_ALL_PACKAGES, PACKAGE_USAGE_STATS, READ_EXTERNAL_STORAGE,
    or BIND_ACCESSIBILITY_SERVICE, if the permission set drifts from
    exactly SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE,
    FOREGROUND_SERVICE_MEDIA_PROJECTION,
    FOREGROUND_SERVICE_SPECIAL_USE, and POST_NOTIFICATIONS, or if
    any component other than MainActivity is exported. The tool
    stays offline and unprivileged by construction, and a deliberate
    future change has to update the test in the same change.

## How it works

```
MediaProjection + VirtualDisplay (960x540, foreground service)
  -> ImageReader RGBA frames (15 FPS cap, frames dropped when behind)
  -> OpenCV: downscale to 720 wide, HSV felt mask -> table corners
     -> HSV contours (every frame) + HoughCircles (every 3rd frame) -> balls
        (green/blue ball windows are learned from the felt colour, so a
        ball the same hue as the cloth is still separated from it)
     -> the game's own white guideline: Gaussian blur 5x5 -> Canny (50, 150)
        -> HoughLinesP (30/15/8) -> verifier (parallel-edge dedup since
        v1.0.39) -> robust refit -> LineTracker
  -> shot solver (ghost ball, cut angle, tangent, mirror-pocket banks
     (one- and two-rail), mirror-ghost kicks (one- and two-rail), combo + kiss
     shots; every reachable pocket ranked, first clear pocket shown;
     one-rail and two-rail banks and kicks ranked the same way, first
     clear shown; the shown shot is flagged "Path blocked" when another
     ball sits on its path)
  -> one small TYPE_APPLICATION_OVERLAY window per line or ring (best shot in
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
As of v1.0.55 the same harness measures 15/15, 85/85, 0 false
positives, 15/15, mean cut-angle error 0.177 deg (max 0.27 deg);
the adversarial tables measure 8/9 tables, 46 balls, 4 missed
(all the purple-felt table, which the felt windows do not cover),
0 false positives. The figures are unchanged since v1.0.45: no
pipeline code changed in v1.0.45, v1.0.46, v1.0.47, v1.0.48, v1.0.49,
v1.0.50, v1.0.51, v1.0.52, v1.0.53, v1.0.54, v1.0.55, or v1.0.56, which were all docs-only cycles. `../cv-harness/RESULTS.md` was refreshed in
v1.0.45: its header now names the current pipeline version and its
frame schedule reads HoughCircles and guideline on frames 0/3/6
(guideline cadence 3 since v1.0.38); before that it still described
the v1.0.3 pipeline and the old 2/4/6 guideline schedule. In v1.0.46
the work-width change (deep-dive item 8, 720 to 640) was attempted
on the harness and abandoned: the baseline stayed identical on every
decision field, but the adversarial tables regressed, the glare cue
ball on a07 was lost (its circle is Hough-only at 720 and
undetectable at 640 at any accumulator threshold from 8 to 12) and
the 0.3x dim guideline on a08 was lost (54 edge pixels, zero Hough
candidates), so the work width stays 720. The experiment record is
`../cv-harness/run_v1046.py` with the rescaled ground truth
(`ground_truth_640.json`, every coordinate times 640/720) and the
before/after JSONs; the `RESULTS.md` header was bumped to v1.0.46
with the figures unchanged, since no pipeline code changed. In v1.0.47
`run_harness.py` learned to write the Adversarial tables section itself:
it reads the newest versioned adversarial JSON (the -after file of the
newest pair, with a loud warning if before/after disagree, and a
`--adversarial` override for the abandoned-change case) and regenerates
the headline figures, the finding bullets and the per-table rows from
it, so the section now survives a re-run instead of being wiped. The
regenerated section was checked row for row against the hand-maintained
one it replaces: identical except for the header comment. The
`RESULTS.md` header was bumped to v1.0.47 with the figures unchanged,
since no pipeline code changed. In v1.0.48 the README harness
paragraph was refreshed (it still anchored on v1.0.45): the anchor is
now v1.0.47, and the paragraph records that the figures are unchanged
since v1.0.45 across the four docs-only cycles v1.0.45-v1.0.48. The
`RESULTS.md` header was bumped to v1.0.48 with the figures unchanged,
since no pipeline code changed. In v1.0.49 the README harness
paragraph was refreshed again (it still anchored on v1.0.47): the
anchor is now v1.0.48, and the paragraph records that the figures are
unchanged since v1.0.45 across the five docs-only cycles
v1.0.45-v1.0.49. The `RESULTS.md` header was bumped to v1.0.49 with
the figures unchanged, since no pipeline code changed. In v1.0.50 the README harness
paragraph was refreshed again (it still anchored on v1.0.48): the
anchor is now v1.0.49, and the paragraph records that the figures are
unchanged since v1.0.45 across the six docs-only cycles
v1.0.45-v1.0.50. The `RESULTS.md` header was bumped to v1.0.50 with
the figures unchanged, since no pipeline code changed. In v1.0.51 the README harness
paragraph was refreshed again (it still anchored on v1.0.49): the
anchor is now v1.0.50, and the paragraph records that the figures are
unchanged since v1.0.45 across the seven docs-only cycles
v1.0.45-v1.0.51. The `RESULTS.md` header was bumped to v1.0.51 with
the figures unchanged, since no pipeline code changed. In v1.0.56 the README harness
paragraph was refreshed again (it still anchored on v1.0.54): the
anchor is now v1.0.55, and the paragraph records that the figures are
unchanged since v1.0.45 across the twelve docs-only cycles
v1.0.45-v1.0.56. The `RESULTS.md` header was bumped to v1.0.56 with
the figures unchanged, since no pipeline code changed.

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
  faster only heats the phone), ~720p downscale, HoughCircles and the
  guideline Hough every 3rd frame (guideline cadence 3 since v1.0.38),
  HSV + contours for coloured balls every frame, the ball HSV pass
  cropped to the table ROI (since v1.0.36), Hough restricted to the
  table ROI, Mat objects reused, detection on a background HandlerThread,
  never `bitmapToMat` per frame. Aim-fit error measured on the synthetic
  demo tables with a Python replica of the detector (`tools/measure_aim_fit.py`):
  **green 0.13 deg, blue 0.03 deg** at full resolution (16 px ball radius),
  against Scugalug's ~0.25 deg target. Since v1.0.28 the demo images draw
  the guideline as the true aim ray (cue -> ghost point, ending at the
  foot of the perpendicular from the target centre, 2 px clear of the
  target ball), so the fit is measured against the aim itself: before
  v1.0.28 the images drew a chord from cue-edge to target-edge that sat
  3.01 deg (green) / 2.05 deg (blue) off the true aim, and the detector
  faithfully fitted the chord (0.31 / 0.04 deg off it) while the solved
  aim sat 2.71 / 2.01 deg off. The blue table's green 6-ball moved
  along the solved aim line in the same change, so the red ball still
  wins the target pick and the depicted shot stays flagged blocked.
  The bank table's guideline is the true aim too (fitted 63.29 deg vs
  the solver's 63.34 deg at full resolution), but it is only ~24 px
  long at working resolution, below the detector's Hough threshold of
  30, so the working-res detector never picks it up; that is
  pre-existing, unchanged by this slice, and the bank test asserts
  the solve, not the angle.
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
   corners, or tap Re-detect; or load a 7ft / 8ft / 9ft preset for your
   table size, then check the mask). Calibration is stored per game version.
5. Play. The green line is your aim, the yellow line is the target ball's
   path, the white line is the cue ball's deflection, the red ring is the
   pocket. Every other reachable pocket is also drawn, faint grey — the
   best shot is the highlighted one. Turn that off with **Show all shots**
   on the main screen if you only want the best shot. Use **Hide guides**
   on the floating bubble while shooting. If the lines are wrong, tap
   **Edit** on the bubble and drag the green (cue) and yellow (ghost)
   handles to correct them, then tap **Done**. The green ring is the ghost
   ball: where the cue ball's centre must be at impact.

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
  state (1-px nudge, clamp, hit test, reset), ball-group
  classification (disc sampling, stripe / solid / cue / 8-ball,
  ambiguous "?" cases), overlay labels (cut angle, pocket distance,
  rail-contact markers, ghost-ball ring), shot history + blocked-path
  flagging, table presets, calibration preview, bubble drag + lock
  ring, CV config + guideline verifier (parallel-edge dedup, spatial
  grid), ball-detector table ROI, black-frame state, display-name
  pins, aim-activity tracking (auto-hide while aiming), EDIT
  inactivity (20 s auto-exit), cue pick by guideline ray, session
  pot-rate tracking (shot / pot counting, settle, scratch, reminder),
  and the no-input-injection + manifest-guard tests (no
  dispatchGesture / AccessibilityService / UiAutomation /
  Instrumentation anywhere; permission set pinned to the five minimal
  permissions, MainActivity the only exported component). 439 tests.
- **Instrumented** (`app/src/androidTest`): the real pipeline on the two
  synthetic tables (green + blue felt) — table, 6 pockets, cue ball, 8+
  balls, guideline, and a solved shot with the expected target and pocket,
  plus the allShots ranking (best = first, > 1 candidate); a pure-black
  frame is reported as secure; the overlay manager shows one small window
  per line (4 for the best shot, +2 faint windows per extra candidate when
  "Show all shots" is on, 4 when it is off) and hides them on toggle /
  black frame; EDIT mode freezes the shot, shows 2 anchor handles, follows
  a ghost-anchor drag, keeps drawing with no guideline, and resumes live
  detection on Done; the overlay service runs in demo mode; the
  ball-detector ROI tests (green table cue + target through the ROI,
  a rail ball near the corner, no-table fallback); calibration-activity
  tests; the practice activity launches, solves the default layout,
  and nudges 1 px. 49 tests.
- **Not yet tested (needs a real phone + the game)**: FLAG_SECURE blackout,
  `isObscured` behaviour while shooting, real-table CV accuracy across
  table skins and resolutions. See `../phone-test/PHONE-TEST-PLAN.md` in
  the research project for the phone test package.

## Project layout

```
app/src/main/java/com/poolguide/tool/
  geometry/   Vec2, ShotSolver, PathBlock, BlockedPathLabel,
              BallGroup, BallGroupTag, CutAngleLabel, GhostBallRing,
              PocketDistanceLabel, RailContactMarker, CushionCorrection
              (pure Kotlin, JVM-testable)
  cv/         CvConfig, TableGeometry, TableDetector, BallDetector,
              BallClassifier, GuidelineDetector, CvPipeline, CuePicker,
              CalibrationPreview, LiveFallback, OpenCvInit,
              RatioTableGeometry (fallback table from frame size)
  capture/    CaptureService, PreviewBus, OverlayBus, FramePacer,
              CaptureDisplayNames (virtual display + notification names)
              (MediaProjection, foreground svc; 15 FPS pacing)
  overlay/    LineView, LineOverlayManager, OverlayService, ManualAim,
              AimActivityTracker (auto-hide while aiming),
              EditInactivityTracker (20 s EDIT auto-exit), BubbleDrag,
              LineSmoother, LockRingView
              (one small window per line; specialUse foreground svc;
              EDIT mode: frozen shot + draggable cue/ghost anchors)
  data/       CalibrationStore, GameProfile (per-version calibration),
              TablePreset (7ft/8ft/9ft table-size presets),
              ShotHistory (last 8 solved shots, with the blocked flag),
              DiagnosticsLog (persisted session log),
              ShotOutcomeTracker (session pot-rate stat)
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
  `line_tracker.py`: verifier (edge points within 1.5 px, min 20 inliers,
  top 3), LineTracker (alpha 40, fade 8/frame), HSV white window
  (0,0,200)-(180,30,255). Its bilateral pre-filter (d=20, sigmaColor=200,
  sigmaSpace=1) was replaced in v1.0.32 by a 5x5 Gaussian blur (7.6x
  faster, same accuracy). The
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
