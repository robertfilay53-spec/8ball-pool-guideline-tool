package com.poolguide.tool.cv

/**
 * Detection constants. Two sources, both cited in the README:
 * - kaigiii/8-Ball-Pool-Aim-Assist-for-macOS (bilateral / Canny / dual
 *   HoughLinesP / verifier / LineTracker parameters).
 * - ChatGPT's Android overlay guide, Nov 13 2026 (HoughCircles parameters,
 *   downscale target, frame-skipping strategy).
 */
object CvConfig {

    /** Working width for analysis. Height follows the capture aspect ratio. */
    const val WORK_WIDTH = 720

    /** A frame whose mean grey value is below this is treated as black
     * (FLAG_SECURE blackout or a dead capture). */
    const val BLACK_FRAME_MEAN = 8.0

    // ---- Guideline detection (kaigiii) ----
    const val BILATERAL_D = 20
    const val BILATERAL_SIGMA_COLOR = 200.0
    const val BILATERAL_SIGMA_SPACE = 1.0
    const val CANNY_LOW = 500.0
    const val CANNY_HIGH = 350.0
    /** Long-line Hough: threshold, min line length, max line gap. */
    const val HOUGH_LONG_THRESHOLD = 20.0
    const val HOUGH_LONG_MIN_LENGTH = 20.0
    const val HOUGH_LONG_MAX_GAP = 1.0
    /** Short-line Hough (catches the guideline when it is short). */
    const val HOUGH_SHORT_THRESHOLD = 10.0
    const val HOUGH_SHORT_MIN_LENGTH = 1.0
    const val HOUGH_SHORT_MAX_GAP = 1.0
    /** Verifier: an edge point counts as support if within this distance. */
    const val VERIFY_MAX_DIST_PX = 1.5
    const val VERIFY_MIN_INLIERS = 20
    const val VERIFY_KEEP_TOP = 3
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
    const val HOUGH_CIRCLES_PARAM2 = 25.0
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
}
