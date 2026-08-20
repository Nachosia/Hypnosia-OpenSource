package dev.hypnosia.ui.layout

import dev.hypnosia.ui.render.HypnosiaRenderUtils
import dev.hypnosia.ui.render.FigmaTextRenderer
import net.minecraft.client.gui.DrawContext

object LayoutExamples {
    fun sidebarButtons(): FigmaRoot {
        val sidebar = column(
            width = SizeMode.Fixed(228.0f),
            height = SizeMode.Hug,
            padding = Insets.symmetric(horizontal = 16.0f, vertical = 16.0f),
            gap = 12.0f,
            alignment = Alignment.Stretch,
        ) {
            child(button("Visuals"))
            child(button("World"))
            child(button("Client"))
        }

        return FigmaRoot(
            designWidth = 260.0f,
            designHeight = 520.0f,
            child = sidebar,
            anchor = RootAnchor.Center,
        )
    }

    private fun button(label: String): UiNode {
        return FigmaBoxNode(
            preferredWidth = 196.0f,
            preferredHeight = 46.0f,
            renderer = { context: DrawContext, rect: Rect ->
            HypnosiaRenderUtils.drawSdfShadowBox(
                context = context,
                x = rect.x,
                y = rect.y,
                width = rect.width,
                height = rect.height,
                radius = 10.0f,
                shadowSpread = 0.0f,
                shadowBlur = 10.0f,
                color = 0x44000000,
            )
            HypnosiaRenderUtils.drawFigmaBox(
                context = context,
                x = rect.x,
                y = rect.y,
                width = rect.width,
                height = rect.height,
                radius = 10.0f,
                bgColor = 0xFF191919.toInt(),
                strokeColor = 0xFF2A2A31.toInt(),
                strokeThickness = 1.0f,
            )
            FigmaTextRenderer.drawInBox(
                context = context,
                text = label,
                x = rect.x + 14.0f,
                y = rect.y + 14.0f,
                width = rect.width - 28.0f,
                height = 18.0f,
                color = 0xFFF0F0F2.toInt(),
                style = FigmaTextRenderer.Styles.Ui14,
                horizontalAlign = FigmaTextRenderer.HorizontalAlign.Left,
                verticalAlign = FigmaTextRenderer.VerticalAlign.Center,
            )
            },
        )
    }
}
