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
 *   by the frame edge, mostly hidden behind another ball (so few of
 *   its own pixels survive the neighbour-aware sampling that the
 *   coverage rule trips), or its white fraction sits in the
 *   ambiguous middle.
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
    val expectedPx: Int,
    /** Fraction of sampled pixels that are saturated colour
     * (S > 150) — v1.0.69, real-phone fix. A stripe ball's caps are
     * saturated colour, so a stripe with a big white band still
     * measures a clear coloured fraction (~0.21-0.35); the real
     * (cream) cue ball's disc is cream + white + the guideline's
     * black edges, almost no saturated colour (~0.00-0.11). The
     * cream-cue rule below uses it to tell a cream cue from a
     * stripe with a big white band, which the white fraction alone
     * cannot separate. Defaults 0.0 so older call sites keep
     * compiling (they predate the stat). */
    val coloredFraction: Double = 0.0,
    /** Max per-sector saturated-colour fraction (v1.0.69): the disc
     * is split into four sectors by dominant axis (top/bottom where
     * |dy| > |dx|, left/right otherwise); this is the highest
     * coloured fraction of any one sector. A stripe ball's coloured
     * caps live in one or two opposite sectors (top for the a09 blue
     * stripe: max sector 0.56 at its detected radius), so its max
     * sector is high even when its overall coloured fraction is low
     * (a09 reads overall 0.11). A cream cue ball's disc is cream in
     * every sector (max sector 0.00-0.10 at its detected radius).
     * The sector test is orientation-independent (caps in any
     * direction land in some sector). A rim-only test does not
     * separate them (both read rim ~0.21 — the rim mixes the caps
     * with the white band's sides). Sectors with < 4 samples report
     * 0.0 (noise, not evidence). Defaults 0.0 for older call sites. */
    val maxSectorColoredFraction: Double = 0.0,
    /** The felt's mean V (v1.0.70), for the felt-relative cream-cue
     * brightness floor in [BallGroupClassifier]. -1.0 means the felt
     * is unknown and the absolute 140 floor applies. Set by
     * [com.poolguide.tool.cv.BallClassifier.sampleDisc] from the
     * table detector's felt mean; unit tests pass it explicitly for
     * the dim-light cases. */
    val feltMeanV: Double = -1.0
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

    /** A cream cue ball (v1.0.69, real-phone fix): the real game's
     * cue ball is cream, not pure white — on Jawad's phone it
     * measures white fraction 0.44-0.73 (the game's black-edged
     * guideline crosses its disc), mean V 165-188, mean S 27-66,
     * coloured fraction 0.00-0.11. The pure-white rule above misses
     * it, and the stripe rule below would claim it. A cream cue
     * reads: white fraction in [0.20, 0.85), almost no saturated
     * colour in its disc (a stripe with a big white band still
     * shows its saturated caps, coloured fraction ~0.21-0.35), a
     * warm mean saturation (a white UI fragment or a stripe band
     * reads mean S under ~10 — pure white, not cream), and bright
     * overall. Bounds, measured on Jawad's real frame at radii
     * 8/9/10 px: white 0.73/0.56/0.44, coloured 0.00/0.05/0.11,
     * mean S 27/45/66, mean V 188/179/165. */
    const val CREAM_CUE_MIN_WHITE_FRACTION = 0.20
    const val CREAM_CUE_MAX_COLORED_FRACTION = 0.15
    const val CREAM_CUE_MIN_MEAN_S = 15.0
    const val CREAM_CUE_MIN_MEAN_V = 140.0

    /** Felt-relative cream-cue brightness floor (v1.0.70): when the
     * felt mean is known ([DiscStats.feltMeanV] >= 0), the floor is
     * max([CREAM_CUE_DIM_MIN_MEAN_V], feltV + [CREAM_CUE_FELT_V_ABOVE])
     * instead of the absolute 140. A real cream cue at 0.3x light is
     * V~74 — far below 140, but still ~1.8x its felt (V~41) — while a
     * dim stripe's caps keep it out of the cream rule on coloured
     * fraction / sector, not on brightness, so the brightness floor
     * does not need to separate cue from stripe and can follow the
     * felt. The 60 absolute floor keeps it above the EIGHT band
     * (mean V < 60) and above any grey felt pixel. */
    const val CREAM_CUE_FELT_V_ABOVE = 15.0
    const val CREAM_CUE_DIM_MIN_MEAN_V = 60.0

    /** The cream-cue brightness floor for [stats]: felt-relative
     * when the felt is known, the absolute 140 otherwise (older
     * call sites and the unit-test boundary cases, which pass no
     * felt and still pin 140/139). */
    fun creamCueMinMeanV(stats: DiscStats): Double {
        if (stats.feltMeanV < 0.0) return CREAM_CUE_MIN_MEAN_V
        return maxOf(CREAM_CUE_DIM_MIN_MEAN_V, stats.feltMeanV + CREAM_CUE_FELT_V_ABOVE)
    }

    /** Sector cap for the cream-cue rule (v1.0.69): a stripe ball's
     * coloured caps live in one or two opposite sectors of its disc
     * (the a09 blue stripe reads max sector 0.56 at its detected
     * radius, overall coloured only 0.11), while a cream cue ball's
     * disc is cream in every sector (max sector 0.00-0.10 at its
     * detected radius). 0.40 sits far from both. */
    const val CREAM_CUE_MAX_SECTOR_COLORED_FRACTION = 0.40

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

    fun classify(stats: DiscStats): BallGroup {
        if (stats.sampledPx < MIN_SAMPLES) return BallGroup.UNKNOWN
        if (stats.sampledPx < (MIN_COVERAGE_FRACTION * stats.expectedPx).toInt()) {
            return BallGroup.UNKNOWN
        }
        if (stats.meanV < BLACK_MAX_V && stats.meanS < BLACK_MAX_S) {
            return BallGroup.EIGHT
        }
        if (stats.whiteFraction >= CUE_WHITE_MIN_FRACTION) return BallGroup.CUE
        // Cream cue (v1.0.69): the real cue ball is cream, not pure
        // white — see CREAM_CUE_* above. Checked before STRIPE: a
        // cream cue's white fraction (0.44-0.73) sits inside the
        // stripe range, and only the near-absence of saturated colour
        // in its disc tells it apart from a stripe with a big band.
        // v1.0.70: the brightness floor is felt-relative
        // (creamCueMinMeanV), so a cream cue at 0.3x light (mean V
        // ~74, felt V ~41) still passes; the absolute 140 floor is
        // kept only when the felt is unknown.
        if (stats.whiteFraction >= CREAM_CUE_MIN_WHITE_FRACTION &&
            stats.coloredFraction <= CREAM_CUE_MAX_COLORED_FRACTION &&
            stats.maxSectorColoredFraction <= CREAM_CUE_MAX_SECTOR_COLORED_FRACTION &&
            stats.meanS >= CREAM_CUE_MIN_MEAN_S &&
            stats.meanV >= creamCueMinMeanV(stats)
        ) return BallGroup.CUE
        if (stats.whiteFraction >= STRIPE_MIN_FRACTION) return BallGroup.STRIPE
        if (stats.whiteFraction <= SOLID_MAX_FRACTION) return BallGroup.SOLID
        return BallGroup.UNKNOWN
    }
}
