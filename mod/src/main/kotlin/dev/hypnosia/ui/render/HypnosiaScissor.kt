package dev.hypnosia.ui.render

import com.mojang.blaze3d.systems.RenderSystem
import dev.hypnosia.ui.layout.Rect
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import org.joml.Matrix3x2f
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object HypnosiaScissor {
    data class ScissorRect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    private val stack = ArrayDeque<ScissorRect>()

    fun current(): ScissorRect? = stack.lastOrNull()

    fun withLocalRect(context: DrawContext, rect: Rect, block: () -> Unit) {
        if (!pushLocalRect(context, rect)) {
            return
        }

        try {
            block()
        } finally {
            context.drawDeferredElements()
            pop()
        }
    }

    private fun pushLocalRect(context: DrawContext, rect: Rect): Boolean {
        context.drawDeferredElements()

        val raw = localRectToPhysicalScissor(context, rect) ?: return false
        val clipped = stack.lastOrNull()?.let { intersect(it, raw) } ?: raw
        if (clipped.width <= 0 || clipped.height <= 0) {
            return false
        }

        stack.addLast(clipped)
        apply(clipped)
        return true
    }

    private fun pop() {
        if (stack.isNotEmpty()) {
            stack.removeLast()
        }

        val previous = stack.lastOrNull()
        if (previous == null) {
            RenderSystem.disableScissorForRenderTypeDraws()
        } else {
            apply(previous)
        }
    }

    private fun apply(rect: ScissorRect) {
        RenderSystem.enableScissorForRenderTypeDraws(rect.x, rect.y, rect.width, rect.height)
    }

    private fun localRectToPhysicalScissor(context: DrawContext, rect: Rect): ScissorRect? {
        if (rect.width <= 0.0f || rect.height <= 0.0f) {
            return null
        }

        val matrix = Matrix3x2f(context.matrices)
        val x1 = transformX(matrix, rect.x, rect.y)
        val y1 = transformY(matrix, rect.x, rect.y)
        val x2 = transformX(matrix, rect.right, rect.y)
        val y2 = transformY(matrix, rect.right, rect.y)
        val x3 = transformX(matrix, rect.x, rect.bottom)
        val y3 = transformY(matrix, rect.x, rect.bottom)
        val x4 = transformX(matrix, rect.right, rect.bottom)
        val y4 = transformY(matrix, rect.right, rect.bottom)

        val guiLeft = min(min(x1, x2), min(x3, x4))
        val guiRight = max(max(x1, x2), max(x3, x4))
        val guiTop = min(min(y1, y2), min(y3, y4))
        val guiBottom = max(max(y1, y2), max(y3, y4))

        val window = MinecraftClient.getInstance().window
        val scale = window.scaleFactor.toFloat().coerceAtLeast(1.0f)
        val physicalWidth = window.width
        val physicalHeight = window.height

        val left = floor(guiLeft * scale).toInt().coerceIn(0, physicalWidth)
        val right = ceil(guiRight * scale).toInt().coerceIn(0, physicalWidth)
        val top = floor(guiTop * scale).toInt().coerceIn(0, physicalHeight)
        val bottom = ceil(guiBottom * scale).toInt().coerceIn(0, physicalHeight)

        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) {
            return null
        }

        return ScissorRect(
            x = left,
            y = physicalHeight - bottom,
            width = width,
            height = height,
        )
    }

    private fun transformX(matrix: Matrix3x2f, x: Float, y: Float): Float {
        return matrix.m00() * x + matrix.m10() * y + matrix.m20()
    }

    private fun transformY(matrix: Matrix3x2f, x: Float, y: Float): Float {
        return matrix.m01() * x + matrix.m11() * y + matrix.m21()
    }

    private fun intersect(a: ScissorRect, b: ScissorRect): ScissorRect {
        val left = max(a.x, b.x)
        val bottom = max(a.y, b.y)
        val right = min(a.x + a.width, b.x + b.width)
        val top = min(a.y + a.height, b.y + b.height)
        return ScissorRect(left, bottom, right - left, top - bottom)
    }
}
