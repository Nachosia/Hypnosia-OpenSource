package dev.hypnosia.hud

import dev.hypnosia.HypnosiaClient
import dev.hypnosia.hud.HudRenderSupport.Text16
import dev.hypnosia.hud.HudRenderSupport.WHITE
import dev.hypnosia.hud.HudRenderSupport.anchorX
import dev.hypnosia.hud.HudRenderSupport.anchorY
import dev.hypnosia.hud.HudRenderSupport.fixedScale
import dev.hypnosia.hud.HudRenderSupport.icon
import dev.hypnosia.hud.HudRenderSupport.marqueeText
import dev.hypnosia.hud.HudRenderSupport.panel
import dev.hypnosia.hud.HudRenderSupport.text
import dev.hypnosia.ui.render.HypnosiaRenderUtils
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.RenderTickCounter
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import kotlin.math.ceil

object PotionsHud {
    private const val WIDTH = 218.0f
    private val drag = HudDragController(HudModuleSettings.Module.POTIONS)

    fun register() {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.SUBTITLES,
            Identifier.of(HypnosiaClient.MOD_ID, "potions_hud"),
            ::render,
        )
    }

    fun tickDrag(client: MinecraftClient) {
        drag.tick(client, WIDTH, 34.0f)
    }

    private fun render(context: DrawContext, tickCounter: RenderTickCounter) {
        val client = MinecraftClient.getInstance()
        if (client.currentScreen is dev.hypnosia.ui.HypnosiaHomeV2Screen) return
        if (client.player == null || !HudModuleSettings.isEnabled(HudModuleSettings.Module.POTIONS)) return
        val state = HudModuleSettings.state(HudModuleSettings.Module.POTIONS)
        val rows = activeEffects(client)
        val height = height(rows.size)
        val scale = fixedScale(client)
        val x = anchorX(client.window.framebufferWidth.toFloat(), WIDTH, state.x)
        val headerY = anchorY(client.window.framebufferHeight.toFloat(), 34.0f, state.y)
        val y = topY(headerY, state.version, rows.size)

        context.matrices.pushMatrix()
        context.matrices.scale(scale, scale)
        drawFrame(context, x, y, state.version, height)
        drawHeader(context, x, headerY, state.version)
        rows.forEachIndexed { index, effect -> drawRow(context, effect, x, rowY(headerY, state.version, index, rows.size), state.version) }
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
        text(context, "Potions", x + 6.0f, headerY + 1.0f, 182.0f, 32.0f, Text16, WHITE)
        icon(context, "potion.png", x + 188.0f, headerY + 5.0f, 24.0f, 24.0f, WHITE)
    }

    private fun drawRow(context: DrawContext, effect: StatusEffectInstance, x: Float, y: Float, version: HudModuleSettings.Version) {
        if (!isFramed(version)) {
            panel(context, x, y, WIDTH, 24.0f, 7.0f)
        }
        marqueeText(context, Text.translatable(effect.translationKey).string, x + 8.0f, y + 1.0f, 95.0f, 22.0f, Text16)
        divider(context, x + 106.0f, y)
        text(context, "lvl:${effect.amplifier + 1}", x + 110.0f, y + 1.0f, 32.0f, 22.0f, Text16)
        divider(context, x + 145.0f, y)
        drawEffectIcon(context, effect, x + 149.0f, y + 4.0f)
        text(context, formatTicks(effect.duration), x + 168.0f, y + 1.0f, 42.0f, 22.0f, Text16)
    }

    private fun drawEffectIcon(context: DrawContext, effect: StatusEffectInstance, x: Float, y: Float) {
        val effectId = Registries.STATUS_EFFECT.getId(effect.effectType.value()) ?: return
        val texture = Identifier.ofVanilla("textures/mob_effect/${effectId.path}.png")
        HypnosiaRenderUtils.drawRoundedTexture(context, texture, x, y, 16.0f, 16.0f, 0.0f)
    }

    private fun divider(context: DrawContext, x: Float, y: Float) {
        HypnosiaRenderUtils.drawFigmaBox(context, x, y + 3.0f, 1.0f, 18.0f, 0.0f, 0xFF6D6D6D.toInt())
    }

    private fun topY(headerY: Float, version: HudModuleSettings.Version, rowCount: Int): Float {
        if (version != HudModuleSettings.Version.V3 && version != HudModuleSettings.Version.V4) return headerY
        val rowsHeight = if (rowCount > 0) 5.0f + rowBlockHeight(rowCount) else 0.0f
        return headerY - rowsHeight
    }

    private fun rowY(headerY: Float, version: HudModuleSettings.Version, index: Int, rowCount: Int): Float {
        return when (version) {
            HudModuleSettings.Version.V1, HudModuleSettings.Version.V2 -> headerY + 39.0f + index * 29.0f
            HudModuleSettings.Version.V3, HudModuleSettings.Version.V4 -> {
                headerY - 5.0f - rowBlockHeight(rowCount) + index * 29.0f
            }
        }
    }

    private fun isFramed(version: HudModuleSettings.Version): Boolean =
        version == HudModuleSettings.Version.V2 || version == HudModuleSettings.Version.V4

    private fun height(rowCount: Int): Float =
        34.0f + if (rowCount > 0) 5.0f + rowBlockHeight(rowCount) else 0.0f

    private fun rowBlockHeight(rowCount: Int): Float =
        if (rowCount <= 0) 0.0f else rowCount * 24.0f + (rowCount - 1) * 5.0f

    private fun activeEffects(client: MinecraftClient): List<StatusEffectInstance> =
        client.player?.statusEffects
            ?.filter { it.shouldShowIcon() }
            ?.sortedWith(compareBy<StatusEffectInstance> { it.duration }.thenBy { it.translationKey })
            ?: emptyList()

    private fun formatTicks(ticks: Int): String {
        val seconds = ticks / 20.0
        return if (seconds >= 60.0) {
            val total = ceil(seconds).toInt()
            "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
        } else {
            String.format(java.util.Locale.US, "%.1f", seconds)
        }
    }
}
