package com.poolguide.tool.geometry

import kotlin.math.sqrt

/**
 * 2D vector on the table plane (pixels in the analysis frame unless noted).
 * Pure Kotlin, no Android dependency — fully unit-testable on the JVM.
 */
data class Vec2(val x: Double, val y: Double) {

    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)

    fun dot(o: Vec2): Double = x * o.x + y * o.y

    fun norm(): Double = sqrt(x * x + y * y)

    /** Unit vector. Returns (0, 0) for a zero vector instead of NaN. */
    fun normalized(): Vec2 {
        val n = norm()
        return if (n < 1e-9) Vec2(0.0, 0.0) else this * (1.0 / n)
    }

    /** 90-degree counter-clockwise rotation: (-y, x). */
    fun perp(): Vec2 = Vec2(-y, x)

    fun distanceTo(o: Vec2): Double = (this - o).norm()

    override fun toString(): String = "(${"%.1f".format(x)}, ${"%.1f".format(y)})"
}

/** A line segment, start -> end. */
data class Segment(val start: Vec2, val end: Vec2) {
    fun length(): Double = start.distanceTo(end)
    fun direction(): Vec2 = (end - start).normalized()
}

/**
 * A cushion (rail) as a line: any point [point] on the rail and a unit normal
 * [normal] pointing INTO the table.
 */
data class Rail(val point: Vec2, val normal: Vec2) {
    init {
        require(kotlin.math.abs(normal.norm() - 1.0) < 1e-6) {
            "Rail normal must be a unit vector, got $normal"
        }
    }
}
