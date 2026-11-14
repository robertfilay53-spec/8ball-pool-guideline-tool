package com.poolguide.tool

import com.poolguide.tool.data.GameProfile
import com.poolguide.tool.data.TablePreset
import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table-size presets (v1.0.18). All numbers pinned against the real
 * code via kotlinc before the tests were written (preset18-probe):
 * built-ins scale the 9ft default by the table-length ratio (9/8 for
 * 8ft, 9/7 for 7ft); the brown window never scales; the corners are
 * the Calibrate screen's defaults.
 */
class TablePresetTest {

    private val version = "56.35.0"

    @Test
    fun builtin9ft_equalsDefaults() {
        val p = TablePreset.builtin("9ft", version)!!
        assertEquals("9ft", p.name)
        assertEquals(GameProfile.DEFAULT.pocketInsetFraction, p.profile.pocketInsetFraction, 0.0)
        assertEquals(GameProfile.DEFAULT.pocketRadiusFraction, p.profile.pocketRadiusFraction, 0.0)
        assertEquals(GameProfile.DEFAULT.ballRadiusPxAt720, p.profile.ballRadiusPxAt720, 0.0)
        assertEquals(GameProfile.DEFAULT.brownHLo, p.profile.brownHLo, 0.0)
        assertEquals(GameProfile.DEFAULT.brownHHi, p.profile.brownHHi, 0.0)
        assertEquals(4, p.corners.size)
        assertEquals(Vec2(0.08, 0.15), p.corners[0])
        assertEquals(Vec2(0.92, 0.85), p.corners[2])
    }

    @Test
    fun builtin8ft_scalesByNineEighths() {
        val p = TablePreset.builtin("8ft", version)!!
        assertEquals(0.0, p.profile.pocketInsetFraction, 0.0)
        assertEquals(0.0225, p.profile.pocketRadiusFraction, 0.0)
        assertEquals(10.125, p.profile.ballRadiusPxAt720, 0.0)
        // Brown window does not scale with table size.
        assertEquals(GameProfile.DEFAULT.brownHLo, p.profile.brownHLo, 0.0)
        assertEquals(GameProfile.DEFAULT.brownHHi, p.profile.brownHHi, 0.0)
    }

    @Test
    fun builtin7ft_scalesByNineSevenths() {
        val p = TablePreset.builtin("7ft", version)!!
        assertEquals(0.0, p.profile.pocketInsetFraction, 0.0)
        assertEquals(0.025714285714285717, p.profile.pocketRadiusFraction, 0.0)
        assertEquals(11.571428571428573, p.profile.ballRadiusPxAt720, 0.0)
    }

    @Test
    fun builtin_unknownNameIsNull() {
        assertNull(TablePreset.builtin("10ft", version))
        assertNull(TablePreset.builtin("", version))
        assertFalse(TablePreset.isBuiltinName("10ft"))
        assertTrue(TablePreset.isBuiltinName("8ft"))
        assertEquals(listOf("7ft", "8ft", "9ft"), TablePreset.BUILTIN_NAMES)
    }

    @Test
    fun serializeParse_roundTrips() {
        val custom = TablePreset(
            "8ft",
            GameProfile(
                version, 0.01, 0.03, 12.0,
                brownHLo = 10.0, brownHHi = 25.0, brownSLo = 120.0,
                brownSHi = 255.0, brownVLo = 90.0, brownVHi = 255.0
            ),
            listOf(
                Vec2(0.1, 0.2), Vec2(0.9, 0.2),
                Vec2(0.9, 0.8), Vec2(0.1, 0.8)
            )
        )
        assertEquals(custom, TablePreset.parse(custom.serialize(), "8ft", version))
    }

    @Test
    fun parse_rejectsMalformed() {
        assertNull(TablePreset.parse(null, "8ft", version))
        assertNull(TablePreset.parse("", "8ft", version))
        assertNull(TablePreset.parse("0.01,0.03", "8ft", version))
        assertNull(
            TablePreset.parse(
                "a,b,c,d,e,f,g,h,i,0.1,0.2,0.9,0.2,0.9,0.8,0.1,0.8",
                "8ft", version
            )
        )
        // Ball radius 99 is out of the GameProfile range.
        assertNull(
            TablePreset.parse(
                "0.0,0.02,99.0,10.0,120.0,90.0,25.0,255.0,255.0," +
                    "0.08,0.15,0.92,0.15,0.92,0.85,0.08,0.85",
                "8ft", version
            )
        )
        // A corner outside 0..1.
        assertNull(
            TablePreset.parse(
                "0.0,0.02,9.0,10.0,120.0,90.0,25.0,255.0,255.0," +
                    "0.08,0.15,0.92,0.15,0.92,0.85,0.08,1.5",
                "8ft", version
            )
        )
    }

    @Test
    fun preset_requiresFourCornersAndNonBlankName() {
        try {
            TablePreset("8ft", GameProfile.DEFAULT, listOf(Vec2(0.0, 0.0)))
            throw AssertionError("expected IllegalArgumentException for 1 corner")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
        try {
            TablePreset("  ", GameProfile.DEFAULT, TablePreset.DEFAULT_CORNERS)
            throw AssertionError("expected IllegalArgumentException for blank name")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
