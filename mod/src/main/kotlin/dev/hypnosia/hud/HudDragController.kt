package dev.hypnosia.hud

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ChatScreen
import org.lwjgl.glfw.GLFW

class HudDragController(private val module: HudModuleSettings.Module) {
    private var active: DragState? = null
    private var wasMouseDown = false

    fun tick(client: MinecraftClient, width: Float, height: Float) {
        val window = client.window
        val isChatOpen = client.currentScreen is ChatScreen
        val isMouseDown = GLFW.glfwGetMouseButton(window.handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
        if (!isChatOpen || client.player == null || !HudModuleSettings.isEnabled(module)) {
            if (active != null) HudModuleSettings.saveNow()
            active = null
            wasMouseDown = isMouseDown
            return
        }

        val fixedScale = HudRenderSupport.fixedScale(client)
        val screenW = window.framebufferWidth.toFloat()
        val screenH = window.framebufferHeight.toFloat()
        val mouseX = HudRenderSupport.mouseFixedX(client, fixedScale)
        val mouseY = HudRenderSupport.mouseFixedY(client, fixedScale)
        val x = HudRenderSupport.anchorX(screenW, width, HudModuleSettings.state(module).x)
        val y = HudRenderSupport.anchorY(screenH, height, HudModuleSettings.state(module).y)

        if (isMouseDown && !wasMouseDown) {
            if (mouseX in x..(x + width) && mouseY in y..(y + height)) {
                active = DragState(mouseX - x, mouseY - y, width, height)
            }
        } else if (isMouseDown) {
            active?.let { drag ->
                val maxX = (screenW - drag.width).coerceAtLeast(1.0f)
                val maxY = (screenH - drag.height).coerceAtLeast(1.0f)
                val snappedX = HudRenderSupport.snapPixel(mouseX - drag.offsetX).coerceIn(0.0f, maxX)
                val snappedY = HudRenderSupport.snapPixel(mouseY - drag.offsetY).coerceIn(0.0f, maxY)
                HudModuleSettings.setPosition(module, snappedX / maxX, snappedY / maxY, persist = false)
            }
        } else {
            if (active != null) HudModuleSettings.saveNow()
            active = null
        }

        wasMouseDown = isMouseDown
    }

    private data class DragState(
        val offsetX: Float,
        val offsetY: Float,
        val width: Float,
        val height: Float,
    )
}
