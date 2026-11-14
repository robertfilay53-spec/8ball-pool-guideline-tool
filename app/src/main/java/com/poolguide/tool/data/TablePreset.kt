package com.poolguide.tool.data

import com.poolguide.tool.geometry.Vec2

/**
 * A named table-size preset (v1.0.18): a full calibration snapshot —
 * the [GameProfile] (pocket inset/radius, ball radius, brown felt
 * window) plus the table corners — saved under one name ("7ft", "8ft",
 * "9ft") so a player who switches tables loads the right starting
 * values instead of re-calibrating every slider by hand.
 *
 * Pure Kotlin (no Android dependency) so it is unit-testable.
 *
 * Built-ins are scaled from the 9ft default by the table-length ratio
 * (9/8 for 8ft, 9/7 for 7ft): at the same camera framing a smaller
 * table fills the same frame, so balls and pockets appear larger
 * relative to the table width, and the size-dependent values grow by
 * that ratio. The brown felt window does not scale — felt colour does
 * not depend on table size — and the corners do not scale either:
 * they are normalised camera geometry, so the built-ins use the same
 * default corners and the player drags them for the real table.
 *
 * A preset is a starting point, not physics: pocket cut, cloth, and
 * camera height all vary between real tables, so the player still
 * checks the felt mask + status line on the Calibrate screen and taps
 * Save. Applying a preset only fills the screen; nothing is persisted
 * until Save (the same contract as the rest of the screen).
 */
data class TablePreset(
    val name: String,
    val profile: GameProfile,
    val corners: List<Vec2>
) {
    init {
        require(name.isNotBlank()) { "preset name must not be blank" }
        require(corners.size == 4) { "preset needs exactly 4 corners, got ${corners.size}" }
    }

    /** One line for storage: 9 profile numbers + 8 corner coords. */
    fun serialize(): String {
        val p = profile
        val numbers = listOf(
            p.pocketInsetFraction, p.pocketRadiusFraction, p.ballRadiusPxAt720,
            p.brownHLo, p.brownSLo, p.brownVLo, p.brownHHi, p.brownSHi, p.brownVHi
        ) + corners.flatMap { listOf(it.x, it.y) }
        return numbers.joinToString(",")
    }

    companion object {
        /** The three built-in sizes, in the order the screen shows them. */
        val BUILTIN_NAMES = listOf("7ft", "8ft", "9ft")

        /** Default corners (normalised), the same as the Calibrate
         * screen's starting handles. */
        val DEFAULT_CORNERS = listOf(
            Vec2(0.08, 0.15), Vec2(0.92, 0.15),
            Vec2(0.92, 0.85), Vec2(0.08, 0.85)
        )

        fun isBuiltinName(name: String): Boolean = name in BUILTIN_NAMES

        /** The built-in preset for [name], or null for an unknown name.
         * [version] stamps the profile, like [GameProfile.forVersion]. */
        fun builtin(name: String, version: String): TablePreset? {
            val feet = when (name) {
                "7ft" -> 7.0
                "8ft" -> 8.0
                "9ft" -> 9.0
                else -> return null
            }
            val factor = 9.0 / feet
            val d = GameProfile.DEFAULT
            return TablePreset(
                name = name,
                profile = d.copy(
                    version = version,
                    pocketInsetFraction = d.pocketInsetFraction * factor,
                    pocketRadiusFraction = d.pocketRadiusFraction * factor,
                    ballRadiusPxAt720 = d.ballRadiusPxAt720 * factor
                ),
                corners = DEFAULT_CORNERS
            )
        }

        /** Parse a stored [serialize] line, or null when it is missing
         * or malformed (wrong count, non-numeric, out of range, or a
         * corner outside 0..1). */
        fun parse(raw: String?, name: String, version: String): TablePreset? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(",").mapNotNull { it.toDoubleOrNull() }
            if (parts.size != 17) return null
            val corners = listOf(
                Vec2(parts[9], parts[10]), Vec2(parts[11], parts[12]),
                Vec2(parts[13], parts[14]), Vec2(parts[15], parts[16])
            )
            if (corners.any { it.x !in 0.0..1.0 || it.y !in 0.0..1.0 }) return null
            return try {
                TablePreset(
                    name = name,
                    profile = GameProfile(
                        version = version,
                        pocketInsetFraction = parts[0],
                        pocketRadiusFraction = parts[1],
                        ballRadiusPxAt720 = parts[2],
                        brownHLo = parts[3], brownSLo = parts[4], brownVLo = parts[5],
                        brownHHi = parts[6], brownSHi = parts[7], brownVHi = parts[8]
                    ),
                    corners = corners
                )
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
