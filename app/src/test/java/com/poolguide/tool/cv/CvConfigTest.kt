package com.poolguide.tool.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The felt-relative white V floors (v1.0.34, dim-light cue fix).
 * Pure rule, no OpenCV, so it is unit-testable here. All numbers are
 * measured harness values, pinned by a kotlinc probe on the real
 * CvConfig before the tests were written (see probe34/): the cue is
 * consistently ~1.75x the felt's V (245/140 at 1.0x, 147/84 at
 * 0.6x, 98/56 at 0.4x, 73/41 at 0.3x). */
class CvConfigTest {

    @Test
    fun whiteFloor_unknownFelt_keepsFixedFloors() {
        assertEquals(140.0, CvConfig.feltRelativeWhiteVFloor(null, CvConfig.WHITE_V_FLOOR), 0.0)
        assertEquals(130.0, CvConfig.feltRelativeWhiteVFloor(null, CvConfig.STRIPE_V_FLOOR), 0.0)
    }

    @Test
    fun whiteFloor_dim04_felt56_is90AndCue98Passes() {
        // Harness table a04: felt V 56, cue V 98. The old fixed
        // floors (140 / 130) lost the cue at every step, so it read
        // SOLID and the pipeline reported "Cue ball not found".
        val felt = doubleArrayOf(73.0, 172.0, 56.0)
        assertEquals(90.0, CvConfig.feltRelativeWhiteVFloor(felt, CvConfig.WHITE_V_FLOOR), 0.0)
        assertEquals(90.0, CvConfig.feltRelativeWhiteVFloor(felt, CvConfig.STRIPE_V_FLOOR), 0.0)
        assertTrue(98.0 > CvConfig.feltRelativeWhiteVFloor(felt, CvConfig.WHITE_V_FLOOR))
        assertTrue(98.0 > CvConfig.feltRelativeWhiteVFloor(felt, CvConfig.STRIPE_V_FLOOR))
    }

    @Test
    fun whiteFloor_dim06_felt84_is114AndCue147Passes() {
        val felt = doubleArrayOf(73.0, 172.0, 84.0)
        assertEquals(114.0, CvConfig.feltRelativeWhiteVFloor(felt, CvConfig.WHITE_V_FLOOR), 0.0)
        assertTrue(147.0 > CvConfig.feltRelativeWhiteVFloor(felt, CvConfig.WHITE_V_FLOOR))
    }

    @Test
    fun whiteFloor_bright_felt140_is170AndCue245Passes() {
        val felt = doubleArrayOf(73.0, 172.0, 140.0)
        assertEquals(170.0, CvConfig.feltRelativeWhiteVFloor(felt, CvConfig.WHITE_V_FLOOR), 0.0)
        assertTrue(245.0 > CvConfig.feltRelativeWhiteVFloor(felt, CvConfig.WHITE_V_FLOOR))
    }

    @Test
    fun whiteFloor_nearBlackFelt35_is90AndCue245Passes() {
        // t13 near-black felt: the 90 absolute floor keeps the rule
        // above the felt while the cue still passes by a wide margin.
        val felt = doubleArrayOf(60.0, 60.0, 35.0)
        assertEquals(90.0, CvConfig.feltRelativeWhiteVFloor(felt, CvConfig.WHITE_V_FLOOR), 0.0)
        assertTrue(245.0 > CvConfig.feltRelativeWhiteVFloor(felt, CvConfig.WHITE_V_FLOOR))
    }

    @Test
    fun whiteFloor_brownFelt110_is140() {
        // t12 brown felt: feltV + 30 lands exactly on the fixed
        // white floor, so nothing changes there.
        val felt = doubleArrayOf(15.0, 139.0, 110.0)
        assertEquals(140.0, CvConfig.feltRelativeWhiteVFloor(felt, CvConfig.WHITE_V_FLOOR), 0.0)
    }

    @Test
    fun whiteFloor_dim03_felt41_is90AndCue73FailsByDesign() {
        // Harness table a08: at 0.3x light the cue is V~73 against
        // the 90 absolute floor, so it stays below the floor and the
        // table stays unsolved. This pins the fix's lower bound:
        // the rule generalises down to ~0.37x light, not below it.
        val felt = doubleArrayOf(74.0, 174.0, 41.0)
        assertEquals(90.0, CvConfig.feltRelativeWhiteVFloor(felt, CvConfig.WHITE_V_FLOOR), 0.0)
        assertFalse(73.0 > CvConfig.feltRelativeWhiteVFloor(felt, CvConfig.WHITE_V_FLOOR))
    }

    @Test
    fun guidelineCadence_isThreeFrames() {
        // v1.0.38: guideline Hough runs every 3rd frame (was every
        // 2nd). The tracker fades a lost segment over 5 frames, so
        // the 3-frame gap is covered; the harness is identical on
        // every accuracy field at cadence 3 (15 baseline + 8
        // adversarial tables, results-v1.0.38-before/after.json).
        assertEquals(3, CvConfig.GUIDELINE_EVERY_N_FRAMES)
    }

    @Test
    fun guidelineCadence_runsOnFramesZeroThreeSix() {
        // Mirrors the CvPipeline gate exactly: frameIndex %
        // GUIDELINE_EVERY_N_FRAMES == 0. Detection frames are 0, 3,
        // 6; the in-between frames reuse the last tracked segments.
        val runFrames = (0L..8L).filter { it % CvConfig.GUIDELINE_EVERY_N_FRAMES == 0L }
        assertEquals(listOf(0L, 3L, 6L), runFrames)
        val skipFrames = (0L..8L).filter { it % CvConfig.GUIDELINE_EVERY_N_FRAMES != 0L }
        assertEquals(listOf(1L, 2L, 4L, 5L, 7L, 8L), skipFrames)
    }
}
