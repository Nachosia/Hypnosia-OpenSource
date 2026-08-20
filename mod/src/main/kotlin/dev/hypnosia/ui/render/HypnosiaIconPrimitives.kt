package dev.hypnosia.ui.render

import dev.hypnosia.ui.component.HypnosiaCategory
import net.minecraft.client.gui.DrawContext
import kotlin.math.min

object HypnosiaIconPrimitives {
    fun drawCategory(
        context: DrawContext,
        category: HypnosiaCategory,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        color: Int,
    ) {
        when (category) {
            HypnosiaCategory.Home -> drawHome(context, x, y, width, height, color)
            HypnosiaCategory.Visuals -> drawVisuals(context, x, y, width, height, color)
            HypnosiaCategory.World -> drawWorld(context, x, y, width, height, color)
            HypnosiaCategory.Client -> drawClient(context, x, y, width, height, color)
            HypnosiaCategory.Hud -> drawHud(context, x, y, width, height, color)
            HypnosiaCategory.Other -> drawOther(context, x, y, width, height, color)
        }
    }

    fun drawByFileName(
        context: DrawContext,
        fileName: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        color: Int,
    ) {
        when (fileName.removeSuffix(".png")) {
            "home" -> drawHome(context, x, y, width, height, color)
            "visuals" -> drawVisuals(context, x, y, width, height, color)
            "world" -> drawWorld(context, x, y, width, height, color)
            "client" -> drawClient(context, x, y, width, height, color)
            "hud" -> drawHud(context, x, y, width, height, color)
            "other" -> drawOther(context, x, y, width, height, color)
            "search" -> drawSearch(context, x, y, width, height, color)
            "settings" -> drawSettings(context, x, y, width, height, color)
            "plug_socket" -> drawPlug(context, x, y, width, height, color)
            "black_hole" -> drawBlackHole(context, x, y, width, height, color)
            "color_preview" -> drawColorPreview(context, x, y, width, height, color)
            else -> drawFallback(context, x, y, width, height, color)
        }
    }

    fun drawPlug(context: DrawContext, x: Float, y: Float, width: Float, height: Float, color: Int) {
        val s = min(width, height)
        val ox = x + (width - s) * 0.5f
        val oy = y + (height - s) * 0.5f
        box(context, ox + s * 0.34f, oy + s * 0.08f, s * 0.08f, s * 0.22f, 1.0f, color)
        box(context, ox + s * 0.58f, oy + s * 0.08f, s * 0.08f, s * 0.22f, 1.0f, color)
        box(context, ox + s * 0.27f, oy + s * 0.26f, s * 0.46f, s * 0.26f, s * 0.07f, color)
        box(context, ox + s * 0.42f, oy + s * 0.50f, s * 0.16f, s * 0.24f, 1.0f, color)
        box(context, ox + s * 0.36f, oy + s * 0.70f, s * 0.28f, s * 0.08f, 1.0f, color)
    }

    fun drawSettings(context: DrawContext, x: Float, y: Float, width: Float, height: Float, color: Int) {
        val s = min(width, height)
        val ox = x + (width - s) * 0.5f
        val oy = y + (height - s) * 0.5f
        val cx = ox + s * 0.5f
        val cy = oy + s * 0.5f
        box(context, cx - s * 0.08f, oy + s * 0.05f, s * 0.16f, s * 0.28f, 1.0f, color)
        box(context, cx - s * 0.08f, oy + s * 0.67f, s * 0.16f, s * 0.28f, 1.0f, color)
        box(context, ox + s * 0.05f, cy - s * 0.08f, s * 0.28f, s * 0.16f, 1.0f, color)
        box(context, ox + s * 0.67f, cy - s * 0.08f, s * 0.28f, s * 0.16f, 1.0f, color)
        box(context, ox + s * 0.24f, oy + s * 0.24f, s * 0.52f, s * 0.52f, s * 0.16f, color)
        box(context, ox + s * 0.40f, oy + s * 0.40f, s * 0.20f, s * 0.20f, s * 0.10f, 0xFF0D0D0D.toInt())
    }

    fun drawSearch(context: DrawContext, x: Float, y: Float, width: Float, height: Float, color: Int) {
        val s = min(width, height)
        val ox = x + (width - s) * 0.5f
        val oy = y + (height - s) * 0.5f
        HypnosiaRenderUtils.drawFigmaBox(
            context = context,
            x = ox + s * 0.18f,
            y = oy + s * 0.15f,
            width = s * 0.48f,
            height = s * 0.48f,
            radius = s * 0.24f,
            bgColor = 0x00000000,
            strokeColor = color,
            strokeThickness = s * 0.09f,
        )
        box(context, ox + s * 0.60f, oy + s * 0.62f, s * 0.28f, s * 0.09f, s * 0.045f, color)
    }

    private fun drawHome(context: DrawContext, x: Float, y: Float, width: Float, height: Float, color: Int) {
        val s = min(width, height)
        val ox = x + (width - s) * 0.5f
        val oy = y + (height - s) * 0.5f
        box(context, ox + s * 0.20f, oy + s * 0.46f, s * 0.60f, s * 0.38f, s * 0.06f, color)
        box(context, ox + s * 0.30f, oy + s * 0.30f, s * 0.40f, s * 0.10f, s * 0.03f, color)
        box(context, ox + s * 0.24f, oy + s * 0.36f, s * 0.52f, s * 0.10f, s * 0.03f, color)
        box(context, ox + s * 0.44f, oy + s * 0.62f, s * 0.12f, s * 0.22f, 1.0f, 0xFF0D0D0D.toInt())
    }

    private fun drawVisuals(context: DrawContext, x: Float, y: Float, width: Float, height: Float, color: Int) {
        val s = min(width, height)
        val ox = x + (width - s) * 0.5f
        val oy = y + (height - s) * 0.5f
        box(context, ox + s * 0.14f, oy + s * 0.26f, s * 0.72f, s * 0.11f, s * 0.055f, color)
        box(context, ox + s * 0.24f, oy + s * 0.46f, s * 0.52f, s * 0.11f, s * 0.055f, color)
        box(context, ox + s * 0.34f, oy + s * 0.66f, s * 0.32f, s * 0.11f, s * 0.055f, color)
    }

    private fun drawWorld(context: DrawContext, x: Float, y: Float, width: Float, height: Float, color: Int) {
        val s = min(width, height)
        val ox = x + (width - s) * 0.5f
        val oy = y + (height - s) * 0.5f
        HypnosiaRenderUtils.drawFigmaBox(context, ox + s * 0.13f, oy + s * 0.13f, s * 0.74f, s * 0.74f, s * 0.37f, 0x00000000, color, s * 0.08f)
        box(context, ox + s * 0.18f, oy + s * 0.47f, s * 0.64f, s * 0.07f, s * 0.035f, color)
        box(context, ox + s * 0.47f, oy + s * 0.18f, s * 0.07f, s * 0.64f, s * 0.035f, color)
    }

    private fun drawClient(context: DrawContext, x: Float, y: Float, width: Float, height: Float, color: Int) {
        val s = min(width, height)
        val ox = x + (width - s) * 0.5f
        val oy = y + (height - s) * 0.5f
        HypnosiaRenderUtils.drawFigmaBox(context, ox + s * 0.18f, oy + s * 0.16f, s * 0.64f, s * 0.48f, s * 0.07f, 0x00000000, color, s * 0.08f)
        box(context, ox + s * 0.40f, oy + s * 0.70f, s * 0.20f, s * 0.07f, s * 0.035f, color)
        box(context, ox + s * 0.32f, oy + s * 0.80f, s * 0.36f, s * 0.07f, s * 0.035f, color)
    }

    private fun drawHud(context: DrawContext, x: Float, y: Float, width: Float, height: Float, color: Int) {
        val s = min(width, height)
        val ox = x + (width - s) * 0.5f
        val oy = y + (height - s) * 0.5f
        box(context, ox + s * 0.18f, oy + s * 0.18f, s * 0.26f, s * 0.26f, s * 0.05f, color)
        box(context, ox + s * 0.56f, oy + s * 0.18f, s * 0.26f, s * 0.26f, s * 0.05f, color)
        box(context, ox + s * 0.18f, oy + s * 0.56f, s * 0.26f, s * 0.26f, s * 0.05f, color)
        box(context, ox + s * 0.56f, oy + s * 0.56f, s * 0.26f, s * 0.26f, s * 0.05f, color)
    }

    private fun drawOther(context: DrawContext, x: Float, y: Float, width: Float, height: Float, color: Int) {
        val s = min(width, height)
        val ox = x + (width - s) * 0.5f
        val oy = y + (height - s) * 0.5f
        box(context, ox + s * 0.20f, oy + s * 0.23f, s * 0.18f, s * 0.18f, s * 0.09f, color)
        box(context, ox + s * 0.62f, oy + s * 0.41f, s * 0.18f, s * 0.18f, s * 0.09f, color)
        box(context, ox + s * 0.24f, oy + s * 0.66f, s * 0.18f, s * 0.18f, s * 0.09f, color)
        box(context, ox + s * 0.36f, oy + s * 0.34f, s * 0.30f, s * 0.06f, s * 0.03f, color)
        box(context, ox + s * 0.39f, oy + s * 0.61f, s * 0.26f, s * 0.06f, s * 0.03f, color)
    }

    private fun drawBlackHole(context: DrawContext, x: Float, y: Float, width: Float, height: Float, color: Int) {
        val s = min(width, height)
        val ox = x + (width - s) * 0.5f
        val oy = y + (height - s) * 0.5f
        HypnosiaRenderUtils.drawFigmaBox(context, ox + s * 0.10f, oy + s * 0.10f, s * 0.80f, s * 0.80f, s * 0.40f, 0x00000000, color, s * 0.08f)
        HypnosiaRenderUtils.drawFigmaBox(context, ox + s * 0.27f, oy + s * 0.27f, s * 0.46f, s * 0.46f, s * 0.23f, color)
        HypnosiaRenderUtils.drawFigmaBox(context, ox + s * 0.40f, oy + s * 0.40f, s * 0.20f, s * 0.20f, s * 0.10f, 0xFF0D0D0D.toInt())
    }

    private fun drawColorPreview(context: DrawContext, x: Float, y: Float, width: Float, height: Float, color: Int) {
        HypnosiaRenderUtils.drawFigmaBox(context, x, y, width, height, min(width, height) * 0.5f, color)
    }

    private fun drawFallback(context: DrawContext, x: Float, y: Float, width: Float, height: Float, color: Int) {
        HypnosiaRenderUtils.drawFigmaBox(context, x, y, width, height, min(width, height) * 0.18f, color)
    }

    private fun box(context: DrawContext, x: Float, y: Float, width: Float, height: Float, radius: Float, color: Int) {
        if (width <= 0.0f || height <= 0.0f || ((color ushr 24) and 0xFF) == 0) {
            return
        }
        HypnosiaRenderUtils.drawFigmaBox(context, x, y, width, height, radius, color)
    }
}
