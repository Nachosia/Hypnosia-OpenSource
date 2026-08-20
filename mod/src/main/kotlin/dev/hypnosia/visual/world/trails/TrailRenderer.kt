package dev.hypnosia.visual.world.trails

import dev.hypnosia.visual.cosmetic.CosmeticSettings.GradientMode
import dev.hypnosia.visual.world.particles.WorldParticlePipeline
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.OverlayTexture
import net.minecraft.util.Identifier
import kotlin.math.sqrt

object TrailRenderer {

    private val points = ArrayDeque<TrailPoint>(110)
    private var globalTick = 0
    private const val FULL_BRIGHT = 0xF000F0
    private val WHITE_TEXTURE = Identifier.of("hypnosia", "textures/white.png")

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!TrailSettings.enabled()) {
                if (points.isNotEmpty()) points.clear()
                return@register
            }
            globalTick++

            val player = client.player ?: return@register
            val maxLen = TrailSettings.length()
            points.addFirst(TrailPoint(player.x, player.y, player.z, player.height))
            while (points.size > maxLen) points.removeLast()
        }

        WorldRenderEvents.BEFORE_TRANSLUCENT.register { context ->
            if (!TrailSettings.enabled()) return@register
            val n = points.size
            if (n < 2) return@register

            val client = MinecraftClient.getInstance()
            val camera = client.gameRenderer.camera ?: return@register
            val isThirdPerson = !client.options.perspective.isFirstPerson
            if (TrailSettings.onlyF5() && !isThirdPerson) return@register

            val camPos = camera.cameraPos
            val matrices = context.matrices()
            val queue = context.commandQueue()
            val batch = queue.getBatchingQueue(1)
            val layer = WorldParticlePipeline.layerFor(WHITE_TEXTURE)

            val baseAlpha = TrailSettings.alpha()
            val width = TrailSettings.width()
            val colorCount = TrailSettings.colorCount()
            val gradMode = TrailSettings.gradientMode()
            val colors = intArrayOf(
                TrailSettings.color1(),
                TrailSettings.color2(),
                TrailSettings.color3(),
                TrailSettings.color4(),
            )

            // Pre-compute per-point perpendicular vectors in XZ (no recompute per-segment)
            // perp[i] is the XZ-perpendicular at point i, averaged from adjacent directions
            val perpX = FloatArray(n)
            val perpZ = FloatArray(n)

            for (i in 0 until n) {
                val prev = if (i > 0) points[i - 1] else points[i]
                val next = if (i < n - 1) points[i + 1] else points[i]

                val dx = (next.x - prev.x).toFloat()
                val dz = (next.z - prev.z).toFloat()
                val len = sqrt(dx * dx + dz * dz)
                if (len > 0.0001f) {
                    // Perpendicular to direction in XZ: rotate 90°
                    perpX[i] = -dz / len
                    perpZ[i] = dx / len
                } else if (i > 0) {
                    // Fallback: copy previous
                    perpX[i] = perpX[i - 1]
                    perpZ[i] = perpZ[i - 1]
                } else {
                    perpX[i] = 1f
                    perpZ[i] = 0f
                }
            }

            batch.submitCustom(matrices, layer) { entry, vc ->
                // Build one continuous ribbon: for each adjacent pair i, i+1
                // share vertices at their respective perp positions → no seams
                for (i in 0 until n - 1) {
                    val p0 = points[i]
                    val p1 = points[i + 1]

                    val t0 = i.toFloat() / (n - 1).toFloat()
                    val t1 = (i + 1).toFloat() / (n - 1).toFloat()

                    val alpha0 = (baseAlpha * (1f - t0)).toInt().coerceIn(0, 255)
                    val alpha1 = (baseAlpha * (1f - t1)).toInt().coerceIn(0, 255)
                    if (alpha0 <= 0 && alpha1 <= 0) continue

                    val color0 = computeColor(colors, colorCount, gradMode, t0)
                    val color1 = computeColor(colors, colorCount, gradMode, t1)

                    val cr0 = (color0 shr 16) and 0xFF; val cg0 = (color0 shr 8) and 0xFF; val cb0 = color0 and 0xFF
                    val cr1 = (color1 shr 16) and 0xFF; val cg1 = (color1 shr 8) and 0xFF; val cb1 = color1 and 0xFF

                    val hw0 = width * 0.5f
                    val hw1 = width * 0.5f

                    // Positions relative to camera
                    val x0 = (p0.x - camPos.x).toFloat()
                    val y0 = (p0.y - camPos.y).toFloat()
                    val z0 = (p0.z - camPos.z).toFloat()
                    val x1 = (p1.x - camPos.x).toFloat()
                    val y1 = (p1.y - camPos.y).toFloat()
                    val z1 = (p1.z - camPos.z).toFloat()

                    val h0 = p0.height
                    val h1 = p1.height

                    val px0 = perpX[i];  val pz0 = perpZ[i]
                    val px1 = perpX[i + 1]; val pz1 = perpZ[i + 1]

                    // Quad: 4 corners — bottom-left, top-left, top-right, bottom-right
                    // Using each point's own perp so adjacent quads share exact vertices → no seam
                    vc.vertex(entry, x0 - px0 * hw0, y0,      z0 - pz0 * hw0)
                        .color(cr0, cg0, cb0, alpha0).texture(0f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_BRIGHT).normal(entry, 0f, 1f, 0f)
                    vc.vertex(entry, x0 - px0 * hw0, y0 + h0, z0 - pz0 * hw0)
                        .color(cr0, cg0, cb0, alpha0).texture(0f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_BRIGHT).normal(entry, 0f, 1f, 0f)
                    vc.vertex(entry, x1 - px1 * hw1, y1 + h1, z1 - pz1 * hw1)
                        .color(cr1, cg1, cb1, alpha1).texture(1f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_BRIGHT).normal(entry, 0f, 1f, 0f)
                    vc.vertex(entry, x1 - px1 * hw1, y1,      z1 - pz1 * hw1)
                        .color(cr1, cg1, cb1, alpha1).texture(1f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_BRIGHT).normal(entry, 0f, 1f, 0f)
                }
            }
        }
    }

    private fun computeColor(colors: IntArray, colorCount: Int, gradMode: GradientMode, t: Float): Int {
        if (colorCount <= 1) return colors[0]
        return when (gradMode) {
            GradientMode.STATIC -> {
                val idx = (t * (colorCount - 1)).toInt().coerceIn(0, colorCount - 2)
                val frac = t * (colorCount - 1) - idx
                lerpColor(colors[idx], colors[idx + 1], frac)
            }
            GradientMode.FLUID -> {
                val animT = ((t + globalTick * TrailSettings.animSpeed() * 0.02f) % 1f)
                lerpColors(colors, colorCount, animT)
            }
            GradientMode.CHROMA -> {
                val animT = ((t + globalTick * TrailSettings.animSpeed() * 0.03f) % 1f)
                lerpColors(colors, colorCount, animT)
            }
        }
    }

    private fun lerpColors(colors: IntArray, count: Int, t: Float): Int {
        val segT = t * (count - 1)
        val idx = segT.toInt().coerceIn(0, count - 2)
        val frac = segT - idx
        return lerpColor(colors[idx], colors[idx + 1], frac)
    }

    private fun lerpColor(c1: Int, c2: Int, frac: Float): Int {
        val r = ((c1 shr 16 and 0xFF) + (((c2 shr 16 and 0xFF) - (c1 shr 16 and 0xFF)) * frac)).toInt()
        val g = ((c1 shr 8 and 0xFF) + (((c2 shr 8 and 0xFF) - (c1 shr 8 and 0xFF)) * frac)).toInt()
        val b = ((c1 and 0xFF) + (((c2 and 0xFF) - (c1 and 0xFF)) * frac)).toInt()
        return (r shl 16) or (g shl 8) or b
    }
}
