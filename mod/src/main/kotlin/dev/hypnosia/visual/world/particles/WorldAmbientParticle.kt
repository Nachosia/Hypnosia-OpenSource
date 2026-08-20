package dev.hypnosia.visual.world.particles

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Одна частица мира. Простой объект состояния — без vanilla BillboardParticle.
 *
 * Хранит prevX/prevY/prevZ для плавной интерполяции позиции в рендере (без jitter).
 * Bounce коллизия делается снаружи (в [WorldParticleRenderer.tick]) через world blockstate.
 */
class WorldAmbientParticle(
    var x: Double,
    var y: Double,
    var z: Double,
    val texture: WorldParticleTexture,
    val mode: WorldParticleMode,
    val gravityMode: WorldGravityMode,
    val maxAge: Int,
    val baseScale: Float,
    private val driftPhase: Float,
    private val gravity: Float,
    private val baseAlpha: Float,
    val seed: Float,
) {
    var velocityX = 0.0
    var velocityY = 0.0
    var velocityZ = 0.0

    var prevX = x; private set
    var prevY = y; private set
    var prevZ = z; private set

    var age = 0
        private set

    var fade = 0f
        private set
    private var prevFade = 0f

    var rotation = 0f
        private set
    private var prevRotation = 0f

    var dead = false
        private set

    val isAlive: Boolean get() = !dead

    fun setVelocity(vx: Double, vy: Double, vz: Double) {
        velocityX = vx
        velocityY = vy
        velocityZ = vz
    }

    fun markDead() { dead = true }

    fun sizeFor(tickDelta: Float): Float {
        val f = prevFade + (fade - prevFade) * tickDelta
        val spawnGrow = ((age + tickDelta) / 30f).coerceIn(0f, 1f)
        return baseScale * f.coerceIn(0f, 1f) * (0.4f + 0.6f * spawnGrow)
    }

    fun alphaFor(tickDelta: Float): Float {
        val f = prevFade + (fade - prevFade) * tickDelta
        return (f * baseAlpha).coerceIn(0f, 1f)
    }

    fun rotationFor(tickDelta: Float): Float =
        prevRotation + (rotation - prevRotation) * tickDelta

    /** Интерполированная позиция для рендера. */
    fun lerpX(tickDelta: Float): Double = prevX + (x - prevX) * tickDelta
    fun lerpY(tickDelta: Float): Double = prevY + (y - prevY) * tickDelta
    fun lerpZ(tickDelta: Float): Double = prevZ + (z - prevZ) * tickDelta

    fun tick() {
        if (age >= maxAge) {
            markDead()
            return
        }
        age++

        prevFade = fade
        prevRotation = rotation
        prevX = x
        prevY = y
        prevZ = z

        val lifeRatio = age.toFloat() / maxAge.toFloat()
        fade = sin(lifeRatio * PI).toFloat().coerceIn(0f, 1f)

        when (mode) {
            WorldParticleMode.NEON -> {
                x += velocityX
                z += velocityZ
            }
            WorldParticleMode.STARS -> {
                x += velocityX
                z += velocityZ
                rotation += 0.01f
            }
            WorldParticleMode.DUST -> {
                val drift = sin(age * 0.05 + driftPhase).toFloat() * 0.002
                x += velocityX + drift
                z += velocityZ + drift
            }
            WorldParticleMode.SNOW_ASH -> {
                val drift = sin(age * 0.03 + driftPhase).toFloat() * 0.001
                x += velocityX + drift
                z += velocityZ + drift
            }
            WorldParticleMode.MAGIC -> {
                val driftX = sin(age * 0.08 + driftPhase) * velocityX * 0.5
                val driftZ = cos(age * 0.08 + driftPhase) * velocityZ * 0.5
                x += velocityX + driftX
                z += velocityZ + driftZ
                rotation += 0.02f
            }
        }

        when (gravityMode) {
            WorldGravityMode.FLOAT -> {
                y += velocityY
            }
            WorldGravityMode.FALL -> {
                velocityY -= gravity.toDouble() * 0.8
                y += velocityY
            }
            WorldGravityMode.BOUNCE -> {
                velocityY -= gravity.toDouble() * 0.8
                y += velocityY
            }
        }
    }

    /** Вызывается из рендерера после tick() для BOUNCE — отскок от реального блока. */
    fun bounceFromFloor(floorY: Double) {
        if (gravityMode != WorldGravityMode.BOUNCE) return
        if (velocityY < 0 && y <= floorY) {
            y = floorY
            velocityY = -velocityY * 0.4
            if (kotlin.math.abs(velocityY) < 0.003) velocityY = 0.0
        }
    }
}
