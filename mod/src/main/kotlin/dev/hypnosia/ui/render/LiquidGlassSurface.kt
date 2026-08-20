package dev.hypnosia.ui.render

import dev.hypnosia.config.ThemeSettings
import net.minecraft.client.gui.DrawContext
import kotlin.math.max
import kotlin.math.min

object LiquidGlassSurface {
    fun draw(
        context: DrawContext,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        bgColor: Int,
        strokeColor: Int,
        sourceStrokeColor: Int,
        strokeThickness: Float,
        role: ThemeSettings.ThemeRole,
        intensity: Float,
        hoverProgress: Float = 0.0f,
        activeProgress: Float = 0.0f,
        blur: Boolean = true,
        flushDeferredBeforeDraw: Boolean = true,
    ) {
        if (width <= 0.0f || height <= 0.0f) return

        val safeRadius = radius.coerceIn(0.0f, min(width, height) * 0.5f)
        val largeSurface = role == ThemeSettings.ThemeRole.MAIN_PANEL || role == ThemeSettings.ThemeRole.DRAWER
        val accentSurface = isAccentStroke(sourceStrokeColor)
        val stateGlow = max(hoverProgress, activeProgress).coerceIn(0.0f, 1.0f)
        val mode = ThemeSettings.mode()
        val transparentMode = mode == ThemeSettings.Mode.TRANSPARENT
        val liquidMode = mode == ThemeSettings.Mode.LIQUID_GLASS ||
            (mode != ThemeSettings.Mode.TRANSPARENT && ThemeSettings.liquidGlass())

        if (transparentMode) {
            drawTransparentSurface(
                context = context,
                x = x,
                y = y,
                width = width,
                height = height,
                radius = safeRadius,
                bgColor = bgColor,
                strokeColor = strokeColor,
                strokeThickness = strokeThickness,
                role = role,
                intensity = intensity,
                stateGlow = stateGlow,
                blur = blur,
                flush = flushDeferredBeforeDraw,
            )
            return
        }

        drawDepthShadow(context, x, y, width, height, safeRadius, largeSurface, flushDeferredBeforeDraw)
        drawBackdropSoftener(context, x, y, width, height, safeRadius, largeSurface, flushDeferredBeforeDraw)

        if (accentSurface || stateGlow > 0.01f) {
            HypnosiaRenderUtils.drawSdfShadowBox(
                context = context,
                x = x,
                y = y,
                width = width,
                height = height,
                radius = safeRadius,
                shadowSpread = 0.0f,
                shadowBlur = if (largeSurface) 22.0f else 14.0f,
                color = alpha(GlassSurfaceTokens.accentActive, ((0x22 + 0x28 * stateGlow).toInt()).coerceIn(0, 255)),
                flushDeferredBeforeDraw = flushDeferredBeforeDraw,
            )
        }

        drawMatteBase(context, x, y, width, height, safeRadius, role, flushDeferredBeforeDraw)

        if (blur) {
            HypnosiaRenderUtils.drawLiquidGlassBox(
                context = context,
                x = x,
                y = y,
                width = width,
                height = height,
                radius = safeRadius,
                bgColor = bgColor,
                strokeColor = if (accentSurface) alpha(GlassSurfaceTokens.accentActive, 0x58) else strokeColor,
                strokeThickness = max(strokeThickness, if (largeSurface) 1.1f else 1.0f),
                strength = intensity,
                distortion = if (liquidMode) 1.0f else 0.0f,
                blurRadius = if (liquidMode) {
                    if (largeSurface) 30.0f else 24.0f
                } else {
                    if (largeSurface) 22.0f else 18.0f
                },
                flushDeferredBeforeDraw = flushDeferredBeforeDraw,
            )
        }

        drawInnerLayer(context, x, y, width, height, safeRadius, largeSurface, flushDeferredBeforeDraw)
        drawBottomDepth(context, x, y, width, height, safeRadius, largeSurface, flushDeferredBeforeDraw)
        drawHighlights(context, x, y, width, height, safeRadius, largeSurface, stateGlow, flushDeferredBeforeDraw)
        drawBorders(context, x, y, width, height, safeRadius, largeSurface, accentSurface, stateGlow, strokeThickness, flushDeferredBeforeDraw)
    }

    private fun drawTransparentSurface(
        context: DrawContext,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        bgColor: Int,
        strokeColor: Int,
        strokeThickness: Float,
        role: ThemeSettings.ThemeRole,
        intensity: Float,
        stateGlow: Float,
        blur: Boolean,
        flush: Boolean,
    ) {
        val largeSurface = role == ThemeSettings.ThemeRole.MAIN_PANEL || role == ThemeSettings.ThemeRole.DRAWER

        HypnosiaRenderUtils.drawSdfShadowBox(
            context = context,
            x = x,
            y = y + if (largeSurface) 5.0f else 2.0f,
            width = width,
            height = height,
            radius = radius,
            shadowSpread = if (largeSurface) 5.0f else 2.0f,
            shadowBlur = if (largeSurface) 28.0f else 12.0f,
            color = if (largeSurface) 0x3A000000 else 0x28000000,
            flushDeferredBeforeDraw = flush,
        )

        if (blur) {
            HypnosiaRenderUtils.drawLiquidGlassBox(
                context = context,
                x = x,
                y = y,
                width = width,
                height = height,
                radius = radius,
                bgColor = bgColor,
                strokeColor = strokeColor,
                strokeThickness = max(strokeThickness, 1.0f),
                strength = (intensity + if (largeSurface) 0.16f else 0.08f).coerceIn(0.0f, 1.0f),
                distortion = 0.0f,
                blurRadius = if (largeSurface) 34.0f else 26.0f,
                flushDeferredBeforeDraw = flush,
            )
        } else {
            HypnosiaRenderUtils.drawFigmaBox(
                context = context,
                x = x,
                y = y,
                width = width,
                height = height,
                radius = radius,
                bgColor = bgColor,
                strokeColor = strokeColor,
                strokeThickness = max(strokeThickness, 1.0f),
                flushDeferredBeforeDraw = flush,
            )
        }

        val softInset = if (largeSurface) 8.0f else 2.0f
        if (width > softInset * 2.0f && height > softInset * 2.0f) {
            HypnosiaRenderUtils.drawLinearGradientBox(
                context = context,
                x = x + softInset,
                y = y + softInset,
                width = width - softInset * 2.0f,
                height = height - softInset * 2.0f,
                radius = max(0.0f, radius - softInset * 0.6f),
                startColor = if (largeSurface) 0x14FFFFFF else 0x0FFFFFFF,
                endColor = if (largeSurface) 0x0D000000 else 0x08000000,
                angleDegrees = 90.0f,
                flushDeferredBeforeDraw = flush,
            )
        }

        if (largeSurface) {
            val highlightHeight = min(height * 0.16f, 42.0f)
            HypnosiaRenderUtils.drawLinearGradientBox(
                context = context,
                x = x + 1.0f,
                y = y + 1.0f,
                width = max(0.0f, width - 2.0f),
                height = max(0.0f, highlightHeight),
                radius = max(0.0f, radius - 1.0f),
                startColor = 0x12FFFFFF,
                endColor = 0x00FFFFFF,
                angleDegrees = 90.0f,
                flushDeferredBeforeDraw = flush,
            )
        }

        if (stateGlow > 0.01f) {
            HypnosiaRenderUtils.drawSdfShadowBox(
                context = context,
                x = x,
                y = y,
                width = width,
                height = height,
                radius = radius,
                shadowSpread = 0.0f,
                shadowBlur = if (largeSurface) 20.0f else 12.0f,
                color = alpha(GlassSurfaceTokens.accentSoft, (0x1A + 0x22 * stateGlow).toInt()),
                flushDeferredBeforeDraw = flush,
            )
        }

        HypnosiaRenderUtils.drawFigmaBox(
            context = context,
            x = x,
            y = y,
            width = width,
            height = height,
            radius = radius,
            bgColor = 0x00000000,
            strokeColor = if (largeSurface) 0x4AFFFFFF else 0x3AFFFFFF,
            strokeThickness = max(strokeThickness, 1.0f),
            flushDeferredBeforeDraw = flush,
        )
    }

    private fun drawDepthShadow(
        context: DrawContext,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        largeSurface: Boolean,
        flush: Boolean,
    ) {
        HypnosiaRenderUtils.drawSdfShadowBox(
            context = context,
            x = x,
            y = y + if (largeSurface) 8.0f else 4.0f,
            width = width,
            height = height,
            radius = radius,
            shadowSpread = if (largeSurface) 8.0f else 4.0f,
            shadowBlur = if (largeSurface) 34.0f else 16.0f,
            color = if (largeSurface) 0x5F000000 else 0x46000000,
            flushDeferredBeforeDraw = flush,
        )
    }

    private fun drawBackdropSoftener(
        context: DrawContext,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        largeSurface: Boolean,
        flush: Boolean,
    ) {
        HypnosiaRenderUtils.drawFigmaBox(
            context = context,
            x = x,
            y = y,
            width = width,
            height = height,
            radius = radius,
            bgColor = if (largeSurface) 0x2E0A0A0C else 0x220A0A0C,
            flushDeferredBeforeDraw = flush,
        )
        HypnosiaRenderUtils.drawLinearGradientBox(
            context = context,
            x = x,
            y = y,
            width = width,
            height = height,
            radius = radius,
            startColor = if (largeSurface) 0x22FFFFFF else 0x18FFFFFF,
            endColor = 0x05000000,
            angleDegrees = 115.0f,
            flushDeferredBeforeDraw = flush,
        )
    }

    private fun drawMatteBase(
        context: DrawContext,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        role: ThemeSettings.ThemeRole,
        flush: Boolean,
    ) {
        val fill = when (role) {
            ThemeSettings.ThemeRole.MAIN_PANEL, ThemeSettings.ThemeRole.DRAWER -> GlassSurfaceTokens.glassBase
            ThemeSettings.ThemeRole.CARD -> GlassSurfaceTokens.glassCard
            else -> GlassSurfaceTokens.glassLayer
        }
        HypnosiaRenderUtils.drawFigmaBox(
            context = context,
            x = x,
            y = y,
            width = width,
            height = height,
            radius = radius,
            bgColor = fill,
            flushDeferredBeforeDraw = flush,
        )
    }

    private fun drawInnerLayer(
        context: DrawContext,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        largeSurface: Boolean,
        flush: Boolean,
    ) {
        val inset = if (largeSurface) 10.0f else 3.0f
        if (width <= inset * 2.0f || height <= inset * 2.0f) return
        HypnosiaRenderUtils.drawLinearGradientBox(
            context = context,
            x = x + inset,
            y = y + inset,
            width = width - inset * 2.0f,
            height = height - inset * 2.0f,
            radius = max(0.0f, radius - inset * 0.55f),
            startColor = if (largeSurface) 0x1AFFFFFF else 0x12FFFFFF,
            endColor = if (largeSurface) 0x18000000 else 0x10000000,
            angleDegrees = 90.0f,
            flushDeferredBeforeDraw = flush,
        )
    }

    private fun drawBottomDepth(
        context: DrawContext,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        largeSurface: Boolean,
        flush: Boolean,
    ) {
        HypnosiaRenderUtils.drawLinearGradientBox(
            context = context,
            x = x + 1.0f,
            y = y + height * 0.50f,
            width = max(0.0f, width - 2.0f),
            height = max(0.0f, height * 0.50f - 1.0f),
            radius = max(0.0f, radius - 1.0f),
            startColor = 0x00000000,
            endColor = if (largeSurface) 0x26000000 else 0x1A000000,
            angleDegrees = 90.0f,
            flushDeferredBeforeDraw = flush,
        )
    }

    private fun drawHighlights(
        context: DrawContext,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        largeSurface: Boolean,
        stateGlow: Float,
        flush: Boolean,
    ) {
        val highlightHeight = min(height * 0.42f, if (largeSurface) 92.0f else 30.0f)
        HypnosiaRenderUtils.drawLinearGradientBox(
            context = context,
            x = x + 1.0f,
            y = y + 1.0f,
            width = max(0.0f, width - 2.0f),
            height = max(0.0f, highlightHeight),
            radius = max(0.0f, radius - 1.0f),
            startColor = alpha(GlassSurfaceTokens.glassHighlight, if (largeSurface) 0x3C else 0x2C),
            endColor = 0x00FFFFFF,
            angleDegrees = 90.0f,
            flushDeferredBeforeDraw = flush,
        )

        val streakWidth = min(width * 0.58f, if (largeSurface) 280.0f else 150.0f)
        if (streakWidth > 18.0f) {
            HypnosiaRenderUtils.drawLinearGradientBox(
                context = context,
                x = x + radius * 0.72f,
                y = y + 2.0f,
                width = streakWidth,
                height = if (largeSurface) 5.0f else 3.0f,
                radius = 2.5f,
                startColor = 0x00FFFFFF,
                endColor = alpha(GlassSurfaceTokens.warmReflection, (0x36 + 0x16 * stateGlow).toInt()),
                angleDegrees = 0.0f,
                flushDeferredBeforeDraw = flush,
            )
        }
    }

    private fun drawBorders(
        context: DrawContext,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        largeSurface: Boolean,
        accentSurface: Boolean,
        stateGlow: Float,
        strokeThickness: Float,
        flush: Boolean,
    ) {
        val outer = when {
            accentSurface -> alpha(GlassSurfaceTokens.accentActive, 0x60)
            stateGlow > 0.01f -> alpha(GlassSurfaceTokens.accentSoft, (0x30 + 0x2A * stateGlow).toInt())
            largeSurface -> GlassSurfaceTokens.glassBorderStrong
            else -> GlassSurfaceTokens.glassBorder
        }
        HypnosiaRenderUtils.drawFigmaBox(
            context = context,
            x = x,
            y = y,
            width = width,
            height = height,
            radius = radius,
            bgColor = 0x00000000,
            strokeColor = outer,
            strokeThickness = max(strokeThickness, 1.0f),
            flushDeferredBeforeDraw = flush,
        )

        val inset = 1.0f
        if (width > inset * 2.0f && height > inset * 2.0f) {
            HypnosiaRenderUtils.drawFigmaBox(
                context = context,
                x = x + inset,
                y = y + inset,
                width = width - inset * 2.0f,
                height = height - inset * 2.0f,
                radius = max(0.0f, radius - inset),
                bgColor = 0x00000000,
                strokeColor = GlassSurfaceTokens.glassInnerHighlight,
                strokeThickness = 1.0f,
                flushDeferredBeforeDraw = flush,
            )
        }
    }

    private fun isAccentStroke(color: Int): Boolean {
        val alpha = (color ushr 24) and 0xFF
        if (alpha < 0x70) return false
        val rgb = color and 0x00FFFFFF
        return rgb == 0xFFFFFF || rgb == 0xFF2F86 || rgb == 0xD8B4FE || rgb == 0xAFCBFF
    }

    private fun alpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
