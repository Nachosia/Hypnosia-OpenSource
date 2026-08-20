package dev.hypnosia.visual.world.particles

import dev.hypnosia.config.HypnosiaClientSettings
import dev.hypnosia.visual.cosmetic.CosmeticSettings.GradientMode

object WorldParticleSettings {
    private const val ENABLED_KEY = "visuals.world_particles.enabled"
    private const val TEXTURE_KEY = "visuals.world_particles.texture"
    private const val TEXTURES_KEY = "visuals.world_particles.textures"
    private const val MODE_KEY = "visuals.world_particles.mode"
    private const val COUNT_KEY = "visuals.world_particles.count"
    private const val SPAWN_RATE_KEY = "visuals.world_particles.spawn_rate"
    private const val SIZE_KEY = "visuals.world_particles.size"
    private const val SPEED_KEY = "visuals.world_particles.speed"
    private const val LIFE_KEY = "visuals.world_particles.life"
    private const val GRAVITY_KEY = "visuals.world_particles.gravity"
    private const val ALPHA_KEY = "visuals.world_particles.alpha"
    private const val COLOR1_KEY = "visuals.world_particles.color1"
    private const val COLOR2_KEY = "visuals.world_particles.color2"
    private const val COLOR3_KEY = "visuals.world_particles.color3"
    private const val COLOR4_KEY = "visuals.world_particles.color4"
    private const val COLOR_COUNT_KEY = "visuals.world_particles.color_count"
    private const val GRADIENT_MODE_KEY = "visuals.world_particles.gradient_mode"
    private const val ANIM_SPEED_KEY = "visuals.world_particles.anim_speed"
    private const val GRAVITY_MODE_KEY = "visuals.world_particles.gravity_mode"
    private const val SPAWN_HEIGHT_KEY = "visuals.world_particles.spawn_height"

    private var loaded = false
    private var cachedEnabled = false
    private var cachedTexture = WorldParticleTexture.CROSS
    private var cachedActiveTextures = mutableSetOf(WorldParticleTexture.CROSS)
    private var cachedMode = WorldParticleMode.NEON
    private var cachedCount = 20
    private var cachedSpawnRate = 3
    private var cachedSize = 0.25f
    private var cachedSpeed = 0.008f
    private var cachedLife = 5.0f
    private var cachedGravity = 0.002f
    private var cachedAlpha = 180
    private var cachedColor1 = 0xFFFFFF
    private var cachedColor2 = 0xFF2F86.toInt()
    private var cachedColor3 = 0x2FB8FF
    private var cachedColor4 = 0xFFFFFF
    private var cachedColorCount = 1
    private var cachedGradientMode = GradientMode.STATIC
    private var cachedAnimSpeed = 1.0f
    private var cachedGravityMode = WorldGravityMode.FLOAT
    private var cachedSpawnHeight = 10

    fun enabled(): Boolean {
        ensureLoaded()
        return cachedEnabled
    }

    fun setEnabled(enabled: Boolean) {
        cachedEnabled = enabled
        loaded = true
        HypnosiaClientSettings.set(ENABLED_KEY, enabled.toString())
    }

    fun texture(): WorldParticleTexture {
        ensureLoaded()
        return cachedTexture
    }

    fun setTexture(texture: WorldParticleTexture) {
        cachedTexture = texture
        loaded = true
        HypnosiaClientSettings.set(TEXTURE_KEY, texture.ordinal.toString())
    }

    /** Набор активных текстур (спавнятся случайно из этого набора). */
    fun activeTextures(): Set<WorldParticleTexture> {
        ensureLoaded()
        return cachedActiveTextures
    }

    fun isTextureActive(tex: WorldParticleTexture): Boolean {
        ensureLoaded()
        return tex in cachedActiveTextures
    }

    fun toggleTexture(tex: WorldParticleTexture) {
        ensureLoaded()
        if (tex in cachedActiveTextures) {
            if (cachedActiveTextures.size > 1) cachedActiveTextures.remove(tex)
        } else {
            cachedActiveTextures.add(tex)
        }
        loaded = true
        saveActiveTextures()
    }

    private fun saveActiveTextures() {
        val str = cachedActiveTextures.joinToString(",") { it.ordinal.toString() }
        HypnosiaClientSettings.set(TEXTURES_KEY, str)
    }

    fun mode(): WorldParticleMode {
        ensureLoaded()
        return cachedMode
    }

    fun setMode(mode: WorldParticleMode) {
        cachedMode = mode
        loaded = true
        HypnosiaClientSettings.set(MODE_KEY, mode.ordinal.toString())
    }

    fun count(): Int {
        ensureLoaded()
        return cachedCount
    }

    fun setCount(count: Int) {
        val v = count.coerceIn(0, 200)
        cachedCount = v
        loaded = true
        HypnosiaClientSettings.set(COUNT_KEY, v.toString())
    }

    fun spawnRate(): Int {
        ensureLoaded()
        return cachedSpawnRate
    }

    fun setSpawnRate(spawnRate: Int) {
        val v = spawnRate.coerceIn(1, 20)
        cachedSpawnRate = v
        loaded = true
        HypnosiaClientSettings.set(SPAWN_RATE_KEY, v.toString())
    }

    fun size(): Float {
        ensureLoaded()
        return cachedSize
    }

    fun setSize(size: Float) {
        val v = size.coerceIn(0.01f, 2.0f)
        cachedSize = v
        loaded = true
        HypnosiaClientSettings.set(SIZE_KEY, String.format("%.3f", v))
    }

    fun speed(): Float {
        ensureLoaded()
        return cachedSpeed
    }

    fun setSpeed(speed: Float) {
        val v = speed.coerceIn(0.0f, 0.2f)
        cachedSpeed = v
        loaded = true
        HypnosiaClientSettings.set(SPEED_KEY, String.format("%.4f", v))
    }

    fun life(): Float {
        ensureLoaded()
        return cachedLife
    }

    fun setLife(life: Float) {
        val v = life.coerceIn(1.0f, 15.0f)
        cachedLife = v
        loaded = true
        HypnosiaClientSettings.set(LIFE_KEY, String.format("%.2f", v))
    }

    fun gravity(): Float {
        ensureLoaded()
        return cachedGravity
    }

    fun setGravity(gravity: Float) {
        val v = gravity.coerceIn(-0.02f, 0.02f)
        cachedGravity = v
        loaded = true
        HypnosiaClientSettings.set(GRAVITY_KEY, String.format("%.4f", v))
    }

    fun alpha(): Int {
        ensureLoaded()
        return cachedAlpha
    }

    fun setAlpha(alpha: Int) {
        val v = alpha.coerceIn(0, 255)
        cachedAlpha = v
        loaded = true
        HypnosiaClientSettings.set(ALPHA_KEY, v.toString())
    }

    fun color1(): Int { ensureLoaded(); return cachedColor1 }
    fun color2(): Int { ensureLoaded(); return cachedColor2 }
    fun color3(): Int { ensureLoaded(); return cachedColor3 }
    fun color4(): Int { ensureLoaded(); return cachedColor4 }

    fun setColor1(color: Int) {
        cachedColor1 = color and 0xFFFFFF
        loaded = true
        HypnosiaClientSettings.set(COLOR1_KEY, String.format("%06X", cachedColor1))
    }

    fun setColor2(color: Int) {
        cachedColor2 = color and 0xFFFFFF
        loaded = true
        HypnosiaClientSettings.set(COLOR2_KEY, String.format("%06X", cachedColor2))
    }

    fun setColor3(color: Int) {
        cachedColor3 = color and 0xFFFFFF
        loaded = true
        HypnosiaClientSettings.set(COLOR3_KEY, String.format("%06X", cachedColor3))
    }

    fun setColor4(color: Int) {
        cachedColor4 = color and 0xFFFFFF
        loaded = true
        HypnosiaClientSettings.set(COLOR4_KEY, String.format("%06X", cachedColor4))
    }

    fun colorCount(): Int { ensureLoaded(); return cachedColorCount }

    fun setColorCount(count: Int) {
        val v = count.coerceIn(1, 4)
        cachedColorCount = v
        loaded = true
        HypnosiaClientSettings.set(COLOR_COUNT_KEY, v.toString())
    }

    fun gradientMode(): GradientMode { ensureLoaded(); return cachedGradientMode }

    fun setGradientMode(mode: GradientMode) {
        cachedGradientMode = mode
        loaded = true
        HypnosiaClientSettings.set(GRADIENT_MODE_KEY, mode.ordinal.toString())
    }

    fun animSpeed(): Float { ensureLoaded(); return cachedAnimSpeed }

    fun setAnimSpeed(speed: Float) {
        val v = speed.coerceIn(0.0f, 5.0f)
        cachedAnimSpeed = v
        loaded = true
        HypnosiaClientSettings.set(ANIM_SPEED_KEY, String.format("%.2f", v))
    }

    fun gravityMode(): WorldGravityMode { ensureLoaded(); return cachedGravityMode }

    fun setGravityMode(mode: WorldGravityMode) {
        cachedGravityMode = mode
        loaded = true
        HypnosiaClientSettings.set(GRAVITY_MODE_KEY, mode.ordinal.toString())
    }

    fun spawnHeight(): Int { ensureLoaded(); return cachedSpawnHeight }

    fun setSpawnHeight(height: Int) {
        val v = height.coerceIn(5, 30)
        cachedSpawnHeight = v
        loaded = true
        HypnosiaClientSettings.set(SPAWN_HEIGHT_KEY, v.toString())
    }

    fun reload() { loaded = false }

    fun ensureLoaded() {
        if (loaded) return
        cachedEnabled = HypnosiaClientSettings.string(ENABLED_KEY, "false").toBooleanStrictOrNull() ?: false
        cachedTexture = WorldParticleTexture.byOrdinal(
            HypnosiaClientSettings.string(TEXTURE_KEY, "0").toIntOrNull() ?: 0
        )
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
        cachedMode = WorldParticleMode.byOrdinal(
            HypnosiaClientSettings.string(MODE_KEY, "0").toIntOrNull() ?: 0
        )
        cachedCount = HypnosiaClientSettings.string(COUNT_KEY, "20").toIntOrNull() ?: 20
        cachedSpawnRate = HypnosiaClientSettings.string(SPAWN_RATE_KEY, "3").toIntOrNull() ?: 3
        cachedSize = HypnosiaClientSettings.string(SIZE_KEY, "0.250").toFloatOrNull() ?: 0.25f
        cachedSpeed = HypnosiaClientSettings.string(SPEED_KEY, "0.0080").toFloatOrNull() ?: 0.008f
        cachedLife = HypnosiaClientSettings.string(LIFE_KEY, "5.00").toFloatOrNull() ?: 5.0f
        cachedGravity = HypnosiaClientSettings.string(GRAVITY_KEY, "0.0020").toFloatOrNull() ?: 0.002f
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
        cachedGravityMode = WorldGravityMode.byOrdinal(
            HypnosiaClientSettings.string(GRAVITY_MODE_KEY, "0").toIntOrNull() ?: 0
        )
        cachedSpawnHeight = HypnosiaClientSettings.string(SPAWN_HEIGHT_KEY, "10").toIntOrNull() ?: 10
        loaded = true
    }
}
