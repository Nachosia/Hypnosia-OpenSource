package dev.hypnosia.visual.world.particles.hit

import dev.hypnosia.visual.world.particles.WorldParticleTexture
import kotlin.math.abs

class HitParticle(
    var x: Double,
    var y: Double,
    var z: Double,
    val texture: WorldParticleTexture,
    val gravityMode: HitGravityMode,
    val maxAge: Int,
    val originX: Double,
    val originY: Double,
    val originZ: Double,
    val seed: Float,
    val baseSize: Float = 0.15f,
    val colorR: Int = 255,
    val colorG: Int = 255,
    val colorB: Int = 255,
) {
    var velocityX = 0.0
    var velocityY = 0.0
    var velocityZ = 0.0

    var prevX = x; private set
    var prevY = y; private set
    var prevZ = z; private set

    var age = 0; private set
    var dead = false; private set

    val isAlive get() = !dead

    fun lerpX(t: Float): Double = prevX + (x - prevX) * t
    fun lerpY(t: Float): Double = prevY + (y - prevY) * t
    fun lerpZ(t: Float): Double = prevZ + (z - prevZ) * t

    fun alphaFor(t: Float): Float {
        val life = (age + t) / maxAge.toFloat()
        return (1.0f - life).coerceIn(0f, 1f)
    }

    fun sizeFor(t: Float): Float {
        val life = (age + t) / maxAge.toFloat()
        return (baseSize * (1.0f - life * 0.5f)).coerceAtLeast(0.02f)
    }

    fun tick() {
        if (age >= maxAge) { dead = true; return }
        age++
        prevX = x; prevY = y; prevZ = z

        when (gravityMode) {
            HitGravityMode.EXPLODE -> {
                velocityX *= 0.92
                velocityY *= 0.92
                velocityZ *= 0.92
                x += velocityX
                y += velocityY
                z += velocityZ
            }
            HitGravityMode.IMPLODE -> {
                val lifeRatio = age.toFloat() / maxAge.toFloat()
                if (lifeRatio < 0.25f) {
                    velocityX *= 0.90
                    velocityY *= 0.90
                    velocityZ *= 0.90
                } else {
                    val dx = originX - x
                    val dy = originY - y
                    val dz = originZ - z
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.01)
                    val progress = ((lifeRatio - 0.25f) / 0.75f).coerceIn(0.0f, 1.0f)
                    val pull = 0.04 + progress * 0.08
                    velocityX += (dx / dist) * pull
                    velocityY += (dy / dist) * pull
                    velocityZ += (dz / dist) * pull
                    velocityX *= 0.88
                    velocityY *= 0.88
                    velocityZ *= 0.88
                }
                x += velocityX
                y += velocityY
                z += velocityZ
            }
            HitGravityMode.BOUNCE -> {
                velocityY -= 0.012
                velocityX *= 0.96
                velocityZ *= 0.96
                x += velocityX
                y += velocityY
                z += velocityZ
            }
        }
    }

    fun bounceFromFloor(floorY: Double) {
        if (gravityMode != HitGravityMode.BOUNCE) return
        if (velocityY < 0 && y <= floorY) {
            y = floorY
            velocityY = -velocityY * 0.35
            if (abs(velocityY) < 0.003) velocityY = 0.0
        }
    }
}
