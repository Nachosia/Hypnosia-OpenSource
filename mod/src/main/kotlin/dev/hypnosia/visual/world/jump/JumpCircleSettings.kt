package dev.hypnosia.visual.world.jump

import dev.hypnosia.config.HypnosiaClientSettings
import dev.hypnosia.visual.cosmetic.CosmeticSettings.GradientMode
import dev.hypnosia.visual.world.particles.WorldParticleTexture
import dev.hypnosia.visual.world.particles.hit.HitGravityMode

object JumpCircleSettings {
    private const val ENABLED_KEY = "visuals.jump_circles.enabled"
    private const val TEXTURE_KEY = "visuals.jump_circles.texture"
    private const val SIZE_KEY = "visuals.jump_circles.size"
    private const val LIFETIME_KEY = "visuals.jump_circles.lifetime"
    private const val ALPHA_KEY = "visuals.jump_circles.alpha"
    private const val ROTATION_KEY = "visuals.jump_circles.rotation_speed"
    private const val COLOR1_KEY = "visuals.jump_circles.color1"
    private const val COLOR2_KEY = "visuals.jump_circles.color2"
    private const val COLOR3_KEY = "visuals.jump_circles.color3"
    private const val COLOR4_KEY = "visuals.jump_circles.color4"
    private const val COLOR_COUNT_KEY = "visuals.jump_circles.color_count"
    private const val GRADIENT_MODE_KEY = "visuals.jump_circles.gradient_mode"
    private const val ANIM_SPEED_KEY = "visuals.jump_circles.anim_speed"
    private const val FADE_MODE_KEY = "visuals.jump_circles.fade_mode"
    private const val ONLY_F5_KEY = "visuals.jump_circles.only_f5"

    private const val P_ENABLED_KEY = "visuals.jump_circles.particles_enabled"
    private const val P_ONLY_F5_KEY = "visuals.jump_circles.p_only_f5"
    private const val P_COUNT_KEY = "visuals.jump_circles.p_count"
    private const val P_FORCE_KEY = "visuals.jump_circles.p_force"
    private const val P_LIFETIME_KEY = "visuals.jump_circles.p_lifetime"
    private const val P_SIZE_KEY = "visuals.jump_circles.p_size"
    private const val P_GRAVITY_KEY = "visuals.jump_circles.p_gravity"
    private const val P_COLOR1_KEY = "visuals.jump_circles.p_color1"
    private const val P_COLOR2_KEY = "visuals.jump_circles.p_color2"
    private const val P_COLOR3_KEY = "visuals.jump_circles.p_color3"
    private const val P_COLOR4_KEY = "visuals.jump_circles.p_color4"
    private const val P_COLOR_COUNT_KEY = "visuals.jump_circles.p_color_count"
    private const val P_GRADIENT_MODE_KEY = "visuals.jump_circles.p_gradient_mode"
    private const val P_ANIM_SPEED_KEY = "visuals.jump_circles.p_anim_speed"
    private const val P_TEXTURES_KEY = "visuals.jump_circles.p_textures"

    private var loaded = false

    private var cachedEnabled = false
    private var cachedTexture = JumpCircleTexture.HYP
    private var cachedSize = 3.0f
    private var cachedLifetime = 30
    private var cachedAlpha = 200
    private var cachedRotationSpeed = 1.0f
    private var cachedColor1 = 0xFFFFFF
    private var cachedColor2 = 0xFF2F86.toInt()
    private var cachedColor3 = 0x2FB8FF
    private var cachedColor4 = 0xFFFFFF
    private var cachedColorCount = 1
    private var cachedGradientMode = GradientMode.STATIC
    private var cachedAnimSpeed = 1.0f
    private var cachedFadeMode = JumpFadeMode.FADE
    private var cachedOnlyF5 = false

    private var cachedParticlesEnabled = true
    private var cachedPOnlyF5 = false
    private var cachedPCount = 12
    private var cachedPForce = 0.4f
    private var cachedPLifetime = 20
    private var cachedPSize = 0.15f
    private var cachedPGravity = HitGravityMode.EXPLODE
    private var cachedPColor1 = 0xFFFFFF
    private var cachedPColor2 = 0xFF2F86.toInt()
    private var cachedPColor3 = 0x2FB8FF
    private var cachedPColor4 = 0xFFFFFF
    private var cachedPColorCount = 1
    private var cachedPGradientMode = GradientMode.STATIC
    private var cachedPAnimSpeed = 1.0f
    private var cachedPActiveTextures = mutableSetOf(WorldParticleTexture.CROSS)

    fun enabled(): Boolean { ensureLoaded(); return cachedEnabled }
    fun setEnabled(v: Boolean) { cachedEnabled = v; loaded = true; HypnosiaClientSettings.set(ENABLED_KEY, v.toString()) }

    fun texture(): JumpCircleTexture { ensureLoaded(); return cachedTexture }
    fun setTexture(v: JumpCircleTexture) { cachedTexture = v; loaded = true; HypnosiaClientSettings.set(TEXTURE_KEY, v.ordinal.toString()) }

    fun size(): Float { ensureLoaded(); return cachedSize }
    fun setSize(v: Float) { cachedSize = v.coerceIn(1.0f, 6.0f); loaded = true; HypnosiaClientSettings.set(SIZE_KEY, String.format("%.2f", cachedSize)) }

    fun lifetime(): Int { ensureLoaded(); return cachedLifetime }
    fun setLifetime(v: Int) { cachedLifetime = v.coerceIn(10, 60); loaded = true; HypnosiaClientSettings.set(LIFETIME_KEY, cachedLifetime.toString()) }

    fun alpha(): Int { ensureLoaded(); return cachedAlpha }
    fun setAlpha(v: Int) { cachedAlpha = v.coerceIn(50, 255); loaded = true; HypnosiaClientSettings.set(ALPHA_KEY, cachedAlpha.toString()) }

    fun rotationSpeed(): Float { ensureLoaded(); return cachedRotationSpeed }
    fun setRotationSpeed(v: Float) { cachedRotationSpeed = v.coerceIn(0.0f, 5.0f); loaded = true; HypnosiaClientSettings.set(ROTATION_KEY, String.format("%.2f", cachedRotationSpeed)) }

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

    fun fadeMode(): JumpFadeMode { ensureLoaded(); return cachedFadeMode }
    fun setFadeMode(v: JumpFadeMode) { cachedFadeMode = v; loaded = true; HypnosiaClientSettings.set(FADE_MODE_KEY, v.ordinal.toString()) }

    fun onlyF5(): Boolean { ensureLoaded(); return cachedOnlyF5 }
    fun setOnlyF5(v: Boolean) { cachedOnlyF5 = v; loaded = true; HypnosiaClientSettings.set(ONLY_F5_KEY, v.toString()) }

    // Particle settings
    fun particlesEnabled(): Boolean { ensureLoaded(); return cachedParticlesEnabled }
    fun setParticlesEnabled(v: Boolean) { cachedParticlesEnabled = v; loaded = true; HypnosiaClientSettings.set(P_ENABLED_KEY, v.toString()) }

    fun pOnlyF5(): Boolean { ensureLoaded(); return cachedPOnlyF5 }
    fun setPOnlyF5(v: Boolean) { cachedPOnlyF5 = v; loaded = true; HypnosiaClientSettings.set(P_ONLY_F5_KEY, v.toString()) }

    fun pCount(): Int { ensureLoaded(); return cachedPCount }
    fun setPCount(v: Int) { cachedPCount = v.coerceIn(1, 30); loaded = true; HypnosiaClientSettings.set(P_COUNT_KEY, cachedPCount.toString()) }

    fun pForce(): Float { ensureLoaded(); return cachedPForce }
    fun setPForce(v: Float) { cachedPForce = v.coerceIn(0.1f, 1.0f); loaded = true; HypnosiaClientSettings.set(P_FORCE_KEY, String.format("%.2f", cachedPForce)) }

    fun pLifetime(): Int { ensureLoaded(); return cachedPLifetime }
    fun setPLifetime(v: Int) { cachedPLifetime = v.coerceIn(5, 60); loaded = true; HypnosiaClientSettings.set(P_LIFETIME_KEY, cachedPLifetime.toString()) }

    fun pSize(): Float { ensureLoaded(); return cachedPSize }
    fun setPSize(v: Float) { cachedPSize = v.coerceIn(0.05f, 1.0f); loaded = true; HypnosiaClientSettings.set(P_SIZE_KEY, String.format("%.3f", cachedPSize)) }

    fun pGravity(): HitGravityMode { ensureLoaded(); return cachedPGravity }
    fun setPGravity(v: HitGravityMode) { cachedPGravity = v; loaded = true; HypnosiaClientSettings.set(P_GRAVITY_KEY, v.ordinal.toString()) }

    fun pColor1(): Int { ensureLoaded(); return cachedPColor1 }
    fun pColor2(): Int { ensureLoaded(); return cachedPColor2 }
    fun pColor3(): Int { ensureLoaded(); return cachedPColor3 }
    fun pColor4(): Int { ensureLoaded(); return cachedPColor4 }
    fun setPColor1(c: Int) { cachedPColor1 = c and 0xFFFFFF; loaded = true; HypnosiaClientSettings.set(P_COLOR1_KEY, String.format("%06X", cachedPColor1)) }
    fun setPColor2(c: Int) { cachedPColor2 = c and 0xFFFFFF; loaded = true; HypnosiaClientSettings.set(P_COLOR2_KEY, String.format("%06X", cachedPColor2)) }
    fun setPColor3(c: Int) { cachedPColor3 = c and 0xFFFFFF; loaded = true; HypnosiaClientSettings.set(P_COLOR3_KEY, String.format("%06X", cachedPColor3)) }
    fun setPColor4(c: Int) { cachedPColor4 = c and 0xFFFFFF; loaded = true; HypnosiaClientSettings.set(P_COLOR4_KEY, String.format("%06X", cachedPColor4)) }

    fun pColorCount(): Int { ensureLoaded(); return cachedPColorCount }
    fun setPColorCount(v: Int) { cachedPColorCount = v.coerceIn(1, 4); loaded = true; HypnosiaClientSettings.set(P_COLOR_COUNT_KEY, cachedPColorCount.toString()) }

    fun pGradientMode(): GradientMode { ensureLoaded(); return cachedPGradientMode }
    fun setPGradientMode(v: GradientMode) { cachedPGradientMode = v; loaded = true; HypnosiaClientSettings.set(P_GRADIENT_MODE_KEY, v.ordinal.toString()) }

    fun pAnimSpeed(): Float { ensureLoaded(); return cachedPAnimSpeed }
    fun setPAnimSpeed(v: Float) { cachedPAnimSpeed = v.coerceIn(0.0f, 5.0f); loaded = true; HypnosiaClientSettings.set(P_ANIM_SPEED_KEY, String.format("%.2f", cachedPAnimSpeed)) }

    fun pActiveTextures(): Set<WorldParticleTexture> { ensureLoaded(); return cachedPActiveTextures }

    fun isPTextureActive(tex: WorldParticleTexture): Boolean { ensureLoaded(); return tex in cachedPActiveTextures }

    fun togglePTexture(tex: WorldParticleTexture) {
        ensureLoaded()
        if (tex in cachedPActiveTextures) {
            if (cachedPActiveTextures.size > 1) cachedPActiveTextures.remove(tex)
        } else {
            cachedPActiveTextures.add(tex)
        }
        loaded = true
        HypnosiaClientSettings.set(P_TEXTURES_KEY, cachedPActiveTextures.joinToString(",") { it.ordinal.toString() })
    }

    fun reload() { loaded = false }

    private fun ensureLoaded() {
        if (loaded) return
        cachedEnabled = HypnosiaClientSettings.string(ENABLED_KEY, "false").toBooleanStrictOrNull() ?: false
        cachedTexture = JumpCircleTexture.byOrdinal(HypnosiaClientSettings.string(TEXTURE_KEY, "0").toIntOrNull() ?: 0)
        cachedSize = HypnosiaClientSettings.string(SIZE_KEY, "3.00").toFloatOrNull() ?: 3.0f
        cachedLifetime = HypnosiaClientSettings.string(LIFETIME_KEY, "30").toIntOrNull() ?: 30
        cachedAlpha = HypnosiaClientSettings.string(ALPHA_KEY, "200").toIntOrNull() ?: 200
        cachedRotationSpeed = HypnosiaClientSettings.string(ROTATION_KEY, "1.00").toFloatOrNull() ?: 1.0f
        cachedColor1 = HypnosiaClientSettings.string(COLOR1_KEY, "FFFFFF").toIntOrNull(16) ?: 0xFFFFFF
        cachedColor2 = HypnosiaClientSettings.string(COLOR2_KEY, "FF2F86").toIntOrNull(16) ?: 0xFF2F86.toInt()
        cachedColor3 = HypnosiaClientSettings.string(COLOR3_KEY, "2FB8FF").toIntOrNull(16) ?: 0x2FB8FF
        cachedColor4 = HypnosiaClientSettings.string(COLOR4_KEY, "FFFFFF").toIntOrNull(16) ?: 0xFFFFFF
        cachedColorCount = HypnosiaClientSettings.string(COLOR_COUNT_KEY, "1").toIntOrNull() ?: 1
        cachedGradientMode = GradientMode.entries.getOrNull(
            HypnosiaClientSettings.string(GRADIENT_MODE_KEY, "0").toIntOrNull() ?: 0
        ) ?: GradientMode.STATIC
        cachedAnimSpeed = HypnosiaClientSettings.string(ANIM_SPEED_KEY, "1.00").toFloatOrNull() ?: 1.0f
        cachedFadeMode = JumpFadeMode.byOrdinal(HypnosiaClientSettings.string(FADE_MODE_KEY, "0").toIntOrNull() ?: 0)
        cachedOnlyF5 = HypnosiaClientSettings.string(ONLY_F5_KEY, "false").toBooleanStrictOrNull() ?: false

        cachedParticlesEnabled = HypnosiaClientSettings.string(P_ENABLED_KEY, "true").toBooleanStrictOrNull() ?: true
        cachedPOnlyF5 = HypnosiaClientSettings.string(P_ONLY_F5_KEY, "false").toBooleanStrictOrNull() ?: false
        cachedPCount = HypnosiaClientSettings.string(P_COUNT_KEY, "12").toIntOrNull() ?: 12
        cachedPForce = HypnosiaClientSettings.string(P_FORCE_KEY, "0.40").toFloatOrNull() ?: 0.4f
        cachedPLifetime = HypnosiaClientSettings.string(P_LIFETIME_KEY, "20").toIntOrNull() ?: 20
        cachedPSize = HypnosiaClientSettings.string(P_SIZE_KEY, "0.150").toFloatOrNull() ?: 0.15f
        cachedPGravity = HitGravityMode.byOrdinal(HypnosiaClientSettings.string(P_GRAVITY_KEY, "0").toIntOrNull() ?: 0)
        cachedPColor1 = HypnosiaClientSettings.string(P_COLOR1_KEY, "FFFFFF").toIntOrNull(16) ?: 0xFFFFFF
        cachedPColor2 = HypnosiaClientSettings.string(P_COLOR2_KEY, "FF2F86").toIntOrNull(16) ?: 0xFF2F86.toInt()
        cachedPColor3 = HypnosiaClientSettings.string(P_COLOR3_KEY, "2FB8FF").toIntOrNull(16) ?: 0x2FB8FF
        cachedPColor4 = HypnosiaClientSettings.string(P_COLOR4_KEY, "FFFFFF").toIntOrNull(16) ?: 0xFFFFFF
        cachedPColorCount = HypnosiaClientSettings.string(P_COLOR_COUNT_KEY, "1").toIntOrNull() ?: 1
        cachedPGradientMode = GradientMode.entries.getOrNull(
            HypnosiaClientSettings.string(P_GRADIENT_MODE_KEY, "0").toIntOrNull() ?: 0
        ) ?: GradientMode.STATIC
        cachedPAnimSpeed = HypnosiaClientSettings.string(P_ANIM_SPEED_KEY, "1.00").toFloatOrNull() ?: 1.0f
        val texturesRaw = HypnosiaClientSettings.string(P_TEXTURES_KEY, "")
        cachedPActiveTextures = if (texturesRaw.isBlank()) {
            mutableSetOf(WorldParticleTexture.CROSS)
        } else {
            texturesRaw.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .map { WorldParticleTexture.byOrdinal(it) }
                .toMutableSet()
                .ifEmpty { mutableSetOf(WorldParticleTexture.CROSS) }
        }
        loaded = true
    }
}
