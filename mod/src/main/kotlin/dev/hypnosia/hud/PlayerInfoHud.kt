package dev.hypnosia.hud

import dev.hypnosia.HypnosiaClient
import dev.hypnosia.hud.HudRenderSupport.WHITE
import dev.hypnosia.hud.HudRenderSupport.anchorX
import dev.hypnosia.hud.HudRenderSupport.anchorY
import dev.hypnosia.hud.HudRenderSupport.fixedScale
import dev.hypnosia.hud.HudRenderSupport.icon
import dev.hypnosia.hud.HudRenderSupport.panel
import dev.hypnosia.hud.HudRenderSupport.text
import dev.hypnosia.ui.render.FigmaTextRenderer
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.client.render.RenderTickCounter
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

object PlayerInfoHud {
    private enum class Part {
        BPS,
        TPS,
        CORDS,
    }

    private val drag = HudDragController(HudModuleSettings.Module.PLAYER_INFO)
    private val separatedDrag = SeparatedDragController()
    private var smoothedBps = 0.0
    private val PlayerInfoText = FigmaTextRenderer.FigmaTextStyle(
        font = FigmaTextRenderer.Font.Main,
        size = 16.0f,
        lineHeight = 19.0f,
        baselineOffset = 0.0f,
    )

    fun register() {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.SUBTITLES,
            Identifier.of(HypnosiaClient.MOD_ID, "player_info_hud"),
            ::render,
        )
    }

    fun tickDrag(client: MinecraftClient) {
        if (!HudModuleSettings.isEnabled(HudModuleSettings.Module.PLAYER_INFO)) return
        val state = HudModuleSettings.state(HudModuleSettings.Module.PLAYER_INFO)
        if (state.version == HudModuleSettings.Version.V1) {
            separatedDrag.tick(client)
        } else {
            val size = size(client)
            drag.tick(client, size.first, size.second)
        }
    }

    private fun render(context: DrawContext, tickCounter: RenderTickCounter) {
        val client = MinecraftClient.getInstance()
        if (client.currentScreen is dev.hypnosia.ui.HypnosiaHomeV2Screen) return
        val player = client.player ?: return
        if (!HudModuleSettings.isEnabled(HudModuleSettings.Module.PLAYER_INFO)) return

        val velocity = player.velocity
        val bps = sqrt(velocity.x * velocity.x + velocity.z * velocity.z) * 20.0
        smoothedBps = smoothedBps * 0.82 + bps * 0.18

        val scale = fixedScale(client)
        val state = HudModuleSettings.state(HudModuleSettings.Module.PLAYER_INFO)

        context.matrices.pushMatrix()
        context.matrices.scale(scale, scale)
        if (state.version == HudModuleSettings.Version.V1) {
            drawSeparated(context, client, state)
        } else {
            val size = size(client)
            val x = anchorX(client.window.framebufferWidth.toFloat(), size.first, state.x)
            val y = anchorY(client.window.framebufferHeight.toFloat(), size.second, state.y)
            drawCombined(context, x, y, client, state)
        }
        context.matrices.popMatrix()
    }

    private fun size(client: MinecraftClient? = MinecraftClient.getInstance()): Pair<Float, Float> {
        val state = HudModuleSettings.state(HudModuleSettings.Module.PLAYER_INFO)
        val parts = activeParts(state)
        if (parts.isEmpty()) return 1.0f to 1.0f

        val width = if (state.version == HudModuleSettings.Version.V1) {
            parts.sumOf { partWidth(it, client).toDouble() }.toFloat() + PART_GAP * (parts.size - 1)
        } else {
            combinedWidth(parts, client)
        }
        return width to 34.0f
    }

    private fun drawSeparated(
        context: DrawContext,
        client: MinecraftClient,
        state: HudModuleSettings.State,
    ) {
        activeParts(state).forEach { part ->
            val width = partWidth(part, client)
            val position = partPosition(client, state, part, width)
            val x = position.first
            val y = position.second
            panel(context, x, y, width, 34.0f)
            drawPartContent(context, x, y, client, part)
        }
    }

    private fun drawCombined(
        context: DrawContext,
        x: Float,
        y: Float,
        client: MinecraftClient,
        state: HudModuleSettings.State,
    ) {
        val parts = activeParts(state)
        if (parts.isEmpty()) return
        panel(context, x, y, combinedWidth(parts, client), 34.0f)
        if (parts == listOf(Part.BPS, Part.TPS, Part.CORDS)) {
            drawMetricContent(context, x, y, "rocket_01.png", "BPS", bpsValue(), 29.0f, 64.0f, valueY = 1.0f, valueHeight = 32.0f)
            drawMetricContent(context, x + 92.0f, y, "chart_03.png", "TPS", "20.0", 29.0f, 63.0f)
            drawCordsContent(context, x, y, coordText(client), separated = false)
            return
        }
        var cursor = x
        parts.forEach { part ->
            cursor += drawPartContent(context, cursor, y, client, part)
        }
    }

    private fun drawPartContent(context: DrawContext, x: Float, y: Float, client: MinecraftClient, part: Part): Float {
        return when (part) {
            Part.BPS -> {
                drawBpsContent(context, x, y)
                partWidth(part, client)
            }
            Part.TPS -> {
                drawTpsContent(context, x, y)
                partWidth(part, client)
            }
            Part.CORDS -> {
                drawCordsContent(context, x, y, coordText(client), separated = true)
                cordsWidth(client)
            }
        }
    }

    private fun drawBpsContent(context: DrawContext, x: Float, y: Float) {
        drawMetricContent(
            context = context,
            x = x,
            y = y,
            iconName = "rocket_01.png",
            label = "BPS",
            value = bpsValue(),
            labelX = 29.0f,
            valueX = 63.0f,
        )
    }

    private fun drawTpsContent(context: DrawContext, x: Float, y: Float) {
        drawMetricContent(
            context = context,
            x = x,
            y = y,
            iconName = "chart_03.png",
            label = "TPS",
            value = "20.0",
            labelX = 29.0f,
            valueX = 63.0f,
        )
    }

    private fun drawMetricContent(
        context: DrawContext,
        x: Float,
        y: Float,
        iconName: String,
        label: String,
        value: String,
        labelX: Float,
        valueX: Float,
        labelY: Float = 9.0f,
        labelHeight: Float = 16.0f,
        valueY: Float = 9.0f,
        valueHeight: Float = 16.0f,
    ) {
        icon(context, iconName, x + 5.0f, y + 5.0f, 24.0f, 24.0f, WHITE)
        text(context, label, x + labelX, y + labelY, 48.0f, labelHeight, PlayerInfoText, WHITE)
        text(context, value, x + valueX, y + valueY, textWidth(value, PlayerInfoText), valueHeight, PlayerInfoText, WHITE)
    }

    private fun drawCordsContent(context: DrawContext, x: Float, y: Float, value: String, separated: Boolean) {
        val iconX = if (separated) 5.0f else 188.0f
        icon(context, "gps_01.png", x + iconX, y + 5.0f, 24.0f, 24.0f, WHITE)
        val textX = if (separated) 32.0f else 215.0f
        text(context, value, x + textX, y + 9.0f, textWidth(value), 16.0f, PlayerInfoText, WHITE)
    }

    private fun activeParts(state: HudModuleSettings.State): List<Part> = buildList {
        if (state.playerInfoBps) add(Part.BPS)
        if (state.playerInfoTps) add(Part.TPS)
        if (state.playerInfoCords) add(Part.CORDS)
    }

    private fun partWidth(part: Part, client: MinecraftClient?): Float {
        return when (part) {
            Part.BPS -> max(93.0f, 63.0f + textWidth(bpsValue()) + 5.0f)
            Part.TPS -> max(93.0f, 63.0f + textWidth("20.0") + 5.0f)
            Part.CORDS -> cordsWidth(client)
        }
    }

    private fun combinedWidth(parts: List<Part>, client: MinecraftClient?): Float {
        if (parts == listOf(Part.BPS, Part.TPS, Part.CORDS)) {
            return max(263.0f, 215.0f + textWidth(coordText(client)) + 5.0f)
        }
        return parts.sumOf { partWidth(it, client).toDouble() }.toFloat()
    }

    private fun bpsValue(): String =
        String.format(Locale.US, "%.1f", smoothedBps)

    private fun coordText(client: MinecraftClient?): String {
        val player = client?.player ?: return "0, 0, 0"
        return "${player.blockX}, ${player.blockY}, ${player.blockZ}"
    }

    private fun cordsWidth(client: MinecraftClient?): Float {
        return max(84.0f, 32.0f + textWidth(coordText(client)) + 5.0f)
    }

    private fun partPosition(
        client: MinecraftClient,
        state: HudModuleSettings.State,
        part: Part,
        width: Float,
    ): Pair<Float, Float> {
        val screenW = client.window.framebufferWidth.toFloat()
        val screenH = client.window.framebufferHeight.toFloat()
        val xNorm = when (part) {
            Part.BPS -> state.playerInfoBpsX
            Part.TPS -> state.playerInfoTpsX
            Part.CORDS -> state.playerInfoCordsX
        }
        val yNorm = when (part) {
            Part.BPS -> state.playerInfoBpsY
            Part.TPS -> state.playerInfoTpsY
            Part.CORDS -> state.playerInfoCordsY
        }
        return anchorX(screenW, width, xNorm) to anchorY(screenH, 34.0f, yNorm)
    }

    private fun settingPart(part: Part): HudModuleSettings.PlayerInfoPart =
        when (part) {
            Part.BPS -> HudModuleSettings.PlayerInfoPart.BPS
            Part.TPS -> HudModuleSettings.PlayerInfoPart.TPS
            Part.CORDS -> HudModuleSettings.PlayerInfoPart.CORDS
        }

    private fun textWidth(value: String): Float =
        textWidth(value, PlayerInfoText)

    private fun textWidth(value: String, style: FigmaTextRenderer.FigmaTextStyle): Float =
        FigmaTextRenderer.width(value, style)

    private const val PART_GAP = 8.0f

    private class SeparatedDragController {
        private var active: DragState? = null
        private var wasMouseDown = false

        fun tick(client: MinecraftClient) {
            val window = client.window
            val state = HudModuleSettings.state(HudModuleSettings.Module.PLAYER_INFO)
            val isChatOpen = client.currentScreen is ChatScreen
            val isMouseDown = GLFW.glfwGetMouseButton(window.handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
            if (!isChatOpen || client.player == null || !HudModuleSettings.isEnabled(HudModuleSettings.Module.PLAYER_INFO)) {
                if (active != null) HudModuleSettings.saveNow()
                active = null
                wasMouseDown = isMouseDown
                return
            }

            val fixedScale = fixedScale(client)
            val screenW = window.framebufferWidth.toFloat()
            val screenH = window.framebufferHeight.toFloat()
            val mouseX = HudRenderSupport.mouseFixedX(client, fixedScale)
            val mouseY = HudRenderSupport.mouseFixedY(client, fixedScale)
            val parts = PlayerInfoHud.activeParts(state)

            if (isMouseDown && !wasMouseDown) {
                active = parts.asReversed().firstNotNullOfOrNull { part ->
                    val width = PlayerInfoHud.partWidth(part, client)
                    val (x, y) = PlayerInfoHud.partPosition(client, state, part, width)
                    if (mouseX in x..(x + width) && mouseY in y..(y + 34.0f)) {
                        DragState(part, mouseX - x, mouseY - y, width)
                    } else {
                        null
                    }
                }
            } else if (isMouseDown) {
                active?.let { drag ->
                    val maxX = (screenW - drag.width).coerceAtLeast(1.0f)
                    val maxY = (screenH - 34.0f).coerceAtLeast(1.0f)
                    val snappedX = HudRenderSupport.snapPixel(mouseX - drag.offsetX).coerceIn(0.0f, maxX)
                    val snappedY = HudRenderSupport.snapPixel(mouseY - drag.offsetY).coerceIn(0.0f, maxY)
                    HudModuleSettings.setPlayerInfoPartPosition(
                        PlayerInfoHud.settingPart(drag.part),
                        snappedX / maxX,
                        snappedY / maxY,
                        persist = false,
                    )
                }
            } else {
                if (active != null) HudModuleSettings.saveNow()
                active = null
            }

            wasMouseDown = isMouseDown
        }

        private data class DragState(
            val part: Part,
            val offsetX: Float,
            val offsetY: Float,
            val width: Float,
        )
    }
}
