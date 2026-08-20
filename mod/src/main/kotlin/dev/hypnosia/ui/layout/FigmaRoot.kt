package dev.hypnosia.ui.layout

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import kotlin.math.max

enum class RootAnchor {
    TopLeft,
    Center,
}

class FigmaRoot(
    private val designWidth: Float,
    private val designHeight: Float,
    private val child: UiNode,
    private val anchor: RootAnchor = RootAnchor.Center,
    private val renderScale: Float = 1.0f,
) {
    private var originX = 0.0f
    private var originY = 0.0f
    private var scale = 1.0f

    fun layout(client: MinecraftClient = MinecraftClient.getInstance()) {
        val window = client.window
        val guiScale = window.scaleFactor.toFloat().coerceAtLeast(1.0f)
        val rootScale = renderScale.coerceAtLeast(0.1f)
        val scaledWidth = window.scaledWidth.toFloat()
        val scaledHeight = window.scaledHeight.toFloat()
        val logicalWidth = designWidth * rootScale / guiScale
        val logicalHeight = designHeight * rootScale / guiScale

        scale = rootScale / guiScale
        originX = when (anchor) {
            RootAnchor.TopLeft -> 0.0f
            RootAnchor.Center -> (scaledWidth - logicalWidth) * 0.5f
        }
        originY = when (anchor) {
            RootAnchor.TopLeft -> 0.0f
            RootAnchor.Center -> (scaledHeight - logicalHeight) * 0.5f
        }

        child.measure(Constraints(designWidth, designHeight))
        child.layout(0.0f, 0.0f, designWidth, designHeight)
    }

    fun render(context: DrawContext) {
        context.matrices.pushMatrix()
        context.matrices.translate(originX, originY)
        context.matrices.scale(scale, scale)
        child.render(context)
        context.matrices.popMatrix()
    }

    fun toFigmaLocal(mouseX: Double, mouseY: Double): Pair<Float, Float> {
        return (((mouseX.toFloat() - originX) / max(scale, 0.0001f)) to
            ((mouseY.toFloat() - originY) / max(scale, 0.0001f)))
    }

    fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val (localX, localY) = toFigmaLocal(mouseX, mouseY)
        return child.mouseClicked(localX, localY, button)
    }

    fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val (localX, localY) = toFigmaLocal(mouseX, mouseY)
        return child.mouseReleased(localX, localY, button)
    }

    fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        val (localX, localY) = toFigmaLocal(mouseX, mouseY)
        val localDeltaX = (deltaX.toFloat() / max(scale, 0.0001f))
        val localDeltaY = (deltaY.toFloat() / max(scale, 0.0001f))
        return child.mouseDragged(localX, localY, button, localDeltaX, localDeltaY)
    }

    fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val (localX, localY) = toFigmaLocal(mouseX, mouseY)
        return child.mouseScrolled(
            mouseX = localX,
            mouseY = localY,
            horizontalAmount = horizontalAmount.toFloat(),
            verticalAmount = verticalAmount.toFloat(),
        )
    }

    fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        return child.keyPressed(keyCode, scanCode, modifiers)
    }

    fun charTyped(chr: Char, modifiers: Int): Boolean {
        return child.charTyped(chr, modifiers)
    }

    fun onScreenClose() {
        child.onScreenClose()
    }
}
