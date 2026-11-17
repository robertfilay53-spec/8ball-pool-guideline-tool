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

    /** A live/demo session is active (v1.0.71, Vivo survival): set when
     * a session starts, cleared on every clean stop. The services read
     * this on a null-intent restart (the system restarting a sticky
     * service after the process was killed): a session that was never
     * cleanly stopped is recovered, a stopped one is not. */
    var sessionActive: Boolean
        get() = prefs.getBoolean(KEY_SESSION_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_SESSION_ACTIVE, value).apply()

    /** The active session is Demo mode (v1.0.71): the overlay recovery
     * restarts the demo loop instead of live mode when this is set. */
    var sessionDemo: Boolean
        get() = prefs.getBoolean(KEY_SESSION_DEMO, false)
        set(value) = prefs.edit().putBoolean(KEY_SESSION_DEMO, value).apply()

    /** Battery-optimisation exemption asked once (v1.0.71): the dialog
     * is shown at most once per install, so a declined answer is never
     * nagged again; the README carries the manual Vivo steps. */
    var batteryOptAsked: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_OPT_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_OPT_ASKED, value).apply()

    /** Draw one-rail bank and kick lines when no direct shot exists
     * (v1.0.13: also the two-rail bank/kick and combo/kiss fallback
     * lines — every family the live overlay draws after direct). */
    var bankLinesEnabled: Boolean
        get() = prefs.getBoolean(KEY_BANK_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BANK_ENABLED, value).apply()

    /** Draw every candidate shot faint, best highlighted (PoolAimAssist
     * UX). When false, only the best shot is drawn. */
    var showAllShots: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ALL_SHOTS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_ALL_SHOTS, value).apply()

    /** Control-bubble position in px (TOP|START gravity), or null when
     * never dragged. Saved on drag release (Bloro: remember_pos). */
    var bubbleX: Int?
        get() = if (prefs.contains(KEY_BUBBLE_X)) prefs.getInt(KEY_BUBBLE_X, 0) else null
        set(value) {
            if (value == null) prefs.edit().remove(KEY_BUBBLE_X).apply()
            else prefs.edit().putInt(KEY_BUBBLE_X, value).apply()
        }

    var bubbleY: Int?
        get() = if (prefs.contains(KEY_BUBBLE_Y)) prefs.getInt(KEY_BUBBLE_Y, 0) else null
        set(value) {
            if (value == null) prefs.edit().remove(KEY_BUBBLE_Y).apply()
            else prefs.edit().putInt(KEY_BUBBLE_Y, value).apply()
        }

    /** Per-version calibration profile (aimcall lead): pocket inset,
     * pocket radius, ball radius at the working resolution. Stored as
     * "inset,radius,ballRadius" under game_profile_<version>; unknown
     * versions fall back to the defaults stamped with that version.
     * The brown felt window lives under a separate key (below) so the
     * 3-part profile string stays backward compatible. */
    fun getGameProfile(version: String = gameVersion): GameProfile {
        val raw = prefs.getString(keyForProfile(version), null) ?: return GameProfile.forVersion(version)
            .withBrownWindow(version)
        val parts = raw.split(",").mapNotNull { it.toDoubleOrNull() }
        if (parts.size != 3) return GameProfile.forVersion(version).withBrownWindow(version)
        return try {
            GameProfile(version, parts[0], parts[1], parts[2]).withBrownWindow(version)
        } catch (_: IllegalArgumentException) {
            GameProfile.forVersion(version).withBrownWindow(version)
        }
    }

    /** This profile with the stored brown felt window applied, when one
     * exists for [version]; otherwise unchanged. */
    private fun GameProfile.withBrownWindow(version: String): GameProfile {
        val brown = GameProfile.parseBrownWindow(
            prefs.getString(keyForBrown(version), null)
        ) ?: return this
        return try {
            copy(
                brownHLo = brown[0], brownSLo = brown[1], brownVLo = brown[2],
                brownHHi = brown[3], brownSHi = brown[4], brownVHi = brown[5]
            )
        } catch (_: IllegalArgumentException) {
            this
        }
    }

    /** Store a brown felt HSV window for [version]. Stored separately
     * from the 3-part profile string (see [getGameProfile]). */
    fun setBrownFeltWindow(lower: DoubleArray, upper: DoubleArray, version: String = gameVersion) {
        val raw = GameProfile.serializeBrownWindow(lower, upper)
        prefs.edit().putString(keyForBrown(version), raw).apply()
    }

    fun clearBrownFeltWindow(version: String = gameVersion) {
        prefs.edit().remove(keyForBrown(version)).apply()
    }

    fun setGameProfile(profile: GameProfile) {
        val raw = "${profile.pocketInsetFraction},${profile.pocketRadiusFraction},${profile.ballRadiusPxAt720}"
        prefs.edit().putString(keyForProfile(profile.version), raw).apply()
    }

    fun clearGameProfile(version: String = gameVersion) {
        prefs.edit().remove(keyForProfile(version)).apply()
    }

    /** Table-size presets (v1.0.18): named calibration snapshots
     * ("7ft", "8ft", "9ft"). Built-ins always exist; saving a preset
     * stores an override under `table_preset_<name>`, and deleting it
     * falls back to the built-in. The saved name set lives under
     * KEY_PRESET_NAMES so [listPresetNames] can find overrides. */
    fun listPresetNames(): List<String> {
        val saved = prefs.getStringSet(KEY_PRESET_NAMES, emptySet()).orEmpty()
        return (TablePreset.BUILTIN_NAMES + saved.filter { it !in TablePreset.BUILTIN_NAMES })
            .distinct()
    }

    /** The preset for [name]: the saved override when one exists, else
     * the built-in. Throws for an unknown name. */
    fun getPreset(name: String, version: String = gameVersion): TablePreset {
        val raw = prefs.getString(keyForPreset(name), null)
        TablePreset.parse(raw, name, version)?.let { return it }
        return TablePreset.builtin(name, version)
            ?: throw IllegalArgumentException("unknown preset: $name")
    }

    fun savePreset(preset: TablePreset) {
        prefs.edit()
            .putString(keyForPreset(preset.name), preset.serialize())
            .putStringSet(KEY_PRESET_NAMES, listPresetNames().toSet() + preset.name)
            .apply()
    }

    /** Delete the saved override for [name]; the built-in (when one
     * exists) returns on the next read. */
    fun deletePreset(name: String) {
        prefs.edit()
            .remove(keyForPreset(name))
            .putStringSet(KEY_PRESET_NAMES, listPresetNames().toSet() - name)
            .apply()
    }

    /** Delete every saved preset override (tests); built-ins remain. */
    fun clearPresets() {
        val editor = prefs.edit()
        for (name in listPresetNames()) editor.remove(keyForPreset(name))
        editor.remove(KEY_PRESET_NAMES).apply()
    }

    private fun keyForPreset(name: String) = "table_preset_$name"

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

    private fun keyForBrown(version: String) = "brown_felt_$version"

    companion object {
        const val PREFS = "poolguide"
        const val KEY_GAME_VERSION = "game_version"
        const val KEY_WARNING_SEEN = "warning_seen"
        const val KEY_GUIDES_HIDDEN = "guides_hidden"
        const val KEY_BANK_ENABLED = "bank_enabled"
        const val KEY_SHOW_ALL_SHOTS = "show_all_shots"
        const val KEY_BUBBLE_X = "bubble_x"
        const val KEY_BUBBLE_Y = "bubble_y"
        const val KEY_SESSION_ACTIVE = "session_active"
        const val KEY_SESSION_DEMO = "session_demo"
        const val KEY_BATTERY_OPT_ASKED = "battery_opt_asked"
        const val KEY_PRESET_NAMES = "preset_names"
        const val DEFAULT_GAME_VERSION = "56.35.0"
    }
}
