package com.poolguide.tool

import com.poolguide.tool.geometry.BallGroup
import com.poolguide.tool.geometry.BallGroupClassifier
import com.poolguide.tool.geometry.BallGroupTag
import com.poolguide.tool.geometry.DiscStats
import com.poolguide.tool.geometry.Segment
import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Ball group identification (v1.0.14). All numbers pinned against the
 * real classifier first (kotlinc probe, group14-probe/kotlin/Probe.kt)
 * and against the measured harness stats (all 85 ground-truth balls
 * on the 15 harness tables — see cv-harness/results-v1.0.14-after.json):
 * cue balls measure white fraction 1.000 (even at 0.6x light, V 147);
 * stripes 0.651–0.791; solids 0.000; the 8-ball mean V 15. The
 * thresholds sit far from every measured value on synthetic data.
 */
class BallGroupTest {

    private fun stats(
        whiteFraction: Double,
        meanV: Double,
        meanS: Double,
        sampledPx: Int = 46,
        expectedPx: Int = 46,
        // v1.0.69: saturated-colour fraction of the disc (S > 150).
        // A stripe's caps read ~1 - whiteFraction; a cream cue reads
        // ~0.00-0.11. Defaults 0.0 for the older cases that predate it.
        coloredFraction: Double = 0.0,
        // v1.0.69: max per-sector saturated-colour fraction. A
        // stripe's caps live in one sector (the a09 blue stripe
        // reads 0.56 at its detected radius); a cream cue's disc is
        // cream in every sector (0.00-0.10 at its detected radius).
        // Defaults 0.0 for the older cases that predate it.
        maxSectorColoredFraction: Double = 0.0
    ) = DiscStats(whiteFraction, meanV, meanS, sampledPx, expectedPx, coloredFraction, maxSectorColoredFraction)

    // ---- Classification: measured harness stats ----

    @Test
    fun classify_cueBall_brightAndDim() {
        assertEquals(BallGroup.CUE, BallGroupClassifier.classify(stats(1.0, 245.0, 0.0)))
        // A cue ball at 0.6x light still reads white fraction 1.000.
        assertEquals(BallGroup.CUE, BallGroupClassifier.classify(stats(1.0, 147.0, 0.0)))
    }

    @Test
    fun classify_stripeBall_measuredRange() {
        // v1.0.69: the coloured fraction is pinned too — a stripe's
        // caps are saturated colour (~1 - white fraction), which is
        // what keeps it a stripe under the cream-cue rule. The caps
        // also colour one sector (max sector 0.56-0.80), so the
        // sector test agrees.
        assertEquals(BallGroup.STRIPE, BallGroupClassifier.classify(stats(0.651, 237.0, 69.7, coloredFraction = 0.35, maxSectorColoredFraction = 0.80)))
        assertEquals(BallGroup.STRIPE, BallGroupClassifier.classify(stats(0.791, 247.7, 50.8, coloredFraction = 0.21, maxSectorColoredFraction = 0.70)))
    }

    @Test
    fun classify_solidBall_measured() {
        assertEquals(BallGroup.SOLID, BallGroupClassifier.classify(stats(0.0, 200.0, 255.0)))
        // A dark solid (maroon at 0.6x light, V 96) is a solid, not
        // the 8-ball: its saturation is high.
        assertEquals(BallGroup.SOLID, BallGroupClassifier.classify(stats(0.0, 96.0, 255.0)))
    }

    @Test
    fun classify_eightBall_measured() {
        assertEquals(BallGroup.EIGHT, BallGroupClassifier.classify(stats(0.0, 15.0, 0.0)))
    }

    // ---- Classification: boundaries ----

    @Test
    fun classify_cueVsStripeBoundary() {
        // 0.85 is the cue floor: at it, cue; just below it, stripe.
        assertEquals(BallGroup.CUE, BallGroupClassifier.classify(stats(0.85, 245.0, 0.0)))
        assertEquals(BallGroup.STRIPE, BallGroupClassifier.classify(stats(0.84, 245.0, 0.0)))
    }

    @Test
    fun classify_stripeVsAmbiguousBoundary() {
        // 0.20 is the stripe floor: at it, stripe; just below it,
        // the ambiguous middle — UNKNOWN, never guessed. v1.0.69: a
        // stripe at the floor still shows its saturated caps
        // (coloured ~0.80), which is what keeps it out of the
        // cream-cue rule.
        assertEquals(BallGroup.STRIPE, BallGroupClassifier.classify(stats(0.20, 200.0, 100.0, coloredFraction = 0.80)))
        assertEquals(BallGroup.UNKNOWN, BallGroupClassifier.classify(stats(0.19, 200.0, 100.0, coloredFraction = 0.81)))
    }

    @Test
    fun classify_solidVsAmbiguousBoundary() {
        // 0.10 is the solid ceiling: at it, solid; just above it,
        // the ambiguous middle — UNKNOWN, never guessed. A real solid
        // showing its number circle reads ~0.05–0.16, so it can land
        // here and read "?" — the honest answer, not a misread.
        assertEquals(BallGroup.SOLID, BallGroupClassifier.classify(stats(0.10, 200.0, 255.0)))
        assertEquals(BallGroup.UNKNOWN, BallGroupClassifier.classify(stats(0.11, 200.0, 255.0)))
        assertEquals(BallGroup.UNKNOWN, BallGroupClassifier.classify(stats(0.16, 200.0, 200.0)))
    }

    @Test
    fun classify_eightNeedsLowSaturationToo() {
        // Dark but saturated is a dark solid, not the 8-ball: the
        // 8-ball check needs mean V < 60 AND mean S < 80.
        assertEquals(BallGroup.SOLID, BallGroupClassifier.classify(stats(0.0, 50.0, 200.0)))
    }

    @Test
    fun classify_tooSmallOrCutOffDiscIsUnknown() {
        // Fewer than 15 samples: too small to judge.
        assertEquals(
            BallGroup.UNKNOWN,
            BallGroupClassifier.classify(stats(1.0, 245.0, 0.0, sampledPx = 10))
        )
        // Less than half the expected samples: cut off by the frame
        // edge (a full disc samples within a few percent of expected).
        assertEquals(
            BallGroup.UNKNOWN,
            BallGroupClassifier.classify(stats(1.0, 245.0, 0.0, sampledPx = 20))
        )
        // Exactly half the expected samples is the coverage floor:
        // it proceeds to the white-fraction decision.
        assertEquals(
            BallGroup.CUE,
            BallGroupClassifier.classify(stats(1.0, 245.0, 0.0, sampledPx = 23))
        )
    }

    // ---- Tag text ----

    @Test
    fun tagText_eachGroup() {
        assertEquals("C", BallGroup.CUE.tagText())
        assertEquals("8", BallGroup.EIGHT.tagText())
        assertEquals("S", BallGroup.SOLID.tagText())
        assertEquals("ST", BallGroup.STRIPE.tagText())
        assertEquals("?", BallGroup.UNKNOWN.tagText())
    }

    // ---- Occlusion ----

    @Test
    fun isOccluded_overlappingDiscsOnly() {
        val center = Vec2(100.0, 100.0)
        // Centres 10 px apart at r = 9: the discs overlap.
        assertTrue(
            BallGroupClassifier.isOccluded(center, 9.0, listOf(Vec2(110.0, 100.0) to 9.0))
        )
        // Tangent balls (18 px apart at r = 9) do not count.
        assertFalse(
            BallGroupClassifier.isOccluded(center, 9.0, listOf(Vec2(118.0, 100.0) to 9.0))
        )
        // 15 px apart is inside the 0.85 overlap fraction (15.3 px).
        assertTrue(
            BallGroupClassifier.isOccluded(center, 9.0, listOf(Vec2(115.0, 100.0) to 9.0))
        )
        // 16 px apart is outside it.
        assertFalse(
            BallGroupClassifier.isOccluded(center, 9.0, listOf(Vec2(116.0, 100.0) to 9.0))
        )
        assertFalse(
            BallGroupClassifier.isOccluded(center, 9.0, listOf(Vec2(200.0, 100.0) to 9.0))
        )
    }

    // ---- Tag placement: never on the aim line ----

    @Test
    fun tagAnchor_horizontalAimLine_offsetsPerpendicular() {
        val aim = Segment(Vec2(100.0, 100.0), Vec2(300.0, 100.0))
        val anchor = BallGroupTag.anchor(Vec2(100.0, 100.0), 9.0, listOf(aim))
        // 2.2 radii off the line, perpendicular to it.
        assertEquals(Vec2(100.0, 119.8), anchor)
        assertTrue(
            "anchor must sit at least 2.0 radii from the line",
            BallGroupTag.distanceToSegment(anchor, aim) >= 2.0 * 9.0
        )
    }

    @Test
    fun tagAnchor_diagonalAimLine_keepsClearance() {
        val diag = Segment(Vec2(0.0, 0.0), Vec2(100.0, 100.0))
        val anchor = BallGroupTag.anchor(Vec2(50.0, 50.0), 9.0, listOf(diag))
        assertTrue(
            "anchor must sit 2.2 radii from a diagonal line",
            abs(BallGroupTag.distanceToSegment(anchor, diag) - 2.2 * 9.0) < 0.01
        )
    }

    @Test
    fun tagAnchor_noLineThroughBall_sitsAbove() {
        val aim = Segment(Vec2(100.0, 100.0), Vec2(300.0, 100.0))
        // A ball nowhere near the aim line gets a tag straight above it.
        assertEquals(
            Vec2(200.0, 180.2),
            BallGroupTag.anchor(Vec2(200.0, 200.0), 9.0, listOf(aim))
        )
        // A line passing 100 px away (far beyond one radius) does not
        // count as passing through the ball either.
        val far = Segment(Vec2(0.0, 0.0), Vec2(400.0, 0.0))
        assertEquals(
            Vec2(200.0, 80.2),
            BallGroupTag.anchor(Vec2(200.0, 100.0), 9.0, listOf(far))
        )
    }

    // ---- Cream cue ball (v1.0.69, real-phone fix) ----

    @Test
    fun classify_creamCueBall_realPhoneMeasured() {
        // Jawad's real frame, cue ball at (412, 252), sampled at
        // radii 8/9/10 px: white 0.73/0.56/0.44, coloured
        // 0.00/0.05/0.11, mean S 27/45/66, mean V 188/179/165, max
        // sector 0.00/0.10/0.38. The pure-white rule misses it; the
        // cream rule must not.
        assertEquals(BallGroup.CUE, BallGroupClassifier.classify(stats(0.73, 188.0, 27.0, coloredFraction = 0.00, maxSectorColoredFraction = 0.00)))
        assertEquals(BallGroup.CUE, BallGroupClassifier.classify(stats(0.56, 179.0, 45.0, coloredFraction = 0.05, maxSectorColoredFraction = 0.10)))
        assertEquals(BallGroup.CUE, BallGroupClassifier.classify(stats(0.44, 165.0, 66.0, coloredFraction = 0.11, maxSectorColoredFraction = 0.38)))
    }

    @Test
    fun classify_creamCueBall_stripeWithLowColoredButHighSectorStaysStripe() {
        // The a09 blue stripe in the cluster: its white band is so
        // wide that its overall coloured fraction is only 0.11
        // (under the 0.15 ceiling), so the aggregate test alone
        // would call it a cream cue. Its caps still colour one
        // sector (max sector 0.56), so the sector test keeps it a
        // stripe. This is the a09 regression: without the sector
        // test the stripe won group-first over the real (occluded)
        // cue ball.
        assertEquals(BallGroup.STRIPE, BallGroupClassifier.classify(stats(0.78, 241.0, 31.0, coloredFraction = 0.11, maxSectorColoredFraction = 0.56)))
    }

    @Test
    fun classify_creamCueBall_sectorBoundary() {
        // Sector cap: max sector 0.40 passes (cue), 0.41 does not
        // (stripe) — with the overall coloured fraction under the
        // ceiling in both cases, so only the sector decides.
        assertEquals(BallGroup.CUE, BallGroupClassifier.classify(stats(0.50, 170.0, 40.0, coloredFraction = 0.10, maxSectorColoredFraction = 0.40)))
        assertEquals(BallGroup.STRIPE, BallGroupClassifier.classify(stats(0.50, 170.0, 40.0, coloredFraction = 0.10, maxSectorColoredFraction = 0.41)))
    }

    @Test
    fun classify_creamCueBall_stripeWithBigBandStaysStripe() {
        // A stripe with a big white band still shows its saturated
        // caps (coloured ~0.21-0.35), so it stays a stripe even
        // though its white fraction sits in the cream range.
        assertEquals(BallGroup.STRIPE, BallGroupClassifier.classify(stats(0.70, 200.0, 60.0, coloredFraction = 0.25)))
        assertEquals(BallGroup.STRIPE, BallGroupClassifier.classify(stats(0.79, 247.7, 50.8, coloredFraction = 0.21)))
    }

    @Test
    fun classify_creamCueBall_pureWhiteFragmentIsNotCream() {
        // A white UI fragment (mean S ~5, almost no saturation) is
        // not cream — the cream rule needs a warm mean S, so the
        // fragment is not picked as the cue ball.
        assertEquals(BallGroup.STRIPE, BallGroupClassifier.classify(stats(0.83, 187.0, 5.0, coloredFraction = 0.00)))
    }

    @Test
    fun classify_creamCueBall_boundaries() {
        // Cream floor: white 0.20 with no saturated colour, warm and
        // bright, is a cue; white 0.19 is not.
        assertEquals(BallGroup.CUE, BallGroupClassifier.classify(stats(0.20, 150.0, 20.0, coloredFraction = 0.10)))
        assertEquals(BallGroup.UNKNOWN, BallGroupClassifier.classify(stats(0.19, 150.0, 20.0, coloredFraction = 0.10)))
        // Coloured ceiling: coloured 0.15 passes, 0.16 does not.
        assertEquals(BallGroup.CUE, BallGroupClassifier.classify(stats(0.50, 170.0, 40.0, coloredFraction = 0.15)))
        assertEquals(BallGroup.STRIPE, BallGroupClassifier.classify(stats(0.50, 170.0, 40.0, coloredFraction = 0.16)))
        // Brightness floor: mean V 140 passes, 139 does not.
        assertEquals(BallGroup.CUE, BallGroupClassifier.classify(stats(0.50, 140.0, 40.0, coloredFraction = 0.10)))
        assertEquals(BallGroup.STRIPE, BallGroupClassifier.classify(stats(0.50, 139.0, 40.0, coloredFraction = 0.10)))
    }
}
