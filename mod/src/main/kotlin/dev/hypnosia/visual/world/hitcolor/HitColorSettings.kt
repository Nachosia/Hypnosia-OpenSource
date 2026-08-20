package dev.hypnosia.visual.world.hitcolor

import dev.hypnosia.config.HypnosiaClientSettings
import dev.hypnosia.visual.cosmetic.CosmeticSettings.GradientMode

object HitColorSettings {
    private const val ENABLED_KEY = "visuals.hit_color.enabled"
    private const val COLOR1_KEY = "visuals.hit_color.color1"
    private const val COLOR2_KEY = "visuals.hit_color.color2"
    private const val COLOR3_KEY = "visuals.hit_color.color3"
    private const val COLOR4_KEY = "visuals.hit_color.color4"
    private const val COLOR_COUNT_KEY = "visuals.hit_color.color_count"
    private const val GRADIENT_MODE_KEY = "visuals.hit_color.gradient_mode"
    private const val ANIM_SPEED_KEY = "visuals.hit_color.anim_speed"

    private var loaded = false

    private var cachedEnabled = false
    private var cachedColor1 = 0xFF0000
    private var cachedColor2 = 0xFF2F86.toInt()
    private var cachedColor3 = 0x2FB8FF
    private var cachedColor4 = 0xFFFFFF
    private var cachedColorCount = 1
    private var cachedGradientMode = GradientMode.STATIC
    private var cachedAnimSpeed = 1.0f

    fun enabled(): Boolean { ensureLoaded(); return cachedEnabled }
    fun setEnabled(v: Boolean) { cachedEnabled = v; loaded = true; HypnosiaClientSettings.set(ENABLED_KEY, v.toString()) }

    fun color1(): Int { ensureLoaded(); return cachedColor1 }
    fun color2(): Int { ensureLoaded(); return cachedColor2 }
    fun color3(): Int { ensureLoaded(); return cachedColor3 }
    fun color4(): Int { ensureLoaded(); return cachedColor4 }
    fun setColor1(c: Int) { cachedColor1 = c and 0xFFFFFF; loaded = true; HypnosiaClientSettings.set(COLOR1_KEY, String.format("%06X", cachedColor1)); markDirty() }
    fun setColor2(c: Int) { cachedColor2 = c and 0xFFFFFF; loaded = true; HypnosiaClientSettings.set(COLOR2_KEY, String.format("%06X", cachedColor2)); markDirty() }
    fun setColor3(c: Int) { cachedColor3 = c and 0xFFFFFF; loaded = true; HypnosiaClientSettings.set(COLOR3_KEY, String.format("%06X", cachedColor3)); markDirty() }
    fun setColor4(c: Int) { cachedColor4 = c and 0xFFFFFF; loaded = true; HypnosiaClientSettings.set(COLOR4_KEY, String.format("%06X", cachedColor4)); markDirty() }

    fun colorCount(): Int { ensureLoaded(); return cachedColorCount }
    fun setColorCount(v: Int) { cachedColorCount = v.coerceIn(1, 4); loaded = true; HypnosiaClientSettings.set(COLOR_COUNT_KEY, cachedColorCount.toString()) }

    fun gradientMode(): GradientMode { ensureLoaded(); return cachedGradientMode }
    fun setGradientMode(v: GradientMode) { cachedGradientMode = v; loaded = true; HypnosiaClientSettings.set(GRADIENT_MODE_KEY, v.ordinal.toString()) }

    fun animSpeed(): Float { ensureLoaded(); return cachedAnimSpeed }
    fun setAnimSpeed(v: Float) { cachedAnimSpeed = v.coerceIn(0.0f, 5.0f); loaded = true; HypnosiaClientSettings.set(ANIM_SPEED_KEY, String.format("%.2f", cachedAnimSpeed)) }

    @Volatile
    var dirty = true
        private set

    fun markDirty() { dirty = true }
    fun clearDirty() { dirty = false }

    fun reload() { loaded = false }

    private fun ensureLoaded() {
        if (loaded) return
        cachedEnabled = HypnosiaClientSettings.string(ENABLED_KEY, "false").toBooleanStrictOrNull() ?: false
        cachedColor1 = HypnosiaClientSettings.string(COLOR1_KEY, "FF0000").toIntOrNull(16) ?: 0xFF0000
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
