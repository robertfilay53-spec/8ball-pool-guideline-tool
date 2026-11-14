package com.poolguide.tool.cv

/**
 * Detection constants. Three sources, all cited in the README:
 * - kaigiii/8-Ball-Pool-Aim-Assist-for-macOS (bilateral / verifier /
 *   LineTracker parameters and structure).
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
    const val BILATERAL_D = 20
    const val BILATERAL_SIGMA_COLOR = 200.0
    const val BILATERAL_SIGMA_SPACE = 1.0
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
    /** Accumulator threshold: 25 missed the stripe balls and the cue on
     * the synthetic tables (sweep, Nov 14); 15 finds them, 12 adds
     * nothing but noise. */
    const val HOUGH_CIRCLES_PARAM2 = 15.0
    const val HOUGH_CIRCLES_MIN_RADIUS = 8
    const val HOUGH_CIRCLES_MAX_RADIUS = 18
    /** Run HoughCircles every Nth frame (HSV contours run every frame). */
    const val HOUGH_CIRCLES_EVERY_N_FRAMES = 3
    /** Run the guideline Hough every Nth frame. */
    const val GUIDELINE_EVERY_N_FRAMES = 2
    /** Re-detect the table every Nth frame (or immediately when lost). */
    const val TABLE_REDETECT_EVERY_N_FRAMES = 30
    /** Median blur kernel before HoughCircles (reduces false circles). */
    const val MEDIAN_BLUR_KSIZE = 5
    /** Contour ball radius range at the working resolution. */
    const val BALL_MIN_RADIUS_PX = 4.0
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

    // ---- Table (felt) detection ----
    /** Green felt HSV window (OpenCV hue 0-179). */
    val FELT_GREEN_LOWER = doubleArrayOf(35.0, 60.0, 50.0)
    val FELT_GREEN_UPPER = doubleArrayOf(85.0, 255.0, 255.0)
    /** Blue felt HSV window (some table skins). */
    val FELT_BLUE_LOWER = doubleArrayOf(90.0, 60.0, 50.0)
    val FELT_BLUE_UPPER = doubleArrayOf(130.0, 255.0, 255.0)
    /** Pocket inset as a fraction of the table width. */
    const val POCKET_INSET_FRACTION = 0.015
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
