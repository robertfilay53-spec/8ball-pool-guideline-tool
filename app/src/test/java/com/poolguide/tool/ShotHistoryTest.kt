package com.poolguide.tool

import com.poolguide.tool.cv.DetectionResult
import com.poolguide.tool.cv.TableRect
import com.poolguide.tool.data.DiagnosticsLog
import com.poolguide.tool.data.ShotHistory
import com.poolguide.tool.geometry.Rail
import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shot-history strip (v1.0.17). All solver numbers pinned against
 * the real solver first (kotlinc probe, hist17-probe/Probe17.kt):
 * direct best 22.2 deg / 32.7 bd to BR, bank 81.6 / 45.4 to TR,
 * kick 17.8 / 37.4 to TR, bank2 12.6 / 29.7 to TM, kick2 16.0 /
 * 51.7 to BL, combo 60.3 / 16.0 to TM, kiss 6.4 / 33.8 to TR.
 */
class ShotHistoryTest {

    private val pockets = listOf(
        Vec2(40.0, 20.0), Vec2(680.0, 20.0), Vec2(680.0, 340.0),
        Vec2(40.0, 340.0), Vec2(360.0, 20.0), Vec2(360.0, 340.0)
    )
    private val rails = listOf(
        Rail(Vec2(0.0, 20.0), Vec2(0.0, 1.0)),
        Rail(Vec2(0.0, 340.0), Vec2(0.0, -1.0)),
        Rail(Vec2(40.0, 0.0), Vec2(1.0, 0.0)),
        Rail(Vec2(680.0, 0.0), Vec2(-1.0, 0.0))
    )
    private val table = TableRect(
        Vec2(40.0, 20.0), Vec2(680.0, 20.0),
        Vec2(680.0, 340.0), Vec2(40.0, 340.0)
    )
    private val r = 9.0

    @After
    fun tearDown() {
        ShotHistory.clear()
        ShotHistory.listener = null
        DiagnosticsLog.end()
    }

    private fun result(
        shot: ShotSolver.ShotSolution? = null,
        bank: ShotSolver.BankSolution? = null,
        kick: ShotSolver.KickSolution? = null,
        bank2: ShotSolver.TwoRailBankSolution? = null,
        kick2: ShotSolver.TwoRailKickSolution? = null,
        combo: ShotSolver.ComboSolution? = null,
        kiss: ShotSolver.KissSolution? = null,
        tableRect: TableRect? = table,
        pocketList: List<Vec2> = pockets
    ) = DetectionResult(
        frameIndex = 1, workingWidth = 720, workingHeight = 405,
        scaleToScreenX = 1.0, scaleToScreenY = 1.0,
        isCaptureBlack = false, table = tableRect, pockets = pocketList,
        balls = emptyList(), cueBall = null, guideline = emptyList(),
        shot = shot, bank = bank, kick = kick, bank2 = bank2,
        kick2 = kick2, combo = combo, kiss = kiss,
        tableLocked = true, status = "test"
    )

    private fun entry(
        family: String, pocket: String, cut: Double, dist: Double, timeMs: Long = 1_000L
    ) = ShotHistory.ShotEntry(family, pocket, cut, dist, timeMs)

    // ---- entryFor: one family at a time ----

    @Test
    fun entryFor_directUsesBestShotAndNamesPocket() {
        val direct = ShotSolver.allShots(
            Vec2(100.0, 200.0), Vec2(300.0, 200.0), pockets, r
        ).first()
        val e = ShotHistory.entryFor(result(shot = direct), 0L)
        assertNotNull(e)
        e!!
        assertEquals("DIRECT", e.family)
        assertEquals("BR", e.pocketName)
        assertEquals(22.17, e.cutAngleDegrees, 0.05)
        assertEquals(32.68, e.distanceBd, 0.05)
    }

    @Test
    fun entryFor_bankNamesPocketAndUsesBankPath() {
        val bank = ShotSolver.solveBank(
            Vec2(100.0, 300.0), Vec2(300.0, 200.0), Vec2(680.0, 20.0), rails[1], r
        )!!
        val e = ShotHistory.entryFor(result(bank = bank), 0L)
        assertNotNull(e)
        e!!
        assertEquals("BANK", e.family)
        assertEquals("TR", e.pocketName)
        assertEquals(81.57, e.cutAngleDegrees, 0.05)
        assertEquals(45.38, e.distanceBd, 0.05)
    }

    @Test
    fun entryFor_kickUsesKickPathNotAimChord() {
        val kick = ShotSolver.solveKick(
            Vec2(100.0, 300.0), Vec2(300.0, 200.0), Vec2(680.0, 20.0), rails[1], r
        )!!
        val e = ShotHistory.entryFor(result(kick = kick), 0L)
        assertNotNull(e)
        e!!
        assertEquals("KICK", e.family)
        assertEquals("TR", e.pocketName)
        assertEquals(17.81, e.cutAngleDegrees, 0.05)
        assertEquals(37.35, e.distanceBd, 0.05)
    }

    @Test
    fun entryFor_twoRailBankAndKick() {
        val bank2 = ShotSolver.solveTwoRailBank(
            Vec2(580.0, 300.0), Vec2(600.0, 320.0), Vec2(360.0, 20.0),
            rails[1], rails[3], r
        )!!
        val e2 = ShotHistory.entryFor(result(bank2 = bank2), 0L)
        assertNotNull(e2)
        e2!!
        assertEquals("BANK2", e2.family)
        assertEquals("TM", e2.pocketName)
        assertEquals(12.64, e2.cutAngleDegrees, 0.05)
        assertEquals(29.75, e2.distanceBd, 0.05)

        val kick2 = ShotSolver.solveTwoRailKick(
            Vec2(60.0, 40.0), Vec2(100.0, 60.0), Vec2(40.0, 340.0),
            rails[1], rails[0], r
        )!!
        val e3 = ShotHistory.entryFor(result(kick2 = kick2), 0L)
        assertNotNull(e3)
        e3!!
        assertEquals("KICK2", e3.family)
        assertEquals("BL", e3.pocketName)
        assertEquals(15.99, e3.cutAngleDegrees, 0.05)
        assertEquals(51.68, e3.distanceBd, 0.05)
    }

    @Test
    fun entryFor_comboAndKissUseSecondShotCut() {
        val combo = ShotSolver.bestCombo(
            Vec2(55.0, 58.0), Vec2(60.0, 40.0),
            listOf(Vec2(80.0, 35.0)), pockets, r
        )!!
        val e = ShotHistory.entryFor(result(combo = combo), 0L)
        assertNotNull(e)
        e!!
        assertEquals("COMBO", e.family)
        assertEquals("TM", e.pocketName)
        assertEquals(60.29, e.cutAngleDegrees, 0.05)
        assertEquals(16.03, e.distanceBd, 0.05)

        val kiss = ShotSolver.bestKiss(
            Vec2(55.0, 58.0), Vec2(60.0, 40.0),
            listOf(Vec2(210.0, 60.0)), pockets, r
        )!!
        val e2 = ShotHistory.entryFor(result(kiss = kiss), 0L)
        assertNotNull(e2)
        e2!!
        assertEquals("KISS", e2.family)
        assertEquals("TR", e2.pocketName)
        assertEquals(6.39, e2.cutAngleDegrees, 0.05)
        assertEquals(33.82, e2.distanceBd, 0.05)
    }

    @Test
    fun entryFor_nullWhenNoShotSolved() {
        assertNull(ShotHistory.entryFor(result(), 0L))
    }

    @Test
    fun entryFor_directWinsOverBank() {
        val direct = ShotSolver.allShots(
            Vec2(100.0, 200.0), Vec2(300.0, 200.0), pockets, r
        ).first()
        val bank = ShotSolver.solveBank(
            Vec2(100.0, 300.0), Vec2(300.0, 200.0), Vec2(680.0, 20.0), rails[1], r
        )!!
        val e = ShotHistory.entryFor(result(shot = direct, bank = bank), 0L)
        assertNotNull(e)
        assertEquals("DIRECT", e!!.family)
    }

    // ---- pocketName ----

    @Test
    fun pocketName_allSixPositionsFromTableAndList() {
        val named = listOf(
            "TL" to Vec2(40.0, 20.0), "TR" to Vec2(680.0, 20.0),
            "BR" to Vec2(680.0, 340.0), "BL" to Vec2(40.0, 340.0),
            "TM" to Vec2(360.0, 20.0), "BM" to Vec2(360.0, 340.0)
        )
        for ((name, p) in named) {
            assertEquals(name, ShotHistory.pocketName(p, table, emptyList()))
            assertEquals(name, ShotHistory.pocketName(p, null, pockets))
        }
    }

    @Test
    fun pocketName_unknownWithoutTableOrPockets() {
        assertEquals("?", ShotHistory.pocketName(Vec2(40.0, 20.0), null, emptyList()))
        assertEquals("?", ShotHistory.pocketName(Vec2(40.0, 20.0), null, pockets.take(3)))
    }

    // ---- recordIfChanged / cap / clear / listener ----

    @Test
    fun recordIfChanged_dedupesUnchangedShot() {
        assertTrue(ShotHistory.recordIfChanged(entry("DIRECT", "BR", 22.17, 32.68)))
        assertEquals(1, ShotHistory.snapshot().size)
        // Same shot again: no new entry.
        assertFalse(ShotHistory.recordIfChanged(entry("DIRECT", "BR", 22.17, 32.68, 2_000L)))
        assertEquals(1, ShotHistory.snapshot().size)
        // Sub-degree noise (0.04 deg) is the same shot.
        assertFalse(ShotHistory.recordIfChanged(entry("DIRECT", "BR", 22.21, 32.68, 3_000L)))
        assertEquals(1, ShotHistory.snapshot().size)
        // A real change (0.2 deg) records.
        assertTrue(ShotHistory.recordIfChanged(entry("DIRECT", "BR", 22.4, 32.68, 4_000L)))
        assertEquals(2, ShotHistory.snapshot().size)
        // A different family or pocket records even at the same angle.
        assertTrue(ShotHistory.recordIfChanged(entry("BANK", "BR", 22.4, 32.68, 5_000L)))
        assertTrue(ShotHistory.recordIfChanged(entry("BANK", "TR", 22.4, 32.68, 6_000L)))
        assertEquals(4, ShotHistory.snapshot().size)
    }

    @Test
    fun recordIfChanged_capsAtEightAndDropsOldest() {
        for (i in 0 until 9) {
            ShotHistory.recordIfChanged(entry("DIRECT", "BR", 10.0 + i, 20.0 + i, i.toLong()))
        }
        val snap = ShotHistory.snapshot()
        assertEquals(ShotHistory.MAX_SHOTS, snap.size)
        assertEquals(8, snap.size)
        // Oldest (cut 10.0) dropped; newest kept, in order.
        assertEquals(11.0, snap.first().cutAngleDegrees, 0.0)
        assertEquals(18.0, snap.last().cutAngleDegrees, 0.0)
    }

    @Test
    fun clear_emptiesStrip() {
        ShotHistory.recordIfChanged(entry("DIRECT", "BR", 22.17, 32.68))
        assertEquals(1, ShotHistory.snapshot().size)
        ShotHistory.clear()
        assertEquals(0, ShotHistory.snapshot().size)
    }

    @Test
    fun listener_firesOnRecordNotOnDedupe() {
        var calls = 0
        ShotHistory.listener = { calls++ }
        ShotHistory.recordIfChanged(entry("DIRECT", "BR", 22.17, 32.68))
        assertEquals(1, calls)
        ShotHistory.recordIfChanged(entry("DIRECT", "BR", 22.17, 32.68, 2_000L))
        assertEquals(1, calls)
        ShotHistory.recordIfChanged(entry("DIRECT", "TR", 22.17, 32.68, 3_000L))
        assertEquals(2, calls)
    }

    // ---- format ----

    @Test
    fun format_oneLineWithTimeFamilyPocketCutAndDistance() {
        val line = ShotHistory.format(entry("DIRECT", "TL", 24.6, 29.555555555555557, 1_794_650_400_000L))
        // Wall-clock hour depends on the device timezone, so pin the
        // tail and only the shape of the time prefix.
        assertTrue(
            line.matches(Regex("^\\d{2}:\\d{2}:\\d{2} DIRECT → TL, cut 24.6°, 29.6 bd$"))
        )
        val thin = ShotHistory.format(entry("BANK", "BR", 80.04, 42.1, 1_794_650_400_000L))
        assertTrue(
            thin.matches(Regex("^\\d{2}:\\d{2}:\\d{2} BANK → BR, cut 80.0°, 42.1 bd$"))
        )
    }

    // ---- diagnostics export ----

    @Test
    fun diagnosticsReport_appendsShotHistory() {
        DiagnosticsLog.start()
        val header = DiagnosticsLog.Header(
            appVersion = "1.0.17", appVersionCode = 18,
            gameVersion = "56.35.0", deviceModel = "Test",
            androidVersion = "API 34", sessionStartIso = "2026-11-14T00:00:00+0000"
        )
        val without = DiagnosticsLog.buildReport(header)
        assertFalse(without.contains("Shots:"))
        val shots = listOf(entry("DIRECT", "TL", 24.6, 29.6, 1_794_650_400_000L))
        val with = DiagnosticsLog.buildReport(header, shots)
        assertTrue(with.contains("Shots: 1"))
        assertTrue(with.contains("DIRECT → TL, cut 24.6°, 29.6 bd"))
    }
}
