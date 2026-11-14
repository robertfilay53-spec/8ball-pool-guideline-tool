package com.poolguide.tool.cv

import com.poolguide.tool.data.GameProfile
import com.poolguide.tool.geometry.BallGroup
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
    val isStripe: Boolean,
    /** Group from the disc classifier (v1.0.14): cue / eight /
     * solid / stripe / unknown. UNKNOWN until the detector classifies
     * the ball — a tracked ball carried over a missed frame keeps the
     * group it was last classified with. */
    val group: BallGroup = BallGroup.UNKNOWN
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

    /** Six pockets at fixed fractions of the table rect (corners + side mids).
     * The fractions come from the per-version [GameProfile] (aimcall lead):
     * a game update can move the pockets, so the fractions are calibration,
     * not constants. The no-arg form uses the defaults. */
    fun pockets(profile: GameProfile = GameProfile.DEFAULT): List<Vec2> {
        val inset = width * profile.pocketInsetFraction
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
    /** Best two-rail bank (v1.0.9), set only when no direct shot and
     * no one-rail bank solves: the last-resort best shot. Ranked
     * after one-rail banks — a two-rail bank never displaces a
     * direct shot or a one-rail bank in the live overlay. */
    val bank2: ShotSolver.TwoRailBankSolution? = null,
    /** Best one-rail kick (v1.0.10), set only when no direct shot
     * solves and the kick's cut angle beats the best one-rail bank's:
     * a kick and a one-rail bank are both one-rail shots ranked by
     * the same number (the direct cut angle), so the smaller cut
     * wins and a tie goes to the bank, the established fallback. A
     * kick never displaces a direct shot. */
    val kick: ShotSolver.KickSolution? = null,
    /** Best two-rail kick (v1.0.11), set only when no direct shot,
     * no one-rail bank, no one-rail kick, and no two-rail bank
     * solves: the last-resort best shot. A two-rail kick compounds
     * real-table error twice (two cushion contacts, each fitted to
     * synthetic data), so it is only offered when it is the only
     * option — it never displaces any other shot in the live
     * overlay. */
    val kick2: ShotSolver.TwoRailKickSolution? = null,
    /** Best combo (v1.0.13), set only when no direct shot, no
     * one-rail bank, no one-rail kick, no two-rail bank, and no
     * two-rail kick solves: the last resort before a kiss. The
     * picked target is the first ball (the ball the cue strikes);
     * every other ball is a candidate second ball. A combo never
     * displaces any other shot in the live overlay. */
    val combo: ShotSolver.ComboSolution? = null,
    /** Best kiss (v1.0.13), set only when no combo solves either:
     * the last resort of all. A kiss never displaces a combo. */
    val kiss: ShotSolver.KissSolution? = null,
    /** Every reachable direct shot, shown shot first (v1.0.21):
     * the first CLEAR shot in cut order heads the list, then the
     * remaining shots in cut order. Empty when no direct shot
     * exists. The overlay draws the non-best entries faint when
     * "Show all shots" is on; [shot] is always
     * allShots.firstOrNull(). */
    val allShots: List<ShotSolver.ShotSolution> = emptyList(),
    /** Blocked-path flag (v1.0.19): the centre of the ball sitting
     * on the shown shot's path, or null when the path is clear. Set
     * by the pipeline from the other detected balls; the overlay
     * draws a "Path blocked" label for it and the status line says
     * so. Since v1.0.24 combo and kiss shots are flagged too, on
     * their own potting chain (see PathBlock). */
    val blockedBy: Vec2? = null,
    /** Table-lock gate (v1.0.3, Bloro lead): true once the detected
     * table rect has stayed within 2 px for 75 consecutive frames
     * (5 s at the 15 FPS cap). The overlay draws nothing until this is
     * set. A calibration override counts as locked immediately. */
    val tableLocked: Boolean = false,
    /** True when [table] is the ratio fallback (v1.0.4, Bloro lead),
     * not a detection: the rect is a guess from the frame size. Since
     * v1.0.5 the fallback only CONTINUES a lock a real detection
     * already earned, for at most 150 frames — it can never be the
     * first table on a fresh screen, and it can never lock the table.
     * The status line says so, and the detector retries so a real
     * detection replaces the guess as soon as one succeeds. */
    val tableFromFallback: Boolean = false,
    /** Table-lock progress (0.0-1.0): the fraction of the 75 stable
     * frames banked so far, 1.0 once locked. The control bubble's ring
     * shows this. Fallback frames never advance it. */
    val tableLockProgress: Float = 0f,
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
