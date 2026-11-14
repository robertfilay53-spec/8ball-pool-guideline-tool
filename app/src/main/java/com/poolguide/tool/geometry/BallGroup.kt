package com.poolguide.tool.geometry

/**
 * Ball group identification (v1.0.14). The CV pipeline already
 * detects ball positions; the player still had to tell solids from
 * stripes by eye. The classifier answers one question per ball —
 * which group is it in? — from the HSV pixels of its own disc:
 *
 * - CUE: the ball is (near-)all white.
 * - EIGHT: the ball is (near-)all black.
 * - STRIPE: a white band crosses the ball — a clear fraction of the
 *   disc's pixels are white.
 * - SOLID: a coloured ball with (almost) no white in its disc.
 * - UNKNOWN: never guess. The disc was too small to judge, cut off
 *   by the frame edge, occluded by another ball, or its white
 *   fraction sits in the ambiguous middle.
 *
 * The decision is pure Kotlin on [DiscStats] (see
 * [BallGroupClassifier]), so it is fully unit-testable on the JVM.
 * The OpenCV part — sampling the disc — lives in
 * cv/BallClassifier.kt; the harness mirrors both (see
 * cv-harness/pipeline.py).
 *
 * Thresholds, verified against all 85 ground-truth balls on the 15
 * harness tables (see cv-harness/results-v1.0.14-after.json):
 * cue balls measure white fraction 1.000 (even at 0.6x light, V 147);
 * stripes 0.651–0.791; solids 0.000; the 8-ball mean V 15. The
 * thresholds sit far from every measured value on synthetic data.
 * On a real table the margins are thinner: a solid showing its
 * number circle reads a white fraction around 0.05–0.16, so it can
 * land in the ambiguous middle and read UNKNOWN — that is the honest
 * answer, not a misclassification (see README honest limit 15).
 */
enum class BallGroup {
    CUE, EIGHT, SOLID, STRIPE, UNKNOWN;

    /** Tag text shown next to the ball in the live overlay and in
     * practice mode. */
    fun tagText(): String = when (this) {
        CUE -> "C"
        EIGHT -> "8"
        SOLID -> "S"
        STRIPE -> "ST"
        UNKNOWN -> "?"
    }
}

/** Pixel statistics of one ball's disc, sampled from the HSV frame. */
data class DiscStats(
    /** Fraction of sampled pixels that are white (S < 60 and
     * V > STRIPE_V_FLOOR). */
    val whiteFraction: Double,
    /** Mean V over the sampled pixels. */
    val meanV: Double,
    /** Mean S over the sampled pixels. */
    val meanS: Double,
    /** Pixels actually sampled inside the disc. */
    val sampledPx: Int,
    /** Samples expected for a full disc of this radius at the sample
     * step; a disc cut off by the frame edge samples far fewer. */
    val expectedPx: Int
)

/** The decision half of ball-group identification. Pure Kotlin —
 * fully unit-testable on the JVM. */
object BallGroupClassifier {

    /** A disc with fewer samples than this is too small to judge. At
     * the 2 px sample step a minimum-radius ball (r = 6 px) samples
     * ~21 pixels inside 0.85r, so 15 leaves room for grid alignment
     * without ever accepting a 3-px speck. */
    const val MIN_SAMPLES = 15

    /** A disc sampling less than this fraction of its expected
     * samples is cut off (by the frame edge): UNKNOWN, never
     * guessed. A full disc samples within a few percent of the
     * expected count, so 0.5 is a loose tripwire, not a tight fit. */
    const val MIN_COVERAGE_FRACTION = 0.5

    /** The 8-ball is near-black: mean V below this AND mean S below
     * [BLACK_MAX_S]. The V bound is the same 60 the colour
     * classifier already uses for BLACK; the S bound keeps a dark
     * coloured solid out — a dark blue solid at 0.6x light still
     * measures V 96, S 255 on the harness. */
    const val BLACK_MAX_V = 60.0
    const val BLACK_MAX_S = 80.0

    /** A cue ball is (near-)all white: white fraction at or above
     * this. Measured 1.000 on every harness cue ball, including the
     * 0.6x-light ones; a stripe tops out near 0.79, so 0.85 leaves a
     * clear gap on both sides. */
    const val CUE_WHITE_MIN_FRACTION = 0.85

    /** A stripe's white band covers a clear fraction of the disc:
     * measured 0.651–0.791 on the harness. 0.20 is far below that —
     * a real band seen at a shallow angle still covers far more than
     * a fifth of the disc. */
    const val STRIPE_MIN_FRACTION = 0.20

    /** A solid has (almost) no white in its disc: measured 0.000 on
     * the harness (the synthetic solids have no number circle). A
     * real solid showing its number circle reads ~0.05–0.16, so
     * anything above 0.10 is not called a solid — it is UNKNOWN. */
    const val SOLID_MAX_FRACTION = 0.10

    /** Two discs overlap (one ball occludes the other) when their
     * centres are closer than this fraction of the touching
     * distance (r1 + r2). Tangent balls sit exactly at the touching
     * distance, so 0.85 leaves room for detection noise and only
     * fires on a clear overlap. */
    const val OCCLUSION_OVERLAP_FRACTION = 0.85

    fun classify(stats: DiscStats): BallGroup {
        if (stats.sampledPx < MIN_SAMPLES) return BallGroup.UNKNOWN
        if (stats.sampledPx < (MIN_COVERAGE_FRACTION * stats.expectedPx).toInt()) {
            return BallGroup.UNKNOWN
        }
        if (stats.meanV < BLACK_MAX_V && stats.meanS < BLACK_MAX_S) {
            return BallGroup.EIGHT
        }
        if (stats.whiteFraction >= CUE_WHITE_MIN_FRACTION) return BallGroup.CUE
        if (stats.whiteFraction >= STRIPE_MIN_FRACTION) return BallGroup.STRIPE
        if (stats.whiteFraction <= SOLID_MAX_FRACTION) return BallGroup.SOLID
        return BallGroup.UNKNOWN
    }

    /** True when the disc at [center] with [radiusPx] overlaps any
     * other detected disc: the two discs cannot both be fully
     * visible, so the overlapped ball is occluded and its group is
     * UNKNOWN, never guessed. [others] are the other detected balls
     * as (centre, radius) pairs — the caller excludes the ball
     * itself. */
    fun isOccluded(
        center: Vec2,
        radiusPx: Double,
        others: List<Pair<Vec2, Double>>
    ): Boolean = others.any { (otherCenter, otherRadius) ->
        center.distanceTo(otherCenter) <
            OCCLUSION_OVERLAP_FRACTION * (radiusPx + otherRadius)
    }
}
