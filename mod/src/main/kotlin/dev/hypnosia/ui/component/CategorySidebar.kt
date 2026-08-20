package dev.hypnosia.ui.component

import dev.hypnosia.HypnosiaClient
import dev.hypnosia.config.ThemeSettings
import dev.hypnosia.ui.animation.SpringColor
import dev.hypnosia.ui.animation.SpringFloat
import dev.hypnosia.ui.layout.BaseUiNode
import dev.hypnosia.ui.layout.Constraints
import dev.hypnosia.ui.layout.LayoutSpec
import dev.hypnosia.ui.layout.Rect
import dev.hypnosia.ui.layout.Size
import dev.hypnosia.ui.layout.SizeMode
import dev.hypnosia.ui.layout.UiInputState
import dev.hypnosia.ui.render.HypnosiaRenderUtils
import net.minecraft.client.gui.DrawContext
import net.minecraft.util.Identifier

enum class HypnosiaCategory(
    val displayName: String,
    val iconFileName: String,
    val order: Int,
) {
    Home("Home", "home.png", 0),
    Visuals("Visuals", "visuals.png", 1),
    World("World", "world.png", 2),
    Client("Client", "client.png", 3),
    Hud("Hud", "hud.png", 4),
    Other("Other", "other.png", 5),
}

class CategorySidebar(
    private val selectedCategory: () -> HypnosiaCategory?,
    private val onCategorySelected: (HypnosiaCategory) -> Unit,
) : BaseUiNode(
    LayoutSpec(
        width = SizeMode.Fixed(WIDTH),
        height = SizeMode.Fixed(HEIGHT),
    ),
) {
    private val buttons = HypnosiaCategory.entries.map(::CategoryButton)

    override fun measure(constraints: Constraints): Size {
        return constraints.constrain(Size(WIDTH, HEIGHT))
    }

    override fun layout(x: Float, y: Float, width: Float, height: Float) {
        super.layout(x, y, WIDTH, HEIGHT)
        buttons.forEach { button ->
            button.bounds = Rect(x + BUTTON_X, y + button.category.slotY(), BUTTON_SIZE, BUTTON_SIZE)
        }
    }

    override fun render(context: DrawContext) {
        buttons.forEach { it.render(context, selectedCategory() == it.category) }
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != 0) {
            return false
        }

        buttons.forEach { categoryButton ->
            val rect = categoryButton.bounds
            if (mouseX in rect.x..rect.right && mouseY in rect.y..rect.bottom) {
                onCategorySelected(categoryButton.category)
                return true
            }
        }
        return false
    }

    private class CategoryButton(
        val category: HypnosiaCategory,
    ) {
        var bounds: Rect = Rect(0.0f, 0.0f, BUTTON_SIZE, BUTTON_SIZE)

        private val background = SpringColor(SURFACE, stiffness = 380.0f, damping = 42.0f)
        private val stroke = SpringColor(STROKE, stiffness = 420.0f, damping = 42.0f)
        private val iconTint = SpringColor(MUTED_ICON, stiffness = 420.0f, damping = 42.0f)
        private val strokeWidth = SpringFloat(1.0f, stiffness = 520.0f, damping = 46.0f)
        private val hoverProgress = SpringFloat(0.0f, stiffness = 520.0f, damping = 46.0f)

        fun render(context: DrawContext, selected: Boolean) {
            val hovered = UiInputState.contains(bounds)
            val seconds = UiInputState.frameSeconds

            background.target = SURFACE
            stroke.target = if (selected) WHITE else STROKE
            iconTint.target = if (selected || hovered) WHITE else MUTED_ICON
            strokeWidth.target = if (selected) 2.0f else if (hovered) HOVER_STROKE_WIDTH else 1.0f
            hoverProgress.target = if (hovered && !selected) 1.0f else 0.0f

            val hover = hoverProgress.update(seconds)
            val visualX = bounds.x + HOVER_OFFSET_X * hover
            val visualY = bounds.y + HOVER_OFFSET_Y * hover
            val visualSize = BUTTON_SIZE + (HOVER_BUTTON_SIZE - BUTTON_SIZE) * hover
            val iconScale = 1.0f + (HOVER_BUTTON_SIZE / BUTTON_SIZE - 1.0f) * hover

            HypnosiaRenderUtils.drawThemedBox(
                context = context,
                x = visualX,
                y = visualY,
                width = visualSize,
                height = visualSize,
                radius = BUTTON_RADIUS + (HOVER_BUTTON_RADIUS - BUTTON_RADIUS) * hover,
                bgColor = background.update(seconds),
                strokeColor = stroke.update(seconds),
                strokeThickness = strokeWidth.update(seconds),
                role = ThemeSettings.ThemeRole.ICON_BUTTON,
            )

            HypnosiaRenderUtils.drawIconTexture(
                context = context,
                identifier = Identifier.of(HypnosiaClient.MOD_ID, "textures/gui/icons/${category.iconFileName}"),
                x = visualX + category.iconOffsetX() * iconScale,
                y = visualY + category.iconOffsetY() * iconScale,
                width = category.iconWidth() * iconScale,
                height = category.iconHeight() * iconScale,
                tintColor = iconTint.update(seconds),
            )
        }
    }

    companion object {
        const val WIDTH = 50.5f
        const val HEIGHT = 464.0f

        private const val BUTTON_X = 7.5f
        private const val BUTTON_SIZE = 35.0f
        private const val BUTTON_RADIUS = 10.0f
        private const val HOVER_BUTTON_SIZE = 38.255144f
        private const val HOVER_BUTTON_RADIUS = 10.4f
        private const val HOVER_OFFSET_X = 0.7774563f
        private const val HOVER_OFFSET_Y = -1.1275725f
        private const val HOVER_STROKE_WIDTH = 1.04f

        private const val SURFACE = 0xFF0D0D0D.toInt()
        private const val STROKE = 0xFF272727.toInt()
        private const val MUTED_ICON = 0xFF8E8E8E.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
    }
}

private fun HypnosiaCategory.slotY(): Float {
    return when (this) {
        HypnosiaCategory.Home -> 7.5f
        HypnosiaCategory.Visuals -> 59.5f
        HypnosiaCategory.World -> 102.5f
        HypnosiaCategory.Client -> 145.5f
        HypnosiaCategory.Hud -> 188.5f
        HypnosiaCategory.Other -> 231.5f
    }
}

private fun HypnosiaCategory.iconOffsetX(): Float {
    return when (this) {
        HypnosiaCategory.Visuals -> 4.0f
        HypnosiaCategory.Other -> 3.0f
        else -> 2.0f
    }
}

private fun HypnosiaCategory.iconOffsetY(): Float {
    return when (this) {
        HypnosiaCategory.Visuals -> 4.0f
        HypnosiaCategory.Other -> 4.0f
        else -> 2.0f
    }
}

private fun HypnosiaCategory.iconWidth(): Float {
    return when (this) {
        HypnosiaCategory.Visuals -> 27.0f
        HypnosiaCategory.Other -> 24.0f
        else -> 31.0f
    }
}

private fun HypnosiaCategory.iconHeight(): Float {
    return when (this) {
        HypnosiaCategory.Visuals -> 27.0f
        HypnosiaCategory.Other -> 25.0f
        else -> 31.0f
    }
}
