package dev.hypnosia.visual.cosmetic.effects

import dev.hypnosia.visual.cosmetic.CosmeticEffect
import dev.hypnosia.visual.cosmetic.CosmeticSettings
import dev.hypnosia.visual.cosmetic.CosmeticSharedPipeline
import dev.hypnosia.visual.cosmetic.EffectAnchor
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.model.PlayerEntityModel
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.client.util.math.MatrixStack
import kotlin.math.cos
import kotlin.math.sin

class NimbusEffect : CosmeticEffect {
    override val id: String = "nimbus"
    override val anchor: EffectAnchor = EffectAnchor.HEAD
    override var enabled: Boolean = true

    var color1: Int = 0xFF0000
    var color2: Int = 0x00FF00
    var color3: Int = 0x0000FF
    var color4: Int = 0xFFFFFF
    var colorCount: Int = 1
    var alpha: Int = 255
    var yOffset: Float = 0.7f
    var radius: Float = 0.5f
    var tubeRadius: Float = 0.15f
    var tilt: Float = 0f
    var gradientMode: CosmeticSettings.GradientMode = CosmeticSettings.GradientMode.STATIC
    var animSpeed: Float = 1.0f

    companion object {
        private val RENDER_LAYER = CosmeticSharedPipeline.RENDER_LAYER
        private const val THETA_SEGMENTS = 48
        private const val PHI_SEGMENTS = 16
        private const val FULL_BRIGHT = 0xF000F0
    }

    private fun resolveColor(index: Int): Triple<Int, Int, Int> {
        val c = when (colorCount) {
            1 -> color1
            2 -> if (index <= 0) color1 else color2
            3 -> when (index) {
                0 -> color1
                1 -> color2
                else -> color3
            }
            else -> when (index) {
                0 -> color1
                1 -> color2
                2 -> color3
                else -> color4
            }
        }
        return Triple((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
    }

    private fun lerpColor(t: Float, a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Triple<Int, Int, Int> {
        val ct = t.coerceIn(0f, 1f)
        return Triple(
            (a.first + (b.first - a.first) * ct).toInt(),
            (a.second + (b.second - a.second) * ct).toInt(),
            (a.third + (b.third - a.third) * ct).toInt()
        )
    }

    private fun colorFor(yRatio: Float, angle: Float): Triple<Int, Int, Int> {
        return when (gradientMode) {
            CosmeticSettings.GradientMode.CHROMA -> {
                val time = System.currentTimeMillis() / 1000.0
                val hue = (time * animSpeed * 0.05 % 1.0).toFloat()
                val rgb = java.awt.Color.HSBtoRGB(hue, 0.85f, 1.0f)
                Triple((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
            }
            CosmeticSettings.GradientMode.FLUID -> {
                if (colorCount <= 1) return resolveColor(0)
                val time = System.currentTimeMillis() / 1000.0 * animSpeed
                val wave1 = sin(angle * 2.0 + time * 1.2)
                val wave2 = cos(yRatio * 4.0 - time * 0.9) * 0.7
                val wave3 = sin(angle * 5.0 + yRatio * 6.0 + time * 0.4) * 0.4
                val wave4 = cos(angle * 3.0 - yRatio * 2.0 + time * 0.6) * 0.3
                val combined = (wave1 + wave2 + wave3 + wave4) / 2.4
                var t = ((combined + 1.0) / 2.0).toFloat()
                if (t < 0f) t = 0f
                if (t > 1f) t = 1f
                val idx = t * colorCount
                val i0 = idx.toInt() % colorCount
                val i1 = (i0 + 1) % colorCount
                val frac = idx - idx.toInt()
                lerpColor(frac, resolveColor(i0), resolveColor(i1))
            }
            else -> {
                if (colorCount <= 1) return resolveColor(0)
                val idx = (yRatio * (colorCount - 1)).toInt().coerceIn(0, colorCount - 1)
                resolveColor(idx)
            }
        }
    }

    override fun render(
        matrices: MatrixStack,
        queue: OrderedRenderCommandQueue,
        light: Int,
        state: PlayerEntityRenderState,
        model: PlayerEntityModel,
        limbAngle: Float,
        limbDistance: Float
    ) {
        matrices.translate(0.0, yOffset.toDouble(), 0.0)
        val tiltC = cos(tilt)
        val tiltS = sin(tilt)

        val a = alpha.coerceIn(0, 255)
        val R = radius
        val r = tubeRadius

        queue.getBatchingQueue(1).submitCustom(matrices, RENDER_LAYER) { entry, vc ->
            for (i in 0 until THETA_SEGMENTS) {
                val theta1 = (i / THETA_SEGMENTS.toFloat()) * Math.PI * 2f
                val theta2 = ((i + 1) / THETA_SEGMENTS.toFloat()) * Math.PI * 2f

                for (j in 0 until PHI_SEGMENTS) {
                    val phi1 = (j / PHI_SEGMENTS.toFloat()) * Math.PI * 2f
                    val phi2 = ((j + 1) / PHI_SEGMENTS.toFloat()) * Math.PI * 2f

                    val p00 = torusPoint(R, r, theta1.toFloat(), phi1.toFloat(), tiltC, tiltS)
                    val p10 = torusPoint(R, r, theta2.toFloat(), phi1.toFloat(), tiltC, tiltS)
                    val p11 = torusPoint(R, r, theta2.toFloat(), phi2.toFloat(), tiltC, tiltS)
                    val p01 = torusPoint(R, r, theta1.toFloat(), phi2.toFloat(), tiltC, tiltS)

                    val c00 = colorFor(sin(phi1).toFloat() * 0.5f + 0.5f, theta1.toFloat())
                    val c10 = colorFor(sin(phi1).toFloat() * 0.5f + 0.5f, theta2.toFloat())
                    val c11 = colorFor(sin(phi2).toFloat() * 0.5f + 0.5f, theta2.toFloat())
                    val c01 = colorFor(sin(phi2).toFloat() * 0.5f + 0.5f, theta1.toFloat())

                    vertex(entry, vc, p00.x, p00.y, p00.z, c00.first, c00.second, c00.third, a)
                    vertex(entry, vc, p10.x, p10.y, p10.z, c10.first, c10.second, c10.third, a)
                    vertex(entry, vc, p11.x, p11.y, p11.z, c11.first, c11.second, c11.third, a)
                    vertex(entry, vc, p01.x, p01.y, p01.z, c01.first, c01.second, c01.third, a)
                }
            }
        }
    }

    private fun torusPoint(R: Float, r: Float, theta: Float, phi: Float, tiltC: Float, tiltS: Float): Point {
        val tx = (R + r * cos(phi)) * cos(theta)
        val ty = r * sin(phi)
        val tz = (R + r * cos(phi)) * sin(theta)
        val y = ty * tiltC - tz * tiltS
        val z = ty * tiltS + tz * tiltC
        return Point(tx, y, z)
    }

    private data class Point(val x: Float, val y: Float, val z: Float)

    private fun vertex(
        entry: MatrixStack.Entry,
        vc: VertexConsumer,
        x: Float, y: Float, z: Float,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        vc.vertex(entry, x, y, z)
            .color(r, g, b, a)
            .texture(0.5f, 0.5f)
            .overlay(OverlayTexture.DEFAULT_UV)
            .light(FULL_BRIGHT)
            .normal(entry, 0f, 1f, 0f)
    }
}
