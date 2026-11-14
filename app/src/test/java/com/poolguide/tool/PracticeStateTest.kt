package com.poolguide.tool.ui

import com.poolguide.tool.cv.TableRect
import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline practice-table state (v1.0.4, Cheto lead). */
class PracticeStateTest {

    private fun state() = PracticeState(
        TableRect(
            Vec2(40.0, 20.0), Vec2(680.0, 20.0),
            Vec2(680.0, 340.0), Vec2(40.0, 340.0)
        ),
        9.0
    )

    @Test
    fun `default layout solves a shot`() {
        val s = state()
        val shot = s.solve()
        assertNotNull(shot)
        // Cue at 25% width, target at 60%: the top-right and
        // bottom-right pockets tie at about a 35 degree cut, and the
        // first-listed pocket wins the tie.
        assertEquals(200.0, s.cue.center.x, 0.0)
        assertEquals(424.0, s.target.center.x, 0.0)
        assertTrue(shot!!.cutAngleDegrees > 20.0)
        assertTrue(shot.cutAngleDegrees < 40.0)
        assertEquals(680.0, shot.targetLine.end.x, 0.0)
        assertEquals(20.0, shot.targetLine.end.y, 0.0)
    }

    @Test
    fun `nudge moves the selected ball by exactly 1 px`() {
        val s = state()
        val before = s.cue.center
        s.nudge(1.0, 0.0)
        assertEquals(before.x + 1.0, s.cue.center.x, 0.0)
        assertEquals(before.y, s.cue.center.y, 0.0)
        s.nudge(0.0, -1.0)
        assertEquals(before.y - 1.0, s.cue.center.y, 0.0)
    }

    @Test
    fun `nudge moves the target when it is selected`() {
        val s = state()
        s.select(1)
        val before = s.target.center
        s.nudge(1.0, 1.0)
        assertEquals(before.x + 1.0, s.target.center.x, 0.0)
        assertEquals(before.y + 1.0, s.target.center.y, 0.0)
        // The cue did not move.
        assertEquals(200.0, s.cue.center.x, 0.0)
    }

    @Test
    fun `balls clamp inside the table`() {
        val s = state()
        s.moveBall(0, Vec2(-100.0, -100.0))
        assertEquals(49.0, s.cue.center.x, 0.0)
        assertEquals(29.0, s.cue.center.y, 0.0)
        s.moveBall(1, Vec2(9999.0, 9999.0))
        assertEquals(671.0, s.target.center.x, 0.0)
        assertEquals(331.0, s.target.center.y, 0.0)
    }

    @Test
    fun `hit test finds the nearer ball and misses empty felt`() {
        val s = state()
        assertEquals(0, s.ballAt(Vec2(200.0, 180.0))?.id)
        assertEquals(1, s.ballAt(Vec2(424.0, 180.0))?.id)
        assertNull(s.ballAt(Vec2(360.0, 100.0)))
    }

    @Test
    fun `reset restores the default layout and selection`() {
        val s = state()
        s.moveBall(0, Vec2(500.0, 300.0))
        s.select(1)
        s.reset()
        assertEquals(200.0, s.cue.center.x, 0.0)
        assertEquals(424.0, s.target.center.x, 0.0)
        assertEquals(0, s.selectedId)
    }

    @Test
    fun `overlapping balls have no shot`() {
        val s = state()
        s.moveBall(1, s.cue.center)
        assertNull(s.solve())
    }
}
