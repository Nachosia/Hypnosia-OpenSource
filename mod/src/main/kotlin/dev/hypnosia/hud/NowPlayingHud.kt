package dev.hypnosia.hud

import dev.hypnosia.HypnosiaClient
import dev.hypnosia.hud.HudRenderSupport.anchorX
import dev.hypnosia.hud.HudRenderSupport.anchorY
import dev.hypnosia.hud.HudRenderSupport.fixedScale
import dev.hypnosia.hud.HudRenderSupport.icon
import dev.hypnosia.hud.HudRenderSupport.panel
import dev.hypnosia.hud.HudRenderSupport.text
import dev.hypnosia.media.GlobalMediaTracker
import dev.hypnosia.media.MediaBridge
import dev.hypnosia.ui.render.FigmaTextRenderer
import dev.hypnosia.ui.render.HypnosiaRenderUtils
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.RenderTickCounter
import net.minecraft.util.Identifier
import kotlin.math.max

object NowPlayingHud {

    private const val COVER_SIZE = 64.0f
    private const val INNER_PAD = 12.0f
    private const val CTRL_SIZE = 20.0f
    private const val CTRL_GAP = 4.0f
    private const val PROGRESS_H = 6.0f
    private const val CORNER = 10.0f

    private const val TEXT_AREA_W = 180.0f

    private val Text14 = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 14.0f, 18.0f, baselineOffset = 1.0f)
    private val Text11 = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 11.0f, 14.0f, baselineOffset = 1.0f)

    private val MUTED = 0xFF888899.toInt()
    private val PROGRESS_BG = 0xFF2A2A3A.toInt()
    private val PROGRESS_FG = 0xFFFF2F86.toInt()

    private val MODULE = HudModuleSettings.Module.NOW_PLAYING
    private val drag = HudDragController(MODULE)

    fun register() {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.SUBTITLES,
            Identifier.of(HypnosiaClient.MOD_ID, "now_playing_hud"),
            ::render,
        )
    }

    fun tickDrag(client: MinecraftClient) {
        val widgetSize = widgetSize()
        drag.tick(client, widgetSize.first, widgetSize.second)
    }

    private fun widgetSize(): Pair<Float, Float> {
        val showCover = NowPlayingSettings.showCover()
        val showControls = NowPlayingSettings.showControls()
        val showProgress = NowPlayingSettings.showProgress()
        val coverW = if (showCover) COVER_SIZE + INNER_PAD else 0.0f
        val ctrlH = if (showControls) CTRL_SIZE + 4.0f else 0.0f
        val progressH = if (showProgress) PROGRESS_H + 8.0f else 0.0f
        val w = INNER_PAD + coverW + TEXT_AREA_W + INNER_PAD
        val h = INNER_PAD + max(COVER_SIZE, 32.0f + ctrlH) + progressH + INNER_PAD
        return w to h
    }

    private fun render(context: DrawContext, tickCounter: RenderTickCounter) {
        val client = MinecraftClient.getInstance()
        if (client.currentScreen is dev.hypnosia.ui.HypnosiaHomeV2Screen) return
        if (!NowPlayingSettings.isEnabled()) return
        if (NowPlayingSettings.onlyWhenPlaying() && !GlobalMediaTracker.isActive()) return

        val alpha = NowPlayingSettings.alpha() / 255.0f
        val showCover = NowPlayingSettings.showCover()
        val showControls = NowPlayingSettings.showControls()
        val showProgress = NowPlayingSettings.showProgress()

        val coverW = if (showCover) COVER_SIZE + INNER_PAD else 0.0f
        val ctrlH = if (showControls) CTRL_SIZE + 4.0f else 0.0f
        val progressH = if (showProgress) PROGRESS_H + 8.0f else 0.0f
        val widgetW = INNER_PAD + coverW + TEXT_AREA_W + INNER_PAD
        val widgetH = INNER_PAD + max(COVER_SIZE, 32.0f + ctrlH) + progressH + INNER_PAD

        val scale = fixedScale(client)
        val state = HudModuleSettings.state(MODULE)
        val x = anchorX(client.window.framebufferWidth.toFloat(), widgetW, state.x)
        val y = anchorY(client.window.framebufferHeight.toFloat(), widgetH, state.y)

        context.matrices.pushMatrix()
        context.matrices.scale(scale, scale)

        // Background
        HypnosiaRenderUtils.drawFigmaBox(context, x, y, widgetW, widgetH, CORNER, applyAlpha(HudRenderSupport.BG, alpha), applyAlpha(HudRenderSupport.STROKE, alpha), 1.0f)

        val contentX = x + INNER_PAD
        var textX = contentX

        // Cover art
        if (showCover) {
            val coverId = GlobalMediaTracker.coverTextureId
            if (coverId != null) {
                HypnosiaRenderUtils.drawRoundedTexture(context, coverId, contentX, y + INNER_PAD, COVER_SIZE, COVER_SIZE, 8.0f, applyAlpha(HudRenderSupport.WHITE, alpha))
            } else {
                HypnosiaRenderUtils.drawFigmaBox(context, contentX, y + INNER_PAD, COVER_SIZE, COVER_SIZE, 8.0f, applyAlpha(0xFF1E1E2E.toInt(), alpha))
                icon(context, "music_note.png", contentX + 20.0f, y + INNER_PAD + 20.0f, 24.0f, 24.0f, applyAlpha(MUTED, alpha))
            }
            textX = contentX + COVER_SIZE + INNER_PAD
        }

        val maxTextW = x + widgetW - INNER_PAD - textX
        val titleText = if (GlobalMediaTracker.isActive()) GlobalMediaTracker.trackTitle else "Nothing playing"
        val artistText = GlobalMediaTracker.trackArtist

        // Title
        FigmaTextRenderer.drawInBox(
            context, titleText, textX, y + INNER_PAD, maxTextW, 20.0f,
            applyAlpha(HudRenderSupport.WHITE, alpha), Text14,
            FigmaTextRenderer.HorizontalAlign.Left, FigmaTextRenderer.VerticalAlign.Center,
        )

        // Artist
        FigmaTextRenderer.drawInBox(
            context, artistText, textX, y + INNER_PAD + 20.0f, maxTextW, 16.0f,
            applyAlpha(MUTED, alpha), Text11,
            FigmaTextRenderer.HorizontalAlign.Left, FigmaTextRenderer.VerticalAlign.Center,
        )

        // Controls
        if (showControls) {
            val ctrlY = y + INNER_PAD + 40.0f
            val prevX = textX
            val playX = prevX + CTRL_SIZE + CTRL_GAP
            val nextX = playX + CTRL_SIZE + CTRL_GAP
            val playIconName = if (GlobalMediaTracker.isMediaPlaying) "pause.png" else "play.png"
            icon(context, "previous.png", prevX, ctrlY, CTRL_SIZE, CTRL_SIZE, applyAlpha(HudRenderSupport.WHITE, alpha))
            icon(context, playIconName, playX, ctrlY, CTRL_SIZE, CTRL_SIZE, applyAlpha(HudRenderSupport.WHITE, alpha))
            icon(context, "next.png", nextX, ctrlY, CTRL_SIZE, CTRL_SIZE, applyAlpha(HudRenderSupport.WHITE, alpha))

            val elapsed = formatTime(GlobalMediaTracker.trackPositionMs)
            val duration = formatTime(GlobalMediaTracker.trackDurationMs)
            FigmaTextRenderer.drawInBox(
                context, "$elapsed / $duration",
                nextX + CTRL_SIZE + 4.0f, ctrlY, 60.0f, CTRL_SIZE,
                applyAlpha(MUTED, alpha), Text11,
                FigmaTextRenderer.HorizontalAlign.Left, FigmaTextRenderer.VerticalAlign.Center,
            )
        }

        // Progress bar
        if (showProgress && GlobalMediaTracker.trackDurationMs > 0) {
            val barY = y + widgetH - INNER_PAD - PROGRESS_H
            val barW = widgetW - INNER_PAD * 2
            val filled = barW * GlobalMediaTracker.getSmoothProgress().coerceIn(0.0f, 1.0f)
            HypnosiaRenderUtils.drawFigmaBox(context, contentX, barY, barW, PROGRESS_H, PROGRESS_H / 2, applyAlpha(PROGRESS_BG, alpha))
            if (filled > 0.5f) {
                HypnosiaRenderUtils.drawFigmaBox(context, contentX, barY, filled, PROGRESS_H, PROGRESS_H / 2, applyAlpha(PROGRESS_FG, alpha))
            }
        }

        context.matrices.popMatrix()
    }

    private fun applyAlpha(color: Int, alpha: Float): Int {
        val a = ((color ushr 24) and 0xFF)
        val newA = (a * alpha).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (newA shl 24)
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val s = ms / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }
}
