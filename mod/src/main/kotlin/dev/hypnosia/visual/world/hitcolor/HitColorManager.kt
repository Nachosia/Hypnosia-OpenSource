package dev.hypnosia.visual.world.hitcolor

import dev.hypnosia.visual.cosmetic.CosmeticSettings.GradientMode
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient

object HitColorManager {

    private var globalTick = 0
    private var lastAppliedColor = -1

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            globalTick++
            if (!HitColorSettings.enabled()) {
                if (lastAppliedColor != 0xFF0000) {
                    restoreVanilla()
                    lastAppliedColor = 0xFF0000
                }
                return@register
            }

            val color = computeCurrentColor()
            if (color != lastAppliedColor || HitColorSettings.dirty) {
                rewriteOverlayColor(color)
                lastAppliedColor = color
                HitColorSettings.clearDirty()
            }
        }
    }

    private fun computeCurrentColor(): Int {
        val colorCount = HitColorSettings.colorCount()
        val colors = intArrayOf(
            HitColorSettings.color1(),
            HitColorSettings.color2(),
            HitColorSettings.color3(),
            HitColorSettings.color4(),
        )
        if (colorCount <= 1) return colors[0]

        val gradMode = HitColorSettings.gradientMode()
        return when (gradMode) {
            GradientMode.STATIC -> colors[0]
            GradientMode.FLUID -> {
                val t = ((globalTick * HitColorSettings.animSpeed() * 0.02f) % 1f)
                lerpColors(colors, colorCount, t)
            }
            GradientMode.CHROMA -> {
                val t = ((globalTick * HitColorSettings.animSpeed() * 0.03f) % 1f)
                lerpColors(colors, colorCount, t)
            }
        }
    }

    private fun restoreVanilla() {
        val client = MinecraftClient.getInstance()
        val overlayTexture = client.gameRenderer?.overlayTexture ?: return
        try {
            val accessor = overlayTexture as dev.hypnosia.mixin.OverlayTextureAccessor
            val nativeImageBackedTexture = accessor.`hypnosia$getTexture`()
            val image = nativeImageBackedTexture.image ?: return

            for (v in 0..15) {
                for (u in 0..15) {
                    val pixel = if (v < 8) {
                        // Vanilla red: alpha=0xB2, R=0xFF, G=0, B=0
                        (0xB2 shl 24) or (0xFF shl 16)
                    } else {
                        // Vanilla white: alpha varies by u
                        val alpha = ((1.0f - u / 15.0f * 0.75f) * 255f).toInt()
                        (alpha shl 24) or (0xFF shl 16) or (0xFF shl 8) or 0xFF
                    }
                    image.setColorArgb(u, v, pixel)
                }
            }
            nativeImageBackedTexture.upload()
        } catch (_: Exception) {
        }
    }

    private fun rewriteOverlayColor(rgb: Int) {
        val client = MinecraftClient.getInstance()
        val overlayTexture = client.gameRenderer?.overlayTexture ?: return
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF

        try {
            val accessor = overlayTexture as dev.hypnosia.mixin.OverlayTextureAccessor
            val nativeImageBackedTexture = accessor.`hypnosia$getTexture`()
            val image = nativeImageBackedTexture.image ?: return

            for (v in 0..15) {
                for (u in 0..15) {
                    val alpha = if (v < 8) {
                        0xB2
                    } else {
                        ((1.0f - u / 15.0f * 0.75f) * 255f).toInt()
                    }
                    val pixel = (alpha shl 24) or (r shl 16) or (g shl 8) or b
                    image.setColorArgb(u, v, pixel)
                }
            }
            nativeImageBackedTexture.upload()
        } catch (_: Exception) {
        }
    }

    private fun lerpColors(colors: IntArray, count: Int, t: Float): Int {
        val segT = t * (count - 1)
        val idx = segT.toInt().coerceIn(0, count - 2)
        val frac = segT - idx
        val c1 = colors[idx]
        val c2 = colors[idx + 1]
        val r = ((c1 shr 16 and 0xFF) + (((c2 shr 16 and 0xFF) - (c1 shr 16 and 0xFF)) * frac)).toInt()
        val g = ((c1 shr 8 and 0xFF) + (((c2 shr 8 and 0xFF) - (c1 shr 8 and 0xFF)) * frac)).toInt()
        val b = ((c1 and 0xFF) + (((c2 and 0xFF) - (c1 and 0xFF)) * frac)).toInt()
        return (r shl 16) or (g shl 8) or b
    }
}
