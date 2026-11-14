package com.poolguide.tool.data

import android.content.Context
import com.poolguide.tool.geometry.Vec2

/**
 * Persistent settings + table calibration (SharedPreferences).
 * Table corners are stored normalised (0-1) so they survive resolution
 * changes, and keyed per game version (per-version calibration profiles).
 */
class CalibrationStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var gameVersion: String
        get() = prefs.getString(KEY_GAME_VERSION, DEFAULT_GAME_VERSION) ?: DEFAULT_GAME_VERSION
        set(value) = prefs.edit().putString(KEY_GAME_VERSION, value).apply()

    /** First-run warning shown? */
    var warningSeen: Boolean
        get() = prefs.getBoolean(KEY_WARNING_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_WARNING_SEEN, value).apply()

    /** "Hide guide while shooting" toggle. */
    var guidesHidden: Boolean
        get() = prefs.getBoolean(KEY_GUIDES_HIDDEN, false)
        set(value) = prefs.edit().putBoolean(KEY_GUIDES_HIDDEN, value).apply()

    /** Draw one-rail bank lines when no direct shot exists. */
    var bankLinesEnabled: Boolean
        get() = prefs.getBoolean(KEY_BANK_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BANK_ENABLED, value).apply()

    /** Draw every candidate shot faint, best highlighted (PoolAimAssist
     * UX). When false, only the best shot is drawn. */
    var showAllShots: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ALL_SHOTS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_ALL_SHOTS, value).apply()

    /** Per-version calibration profile (aimcall lead): pocket inset,
     * pocket radius, ball radius at the working resolution. Stored as
     * "inset,radius,ballRadius" under game_profile_<version>; unknown
     * versions fall back to the defaults stamped with that version. */
    fun getGameProfile(version: String = gameVersion): GameProfile {
        val raw = prefs.getString(keyForProfile(version), null) ?: return GameProfile.forVersion(version)
        val parts = raw.split(",").mapNotNull { it.toDoubleOrNull() }
        if (parts.size != 3) return GameProfile.forVersion(version)
        return try {
            GameProfile(version, parts[0], parts[1], parts[2])
        } catch (_: IllegalArgumentException) {
            GameProfile.forVersion(version)
        }
    }

    fun setGameProfile(profile: GameProfile) {
        val raw = "${profile.pocketInsetFraction},${profile.pocketRadiusFraction},${profile.ballRadiusPxAt720}"
        prefs.edit().putString(keyForProfile(profile.version), raw).apply()
    }

    fun clearGameProfile(version: String = gameVersion) {
        prefs.edit().remove(keyForProfile(version)).apply()
    }

    /** Manual table corners (normalised 0-1), or null for auto-detect. */
    fun getTableCorners(version: String = gameVersion): List<Vec2>? {
        val raw = prefs.getString(keyFor(version), null) ?: return null
        val parts = raw.split(",").mapNotNull { it.toFloatOrNull() }
        if (parts.size != 8) return null
        return listOf(
            Vec2(parts[0].toDouble(), parts[1].toDouble()),
            Vec2(parts[2].toDouble(), parts[3].toDouble()),
            Vec2(parts[4].toDouble(), parts[5].toDouble()),
            Vec2(parts[6].toDouble(), parts[7].toDouble())
        )
    }

    fun setTableCorners(corners: List<Vec2>, version: String = gameVersion) {
        require(corners.size == 4)
        val raw = corners.joinToString(",") { "${it.x.toFloat()},${it.y.toFloat()}" }
        prefs.edit().putString(keyFor(version), raw).apply()
    }

    fun clearTableCorners(version: String = gameVersion) {
        prefs.edit().remove(keyFor(version)).apply()
    }

    private fun keyFor(version: String) = "table_corners_$version"

    private fun keyForProfile(version: String) = "game_profile_$version"

    companion object {
        const val PREFS = "poolguide"
        const val KEY_GAME_VERSION = "game_version"
        const val KEY_WARNING_SEEN = "warning_seen"
        const val KEY_GUIDES_HIDDEN = "guides_hidden"
        const val KEY_BANK_ENABLED = "bank_enabled"
        const val KEY_SHOW_ALL_SHOTS = "show_all_shots"
        const val DEFAULT_GAME_VERSION = "56.35.0"
    }
}
