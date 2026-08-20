package dev.hypnosia.ui.animation

import net.minecraft.client.render.RenderTickCounter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object FigmaAnimation {
    fun frameSeconds(tickCounter: RenderTickCounter, ticksPerSecond: Float = 20.0f): Float {
        return (tickCounter.getDynamicDeltaTicks() / ticksPerSecond).coerceIn(0.0f, 0.25f)
    }

    fun lerp(from: Float, to: Float, t: Float): Float {
        return from + (to - from) * t.coerceIn(0.0f, 1.0f)
    }

    fun lerpArgb(from: Int, to: Int, t: Float): Int {
        val amount = t.coerceIn(0.0f, 1.0f)
        val a = lerpChannel(from, to, 24, amount)
        val r = lerpChannel(from, to, 16, amount)
        val g = lerpChannel(from, to, 8, amount)
        val b = lerpChannel(from, to, 0, amount)
        return ((a and 0xFF) shl 24) or
            ((r and 0xFF) shl 16) or
            ((g and 0xFF) shl 8) or
            (b and 0xFF)
    }

    fun exponentialApproach(current: Float, target: Float, seconds: Float, speed: Float): Float {
        if (seconds <= 0.0f || speed <= 0.0f) {
            return current
        }
        val amount = 1.0f - kotlin.math.exp(-speed * seconds)
        return lerp(current, target, amount)
    }

    fun cubicBezier(x1: Float, y1: Float, x2: Float, y2: Float): Easing {
        return Easing { progress ->
            val x = progress.coerceIn(0.0f, 1.0f)
            val solved = solveBezierTForX(x, x1, x2)
            sampleBezier(solved, y1, y2)
        }
    }

    val Linear = Easing { it.coerceIn(0.0f, 1.0f) }
    val EaseIn = cubicBezier(0.42f, 0.0f, 1.0f, 1.0f)
    val EaseOut = cubicBezier(0.0f, 0.0f, 0.58f, 1.0f)
    val EaseInOut = cubicBezier(0.42f, 0.0f, 0.58f, 1.0f)
    val FigmaGentle = cubicBezier(0.2f, 0.0f, 0.0f, 1.0f)

    private fun lerpChannel(from: Int, to: Int, shift: Int, t: Float): Int {
        val a = (from ushr shift) and 0xFF
        val b = (to ushr shift) and 0xFF
        return (a + (b - a) * t).toInt().coerceIn(0, 255)
    }

    private fun sampleBezier(t: Float, p1: Float, p2: Float): Float {
        val u = 1.0f - t
        return 3.0f * u * u * t * p1 + 3.0f * u * t * t * p2 + t * t * t
    }

    private fun sampleBezierDerivative(t: Float, p1: Float, p2: Float): Float {
        val u = 1.0f - t
        return 3.0f * u * u * p1 + 6.0f * u * t * (p2 - p1) + 3.0f * t * t * (1.0f - p2)
    }

    private fun solveBezierTForX(x: Float, x1: Float, x2: Float): Float {
        var t = x
        repeat(6) {
            val current = sampleBezier(t, x1, x2) - x
            val derivative = sampleBezierDerivative(t, x1, x2)
            if (abs(derivative) < 0.0001f) {
                return@repeat
            }
            t = (t - current / derivative).coerceIn(0.0f, 1.0f)
        }
        return t
    }
}

fun interface Easing {
    fun transform(progress: Float): Float
}

class AnimatedFloat(
    initial: Float,
    private val speed: Float = 14.0f,
    private val snapEpsilon: Float = 0.001f,
) {
    var value: Float = initial
        private set

    var target: Float = initial

    fun snap(value: Float) {
        this.value = value
        target = value
    }

    fun update(seconds: Float): Float {
        value = FigmaAnimation.exponentialApproach(value, target, seconds, speed)
        if (abs(value - target) <= snapEpsilon) {
            value = target
        }
        return value
    }
}

class AnimatedColor(
    initial: Int,
    private val speed: Float = 14.0f,
) {
    var value: Int = initial
        private set

    var target: Int = initial

    private val alpha = AnimatedFloat(((initial ushr 24) and 0xFF).toFloat(), speed)
    private val red = AnimatedFloat(((initial ushr 16) and 0xFF).toFloat(), speed)
    private val green = AnimatedFloat(((initial ushr 8) and 0xFF).toFloat(), speed)
    private val blue = AnimatedFloat((initial and 0xFF).toFloat(), speed)

    fun snap(value: Int) {
        this.value = value
        target = value
        alpha.snap(((value ushr 24) and 0xFF).toFloat())
        red.snap(((value ushr 16) and 0xFF).toFloat())
        green.snap(((value ushr 8) and 0xFF).toFloat())
        blue.snap((value and 0xFF).toFloat())
    }

    fun update(seconds: Float): Int {
        alpha.target = ((target ushr 24) and 0xFF).toFloat()
        red.target = ((target ushr 16) and 0xFF).toFloat()
        green.target = ((target ushr 8) and 0xFF).toFloat()
        blue.target = (target and 0xFF).toFloat()

        value = pack(
            alpha.update(seconds),
            red.update(seconds),
            green.update(seconds),
            blue.update(seconds),
        )
        return value
    }

    private fun pack(a: Float, r: Float, g: Float, b: Float): Int {
        return ((a.toInt().coerceIn(0, 255) and 0xFF) shl 24) or
            ((r.toInt().coerceIn(0, 255) and 0xFF) shl 16) or
            ((g.toInt().coerceIn(0, 255) and 0xFF) shl 8) or
            (b.toInt().coerceIn(0, 255) and 0xFF)
    }
}

class SpringColor(
    initial: Int,
    stiffness: Float = 420.0f,
    damping: Float = 34.0f,
) {
    var value: Int = initial
        private set

    var target: Int = initial

    private val alpha = SpringFloat(((initial ushr 24) and 0xFF).toFloat(), stiffness, damping)
    private val red = SpringFloat(((initial ushr 16) and 0xFF).toFloat(), stiffness, damping)
    private val green = SpringFloat(((initial ushr 8) and 0xFF).toFloat(), stiffness, damping)
    private val blue = SpringFloat((initial and 0xFF).toFloat(), stiffness, damping)

    fun snap(value: Int) {
        this.value = value
        target = value
        alpha.snap(((value ushr 24) and 0xFF).toFloat())
        red.snap(((value ushr 16) and 0xFF).toFloat())
        green.snap(((value ushr 8) and 0xFF).toFloat())
        blue.snap((value and 0xFF).toFloat())
    }

    fun update(seconds: Float): Int {
        alpha.target = ((target ushr 24) and 0xFF).toFloat()
        red.target = ((target ushr 16) and 0xFF).toFloat()
        green.target = ((target ushr 8) and 0xFF).toFloat()
        blue.target = (target and 0xFF).toFloat()

        value = pack(
            alpha.update(seconds),
            red.update(seconds),
            green.update(seconds),
            blue.update(seconds),
        )
        return value
    }

    private fun pack(a: Float, r: Float, g: Float, b: Float): Int {
        return ((a.toInt().coerceIn(0, 255) and 0xFF) shl 24) or
            ((r.toInt().coerceIn(0, 255) and 0xFF) shl 16) or
            ((g.toInt().coerceIn(0, 255) and 0xFF) shl 8) or
            (b.toInt().coerceIn(0, 255) and 0xFF)
    }
}

class TimedTransition(
    private val durationSeconds: Float,
    private val easing: Easing = FigmaAnimation.FigmaGentle,
) {
    private var elapsed = 0.0f
    private var direction = 0

    var progress = 0.0f
        private set

    fun show() {
        direction = 1
    }

    fun hide() {
        direction = -1
    }

    fun snap(visible: Boolean) {
        direction = 0
        elapsed = if (visible) durationSeconds else 0.0f
        progress = if (visible) 1.0f else 0.0f
    }

    fun update(seconds: Float): Float {
        if (direction != 0) {
            elapsed = (elapsed + seconds * direction).coerceIn(0.0f, durationSeconds)
            if (elapsed == 0.0f || elapsed == durationSeconds) {
                direction = 0
            }
        }

        val raw = if (durationSeconds <= 0.0f) 1.0f else elapsed / durationSeconds
        progress = easing.transform(raw)
        return progress
    }
}

class SpringFloat(
    initial: Float,
    private val stiffness: Float = 420.0f,
    private val damping: Float = 34.0f,
) {
    var value = initial
        private set

    var velocity = 0.0f
        private set

    var target = initial

    fun snap(value: Float) {
        this.value = value
        target = value
        velocity = 0.0f
    }

    fun update(seconds: Float): Float {
        val dt = max(0.0f, seconds.coerceAtMost(0.05f))
        val displacement = value - target
        val acceleration = -stiffness * displacement - damping * velocity
        velocity += acceleration * dt
        value += velocity * dt

        if (abs(value - target) < 0.001f && abs(velocity) < 0.001f) {
            value = target
            velocity = 0.0f
        }
        return value
    }
}

data class AnimatedRect(
    val x: AnimatedFloat,
    val y: AnimatedFloat,
    val width: AnimatedFloat,
    val height: AnimatedFloat,
) {
    fun update(seconds: Float) {
        x.update(seconds)
        y.update(seconds)
        width.update(seconds)
        height.update(seconds)
    }
}
