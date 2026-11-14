package com.poolguide.tool

import com.poolguide.tool.geometry.Rail
import com.poolguide.tool.geometry.RailContactMarker
import com.poolguide.tool.geometry.Segment
import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rail-contact markers (v1.0.16). All solver numbers pinned against
 * the solver with kotlinc before the tests were written
 * (contact16-probe/Probe.kt).
 */
class RailContactMarkerTest {

    private val r = 9.0
    // Practice-table rails (normals point into the table).
    private val bottom = Rail(Vec2(40.0, 340.0), Vec2(0.0, -1.0))
    private val right = Rail(Vec2(680.0, 20.0), Vec2(-1.0, 0.0))
    private val top = Rail(Vec2(40.0, 20.0), Vec2(0.0, 1.0))
    private val left = Rail(Vec2(40.0, 20.0), Vec2(1.0, 0.0))

    @Test
    fun `empty path has no contacts`() {
        assertTrue(RailContactMarker.contacts(emptyList()).isEmpty())
    }

    @Test
    fun `single segment has no contacts`() {
        val path = listOf(Segment(Vec2(0.0, 0.0), Vec2(100.0, 0.0)))
        assertTrue(RailContactMarker.contacts(path).isEmpty())
    }

    @Test
    fun `two segments give one contact at the shared joint`() {
        val joint = Vec2(50.0, 30.0)
        val path = listOf(
            Segment(Vec2(0.0, 0.0), joint),
            Segment(joint, Vec2(100.0, 60.0))
        )
        val contacts = RailContactMarker.contacts(path)
        assertEquals(1, contacts.size)
        assertEquals(joint, contacts[0])
    }

    @Test
    fun `three segments give two contacts in path order`() {
        val first = Vec2(50.0, 30.0)
        val second = Vec2(90.0, 10.0)
        val path = listOf(
            Segment(Vec2(0.0, 0.0), first),
            Segment(first, second),
            Segment(second, Vec2(120.0, 80.0))
        )
        assertEquals(listOf(first, second), RailContactMarker.contacts(path))
    }

    @Test
    fun `contacts are the joints, not the path endpoints`() {
        val joint = Vec2(50.0, 30.0)
        val path = listOf(
            Segment(Vec2(0.0, 0.0), joint),
            Segment(joint, Vec2(100.0, 60.0))
        )
        val contacts = RailContactMarker.contacts(path)
        assertTrue(!contacts.contains(Vec2(0.0, 0.0)))
        assertTrue(!contacts.contains(Vec2(100.0, 60.0)))
    }

    @Test
    fun `one-rail bank contacts equal the solver rail contact`() {
        // Bank-default layout, bottom rail to the top-right pocket:
        // cut 30.77 deg, contact (502.2, 340.0).
        val bank = ShotSolver.solveBank(
            Vec2(120.0, 80.0), Vec2(480.0, 300.0),
            Vec2(680.0, 20.0), bottom, r
        )
        assertNotNull(bank)
        bank!!
        assertEquals(30.77, bank.direct.cutAngleDegrees, 0.1)
        assertEquals(listOf(bank.railContact), RailContactMarker.contacts(bank.bankPath))
        assertEquals(502.2, bank.railContact.x, 0.5)
        assertEquals(340.0, bank.railContact.y, 0.0)
    }

    @Test
    fun `one-rail kick contacts equal the solver rail contact`() {
        // KickTest layout: cue (120, 80), target (480, 300), right
        // rail to the bottom-middle pocket: cut 2.34 deg, contact
        // (680, 241.5).
        val kick = ShotSolver.solveKick(
            Vec2(120.0, 80.0), Vec2(480.0, 300.0),
            Vec2(360.0, 340.0), right, r
        )
        assertNotNull(kick)
        kick!!
        assertEquals(2.34, kick.direct.cutAngleDegrees, 0.1)
        assertEquals(listOf(kick.railContact), RailContactMarker.contacts(kick.kickPath))
        assertEquals(680.0, kick.railContact.x, 0.0)
        assertEquals(241.5, kick.railContact.y, 0.5)
    }

    @Test
    fun `two-rail bank contacts equal the solver rail contacts`() {
        // TwoRailBankTest layout: cue (580, 300), target (600, 320),
        // bottom then right rails to the top-middle pocket: cut 12.64
        // deg, contacts (623.5, 340) and (680, 292).
        val bank = ShotSolver.solveTwoRailBank(
            Vec2(580.0, 300.0), Vec2(600.0, 320.0),
            Vec2(360.0, 20.0), bottom, right, r
        )
        assertNotNull(bank)
        bank!!
        assertEquals(12.64, bank.direct.cutAngleDegrees, 0.1)
        assertEquals(bank.railContacts, RailContactMarker.contacts(bank.bankPath))
        assertEquals(623.5, bank.railContacts[0].x, 0.5)
        assertEquals(340.0, bank.railContacts[0].y, 0.0)
        assertEquals(680.0, bank.railContacts[1].x, 0.0)
        assertEquals(292.0, bank.railContacts[1].y, 0.5)
    }

    @Test
    fun `two-rail kick contacts equal the solver rail contacts`() {
        // TwoRailKickTest layout: cue (140, 120), target (60, 120),
        // top then left rails to the bottom-middle pocket: cut 24.63
        // deg, contacts (84.3, 20) and (40, 99.5).
        val kick = ShotSolver.solveTwoRailKick(
            Vec2(140.0, 120.0), Vec2(60.0, 120.0),
            Vec2(360.0, 340.0), top, left, r
        )
        assertNotNull(kick)
        kick!!
        assertEquals(24.63, kick.direct.cutAngleDegrees, 0.1)
        assertEquals(kick.railContacts, RailContactMarker.contacts(kick.kickPath))
        assertEquals(84.3, kick.railContacts[0].x, 0.5)
        assertEquals(20.0, kick.railContacts[0].y, 0.0)
        assertEquals(40.0, kick.railContacts[1].x, 0.0)
        assertEquals(99.5, kick.railContacts[1].y, 0.5)
    }
}
