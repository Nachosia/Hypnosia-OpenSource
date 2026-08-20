package dev.hypnosia.visual.world.esp

import dev.hypnosia.config.HypnosiaClientSettings
import dev.hypnosia.visual.cosmetic.CosmeticSettings.GradientMode

object TargetEspSettings {
    private const val ENABLED_KEY = "visuals.target_esp.enabled"
    private const val TEXTURE_KEY = "visuals.target_esp.texture"
    private const val SIZE_KEY = "visuals.target_esp.size"
    private const val LIFETIME_KEY = "visuals.target_esp.lifetime"
    private const val ALPHA_KEY = "visuals.target_esp.alpha"
    private const val COLOR1_KEY = "visuals.target_esp.color1"
    private const val COLOR2_KEY = "visuals.target_esp.color2"
    private const val COLOR3_KEY = "visuals.target_esp.color3"
    private const val COLOR4_KEY = "visuals.target_esp.color4"
    private const val COLOR_COUNT_KEY = "visuals.target_esp.color_count"
    private const val GRADIENT_MODE_KEY = "visuals.target_esp.gradient_mode"
    private const val ANIM_SPEED_KEY = "visuals.target_esp.anim_speed"
    private const val ROTATION_SPEED_KEY = "visuals.target_esp.rotation_speed"

    private var loaded = false
    private var cachedEnabled = false
    private var cachedTexture = TargetEspTexture.PLANET
    private var cachedSize = 1.5f
    private var cachedLifetime = 40
    private var cachedAlpha = 200
    private var cachedColor1 = 0xFFFFFF
    private var cachedColor2 = 0xFF2F86.toInt()
    private var cachedColor3 = 0x2FB8FF
    private var cachedColor4 = 0xFFFFFF
    private var cachedColorCount = 1
    private var cachedGradientMode = GradientMode.STATIC
    private var cachedAnimSpeed = 1.0f
    private var cachedRotationSpeed = 1.0f

    fun enabled(): Boolean { ensureLoaded(); return cachedEnabled }
    fun setEnabled(v: Boolean) { cachedEnabled = v; loaded = true; HypnosiaClientSettings.set(ENABLED_KEY, v.toString()) }

    fun texture(): TargetEspTexture { ensureLoaded(); return cachedTexture }
    fun setTexture(v: TargetEspTexture) { cachedTexture = v; loaded = true; HypnosiaClientSettings.set(TEXTURE_KEY, v.ordinal.toString()) }

    fun size(): Float { ensureLoaded(); return cachedSize }
    fun setSize(v: Float) { cachedSize = v.coerceIn(0.5f, 5.0f); loaded = true; HypnosiaClientSettings.set(SIZE_KEY, String.format("%.2f", cachedSize)) }

    fun lifetime(): Int { ensureLoaded(); return cachedLifetime }
    fun setLifetime(v: Int) { cachedLifetime = v.coerceIn(10, 100); loaded = true; HypnosiaClientSettings.set(LIFETIME_KEY, cachedLifetime.toString()) }

    fun alpha(): Int { ensureLoaded(); return cachedAlpha }
    fun setAlpha(v: Int) { cachedAlpha = v.coerceIn(50, 255); loaded = true; HypnosiaClientSettings.set(ALPHA_KEY, cachedAlpha.toString()) }

    fun color1(): Int { ensureLoaded(); return cachedColor1 }
    fun color2(): Int { ensureLoaded(); return cachedColor2 }
    fun color3(): Int { ensureLoaded(); return cachedColor3 }
    fun color4(): Int { ensureLoaded(); return cachedColor4 }

    fun setColor1(color: Int) { cachedColor1 = color and 0xFFFFFF; loaded = true; HypnosiaClientSettings.set(COLOR1_KEY, String.format("%06X", cachedColor1)) }
    fun setColor2(color: Int) { cachedColor2 = color and 0xFFFFFF; loaded = true; HypnosiaClientSettings.set(COLOR2_KEY, String.format("%06X", cachedColor2)) }
    fun setColor3(color: Int) { cachedColor3 = color and 0xFFFFFF; loaded = true; HypnosiaClientSettings.set(COLOR3_KEY, String.format("%06X", cachedColor3)) }
    fun setColor4(color: Int) { cachedColor4 = color and 0xFFFFFF; loaded = true; HypnosiaClientSettings.set(COLOR4_KEY, String.format("%06X", cachedColor4)) }

    fun colorCount(): Int { ensureLoaded(); return cachedColorCount }
    fun setColorCount(count: Int) { val v = count.coerceIn(1, 4); cachedColorCount = v; loaded = true; HypnosiaClientSettings.set(COLOR_COUNT_KEY, v.toString()) }

    fun gradientMode(): GradientMode { ensureLoaded(); return cachedGradientMode }
    fun setGradientMode(mode: GradientMode) { cachedGradientMode = mode; loaded = true; HypnosiaClientSettings.set(GRADIENT_MODE_KEY, mode.ordinal.toString()) }

    fun animSpeed(): Float { ensureLoaded(); return cachedAnimSpeed }
    fun setAnimSpeed(speed: Float) { val v = speed.coerceIn(0.0f, 5.0f); cachedAnimSpeed = v; loaded = true; HypnosiaClientSettings.set(ANIM_SPEED_KEY, String.format("%.2f", v)) }

    fun rotationSpeed(): Float { ensureLoaded(); return cachedRotationSpeed }
    fun setRotationSpeed(speed: Float) { val v = speed.coerceIn(0.0f, 5.0f); cachedRotationSpeed = v; loaded = true; HypnosiaClientSettings.set(ROTATION_SPEED_KEY, String.format("%.2f", v)) }

    fun reload() { loaded = false }

    fun ensureLoaded() {
        if (loaded) return
        cachedEnabled = HypnosiaClientSettings.string(ENABLED_KEY, "false").toBooleanStrictOrNull() ?: false
        cachedTexture = TargetEspTexture.byOrdinal(HypnosiaClientSettings.string(TEXTURE_KEY, "0").toIntOrNull() ?: 0)
        cachedSize = HypnosiaClientSettings.string(SIZE_KEY, "1.50").toFloatOrNull() ?: 1.5f
        cachedLifetime = HypnosiaClientSettings.string(LIFETIME_KEY, "40").toIntOrNull() ?: 40
        cachedAlpha = HypnosiaClientSettings.string(ALPHA_KEY, "200").toIntOrNull() ?: 200
        cachedColor1 = HypnosiaClientSettings.string(COLOR1_KEY, "FFFFFF").toIntOrNull(16) ?: 0xFFFFFF
        cachedColor2 = HypnosiaClientSettings.string(COLOR2_KEY, "FF2F86").toIntOrNull(16) ?: 0xFF2F86.toInt()
        cachedColor3 = HypnosiaClientSettings.string(COLOR3_KEY, "2FB8FF").toIntOrNull(16) ?: 0x2FB8FF
        cachedColor4 = HypnosiaClientSettings.string(COLOR4_KEY, "FFFFFF").toIntOrNull(16) ?: 0xFFFFFF
        cachedColorCount = HypnosiaClientSettings.string(COLOR_COUNT_KEY, "1").toIntOrNull() ?: 1
        cachedGradientMode = GradientMode.entries.getOrNull(
            HypnosiaClientSettings.string(GRADIENT_MODE_KEY, "0").toIntOrNull() ?: 0
        ) ?: GradientMode.STATIC
        cachedAnimSpeed = HypnosiaClientSettings.string(ANIM_SPEED_KEY, "1.00").toFloatOrNull() ?: 1.0f
        cachedRotationSpeed = HypnosiaClientSettings.string(ROTATION_SPEED_KEY, "1.00").toFloatOrNull() ?: 1.0f
        loaded = true
    }
}
