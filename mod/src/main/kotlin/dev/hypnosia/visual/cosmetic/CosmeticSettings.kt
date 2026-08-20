package dev.hypnosia.visual.cosmetic

import dev.hypnosia.config.HypnosiaClientSettings
import dev.hypnosia.visual.cosmetic.effects.ChinaHatEffect
import dev.hypnosia.visual.cosmetic.effects.NimbusEffect

object CosmeticSettings {
    private const val ENABLED_KEY = "visuals.cosmetics.enabled"
    private const val CHINA_HAT_ENABLED_KEY = "visuals.cosmetics.china_hat.enabled"
    private const val CHINA_HAT_COLOR1_KEY = "visuals.cosmetics.china_hat.color1"
    private const val CHINA_HAT_COLOR2_KEY = "visuals.cosmetics.china_hat.color2"
    private const val CHINA_HAT_COLOR3_KEY = "visuals.cosmetics.china_hat.color3"
    private const val CHINA_HAT_COLOR4_KEY = "visuals.cosmetics.china_hat.color4"
    private const val CHINA_HAT_ALPHA_KEY = "visuals.cosmetics.china_hat.alpha"
    private const val CHINA_HAT_COLOR_COUNT_KEY = "visuals.cosmetics.china_hat.color_count"
    private const val CHINA_HAT_Y_KEY = "visuals.cosmetics.china_hat.y"
    private const val CHINA_HAT_WIDTH_KEY = "visuals.cosmetics.china_hat.width"
    private const val CHINA_HAT_HEIGHT_KEY = "visuals.cosmetics.china_hat.height"
    private const val CHINA_HAT_GRADIENT_MODE_KEY = "visuals.cosmetics.china_hat.gradient_mode"
    private const val CHINA_HAT_ANIM_SPEED_KEY = "visuals.cosmetics.china_hat.anim_speed"

    private const val NIMBUS_ENABLED_KEY = "visuals.cosmetics.nimbus.enabled"
    private const val NIMBUS_COLOR1_KEY = "visuals.cosmetics.nimbus.color1"
    private const val NIMBUS_COLOR2_KEY = "visuals.cosmetics.nimbus.color2"
    private const val NIMBUS_COLOR3_KEY = "visuals.cosmetics.nimbus.color3"
    private const val NIMBUS_COLOR4_KEY = "visuals.cosmetics.nimbus.color4"
    private const val NIMBUS_ALPHA_KEY = "visuals.cosmetics.nimbus.alpha"
    private const val NIMBUS_COLOR_COUNT_KEY = "visuals.cosmetics.nimbus.color_count"
    private const val NIMBUS_Y_KEY = "visuals.cosmetics.nimbus.y"
    private const val NIMBUS_RADIUS_KEY = "visuals.cosmetics.nimbus.radius"
    private const val NIMBUS_TUBE_RADIUS_KEY = "visuals.cosmetics.nimbus.tube_radius"
    private const val NIMBUS_TILT_KEY = "visuals.cosmetics.nimbus.tilt"
    private const val NIMBUS_GRADIENT_MODE_KEY = "visuals.cosmetics.nimbus.gradient_mode"
    private const val NIMBUS_ANIM_SPEED_KEY = "visuals.cosmetics.nimbus.anim_speed"

    enum class GradientMode {
        STATIC, FLUID, CHROMA
    }

    private var loaded = false
    private var cachedEnabled = true
    private var cachedChinaHatEnabled = true
    private var cachedChinaHatColor1 = 0xFF4444
    private var cachedChinaHatColor2 = 0xFFFFFF
    private var cachedChinaHatColor3 = 0xFFFFFF
    private var cachedChinaHatColor4 = 0xFFFFFF
    private var cachedChinaHatAlpha = 180
    private var cachedChinaHatColorCount = 1
    private var cachedChinaHatY = -0.45f
    private var cachedChinaHatWidth = 0.5f
    private var cachedChinaHatHeight = -0.25f
    private var cachedChinaHatGradientMode = GradientMode.STATIC
    private var cachedChinaHatAnimSpeed = 1.0f

    private var cachedNimbusEnabled = false
    private var cachedNimbusColor1 = 0xFF0000
    private var cachedNimbusColor2 = 0x00FF00
    private var cachedNimbusColor3 = 0x0000FF
    private var cachedNimbusColor4 = 0xFFFFFF
    private var cachedNimbusAlpha = 255
    private var cachedNimbusColorCount = 1
    private var cachedNimbusY = 0.7f
    private var cachedNimbusRadius = 0.5f
    private var cachedNimbusTubeRadius = 0.15f
    private var cachedNimbusTilt = 0f
    private var cachedNimbusGradientMode = GradientMode.STATIC
    private var cachedNimbusAnimSpeed = 1.0f

    fun enabled(): Boolean {
        ensureLoaded()
        return cachedEnabled
    }

    fun setEnabled(enabled: Boolean) {
        cachedEnabled = enabled
        loaded = true
        HypnosiaClientSettings.set(ENABLED_KEY, enabled.toString())
        CosmeticRenderModule.enabled = enabled
    }

    fun chinaHatEnabled(): Boolean {
        ensureLoaded()
        return cachedChinaHatEnabled
    }

    fun setChinaHatEnabled(enabled: Boolean) {
        cachedChinaHatEnabled = enabled
        loaded = true
        HypnosiaClientSettings.set(CHINA_HAT_ENABLED_KEY, enabled.toString())
        val effect = CosmeticRenderModule.effects.find { it.id == "china_hat" } as? ChinaHatEffect
        effect?.enabled = enabled
    }

    fun chinaHatColor1(): Int {
        ensureLoaded()
        return cachedChinaHatColor1
    }

    fun setChinaHatColor1(color: Int) {
        cachedChinaHatColor1 = color
        loaded = true
        HypnosiaClientSettings.set(CHINA_HAT_COLOR1_KEY, String.format("%06X", color and 0xFFFFFF))
        val effect = CosmeticRenderModule.effects.find { it.id == "china_hat" } as? ChinaHatEffect
        effect?.color1 = color
    }

    fun chinaHatColor2(): Int {
        ensureLoaded()
        return cachedChinaHatColor2
    }

    fun setChinaHatColor2(color: Int) {
        cachedChinaHatColor2 = color
        loaded = true
        HypnosiaClientSettings.set(CHINA_HAT_COLOR2_KEY, String.format("%06X", color and 0xFFFFFF))
        val effect = CosmeticRenderModule.effects.find { it.id == "china_hat" } as? ChinaHatEffect
        effect?.color2 = color
    }

    fun chinaHatColor3(): Int {
        ensureLoaded()
        return cachedChinaHatColor3
    }

    fun setChinaHatColor3(color: Int) {
        cachedChinaHatColor3 = color
        loaded = true
        HypnosiaClientSettings.set(CHINA_HAT_COLOR3_KEY, String.format("%06X", color and 0xFFFFFF))
        val effect = CosmeticRenderModule.effects.find { it.id == "china_hat" } as? ChinaHatEffect
        effect?.color3 = color
    }

    fun chinaHatColor4(): Int {
        ensureLoaded()
        return cachedChinaHatColor4
    }

    fun setChinaHatColor4(color: Int) {
        cachedChinaHatColor4 = color
        loaded = true
        HypnosiaClientSettings.set(CHINA_HAT_COLOR4_KEY, String.format("%06X", color and 0xFFFFFF))
        val effect = CosmeticRenderModule.effects.find { it.id == "china_hat" } as? ChinaHatEffect
        effect?.color4 = color
    }

    fun chinaHatAlpha(): Int {
        ensureLoaded()
        return cachedChinaHatAlpha
    }

    fun setChinaHatAlpha(alpha: Int) {
        val v = alpha.coerceIn(0, 255)
        cachedChinaHatAlpha = v
        loaded = true
        HypnosiaClientSettings.set(CHINA_HAT_ALPHA_KEY, v.toString())
        val effect = CosmeticRenderModule.effects.find { it.id == "china_hat" } as? ChinaHatEffect
        effect?.alpha = v
    }

    fun chinaHatColorCount(): Int {
        ensureLoaded()
        return cachedChinaHatColorCount
    }

    fun setChinaHatColorCount(count: Int) {
        val v = count.coerceIn(1, 4)
        cachedChinaHatColorCount = v
        loaded = true
        HypnosiaClientSettings.set(CHINA_HAT_COLOR_COUNT_KEY, v.toString())
        val effect = CosmeticRenderModule.effects.find { it.id == "china_hat" } as? ChinaHatEffect
        effect?.colorCount = v
    }

    fun chinaHatY(): Float {
        ensureLoaded()
        return cachedChinaHatY
    }

    fun setChinaHatY(y: Float) {
        cachedChinaHatY = y
        loaded = true
        HypnosiaClientSettings.set(CHINA_HAT_Y_KEY, String.format("%.3f", y))
        val effect = CosmeticRenderModule.effects.find { it.id == "china_hat" } as? ChinaHatEffect
        effect?.yOffset = y
    }

    fun chinaHatWidth(): Float {
        ensureLoaded()
        return cachedChinaHatWidth
    }

    fun setChinaHatWidth(width: Float) {
        val v = width.coerceIn(0.05f, 2.0f)
        cachedChinaHatWidth = v
        loaded = true
        HypnosiaClientSettings.set(CHINA_HAT_WIDTH_KEY, String.format("%.3f", v))
        val effect = CosmeticRenderModule.effects.find { it.id == "china_hat" } as? ChinaHatEffect
        effect?.radius = v
    }

    fun chinaHatHeight(): Float {
        ensureLoaded()
        return cachedChinaHatHeight
    }

    fun setChinaHatHeight(height: Float) {
        val v = height.coerceIn(-2.0f, -0.01f)
        cachedChinaHatHeight = v
        loaded = true
        HypnosiaClientSettings.set(CHINA_HAT_HEIGHT_KEY, String.format("%.3f", v))
        val effect = CosmeticRenderModule.effects.find { it.id == "china_hat" } as? ChinaHatEffect
        effect?.coneHeight = v
    }

    fun chinaHatGradientMode(): GradientMode {
        ensureLoaded()
        return cachedChinaHatGradientMode
    }

    fun setChinaHatGradientMode(mode: GradientMode) {
        cachedChinaHatGradientMode = mode
        loaded = true
        HypnosiaClientSettings.set(CHINA_HAT_GRADIENT_MODE_KEY, mode.ordinal.toString())
        val effect = CosmeticRenderModule.effects.find { it.id == "china_hat" } as? ChinaHatEffect
        effect?.gradientMode = mode
    }

    fun chinaHatAnimSpeed(): Float {
        ensureLoaded()
        return cachedChinaHatAnimSpeed
    }

    fun setChinaHatAnimSpeed(speed: Float) {
        val v = speed.coerceIn(0.0f, 5.0f)
        cachedChinaHatAnimSpeed = v
        loaded = true
        HypnosiaClientSettings.set(CHINA_HAT_ANIM_SPEED_KEY, String.format("%.2f", v))
        val effect = CosmeticRenderModule.effects.find { it.id == "china_hat" } as? ChinaHatEffect
        effect?.animSpeed = v
    }

    fun nimbusEnabled(): Boolean {
        ensureLoaded()
        return cachedNimbusEnabled
    }

    fun setNimbusEnabled(enabled: Boolean) {
        cachedNimbusEnabled = enabled
        loaded = true
        HypnosiaClientSettings.set(NIMBUS_ENABLED_KEY, enabled.toString())
        val effect = CosmeticRenderModule.effects.find { it.id == "nimbus" } as? NimbusEffect
        effect?.enabled = enabled
    }

    fun nimbusColor1(): Int {
        ensureLoaded()
        return cachedNimbusColor1
    }

    fun setNimbusColor1(color: Int) {
        cachedNimbusColor1 = color
        loaded = true
        HypnosiaClientSettings.set(NIMBUS_COLOR1_KEY, String.format("%06X", color and 0xFFFFFF))
        val effect = CosmeticRenderModule.effects.find { it.id == "nimbus" } as? NimbusEffect
        effect?.color1 = color
    }

    fun nimbusColor2(): Int {
        ensureLoaded()
        return cachedNimbusColor2
    }

    fun setNimbusColor2(color: Int) {
        cachedNimbusColor2 = color
        loaded = true
        HypnosiaClientSettings.set(NIMBUS_COLOR2_KEY, String.format("%06X", color and 0xFFFFFF))
        val effect = CosmeticRenderModule.effects.find { it.id == "nimbus" } as? NimbusEffect
        effect?.color2 = color
    }

    fun nimbusColor3(): Int {
        ensureLoaded()
        return cachedNimbusColor3
    }

    fun setNimbusColor3(color: Int) {
        cachedNimbusColor3 = color
        loaded = true
        HypnosiaClientSettings.set(NIMBUS_COLOR3_KEY, String.format("%06X", color and 0xFFFFFF))
        val effect = CosmeticRenderModule.effects.find { it.id == "nimbus" } as? NimbusEffect
        effect?.color3 = color
    }

    fun nimbusColor4(): Int {
        ensureLoaded()
        return cachedNimbusColor4
    }

    fun setNimbusColor4(color: Int) {
        cachedNimbusColor4 = color
        loaded = true
        HypnosiaClientSettings.set(NIMBUS_COLOR4_KEY, String.format("%06X", color and 0xFFFFFF))
        val effect = CosmeticRenderModule.effects.find { it.id == "nimbus" } as? NimbusEffect
        effect?.color4 = color
    }

    fun nimbusAlpha(): Int {
        ensureLoaded()
        return cachedNimbusAlpha
    }

    fun setNimbusAlpha(alpha: Int) {
        val v = alpha.coerceIn(0, 255)
        cachedNimbusAlpha = v
        loaded = true
        HypnosiaClientSettings.set(NIMBUS_ALPHA_KEY, v.toString())
        val effect = CosmeticRenderModule.effects.find { it.id == "nimbus" } as? NimbusEffect
        effect?.alpha = v
    }

    fun nimbusColorCount(): Int {
        ensureLoaded()
        return cachedNimbusColorCount
    }

    fun setNimbusColorCount(count: Int) {
        val v = count.coerceIn(1, 4)
        cachedNimbusColorCount = v
        loaded = true
        HypnosiaClientSettings.set(NIMBUS_COLOR_COUNT_KEY, v.toString())
        val effect = CosmeticRenderModule.effects.find { it.id == "nimbus" } as? NimbusEffect
        effect?.colorCount = v
    }

    fun nimbusY(): Float {
        ensureLoaded()
        return cachedNimbusY
    }

    fun setNimbusY(y: Float) {
        cachedNimbusY = y
        loaded = true
        HypnosiaClientSettings.set(NIMBUS_Y_KEY, String.format("%.3f", y))
        val effect = CosmeticRenderModule.effects.find { it.id == "nimbus" } as? NimbusEffect
        effect?.yOffset = y
    }

    fun nimbusRadius(): Float {
        ensureLoaded()
        return cachedNimbusRadius
    }

    fun setNimbusRadius(radius: Float) {
        val v = radius.coerceIn(0.05f, 2.0f)
        cachedNimbusRadius = v
        loaded = true
        HypnosiaClientSettings.set(NIMBUS_RADIUS_KEY, String.format("%.3f", v))
        val effect = CosmeticRenderModule.effects.find { it.id == "nimbus" } as? NimbusEffect
        effect?.radius = v
    }

    fun nimbusTubeRadius(): Float {
        ensureLoaded()
        return cachedNimbusTubeRadius
    }

    fun setNimbusTubeRadius(tubeRadius: Float) {
        val v = tubeRadius.coerceIn(0.01f, 0.5f)
        cachedNimbusTubeRadius = v
        loaded = true
        HypnosiaClientSettings.set(NIMBUS_TUBE_RADIUS_KEY, String.format("%.3f", v))
        val effect = CosmeticRenderModule.effects.find { it.id == "nimbus" } as? NimbusEffect
        effect?.tubeRadius = v
    }

    fun nimbusTilt(): Float {
        ensureLoaded()
        return cachedNimbusTilt
    }

    fun setNimbusTilt(tilt: Float) {
        val v = tilt.coerceIn(-1.57f, 1.57f)
        cachedNimbusTilt = v
        loaded = true
        HypnosiaClientSettings.set(NIMBUS_TILT_KEY, String.format("%.3f", v))
        val effect = CosmeticRenderModule.effects.find { it.id == "nimbus" } as? NimbusEffect
        effect?.tilt = v
    }

    fun nimbusGradientMode(): GradientMode {
        ensureLoaded()
        return cachedNimbusGradientMode
    }

    fun setNimbusGradientMode(mode: GradientMode) {
        cachedNimbusGradientMode = mode
        loaded = true
        HypnosiaClientSettings.set(NIMBUS_GRADIENT_MODE_KEY, mode.ordinal.toString())
        val effect = CosmeticRenderModule.effects.find { it.id == "nimbus" } as? NimbusEffect
        effect?.gradientMode = mode
    }

    fun nimbusAnimSpeed(): Float {
        ensureLoaded()
        return cachedNimbusAnimSpeed
    }

    fun setNimbusAnimSpeed(speed: Float) {
        val v = speed.coerceIn(0.0f, 5.0f)
        cachedNimbusAnimSpeed = v
        loaded = true
        HypnosiaClientSettings.set(NIMBUS_ANIM_SPEED_KEY, String.format("%.2f", v))
        val effect = CosmeticRenderModule.effects.find { it.id == "nimbus" } as? NimbusEffect
        effect?.animSpeed = v
    }

    fun ensureLoaded() {
        if (loaded) return
        cachedEnabled = HypnosiaClientSettings.string(ENABLED_KEY, "true").toBooleanStrictOrNull() ?: true
        cachedChinaHatEnabled = HypnosiaClientSettings.string(CHINA_HAT_ENABLED_KEY, "true").toBooleanStrictOrNull() ?: true
        cachedChinaHatColor1 = HypnosiaClientSettings.string(CHINA_HAT_COLOR1_KEY, "FF4444").toIntOrNull(16) ?: 0xFF4444
        cachedChinaHatColor2 = HypnosiaClientSettings.string(CHINA_HAT_COLOR2_KEY, "FFFFFF").toIntOrNull(16) ?: 0xFFFFFF
        cachedChinaHatColor3 = HypnosiaClientSettings.string(CHINA_HAT_COLOR3_KEY, "FFFFFF").toIntOrNull(16) ?: 0xFFFFFF
        cachedChinaHatColor4 = HypnosiaClientSettings.string(CHINA_HAT_COLOR4_KEY, "FFFFFF").toIntOrNull(16) ?: 0xFFFFFF
        cachedChinaHatAlpha = HypnosiaClientSettings.string(CHINA_HAT_ALPHA_KEY, "180").toIntOrNull() ?: 180
        cachedChinaHatColorCount = HypnosiaClientSettings.string(CHINA_HAT_COLOR_COUNT_KEY, "1").toIntOrNull() ?: 1
        cachedChinaHatY = HypnosiaClientSettings.string(CHINA_HAT_Y_KEY, "-0.450").toFloatOrNull() ?: -0.45f
        cachedChinaHatWidth = HypnosiaClientSettings.string(CHINA_HAT_WIDTH_KEY, "0.500").toFloatOrNull() ?: 0.5f
        cachedChinaHatHeight = HypnosiaClientSettings.string(CHINA_HAT_HEIGHT_KEY, "-0.250").toFloatOrNull() ?: -0.25f
        cachedChinaHatGradientMode = GradientMode.entries.getOrNull(
            HypnosiaClientSettings.string(CHINA_HAT_GRADIENT_MODE_KEY, "0").toIntOrNull() ?: 0
        ) ?: GradientMode.STATIC
        cachedChinaHatAnimSpeed = HypnosiaClientSettings.string(CHINA_HAT_ANIM_SPEED_KEY, "1.00").toFloatOrNull() ?: 1.0f
        cachedNimbusEnabled = HypnosiaClientSettings.string(NIMBUS_ENABLED_KEY, "false").toBooleanStrictOrNull() ?: false
        cachedNimbusColor1 = HypnosiaClientSettings.string(NIMBUS_COLOR1_KEY, "FF0000").toIntOrNull(16) ?: 0xFF0000
        cachedNimbusColor2 = HypnosiaClientSettings.string(NIMBUS_COLOR2_KEY, "00FF00").toIntOrNull(16) ?: 0x00FF00
        cachedNimbusColor3 = HypnosiaClientSettings.string(NIMBUS_COLOR3_KEY, "0000FF").toIntOrNull(16) ?: 0x0000FF
        cachedNimbusColor4 = HypnosiaClientSettings.string(NIMBUS_COLOR4_KEY, "FFFFFF").toIntOrNull(16) ?: 0xFFFFFF
        cachedNimbusAlpha = HypnosiaClientSettings.string(NIMBUS_ALPHA_KEY, "255").toIntOrNull() ?: 255
        cachedNimbusColorCount = HypnosiaClientSettings.string(NIMBUS_COLOR_COUNT_KEY, "1").toIntOrNull() ?: 1
        cachedNimbusY = HypnosiaClientSettings.string(NIMBUS_Y_KEY, "0.700").toFloatOrNull() ?: 0.7f
        cachedNimbusRadius = HypnosiaClientSettings.string(NIMBUS_RADIUS_KEY, "0.500").toFloatOrNull() ?: 0.5f
        cachedNimbusTubeRadius = HypnosiaClientSettings.string(NIMBUS_TUBE_RADIUS_KEY, "0.150").toFloatOrNull() ?: 0.15f
        cachedNimbusTilt = HypnosiaClientSettings.string(NIMBUS_TILT_KEY, "0.000").toFloatOrNull() ?: 0f
        cachedNimbusGradientMode = GradientMode.entries.getOrNull(
            HypnosiaClientSettings.string(NIMBUS_GRADIENT_MODE_KEY, "0").toIntOrNull() ?: 0
        ) ?: GradientMode.STATIC
        cachedNimbusAnimSpeed = HypnosiaClientSettings.string(NIMBUS_ANIM_SPEED_KEY, "1.00").toFloatOrNull() ?: 1.0f
        loaded = true

        CosmeticRenderModule.enabled = cachedEnabled
        val hat = CosmeticRenderModule.effects.find { it.id == "china_hat" } as? ChinaHatEffect
        hat?.enabled = cachedChinaHatEnabled
        hat?.color1 = cachedChinaHatColor1
        hat?.color2 = cachedChinaHatColor2
        hat?.color3 = cachedChinaHatColor3
        hat?.color4 = cachedChinaHatColor4
        hat?.colorCount = cachedChinaHatColorCount
        hat?.alpha = cachedChinaHatAlpha
        hat?.yOffset = cachedChinaHatY
        hat?.radius = cachedChinaHatWidth
        hat?.coneHeight = cachedChinaHatHeight
        hat?.gradientMode = cachedChinaHatGradientMode
        hat?.animSpeed = cachedChinaHatAnimSpeed

        val nimbus = CosmeticRenderModule.effects.find { it.id == "nimbus" } as? NimbusEffect
        nimbus?.enabled = cachedNimbusEnabled
        nimbus?.color1 = cachedNimbusColor1
        nimbus?.color2 = cachedNimbusColor2
        nimbus?.color3 = cachedNimbusColor3
        nimbus?.color4 = cachedNimbusColor4
        nimbus?.colorCount = cachedNimbusColorCount
        nimbus?.alpha = cachedNimbusAlpha
        nimbus?.yOffset = cachedNimbusY
        nimbus?.radius = cachedNimbusRadius
        nimbus?.tubeRadius = cachedNimbusTubeRadius
        nimbus?.tilt = cachedNimbusTilt
        nimbus?.gradientMode = cachedNimbusGradientMode
        nimbus?.animSpeed = cachedNimbusAnimSpeed
    }
}
