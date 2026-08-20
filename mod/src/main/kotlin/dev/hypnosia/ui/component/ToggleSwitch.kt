package dev.hypnosia.ui.component

import dev.hypnosia.ui.animation.AnimatedFloat
import dev.hypnosia.ui.animation.FigmaAnimation
import dev.hypnosia.ui.animation.SpringFloat
import dev.hypnosia.ui.layout.BaseUiNode
import dev.hypnosia.ui.layout.Constraints
import dev.hypnosia.ui.layout.LayoutSpec
import dev.hypnosia.ui.layout.Size
import dev.hypnosia.ui.layout.SizeMode
import dev.hypnosia.ui.layout.UiInputState
import dev.hypnosia.ui.render.HypnosiaRenderUtils
import net.minecraft.client.gui.DrawContext
import kotlin.math.min

class ToggleSwitch(
    initialChecked: Boolean = false,
    private val accentColor: Int = 0xFFFFFFFF.toInt(),
    private val onChanged: (Boolean) -> Unit = {},
) : BaseUiNode(
    LayoutSpec(
        width = SizeMode.Fixed(WIDTH),
        height = SizeMode.Fixed(HEIGHT),
    ),
) {
    var checked: Boolean = initialChecked
        private set

    private val progress = SpringFloat(if (initialChecked) 1.0f else 0.0f, stiffness = 520.0f, damping = 42.0f)
    private val hover = AnimatedFloat(0.0f, speed = 16.0f)

    override fun measure(constraints: Constraints): Size {
        return constraints.constrain(Size(WIDTH, HEIGHT))
    }

    override fun render(context: DrawContext) {
        val hovered = UiInputState.contains(bounds)
        progress.target = if (checked) 1.0f else 0.0f
        hover.target = if (hovered) 1.0f else 0.0f

        val switchProgress = progress.update(UiInputState.frameSeconds)
        val hoverProgress = hover.update(UiInputState.frameSeconds)

        val trackColor = FigmaAnimation.lerpArgb(0xFF0D0D0D.toInt(), accentColor, switchProgress)
        val trackStroke = FigmaAnimation.lerpArgb(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), switchProgress)
        val thumbColor = FigmaAnimation.lerpArgb(0xFFFFFFFF.toInt(), 0xFF0D0D0D.toInt(), switchProgress)

        HypnosiaRenderUtils.drawFigmaBox(
            context = context,
            x = bounds.x,
            y = bounds.y,
            width = bounds.width,
            height = bounds.height,
            radius = bounds.height * 0.5f,
            bgColor = trackColor,
            strokeColor = trackStroke,
            strokeThickness = 1.0f,
        )

        val baseThumbSize = 18.0f
        val thumbSize = baseThumbSize + hoverProgress * 2.0f
        val travel = bounds.width - PADDING * 2.0f - baseThumbSize
        val thumbX = bounds.x + PADDING + travel * switchProgress - hoverProgress
        val thumbY = bounds.y + (bounds.height - thumbSize) * 0.5f

        if (checked || hoverProgress > 0.001f) {
            HypnosiaRenderUtils.drawSdfShadowBox(
                context = context,
                x = thumbX,
                y = thumbY,
                width = thumbSize,
                height = thumbSize,
                radius = thumbSize * 0.5f,
                shadowSpread = 0.0f,
                shadowBlur = 6.0f,
                color = 0x22000000,
            )
        }

        HypnosiaRenderUtils.drawFigmaBox(
            context = context,
            x = thumbX,
            y = thumbY,
            width = thumbSize,
            height = thumbSize,
            radius = min(thumbSize, thumbSize) * 0.5f,
            bgColor = thumbColor,
            strokeColor = 0x1A000000,
            strokeThickness = 1.0f,
        )
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != 0 || mouseX !in bounds.x..bounds.right || mouseY !in bounds.y..bounds.bottom) {
            return false
        }

        checked = !checked
        progress.target = if (checked) 1.0f else 0.0f
        onChanged(checked)
        return true
    }

    fun setChecked(value: Boolean, snap: Boolean = false) {
        checked = value
        progress.target = if (value) 1.0f else 0.0f
        if (snap) {
            progress.snap(progress.target)
        }
        onChanged(checked)
    }

    companion object {
        const val WIDTH = 46.0f
        const val HEIGHT = 26.0f
        private const val PADDING = 4.0f
    }
}
