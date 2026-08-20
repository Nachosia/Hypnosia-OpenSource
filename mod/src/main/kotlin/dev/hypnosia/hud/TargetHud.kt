package dev.hypnosia.hud

import dev.hypnosia.HypnosiaClient
import dev.hypnosia.ui.render.FigmaTextRenderer
import dev.hypnosia.ui.render.HypnosiaRenderUtils
import dev.hypnosia.other.StreamerModeSettings
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.PlayerSkinDrawer
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.client.network.AbstractClientPlayerEntity
import net.minecraft.client.render.RenderTickCounter
import net.minecraft.client.render.entity.state.LivingEntityRenderState
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.LivingEntity
import net.minecraft.item.ItemStack
import net.minecraft.util.Identifier
import net.minecraft.util.hit.EntityHitResult
import org.joml.Quaternionf
import org.joml.Vector3f
import org.lwjgl.glfw.GLFW
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object TargetHud {
    private const val BG = 0xFF0D0D0D.toInt()
    private const val STROKE = 0xFF272727.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val HP = 0xFFFF1313.toInt()
    private const val PREVIEW = 0xFF1E1E1E.toInt()
    private const val TRACK = 0xFF272727.toInt()

    private val NameText = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 16.0f, 20.0f, baselineOffset = 2.0f)
    private val HpText = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 15.0f, 16.0f, baselineOffset = 1.0f)
    private var activeDrag: DragState? = null
    private var activeModelDrag: ModelDragState? = null
    private var wasMouseDown = false

    fun register() {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.SUBTITLES,
            Identifier.of(HypnosiaClient.MOD_ID, "target_hud"),
            ::render,
        )
    }

    fun tickDrag(client: MinecraftClient) {
        val window = client.window
        val isChatOpen = client.currentScreen is ChatScreen
        val isMouseDown = GLFW.glfwGetMouseButton(window.handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
        if (!isChatOpen || client.player == null || !TargetHudSettings.isEnabled()) {
            if (activeDrag != null || activeModelDrag != null) TargetHudSettings.saveNow()
            activeDrag = null
            activeModelDrag = null
            wasMouseDown = isMouseDown
            return
        }

        val state = TargetHudSettings.state()
        val fixedScale = 1.0f / window.scaleFactor.toFloat().coerceAtLeast(1.0f)
        val screenW = window.framebufferWidth.toFloat()
        val screenH = window.framebufferHeight.toFloat()
        val mouseGuiX = (client.mouse.x * window.scaledWidth / window.width).toFloat()
        val mouseGuiY = (client.mouse.y * window.scaledHeight / window.height).toFloat()
        val mouseX = mouseGuiX / fixedScale
        val mouseY = mouseGuiY / fixedScale
        val spec = spec(state.version, state.showEquipmentStrip, targetName(client.player!!))
        val x = anchorX(screenW, spec.width, state.x)
        val y = anchorY(screenH, spec.height, state.y)

        if (isMouseDown && !wasMouseDown) {
            if (state.version.ordinal >= TargetHudSettings.Version.V4.ordinal) {
                val modelX = x + 8.0f
                val modelY = y + spec.targetY + 8.0f
                if (mouseX in modelX..(modelX + 98.0f) && mouseY in modelY..(modelY + 91.0f)) {
                    activeModelDrag = ModelDragState(mouseX, mouseY)
                    activeDrag = null
                    wasMouseDown = isMouseDown
                    return
                }
            }
            if (mouseX in x..(x + spec.width) && mouseY in y..(y + spec.height)) {
                activeDrag = DragState(mouseX - x, mouseY - y, spec.width, spec.height)
            }
        } else if (isMouseDown) {
            activeModelDrag?.let { drag ->
                val dx = mouseX - drag.lastX
                val dy = mouseY - drag.lastY
                TargetHudSettings.setModelYaw(state.modelYaw + dx * 1.2f, persist = false)
                TargetHudSettings.setModelPitch(state.modelPitch - dy * 0.8f, persist = false)
                activeModelDrag = drag.copy(lastX = mouseX, lastY = mouseY)
                wasMouseDown = isMouseDown
                return
            }
            activeDrag?.let { drag ->
                val maxX = (screenW - drag.width).coerceAtLeast(1.0f)
                val maxY = (screenH - drag.height).coerceAtLeast(1.0f)
                val snappedX = HudRenderSupport.snapPixel(mouseX - drag.offsetX).coerceIn(0.0f, maxX)
                val snappedY = HudRenderSupport.snapPixel(mouseY - drag.offsetY).coerceIn(0.0f, maxY)
                TargetHudSettings.setPosition(snappedX / maxX, snappedY / maxY, persist = false)
            }
        } else {
            if (activeDrag != null || activeModelDrag != null) TargetHudSettings.saveNow()
            activeDrag = null
            activeModelDrag = null
        }

        wasMouseDown = isMouseDown
    }

    private fun render(context: DrawContext, tickCounter: RenderTickCounter) {
        if (MinecraftClient.getInstance().currentScreen is dev.hypnosia.ui.HypnosiaHomeV2Screen) return
        val client = MinecraftClient.getInstance()
        if (!TargetHudSettings.isEnabled() || client.player == null) return

        val target = target(client) ?: return
        val window = client.window
        val fixedScale = 1.0f / window.scaleFactor.toFloat().coerceAtLeast(1.0f)
        val screenW = window.framebufferWidth.toFloat()
        val screenH = window.framebufferHeight.toFloat()
        val state = TargetHudSettings.state()
        val spec = spec(state.version, state.showEquipmentStrip, targetName(target))
        val x = anchorX(screenW, spec.width, state.x)
        val y = anchorY(screenH, spec.height, state.y)

        context.matrices.pushMatrix()
        context.matrices.scale(fixedScale, fixedScale)
        drawHud(context, client, target, x, y, spec, state)
        context.matrices.popMatrix()

        if (state.version.ordinal >= TargetHudSettings.Version.V4.ordinal) {
            val targetX = x
            val targetY = y + spec.targetY
            drawTargetModelGui(
                context = context,
                client = client,
                target = target,
                fixedScale = fixedScale,
                x = targetX + 8.0f,
                y = targetY + 8.0f,
                width = 98.0f,
                height = 91.0f,
                state = state,
            )
        }
    }

    private fun drawHud(
        context: DrawContext,
        client: MinecraftClient,
        target: LivingEntity,
        x: Float,
        y: Float,
        spec: Spec,
        state: TargetHudSettings.State,
    ) {
        if (state.showEquipmentStrip) {
            drawEquipmentStrip(context, target, x + spec.equipmentX, y + spec.equipmentY)
        }

        val targetX = x
        val targetY = y + spec.targetY
        panel(context, targetX, targetY, spec.targetWidth, spec.targetHeight, 10.0f)
        when (state.version) {
            TargetHudSettings.Version.V1 -> drawSmallBar(context, client, target, targetX, targetY, spec.targetWidth)
            TargetHudSettings.Version.V2 -> drawSmallCircle(context, client, target, targetX, targetY, spec.targetWidth, filled = false)
            TargetHudSettings.Version.V3 -> drawSmallCircle(context, client, target, targetX, targetY, spec.targetWidth, filled = true)
            TargetHudSettings.Version.V4 -> drawLargeBar(context, client, target, targetX, targetY, spec.targetWidth, state)
            TargetHudSettings.Version.V5 -> drawLargeCircle(context, client, target, targetX, targetY, spec.targetWidth, state, filled = false)
            TargetHudSettings.Version.V6 -> drawLargeCircle(context, client, target, targetX, targetY, spec.targetWidth, state, filled = true)
        }
    }

    private fun drawSmallBar(context: DrawContext, client: MinecraftClient, target: LivingEntity, x: Float, y: Float, targetWidth: Float) {
        val contentWidth = targetWidth - 72.0f
        drawSmallTargetPreview(context, target, x + 8.0f, y + 8.0f, 48.0f)
        drawText(context, targetName(target), x + 64.0f, y + 8.0f, contentWidth, 16.0f, NameText)
        drawText(context, healthSmall(target), x + 64.0f, y + 31.0f, 59.0f, 16.0f, NameText)
        drawHpBar(context, target, x + 64.0f, y + 53.0f, contentWidth, 4.0f)
    }

    private fun drawSmallCircle(
        context: DrawContext,
        client: MinecraftClient,
        target: LivingEntity,
        x: Float,
        y: Float,
        targetWidth: Float,
        filled: Boolean,
    ) {
        val circleX = x + targetWidth - 38.0f
        val nameWidth = circleX - (x + 64.0f) - 5.0f
        drawSmallTargetPreview(context, target, x + 8.0f, y + 8.0f, 48.0f)
        drawText(context, targetName(target), x + 64.0f, y + 8.0f, nameWidth, 16.0f, NameText)
        drawText(context, healthSmall(target), x + 64.0f, y + 31.0f, 59.0f, 16.0f, NameText)
        drawHpCircle(context, target, circleX, y + 26.0f, 30.0f, 3.0f, filled)
    }

    private fun drawLargeBar(
        context: DrawContext,
        client: MinecraftClient,
        target: LivingEntity,
        x: Float,
        y: Float,
        targetWidth: Float,
        state: TargetHudSettings.State,
    ) {
        val contentWidth = targetWidth - 122.0f
        drawTargetModelBox(context, x + 8.0f, y + 8.0f, 98.0f, 91.0f)
        drawText(context, targetName(target), x + 114.0f, y + 8.0f, contentWidth, 16.0f, NameText)
        drawHpBar(context, target, x + 114.0f, y + 83.0f, contentWidth, 16.0f)
        drawText(
            context = context,
            text = healthFull(target),
            x = x + 114.0f,
            y = y + 83.0f,
            width = contentWidth,
            height = 16.0f,
            style = HpText,
            align = FigmaTextRenderer.HorizontalAlign.Center,
            verticalAlign = FigmaTextRenderer.VerticalAlign.Center,
        )
    }

    private fun drawLargeCircle(
        context: DrawContext,
        client: MinecraftClient,
        target: LivingEntity,
        x: Float,
        y: Float,
        targetWidth: Float,
        state: TargetHudSettings.State,
        filled: Boolean,
    ) {
        val circleX = x + targetWidth - 78.0f
        val nameWidth = circleX - (x + 114.0f) - 5.0f
        drawTargetModelBox(context, x + 8.0f, y + 8.0f, 98.0f, 91.0f)
        drawText(context, targetName(target), x + 114.0f, y + 8.0f, nameWidth, 16.0f, NameText)
        val circleY = y + 36.0f
        drawHpCircle(context, target, circleX, circleY, 64.0f, 4.0f, filled)
        drawText(
            context = context,
            text = healthSmall(target),
            x = circleX,
            y = circleY,
            width = 64.0f,
            height = 64.0f,
            style = HpText,
            align = FigmaTextRenderer.HorizontalAlign.Center,
            verticalAlign = FigmaTextRenderer.VerticalAlign.Center,
        )
    }

    private fun drawEquipmentStrip(context: DrawContext, target: LivingEntity, x: Float, y: Float) {
        panel(context, x, y, 132.0f, 20.0f, 5.0f)
        val stacks = listOf(
            target.mainHandStack,
            target.offHandStack,
            target.getEquippedStack(EquipmentSlot.HEAD),
            target.getEquippedStack(EquipmentSlot.CHEST),
            target.getEquippedStack(EquipmentSlot.LEGS),
            target.getEquippedStack(EquipmentSlot.FEET),
        )
        stacks.forEachIndexed { index, stack ->
            val slotX = x + 3.0f + index * 22.0f
            renderItem(context, stack, slotX, y + 2.0f)
            drawDurability(context, stack, slotX + 1.0f, y + 17.0f, 14.0f, 1.0f)
        }
    }

    private fun renderItem(context: DrawContext, stack: ItemStack, x: Float, y: Float) {
        if (stack.isEmpty) return
        context.matrices.pushMatrix()
        context.matrices.translate(x, y)
        context.drawItem(stack, 0, 0)
        context.matrices.popMatrix()
    }

    private fun drawDurability(context: DrawContext, stack: ItemStack, x: Float, y: Float, width: Float, height: Float) {
        if (stack.isEmpty || !stack.isDamageable) return
        val left = ((stack.maxDamage - stack.damage).toFloat() / stack.maxDamage.toFloat()).coerceIn(0.0f, 1.0f)
        HypnosiaRenderUtils.drawFigmaBox(context, x, y, width, height, 0.5f, 0xFF202020.toInt())
        HypnosiaRenderUtils.drawFigmaBox(context, x, y, width * left, height, 0.5f, durabilityColor(left))
    }

    private fun drawSmallTargetPreview(context: DrawContext, target: LivingEntity, x: Float, y: Float, size: Float) {
        HypnosiaRenderUtils.drawFigmaBox(context, x, y, size, size, 6.0f, PREVIEW, STROKE, 1.0f)
        val player = target as? AbstractClientPlayerEntity
        if (player == null) {
            drawText(
                context = context,
                text = targetName(target).take(1).uppercase(Locale.ENGLISH),
                x = x,
                y = y + 10.0f,
                width = size,
                height = 20.0f,
                style = NameText,
                align = FigmaTextRenderer.HorizontalAlign.Center,
                verticalAlign = FigmaTextRenderer.VerticalAlign.Center,
            )
            return
        }

        PlayerSkinDrawer.draw(context, player.skin, x.roundToInt(), y.roundToInt(), size.roundToInt())
    }

    private fun drawTargetModelBox(
        context: DrawContext,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ) {
        HypnosiaRenderUtils.drawFigmaBox(context, x, y, width, height, 6.0f, PREVIEW, STROKE, 1.0f)
    }

    private fun drawTargetModelGui(
        context: DrawContext,
        client: MinecraftClient,
        target: LivingEntity,
        fixedScale: Float,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        state: TargetHudSettings.State,
    ) {
        val player = target as? AbstractClientPlayerEntity ?: return

        val clipLeft = ((x + 1.0f) * fixedScale).roundToInt()
        val clipTop = ((y + 1.0f) * fixedScale).roundToInt()
        val clipRight = ((x + width - 1.0f) * fixedScale).roundToInt()
        val clipBottom = ((y + height - 1.0f) * fixedScale).roundToInt()
        context.enableScissor(clipLeft, clipTop, clipRight, clipBottom)
        try {
            val animatedYaw = if (state.modelSpin) {
                state.modelYaw + ((System.nanoTime() / 1_000_000_000.0) * 35.0).toFloat()
            } else {
                state.modelYaw
            }
            val centerX = x + width * 0.5f + state.modelOffsetX
            val topY = y - 4.0f + state.modelOffsetY
            val bottomY = y + height + 20.0f + state.modelOffsetY
            val modelHalfWidth = width * 0.5f
            val entityState = client.entityRenderDispatcher
                .getRenderer(player)
                .getAndUpdateRenderState(player, 1.0f)
            entityState.light = 15728880
            entityState.shadowPieces.clear()
            entityState.outlineColor = 0
            (entityState as? LivingEntityRenderState)?.let { livingState ->
                livingState.bodyYaw = 180.0f + animatedYaw
                livingState.relativeHeadYaw = 0.0f
                livingState.pitch = 0.0f
                livingState.width /= livingState.baseScale
                livingState.height /= livingState.baseScale
                livingState.baseScale = 1.0f
            }

            val pitchRotation = Quaternionf().rotateX(state.modelPitch * DEG_TO_RAD)
            val modelRotation = Quaternionf()
                .rotateZ(PI.toFloat())
                .mul(pitchRotation)
            context.addEntity(
                entityState,
                state.modelScale * fixedScale,
                Vector3f(0.0f, entityState.height * 0.5f, 0.0f),
                modelRotation,
                pitchRotation,
                ((centerX - modelHalfWidth) * fixedScale).roundToInt(),
                (topY * fixedScale).roundToInt(),
                ((centerX + modelHalfWidth) * fixedScale).roundToInt(),
                (bottomY * fixedScale).roundToInt(),
            )
        } finally {
            context.disableScissor()
        }
    }

    private fun drawHpBar(context: DrawContext, target: LivingEntity, x: Float, y: Float, width: Float, height: Float) {
        val percent = (target.health / target.maxHealth.coerceAtLeast(1.0f)).coerceIn(0.0f, 1.0f)
        HypnosiaRenderUtils.drawFigmaBox(context, x, y, width, height, height * 0.5f, TRACK)
        HypnosiaRenderUtils.drawFigmaBox(context, x, y, width * percent, height, height * 0.5f, HP)
    }

    private fun drawHpCircle(context: DrawContext, target: LivingEntity, x: Float, y: Float, size: Float, stroke: Float, filled: Boolean) {
        val percent = (target.health / target.maxHealth.coerceAtLeast(1.0f)).coerceIn(0.0f, 1.0f)
        val trackColor = 0x663F1010
        HypnosiaRenderUtils.drawFigmaBox(
            context = context,
            x = x,
            y = y,
            width = size,
            height = size,
            radius = size * 0.5f,
            bgColor = if (filled) 0x55FF1313 else 0x00131313,
            strokeColor = trackColor,
            strokeThickness = stroke,
        )
        drawCircleProgress(context, x, y, size, stroke, percent)
    }

    private fun drawCircleProgress(context: DrawContext, x: Float, y: Float, size: Float, stroke: Float, percent: Float) {
        val centerX = x + size * 0.5f
        val centerY = y + size * 0.5f
        val radius = size * 0.5f - stroke * 0.5f
        val steps = if (size <= 32.0f) 40 else 72
        val activeSteps = (steps * percent.coerceIn(0.0f, 1.0f)).roundToInt()
        val dot = (stroke + 0.8f).coerceAtLeast(2.5f)

        repeat(activeSteps) { index ->
            val angle = -PI * 0.5 + (PI * 2.0 * index / steps.toDouble())
            val dotX = centerX + cos(angle).toFloat() * radius - dot * 0.5f
            val dotY = centerY + sin(angle).toFloat() * radius - dot * 0.5f
            HypnosiaRenderUtils.drawFigmaBox(context, dotX, dotY, dot, dot, dot * 0.5f, HP)
        }
    }

    private fun panel(context: DrawContext, x: Float, y: Float, width: Float, height: Float, radius: Float) {
        HypnosiaRenderUtils.drawFigmaBox(context, x, y, width, height, radius, BG, STROKE, 1.0f)
    }

    private fun drawText(
        context: DrawContext,
        text: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        style: FigmaTextRenderer.FigmaTextStyle,
        align: FigmaTextRenderer.HorizontalAlign = FigmaTextRenderer.HorizontalAlign.Left,
        verticalAlign: FigmaTextRenderer.VerticalAlign = FigmaTextRenderer.VerticalAlign.Top,
    ) {
        FigmaTextRenderer.drawInBox(context, text, x, y, width, height, WHITE, style, align, verticalAlign)
    }

    private fun target(client: MinecraftClient): LivingEntity? {
        if (client.currentScreen is ChatScreen) {
            return client.player
        }

        val exact = (client.crosshairTarget as? EntityHitResult)?.entity as? AbstractClientPlayerEntity
        return exact?.takeIf { isUsableTarget(client, it) }
    }

    private fun isUsableTarget(client: MinecraftClient, target: LivingEntity?): Boolean {
        return target != null &&
            target != client.player &&
            !target.isRemoved &&
            !target.isDead &&
            target.health > 0.0f &&
            !target.isInvisible
    }

    private fun targetName(target: LivingEntity): String =
        StreamerModeSettings.displayName(
            (target as? AbstractClientPlayerEntity)?.gameProfile?.name
                ?: target.name.string,
        )

    private fun healthSmall(target: LivingEntity): String =
        String.format(Locale.US, "%.1f", target.health).removeSuffix(".0")

    private fun healthFull(target: LivingEntity): String =
        "${healthSmall(target)}/${healthSmallValue(target.maxHealth)}"

    private fun healthSmallValue(value: Float): String =
        String.format(Locale.US, "%.1f", value).removeSuffix(".0")

    private fun durabilityColor(left: Float): Int {
        val value = left.coerceIn(0.0f, 1.0f)
        val red = if (value < 0.5f) 255 else ((1.0f - value) * 2.0f * 255.0f).roundToInt()
        val green = if (value < 0.5f) (value * 2.0f * 210.0f).roundToInt() else 210
        return (0xFF shl 24) or (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or 0x2A
    }

    private fun spec(version: TargetHudSettings.Version, equipment: Boolean, targetName: String? = null): Spec {
        val targetY = if (equipment) {
            when (version) {
                TargetHudSettings.Version.V1, TargetHudSettings.Version.V2 -> 26.0f
                TargetHudSettings.Version.V3 -> 28.0f
                else -> 25.0f
            }
        } else {
            0.0f
        }
        val targetWidth = when (version) {
            TargetHudSettings.Version.V1 -> {
                val textWidth = targetName?.let { FigmaTextRenderer.width(it, NameText) } ?: 0.0f
                maxOf(198.0f, 64.0f + textWidth + 8.0f)
            }
            TargetHudSettings.Version.V2, TargetHudSettings.Version.V3 -> {
                val textWidth = targetName?.let { FigmaTextRenderer.width(it, NameText) } ?: 0.0f
                maxOf(198.0f, 64.0f + textWidth + 5.0f + 38.0f)
            }
            TargetHudSettings.Version.V4 -> {
                val textWidth = targetName?.let { FigmaTextRenderer.width(it, NameText) } ?: 0.0f
                maxOf(248.0f, 114.0f + textWidth + 8.0f)
            }
            TargetHudSettings.Version.V5, TargetHudSettings.Version.V6 -> {
                val textWidth = targetName?.let { FigmaTextRenderer.width(it, NameText) } ?: 0.0f
                maxOf(248.0f, 114.0f + textWidth + 5.0f + 78.0f)
            }
        }
        val targetHeight = when (version) {
            TargetHudSettings.Version.V1, TargetHudSettings.Version.V2, TargetHudSettings.Version.V3 -> 64.0f
            else -> 107.0f
        }
        val equipmentX = when (version) {
            TargetHudSettings.Version.V1 -> 34.0f
            TargetHudSettings.Version.V2, TargetHudSettings.Version.V3 -> 33.0f
            else -> 58.0f
        }
        val groupWidth = maxOf(targetWidth, equipmentX + 132.0f)
        val groupHeight = if (equipment) targetY + targetHeight else targetHeight
        return Spec(groupWidth, groupHeight, targetWidth, targetHeight, targetY, equipmentX, 0.0f)
    }

    private fun anchorX(screenW: Float, elementW: Float, normalized: Float): Float =
        HudRenderSupport.anchorX(screenW, elementW, normalized)

    private fun anchorY(screenH: Float, elementH: Float, normalized: Float): Float =
        HudRenderSupport.anchorY(screenH, elementH, normalized)

    private data class Spec(
        val width: Float,
        val height: Float,
        val targetWidth: Float,
        val targetHeight: Float,
        val targetY: Float,
        val equipmentX: Float,
        val equipmentY: Float,
    )

    private data class DragState(
        val offsetX: Float,
        val offsetY: Float,
        val width: Float,
        val height: Float,
    )

    private data class ModelDragState(
        val lastX: Float,
        val lastY: Float,
    )

    private const val DEG_TO_RAD = 0.017453292f
}
