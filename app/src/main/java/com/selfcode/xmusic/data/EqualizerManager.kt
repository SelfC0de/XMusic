package com.selfcode.xmusic.data

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer

object EqualizerManager {

    private const val PREFS = "xmusic_eq"
    private const val KEY_ENABLED = "eq_enabled"
    private const val KEY_PRESET = "eq_preset"
    private const val KEY_BASS = "bass_strength"
    private const val KEY_BANDS = "eq_bands"

    private lateinit var prefs: SharedPreferences
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null

    data class Preset(val name: String, val bands: ShortArray)

    val presets = listOf(
        Preset("Flat", shortArrayOf(0, 0, 0, 0, 0)),
        Preset("Bass Boost", shortArrayOf(600, 400, 0, 0, 0)),
        Preset("Rock", shortArrayOf(400, 200, -100, 200, 400)),
        Preset("Pop", shortArrayOf(-100, 200, 400, 200, -100)),
        Preset("Jazz", shortArrayOf(300, 0, 100, 200, 400)),
        Preset("Classical", shortArrayOf(400, 200, 0, 200, 400)),
        Preset("Hip-Hop", shortArrayOf(500, 300, 0, 100, 300)),
        Preset("Electronic", shortArrayOf(400, 200, 0, -200, 400)),
        Preset("Vocal", shortArrayOf(-200, 0, 300, 200, 0)),
        Preset("Deep Bass", shortArrayOf(800, 600, 200, 0, -100))
    )

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)
    fun setEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun getCurrentPreset(): Int = prefs.getInt(KEY_PRESET, 0)
    fun setCurrentPreset(idx: Int) = prefs.edit().putInt(KEY_PRESET, idx).apply()

    fun getBassStrength(): Int = prefs.getInt(KEY_BASS, 0)
    fun setBassStrength(strength: Int) = prefs.edit().putInt(KEY_BASS, strength).apply()

    fun getCustomBands(): ShortArray {
        val s = prefs.getString(KEY_BANDS, null) ?: return shortArrayOf(0, 0, 0, 0, 0)
        return try {
            s.split(",").map { it.toShort() }.toShortArray()
        } catch (_: Exception) {
            shortArrayOf(0, 0, 0, 0, 0)
        }
    }

    fun setCustomBands(bands: ShortArray) {
        prefs.edit().putString(KEY_BANDS, bands.joinToString(",")).apply()
    }

    fun attachToSession(audioSessionId: Int) {
        release()
        try {
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = isEnabled()
            }
            bassBoost = BassBoost(0, audioSessionId).apply {
                enabled = isEnabled()
            }
            if (isEnabled()) {
                applyCurrentSettings()
            }
        } catch (_: Exception) {}
    }

    fun applyCurrentSettings() {
        val eq = equalizer ?: return
        val preset = getCurrentPreset()
        val bands = if (preset >= 0 && preset < presets.size) presets[preset].bands else getCustomBands()
        val numBands = eq.numberOfBands.toInt()
        val range = eq.bandLevelRange
        val minLevel = range[0]
        val maxLevel = range[1]

        for (i in 0 until minOf(numBands, bands.size)) {
            val level = bands[i].toInt().coerceIn(minLevel.toInt(), maxLevel.toInt()).toShort()
            try {
                eq.setBandLevel(i.toShort(), level)
            } catch (_: Exception) {}
        }

        try {
            val strength = getBassStrength().toShort().coerceIn(0, 1000)
            bassBoost?.setStrength(strength)
        } catch (_: Exception) {}
    }

    fun applyEnabled(enabled: Boolean) {
        setEnabled(enabled)
        equalizer?.enabled = enabled
        bassBoost?.enabled = enabled
        if (enabled) applyCurrentSettings()
    }

    fun applyPreset(idx: Int) {
        setCurrentPreset(idx)
        if (isEnabled()) applyCurrentSettings()
    }

    fun applyBassStrength(strength: Int) {
        setBassStrength(strength)
        try {
            bassBoost?.setStrength(strength.toShort().coerceIn(0, 1000))
        } catch (_: Exception) {}
    }

    fun applyBandLevel(band: Int, level: Short) {
        try {
            equalizer?.setBandLevel(band.toShort(), level)
        } catch (_: Exception) {}
        val bands = getCustomBands().copyOf(5)
        if (band < bands.size) bands[band] = level
        setCustomBands(bands)
    }

    fun getBandCount(): Int = equalizer?.numberOfBands?.toInt() ?: 5

    fun getBandLevelRange(): Pair<Short, Short> {
        val range = equalizer?.bandLevelRange ?: shortArrayOf(-1500, 1500)
        return Pair(range[0], range[1])
    }

    fun getBandFreq(band: Int): Int {
        return try {
            (equalizer?.getCenterFreq(band.toShort()) ?: 0) / 1000
        } catch (_: Exception) { 0 }
    }

    fun release() {
        try { equalizer?.release() } catch (_: Exception) {}
        try { bassBoost?.release() } catch (_: Exception) {}
        equalizer = null
        bassBoost = null
    }
}
