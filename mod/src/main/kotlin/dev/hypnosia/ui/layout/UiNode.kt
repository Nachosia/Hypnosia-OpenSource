package dev.hypnosia.ui.layout

import net.minecraft.client.gui.DrawContext

interface UiNode {
    var bounds: Rect
    val layoutSpec: LayoutSpec

    fun measure(constraints: Constraints): Size

    fun layout(x: Float, y: Float, width: Float, height: Float) {
        bounds = Rect(x, y, width, height)
    }

    fun render(context: DrawContext)

    fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean = false

    fun mouseReleased(mouseX: Float, mouseY: Float, button: Int): Boolean = false

    fun mouseDragged(mouseX: Float, mouseY: Float, button: Int, deltaX: Float, deltaY: Float): Boolean = false

    fun mouseScrolled(mouseX: Float, mouseY: Float, horizontalAmount: Float, verticalAmount: Float): Boolean = false

    fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean = false

    fun charTyped(chr: Char, modifiers: Int): Boolean = false

    fun onScreenClose() {}
}

abstract class BaseUiNode(
    override val layoutSpec: LayoutSpec = LayoutSpec(),
) : UiNode {
    override var bounds: Rect = Rect(0.0f, 0.0f, 0.0f, 0.0f)
}

class FigmaBoxNode(
    private val preferredWidth: Float,
    private val preferredHeight: Float,
    private val renderer: (DrawContext, Rect) -> Unit,
    layoutSpec: LayoutSpec = LayoutSpec(
        width = SizeMode.Fixed(preferredWidth),
        height = SizeMode.Fixed(preferredHeight),
    ),
) : BaseUiNode(layoutSpec) {
    override fun measure(constraints: Constraints): Size {
        return constraints.constrain(Size(preferredWidth, preferredHeight))
    }

    override fun render(context: DrawContext) {
        renderer(context, bounds)
    }
}
