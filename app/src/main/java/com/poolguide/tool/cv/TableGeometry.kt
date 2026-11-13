package com.poolguide.tool.cv

import com.poolguide.tool.geometry.Segment
import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2

/** Colour class of a detected ball (HSV classification, v1 approximation). */
enum class BallColor {
    WHITE, BLACK, RED, YELLOW, BLUE, ORANGE, GREEN, PURPLE, UNKNOWN
}

/** One detected ball, in working-resolution coordinates. */
data class Ball(
    val id: Int,
    val center: Vec2,
    val radiusPx: Double,
    val color: BallColor,
    /** True when the ball's centre pixel is white (stripe ball band). */
    val isStripe: Boolean
) {
    val isCue: Boolean get() = color == BallColor.WHITE
    val isEight: Boolean get() = color == BallColor.BLACK
}

/** A tracked white guideline segment (kaigiii LineTracker). */
data class GuidelineSegment(
    val segment: Segment,
    /** Fade alpha: 40 when freshly seen, -8 per lost frame, dropped at 0. */
    val alpha: Int
)

/** The table: four corners in working-resolution coordinates. */
data class TableRect(
    val topLeft: Vec2,
    val topRight: Vec2,
    val bottomRight: Vec2,
    val bottomLeft: Vec2
) {
    val corners: List<Vec2> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)
    val width: Double get() = topLeft.distanceTo(topRight)
    val height: Double get() = topLeft.distanceTo(bottomLeft)
    val center: Vec2 get() = Vec2(
        (topLeft.x + topRight.x + bottomLeft.x + bottomRight.x) / 4.0,
        (topLeft.y + topRight.y + bottomLeft.y + bottomRight.y) / 4.0
    )

    /** Six pockets at fixed fractions of the table rect (corners + side mids). */
    fun pockets(): List<Vec2> {
        val inset = width * CvConfig.POCKET_INSET_FRACTION
        val midX = (topLeft.x + topRight.x) / 2.0
        return listOf(
            topLeft + Vec2(inset, inset),
            topRight + Vec2(-inset, inset),
            bottomRight + Vec2(-inset, -inset),
            bottomLeft + Vec2(inset, -inset),
            Vec2(midX, topLeft.y + inset),
            Vec2(midX, bottomLeft.y - inset)
        )
    }
}

/** Everything one processed frame produces. All coordinates are in the
 * working resolution ([workingWidth] x [workingHeight]); the overlay maps
 * them to screen coordinates with [scaleToScreenX] / [scaleToScreenY]. */
data class DetectionResult(
    val frameIndex: Long,
    val workingWidth: Int,
    val workingHeight: Int,
    val scaleToScreenX: Double,
    val scaleToScreenY: Double,
    /** True when the captured frame is (near-)solid black. When true, the
     * game is probably using FLAG_SECURE — see README. No other fields are
     * meaningful in that case. */
    val isCaptureBlack: Boolean,
    val table: TableRect?,
    val pockets: List<Vec2>,
    val balls: List<Ball>,
    val cueBall: Ball?,
    val guideline: List<GuidelineSegment>,
    val shot: ShotSolver.ShotSolution?,
    val bank: ShotSolver.BankSolution?,
    /** Human-readable status for the diagnostic preview. */
    val status: String
) {
    companion object {
        fun blackFrame(
            frameIndex: Long, workingWidth: Int, workingHeight: Int,
            scaleX: Double, scaleY: Double
        ) = DetectionResult(
            frameIndex = frameIndex,
            workingWidth = workingWidth,
            workingHeight = workingHeight,
            scaleToScreenX = scaleX,
            scaleToScreenY = scaleY,
            isCaptureBlack = true,
            table = null,
            pockets = emptyList(),
            balls = emptyList(),
            cueBall = null,
            guideline = emptyList(),
            shot = null,
            bank = null,
            status = "Capture is black — the game may be using FLAG_SECURE"
        )
    }
}
