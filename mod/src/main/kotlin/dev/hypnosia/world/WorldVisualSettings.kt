package dev.hypnosia.world

import dev.hypnosia.config.HypnosiaClientSettings
import net.minecraft.client.MinecraftClient

object WorldVisualSettings {
    private const val FULLBRIGHT_KEY = "module.world.fullbright.enabled"
    private const val LEGACY_FULLBRIGHT_KEY = "module.visuals.fullbright.enabled"
    private const val CUSTOM_FOG_KEY = "module.world.custom_fog.enabled"
    private const val FOG_DISTANCE_KEY = "world.custom_fog.distance"
    private const val FOG_STRENGTH_KEY = "world.custom_fog.strength"
    private const val FOG_SOFTNESS_KEY = "world.custom_fog.softness"
    private const val FOG_COLOR_KEY = "world.custom_fog.color"

    private const val FULLBRIGHT_GAMMA = 16.0
    private const val MIN_FOG_DISTANCE = 64.0f
    private const val MAX_FOG_DISTANCE = 512.0f
    private const val DEFAULT_FOG_DISTANCE = 256.0f
    private const val DEFAULT_FOG_STRENGTH = 0.55f
    private const val DEFAULT_FOG_SOFTNESS = 0.65f
    private const val DEFAULT_FOG_COLOR = 0xFFC9D7E8.toInt()

    private var loaded = false
    private var cachedFullbrightEnabled = true
    private var cachedCustomFogEnabled = false
    private var cachedFogDistance = DEFAULT_FOG_DISTANCE
    private var cachedFogStrength = DEFAULT_FOG_STRENGTH
    private var cachedFogSoftness = DEFAULT_FOG_SOFTNESS
    private var cachedFogColor = DEFAULT_FOG_COLOR

    fun fullbrightGamma(): Double = FULLBRIGHT_GAMMA

    fun shouldOverrideGamma(option: Any?): Boolean {
        val client = MinecraftClient.getInstance() ?: return false
        return fullbrightEnabled() && option === client.options.gamma
    }

    fun fullbrightEnabled(): Boolean {
        ensureLoaded()
        return cachedFullbrightEnabled
    }

    fun setFullbrightEnabled(enabled: Boolean) {
        cachedFullbrightEnabled = enabled
        loaded = true
        HypnosiaClientSettings.set(FULLBRIGHT_KEY, enabled.toString())
    }

    fun customFogEnabled(): Boolean {
        ensureLoaded()
        return cachedCustomFogEnabled
    }

    fun setCustomFogEnabled(enabled: Boolean) {
        cachedCustomFogEnabled = enabled
        loaded = true
        HypnosiaClientSettings.set(CUSTOM_FOG_KEY, enabled.toString())
    }

    fun fogDistance(): Float {
        ensureLoaded()
        return cachedFogDistance
    }

    fun fogStrength(): Float {
        ensureLoaded()
        return cachedFogStrength
    }

    fun fogSoftness(): Float {
        ensureLoaded()
        return cachedFogSoftness
    }

    fun fogColor(): Int {
        ensureLoaded()
        return cachedFogColor
    }

    fun fogDistanceSlider(): Float =
        ((fogDistance() - MIN_FOG_DISTANCE) / (MAX_FOG_DISTANCE - MIN_FOG_DISTANCE)).coerceIn(0.0f, 1.0f)

    fun fogStrengthSlider(): Float = fogStrength()

    fun fogSoftnessSlider(): Float = fogSoftness()

    fun setFogDistanceFromSlider(value: Float) {
        val distance = MIN_FOG_DISTANCE + (MAX_FOG_DISTANCE - MIN_FOG_DISTANCE) * value.coerceIn(0.0f, 1.0f)
        cachedFogDistance = distance.coerceIn(MIN_FOG_DISTANCE, MAX_FOG_DISTANCE)
        loaded = true
        HypnosiaClientSettings.set(FOG_DISTANCE_KEY, distance.toInt().toString())
    }

    fun setFogStrengthFromSlider(value: Float) {
        cachedFogStrength = value.coerceIn(0.0f, 1.0f)
        loaded = true
        HypnosiaClientSettings.set(FOG_STRENGTH_KEY, cachedFogStrength.toString())
    }

    fun setFogSoftnessFromSlider(value: Float) {
        cachedFogSoftness = value.coerceIn(0.0f, 1.0f)
        loaded = true
        HypnosiaClientSettings.set(FOG_SOFTNESS_KEY, cachedFogSoftness.toString())
    }

    fun setFogColor(color: Int) {
        cachedFogColor = color
        loaded = true
        val alpha = (color ushr 24) and 0xFF
        HypnosiaClientSettings.set(
            FOG_COLOR_KEY,
            if (alpha == 0xFF) colorHex(color) else "#%08X".format(color),
        )
    }

    fun colorHex(color: Int): String = "#%06X".format(color and 0x00FFFFFF)

    fun fogRed(): Float = ((fogColor() shr 16) and 0xFF) / 255.0f

    fun fogGreen(): Float = ((fogColor() shr 8) and 0xFF) / 255.0f

    fun fogBlue(): Float = (fogColor() and 0xFF) / 255.0f

    fun fogColorBlend(): Float = (0.15f + fogStrength() * 0.65f).coerceIn(0.0f, 0.85f)

    fun reload() {
        loaded = false
        ensureLoaded()
    }

    fun tick(client: MinecraftClient) {
        if (!fullbrightEnabled()) restoreGamma(client)
    }

    fun restoreGamma(client: MinecraftClient) {
        // Gamma is now overridden through SimpleOptionMixin instead of mutating options.
        client.options.gamma
    }

    private fun parseColor(value: String): Int? {
        val normalized = value.trim().removePrefix("#")
        if (normalized.length != 6 && normalized.length != 8) return null
        return normalized.toLongOrNull(16)?.let {
            if (normalized.length == 6) (0xFF000000 or it).toInt() else it.toInt()
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        cachedFullbrightEnabled = HypnosiaClientSettings.boolean(
            FULLBRIGHT_KEY,
            HypnosiaClientSettings.boolean(LEGACY_FULLBRIGHT_KEY, true),
        )
        cachedCustomFogEnabled = HypnosiaClientSettings.boolean(CUSTOM_FOG_KEY, false)
        cachedFogDistance = HypnosiaClientSettings.string(FOG_DISTANCE_KEY, DEFAULT_FOG_DISTANCE.toInt().toString())
            .toFloatOrNull()
            ?.coerceIn(MIN_FOG_DISTANCE, MAX_FOG_DISTANCE)
            ?: DEFAULT_FOG_DISTANCE
        cachedFogStrength = HypnosiaClientSettings.string(FOG_STRENGTH_KEY, DEFAULT_FOG_STRENGTH.toString())
            .toFloatOrNull()
            ?.coerceIn(0.0f, 1.0f)
            ?: DEFAULT_FOG_STRENGTH
        cachedFogSoftness = HypnosiaClientSettings.string(FOG_SOFTNESS_KEY, DEFAULT_FOG_SOFTNESS.toString())
            .toFloatOrNull()
            ?.coerceIn(0.0f, 1.0f)
            ?: DEFAULT_FOG_SOFTNESS
        cachedFogColor = parseColor(HypnosiaClientSettings.string(FOG_COLOR_KEY, colorHex(DEFAULT_FOG_COLOR))) ?: DEFAULT_FOG_COLOR
        loaded = true
    }
}
