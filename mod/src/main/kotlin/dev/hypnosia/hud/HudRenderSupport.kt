package dev.hypnosia.hud

import dev.hypnosia.HypnosiaClient
import dev.hypnosia.ui.render.FigmaTextRenderer
import dev.hypnosia.ui.render.HighQualityTextRenderer
import dev.hypnosia.ui.render.HypnosiaRenderUtils
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.util.Identifier
import kotlin.math.roundToInt

object HudRenderSupport {
    const val BG: Int = 0xFF0D0D0D.toInt()
    const val STROKE: Int = 0xFF272727.toInt()
    const val WHITE: Int = 0xFFFFFFFF.toInt()
    const val TEXT: Int = WHITE
    const val MUTED: Int = 0xFF8E8E98.toInt()

    val Text16 = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 16.0f, 19.0f, baselineOffset = 0.0f)
    val Text18 = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 18.0f, 22.0f, baselineOffset = 2.0f)
    val Text20 = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 20.0f, 24.0f, baselineOffset = 2.0f)
    val Text24 = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 24.0f, 32.0f, baselineOffset = 3.0f)

    fun fixedScale(client: MinecraftClient): Float =
        1.0f / client.window.scaleFactor.toFloat().coerceAtLeast(1.0f)

    fun mouseFixedX(client: MinecraftClient, fixedScale: Float): Float {
        val window = client.window
        val mouseGuiX = (client.mouse.x * window.scaledWidth / window.width).toFloat()
        return mouseGuiX / fixedScale
    }

    fun mouseFixedY(client: MinecraftClient, fixedScale: Float): Float {
        val window = client.window
        val mouseGuiY = (client.mouse.y * window.scaledHeight / window.height).toFloat()
        return mouseGuiY / fixedScale
    }

    fun anchorX(screenW: Float, elementW: Float, normalized: Float): Float =
        snapPixel((screenW - elementW) * normalized)

    fun anchorY(screenH: Float, elementH: Float, normalized: Float): Float =
        snapPixel((screenH - elementH) * normalized)

    fun snapPixel(value: Float): Float =
        value.roundToInt().toFloat()

    fun panel(context: DrawContext, x: Float, y: Float, width: Float, height: Float, radius: Float = 10.0f) {
        HypnosiaRenderUtils.drawFigmaBox(context, x, y, width, height, radius, BG, STROKE, 1.0f)
    }

    fun icon(context: DrawContext, name: String, x: Float, y: Float, width: Float, height: Float, tint: Int = WHITE) {
        val id = Identifier.of(HypnosiaClient.MOD_ID, "textures/gui/icons/$name")
        HypnosiaRenderUtils.drawIconTexture(context, id, x, y, width, height, tint)
    }

    fun text(
        context: DrawContext,
        value: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        style: FigmaTextRenderer.FigmaTextStyle = Text16,
        color: Int = TEXT,
        align: FigmaTextRenderer.HorizontalAlign = FigmaTextRenderer.HorizontalAlign.Left,
        valign: FigmaTextRenderer.VerticalAlign = FigmaTextRenderer.VerticalAlign.Center,
    ) {
        FigmaTextRenderer.drawInBox(context, value, x, y, width, height, color, style, align, valign)
    }

    fun marqueeText(
        context: DrawContext,
        value: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        style: FigmaTextRenderer.FigmaTextStyle = Text16,
        color: Int = TEXT,
    ) {
        val textWidth = FigmaTextRenderer.width(value, style)
        if (textWidth <= width) {
            text(context, value, x, y, width, height, style, color)
            return
        }

        val overflow = textWidth - width + 12.0f
        val pause = 900L
        val scrollMs = (overflow / 28.0f * 1000.0f).roundToInt().coerceAtLeast(600)
        val cycle = pause + scrollMs + pause
        val phase = (System.currentTimeMillis() % cycle).toFloat()
        val offset = when {
            phase < pause -> 0.0f
            phase < pause + scrollMs -> overflow * ((phase - pause) / scrollMs.toFloat())
            else -> overflow
        }

        FigmaTextRenderer.drawInBox(
            context = context,
            text = value,
            x = x - offset,
            y = y,
            width = textWidth + 2.0f,
            height = height,
            color = color,
            style = style,
            horizontalAlign = FigmaTextRenderer.HorizontalAlign.Left,
            verticalAlign = FigmaTextRenderer.VerticalAlign.Center,
            fade = HighQualityTextRenderer.TextFade(
                x = x - TEXT_FADE_EDGE_PAD,
                y = y,
                width = width + TEXT_FADE_EDGE_PAD * 2.0f,
                height = height,
                fadeWidth = 12.0f,
            ),
        )
    }

    private const val TEXT_FADE_EDGE_PAD = 3.0f
}
