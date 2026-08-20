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

class ChinaHatEffect : CosmeticEffect {
    override val id: String = "china_hat"
    override val anchor: EffectAnchor = EffectAnchor.HEAD
    override var enabled: Boolean = true

    var color1: Int = 0xFF4444
    var color2: Int = 0xFFFFFF
    var color3: Int = 0xFFFFFF
    var color4: Int = 0xFFFFFF
    var colorCount: Int = 1
    var alpha: Int = 180
    var yOffset: Float = -0.45f
    var radius: Float = 0.5f
    var coneHeight: Float = -0.25f
    var gradientMode: CosmeticSettings.GradientMode = CosmeticSettings.GradientMode.STATIC
    var animSpeed: Float = 1.0f

    companion object {
        private val RENDER_LAYER = CosmeticSharedPipeline.RENDER_LAYER
        private const val SEGMENTS = 72
        private const val RINGS = 24
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

        val a = alpha.coerceIn(0, 255)

        queue.getBatchingQueue(1).submitCustom(matrices, RENDER_LAYER) { entry, vc ->
            for (ring in 0 until RINGS) {
                val topRatio = ring.toFloat() / RINGS
                val botRatio = (ring + 1).toFloat() / RINGS

                val yTop = coneHeight * (1f - topRatio)
                val yBot = coneHeight * (1f - botRatio)
                val rTop = radius * topRatio
                val rBot = radius * botRatio

                if (rTop <= 0.0001f) {
                    for (i in 0 until SEGMENTS) {
                        val a1 = (i / SEGMENTS.toFloat()) * Math.PI * 2f
                        val a2 = ((i + 1) / SEGMENTS.toFloat()) * Math.PI * 2f

                        val bx1 = cos(a1).toFloat() * rBot
                        val bz1 = sin(a1).toFloat() * rBot
                        val bx2 = cos(a2).toFloat() * rBot
                        val bz2 = sin(a2).toFloat() * rBot

                        val cTop = colorFor(1f - topRatio, ((a1 + a2) / 2.0).toFloat())
                        val cBL = colorFor(1f - botRatio, a1.toFloat())
                        val cBR = colorFor(1f - botRatio, a2.toFloat())

                        vertex(entry, vc, 0f, yTop, 0f, cTop.first, cTop.second, cTop.third, a)
                        vertex(entry, vc, bx1, yBot, bz1, cBL.first, cBL.second, cBL.third, a)
                        vertex(entry, vc, bx2, yBot, bz2, cBR.first, cBR.second, cBR.third, a)
                        vertex(entry, vc, bx2, yBot, bz2, cBR.first, cBR.second, cBR.third, a)
                    }
                } else {
                    for (i in 0 until SEGMENTS) {
                        val a1 = (i / SEGMENTS.toFloat()) * Math.PI * 2f
                        val a2 = ((i + 1) / SEGMENTS.toFloat()) * Math.PI * 2f

                        val tx1 = cos(a1).toFloat() * rTop
                        val tz1 = sin(a1).toFloat() * rTop
                        val tx2 = cos(a2).toFloat() * rTop
                        val tz2 = sin(a2).toFloat() * rTop

                        val bx1 = cos(a1).toFloat() * rBot
                        val bz1 = sin(a1).toFloat() * rBot
                        val bx2 = cos(a2).toFloat() * rBot
                        val bz2 = sin(a2).toFloat() * rBot

                        val cTL = colorFor(1f - topRatio, a1.toFloat())
                        val cTR = colorFor(1f - topRatio, a2.toFloat())
                        val cBL = colorFor(1f - botRatio, a1.toFloat())
                        val cBR = colorFor(1f - botRatio, a2.toFloat())

                        vertex(entry, vc, tx1, yTop, tz1, cTL.first, cTL.second, cTL.third, a)
                        vertex(entry, vc, bx1, yBot, bz1, cBL.first, cBL.second, cBL.third, a)
                        vertex(entry, vc, bx2, yBot, bz2, cBR.first, cBR.second, cBR.third, a)
                        vertex(entry, vc, tx2, yTop, tz2, cTR.first, cTR.second, cTR.third, a)
                    }
                }
            }
        }
    }

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
