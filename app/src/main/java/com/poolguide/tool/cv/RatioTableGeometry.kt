package com.poolguide.tool.cv

import kotlin.math.min

/**
 * Ratio-based table geometry fallback (Bloro lead: `I1Il1llI.IlllI1II`).
 *
 * When the felt detector finds no table at all, the pipeline guesses the
 * table from the frame size instead of reporting nothing: the playfield
 * band spans 0.237037 to 0.912963 of the frame height (Bloro's measured
 * fractions for the real game in landscape), and a standard pool table is
 * 2:1, so the band height sets the table length, centred horizontally.
 * The ball radius is 3% of the band height, also Bloro's fraction.
 *
 * This is a FALLBACK, never the primary: the same hardcoded-ratio
 * fragility Bloro carries applies here (a UI redesign moving the table
 * breaks it silently), so it is used only when detection fails, the
 * result is labelled ([TableGeometry.DetectionResult.tableFromFallback]),
 * and the detector retries every 30 frames so a real detection replaces
 * the guess as soon as one succeeds. The pockets come from the standard
 * [TableGeometry.TableRect.pockets] model, not Bloro's pocket offsets
 * (0.0739726 / 0.063 of band width) — our pocket model is fitted to
 * ground truth (inset 0), and mixing two pocket models would move the
 * pockets relative to the corners the fallback already placed.
 *
 * Pure Kotlin, no Android dependency.
 */
object RatioTableGeometry {

    /** Playfield band top, as a fraction of frame height (Bloro). */
    const val PLAYFIELD_TOP_FRACTION = 0.237037

    /** Playfield band bottom, as a fraction of frame height (Bloro). */
    const val PLAYFIELD_BOTTOM_FRACTION = 0.912963

    /** Ball radius as a fraction of the band height (Bloro). */
    const val BALL_RADIUS_FRACTION_OF_BAND_HEIGHT = 0.03

    /** A standard pool table is 2:1 (length : width). */
    const val TABLE_LENGTH_TO_WIDTH = 2.0

    /** The guessed table rect for a frame of [workingWidth] x
     * [workingHeight]. The length is capped at 96% of the frame width so
     * a portrait frame (where the band would imply a table wider than
     * the frame) still yields a rect inside the frame. */
    fun fallbackTable(workingWidth: Int, workingHeight: Int): TableRect {
        require(workingWidth > 0) { "workingWidth must be positive" }
        require(workingHeight > 0) { "workingHeight must be positive" }
        val top = PLAYFIELD_TOP_FRACTION * workingHeight
        val bottom = PLAYFIELD_BOTTOM_FRACTION * workingHeight
        val bandHeight = bottom - top
        val length = min(bandHeight * TABLE_LENGTH_TO_WIDTH, workingWidth * 0.96)
        val left = (workingWidth - length) / 2.0
        return TableRect(
            topLeft = com.poolguide.tool.geometry.Vec2(left, top),
            topRight = com.poolguide.tool.geometry.Vec2(left + length, top),
            bottomRight = com.poolguide.tool.geometry.Vec2(left + length, bottom),
            bottomLeft = com.poolguide.tool.geometry.Vec2(left, bottom)
        )
    }

    /** Ball radius in px for a frame [workingHeight] tall, from the band. */
    fun ballRadiusPx(workingHeight: Int): Double {
        require(workingHeight > 0) { "workingHeight must be positive" }
        val bandHeight = (PLAYFIELD_BOTTOM_FRACTION - PLAYFIELD_TOP_FRACTION) * workingHeight
        return BALL_RADIUS_FRACTION_OF_BAND_HEIGHT * bandHeight
    }
}
