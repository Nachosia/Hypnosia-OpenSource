package dev.hypnosia.visual.world.particles.hit

import dev.hypnosia.config.HypnosiaClientSettings
import dev.hypnosia.visual.cosmetic.CosmeticSettings.GradientMode
import dev.hypnosia.visual.world.particles.WorldParticleTexture

object HitParticleSettings {
    private const val ENABLED_KEY = "visuals.hit_particles.enabled"
    private const val TRIGGER_KEY = "visuals.hit_particles.trigger"
    private const val GRAVITY_KEY = "visuals.hit_particles.gravity"
    private const val COUNT_KEY = "visuals.hit_particles.count"
    private const val FORCE_KEY = "visuals.hit_particles.force"
    private const val LIFETIME_KEY = "visuals.hit_particles.lifetime"
    private const val TEXTURE_KEY = "visuals.hit_particles.texture"
    private const val TEXTURES_KEY = "visuals.hit_particles.textures"
    private const val SIZE_KEY = "visuals.hit_particles.size"
    private const val COLOR1_KEY = "visuals.hit_particles.color1"
    private const val COLOR2_KEY = "visuals.hit_particles.color2"
    private const val COLOR3_KEY = "visuals.hit_particles.color3"
    private const val COLOR4_KEY = "visuals.hit_particles.color4"
    private const val COLOR_COUNT_KEY = "visuals.hit_particles.color_count"
    private const val GRADIENT_MODE_KEY = "visuals.hit_particles.gradient_mode"
    private const val ANIM_SPEED_KEY = "visuals.hit_particles.anim_speed"

    private var loaded = false
    private var cachedEnabled = false
    private var cachedTrigger = HitTriggerMode.HIT_AND_CRIT
    private var cachedGravity = HitGravityMode.EXPLODE
    private var cachedCount = 8
    private var cachedForce = 0.4f
    private var cachedLifetime = 25
    private var cachedTexture = WorldParticleTexture.CROSS
    private var cachedActiveTextures = mutableSetOf(WorldParticleTexture.CROSS)
    private var cachedSize = 0.15f
    private var cachedColor1 = 0xFFFFFF
    private var cachedColor2 = 0xFF2F86.toInt()
    private var cachedColor3 = 0x2FB8FF
    private var cachedColor4 = 0xFFFFFF
    private var cachedColorCount = 1
    private var cachedGradientMode = GradientMode.STATIC
    private var cachedAnimSpeed = 1.0f

    fun enabled(): Boolean { ensureLoaded(); return cachedEnabled }
    fun setEnabled(v: Boolean) { cachedEnabled = v; loaded = true; HypnosiaClientSettings.set(ENABLED_KEY, v.toString()) }

    fun trigger(): HitTriggerMode { ensureLoaded(); return cachedTrigger }
    fun setTrigger(v: HitTriggerMode) { cachedTrigger = v; loaded = true; HypnosiaClientSettings.set(TRIGGER_KEY, v.ordinal.toString()) }

    fun gravity(): HitGravityMode { ensureLoaded(); return cachedGravity }
    fun setGravity(v: HitGravityMode) { cachedGravity = v; loaded = true; HypnosiaClientSettings.set(GRAVITY_KEY, v.ordinal.toString()) }

    fun count(): Int { ensureLoaded(); return cachedCount }
    fun setCount(v: Int) { cachedCount = v.coerceIn(1, 30); loaded = true; HypnosiaClientSettings.set(COUNT_KEY, cachedCount.toString()) }

    fun force(): Float { ensureLoaded(); return cachedForce }
    fun setForce(v: Float) { cachedForce = v.coerceIn(0.1f, 1.0f); loaded = true; HypnosiaClientSettings.set(FORCE_KEY, String.format("%.2f", cachedForce)) }

    fun lifetime(): Int { ensureLoaded(); return cachedLifetime }
    fun setLifetime(v: Int) { cachedLifetime = v.coerceIn(5, 60); loaded = true; HypnosiaClientSettings.set(LIFETIME_KEY, cachedLifetime.toString()) }

    fun texture(): WorldParticleTexture { ensureLoaded(); return cachedTexture }
    fun setTexture(v: WorldParticleTexture) { cachedTexture = v; loaded = true; HypnosiaClientSettings.set(TEXTURE_KEY, v.ordinal.toString()) }

    fun activeTextures(): Set<WorldParticleTexture> { ensureLoaded(); return cachedActiveTextures }

    fun isTextureActive(tex: WorldParticleTexture): Boolean { ensureLoaded(); return tex in cachedActiveTextures }

    fun toggleTexture(tex: WorldParticleTexture) {
        ensureLoaded()
        if (tex in cachedActiveTextures) {
            if (cachedActiveTextures.size > 1) cachedActiveTextures.remove(tex)
        } else {
            cachedActiveTextures.add(tex)
        }
        loaded = true
        val str = cachedActiveTextures.joinToString(",") { it.ordinal.toString() }
        HypnosiaClientSettings.set(TEXTURES_KEY, str)
    }

    fun size(): Float { ensureLoaded(); return cachedSize }
    fun setSize(v: Float) { cachedSize = v.coerceIn(0.05f, 1.0f); loaded = true; HypnosiaClientSettings.set(SIZE_KEY, String.format("%.3f", cachedSize)) }

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

    fun reload() { loaded = false }

    fun ensureLoaded() {
        if (loaded) return
        cachedEnabled = HypnosiaClientSettings.string(ENABLED_KEY, "false").toBooleanStrictOrNull() ?: false
        cachedTrigger = HitTriggerMode.byOrdinal(HypnosiaClientSettings.string(TRIGGER_KEY, "0").toIntOrNull() ?: 0)
        cachedGravity = HitGravityMode.byOrdinal(HypnosiaClientSettings.string(GRAVITY_KEY, "0").toIntOrNull() ?: 0)
        cachedCount = HypnosiaClientSettings.string(COUNT_KEY, "8").toIntOrNull() ?: 8
        cachedForce = HypnosiaClientSettings.string(FORCE_KEY, "0.40").toFloatOrNull() ?: 0.4f
        cachedLifetime = HypnosiaClientSettings.string(LIFETIME_KEY, "25").toIntOrNull() ?: 25
        cachedTexture = WorldParticleTexture.byOrdinal(HypnosiaClientSettings.string(TEXTURE_KEY, "0").toIntOrNull() ?: 0)
        val texturesRaw = HypnosiaClientSettings.string(TEXTURES_KEY, "")
        cachedActiveTextures = if (texturesRaw.isBlank()) {
            mutableSetOf(cachedTexture)
        } else {
            texturesRaw.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .map { WorldParticleTexture.byOrdinal(it) }
                .toMutableSet()
                .ifEmpty { mutableSetOf(cachedTexture) }
        }
        cachedSize = HypnosiaClientSettings.string(SIZE_KEY, "0.150").toFloatOrNull() ?: 0.15f
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

enum class HitTriggerMode(val displayName: String) {
    HIT_AND_CRIT("Hit + Crit"),
    HIT_ONLY("Hit Only"),
    CRIT_ONLY("Crit Only");
    companion object { fun byOrdinal(i: Int) = entries.getOrNull(i) ?: HIT_AND_CRIT }
}

enum class HitGravityMode(val displayName: String) {
    EXPLODE("Explode"),
    IMPLODE("Implode"),
    BOUNCE("Bounce");
    companion object { fun byOrdinal(i: Int) = entries.getOrNull(i) ?: EXPLODE }
}
