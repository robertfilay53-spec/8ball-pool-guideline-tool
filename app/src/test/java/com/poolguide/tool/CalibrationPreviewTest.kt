package com.poolguide.tool

import com.poolguide.tool.cv.CalibrationPreview
import com.poolguide.tool.cv.CalibrationPreview.PreviewStatus
import com.poolguide.tool.data.GameProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Slider <-> value mappings and preview-status decision (v1.0.8). Pure Kotlin. */
class CalibrationPreviewTest {

    @Test
    fun `brown H slider round trips`() {
        for (v in listOf(0, 13, 22, 90, 179)) {
            val slider = CalibrationPreview.brownValueToSlider(v.toDouble(), 179)
            assertEquals(v, slider)
            assertEquals(v, CalibrationPreview.brownSliderToValue(slider, 179))
        }
    }

    @Test
    fun `brown S and V sliders round trip`() {
        for (v in listOf(0, 105, 135, 255)) {
            assertEquals(v, CalibrationPreview.brownValueToSlider(v.toDouble(), 255))
            assertEquals(
                v,
                CalibrationPreview.brownSliderToValue(
                    CalibrationPreview.brownValueToSlider(v.toDouble(), 255), 255
                )
            )
        }
    }

    @Test
    fun `brown slider values clamp to range`() {
        assertEquals(0, CalibrationPreview.brownValueToSlider(-5.0, 179))
        assertEquals(179, CalibrationPreview.brownValueToSlider(500.0, 179))
        assertEquals(0, CalibrationPreview.brownSliderToValue(-3, 255))
        assertEquals(255, CalibrationPreview.brownSliderToValue(999, 255))
    }

    @Test
    fun `pocket fraction sliders round trip`() {
        for (v in listOf(0.0, 0.02, 0.05, 0.1)) {
            val slider = CalibrationPreview.fractionValueToSlider(v)
            assertEquals(v, CalibrationPreview.fractionSliderToValue(slider), 0.00005)
        }
        // 0.02 -> slider 200, 0.1 -> slider 1000.
        assertEquals(200, CalibrationPreview.fractionValueToSlider(0.02))
        assertEquals(1000, CalibrationPreview.fractionValueToSlider(0.1))
    }

    @Test
    fun `pocket fraction sliders clamp`() {
        assertEquals(0, CalibrationPreview.fractionValueToSlider(-0.01))
        assertEquals(1000, CalibrationPreview.fractionValueToSlider(0.5))
        assertEquals(0.0, CalibrationPreview.fractionSliderToValue(-10), 0.0)
        assertEquals(0.1, CalibrationPreview.fractionSliderToValue(5000), 0.0)
    }

    @Test
    fun `ball radius slider round trips`() {
        for (v in listOf(1.0, 9.0, 20.0, 40.0)) {
            assertEquals(v, CalibrationPreview.ballRadiusSliderToValue(
                CalibrationPreview.ballRadiusValueToSlider(v)
            ), 0.0)
        }
        assertEquals(9, CalibrationPreview.ballRadiusValueToSlider(9.0))
    }

    @Test
    fun `ball radius slider clamps to 1 to 40`() {
        assertEquals(1, CalibrationPreview.ballRadiusValueToSlider(0.0))
        assertEquals(40, CalibrationPreview.ballRadiusValueToSlider(100.0))
        assertEquals(1.0, CalibrationPreview.ballRadiusSliderToValue(0), 0.0)
        assertEquals(40.0, CalibrationPreview.ballRadiusSliderToValue(99), 0.0)
    }

    @Test
    fun `all nine default sliders round trip through the profile`() {
        val defaults = CalibrationPreview.defaultSliders()
        val profile = CalibrationPreview.profileFromSliders(
            version = GameProfile.DEFAULT.version,
            brownHLo = defaults.getValue("brownHLo"),
            brownHHi = defaults.getValue("brownHHi"),
            brownSLo = defaults.getValue("brownSLo"),
            brownSHi = defaults.getValue("brownSHi"),
            brownVLo = defaults.getValue("brownVLo"),
            brownVHi = defaults.getValue("brownVHi"),
            pocketInsetSlider = defaults.getValue("pocketInset"),
            pocketRadiusSlider = defaults.getValue("pocketRadius"),
            ballRadiusSlider = defaults.getValue("ballRadius")
        )
        assertNotNull(profile)
        assertEquals(GameProfile.DEFAULT, profile)
    }

    @Test
    fun `profile from sliders rejects low above high`() {
        assertNull(
            CalibrationPreview.profileFromSliders(
                version = "56.35.0",
                brownHLo = 30, brownHHi = 20,
                brownSLo = 0, brownSHi = 255,
                brownVLo = 0, brownVHi = 255,
                pocketInsetSlider = 0, pocketRadiusSlider = 200,
                ballRadiusSlider = 9
            )
        )
    }

    @Test
    fun `mask coverage is the non-zero fraction`() {
        assertEquals(0.25, CalibrationPreview.maskCoverage(25, 100), 0.0)
        assertEquals(0.0, CalibrationPreview.maskCoverage(0, 100), 0.0)
        assertEquals(0.0, CalibrationPreview.maskCoverage(10, 0), 0.0)
        assertEquals(1.0, CalibrationPreview.maskCoverage(200, 100), 0.0)
    }

    @Test
    fun `coverage formats to one decimal percent`() {
        assertEquals("12.3", CalibrationPreview.formatCoveragePercent(0.1234))
        assertEquals("0.0", CalibrationPreview.formatCoveragePercent(0.0))
        assertEquals("100.0", CalibrationPreview.formatCoveragePercent(1.0))
    }

    @Test
    fun `status decision found not-found and no-frame`() {
        assertEquals(PreviewStatus.FOUND, CalibrationPreview.decideStatus(true, 0.4))
        assertEquals(PreviewStatus.NOT_FOUND, CalibrationPreview.decideStatus(false, 0.001))
        assertEquals(PreviewStatus.NO_FRAME, CalibrationPreview.decideStatus(null, null))
        // An invalid profile (null table result) is no-frame, never "found".
        assertEquals(PreviewStatus.NO_FRAME, CalibrationPreview.decideStatus(null, 0.5))
    }

    @Test
    fun `reset values equal the defaults`() {
        val d = GameProfile.DEFAULT
        val defaults = CalibrationPreview.defaultSliders()
        assertEquals(d.brownHLo.toInt(), defaults.getValue("brownHLo"))
        assertEquals(d.brownHHi.toInt(), defaults.getValue("brownHHi"))
        assertEquals(d.brownSLo.toInt(), defaults.getValue("brownSLo"))
        assertEquals(d.brownSHi.toInt(), defaults.getValue("brownSHi"))
        assertEquals(d.brownVLo.toInt(), defaults.getValue("brownVLo"))
        assertEquals(d.brownVHi.toInt(), defaults.getValue("brownVHi"))
        assertEquals(
            d.pocketInsetFraction,
            CalibrationPreview.fractionSliderToValue(defaults.getValue("pocketInset")),
            0.00005
        )
        assertEquals(
            d.pocketRadiusFraction,
            CalibrationPreview.fractionSliderToValue(defaults.getValue("pocketRadius")),
            0.00005
        )
        assertEquals(
            d.ballRadiusPxAt720,
            CalibrationPreview.ballRadiusSliderToValue(defaults.getValue("ballRadius")),
            0.0
        )
    }
}
