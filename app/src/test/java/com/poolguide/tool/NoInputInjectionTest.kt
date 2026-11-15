package com.poolguide.tool

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No-input-injection guard (v1.0.42, REA hardening R6).
 *
 * The companion model only works because the tool never injects
 * input: no scripted taps, no autoplay, no accessibility-driven
 * play. The game forwards every touch to Anybrain (signal S2 in
 * the REA deep dive), so any injected input would be visible as
 * inhuman touch data. This test scans the main source set and
 * fails if any input-injection API is referenced anywhere, so a
 * future cycle cannot silently add one. The tokens are matched as
 * plain substrings, so even a comment naming one trips the test:
 * that is deliberate, the rule is "nowhere in the code".
 */
class NoInputInjectionTest {

    /** APIs that must never appear in the main source set. */
    private val forbidden = listOf(
        "dispatchGesture",
        "AccessibilityService",
        "UiAutomation",
        "Instrumentation"
    )

    @Test
    fun `main source set references no input injection API`() {
        val srcDir = File("src/main/java").takeIf { it.isDirectory }
            ?: File("app/src/main/java").takeIf { it.isDirectory }
            ?: error("cannot find the main source set from ${File(".").absolutePath}")
        val hits = ArrayList<String>()
        srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                for (token in forbidden) {
                    if (token in text) hits += "${file.path}: $token"
                }
            }
        assertTrue(
            "input-injection API referenced:\n" + hits.joinToString("\n"),
            hits.isEmpty()
        )
    }
}
