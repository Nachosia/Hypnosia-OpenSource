package dev.hypnosia.hud

import dev.hypnosia.HypnosiaClient
import dev.hypnosia.ui.render.FigmaTextRenderer
import dev.hypnosia.ui.render.HypnosiaRenderUtils
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.client.render.RenderTickCounter
import net.minecraft.entity.EquipmentSlot
import net.minecraft.item.ItemStack
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW
import kotlin.math.PI
import kotlin.math.roundToInt

object HudModulesHud {
    private const val BG = 0xFF0D0D0D.toInt()
    private const val STROKE = 0xFF272727.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val HEALTH = 0xFFE8426A.toInt()
    private const val ABSORPTION = 0xFFECC84A.toInt()
    private const val ARMOR = 0xFF6BB8FF.toInt()
    private const val FOOD = 0xFFE78A3D.toInt()
    private const val SATURATION = 0xFFE7D95B.toInt()
    private const val AIR = 0xFF6FC8FF.toInt()
    private const val LEVEL = 0xFF36FF2F.toInt()
    private const val TRACK = 0xFF2A2A2A.toInt()
    private const val BAR_BG = 0xFFB9BBC4.toInt()

    private val StatText = FigmaTextRenderer.FigmaTextStyle(
        FigmaTextRenderer.Font.Main,
        size = 12.0f,
        lineHeight = 12.0f,
        baselineOffset = -1.0f,
    )
    private var activeDrag: DragState? = null
    private var wasMouseDown = false

    fun register() {
        replaceVanillaHudElement(VanillaHudElements.HOTBAR)
        replaceVanillaHudElement(VanillaHudElements.ARMOR_BAR)
        replaceVanillaHudElement(VanillaHudElements.HEALTH_BAR)
        replaceVanillaHudElement(VanillaHudElements.FOOD_BAR)
        replaceVanillaHudElement(VanillaHudElements.AIR_BAR)
        replaceVanillaHudElement(VanillaHudElements.INFO_BAR)
        replaceVanillaHudElement(VanillaHudElements.EXPERIENCE_LEVEL)
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.SUBTITLES,
            Identifier.of(HypnosiaClient.MOD_ID, "hud_modules"),
            ::render,
        )
    }

    fun tickDrag(client: MinecraftClient) {
        val window = client.window
        val isChatOpen = client.currentScreen is ChatScreen
        val isMouseDown = GLFW.glfwGetMouseButton(window.handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
        if (!isChatOpen || client.player == null) {
            if (activeDrag != null) HudModuleSettings.saveNow()
            activeDrag = null
            wasMouseDown = isMouseDown
            return
        }

        val fixedScale = 1.0f / window.scaleFactor.toFloat().coerceAtLeast(1.0f)
        val screenW = window.framebufferWidth.toFloat()
        val screenH = window.framebufferHeight.toFloat()
        val mouseGuiX = (client.mouse.x * window.scaledWidth / window.width).toFloat()
        val mouseGuiY = (client.mouse.y * window.scaledHeight / window.height).toFloat()
        val mouseX = mouseGuiX / fixedScale
        val mouseY = mouseGuiY / fixedScale

        if (isMouseDown && !wasMouseDown) {
            activeDrag = dragTargets(screenW, screenH)
                .lastOrNull { target ->
                    mouseX in target.hitX..(target.hitX + target.hitW) &&
                        mouseY in target.hitY..(target.hitY + target.hitH)
                }
                ?.let { target ->
                    DragState(
                        module = target.module,
                        offsetX = mouseX - target.anchorX,
                        offsetY = mouseY - target.anchorY,
                        width = target.width,
                        height = target.height,
                    )
                }
        } else if (isMouseDown) {
            activeDrag?.let { drag ->
                val maxX = (screenW - drag.width).coerceAtLeast(1.0f)
                val maxY = (screenH - drag.height).coerceAtLeast(1.0f)
                val snappedX = HudRenderSupport.snapPixel(mouseX - drag.offsetX).coerceIn(0.0f, maxX)
                val snappedY = HudRenderSupport.snapPixel(mouseY - drag.offsetY).coerceIn(0.0f, maxY)
                HudModuleSettings.setPosition(
                    drag.module,
                    snappedX / maxX,
                    snappedY / maxY,
                    persist = false,
                )
            }
        } else {
            if (activeDrag != null) HudModuleSettings.saveNow()
            activeDrag = null
        }

        wasMouseDown = isMouseDown
    }

    private fun replaceVanillaHudElement(id: Identifier) {
        HudElementRegistry.replaceElement(id) { old ->
            HudElement { context, tickCounter ->
                if (!HudModuleSettings.isEnabled(HudModuleSettings.Module.HOTBAR)) {
                    old.render(context, tickCounter)
                }
            }
        }
    }

    private fun render(context: DrawContext, tickCounter: RenderTickCounter) {
        val client = MinecraftClient.getInstance()
        if (client.currentScreen is dev.hypnosia.ui.HypnosiaHomeV2Screen) return
        if (client.player == null) return

        val window = client.window
        val fixedScale = 1.0f / window.scaleFactor.toFloat().coerceAtLeast(1.0f)
        val width = window.framebufferWidth.toFloat()
        val height = window.framebufferHeight.toFloat()

        context.matrices.pushMatrix()
        context.matrices.scale(fixedScale, fixedScale)
        if (HudModuleSettings.isEnabled(HudModuleSettings.Module.HOTBAR)) {
            renderHotbar(context, client, width, height)
        }
        if (HudModuleSettings.isEnabled(HudModuleSettings.Module.ARMOR)) {
            renderArmor(context, client, width, height)
        }
        context.matrices.popMatrix()
    }

    private fun renderHotbar(context: DrawContext, client: MinecraftClient, screenW: Float, screenH: Float) {
        val state = HudModuleSettings.state(HudModuleSettings.Module.HOTBAR)
        if (state.axis == HudModuleSettings.Axis.Y) {
            renderVerticalHotbar(context, client, screenW, screenH)
            return
        }

        val totalW = 427.0f
        val x = anchorX(screenW, totalW, state.x)
        val y = anchorY(screenH, 92.0f, state.y)

        renderStatusBars(context, client, x + 62.0f, y - 42.0f)

        panel(context, x + 60.0f, y, 367.0f, 48.0f, 10.0f)
        panel(context, x, y, 48.0f, 48.0f, 10.0f)

        val inventory = client.player?.inventory ?: return
        renderItemSlot(context, client.player?.offHandStack ?: ItemStack.EMPTY, x + 8.0f, y + 8.0f, state.slotHighlight)
        val selected = inventory.selectedSlot
        repeat(9) { slot ->
            val sx = x + 68.0f + slot * 40.0f
            val stack = inventory.getStack(slot)
            renderItemSlot(context, stack, sx, y + 8.0f, state.slotHighlight)
            if (slot == selected) {
                drawActiveSlot(context, sx, y + 8.0f)
            }
        }
    }

    private fun renderVerticalHotbar(context: DrawContext, client: MinecraftClient, screenW: Float, screenH: Float) {
        val state = HudModuleSettings.state(HudModuleSettings.Module.HOTBAR)
        val x = anchorX(screenW, 96.0f, state.x)
        val y = anchorY(screenH, 431.0f, state.y)
        panel(context, x, y, 48.0f, 48.0f, 10.0f)
        panel(context, x, y + 64.0f, 48.0f, 367.0f, 10.0f)
        renderVerticalStatusBars(context, client, x, y)

        val inventory = client.player?.inventory ?: return
        val selected = inventory.selectedSlot
        renderItemSlot(context, client.player?.offHandStack ?: ItemStack.EMPTY, x + 8.0f, y + 8.0f, state.slotHighlight)
        repeat(9) { slot ->
            val sy = y + 72.0f + slot * 40.0f
            val stack = inventory.getStack(slot)
            renderItemSlot(context, stack, x + 8.0f, sy, state.slotHighlight)
            if (slot == selected) {
                drawActiveSlot(context, x + 8.0f, sy)
            }
        }
    }

    private fun renderVerticalStatusBars(context: DrawContext, client: MinecraftClient, x: Float, y: Float) {
        val player = client.player ?: return
        val maxHealth = player.maxHealth.coerceAtLeast(1.0f)
        val health = player.health.coerceIn(0.0f, maxHealth)
        val absorption = player.absorptionAmount.coerceAtLeast(0.0f)
        val armorValue = player.armor.coerceIn(0, 20)
        val food = player.hungerManager.foodLevel.coerceIn(0, 20)
        val saturation = player.hungerManager.saturationLevel.coerceIn(0.0f, 20.0f)
        val air = player.air.coerceIn(0, player.maxAir.coerceAtLeast(1))

        drawVerticalHealthBar(context, x + 60.0f, y + 64.0f, health, maxHealth, absorption)
        drawVerticalStatBar(context, x + 80.0f, y + 64.0f, armorValue / 20.0f, BAR_BG, "$armorValue/20")
        drawVerticalDualStatBar(context, x + 60.0f, y + 268.0f, food / 20.0f, saturation / 20.0f, "$food/20", "${saturation.roundToInt()}/20")
        if (player.isSubmergedInWater || air < player.maxAir) {
            drawVerticalStatBar(context, x + 80.0f, y + 268.0f, air / player.maxAir.toFloat().coerceAtLeast(1.0f), AIR, "$air/${player.maxAir}")
        }
        drawLevelLabel(context, player.experienceLevel, x + 59.0f, y + 240.0f, 37.0f, 16.0f)
    }

    private fun renderStatusBars(context: DrawContext, client: MinecraftClient, x: Float, y: Float) {
        val player = client.player ?: return
        val maxHealth = player.maxHealth.coerceAtLeast(1.0f)
        val health = player.health.coerceIn(0.0f, maxHealth)
        val absorption = player.absorptionAmount.coerceAtLeast(0.0f)
        val armorValue = player.armor.coerceIn(0, 20)
        val food = player.hungerManager.foodLevel.coerceIn(0, 20)
        val saturation = player.hungerManager.saturationLevel.coerceIn(0.0f, 20.0f)
        val air = player.air.coerceIn(0, player.maxAir.coerceAtLeast(1))

        drawStatBar(context, x, y, 162.0f, armorValue / 20.0f, BAR_BG, "$armorValue/20")
        if (player.isSubmergedInWater || air < player.maxAir) {
            drawStatBar(context, x + 202.0f, y, 162.0f, air / player.maxAir.toFloat().coerceAtLeast(1.0f), AIR, "$air/${player.maxAir}")
        }
        drawHealthBar(context, x, y + 20.0f, health, maxHealth, absorption)
        drawLevelLabel(context, player.experienceLevel, x + 162.0f, y + 20.0f, 40.0f, 16.0f)
        drawDualStatBar(context, x + 202.0f, y + 20.0f, food / 20.0f, saturation / 20.0f, FOOD, SATURATION, "$food/20", "${saturation.roundToInt()}/20")
    }

    private fun renderArmor(context: DrawContext, client: MinecraftClient, screenW: Float, screenH: Float) {
        val state = HudModuleSettings.state(HudModuleSettings.Module.ARMOR)
        val spec = armorSpec(state.version, state.axis)
        val width = spec.width
        val height = spec.height
        val x = anchorX(screenW, width, state.x)
        val y = anchorY(screenH, height, state.y)
        panel(context, x, y, width, height, 10.0f)

        val player = client.player ?: return
        val stacks = listOf(
            player.getEquippedStack(EquipmentSlot.HEAD),
            player.getEquippedStack(EquipmentSlot.CHEST),
            player.getEquippedStack(EquipmentSlot.LEGS),
            player.getEquippedStack(EquipmentSlot.FEET),
        )
        val activeArmorIndex = activeArmorSlotIndex(stacks)
        stacks.forEachIndexed { index, stack ->
            val slot = spec.slots[index]
            val bar = spec.bars[index]
            renderItemSlot(context, stack, x + slot.x, y + slot.y, state.slotHighlight && index == activeArmorIndex)
            drawDurability(context, stack, x + bar.x, y + bar.y, bar.width, bar.height, vertical = bar.height > bar.width)
        }
    }

    private fun drawActiveSlot(context: DrawContext, x: Float, y: Float) {
        HypnosiaRenderUtils.drawFigmaBox(context, x - 3.0f, y - 3.0f, 38.0f, 38.0f, 5.0f, 0x00FFFFFF, WHITE, 1.0f)
    }

    private fun renderItemSlot(context: DrawContext, stack: ItemStack, x: Float, y: Float, drawFrame: Boolean) {
        if (drawFrame) {
            HypnosiaRenderUtils.drawFigmaBox(context, x, y, 32.0f, 32.0f, 6.0f, BG, STROKE, 1.0f)
        }
        if (!stack.isEmpty) {
            context.matrices.pushMatrix()
            context.matrices.translate(x, y)
            context.matrices.scale(2.0f, 2.0f)
            context.drawItem(stack, 0, 0)
            drawClassicStackCount(context, stack)
            context.matrices.popMatrix()
        }
    }

    private fun drawClassicStackCount(context: DrawContext, stack: ItemStack) {
        if (stack.count <= 1) return

        val textRenderer = MinecraftClient.getInstance().textRenderer
        val count = stack.count.toString()
        val countX = 17 - textRenderer.getWidth(count)
        val countY = 9
        context.drawText(textRenderer, count, countX, countY, WHITE, true)
    }

    private fun drawDurability(context: DrawContext, stack: ItemStack, x: Float, y: Float, width: Float, height: Float, vertical: Boolean) {
        HypnosiaRenderUtils.drawFigmaBox(context, x, y, width, height, 2.0f, TRACK)
        if (stack.isEmpty || !stack.isDamageable) return
        val left = ((stack.maxDamage - stack.damage).toFloat() / stack.maxDamage.toFloat()).coerceIn(0.0f, 1.0f)
        if (vertical) {
            val fillH = height * left
            HypnosiaRenderUtils.drawFigmaBox(context, x, y + height - fillH, width, fillH, 2.0f, durabilityColor(left))
        } else {
            HypnosiaRenderUtils.drawFigmaBox(context, x, y, width * left, height, 2.0f, durabilityColor(left))
        }
    }

    private fun drawHealthBar(context: DrawContext, x: Float, y: Float, health: Float, maxHealth: Float, absorption: Float) {
        panel(context, x, y, 162.0f, 16.0f, 6.0f)
        val absorbWidth = if (absorption > 0.0f) 32.0f else 0.0f
        if (absorbWidth > 0.0f) {
            drawStatFill(context, x, y + 1.0f, absorbWidth, 14.0f, (absorption / 20.0f).coerceIn(0.0f, 1.0f), ABSORPTION)
            drawStatLabel(context, "+${absorption.roundToInt()}", x, y, absorbWidth, 16.0f)
        }
        val healthX = x + absorbWidth
        val healthWidth = 162.0f - absorbWidth
        drawStatFill(context, healthX, y + 1.0f, healthWidth, 14.0f, health / maxHealth, HEALTH)
        drawStatLabel(context, "${health.roundToInt()}/${maxHealth.roundToInt()}", healthX, y, healthWidth, 16.0f)
    }

    private fun drawStatBar(context: DrawContext, x: Float, y: Float, width: Float, fill: Float, color: Int, label: String) {
        panel(context, x, y, width, 16.0f, 6.0f)
        drawStatFill(context, x, y + 1.0f, width, 14.0f, fill, color)
        drawStatLabel(context, label, x, y, width, 16.0f)
    }

    private fun drawDualStatBar(
        context: DrawContext,
        x: Float,
        y: Float,
        foodFill: Float,
        saturationFill: Float,
        foodColor: Int,
        saturationColor: Int,
        foodLabel: String,
        saturationLabel: String,
    ) {
        panel(context, x, y, 162.0f, 16.0f, 6.0f)
        drawStatFill(context, x, y + 1.0f, 81.0f, 14.0f, foodFill, foodColor)
        drawStatFill(context, x + 162.0f - 81.0f * saturationFill, y + 1.0f, 81.0f * saturationFill, 14.0f, 1.0f, saturationColor)
        drawStatLabel(context, foodLabel, x, y, 81.0f, 16.0f)
        drawStatLabel(context, saturationLabel, x + 81.0f, y, 81.0f, 16.0f)
    }

    private fun drawStatLabel(context: DrawContext, label: String, x: Float, y: Float, width: Float, height: Float) {
        val textWidth = FigmaTextRenderer.width(label, StatText)
        val drawX = x + (width - textWidth) * 0.5f
        val drawY = y + (height - StatText.lineHeight) * 0.5f
        FigmaTextRenderer.draw(context, label, drawX, drawY, WHITE, StatText)
    }

    private fun drawLevelLabel(context: DrawContext, level: Int, x: Float, y: Float, width: Float, height: Float) {
        if (level <= 0) return
        val label = level.toString()
        val textWidth = FigmaTextRenderer.width(label, StatText)
        val drawX = x + (width - textWidth) * 0.5f
        val drawY = y + (height - StatText.lineHeight) * 0.5f
        FigmaTextRenderer.draw(context, label, drawX, drawY, LEVEL, StatText)
        FigmaTextRenderer.draw(context, label, drawX + 0.35f, drawY, LEVEL, StatText)
        FigmaTextRenderer.draw(context, label, drawX - 0.35f, drawY, LEVEL, StatText)
        FigmaTextRenderer.draw(context, label, drawX, drawY + 0.35f, LEVEL, StatText)
    }

    private fun drawVerticalHealthBar(context: DrawContext, x: Float, y: Float, health: Float, maxHealth: Float, absorption: Float) {
        panel(context, x, y, 16.0f, 163.0f, 6.0f)
        drawVerticalStatFill(context, x, y, 16.0f, 163.0f, health / maxHealth, HEALTH)
        if (absorption > 0.0f) {
            val absorbHeight = (32.0f * (absorption / 20.0f)).coerceIn(0.0f, 32.0f)
            if (absorbHeight > 0.0f) {
                HypnosiaRenderUtils.drawFigmaBox(context, x + 1.0f, y + 1.0f, 14.0f, absorbHeight, 5.0f, ABSORPTION)
                drawVerticalStatLabel(context, "+${absorption.roundToInt()}", x, y, 16.0f, 32.0f)
            }
        }
        drawVerticalStatLabel(context, "${health.roundToInt()}/${maxHealth.roundToInt()}", x, y, 16.0f, 163.0f)
    }

    private fun drawVerticalStatBar(context: DrawContext, x: Float, y: Float, fill: Float, color: Int, label: String) {
        panel(context, x, y, 16.0f, 163.0f, 6.0f)
        drawVerticalStatFill(context, x, y, 16.0f, 163.0f, fill, color)
        drawVerticalStatLabel(context, label, x, y, 16.0f, 163.0f)
    }

    private fun drawVerticalDualStatBar(
        context: DrawContext,
        x: Float,
        y: Float,
        foodFill: Float,
        saturationFill: Float,
        foodLabel: String,
        saturationLabel: String,
    ) {
        panel(context, x, y, 16.0f, 163.0f, 6.0f)
        val half = 81.0f
        val topFill = (half * foodFill.coerceIn(0.0f, 1.0f)).coerceAtLeast(0.0f)
        val bottomFill = (half * saturationFill.coerceIn(0.0f, 1.0f)).coerceAtLeast(0.0f)
        if (topFill > 0.0f) {
            HypnosiaRenderUtils.drawFigmaBox(context, x + 1.0f, y + 1.0f, 14.0f, topFill, 5.0f, FOOD)
        }
        if (bottomFill > 0.0f) {
            HypnosiaRenderUtils.drawFigmaBox(context, x + 1.0f, y + 162.0f - bottomFill, 14.0f, bottomFill, 5.0f, SATURATION)
        }
        drawVerticalStatLabel(context, foodLabel, x, y, 16.0f, half)
        drawVerticalStatLabel(context, saturationLabel, x, y + half, 16.0f, half)
    }

    private fun drawVerticalStatFill(context: DrawContext, x: Float, y: Float, width: Float, height: Float, fill: Float, color: Int) {
        val safeFill = fill.coerceIn(0.0f, 1.0f)
        if (safeFill <= 0.0f) return
        val inset = 1.0f
        val innerWidth = (width - inset * 2.0f).coerceAtLeast(0.0f)
        val innerHeight = (height - inset * 2.0f).coerceAtLeast(0.0f)
        val fillHeight = innerHeight * safeFill
        HypnosiaRenderUtils.drawFigmaBox(
            context,
            x + inset,
            y + height - inset - fillHeight,
            innerWidth,
            fillHeight,
            innerWidth * 0.5f,
            color,
        )
    }

    private fun drawVerticalStatLabel(context: DrawContext, label: String, x: Float, y: Float, width: Float, height: Float) {
        val centerX = x + width * 0.5f
        val centerY = y + height * 0.5f
        context.matrices.pushMatrix()
        context.matrices.rotateAbout((-PI / 2.0).toFloat(), centerX, centerY)
        drawStatLabel(context, label, centerX - height * 0.5f, centerY - width * 0.5f, height, width)
        context.matrices.popMatrix()
    }

    private fun drawStatFill(context: DrawContext, x: Float, y: Float, width: Float, height: Float, fill: Float, color: Int) {
        val safeFill = fill.coerceIn(0.0f, 1.0f)
        if (safeFill <= 0.0f) return
        val inset = 1.0f
        val innerWidth = (width - inset * 2.0f).coerceAtLeast(0.0f)
        val innerHeight = (height - inset * 2.0f).coerceAtLeast(0.0f)
        if (innerWidth <= 0.0f || innerHeight <= 0.0f) return
        HypnosiaRenderUtils.drawFigmaBox(
            context,
            x + inset,
            y + inset,
            innerWidth * safeFill,
            innerHeight,
            innerHeight * 0.5f,
            color,
        )
    }

    private fun panel(context: DrawContext, x: Float, y: Float, width: Float, height: Float, radius: Float) {
        HypnosiaRenderUtils.drawFigmaBox(context, x, y, width, height, radius, BG, STROKE, 1.0f)
    }

    private fun anchorX(screenW: Float, elementW: Float, normalized: Float): Float =
        HudRenderSupport.anchorX(screenW, elementW, normalized)

    private fun anchorY(screenH: Float, elementH: Float, normalized: Float): Float =
        HudRenderSupport.anchorY(screenH, elementH, normalized)

    private fun dragTargets(screenW: Float, screenH: Float): List<DragTarget> {
        val targets = mutableListOf<DragTarget>()
        if (HudModuleSettings.isEnabled(HudModuleSettings.Module.HOTBAR)) {
            val state = HudModuleSettings.state(HudModuleSettings.Module.HOTBAR)
            if (state.axis == HudModuleSettings.Axis.Y) {
                val x = anchorX(screenW, 96.0f, state.x)
                val y = anchorY(screenH, 431.0f, state.y)
                targets += DragTarget(
                    module = HudModuleSettings.Module.HOTBAR,
                    anchorX = x,
                    anchorY = y,
                    width = 96.0f,
                    height = 431.0f,
                    hitX = x,
                    hitY = y,
                    hitW = 96.0f,
                    hitH = 431.0f,
                )
            } else {
                val x = anchorX(screenW, 427.0f, state.x)
                val y = anchorY(screenH, 92.0f, state.y)
                targets += DragTarget(
                    module = HudModuleSettings.Module.HOTBAR,
                    anchorX = x,
                    anchorY = y,
                    width = 427.0f,
                    height = 92.0f,
                    hitX = x,
                    hitY = y - 42.0f,
                    hitW = 427.0f,
                    hitH = 90.0f,
                )
            }
        }
        if (HudModuleSettings.isEnabled(HudModuleSettings.Module.ARMOR)) {
            val state = HudModuleSettings.state(HudModuleSettings.Module.ARMOR)
            val spec = armorSpec(state.version, state.axis)
            val x = anchorX(screenW, spec.width, state.x)
            val y = anchorY(screenH, spec.height, state.y)
            targets += DragTarget(
                module = HudModuleSettings.Module.ARMOR,
                anchorX = x,
                anchorY = y,
                width = spec.width,
                height = spec.height,
                hitX = x,
                hitY = y,
                hitW = spec.width,
                hitH = spec.height,
            )
        }
        return targets
    }

    private fun armorSpec(version: HudModuleSettings.Version, axis: HudModuleSettings.Axis): ArmorSpec {
        return when (version to axis) {
            HudModuleSettings.Version.V1 to HudModuleSettings.Axis.X -> ArmorSpec(
                width = 166.0f,
                height = 48.0f,
                slots = listOf(
                    SlotRect(4.0f, 8.0f),
                    SlotRect(44.0f, 8.0f),
                    SlotRect(84.0f, 8.0f),
                    SlotRect(124.0f, 8.0f),
                ),
                bars = listOf(
                    BarRect(38.0f, 8.0f, 4.0f, 32.0f),
                    BarRect(78.0f, 8.0f, 4.0f, 32.0f),
                    BarRect(118.0f, 8.0f, 4.0f, 32.0f),
                    BarRect(158.0f, 8.0f, 4.0f, 32.0f),
                ),
            )

            HudModuleSettings.Version.V2 to HudModuleSettings.Axis.X -> ArmorSpec(
                width = 168.0f,
                height = 48.0f,
                slots = listOf(
                    SlotRect(8.0f, 5.0f),
                    SlotRect(48.0f, 5.0f),
                    SlotRect(88.0f, 5.0f),
                    SlotRect(128.0f, 5.0f),
                ),
                bars = listOf(
                    BarRect(8.0f, 38.0f, 32.0f, 4.0f),
                    BarRect(48.0f, 38.0f, 32.0f, 4.0f),
                    BarRect(88.0f, 38.0f, 32.0f, 4.0f),
                    BarRect(128.0f, 38.0f, 32.0f, 4.0f),
                ),
            )

            HudModuleSettings.Version.V1 to HudModuleSettings.Axis.Y -> ArmorSpec(
                width = 48.0f,
                height = 162.0f,
                slots = listOf(
                    SlotRect(5.0f, 5.0f),
                    SlotRect(5.0f, 45.0f),
                    SlotRect(5.0f, 85.0f),
                    SlotRect(5.0f, 125.0f),
                ),
                bars = listOf(
                    BarRect(40.0f, 5.0f, 4.0f, 32.0f),
                    BarRect(40.0f, 45.0f, 4.0f, 32.0f),
                    BarRect(40.0f, 85.0f, 4.0f, 32.0f),
                    BarRect(40.0f, 125.0f, 4.0f, 32.0f),
                ),
            )

            else -> ArmorSpec(
                width = 42.0f,
                height = 166.0f,
                slots = listOf(
                    SlotRect(5.0f, 5.0f),
                    SlotRect(5.0f, 45.0f),
                    SlotRect(5.0f, 85.0f),
                    SlotRect(5.0f, 125.0f),
                ),
                bars = listOf(
                    BarRect(5.0f, 38.0f, 32.0f, 4.0f),
                    BarRect(5.0f, 78.0f, 32.0f, 4.0f),
                    BarRect(5.0f, 118.0f, 32.0f, 4.0f),
                    BarRect(5.0f, 158.0f, 32.0f, 4.0f),
                ),
            )
        }
    }

    private data class ArmorSpec(
        val width: Float,
        val height: Float,
        val slots: List<SlotRect>,
        val bars: List<BarRect>,
    )

    private data class DragTarget(
        val module: HudModuleSettings.Module,
        val anchorX: Float,
        val anchorY: Float,
        val width: Float,
        val height: Float,
        val hitX: Float,
        val hitY: Float,
        val hitW: Float,
        val hitH: Float,
    )

    private data class DragState(
        val module: HudModuleSettings.Module,
        val offsetX: Float,
        val offsetY: Float,
        val width: Float,
        val height: Float,
    )

    private data class SlotRect(val x: Float, val y: Float)

    private data class BarRect(val x: Float, val y: Float, val width: Float, val height: Float)

    private fun activeArmorSlotIndex(stacks: List<ItemStack>): Int {
        return stacks
            .mapIndexedNotNull { index, stack ->
                if (stack.isEmpty || !stack.isDamageable || stack.damage <= 0) {
                    null
                } else {
                    val left = (stack.maxDamage - stack.damage).toFloat() / stack.maxDamage.toFloat()
                    index to left
                }
            }
            .minByOrNull { it.second }
            ?.first ?: -1
    }

    private fun durabilityColor(left: Float): Int {
        val value = left.coerceIn(0.0f, 1.0f)
        val red: Int
        val green: Int
        if (value < 0.5f) {
            red = 255
            green = (value * 2.0f * 210.0f).roundToInt()
        } else {
            red = ((1.0f - value) * 2.0f * 255.0f).roundToInt()
            green = 210
        }
        return (0xFF shl 24) or (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or 0x2A
    }
}
