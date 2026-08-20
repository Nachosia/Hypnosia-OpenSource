package dev.hypnosia.ui

import dev.hypnosia.ui.layout.HypnosiaHomeV2Layout
import dev.hypnosia.ui.layout.UiInputState
import dev.hypnosia.visual.image.ImageRenderModule
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.input.CharInput
import net.minecraft.client.input.KeyInput
import net.minecraft.text.Text

class HypnosiaHomeV2Screen : Screen(Text.literal("Hypnosia Home V2")) {
    private val rootLayout = HypnosiaHomeV2Layout.create()
    private var lastFrameNanos = 0L
    override fun shouldPause(): Boolean = false

    override fun removed() {
        ImageRenderModule.isV2GuiOpen = false
        rootLayout.onScreenClose()
        super.removed()
    }

    override fun close() {
        ImageRenderModule.isV2GuiOpen = false
        rootLayout.onScreenClose()
        super.close()
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Keep the world visible behind the prototype, same as the current Hypnosia menu.
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        ImageRenderModule.isV2GuiOpen = true
        renderBackground(context, mouseX, mouseY, delta)
        val client = MinecraftClient.getInstance()
        val now = System.nanoTime()
        val frameSeconds = if (lastFrameNanos == 0L) {
            0.0f
        } else {
            ((now - lastFrameNanos) / 1_000_000_000.0f).coerceIn(0.0f, 0.05f)
        }
        lastFrameNanos = now

        rootLayout.layout(client)
        val (localMouseX, localMouseY) = rootLayout.toFigmaLocal(mouseX.toDouble(), mouseY.toDouble())
        UiInputState.update(localMouseX, localMouseY, frameSeconds)

        dev.hypnosia.ui.render.HypnosiaRenderUtils.captureThemeBackdrop(context)

        HypnosiaHomeV2Layout.updateDrawer()
        ImageRenderModule.renderOverlay(context)

        rootLayout.render(context)
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        return rootLayout.mouseClicked(click.x(), click.y(), click.button()) || super.mouseClicked(click, doubled)
    }

    override fun mouseReleased(click: Click): Boolean {
        return rootLayout.mouseReleased(click.x(), click.y(), click.button()) || super.mouseReleased(click)
    }

    override fun mouseDragged(click: Click, offsetX: Double, offsetY: Double): Boolean {
        return rootLayout.mouseDragged(click.x(), click.y(), click.button(), offsetX, offsetY) ||
            super.mouseDragged(click, offsetX, offsetY)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        return rootLayout.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount) ||
            super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun keyPressed(input: KeyInput): Boolean {
        return rootLayout.keyPressed(input.key, input.scancode, input.modifiers) || super.keyPressed(input)
    }

    override fun charTyped(input: CharInput): Boolean {
        val text = input.asString()
        return text.any { rootLayout.charTyped(it, input.modifiers) } || super.charTyped(input)
    }


}
