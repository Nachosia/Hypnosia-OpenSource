package dev.hypnosia.hud

import dev.hypnosia.HypnosiaClient
import dev.hypnosia.license.AccountManager
import dev.hypnosia.license.AccountState
import dev.hypnosia.license.LicenseRole
import dev.hypnosia.media.GlobalMediaTracker
import dev.hypnosia.media.MediaBridge
import dev.hypnosia.other.StreamerModeSettings
import dev.hypnosia.ui.animation.FigmaAnimation
import dev.hypnosia.ui.animation.SpringFloat
import dev.hypnosia.ui.layout.Rect
import dev.hypnosia.ui.render.FigmaTextRenderer
import dev.hypnosia.ui.render.HypnosiaRenderUtils
import dev.hypnosia.ui.render.HypnosiaScissor
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.RenderTickCounter
import net.minecraft.util.Identifier
import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import kotlin.math.max

object WatermarkHud {
    private data class WatermarkSegment(
        val icon: String,
        val text: String,
        val textWidth: Float,
        val textStyle: FigmaTextRenderer.FigmaTextStyle,
        val textColor: Int = WHITE,
        val gradientColors: List<Int>? = null,
    )

    private data class WatermarkMetrics(
        val fps: Int = 0,
        val ping: Int = 0,
        val ram: Int = 0,
        val cpu: Int = 0,
    )

    // Media state proxies — kept for backward compat, synced from GlobalMediaTracker
    var trackTitle: String
        get() = GlobalMediaTracker.trackTitle
        set(v) { GlobalMediaTracker.trackTitle = v }
    var trackArtist: String
        get() = GlobalMediaTracker.trackArtist
        set(v) { GlobalMediaTracker.trackArtist = v }
    var trackProgress: Float = 0.0f
    var trackPositionMs: Long
        get() = GlobalMediaTracker.trackPositionMs
        set(v) { GlobalMediaTracker.trackPositionMs = v }
    var trackDurationMs: Long
        get() = GlobalMediaTracker.trackDurationMs
        set(v) { GlobalMediaTracker.trackDurationMs = v }
    var lastProgressUpdate: Long
        get() = GlobalMediaTracker.lastProgressUpdate
        set(v) { GlobalMediaTracker.lastProgressUpdate = v }
    var trackElapsed: String = "3:27"
    var trackDuration: String = "2:23"
    var coverTextureId: Identifier?
        get() = GlobalMediaTracker.coverTextureId
        set(v) { GlobalMediaTracker.coverTextureId = v }
    var isMediaPlaying: Boolean
        get() = GlobalMediaTracker.isMediaPlaying
        set(v) { GlobalMediaTracker.isMediaPlaying = v }

    // Called by GlobalMediaTracker after state update — triggers any WatermarkHud-specific refresh
    fun syncFromTracker() {
        // No-op: all fields are now proxies, nothing extra needed
    }

    fun getSmoothProgress(): Float = GlobalMediaTracker.getSmoothProgress()

    private var lastV1HoverX = 0.0f
    private var lastV1HoverWidth = V1_MAIN_WIDTH
    private var lastLoggedExpand = false

    private val musicExpand = SpringFloat(0.0f, stiffness = 310.0f, damping = 28.0f)
    private val osBean = ManagementFactory.getOperatingSystemMXBean()
    private val cpuLoadMethod = sequenceOf("getCpuLoad", "getSystemCpuLoad", "getProcessCpuLoad")
        .firstNotNullOfOrNull { methodName -> osBean.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 } }
    private var cachedMetrics = WatermarkMetrics()
    private var lastMetricsAtMs = 0L

    private val BG = 0xFF0D0D0D.toInt()
    private val STROKE = 0xFF272727.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()
    private val MUTED = 0xFF606060.toInt()
    private val SEPARATOR = 0xFF6D6D6D.toInt()
    private val ART_PLACEHOLDER = 0xFFD9D9D9.toInt()
    private val PROGRESS_TRACK = 0xFFE8DEFD.toInt()

    private val Text12 = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 12.0f, 14.0f, baselineOffset = 1.0f)
    private val Text18 = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 18.0f, 28.0f, baselineOffset = 2.0f)
    private val Text16 = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 16.0f, 32.0f, baselineOffset = 2.0f)
    private val Text20 = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 20.0f, 32.0f, baselineOffset = 2.0f)
    private val Text24 = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 24.0f, 32.0f, baselineOffset = 3.0f)

    private fun iconId(name: String): Identifier =
        Identifier.of(HypnosiaClient.MOD_ID, "textures/gui/icons/${name.lowercase()}")

    fun register() {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.SUBTITLES,
            Identifier.of(HypnosiaClient.MOD_ID, "watermark_hud"),
            ::render,
        )

        ScreenEvents.BEFORE_INIT.register { client, screen, _, _ ->
            ScreenMouseEvents.allowMouseClick(screen).register { _, context ->
                if (WatermarkSettings.version() != WatermarkSettings.Version.V1) return@register true
                if (context.button() != 0) return@register true

                val scaleFactor = client.window.scaleFactor
                val mouseX = (context.x() * scaleFactor).toFloat()
                val mouseY = (context.y() * scaleFactor).toFloat()

                val handled = handleV1PlayerClick(mouseX, mouseY)
                !handled
            }
        }
    }

    private fun render(context: DrawContext, tickCounter: RenderTickCounter) {
        val client = MinecraftClient.getInstance()
        if (client.currentScreen is dev.hypnosia.ui.HypnosiaHomeV2Screen) return

        val window = client.window
        val fixedScale = 1.0f / window.scaleFactor.toFloat().coerceAtLeast(1.0f)
        val fixedWidth = window.framebufferWidth.toFloat()
        val dt = FigmaAnimation.frameSeconds(tickCounter)

        val mouseGuiX = (client.mouse.x * window.scaledWidth / window.width).toFloat()
        val mouseGuiY = (client.mouse.y * window.scaledHeight / window.height).toFloat()
        val mouseX = mouseGuiX / fixedScale
        val mouseY = mouseGuiY / fixedScale

        val collapsedX = fixedWidth * 0.5f - V1_MAIN_WIDTH * 0.5f
        val centerX = collapsedX + V1_MAIN_WIDTH * 0.5f
        // TODO: expand temporarily disabled
        musicExpand.target = 0.0f
        musicExpand.update(dt)
        val expand = 0.0f

        context.matrices.pushMatrix()
        context.matrices.scale(fixedScale, fixedScale)
        when (WatermarkSettings.version()) {
            WatermarkSettings.Version.V1 -> drawVersion1Watermark(context, client, collapsedX, centerX, expand)
            WatermarkSettings.Version.V2 -> drawVersion2Watermark(context, client, V2_X, V2_Y)
        }
        context.matrices.popMatrix()
    }

    private fun drawVersion1Watermark(
        context: DrawContext,
        client: MinecraftClient,
        collapsedX: Float,
        centerX: Float,
        expand: Float,
    ) {
        val width = lerp(V1_MAIN_WIDTH, V1_HOVER_WIDTH, expand)
        val height = lerp(V1_MAIN_HEIGHT, V1_HOVER_HEIGHT, expand)
        val x = centerX - width * 0.5f
        lastV1HoverX = x
        lastV1HoverWidth = width

        if (expand < 0.01f) {
            drawVersion1Main(context, client, collapsedX, V1_Y)
            return
        }

        val radius = lerp(200.0f, 30.0f, expand)
        panel(context, x, V1_Y, width, height, radius, 3.0f)

        val clipInsetX = lerp(6.0f, 18.0f, expand)
        val clipInsetY = lerp(3.0f, 8.0f, expand)
        HypnosiaScissor.withLocalRect(
            context,
            Rect(
                x + clipInsetX,
                V1_Y + clipInsetY,
                width - clipInsetX * 2.0f,
                height - clipInsetY * 2.0f,
            ),
        ) {
            val mainAlpha = (1.0f - expand * 1.8f).coerceIn(0.0f, 1.0f)
            val hoverAlpha = ((expand - 0.72f) / 0.28f).coerceIn(0.0f, 1.0f)
            if (mainAlpha > 0.01f) {
                drawVersion1MainContent(context, client, x, V1_Y, mainAlpha)
            }
            if (hoverAlpha > 0.01f) {
                drawVersion1HoverContent(context, x, V1_Y, hoverAlpha)
            }
        }
    }

    private fun drawVersion1Main(context: DrawContext, client: MinecraftClient, x: Float, y: Float) {
        panel(context, x, y, V1_MAIN_WIDTH, V1_MAIN_HEIGHT, 200.0f, 3.0f)
        drawVersion1MainContent(context, client, x, y, 1.0f)
    }

    private fun drawVersion1MainContent(context: DrawContext, client: MinecraftClient, x: Float, y: Float, alpha: Float) {
        val metrics = metrics(client)
        val textStartX = x + 12.0f
        val textY = y + 9.0f
        val textHeight = 46.0f
        val fpsStartX = x + 199.0f
        val maxTextWidth = fpsStartX - textStartX - 8.0f

        val hasTrack = trackTitle.isNotBlank() && trackTitle != "Minecraft"
        val displayText = musicTitle(client)
        val finalText = truncateText(displayText, Text24, maxTextWidth)

        if (hasTrack) {
            // Трек — белый текст
            drawText(context, finalText, textStartX, textY, maxTextWidth, textHeight, Text24, WHITE, alpha)
        } else {
            // Ник — градиент если есть, иначе белый
            val session = (AccountManager.state as? AccountState.Valid)?.session
            val role = effectiveRole(session)
            val nickGradientColors = parseGradientColors(session?.nickGradients?.get(role?.name))
            if (nickGradientColors.size >= 2) {
                val time = (System.currentTimeMillis() % 1000000L) / 1000f
                FigmaTextRenderer.drawGradientInBox(
                    context = context,
                    text = finalText,
                    x = textStartX,
                    y = textY,
                    width = maxTextWidth,
                    height = textHeight,
                    color = WHITE,
                    style = Text24,
                    gradientColor1 = nickGradientColors[0],
                    gradientColor2 = nickGradientColors[1],
                    time = time,
                    verticalAlign = FigmaTextRenderer.VerticalAlign.Center,
                    fallbackColor = WHITE,
                )
            } else {
                drawText(context, finalText, textStartX, textY, maxTextWidth, textHeight, Text24, WHITE, alpha)
            }
        }

        drawText(
            context = context,
            text = metrics.fps.toString(),
            x = fpsStartX,
            y = textY,
            width = 66.0f,
            height = textHeight,
            style = Text24,
            color = WHITE,
            alpha = alpha,
            align = FigmaTextRenderer.HorizontalAlign.Right,
        )
        drawText(context, "/", x + 265.0f, y + 9.0f, 17.0f, 46.0f, Text24, MUTED, alpha, FigmaTextRenderer.HorizontalAlign.Center)
        drawText(context, metrics.ping.toString(), x + 279.0f, y + 9.0f, 60.0f, 46.0f, Text24, WHITE, alpha)
        drawColoredIcon(context, "black_hole.png", x + 343.0f, y + 14.0f, 32.0f, 32.0f, alpha)
    }

    private fun drawVersion1HoverContent(context: DrawContext, x: Float, y: Float, alpha: Float) {
        val coverId = coverTextureId
        if (coverId != null) {
            HypnosiaRenderUtils.drawVanillaIcon(context, coverId, x + 11.0f, y + 11.0f, 96.0f, 96.0f, a(WHITE, alpha))
        }
        drawText(context, trackTitle, x + 114.0f, y + 14.0f, 241.0f, 29.0f, Text20, WHITE, alpha)
        drawText(context, trackArtist, x + 114.0f, y + 43.0f, 241.0f, 29.0f, Text16, MUTED, alpha)
        HypnosiaRenderUtils.drawVanillaIcon(context, iconId("previous.png"), x + 359.0f, y + 8.0f, 24.0f, 24.0f, a(WHITE, alpha))
        if (isMediaPlaying) {
            HypnosiaRenderUtils.drawVanillaIcon(context, iconId("pause.png"), x + 383.0f, y + 8.0f, 24.0f, 24.0f, a(WHITE, alpha))
        } else {
            HypnosiaRenderUtils.drawVanillaIcon(context, iconId("play.png"), x + 383.0f, y + 8.0f, 24.0f, 24.0f, a(WHITE, alpha))
        }
        HypnosiaRenderUtils.drawVanillaIcon(context, iconId("next.png"), x + 407.0f, y + 8.0f, 24.0f, 24.0f, a(WHITE, alpha))
        drawColoredIcon(context, "black_hole.png", x + 430.0f, y + 8.0f, 24.0f, 24.0f, alpha)

        if (trackDurationMs > 0) {
            val progressWidth = 404.0f * getSmoothProgress().coerceIn(0.0f, 1.0f)
            HypnosiaRenderUtils.drawFigmaBox(context, x + 8.0f, y + 132.0f, 404.0f, 8.0f, 4.0f, a(PROGRESS_TRACK, alpha))
            HypnosiaRenderUtils.drawFigmaBox(context, x + 8.0f, y + 132.0f, progressWidth, 8.0f, 4.0f, a(0xFFFF2F86.toInt(), alpha))
        }
        val elapsedStr = formatTime(trackPositionMs)
        val durationStr = formatTime(trackDurationMs)
        drawText(context, "$elapsedStr / $durationStr", x + 417.0f, y + 128.0f, 69.0f, 14.0f, Text12, WHITE, alpha)
    }

    private fun drawVersion2Watermark(context: DrawContext, client: MinecraftClient, x: Float, y: Float) {
        var topX = x
        if (WatermarkSettings.isEnabled(WatermarkSettings.Module.VISUAL_ICON)) {
            panel(context, topX, y, 34.0f, 34.0f, 10.0f, 1.0f)
            drawColoredIcon(context, "black_hole.png", topX + 1.0f, y + 1.0f, 32.0f, 32.0f, 1.0f)
            topX += 38.0f
        }

        val clientInfoHeight = drawVersion2ClientInfo(context, client, topX, y)
        drawVersion2ServerInfo(context, client, x, y + clientInfoHeight + 4.0f)
    }

    private fun drawVersion2ClientInfo(context: DrawContext, client: MinecraftClient, x: Float, y: Float): Float {
        val session = (AccountManager.state as? AccountState.Valid)?.session
        val role = effectiveRole(session)
        val nick = StreamerModeSettings.displayName(session?.displayName?.takeIf { it.isNotBlank() } ?: client.session.username)
        val fps = "${metrics(client).fps} fps"

        val nickGradientColors = parseGradientColors(session?.nickGradients?.get(role?.name))
        val segments = buildList {
            if (role != null && WatermarkSettings.isEnabled(WatermarkSettings.Module.ROLE)) {
                val roleGradientColors = parseGradientColors(session?.roleGradients?.get(role.name))
                val roleIconPath = session?.roleIcons?.get(role.name)
                val iconName = if (!roleIconPath.isNullOrBlank() && !roleIconPath.startsWith("/")) {
                    roleIconPath
                } else {
                    LicenseRole.iconFor(role)
                }
                add(
                    WatermarkSegment(
                        icon = iconName,
                        text = LicenseRole.displayName(role),
                        textWidth = max(40.0f, textWidth(LicenseRole.displayName(role), Text18)),
                        textStyle = Text18,
                        textColor = roleGradientColors.firstOrNull() ?: WHITE,
                        gradientColors = roleGradientColors,
                    ),
                )
            }
            if (WatermarkSettings.isEnabled(WatermarkSettings.Module.NICK)) {
                add(
                    WatermarkSegment(
                        icon = "location_user.png",
                        text = nick,
                        textWidth = max(44.0f, textWidth(nick, Text18)),
                        textStyle = Text18,
                        textColor = nickGradientColors.firstOrNull() ?: WHITE,
                        gradientColors = nickGradientColors,
                    ),
                )
            }
            if (WatermarkSettings.isEnabled(WatermarkSettings.Module.FPS)) {
                add(
                    WatermarkSegment(
                        icon = "flash.png",
                        text = fps,
                        textWidth = max(77.0f, textWidth(fps, Text18) + 4.0f),
                        textStyle = Text18,
                    ),
                )
            }
        }

        val maxBarWidth = client.window.framebufferWidth.toFloat() - x
        return drawVersion2SegmentBar(context, x, y, segments, maxBarWidth)
    }

    private fun drawVersion2ServerInfo(context: DrawContext, client: MinecraftClient, x: Float, y: Float) {
        val metrics = metrics(client)
        val segments = buildList {
            if (WatermarkSettings.isEnabled(WatermarkSettings.Module.SERVER)) {
                add(
                    WatermarkSegment(
                        icon = "server.png",
                        text = serverLabel(client),
                        textWidth = max(66.0f, textWidth(serverLabel(client), Text16)),
                        textStyle = Text16,
                    ),
                )
            }
            if (WatermarkSettings.isEnabled(WatermarkSettings.Module.PING)) {
                val ping = "${metrics.ping} ms"
                add(
                    WatermarkSegment(
                        icon = "satellite.png",
                        text = ping,
                        textWidth = max(70.0f, textWidth(ping, Text20)),
                        textStyle = Text20,
                    ),
                )
            }
            if (WatermarkSettings.isEnabled(WatermarkSettings.Module.RAM)) {
                val ram = "${metrics.ram}%"
                add(
                    WatermarkSegment(
                        icon = "ram.png",
                        text = ram,
                        textWidth = max(54.0f, textWidth(ram, Text20)),
                        textStyle = Text20,
                    ),
                )
            }
            if (WatermarkSettings.isEnabled(WatermarkSettings.Module.CPU)) {
                val cpu = "${metrics.cpu}%"
                add(
                    WatermarkSegment(
                        icon = "cpu.png",
                        text = cpu,
                        textWidth = max(64.0f, textWidth(cpu, Text20)),
                        textStyle = Text20,
                    ),
                )
            }
        }

        drawVersion2SegmentBar(context, x, y, segments)
    }

    private fun drawVersion2SegmentBar(
        context: DrawContext,
        x: Float,
        y: Float,
        segments: List<WatermarkSegment>,
        maxWidth: Float = Float.POSITIVE_INFINITY,
    ): Float {
        if (segments.isEmpty()) return 0.0f

        val panelHeight = 34.0f
        val iconSize = 24.0f
        val gap = 8.0f

        // Split segments into rows that fit within maxWidth
        val rows = mutableListOf<List<WatermarkSegment>>()
        var currentRow = mutableListOf<WatermarkSegment>()
        var currentRowWidth = 8.0f // left + right padding

        segments.forEachIndexed { index, segment ->
            val segmentWidth = 28.0f + segment.textWidth + if (currentRow.isNotEmpty()) gap else 0.0f
            if (currentRow.isNotEmpty() && currentRowWidth + segmentWidth > maxWidth) {
                rows += currentRow
                currentRow = mutableListOf()
                currentRowWidth = 8.0f
            }
            currentRow += segment
            currentRowWidth += segmentWidth
        }
        if (currentRow.isNotEmpty()) {
            rows += currentRow
        }

        var currentY = y
        rows.forEach { rowSegments ->
            val width = calculateVersion2BarWidth(rowSegments)
            panel(context, x, currentY, width, panelHeight, 10.0f, 1.0f)

            var cursorX = x + 4.0f
            val iconTop = currentY + (panelHeight - iconSize) / 2.0f
            rowSegments.forEachIndexed { index, segment ->
                drawIcon(context, segment.icon, cursorX, iconTop, iconSize, iconSize, segment.textColor)
                if (segment.gradientColors != null && segment.gradientColors.size >= 2) {
                    val time = (System.currentTimeMillis() % 1000000L) / 1000f
                    FigmaTextRenderer.drawGradientInBox(
                        context = context,
                        text = segment.text,
                        x = cursorX + 28.0f,
                        y = currentY,
                        width = segment.textWidth,
                        height = panelHeight,
                        color = WHITE,
                        style = segment.textStyle,
                        gradientColor1 = segment.gradientColors[0],
                        gradientColor2 = segment.gradientColors[1],
                        time = time,
                        verticalAlign = FigmaTextRenderer.VerticalAlign.Center,
                        fallbackColor = segment.textColor,
                    )
                } else {
                    drawText(
                        context = context,
                        text = segment.text,
                        x = cursorX + 28.0f,
                        y = currentY,
                        width = segment.textWidth,
                        height = panelHeight,
                        style = segment.textStyle,
                        color = segment.textColor,
                        alpha = 1.0f,
                        align = FigmaTextRenderer.HorizontalAlign.Center,
                    )
                }
                cursorX += 28.0f + segment.textWidth
                if (index != rowSegments.lastIndex) {
                    cursorX += gap
                }
            }
            currentY += panelHeight + 10.0f // 34 height + 10px gap
        }
        return rows.size * panelHeight + (rows.size - 1).coerceAtLeast(0) * 10.0f
    }

    private fun parseGradientColors(gradientStr: String?): List<Int> {
        if (gradientStr.isNullOrBlank()) return emptyList()
        val hexRegex = Regex("#([A-Fa-f0-9]{6})")
        return hexRegex.findAll(gradientStr).map { match ->
            val hex = match.groupValues[1]
            0xFF000000.toInt() or hex.toInt(16)
        }.toList()
    }

    private fun calculateVersion2BarWidth(segments: List<WatermarkSegment>): Float {
        var width = 8.0f
        val gap = 8.0f
        segments.forEachIndexed { index, segment ->
            width += 28.0f + segment.textWidth
            if (index != segments.lastIndex) {
                width += gap
            }
        }
        return width + 8.0f
    }

    private fun effectiveRole(session: dev.hypnosia.license.AccountSession?): LicenseRole? {
        return session?.roles?.firstOrNull { it.name != "USER" }
    }

    private fun roleIconName(role: LicenseRole, session: dev.hypnosia.license.AccountSession?): String {
        return LicenseRole.iconFor(role)
    }

    private fun musicTitle(client: MinecraftClient): String =
        if (trackTitle.isNotBlank() && trackTitle != "Minecraft") trackTitle else StreamerModeSettings.displayName(client.session.username)

    private fun truncateText(text: String, style: FigmaTextRenderer.FigmaTextStyle, maxWidth: Float): String {
        if (FigmaTextRenderer.width(text, style) <= maxWidth) return text
        val ellipsis = "..."
        val ellipsisWidth = FigmaTextRenderer.width(ellipsis, style)
        var i = text.length
        while (i > 0) {
            val truncated = text.substring(0, i)
            if (FigmaTextRenderer.width(truncated, style) + ellipsisWidth <= maxWidth) {
                return truncated + ellipsis
            }
            i--
        }
        return ellipsis
    }

    private fun panel(context: DrawContext, x: Float, y: Float, width: Float, height: Float, radius: Float, stroke: Float) {
        HypnosiaRenderUtils.drawFigmaBox(context, x, y, width, height, radius, BG, STROKE, stroke)
    }

    private fun drawIcon(context: DrawContext, name: String, x: Float, y: Float, width: Float, height: Float, color: Int) {
        HypnosiaRenderUtils.drawIconTexture(context, iconId(name), x, y, width, height, color)
    }

    private fun drawColoredIcon(context: DrawContext, name: String, x: Float, y: Float, width: Float, height: Float, alpha: Float) {
        HypnosiaRenderUtils.drawRoundedTexture(context, iconId(name), x, y, width, height, 0.0f, a(WHITE, alpha))
    }

    private fun drawDivider(context: DrawContext, x: Float, y: Float) {
        HypnosiaRenderUtils.drawFigmaBox(
            context = context,
            x = x,
            y = y + 6.0f,
            width = 2.0f,
            height = 22.0f,
            radius = 1.0f,
            bgColor = SEPARATOR,
        )
    }

    private fun drawText(
        context: DrawContext,
        text: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        style: FigmaTextRenderer.FigmaTextStyle,
        color: Int = WHITE,
        alpha: Float = 1.0f,
        align: FigmaTextRenderer.HorizontalAlign = FigmaTextRenderer.HorizontalAlign.Left,
    ) {
        FigmaTextRenderer.drawInBox(context, text, x, y, width, height, a(color, alpha), style, align, FigmaTextRenderer.VerticalAlign.Center)
    }

    private fun serverLabel(client: MinecraftClient): String {
        return client.currentServerEntry?.address?.take(12) ?: "Single..."
    }

    private fun isMusicActive(): Boolean = trackTitle.isNotBlank() && trackTitle != "Minecraft"

    fun handleV1PlayerClick(mouseX: Float, mouseY: Float): Boolean {
        if (WatermarkSettings.version() != WatermarkSettings.Version.V1) return false
        val x = lastV1HoverX
        val y = V1_Y
        val w = lastV1HoverWidth
        if (w < V1_HOVER_WIDTH * 0.9f) {
            println("[Hypnosia] Click ignored: not expanded enough (w=$w)")
            return false
        }
        if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + V1_HOVER_HEIGHT) {
            return false
        }

        // Button rects inside hover panel
        val prevRect = Rect(x + 359.0f, y + 8.0f, 24.0f, 24.0f)
        val playRect = Rect(x + 383.0f, y + 8.0f, 24.0f, 24.0f)
        val nextRect = Rect(x + 407.0f, y + 8.0f, 24.0f, 24.0f)

        return when {
            contains(mouseX, mouseY, prevRect.x, prevRect.y, prevRect.width, prevRect.height) -> {
                println("[Hypnosia] Click: PREV button at $mouseX,$mouseY")
                MediaBridge.sendCommand("prev")
                true
            }
            contains(mouseX, mouseY, playRect.x, playRect.y, playRect.width, playRect.height) -> {
                println("[Hypnosia] Click: PLAY/PAUSE button at $mouseX,$mouseY")
                MediaBridge.sendCommand(if (isMediaPlaying) "pause" else "play")
                true
            }
            contains(mouseX, mouseY, nextRect.x, nextRect.y, nextRect.width, nextRect.height) -> {
                println("[Hypnosia] Click: NEXT button at $mouseX,$mouseY")
                MediaBridge.sendCommand("next")
                true
            }
            else -> {
                println("[Hypnosia] Click inside panel but missed buttons (mouse=$mouseX,$mouseY, buttons at prev=$prevRect play=$playRect next=$nextRect)")
                false
            }
        }
    }

    private fun textWidth(text: String, style: FigmaTextRenderer.FigmaTextStyle): Float =
        FigmaTextRenderer.width(text, style)

    private fun pingMs(client: MinecraftClient): Int =
        client.player?.networkHandler?.getPlayerListEntry(client.player!!.uuid)?.latency ?: 0

    private fun metrics(client: MinecraftClient): WatermarkMetrics {
        val now = System.currentTimeMillis()
        if (now - lastMetricsAtMs >= METRICS_CACHE_MS) {
            cachedMetrics = WatermarkMetrics(
                fps = client.currentFps,
                ping = pingMs(client),
                ram = ramPercent(),
                cpu = cpuPercent(),
            )
            lastMetricsAtMs = now
        }
        return cachedMetrics
    }

    private fun ramPercent(): Int {
        val runtime = Runtime.getRuntime()
        return (((runtime.totalMemory() - runtime.freeMemory()) * 100L) / runtime.maxMemory()).toInt()
    }

    private fun cpuPercent(): Int {
        val load = (osBean as? OperatingSystemMXBean)?.let { bean ->
            runCatching { bean.cpuLoad.takeIf { it >= 0.0 } ?: bean.processCpuLoad.takeIf { it >= 0.0 } }.getOrNull()
        } ?: runCatching { (cpuLoadMethod?.invoke(osBean) as? Number)?.toDouble() }.getOrNull()
            ?.takeIf { it >= 0.0 }
            ?: return 0
        return (load.coerceIn(0.0, 1.0) * 100.0).toInt()
    }

    private fun lerp(from: Float, to: Float, amount: Float): Float =
        from + (to - from) * amount.coerceIn(0.0f, 1.0f)

    private fun contains(mouseX: Float, mouseY: Float, x: Float, y: Float, width: Float, height: Float): Boolean {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }

    private fun a(color: Int, alpha: Float): Int {
        val channel = (((color ushr 24) and 0xFF) * alpha.coerceIn(0.0f, 1.0f)).toInt()
        return (color and 0x00FFFFFF) or (channel shl 24)
    }

    private const val V1_Y = 11.0f
    private const val V1_MAIN_WIDTH = 400.0f
    private const val V1_MAIN_HEIGHT = 66.0f
    private const val V1_HOVER_WIDTH = 500.0f
    private const val V1_HOVER_HEIGHT = 164.0f

    private const val V2_X = 20.0f
    private const val V2_Y = 19.0f
    private const val METRICS_CACHE_MS = 200L
}
