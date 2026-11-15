package com.poolguide.tool.cv

/**
 * Detection constants. Three sources, all cited in the README:
 * - kaigiii/8-Ball-Pool-Aim-Assist-for-macOS (verifier / LineTracker
 *   parameters and structure; the pre-filter was its bilateral, now a
 *   Gaussian blur, see GAUSSIAN_BLUR_KSIZE below).
 * - ChatGPT's Android overlay guide, Nov 13 2026 (HoughCircles parameters,
 *   downscale target, frame-skipping strategy).
 * - workua94-afk/8ball-aim-master, via the Nov 13 GitHub search
 *   (guideline Canny 50/150 + HoughLinesP 30/15/8 on the table ROI, and
 *   the 15-frame cache idea): the kaigiii Canny (500, 350) killed the
 *   thin 2 px guideline after the 720-wide downscale — the instrumented
 *   tests proved it (0 verified segments on the green table).
 */
object CvConfig {

    /** Working width for analysis. Height follows the capture aspect ratio. */
    const val WORK_WIDTH = 720

    /** Capture scale (Scugalug measured targets): the VirtualDisplay is
     * 0.75x the screen (960x540 for a 1280x720 game) — the pipeline
     * downscales to 720 wide anyway, so the extra capture pixels only
     * cost bandwidth and battery. */
    const val CAPTURE_SCALE = 0.75
    /** Frame-rate cap (Scugalug measured targets): 15 FPS is enough for
     * an aim overlay — the table does not move between frames, and
     * anything faster only heats the phone. The pacer enforces this
     * (see FramePacer); 1000/15 rounds down to 66 ms. */
    const val MAX_FPS = 15
    const val MIN_FRAME_INTERVAL_MS = 1000L / MAX_FPS // 66 ms

    /** A frame whose mean grey value is below this is treated as black
     * (FLAG_SECURE blackout or a dead capture). */
    const val BLACK_FRAME_MEAN = 8.0

    // ---- Guideline detection (kaigiii structure, 8ball-aim-master params) ----
    /** Guideline pre-filter (v1.0.32, was the kaigiii bilateral d=20,
     * sigmaColor 200, sigmaSpace 1): the bilateral was 92% of the
     * guideline stage (50.6 ms/run on the harness replica) while its
     * spatial smoothing was nearly a no-op (sigmaSpace 1.0). A 5x5
     * Gaussian blur is 7.6x faster per run (7.9 ms) for +0.02 deg mean
     * angle error, max unchanged, zero change to shots, cut error, or
     * ball recall on the 15 baseline + 7 adversarial tables. 5x5, not
     * 3x3: it smooths sensor noise a little more and its max angle
     * error matches the bilateral exactly. */
    const val GAUSSIAN_BLUR_KSIZE = 5
    /** Canny thresholds (8ball-aim-master 50/150): the guideline is ~2 px
     * wide at the working resolution, so its Sobel response is far below
     * the old 500/350 pair, which kept almost no edges at all. */
    const val CANNY_LOW = 50.0
    const val CANNY_HIGH = 150.0
    /** Guideline Hough (8ball-aim-master): threshold, min line length,
     * max line gap, on the table-ROI crop. */
    const val HOUGH_GUIDELINE_THRESHOLD = 30.0
    const val HOUGH_GUIDELINE_MIN_LENGTH = 15.0
    const val HOUGH_GUIDELINE_MAX_GAP = 8.0
    /** Short-line fallback (kaigiii): only run when the main Hough finds
     * no verified segment — it catches a very short guideline (balls
     * nearly touching) without paying its ~150 junk candidates per frame. */
    const val HOUGH_SHORT_THRESHOLD = 10.0
    const val HOUGH_SHORT_MIN_LENGTH = 1.0
    const val HOUGH_SHORT_MAX_GAP = 1.0
    /** Verifier: an edge point counts as support if within this distance. */
    const val VERIFY_MAX_DIST_PX = 1.5
    const val VERIFY_MIN_INLIERS = 20
    const val VERIFY_KEEP_TOP = 3
    /** Robust refit (yasin IRLS/Tukey lead, one guarded pass): a refitted
     * segment is kept only when it stays close to the verified one. */
    const val REFIT_MAX_ANGLE_DEG = 5.0
    const val REFIT_MIN_LENGTH_FRACTION = 0.5
    /** LineTracker: alpha starts here, fades by this much per lost frame. */
    const val TRACKER_ALPHA_START = 40
    const val TRACKER_ALPHA_FADE = 8
    /** A new segment matches a tracked one if angle and endpoints are close. */
    const val TRACKER_MAX_ANGLE_DEG = 10.0
    const val TRACKER_MAX_ENDPOINT_DIST_PX = 30.0
    /** White-guideline HSV window (kaigiii): H any, S 0-30, V 200-255. */
    val GUIDELINE_HSV_LOWER = doubleArrayOf(0.0, 0.0, 200.0)
    val GUIDELINE_HSV_UPPER = doubleArrayOf(180.0, 30.0, 255.0)

    // ---- Ball detection (ChatGPT HoughCircles params, 720p working res) ----
    const val HOUGH_CIRCLES_DP = 1.0
    const val HOUGH_CIRCLES_MIN_DIST = 20.0
    const val HOUGH_CIRCLES_PARAM1 = 100.0
    /** Accumulator threshold: 15 missed the stripe balls (7 of 9 on the
     * harness); 12 finds them (5/5 on t14 with the blur off). 10 adds
     * nothing but noise, so 12 is the floor. */
    const val HOUGH_CIRCLES_PARAM2 = 12.0
    const val HOUGH_CIRCLES_MIN_RADIUS = 8
    const val HOUGH_CIRCLES_MAX_RADIUS = 18
    /** Run HoughCircles every Nth frame (HSV contours run every frame). */
    const val HOUGH_CIRCLES_EVERY_N_FRAMES = 3
    /** Run the guideline Hough every Nth frame. v1.0.38: 2 to 3 — the
     * tracker already fades a lost segment over 5 frames (alpha 40,
     * fade 8), so a 3-frame gap is covered; the harness (15 baseline
     * + 8 adversarial tables) is byte-identical on every accuracy
     * field at cadence 3, and the baseline mean frame time drops
     * ~3.5 ms (15.9 to 12.3 ms over 3 runs each, noisy VM). */
    const val GUIDELINE_EVERY_N_FRAMES = 3
    /** Re-detect the table every Nth frame (or immediately when lost). */
    const val TABLE_REDETECT_EVERY_N_FRAMES = 30
    /** Median blur kernel before HoughCircles. 0 = OFF (v1.0.3): the
     * blur smears the white stripe band into the ball and Hough loses
     * stripe balls; with it off, param2 12 finds 5/5 stripes on t14. */
    const val MEDIAN_BLUR_KSIZE = 0
    /** Contour ball radius range at the working resolution. The floor is
     * 6 px (was 4): the game's own ghost-ball marker is a ~4.3 px black
     * disc that the old floor reported as a ball (t13/t15 false
     * positives); a real ball is 8-18 px at this resolution. */
    const val BALL_MIN_RADIUS_PX = 6.0
    const val BALL_MAX_RADIUS_PX = 25.0
    /** Detected ball radius in px at the 720-wide working resolution
     * (synthetic demo tables: 16 px at 1280 wide -> ~9 px at 720 wide).
     * The per-version default in GameProfile; the pipeline uses the
     * actually detected radii when it has them. */
    const val BALL_RADIUS_PX_AT_720 = 9.0
    /** A contour is ball-like when area / (pi * r^2) is above this. */
    const val BALL_MIN_CIRCULARITY = 0.55
    /** Two detections closer than this are the same ball (dedup). */
    const val BALL_DEDUP_DIST_PX = 10.0
    /** Nearest-neighbour tracking gate between frames. */
    const val BALL_TRACK_GATE_PX = 20.0
    /** A tracked ball is dropped after this many missed frames. */
    const val BALL_TRACK_MISS_LIMIT = 5

    /** White-ball V floor (v1.0.3, was 200): a dim white cue ball is
     * V~147 at 0.6x lighting, so the old floor classified it RED and no
     * shot was solved (t02/t09). 140 keeps the floor above the felt and
     * background (V<=110 on the harness tables) and above any grey felt
     * pixel the S<40 rule could catch. */
    const val WHITE_V_FLOOR = 140.0
    /** Stripe-band V floor (v1.0.3, was 180): a dim stripe band is
     * V~147, the same 0.6x lighting as the dim cue ball. */
    const val STRIPE_V_FLOOR = 130.0
    /** Felt-relative white V floor (v1.0.34, dim-light cue fix): the
     * fixed floors above miss a dim white cue ball (V~98 at 0.4x
     * light, felt V~56): the cue is found but its disc samples
     * white_fraction 0, so it reads SOLID and the pipeline reports
     * "Cue ball not found". The cue is consistently ~1.75x the
     * felt's V (245/140 at 1.0x, 147/84 at 0.6x, 98/56 at 0.4x),
     * so the floor follows the felt: max(90, feltV + 30) when the
     * felt mean is known, the fixed floor when it is not. The 90
     * absolute floor keeps the rule above any grey felt pixel on
     * near-black felt (V 35) and below any real white ball down to
     * ~0.37x light; below that (0.3x: cue V~74) the cue stays below
     * the floor by design (harness table a08 pins the boundary).
     * [fixedFloor] is WHITE_V_FLOOR (140) for the white window /
     * colorAt and STRIPE_V_FLOOR (130) for the stripe white test;
     * the same felt-relative value feeds both. */
    fun feltRelativeWhiteVFloor(feltHsv: DoubleArray?, fixedFloor: Double): Double {
        val feltV = feltHsv?.getOrNull(2) ?: return fixedFloor
        return (feltV + 30.0).coerceAtLeast(90.0)
    }
    /** Pocket-exclusion margin (v1.0.3, was +3 px): with the pocket
     * model fixed (inset 0, below) the disc-overlap test needs no extra
     * margin; the +3 px ate real balls 22.5 px from a pocket (t05). */
    const val POCKET_EXCLUSION_MARGIN_PX = 0.0

    /** Table-lock gate (v1.0.3, Bloro lead): the table rect must stay
     * within [TABLE_LOCK_TOLERANCE_PX] on all four corners for
     * [TABLE_LOCK_FRAMES] consecutive frames (75 at the 15 FPS cap =
     * 5 s) before the overlay trusts it. */
    const val TABLE_LOCK_FRAMES = 75
    const val TABLE_LOCK_TOLERANCE_PX = 2.0
    /** Ratio-fallback continuation window (v1.0.5): once a real
     * detection has locked the table, the fallback rect may stand in
     * for it for at most this many frames (150 at the 15 FPS cap =
     * 10 s) while detection is briefly lost. A guess can never lock a
     * table on its own, and past this window the lock drops and the
     * pipeline reports no table until a real detection re-locks. */
    const val FALLBACK_CONTINUATION_FRAMES = 150

    // ---- Table (felt) detection ----
    /** Green felt HSV window (OpenCV hue 0-179). V floor 30 (was 50):
     * a near-black felt is V~35 and the old floor killed the whole
     * pipeline on it; the S>=60 rule still excludes the dark
     * background (S~0). */
    val FELT_GREEN_LOWER = doubleArrayOf(35.0, 60.0, 30.0)
    val FELT_GREEN_UPPER = doubleArrayOf(85.0, 255.0, 255.0)
    /** Blue felt HSV window (some table skins). V floor 30, as green. */
    val FELT_BLUE_LOWER = doubleArrayOf(90.0, 60.0, 30.0)
    val FELT_BLUE_UPPER = doubleArrayOf(130.0, 255.0, 255.0)
    /** Brown felt HSV window (v1.0.3; measured H 15, S 139, V 110 on
     * t12). TIGHTLY fitted on purpose: the wooden rail frame around
     * the felt is (10,129,101) — nearly the same hue — so a loose
     * brown window matches the rail too and the detected rect grows
     * to the rail's outer edge (29 px corner error on t12). The tight
     * window excludes the rail on all three channels. It is fitted to
     * the synthetic measurement; on a real brown table the rail/felt
     * separation may differ — recalibrate via GameProfile if a real
     * brown table ever shows up (green/blue are the standard skins). */
    val FELT_BROWN_LOWER = doubleArrayOf(13.0, 135.0, 105.0)
    val FELT_BROWN_UPPER = doubleArrayOf(22.0, 255.0, 255.0)
    /** Pocket inset as a fraction of the table width. FITTED from
     * ground truth (v1.0.3, was 0.015): the drawn pockets sit exactly
     * on the table corners, so the model inset is 0. The 1.5% inset
     * was the dominant cut-angle error source (t06: 4.24 deg,
     * t08: 2.02 deg; after the fix the mean cut error is 0.32 deg). */
    const val POCKET_INSET_FRACTION = 0.0
    /** Pocket radius as a fraction of the table width (working res):
     * pockets are ~24 px across at 720 wide. Used for the disc-overlap
     * pocket exclusion (a ball's disc can never overlap a pocket's). */
    const val POCKET_RADIUS_FRACTION = 0.02
    /** A quad whose edges are within this many degrees of horizontal /
     * vertical counts as axis-aligned: the table is rendered aligned with
     * the screen, so the bounding rect then gives symmetric corners
     * (approxPolyDP cuts the rounded corners asymmetrically and the
     * pocket notches skew the fit — that skew flipped a tied pocket
     * pick on the synthetic green table). */
    const val AXIS_ALIGNED_MAX_ANGLE_DEG = 6.0
}
