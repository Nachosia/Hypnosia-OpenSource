package dev.hypnosia.hud

import dev.hypnosia.HypnosiaClient
import dev.hypnosia.hud.HudRenderSupport.WHITE
import dev.hypnosia.hud.HudRenderSupport.anchorX
import dev.hypnosia.hud.HudRenderSupport.anchorY
import dev.hypnosia.hud.HudRenderSupport.fixedScale
import dev.hypnosia.hud.HudRenderSupport.icon
import dev.hypnosia.hud.HudRenderSupport.marqueeText
import dev.hypnosia.hud.HudRenderSupport.panel
import dev.hypnosia.hud.HudRenderSupport.text
import dev.hypnosia.ui.render.FigmaTextRenderer
import dev.hypnosia.ui.render.HypnosiaRenderUtils
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.RenderTickCounter
import net.minecraft.util.Identifier

object HotKeyHud {
    private const val WIDTH = 218.0f
    private val drag = HudDragController(HudModuleSettings.Module.HOTKEYS)
    private val HeaderText = FigmaTextRenderer.FigmaTextStyle(
        font = FigmaTextRenderer.Font.Main,
        size = 16.0f,
        lineHeight = 19.0f,
        baselineOffset = 0.0f,
    )
    private val RowText = FigmaTextRenderer.FigmaTextStyle(
        font = FigmaTextRenderer.Font.Main,
        size = 16.0f,
        lineHeight = 19.0f,
        baselineOffset = 0.0f,
    )

    fun register() {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.SUBTITLES,
            Identifier.of(HypnosiaClient.MOD_ID, "hotkey_hud"),
            ::render,
        )
    }

    fun tickDrag(client: MinecraftClient) {
        val rows = rows()
        drag.tick(client, WIDTH, height(rows.size))
    }

    private fun render(context: DrawContext, tickCounter: RenderTickCounter) {
        val client = MinecraftClient.getInstance()
        if (client.currentScreen is dev.hypnosia.ui.HypnosiaHomeV2Screen) return
        if (client.player == null || !HudModuleSettings.isEnabled(HudModuleSettings.Module.HOTKEYS)) return
        val state = HudModuleSettings.state(HudModuleSettings.Module.HOTKEYS)
        val rows = rows()
        val height = height(rows.size)
        val scale = fixedScale(client)
        val x = anchorX(client.window.framebufferWidth.toFloat(), WIDTH, state.x)
        val headerY = anchorY(client.window.framebufferHeight.toFloat(), 34.0f, state.y)
        val y = topY(headerY, state.version, rows.size)

        context.matrices.pushMatrix()
        context.matrices.scale(scale, scale)
        drawFrame(context, x, y, state.version, height)
        drawHeader(context, x, headerY, state.version)
        rows.forEachIndexed { index, row ->
            drawRow(context, row, x, rowY(headerY, state.version, index, rows.size), state.version)
        }
        context.matrices.popMatrix()
    }

    private fun drawFrame(context: DrawContext, x: Float, y: Float, version: HudModuleSettings.Version, height: Float) {
        if (isFramed(version)) {
            panel(context, x, y, WIDTH, height)
        }
    }

    private fun drawHeader(context: DrawContext, x: Float, headerY: Float, version: HudModuleSettings.Version) {
        if (isFramed(version)) {
            HypnosiaRenderUtils.drawFigmaBox(context, x + 1.0f, headerY + 1.0f, WIDTH - 2.0f, 31.0f, 9.0f, 0xFF191919.toInt())
        } else {
            panel(context, x, headerY, WIDTH, 34.0f)
        }
        text(context, "HotKey", x + 6.0f, headerY + 1.0f, 182.0f, 32.0f, HeaderText, WHITE)
        val iconX = if (isFramed(version)) 186.0f else 188.0f
        icon(context, "file_edit.png", x + iconX, headerY + 5.0f, 24.0f, 24.0f, WHITE)
    }

    private fun drawRow(context: DrawContext, row: Row, x: Float, y: Float, version: HudModuleSettings.Version) {
        if (!isFramed(version)) {
            panel(context, x, y, WIDTH, 24.0f, 7.0f)
        }
        marqueeText(context, row.title, x + 8.0f, y + 1.0f, 153.0f, 22.0f, RowText, WHITE)
        HypnosiaRenderUtils.drawFigmaBox(context, x + 164.0f, y + 3.0f, 1.0f, 18.0f, 0.0f, 0xFF6D6D6D.toInt())
        text(
            context = context,
            value = row.key,
            x = x + 168.0f,
            y = y + 1.0f,
            width = 42.0f,
            height = 22.0f,
            style = RowText,
            color = WHITE,
            align = FigmaTextRenderer.HorizontalAlign.Center,
        )
    }

    private fun rows(): List<Row> =
        ModuleHotkeys.activeBindings().map { Row(it.title, ModuleHotkeys.displayKey(it.keyCode)) }

    private fun topY(headerY: Float, version: HudModuleSettings.Version, rowCount: Int): Float {
        if (version != HudModuleSettings.Version.V3 && version != HudModuleSettings.Version.V4) return headerY
        val rowsHeight = if (rowCount > 0) 5.0f + rowBlockHeight(rowCount) else 0.0f
        return headerY - rowsHeight
    }

    private fun rowY(headerY: Float, version: HudModuleSettings.Version, index: Int, rowCount: Int): Float {
        return when (version) {
            HudModuleSettings.Version.V1, HudModuleSettings.Version.V2 -> headerY + 39.0f + index * 29.0f
            HudModuleSettings.Version.V3, HudModuleSettings.Version.V4 -> headerY - 5.0f - rowBlockHeight(rowCount) + index * 29.0f
        }
    }

    private fun isFramed(version: HudModuleSettings.Version): Boolean =
        version == HudModuleSettings.Version.V2 || version == HudModuleSettings.Version.V4

    private fun height(rowCount: Int): Float =
        34.0f + if (rowCount > 0) 5.0f + rowBlockHeight(rowCount) else 0.0f

    private fun rowBlockHeight(rowCount: Int): Float =
        if (rowCount <= 0) 0.0f else rowCount * 24.0f + (rowCount - 1) * 5.0f

    private data class Row(val title: String, val key: String)
}
