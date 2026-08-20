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
import net.minecraft.item.ItemStack
import net.minecraft.util.Identifier
import java.util.IdentityHashMap
import kotlin.math.ceil

object CooldownHud {
    private const val WIDTH = 218.0f
    private val drag = HudDragController(HudModuleSettings.Module.COOLDOWNS)

    fun register() {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.SUBTITLES,
            Identifier.of(HypnosiaClient.MOD_ID, "cooldown_hud"),
            ::render,
        )
    }

    fun tickDrag(client: MinecraftClient) {
        drag.tick(client, WIDTH, 34.0f)
    }

    private fun render(context: DrawContext, tickCounter: RenderTickCounter) {
        val client = MinecraftClient.getInstance()
        if (client.currentScreen is dev.hypnosia.ui.HypnosiaHomeV2Screen) return
        if (client.player == null || !HudModuleSettings.isEnabled(HudModuleSettings.Module.COOLDOWNS)) return
        val state = HudModuleSettings.state(HudModuleSettings.Module.COOLDOWNS)
        val rows = activeCooldowns(client)
        val height = height(rows.size)
        val scale = fixedScale(client)
        val x = anchorX(client.window.framebufferWidth.toFloat(), WIDTH, state.x)
        val headerY = anchorY(client.window.framebufferHeight.toFloat(), 34.0f, state.y)
        val y = topY(headerY, state.version, rows.size)

        context.matrices.pushMatrix()
        context.matrices.scale(scale, scale)
        drawFrame(context, x, y, state.version, height)
        drawHeader(context, x, headerY, state.version)
        rows.forEachIndexed { index, row -> drawRow(context, row, x, rowY(headerY, state.version, index, rows.size), state.version) }
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
        text(context, "CoolDowns", x + 6.0f, headerY + 1.0f, 182.0f, 32.0f, Text16, WHITE)
        icon(context, "hourglass.png", x + 188.0f, headerY + 5.0f, 24.0f, 24.0f, WHITE)
    }

    private fun drawRow(context: DrawContext, row: Row, x: Float, y: Float, version: HudModuleSettings.Version) {
        if (!isFramed(version)) {
            panel(context, x, y, WIDTH, 24.0f, 7.0f)
        }
        marqueeText(context, row.name, x + 9.0f, y + 1.0f, 134.0f, 22.0f, Text16)
        HypnosiaRenderUtils.drawFigmaBox(context, x + 146.0f, y + 3.0f, 1.0f, 18.0f, 0.0f, 0xFF6D6D6D.toInt())
        context.drawItem(row.stack, (x + 150.0f).toInt(), (y + 4.0f).toInt())
        text(context, row.time, x + 168.0f, y + 1.0f, 42.0f, 22.0f, Text16)
    }

    private fun activeCooldowns(client: MinecraftClient): List<Row> {
        val player = client.player ?: return emptyList()
        val manager = player.itemCooldownManager
        val entries = cooldownEntries(manager)
        if (entries.isEmpty()) return emptyList()
        val currentTick = cooldownTick(manager)
        val seen = IdentityHashMap<Any, Boolean>()
        val result = mutableListOf<Row>()
        for (slot in 0 until player.inventory.size()) {
            val stack = player.inventory.getStack(slot)
            if (stack.isEmpty || seen.put(stack.item, true) == true) continue
            val group = manager.getGroup(stack)
            val entry = entries[group] ?: continue
            val remaining = ((entry.endTick - currentTick).coerceAtLeast(0) / 20.0)
            if (remaining > 0.0) {
                result += Row(stack.name.string, formatSeconds(remaining), stack)
            }
        }
        return result
    }

    private fun cooldownEntries(manager: Any): Map<Identifier, CooldownEntry> {
        return runCatching {
            val field = manager.javaClass.getDeclaredField("entries")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val raw = field.get(manager) as Map<Identifier, Any>
            raw.mapValues { (_, entry) ->
                val start = entry.javaClass.getDeclaredMethod("startTick").invoke(entry) as Int
                val end = entry.javaClass.getDeclaredMethod("endTick").invoke(entry) as Int
                CooldownEntry(start, end)
            }
        }.getOrDefault(emptyMap())
    }

    private fun cooldownTick(manager: Any): Int {
        return runCatching {
            val field = manager.javaClass.getDeclaredField("tick")
            field.isAccessible = true
            field.getInt(manager)
        }.getOrDefault(0)
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

    private fun formatSeconds(seconds: Double): String {
        return if (seconds >= 60.0) {
            val total = ceil(seconds).toInt()
            "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
        } else {
            String.format(java.util.Locale.US, "%.1f", seconds)
        }
    }

    private data class Row(val name: String, val time: String, val stack: ItemStack)
    private data class CooldownEntry(val startTick: Int, val endTick: Int)
}
