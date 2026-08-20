package dev.hypnosia.ui.layout

import dev.hypnosia.ui.animation.SpringFloat
import dev.hypnosia.ui.render.HypnosiaRenderUtils
import dev.hypnosia.ui.render.HypnosiaScissor
import net.minecraft.client.gui.DrawContext
import kotlin.math.max

class ScrollColumn(
    private val padding: Insets = Insets.Zero,
    private val gap: Float = 0.0f,
    private val crossAxisAlignment: Alignment = Alignment.Start,
    private val scrollStep: Float = 42.0f,
    private val scrollbar: Boolean = true,
    override val layoutSpec: LayoutSpec = LayoutSpec(width = SizeMode.Fill, height = SizeMode.Fill),
) : BaseUiNode(layoutSpec) {
    private val children = mutableListOf<UiNode>()
    private val scroll = SpringFloat(0.0f, stiffness = 320.0f, damping = 38.0f)

    private var measuredChildren: List<Size> = emptyList()
    private var contentHeight = 0.0f
    private var maxScroll = 0.0f

    fun child(node: UiNode): ScrollColumn {
        children += node
        return this
    }

    fun children(nodes: Iterable<UiNode>): ScrollColumn {
        children += nodes
        return this
    }

    fun replaceChildren(nodes: Iterable<UiNode>): ScrollColumn {
        children.clear()
        children += nodes
        measuredChildren = emptyList()
        contentHeight = 0.0f
        maxScroll = 0.0f
        scroll.snap(0.0f)
        return this
    }

    override fun measure(constraints: Constraints): Size {
        val childConstraints = Constraints(
            maxWidth = (constraints.maxWidth - padding.horizontal).coerceAtLeast(0.0f),
            maxHeight = Float.POSITIVE_INFINITY,
        )

        measuredChildren = children.map { it.measure(childConstraints) }
        val totalGap = gap * (children.size - 1).coerceAtLeast(0)
        val contentWidth = measuredChildren.maxOfOrNull { it.width } ?: 0.0f
        contentHeight = measuredChildren.sumOf { it.height.toDouble() }.toFloat() + totalGap

        val hugWidth = contentWidth + padding.horizontal
        val hugHeight = contentHeight + padding.vertical
        return constraints.constrain(
            Size(
                width = resolveOwnSize(layoutSpec.width, hugWidth, constraints.maxWidth),
                height = resolveOwnSize(layoutSpec.height, hugHeight, constraints.maxHeight),
            ),
        )
    }

    override fun layout(x: Float, y: Float, width: Float, height: Float) {
        super.layout(x, y, width, height)
        updateMaxScroll()
        scroll.target = scroll.target.coerceIn(0.0f, maxScroll)
        layoutChildren(scroll.value)
    }

    override fun render(context: DrawContext) {
        val offset = scroll.update(UiInputState.frameSeconds).coerceIn(0.0f, maxScroll)
        if (offset != scroll.value) {
            scroll.snap(offset)
        }
        layoutChildren(offset)

        HypnosiaScissor.withLocalRect(context, bounds) {
            children.forEach { child ->
                if (child.bounds.bottom >= bounds.y && child.bounds.y <= bounds.bottom) {
                    child.render(context)
                }
            }
        }

        if (scrollbar && maxScroll > 0.5f) {
            renderScrollbar(context, offset)
        }
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (!contains(mouseX, mouseY)) {
            return false
        }
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
        if (!contains(mouseX, mouseY) || maxScroll <= 0.0f) {
            return false
        }

        scroll.target = (scroll.target - verticalAmount * scrollStep).coerceIn(0.0f, maxScroll)
        return true
    }

    private fun layoutChildren(scrollOffset: Float) {
        val contentX = bounds.x + padding.left
        val contentY = bounds.y + padding.top
        val contentWidth = (bounds.width - padding.horizontal).coerceAtLeast(0.0f)
        val contentHeight = (bounds.height - padding.vertical).coerceAtLeast(0.0f)
        val sizes = if (measuredChildren.size == children.size) measuredChildren else children.map {
            it.measure(Constraints(contentWidth, Float.POSITIVE_INFINITY))
        }

        var cursorY = contentY - scrollOffset
        children.forEachIndexed { index, child ->
            val measured = sizes[index]
            val childWidth = resolveCrossSize(child.layoutSpec.width, measured.width, contentWidth)
            val childHeight = measured.height
            val childX = alignCross(contentX, contentWidth, childWidth)
            child.layout(childX, cursorY, childWidth, childHeight)
            cursorY += childHeight + gap
        }
    }

    private fun updateMaxScroll() {
        val viewportContentHeight = (bounds.height - padding.vertical).coerceAtLeast(0.0f)
        maxScroll = (contentHeight - viewportContentHeight).coerceAtLeast(0.0f)
    }

    private fun renderScrollbar(context: DrawContext, offset: Float) {
        val trackTop = bounds.y + 10.0f
        val trackHeight = (bounds.height - 20.0f).coerceAtLeast(24.0f)
        val visibleRatio = (bounds.height - padding.vertical).coerceAtLeast(1.0f) / max(contentHeight, 1.0f)
        val thumbHeight = (trackHeight * visibleRatio).coerceIn(28.0f, trackHeight)
        val travel = (trackHeight - thumbHeight).coerceAtLeast(0.0f)
        val thumbProgress = if (maxScroll <= 0.0f) 0.0f else offset / maxScroll
        val thumbY = trackTop + travel * thumbProgress

        HypnosiaRenderUtils.drawFigmaBox(
            context = context,
            x = bounds.right - 9.0f,
            y = trackTop,
            width = 3.0f,
            height = trackHeight,
            radius = 2.0f,
            bgColor = 0x332A2A33,
        )
        HypnosiaRenderUtils.drawFigmaBox(
            context = context,
            x = bounds.right - 10.0f,
            y = thumbY,
            width = 5.0f,
            height = thumbHeight,
            radius = 3.0f,
            bgColor = 0x99EDEDF1.toInt(),
        )
    }

    private fun contains(mouseX: Float, mouseY: Float): Boolean {
        return mouseX >= bounds.x && mouseX <= bounds.right && mouseY >= bounds.y && mouseY <= bounds.bottom
    }

    private fun resolveOwnSize(mode: SizeMode, hug: Float, maxSize: Float): Float {
        return when (mode) {
            is SizeMode.Fixed -> mode.pixels
            SizeMode.Hug -> if (maxSize.isFinite()) hug.coerceAtMost(maxSize) else hug
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

fun scrollColumn(
    width: SizeMode = SizeMode.Fill,
    height: SizeMode = SizeMode.Fill,
    padding: Insets = Insets.Zero,
    gap: Float = 0.0f,
    alignment: Alignment = Alignment.Start,
    scrollStep: Float = 42.0f,
    scrollbar: Boolean = true,
    init: ScrollColumn.() -> Unit = {},
): ScrollColumn {
    return ScrollColumn(
        padding = padding,
        gap = gap,
        crossAxisAlignment = alignment,
        scrollStep = scrollStep,
        scrollbar = scrollbar,
        layoutSpec = LayoutSpec(width, height),
    ).apply(init)
}
