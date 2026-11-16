package com.poolguide.tool

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poolguide.tool.capture.CaptureService
import com.poolguide.tool.overlay.OverlayService
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1.0.68: constructing either service must not touch native code.
 * Before the fix both services allocated OpenCV Mats as field
 * initialisers, so `OverlayService()` / `CaptureService()` threw
 * UnsatisfiedLinkError in a process where libopencv_java4 was not
 * loaded yet — the Start-capture / Demo crash on a fresh app
 * process. Reaching the end of these tests IS the test: on the old
 * code the test process died at construction. Note the suite is
 * order-dependent for the full story: on CI the library is usually
 * already loaded by an earlier test class (CvPipeline / BallDetector
 * call OpenCvInit.ensureLoaded in setup), so this guards the field
 * order rather than the runtime load. The decisive check is the
 * UptoPlay retest on a fresh process.
 */
@RunWith(AndroidJUnit4::class)
class ServiceConstructionInstrumentedTest {

    @Test
    fun overlayService_constructsWithoutNativeCode() {
        OverlayService()
    }

    @Test
    fun captureService_constructsWithoutNativeCode() {
        CaptureService()
    }
}
