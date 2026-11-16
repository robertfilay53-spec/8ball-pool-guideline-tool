package com.poolguide.tool.cv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.opencv.core.Mat

/** v1.0.68: the Start-capture / Demo crash root cause, pinned as a
 * test. OpenCV's Mat is a JNI wrapper: the default constructor
 * allocates natively, so constructing a Mat (or any class holding
 * Mat fields) before libopencv_java4 is loaded throws
 * UnsatisfiedLinkError — an Error, not an Exception, so no
 * catch-Exception anywhere could have saved the services. Before
 * the fix both services allocated Mats as field initialisers, so
 * the process died the moment the service was constructed, before
 * onStartCommand and before any diagnostics. The fix moves every
 * Mat allocation to after OpenCvInit.ensureLoaded(). */
class OpenCvLoadOrderTest {

    @Test
    fun `Mat without the native library throws UnsatisfiedLinkError`() {
        assertThrows(UnsatisfiedLinkError::class.java) { Mat() }
    }

    @Test
    fun `ensureLoaded fails safe when the native library is absent`() {
        // On the host JVM there is no libopencv_java4, so loading
        // fails — and it must fail by returning false, never by
        // throwing, because both services branch on this result.
        assertFalse(OpenCvInit.ensureLoaded())
    }
}
