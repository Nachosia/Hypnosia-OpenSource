package dev.hypnosia.ui.layout

import net.minecraft.client.gui.DrawContext
import kotlin.math.max

class LayoutContainer(
    private val axis: Axis,
    private val padding: Insets = Insets.Zero,
    private val gap: Float = 0.0f,
    private val crossAxisAlignment: Alignment = Alignment.Start,
    override val layoutSpec: LayoutSpec = LayoutSpec(),
) : BaseUiNode(layoutSpec) {
    private val children = mutableListOf<UiNode>()
    private var measuredChildren: List<Size> = emptyList()

    fun child(node: UiNode): LayoutContainer {
        children += node
        return this
    }

    fun children(nodes: Iterable<UiNode>): LayoutContainer {
        children += nodes
        return this
    }

    override fun measure(constraints: Constraints): Size {
        measuredChildren = children.map { child ->
            val childConstraints = Constraints(
                maxWidth = (constraints.maxWidth - padding.horizontal).coerceAtLeast(0.0f),
                maxHeight = (constraints.maxHeight - padding.vertical).coerceAtLeast(0.0f),
            )
            child.measure(childConstraints)
        }

        val totalGap = gap * (children.size - 1).coerceAtLeast(0)
        val contentWidth: Float
        val contentHeight: Float

        if (axis == Axis.Horizontal) {
            contentWidth = measuredChildren.sumOf { it.width.toDouble() }.toFloat() + totalGap
            contentHeight = measuredChildren.maxOfOrNull { it.height } ?: 0.0f
        } else {
            contentWidth = measuredChildren.maxOfOrNull { it.width } ?: 0.0f
            contentHeight = measuredChildren.sumOf { it.height.toDouble() }.toFloat() + totalGap
        }

        val hugSize = Size(contentWidth + padding.horizontal, contentHeight + padding.vertical)
        return constraints.constrain(
            Size(
                width = resolveOwnSize(layoutSpec.width, hugSize.width, constraints.maxWidth),
                height = resolveOwnSize(layoutSpec.height, hugSize.height, constraints.maxHeight),
            ),
        )
    }

    override fun layout(x: Float, y: Float, width: Float, height: Float) {
        super.layout(x, y, width, height)

        if (children.isEmpty()) {
            return
        }

        val contentX = x + padding.left
        val contentY = y + padding.top
        val contentWidth = (width - padding.horizontal).coerceAtLeast(0.0f)
        val contentHeight = (height - padding.vertical).coerceAtLeast(0.0f)
        val sizes = if (measuredChildren.size == children.size) measuredChildren else children.map {
            it.measure(Constraints(contentWidth, contentHeight))
        }

        if (axis == Axis.Horizontal) {
            layoutHorizontal(contentX, contentY, contentWidth, contentHeight, sizes)
        } else {
            layoutVertical(contentX, contentY, contentWidth, contentHeight, sizes)
        }
    }

    override fun render(context: DrawContext) {
        children.forEach { it.render(context) }
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        return children.asReversed().any { it.mouseClicked(mouseX, mouseY, button) }
    }

    override fun mouseReleased(mouseX: Float, mouseY: Float, button: Int): Boolean {
        return children.asReversed().any { it.mouseReleased(mouseX, mouseY, button) }
    }

    override fun mouseDragged(mouseX: Float, mouseY: Float, button: Int, deltaX: Float, deltaY: Float): Boolean {
        return children.asReversed().any { it.mouseDragged(mouseX, mouseY, button, deltaX, deltaY) }
    }

    override fun mouseScrolled(
        mouseX: Float,
        mouseY: Float,
        horizontalAmount: Float,
        verticalAmount: Float,
    ): Boolean {
        return children.asReversed().any { it.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount) }
    }

    private fun layoutHorizontal(
        contentX: Float,
        contentY: Float,
        contentWidth: Float,
        contentHeight: Float,
        sizes: List<Size>,
    ) {
        val fixedWidth = children.indices.sumOf { index ->
            if (children[index].layoutSpec.width is SizeMode.Fill) 0.0 else sizes[index].width.toDouble()
        }.toFloat()
        val fillCount = children.count { it.layoutSpec.width is SizeMode.Fill }
        val remaining = (contentWidth - fixedWidth - gap * (children.size - 1).coerceAtLeast(0)).coerceAtLeast(0.0f)
        val fillWidth = if (fillCount == 0) 0.0f else remaining / fillCount

        var cursorX = contentX
        children.forEachIndexed { index, child ->
            val measured = sizes[index]
            val childWidth = if (child.layoutSpec.width is SizeMode.Fill) fillWidth else measured.width
            val childHeight = resolveCrossSize(child.layoutSpec.height, measured.height, contentHeight)
            val childY = alignCross(contentY, contentHeight, childHeight)
            child.layout(cursorX, childY, childWidth, childHeight)
            cursorX += childWidth + gap
        }
    }

    private fun layoutVertical(
        contentX: Float,
        contentY: Float,
        contentWidth: Float,
        contentHeight: Float,
        sizes: List<Size>,
    ) {
        val fixedHeight = children.indices.sumOf { index ->
            if (children[index].layoutSpec.height is SizeMode.Fill) 0.0 else sizes[index].height.toDouble()
        }.toFloat()
        val fillCount = children.count { it.layoutSpec.height is SizeMode.Fill }
        val remaining = (contentHeight - fixedHeight - gap * (children.size - 1).coerceAtLeast(0)).coerceAtLeast(0.0f)
        val fillHeight = if (fillCount == 0) 0.0f else remaining / fillCount

        var cursorY = contentY
        children.forEachIndexed { index, child ->
            val measured = sizes[index]
            val childWidth = resolveCrossSize(child.layoutSpec.width, measured.width, contentWidth)
            val childHeight = if (child.layoutSpec.height is SizeMode.Fill) fillHeight else measured.height
            val childX = alignCross(contentX, contentWidth, childWidth)
            child.layout(childX, cursorY, childWidth, childHeight)
            cursorY += childHeight + gap
        }
    }

    private fun resolveOwnSize(mode: SizeMode, hug: Float, maxSize: Float): Float {
        return when (mode) {
            is SizeMode.Fixed -> mode.pixels
            SizeMode.Hug -> hug
            SizeMode.Fill -> if (maxSize.isFinite()) maxSize else hug
        }
    }

    private fun resolveCrossSize(mode: SizeMode, measured: Float, available: Float): Float {
        return when {
            crossAxisAlignment == Alignment.Stretch -> available
            mode is SizeMode.Fixed -> mode.pixels
            mode is SizeMode.Fill -> available
            else -> measured
        }
    }

    private fun alignCross(start: Float, available: Float, childSize: Float): Float {
        return when (crossAxisAlignment) {
            Alignment.Start, Alignment.Stretch -> start
            Alignment.Center -> start + max(0.0f, available - childSize) * 0.5f
            Alignment.End -> start + max(0.0f, available - childSize)
        }
    }
}

fun column(
    width: SizeMode = SizeMode.Hug,
    height: SizeMode = SizeMode.Hug,
    padding: Insets = Insets.Zero,
    gap: Float = 0.0f,
    alignment: Alignment = Alignment.Start,
    init: LayoutContainer.() -> Unit = {},
): LayoutContainer {
    return LayoutContainer(
        axis = Axis.Vertical,
        padding = padding,
        gap = gap,
        crossAxisAlignment = alignment,
        layoutSpec = LayoutSpec(width, height),
    ).apply(init)
}

fun row(
    width: SizeMode = SizeMode.Hug,
    height: SizeMode = SizeMode.Hug,
    padding: Insets = Insets.Zero,
    gap: Float = 0.0f,
    alignment: Alignment = Alignment.Start,
    init: LayoutContainer.() -> Unit = {},
): LayoutContainer {
    return LayoutContainer(
        axis = Axis.Horizontal,
        padding = padding,
        gap = gap,
        crossAxisAlignment = alignment,
        layoutSpec = LayoutSpec(width, height),
    ).apply(init)
}
