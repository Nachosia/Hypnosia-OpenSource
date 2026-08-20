package dev.hypnosia.ui.component

import dev.hypnosia.HypnosiaClient
import dev.hypnosia.config.ThemeSettings
import dev.hypnosia.ui.animation.SpringColor
import dev.hypnosia.ui.layout.BaseUiNode
import dev.hypnosia.ui.layout.Constraints
import dev.hypnosia.ui.layout.LayoutSpec
import dev.hypnosia.ui.layout.Rect
import dev.hypnosia.ui.layout.Size
import dev.hypnosia.ui.layout.SizeMode
import dev.hypnosia.ui.render.FigmaTextRenderer
import dev.hypnosia.ui.render.HypnosiaRenderUtils
import net.minecraft.client.gui.DrawContext
import net.minecraft.util.Identifier

class ModuleRow(
    private val title: String,
    @Suppress("unused")
    private val description: String = "",
    @Suppress("unused")
    private val iconPath: String = "plug_socket.png",
    isActive: Boolean = false,
    private val isSettingsOpen: () -> Boolean = { false },
    private val settingsEnabled: Boolean = true,
    private val onActiveChanged: (Boolean) -> Unit = {},
    private val onSettingsChanged: (Boolean) -> Unit = {},
) : BaseUiNode(
    LayoutSpec(
        width = SizeMode.Fixed(WIDTH),
        height = SizeMode.Fixed(HEIGHT),
    ),
) {
    private var active = isActive
    var alpha: Float = 1.0f

    private val strokeColor = SpringColor(if (isActive) SELECTED_STROKE else IDLE_STROKE, stiffness = 420.0f, damping = 42.0f)
    private val headerColor = SpringColor(if (isActive) SELECTED_HEADER else IDLE_HEADER, stiffness = 420.0f, damping = 42.0f)
    private val titleColor = SpringColor(if (isActive) SELECTED_TITLE else IDLE_TITLE, stiffness = 420.0f, damping = 42.0f)

    override fun measure(constraints: Constraints): Size {
        return constraints.constrain(Size(WIDTH, HEIGHT))
    }

    override fun layout(x: Float, y: Float, width: Float, height: Float) {
        super.layout(x, y, WIDTH, HEIGHT)
    }

    override fun render(context: DrawContext) {
        val drawAlpha = alpha.coerceIn(0.0f, 1.0f)
        val seconds = dev.hypnosia.ui.layout.UiInputState.frameSeconds
        val settingsOpen = isSettingsOpen()

        strokeColor.target = if (active) SELECTED_STROKE else IDLE_STROKE
        headerColor.target = if (active) SELECTED_HEADER else IDLE_HEADER
        titleColor.target = if (active) SELECTED_TITLE else IDLE_TITLE

        HypnosiaRenderUtils.drawThemedBox(
            context = context,
            x = bounds.x,
            y = bounds.y,
            width = WIDTH,
            height = HEIGHT,
            radius = 10.0f,
            bgColor = withAlpha(BACKGROUND, drawAlpha),
            role = ThemeSettings.ThemeRole.CARD,
        )

        val animatedStroke = withAlpha(strokeColor.update(seconds), drawAlpha)
        val animatedHeader = withAlpha(headerColor.update(seconds), drawAlpha)

        HypnosiaRenderUtils.drawThemedBox(
            context = context,
            x = bounds.x,
            y = bounds.y,
            width = WIDTH,
            height = HEADER_HEIGHT,
            radius = 10.0f,
            bgColor = animatedHeader,
            role = ThemeSettings.ThemeRole.HEADER,
        )
        HypnosiaRenderUtils.drawThemedBox(
            context = context,
            x = bounds.x,
            y = bounds.y + HEADER_HEIGHT - 10.0f,
            width = WIDTH,
            height = 10.0f,
            radius = 0.0f,
            bgColor = animatedHeader,
            role = ThemeSettings.ThemeRole.HEADER,
        )
        HypnosiaRenderUtils.drawThemedBox(
            context = context,
            x = bounds.x,
            y = bounds.y,
            width = WIDTH,
            height = HEIGHT,
            radius = 10.0f,
            bgColor = 0x00000000,
            strokeColor = animatedStroke,
            strokeThickness = 1.0f,
            role = ThemeSettings.ThemeRole.CARD,
        )

        drawText(
            context = context,
            text = title,
            x = bounds.x + TITLE_X,
            y = bounds.y + TITLE_Y,
            color = withAlpha(titleColor.update(seconds), drawAlpha),
        )

        drawStateButtons(context, drawAlpha, settingsOpen)
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != 0 || mouseX !in bounds.x..bounds.right || mouseY !in bounds.y..bounds.bottom) {
            return false
        }

        if (contains(leftButtonRect(), mouseX, mouseY)) {
            active = !active
            onActiveChanged(active)
            return true
        }

        if (settingsEnabled && contains(rightButtonRect(), mouseX, mouseY)) {
            onSettingsChanged(!isSettingsOpen())
            return true
        }

        return false
    }

    private fun drawStateButtons(context: DrawContext, drawAlpha: Float, settingsOpen: Boolean) {
        val leftX = bounds.x + LEFT_BUTTON_X
        val rightX = bounds.x + RIGHT_BUTTON_X
        val buttonY = bounds.y + BUTTON_Y

        drawIconFrame(
            context = context,
            x = leftX,
            y = buttonY,
            fill = if (active) SELECTED_HEADER else 0x00000000,
            stroke = if (active) SELECTED_STROKE else IDLE_STROKE,
            drawAlpha = drawAlpha,
        )
        drawIcon(
            context = context,
            fileName = if (active) "module_plug_on.png" else "module_plug_off.png",
            x = leftX,
            y = buttonY,
            width = PLUG_ICON_SIZE,
            height = PLUG_ICON_SIZE,
            alpha = drawAlpha,
        )

        if (!settingsEnabled) return

        drawIconFrame(
            context = context,
            x = rightX,
            y = buttonY,
            fill = 0x00000000,
            stroke = if (active) SELECTED_STROKE else IDLE_STROKE,
            drawAlpha = drawAlpha,
        )

        if (!active && !settingsOpen) {
            drawIconFrame(
                context = context,
                x = rightX,
                y = buttonY,
                fill = 0x00000000,
                stroke = SELECTED_STROKE,
                drawAlpha = drawAlpha,
            )
        }

        if (settingsOpen) {
            drawIcon(
                context = context,
                fileName = if (active) "module_setup_on_open.png" else "module_setup_off_open.png",
                x = rightX,
                y = buttonY,
                width = SETUP_ICON_SIZE,
                height = SETUP_ICON_SIZE,
                alpha = drawAlpha,
            )
        } else {
            drawIcon(
                context = context,
                fileName = if (active) "module_settings_on_closed.png" else "module_settings_off_closed.png",
                x = rightX + GEAR_ICON_X,
                y = buttonY + GEAR_ICON_Y,
                width = GEAR_ICON_SIZE,
                height = GEAR_ICON_SIZE,
                alpha = drawAlpha,
            )
        }
    }

    private fun drawIconFrame(
        context: DrawContext,
        x: Float,
        y: Float,
        fill: Int,
        stroke: Int,
        drawAlpha: Float,
    ) {
        HypnosiaRenderUtils.drawThemedBox(
            context = context,
            x = x,
            y = y,
            width = BUTTON_SIZE,
            height = BUTTON_SIZE,
            radius = 5.0f,
            bgColor = withAlpha(fill, drawAlpha),
            strokeColor = withAlpha(stroke, drawAlpha),
            strokeThickness = 1.0f,
            role = ThemeSettings.ThemeRole.ICON_BUTTON,
        )
    }

    private fun drawIcon(
        context: DrawContext,
        fileName: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        alpha: Float,
    ) {
        HypnosiaRenderUtils.drawRoundedTexture(
            context = context,
            identifier = Identifier.of(HypnosiaClient.MOD_ID, "textures/gui/icons/$fileName"),
            x = x,
            y = y,
            width = width,
            height = height,
            radius = 0.0f,
            tintColor = withAlpha(0xFFFFFFFF.toInt(), alpha),
        )
    }

    private fun drawText(context: DrawContext, text: String, x: Float, y: Float, color: Int) {
        FigmaTextRenderer.drawInBox(
            context = context,
            text = text,
            x = x,
            y = y,
            width = TITLE_WIDTH,
            height = TITLE_HEIGHT,
            color = color,
            style = FigmaTextRenderer.Styles.ModuleTitle,
            horizontalAlign = FigmaTextRenderer.HorizontalAlign.Left,
            verticalAlign = FigmaTextRenderer.VerticalAlign.Top,
        )
    }

    private fun leftButtonRect(): Rect = Rect(bounds.x + LEFT_BUTTON_X, bounds.y + BUTTON_Y, BUTTON_SIZE, BUTTON_SIZE)

    private fun rightButtonRect(): Rect = Rect(bounds.x + RIGHT_BUTTON_X, bounds.y + BUTTON_Y, BUTTON_SIZE, BUTTON_SIZE)

    private fun contains(rect: Rect, mouseX: Float, mouseY: Float): Boolean {
        return mouseX >= rect.x && mouseX <= rect.right && mouseY >= rect.y && mouseY <= rect.bottom
    }

    private fun withAlpha(color: Int, multiplier: Float): Int {
        val alpha = (((color ushr 24) and 0xFF) * multiplier.coerceIn(0.0f, 1.0f)).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    companion object {
        const val WIDTH = 263.0f
        const val HEIGHT = 70.0f

        private const val HEADER_HEIGHT = 20.0f
        private const val TITLE_X = 6.0f
        private const val TITLE_Y = 2.0f
        private const val TITLE_WIDTH = 210.0f
        private const val TITLE_HEIGHT = 16.0f

        private const val BUTTON_SIZE = 26.0f
        private const val BUTTON_Y = 31.0f
        private const val LEFT_BUTTON_X = 9.0f
        private const val RIGHT_BUTTON_X = 226.0f

        private const val PLUG_ICON_SIZE = 24.0f
        private const val GEAR_ICON_X = 5.0f
        private const val GEAR_ICON_Y = 5.0f
        private const val GEAR_ICON_SIZE = 14.0f

        private const val BACKGROUND = 0xFF0D0D0D.toInt()
        private const val IDLE_HEADER = 0xFF191919.toInt()
        private const val IDLE_TITLE = 0xFFFFFFFF.toInt()
        private const val IDLE_STROKE = 0xFF272727.toInt()

        private const val SELECTED_HEADER = 0xFFFFFFFF.toInt()
        private const val SELECTED_TITLE = 0xFF000000.toInt()
        private const val SELECTED_STROKE = 0xFFFFFFFF.toInt()

        private const val SETUP_ICON_SIZE = 24.0f
    }
}
