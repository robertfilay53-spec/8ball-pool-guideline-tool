package com.poolguide.tool.overlay

import com.poolguide.tool.geometry.Segment
import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Line smoother tests (v1.0.3, Bloro lead): small cue moves
 * interpolate, big jumps and status changes snap.
 */
class LineSmootherTest {

    private fun seg(x0: Double, y0: Double, x1: Double, y1: Double) =
        Segment(Vec2(x0, y0), Vec2(x1, y1))

    private val aim = seg(100.0, 200.0, 300.0, 200.0)
    private val target = seg(300.0, 200.0, 600.0, 50.0)
    private val deflection = seg(280.0, 200.0, 280.0, 230.0)
    private val status = "Shot to pocket"

    @Test
    fun `first frame snaps to the detected lines`() {
        val smoother = LineSmoother()
        val (a, t, d) = smoother.smooth(aim, target, deflection, status)
        assertEquals(aim, a)
        assertEquals(target, t)
        assertEquals(deflection, d)
    }

    @Test
    fun `small cue move interpolates halfway`() {
        val smoother = LineSmoother()
        smoother.smooth(aim, target, deflection, status)
        // Cue moves 10 px right (inside the 40 px gate): the drawn aim
        // start moves half that far (lerp 0.5).
        val moved = seg(110.0, 200.0, 310.0, 200.0)
        val (a, _, _) = smoother.smooth(moved, target, deflection, status)
        assertEquals(105.0, a.start.x, 1e-9)
        assertEquals(305.0, a.end.x, 1e-9)
    }

    @Test
    fun `repeated small moves converge on the detection`() {
        val smoother = LineSmoother()
        smoother.smooth(aim, target, deflection, status)
        val moved = seg(110.0, 200.0, 310.0, 200.0)
        var x = 100.0
        repeat(10) {
            val (a, _, _) = smoother.smooth(moved, target, deflection, status)
            x = a.start.x
        }
        // After 10 lerps at 0.5 the drawn cue is within 0.1 px of 110.
        assertEquals(110.0, x, 0.1)
    }

    @Test
    fun `cue jump over the gate snaps immediately`() {
        val smoother = LineSmoother()
        smoother.smooth(aim, target, deflection, status)
        // 50 px jump (over the 40 px gate): snap, no interpolation.
        val jumped = seg(150.0, 200.0, 350.0, 200.0)
        val (a, _, _) = smoother.smooth(jumped, target, deflection, status)
        assertEquals(150.0, a.start.x, 1e-9)
        assertEquals(350.0, a.end.x, 1e-9)
    }

    @Test
    fun `status change snaps even for a small move`() {
        val smoother = LineSmoother()
        smoother.smooth(aim, target, deflection, status)
        val moved = seg(110.0, 200.0, 310.0, 200.0)
        val (a, _, _) = smoother.smooth(moved, target, deflection, "Bank shot (one rail) to pocket")
        assertEquals(110.0, a.start.x, 1e-9)
    }

    @Test
    fun `move exactly at the gate interpolates, just over it snaps`() {
        val smoother = LineSmoother()
        smoother.smooth(aim, target, deflection, status)
        val atGate = seg(140.0, 200.0, 340.0, 200.0)
        val (a, _, _) = smoother.smooth(atGate, target, deflection, status)
        assertEquals(120.0, a.start.x, 1e-9) // lerped halfway to 140
        val overGate = seg(181.0, 200.0, 381.0, 200.0)
        val (b, _, _) = smoother.smooth(overGate, target, deflection, status)
        assertEquals(181.0, b.start.x, 1e-9) // snapped (61 px from 120)
    }

    @Test
    fun `reset makes the next frame snap`() {
        val smoother = LineSmoother()
        smoother.smooth(aim, target, deflection, status)
        smoother.reset()
        val moved = seg(110.0, 200.0, 310.0, 200.0)
        val (a, _, _) = smoother.smooth(moved, target, deflection, status)
        assertEquals(110.0, a.start.x, 1e-9)
    }

    // ---- v1.0.9: smoothList (two-rail banks have four segments) ----

    private val bankStatus = "Bank shot (two rails) to pocket"
    private fun bank2Segments() = listOf(
        seg(100.0, 200.0, 300.0, 200.0), // aim
        seg(300.0, 200.0, 400.0, 300.0), // target -> rail 1
        seg(400.0, 300.0, 500.0, 200.0), // rail 1 -> rail 2
        seg(500.0, 200.0, 600.0, 50.0) // rail 2 -> pocket
    )

    @Test
    fun `smoothList first frame snaps all four segments`() {
        val smoother = LineSmoother()
        val out = smoother.smoothList(bank2Segments(), bankStatus)
        assertEquals(4, out.size)
        assertEquals(bank2Segments(), out)
    }

    @Test
    fun `smoothList small cue move interpolates all four segments halfway`() {
        val smoother = LineSmoother()
        smoother.smoothList(bank2Segments(), bankStatus)
        // Cue moves 10 px right (inside the 40 px gate): every
        // segment's endpoints move half that far (lerp 0.5).
        val moved = bank2Segments().map {
            seg(it.start.x + 10.0, it.start.y, it.end.x + 10.0, it.end.y)
        }
        val out = smoother.smoothList(moved, bankStatus)
        assertEquals(4, out.size)
        assertEquals(105.0, out[0].start.x, 1e-9)
        assertEquals(305.0, out[1].start.x, 1e-9)
        assertEquals(405.0, out[1].end.x, 1e-9)
        assertEquals(405.0, out[2].start.x, 1e-9)
        assertEquals(505.0, out[2].end.x, 1e-9)
        assertEquals(605.0, out[3].end.x, 1e-9)
    }

    @Test
    fun `smoothList snaps when the segment count changes`() {
        // A three-segment direct shot followed by a four-segment
        // two-rail bank, same status string: the line counts differ,
        // so the bank must snap in, never lerp from the direct
        // shot's lines.
        val smoother = LineSmoother()
        smoother.smooth(aim, target, deflection, bankStatus)
        val out = smoother.smoothList(bank2Segments(), bankStatus)
        assertEquals(4, out.size)
        assertEquals(bank2Segments(), out)
    }

    @Test
    fun `smooth delegates to smoothList and keeps the old behaviour`() {
        val smoother = LineSmoother()
        val (a, _, _) = smoother.smooth(aim, target, deflection, status)
        assertEquals(aim, a)
        val moved = seg(110.0, 200.0, 310.0, 200.0)
        val (b, _, _) = smoother.smooth(moved, target, deflection, status)
        assertEquals(105.0, b.start.x, 1e-9)
    }
}
