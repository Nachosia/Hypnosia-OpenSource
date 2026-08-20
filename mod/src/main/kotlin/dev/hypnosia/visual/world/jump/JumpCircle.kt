package dev.hypnosia.visual.world.jump

class JumpCircle(
    val x: Double,
    val y: Double,
    val z: Double,
    val maxAge: Int,
    val size: Float,
    val rotationOffset: Float,
    val colorR: Int = 255,
    val colorG: Int = 255,
    val colorB: Int = 255,
) {
    var age = 0; private set
    var dead = false; private set

    val isAlive get() = !dead

    fun tick() {
        if (age >= maxAge) { dead = true; return }
        age++
    }

    fun alphaFor(t: Float, fadeMode: JumpFadeMode): Float {
        val life = (age + t) / maxAge.toFloat()
        return when (fadeMode) {
            JumpFadeMode.FADE -> (1.0f - life).coerceIn(0f, 1f)
            JumpFadeMode.SHRINK -> if (life < 0.8f) 1.0f else ((1.0f - life) / 0.2f).coerceIn(0f, 1f)
            JumpFadeMode.BOUNCE_SHRINK -> if (life < 0.7f) 1.0f else ((1.0f - life) / 0.3f).coerceIn(0f, 1f)
        }
    }

    fun sizeFor(t: Float, fadeMode: JumpFadeMode): Float {
        val life = (age + t) / maxAge.toFloat()
        val expandScale = 0.3f + 0.7f * life.coerceAtMost(0.3f) / 0.3f
        return when (fadeMode) {
            JumpFadeMode.FADE -> size * expandScale
            JumpFadeMode.SHRINK -> {
                val shrink = if (life < 0.3f) expandScale
                else (1.0f - (life - 0.3f) / 0.7f).coerceIn(0f, 1f)
                size * shrink
            }
            JumpFadeMode.BOUNCE_SHRINK -> {
                if (life < 0.3f) return size * expandScale
                val shrinkPhase = (life - 0.3f) / 0.7f
                val shrink = when {
                    shrinkPhase < 0.5f -> 1.0f - shrinkPhase * 1.2f
                    shrinkPhase < 0.65f -> 0.4f + (shrinkPhase - 0.5f) / 0.15f * 0.2f
                    else -> 0.6f * (1.0f - (shrinkPhase - 0.65f) / 0.35f)
                }
                size * shrink.coerceIn(0f, 1f)
            }
        }
    }
}

enum class JumpFadeMode(val displayName: String) {
    FADE("Fade"),
    SHRINK("Shrink"),
    BOUNCE_SHRINK("Bounce");

    companion object {
        fun byOrdinal(i: Int) = entries.getOrNull(i) ?: FADE
    }

    fun next(): JumpFadeMode = entries[(ordinal + 1) % entries.size]
}
