package dev.hypnosia.hud

import dev.hypnosia.HypnosiaClient
import dev.hypnosia.hud.HudRenderSupport.Text16
import dev.hypnosia.hud.HudRenderSupport.WHITE
import dev.hypnosia.hud.HudRenderSupport.anchorX
import dev.hypnosia.hud.HudRenderSupport.anchorY
import dev.hypnosia.hud.HudRenderSupport.fixedScale
import dev.hypnosia.hud.HudRenderSupport.icon
import dev.hypnosia.hud.HudRenderSupport.panel
import dev.hypnosia.hud.HudRenderSupport.text
import dev.hypnosia.ui.render.HypnosiaRenderUtils
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.RenderTickCounter
import net.minecraft.item.ItemStack
import net.minecraft.util.Identifier

object InventoryHud {
    private const val WIDTH = 193.0f
    private const val HEIGHT = 96.0f
    private val drag = HudDragController(HudModuleSettings.Module.INVENTORY)

    fun register() {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.SUBTITLES,
            Identifier.of(HypnosiaClient.MOD_ID, "inventory_hud"),
            ::render,
        )
    }

    fun tickDrag(client: MinecraftClient) {
        drag.tick(client, WIDTH, HEIGHT)
    }

    private fun render(context: DrawContext, tickCounter: RenderTickCounter) {
        val client = MinecraftClient.getInstance()
        if (client.currentScreen is dev.hypnosia.ui.HypnosiaHomeV2Screen) return
        if (client.player == null || !HudModuleSettings.isEnabled(HudModuleSettings.Module.INVENTORY)) return

        val state = HudModuleSettings.state(HudModuleSettings.Module.INVENTORY)
        val scale = fixedScale(client)
        val x = anchorX(client.window.framebufferWidth.toFloat(), WIDTH, state.x)
        val y = anchorY(client.window.framebufferHeight.toFloat(), HEIGHT, state.y)

        context.matrices.pushMatrix()
        context.matrices.scale(scale, scale)
        panel(context, x, y, WIDTH, HEIGHT)
        if (state.version == HudModuleSettings.Version.V1) {
            drawHeader(context, x, y)
            drawSlots(context, client, x + 8.0f, y + 36.0f)
        } else {
            drawSlots(context, client, x + 8.0f, y + 4.0f)
            drawHeader(context, x, y + 63.0f)
        }
        context.matrices.popMatrix()
    }

    private fun drawHeader(context: DrawContext, x: Float, y: Float) {
        HypnosiaRenderUtils.drawFigmaBox(context, x + 1.0f, y + 1.0f, WIDTH - 2.0f, 31.0f, 9.0f, 0xFF191919.toInt())
        text(context, "Inventory", x + 8.0f, y + 1.0f, 73.0f, 32.0f, Text16, WHITE)
        icon(context, "backpack_03.png", x + 161.0f, y + 4.0f, 24.0f, 24.0f, WHITE)
    }

    private fun drawSlots(context: DrawContext, client: MinecraftClient, x: Float, y: Float) {
        val inventory = client.player?.inventory ?: return
        repeat(3) { row ->
            repeat(9) { col ->
                val slotIndex = 9 + row * 9 + col
                val sx = x + col * 20.0f
                val sy = y + row * 20.0f
                drawSlot(context, inventory.getStack(slotIndex), sx, sy)
            }
        }
    }

    private fun drawSlot(context: DrawContext, stack: ItemStack, x: Float, y: Float) {
        if (stack.isEmpty) return
        context.drawItem(stack, x.toInt(), y.toInt())
        if (stack.count > 1) {
            val renderer = MinecraftClient.getInstance().textRenderer
            val value = stack.count.toString()
            context.drawText(renderer, value, (x + 17.0f - renderer.getWidth(value)).toInt(), (y + 9.0f).toInt(), WHITE, true)
        }
    }
}
