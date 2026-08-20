package dev.hypnosia.visual.world.trails

import dev.hypnosia.config.HypnosiaClientSettings
import dev.hypnosia.visual.cosmetic.CosmeticSettings.GradientMode

object TrailSettings {
    private const val ENABLED_KEY = "visuals.trails.enabled"
    private const val ONLY_F5_KEY = "visuals.trails.only_f5"
    private const val LENGTH_KEY = "visuals.trails.length"
    private const val WIDTH_KEY = "visuals.trails.width"
    private const val ALPHA_KEY = "visuals.trails.alpha"
    private const val COLOR1_KEY = "visuals.trails.color1"
    private const val COLOR2_KEY = "visuals.trails.color2"
    private const val COLOR3_KEY = "visuals.trails.color3"
    private const val COLOR4_KEY = "visuals.trails.color4"
    private const val COLOR_COUNT_KEY = "visuals.trails.color_count"
    private const val GRADIENT_MODE_KEY = "visuals.trails.gradient_mode"
    private const val ANIM_SPEED_KEY = "visuals.trails.anim_speed"

    private var loaded = false

    private var cachedEnabled = false
    private var cachedOnlyF5 = false
    private var cachedLength = 40
    private var cachedWidth = 0.4f
    private var cachedAlpha = 180
    private var cachedColor1 = 0xFFFFFF
    private var cachedColor2 = 0xFF2F86.toInt()
    private var cachedColor3 = 0x2FB8FF
    private var cachedColor4 = 0xFFFFFF
    private var cachedColorCount = 1
    private var cachedGradientMode = GradientMode.STATIC
    private var cachedAnimSpeed = 1.0f

    fun enabled(): Boolean { ensureLoaded(); return cachedEnabled }
    fun setEnabled(v: Boolean) { cachedEnabled = v; loaded = true; HypnosiaClientSettings.set(ENABLED_KEY, v.toString()) }

    fun onlyF5(): Boolean { ensureLoaded(); return cachedOnlyF5 }
    fun setOnlyF5(v: Boolean) { cachedOnlyF5 = v; loaded = true; HypnosiaClientSettings.set(ONLY_F5_KEY, v.toString()) }

    fun length(): Int { ensureLoaded(); return cachedLength }
    fun setLength(v: Int) { cachedLength = v.coerceIn(10, 100); loaded = true; HypnosiaClientSettings.set(LENGTH_KEY, cachedLength.toString()) }

    fun width(): Float { ensureLoaded(); return cachedWidth }
    fun setWidth(v: Float) { cachedWidth = v.coerceIn(0.1f, 2.0f); loaded = true; HypnosiaClientSettings.set(WIDTH_KEY, String.format("%.2f", cachedWidth)) }

    fun alpha(): Int { ensureLoaded(); return cachedAlpha }
    fun setAlpha(v: Int) { cachedAlpha = v.coerceIn(50, 255); loaded = true; HypnosiaClientSettings.set(ALPHA_KEY, cachedAlpha.toString()) }

    fun color1(): Int { ensureLoaded(); return cachedColor1 }
    fun color2(): Int { ensureLoaded(); return cachedColor2 }
    fun color3(): Int { ensureLoaded(); return cachedColor3 }
    fun color4(): Int { ensureLoaded(); return cachedColor4 }
    fun setColor1(c: Int) { cachedColor1 = c and 0xFFFFFF; loaded = true; HypnosiaClientSettings.set(COLOR1_KEY, String.format("%06X", cachedColor1)) }
    fun setColor2(c: Int) { cachedColor2 = c and 0xFFFFFF; loaded = true; HypnosiaClientSettings.set(COLOR2_KEY, String.format("%06X", cachedColor2)) }
    fun setColor3(c: Int) { cachedColor3 = c and 0xFFFFFF; loaded = true; HypnosiaClientSettings.set(COLOR3_KEY, String.format("%06X", cachedColor3)) }
    fun setColor4(c: Int) { cachedColor4 = c and 0xFFFFFF; loaded = true; HypnosiaClientSettings.set(COLOR4_KEY, String.format("%06X", cachedColor4)) }

    fun colorCount(): Int { ensureLoaded(); return cachedColorCount }
    fun setColorCount(v: Int) { cachedColorCount = v.coerceIn(1, 4); loaded = true; HypnosiaClientSettings.set(COLOR_COUNT_KEY, cachedColorCount.toString()) }

    fun gradientMode(): GradientMode { ensureLoaded(); return cachedGradientMode }
    fun setGradientMode(v: GradientMode) { cachedGradientMode = v; loaded = true; HypnosiaClientSettings.set(GRADIENT_MODE_KEY, v.ordinal.toString()) }

    fun animSpeed(): Float { ensureLoaded(); return cachedAnimSpeed }
    fun setAnimSpeed(v: Float) { cachedAnimSpeed = v.coerceIn(0.0f, 5.0f); loaded = true; HypnosiaClientSettings.set(ANIM_SPEED_KEY, String.format("%.2f", cachedAnimSpeed)) }

    fun reload() { loaded = false }

    private fun ensureLoaded() {
        if (loaded) return
        cachedEnabled = HypnosiaClientSettings.string(ENABLED_KEY, "false").toBooleanStrictOrNull() ?: false
        cachedOnlyF5 = HypnosiaClientSettings.string(ONLY_F5_KEY, "false").toBooleanStrictOrNull() ?: false
        cachedLength = HypnosiaClientSettings.string(LENGTH_KEY, "40").toIntOrNull() ?: 40
        cachedWidth = HypnosiaClientSettings.string(WIDTH_KEY, "0.40").toFloatOrNull() ?: 0.4f
        cachedAlpha = HypnosiaClientSettings.string(ALPHA_KEY, "180").toIntOrNull() ?: 180
        cachedColor1 = HypnosiaClientSettings.string(COLOR1_KEY, "FFFFFF").toIntOrNull(16) ?: 0xFFFFFF
        cachedColor2 = HypnosiaClientSettings.string(COLOR2_KEY, "FF2F86").toIntOrNull(16) ?: 0xFF2F86.toInt()
        cachedColor3 = HypnosiaClientSettings.string(COLOR3_KEY, "2FB8FF").toIntOrNull(16) ?: 0x2FB8FF
        cachedColor4 = HypnosiaClientSettings.string(COLOR4_KEY, "FFFFFF").toIntOrNull(16) ?: 0xFFFFFF
        cachedColorCount = HypnosiaClientSettings.string(COLOR_COUNT_KEY, "1").toIntOrNull() ?: 1
        cachedGradientMode = GradientMode.entries.getOrNull(
            HypnosiaClientSettings.string(GRADIENT_MODE_KEY, "0").toIntOrNull() ?: 0
        ) ?: GradientMode.STATIC
        cachedAnimSpeed = HypnosiaClientSettings.string(ANIM_SPEED_KEY, "1.00").toFloatOrNull() ?: 1.0f
        loaded = true
    }
}
