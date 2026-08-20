package dev.hypnosia.ui.render

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.ProjectionType
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vertex.VertexFormat
import dev.hypnosia.config.IconSettings
import dev.hypnosia.config.ThemeSettings
import dev.hypnosia.render.HypnosiaShaders
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gl.GpuSampler
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.BuiltBuffer
import net.minecraft.client.render.BufferBuilder
import net.minecraft.client.render.ProjectionMatrix2
import net.minecraft.client.render.Tessellator
import net.minecraft.client.render.VertexFormats
import net.minecraft.util.Identifier
import org.joml.Matrix3x2f
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.OptionalDouble
import java.util.OptionalInt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

object HypnosiaRenderUtils {
    private const val GUI_MODEL_VIEW_Z = -11000.0f
    private const val GUI_VERTEX_Z = 0.0f
    private const val BOX_UNIFORM_BYTES = 48
    private const val SHADOW_BOX_UNIFORM_BYTES = 48
    private const val GRADIENT_BOX_UNIFORM_BYTES = 80
    private const val GLASS_BOX_UNIFORM_BYTES = 80
    private const val HSV_CANVAS_UNIFORM_BYTES = 16
    private const val HUE_STRIP_UNIFORM_BYTES = 16
    private const val ALPHA_STRIP_UNIFORM_BYTES = 32
    private const val TEXTURE_BOX_UNIFORM_BYTES = 32
    private val guiProjection = ProjectionMatrix2("hypnosia_gui", 1000.0f, 11000.0f, true)
    private var backdropTexture: GpuTexture? = null
    private var backdropView: GpuTextureView? = null
    private var backdropWidth = 0
    private var backdropHeight = 0
    private var backdropReady = false
    private var backdropSampler: GpuSampler? = null

    fun captureThemeBackdrop(context: DrawContext) {
        backdropReady = false
        if (!ThemeSettings.enabled) return
        if (ThemeSettings.mode() != ThemeSettings.Mode.TRANSPARENT && !ThemeSettings.glassActive()) return

        context.draw()

        val framebuffer = MinecraftClient.getInstance().framebuffer
        val width = framebuffer.textureWidth
        val height = framebuffer.textureHeight
        if (width <= 0 || height <= 0) return
        if (!ensureBackdropTexture(width, height)) return

        val target = backdropTexture ?: return
        runCatching {
            RenderSystem.getDevice()
                .createCommandEncoder()
                .copyTextureToTexture(
                    framebuffer.getColorAttachment(),
                    target,
                    0,
                    0,
                    0,
                    0,
                    width,
                    height,
                    0,
                )
        }.onSuccess {
            backdropReady = true
        }.onFailure {
            backdropReady = false
        }
    }

    private fun ensureBackdropTexture(width: Int, height: Int): Boolean {
        val existing = backdropTexture
        if (
            existing != null &&
            !existing.isClosed() &&
            backdropView?.isClosed() == false &&
            backdropWidth == width &&
            backdropHeight == height
        ) {
            return true
        }

        closeBackdropTexture()

        val sourceFormat = MinecraftClient.getInstance().framebuffer.getColorAttachment()?.getFormat() ?: return false
        return runCatching {
            val texture = RenderSystem.getDevice().createTexture(
                "Hypnosia theme backdrop",
                GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING,
                sourceFormat,
                width,
                height,
                1,
                1,
            )
            backdropTexture = texture
            backdropView = RenderSystem.getDevice().createTextureView(texture)
            backdropWidth = width
            backdropHeight = height
        }.isSuccess
    }

    private fun closeBackdropTexture() {
        runCatching { backdropView?.close() }
        runCatching { backdropTexture?.close() }
        backdropView = null
        backdropTexture = null
        backdropWidth = 0
        backdropHeight = 0
        backdropReady = false
    }

    private fun backdropSampler(): GpuSampler? {
        backdropSampler?.let { return it }
        return runCatching {
            RenderSystem.getDevice().createSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR,
                FilterMode.LINEAR,
                1,
                OptionalDouble.empty(),
            )
        }.getOrNull()?.also {
            backdropSampler = it
        }
    }

    fun drawFigmaBox(
        context: DrawContext,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        bgColor: Int,
        strokeColor: Int = 0x00000000,
        strokeThickness: Float = 0.0f,
        flushDeferredBeforeDraw: Boolean = true,
    ) {
        if (width <= 0.0f || height <= 0.0f) {
            return
        }

        drawFigmaBoxRaw(
            context = context,
            x = x,
            y = y,
            width = width,
            height = height,
            radius = radius,
            bgColor = bgColor,
            strokeColor = strokeColor,
            strokeThickness = strokeThickness,
            flushDeferredBeforeDraw = flushDeferredBeforeDraw,
        )
    }

    fun drawThemedBox(
        context: DrawContext,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        bgColor: Int,
        strokeColor: Int = 0x00000000,
        strokeThickness: Float = 0.0f,
        role: ThemeSettings.ThemeRole = ThemeSettings.ThemeRole.CARD,
        flushDeferredBeforeDraw: Boolean = true,
    ) {
        if (width <= 0.0f || height <= 0.0f) {
            return
        }

        val themed = ThemeSettings.surface(role, bgColor, strokeColor)
        if (themed != null) {
            if (themed.liquidGlass) {
                LiquidGlassSurface.draw(
                    context = context,
                    x = x,
                    y = y,
                    width = width,
                    height = height,
                    radius = radius,
                    bgColor = themed.bgColor,
                    strokeColor = themed.strokeColor,
                    sourceStrokeColor = strokeColor,
                    strokeThickness = strokeThickness,
                    role = role,
                    intensity = themed.glassStrength,
                    flushDeferredBeforeDraw = flushDeferredBeforeDraw,
                )
            } else if (themed.useGradient) {
                drawLinearGradientBox(
                    context = context,
                    x = x,
                    y = y,
                    width = width,
                    height = height,
                    radius = radius,
                    startColor = themed.gradientStart,
                    endColor = themed.gradientEnd,
                    angleDegrees = 135.0f,
                    strokeColor = themed.strokeColor,
                    strokeThickness = strokeThickness,
                    flushDeferredBeforeDraw = flushDeferredBeforeDraw,
                )
            } else {
                drawFigmaBoxRaw(
                    context = context,
                    x = x,
                    y = y,
                    width = width,
                    height = height,
                    radius = radius,
                    bgColor = themed.bgColor,
                    strokeColor = themed.strokeColor,
                    strokeThickness = strokeThickness,
                    flushDeferredBeforeDraw = flushDeferredBeforeDraw,
                )
            }
            return
        }

        drawFigmaBoxRaw(
            context = context,
            x = x,
            y = y,
            width = width,
            height = height,
            radius = radius,
            bgColor = bgColor,
            strokeColor = strokeColor,
            strokeThickness = strokeThickness,
            flushDeferredBeforeDraw = flushDeferredBeforeDraw,
        )
    }

    private fun drawFigmaBoxRaw(
        context: DrawContext,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        bgColor: Int,
        strokeColor: Int = 0x00000000,
        strokeThickness: Float = 0.0f,
        flushDeferredBeforeDraw: Boolean = true,
    ) {
        if (width <= 0.0f || height <= 0.0f) {
            return
        }

        val safeRadius = radius.coerceIn(0.0f, min(width, height) * 0.5f)
        val safeStroke = strokeThickness.coerceIn(0.0f, max(0.0f, min(width, height) * 0.5f))
        val uniformBuffer = createBoxUniformBuffer(width, height, safeRadius, safeStroke, bgColor, strokeColor)
        val pipeline = if (shouldUseOpaqueBoxPipeline(width, height, safeRadius, bgColor, strokeColor, safeStroke)) {
            HypnosiaShaders.SDF_ROUNDED_RECT_OPAQUE
        } else {
            HypnosiaShaders.SDF_ROUNDED_RECT
        }

        drawUniformQuad(
            context = context,
            debugName = "Hypnosia SDF rounded rectangle",
            pipeline = pipeline,
            uniformName = "HypnosiaBox",
            uniformBuffer = uniformBuffer,
            x = x,
            y = y,
            width = width,
            height = height,
            flushDeferredBeforeDraw = flushDeferredBeforeDraw,
        )
    }

    fun drawFigmaBox(
        context: DrawContext,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        radius: Int,
        bgColor: Int,
        strokeColor: Int = 0x00000000,
        strokeThickness: Float = 0.0f,
        flushDeferredBeforeDraw: Boolean = true,
    ) {
        drawFigmaBox(
            context = context,
            x = x.toFloat(),
            y = y.toFloat(),
            width = width.toFloat(),
            height = height.toFloat(),
            radius = radius.toFloat(),
            bgColor = bgColor,
            strokeColor = strokeColor,
            strokeThickness = strokeThickness,
            flushDeferredBeforeDraw = flushDeferredBeforeDraw,
        )
    }

    fun drawSdfShadowBox(
        context: DrawContext,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        shadowSpread: Float,
        shadowBlur: Float,
        color: Int,
        flushDeferredBeforeDraw: Boolean = true,
    ) {
        if (width <= 0.0f || height <= 0.0f) {
            return
        }

        val safeSpread = shadowSpread.coerceAtLeast(0.0f)
        val safeBlur = shadowBlur.coerceAtLeast(0.0f)
        val extent = safeSpread + safeBlur * 2.0f
        val uniform = createShadowBoxUniformBuffer(
            width = width,
            height = height,
            radius = radius.coerceIn(0.0f, min(width, height) * 0.5f),
            spread = safeSpread,
            blur = safeBlur,
            extent = extent,
            color = color,
        )

        drawUniformQuad(
            context = context,
            debugName = "Hypnosia SDF drop shadow",
            pipeline = HypnosiaShaders.SDF_DROP_SHADOW,
            uniformName = "HypnosiaShadowBox",
            uniformBuffer = uniform,
            x = x - extent,
            y = y - extent,
            width = width + extent * 2.0f,
            height = height + extent * 2.0f,
            flushDeferredBeforeDraw = flushDeferredBeforeDraw,
        )
    }

    fun drawSdfShadowBox(
        context: DrawContext,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        radius: Int,
        shadowSpread: Float,
        shadowBlur: Float,
        color: Int,
        flushDeferredBeforeDraw: Boolean = true,
    ) {
        drawSdfShadowBox(
            context = context,
            x = x.toFloat(),
            y = y.toFloat(),
            width = width.toFloat(),
            height = height.toFloat(),
            radius = radius.toFloat(),
            shadowSpread = shadowSpread,
            shadowBlur = shadowBlur,
            color = color,
            flushDeferredBeforeDraw = flushDeferredBeforeDraw,
        )
    }

    fun drawLinearGradientBox(
        context: DrawContext,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        startColor: Int,
        endColor: Int,
        angleDegrees: Float,
        strokeColor: Int = 0x00000000,
        strokeThickness: Float = 0.0f,
        flushDeferredBeforeDraw: Boolean = true,
    ) {
        val safeRadius = radius.coerceIn(0.0f, min(width, height) * 0.5f)
        val safeStroke = strokeThickness.coerceIn(0.0f, max(0.0f, min(width, height) * 0.5f))
        val radians = Math.toRadians(angleDegrees.toDouble())
        val directionX = kotlin.math.cos(radians).toFloat()
        val directionY = kotlin.math.sin(radians).toFloat()
        val uniform = createGradientBoxUniformBuffer(
            width = width,
            height = height,
            radius = safeRadius,
            strokeThickness = safeStroke,
            directionX = directionX,
            directionY = directionY,
            startColor = startColor,
            endColor = endColor,
            strokeColor = strokeColor,
        )
        drawUniformQuad(
            context = context,
            debugName = "Hypnosia SDF linear gradient box",
            pipeline = HypnosiaShaders.SDF_LINEAR_GRADIENT_BOX,
            uniformName = "HypnosiaGradientBox",
            uniformBuffer = uniform,
            x = x,
            y = y,
            width = width,
            height = height,
            flushDeferredBeforeDraw = flushDeferredBeforeDraw,
        )
    }

    fun drawLiquidGlassBox(
        context: DrawContext,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        bgColor: Int,
        strokeColor: Int,
        strokeThickness: Float = 1.0f,
        strength: Float = 0.85f,
        distortion: Float = 0.0f,
        blurRadius: Float = 18.0f,
        flushDeferredBeforeDraw: Boolean = true,
    ) {
        val backdropView = backdropView
        val backdropSampler = backdropSampler()
        if (!backdropReady || backdropView == null || backdropView.isClosed() || backdropSampler == null) {
            drawFigmaBoxRaw(
                context = context,
                x = x,
                y = y,
                width = width,
                height = height,
                radius = radius,
                bgColor = bgColor,
                strokeColor = strokeColor,
                strokeThickness = strokeThickness,
                flushDeferredBeforeDraw = flushDeferredBeforeDraw,
            )
            return
        }

        val safeRadius = radius.coerceIn(0.0f, min(width, height) * 0.5f)
        val safeStroke = strokeThickness.coerceIn(0.0f, max(0.0f, min(width, height) * 0.5f))
        val uniform = createGlassBoxUniformBuffer(
            width = width,
            height = height,
            radius = safeRadius,
            strokeThickness = safeStroke,
            bgColor = bgColor,
            strokeColor = strokeColor,
            strength = strength,
            distortion = distortion,
            blurRadius = blurRadius,
        )
        drawUniformQuad(
            context = context,
            debugName = "Hypnosia SDF liquid glass box",
            pipeline = HypnosiaShaders.SDF_LIQUID_GLASS_BOX,
            uniformName = "HypnosiaGlassBox",
            uniformBuffer = uniform,
            x = x,
            y = y,
            width = width,
            height = height,
            textureView = backdropView,
            sampler = backdropSampler,
            flushDeferredBeforeDraw = flushDeferredBeforeDraw,
        )
    }

    fun drawHsvColorCanvas(
        context: DrawContext,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        hueDegrees: Float,
        flushDeferredBeforeDraw: Boolean = true,
    ) {
        val uniform = createHsvCanvasUniformBuffer(width, height, radius, hueDegrees)
        drawUniformQuad(
            context = context,
            debugName = "Hypnosia HSV color canvas",
            pipeline = HypnosiaShaders.HSV_COLOR_CANVAS,
            uniformName = "HypnosiaHsvCanvas",
            uniformBuffer = uniform,
            x = x,
            y = y,
            width = width,
            height = height,
            flushDeferredBeforeDraw = flushDeferredBeforeDraw,
        )
    }

    fun drawHueStrip(
        context: DrawContext,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        flushDeferredBeforeDraw: Boolean = true,
    ) {
        val uniform = createHueStripUniformBuffer(width, height, radius)
        drawUniformQuad(
            context = context,
            debugName = "Hypnosia HSV hue strip",
            pipeline = HypnosiaShaders.HSV_HUE_STRIP,
            uniformName = "HypnosiaHueStrip",
            uniformBuffer = uniform,
            x = x,
            y = y,
            width = width,
            height = height,
            flushDeferredBeforeDraw = flushDeferredBeforeDraw,
        )
    }

    fun drawAlphaStrip(
        context: DrawContext,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        color: Int,
        checkerSize: Float = 4.0f,
        flushDeferredBeforeDraw: Boolean = true,
    ) {
        val uniform = createAlphaStripUniformBuffer(width, height, radius, checkerSize, color)
        drawUniformQuad(
            context = context,
            debugName = "Hypnosia HSV alpha strip",
            pipeline = HypnosiaShaders.HSV_ALPHA_STRIP,
            uniformName = "HypnosiaAlphaStrip",
            uniformBuffer = uniform,
            x = x,
            y = y,
            width = width,
            height = height,
            flushDeferredBeforeDraw = flushDeferredBeforeDraw,
        )
    }

    fun drawRoundedTexture(
        context: DrawContext,
        identifier: Identifier,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        tintColor: Int = 0xFFFFFFFF.toInt(),
        flushDeferredBeforeDraw: Boolean = true,
    ) {
        if (IconSettings.shouldSkip(identifier)) return
        val themedTint = ThemeSettings.resolveIconTint(
            identifierPath = identifier.path,
            color = IconSettings.tint(identifier, tintColor),
        )
        drawTexture(
            context = context,
            identifier = identifier,
            x = x,
            y = y,
            width = width,
            height = height,
            radius = radius,
            tintColor = themedTint,
            iconMaskMode = false,
            flushDeferredBeforeDraw = flushDeferredBeforeDraw,
        )
    }

    /**
     * Draws a role icon using vanilla GUI_TEXTURED pipeline.
     * This bypasses our SDF shader entirely, preserving original PNG colors with NEAREST filtering.
     * Use ONLY for raster icons that should not be tinted/masked (e.g. downloaded role icons).
     */
    fun drawRoleIcon(
        context: DrawContext,
        identifier: Identifier,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ) {
        val snappedX = round(x).toInt()
        val snappedY = round(y).toInt()
        val snappedW = max(1, round(width).toInt())
        val snappedH = max(1, round(height).toInt())
        context.drawTexture(
            RenderPipelines.GUI_TEXTURED,
            identifier,
            snappedX,
            snappedY,
            0.0f,
            0.0f,
            snappedW,
            snappedH,
            snappedW,
            snappedH,
            0xFFFFFFFF.toInt(),
        )
    }

    fun drawVanillaIcon(
        context: DrawContext,
        identifier: Identifier,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        tintColor: Int = 0xFFFFFFFF.toInt(),
    ) {
        val client = MinecraftClient.getInstance()
        val hasResource = client.resourceManager.getResource(identifier).isPresent
        val hasTexture = client.textureManager.getTexture(identifier) != null
        if (!hasResource && !hasTexture) {
            println("[Hypnosia] drawVanillaIcon MISSING: ${identifier}")
            return
        }
        val snappedX = round(x).toInt()
        val snappedY = round(y).toInt()
        val snappedW = max(1, round(width).toInt())
        val snappedH = max(1, round(height).toInt())
        context.drawTexture(
            RenderPipelines.GUI_TEXTURED,
            identifier,
            snappedX,
            snappedY,
            0.0f,
            0.0f,
            snappedW,
            snappedH,
            snappedW,
            snappedH,
            tintColor,
        )
    }

    fun drawIconTexture(
        context: DrawContext,
        identifier: Identifier,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        tintColor: Int = 0xFFFFFFFF.toInt(),
        flushDeferredBeforeDraw: Boolean = true,
    ) {
        if (IconSettings.shouldSkip(identifier)) {
            println("[Hypnosia] drawIconTexture SKIP: ${identifier}")
            return
        }
        val client = MinecraftClient.getInstance()
        if (client.resourceManager.getResource(identifier).isEmpty) {
            // silently skip missing icons to avoid log spam
            return
        }
        val themedTint = ThemeSettings.resolveIconTint(
            identifierPath = identifier.path,
            color = IconSettings.tint(identifier, tintColor),
        )
        // Snap icon quads to whole screen pixels to avoid subpixel texture blur.
        val snappedX = round(x)
        val snappedY = round(y)
        val snappedW = round(width)
        val snappedH = round(height)

        drawTexture(
            context = context,
            identifier = identifier,
            x = snappedX,
            y = snappedY,
            width = snappedW,
            height = snappedH,
            radius = 0.0f,
            tintColor = themedTint,
            iconMaskMode = true,
            flushDeferredBeforeDraw = flushDeferredBeforeDraw,
        )
    }

    private fun drawTexture(
        context: DrawContext,
        identifier: Identifier,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        tintColor: Int,
        iconMaskMode: Boolean,
        flushDeferredBeforeDraw: Boolean,
    ) {
        // Figma-exported SVG icons must be converted to PNG assets before using
        // drawRoundedTexture; Minecraft's GUI texture pipeline does not upload SVG files.
        val client = MinecraftClient.getInstance()
        val hasResource = client.resourceManager.getResource(identifier).isPresent
        val hasTexture = client.textureManager.getTexture(identifier) != null
        if (!hasResource && !hasTexture) {
            drawFigmaBox(
                context = context,
                x = x,
                y = y,
                width = width,
                height = height,
                radius = radius,
                bgColor = 0xFF3A3A3A.toInt(),
                strokeColor = 0xFF5A5A5A.toInt(),
                strokeThickness = 1.0f,
                flushDeferredBeforeDraw = flushDeferredBeforeDraw,
            )
            return
        }

        val texture = client.textureManager.getTexture(identifier)
        val uniform = createTextureBoxUniformBuffer(width, height, radius, tintColor, iconMaskMode)
        drawUniformQuad(
            context = context,
            debugName = "Hypnosia SDF rounded texture",
            pipeline = HypnosiaShaders.SDF_ROUNDED_TEXTURE,
            uniformName = "HypnosiaTextureBox",
            uniformBuffer = uniform,
            x = x,
            y = y,
            width = width,
            height = height,
            textureView = texture.glTextureView,
            sampler = texture.sampler,
            flushDeferredBeforeDraw = flushDeferredBeforeDraw,
        )
    }

    fun drawRoundedTexture(
        context: DrawContext,
        identifier: Identifier,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        radius: Int,
        tintColor: Int = 0xFFFFFFFF.toInt(),
        flushDeferredBeforeDraw: Boolean = true,
    ) {
        drawRoundedTexture(
            context = context,
            identifier = identifier,
            x = x.toFloat(),
            y = y.toFloat(),
            width = width.toFloat(),
            height = height.toFloat(),
            radius = radius.toFloat(),
            tintColor = tintColor,
            flushDeferredBeforeDraw = flushDeferredBeforeDraw,
        )
    }

    private fun drawUniformQuad(
        context: DrawContext,
        debugName: String,
        pipeline: RenderPipeline,
        uniformName: String,
        uniformBuffer: GpuBuffer,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        textureView: GpuTextureView? = null,
        sampler: GpuSampler? = null,
        flushDeferredBeforeDraw: Boolean = true,
    ) {
        if (width <= 0.0f || height <= 0.0f) {
            uniformBuffer.close()
            return
        }

        val guiMatrix = createGuiMatrix(context)
        context.draw()

        val tessellator = Tessellator.getInstance()
        val buffer = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE)

        putVertex(buffer, guiMatrix, x, y, 0.0f, 0.0f)
        putVertex(buffer, guiMatrix, x, y + height, 0.0f, height)
        putVertex(buffer, guiMatrix, x + width, y + height, width, height)
        putVertex(buffer, guiMatrix, x + width, y, width, 0.0f)

        val builtBuffer = buffer.end()
        val vertexBuffer = createOwnedVertexBuffer("$debugName vertices", builtBuffer)

        try {
            renderImmediate(
                debugName = debugName,
                pipeline = pipeline,
                vertexBuffer = vertexBuffer,
                uniformName = uniformName,
                uniformBuffer = uniformBuffer,
                textureView = textureView,
                sampler = sampler,
            )
        } finally {
            uniformBuffer.close()
            vertexBuffer.close()
            builtBuffer.close()
            context.draw()
        }
    }

    private fun createOwnedVertexBuffer(label: String, builtBuffer: BuiltBuffer): GpuBuffer {
        return RenderSystem.getDevice().createBuffer(
            { label },
            GpuBuffer.USAGE_VERTEX or GpuBuffer.USAGE_COPY_DST,
            builtBuffer.buffer,
        )
    }

    private fun createGuiMatrix(context: DrawContext): Matrix4f {
        val pose = Matrix3x2f(context.matrices)
        return Matrix4f()
            .m00(pose.m00())
            .m01(pose.m01())
            .m10(pose.m10())
            .m11(pose.m11())
            .m30(pose.m20())
            .m31(pose.m21())
    }

    private fun putVertex(
        buffer: BufferBuilder,
        matrix: Matrix4f,
        x: Float,
        y: Float,
        u: Float,
        v: Float,
    ) {
        val vec = Vector4f(x, y, GUI_VERTEX_Z, 1.0f).mul(matrix)
        buffer.vertex(vec.x, vec.y, vec.z).texture(u, v)
    }

    private fun DrawContext.draw() {
        drawDeferredElements()
    }

    private fun renderImmediate(
        debugName: String,
        pipeline: RenderPipeline,
        vertexBuffer: GpuBuffer,
        uniformName: String,
        uniformBuffer: GpuBuffer,
        textureView: GpuTextureView? = null,
        sampler: GpuSampler? = null,
    ) {
        val client = MinecraftClient.getInstance()
        val framebuffer = client.framebuffer
        val window = client.window
        val scaledWidth = window.framebufferWidth.toFloat() / window.scaleFactor.toFloat().coerceAtLeast(1.0f)
        val scaledHeight = window.framebufferHeight.toFloat() / window.scaleFactor.toFloat().coerceAtLeast(1.0f)
        val modelView = createGuiModelView()
        val dynamicTransforms = RenderSystem.getDynamicUniforms().write(
            modelView,
            Vector4f(1.0f, 1.0f, 1.0f, 1.0f),
            Vector3f(0.0f, 0.0f, 0.0f),
            Matrix4f(),
        )
        val indexBuffer = RenderSystem.getSequentialBuffer(VertexFormat.DrawMode.QUADS)
        val gpuIndexBuffer = indexBuffer.getIndexBuffer(6)
        val indexType = indexBuffer.indexType

        RenderSystem.backupProjectionMatrix()
        RenderSystem.setProjectionMatrix(
            guiProjection.set(scaledWidth, scaledHeight),
            ProjectionType.ORTHOGRAPHIC,
        )
        try {
            RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                    { debugName },
                    framebuffer.getColorAttachmentView(),
                    OptionalInt.empty(),
                    if (framebuffer.useDepthAttachment) framebuffer.getDepthAttachmentView() else null,
                    OptionalDouble.empty(),
                ).use { pass ->
                    RenderSystem.bindDefaultUniforms(pass)
                    pass.setUniform("DynamicTransforms", dynamicTransforms)
                    pass.setUniform(uniformName, uniformBuffer)
                    HypnosiaScissor.current()?.let { scissor ->
                        pass.enableScissor(scissor.x, scissor.y, scissor.width, scissor.height)
                    }
                    if (textureView != null && sampler != null) {
                        pass.bindTexture("Sampler0", textureView, sampler)
                    }
                    pass.setPipeline(pipeline)
                    pass.setVertexBuffer(0, vertexBuffer)
                    pass.setIndexBuffer(gpuIndexBuffer, indexType)
                    pass.drawIndexed(0, 0, 6, 1)
                }
        } finally {
            RenderSystem.restoreProjectionMatrix()
        }
    }

    private fun createGuiModelView(): Matrix4f {
        return Matrix4f().setTranslation(0.0f, 0.0f, GUI_MODEL_VIEW_Z)
    }

    private fun createBoxUniformBuffer(
        width: Float,
        height: Float,
        radius: Float,
        strokeThickness: Float,
        bgColor: Int,
        strokeColor: Int,
    ): GpuBuffer {
        val bytes = ByteBuffer.allocateDirect(BOX_UNIFORM_BYTES).order(ByteOrder.nativeOrder())

        bytes.putFloat(width)
        bytes.putFloat(height)
        bytes.putFloat(radius)
        bytes.putFloat(strokeThickness)

        putRgba(bytes, bgColor)
        putRgba(bytes, strokeColor)

        bytes.flip()
        return RenderSystem.getDevice().createBuffer(
            { "Hypnosia box uniforms" },
            GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST,
            bytes,
        )
    }

    private fun createShadowBoxUniformBuffer(
        width: Float,
        height: Float,
        radius: Float,
        spread: Float,
        blur: Float,
        extent: Float,
        color: Int,
    ): GpuBuffer {
        val bytes = ByteBuffer.allocateDirect(SHADOW_BOX_UNIFORM_BYTES).order(ByteOrder.nativeOrder())
        bytes.putFloat(width)
        bytes.putFloat(height)
        bytes.putFloat(radius)
        bytes.putFloat(spread)
        bytes.putFloat(blur)
        bytes.putFloat(extent)
        bytes.putFloat(0.0f)
        bytes.putFloat(0.0f)
        putRgba(bytes, color)
        bytes.flip()
        return createUniformBuffer("Hypnosia shadow box uniforms", bytes)
    }

    private fun createGradientBoxUniformBuffer(
        width: Float,
        height: Float,
        radius: Float,
        strokeThickness: Float,
        directionX: Float,
        directionY: Float,
        startColor: Int,
        endColor: Int,
        strokeColor: Int,
    ): GpuBuffer {
        val bytes = ByteBuffer.allocateDirect(GRADIENT_BOX_UNIFORM_BYTES).order(ByteOrder.nativeOrder())
        bytes.putFloat(width)
        bytes.putFloat(height)
        bytes.putFloat(radius)
        bytes.putFloat(strokeThickness)
        bytes.putFloat(directionX)
        bytes.putFloat(directionY)
        bytes.putFloat(0.0f)
        bytes.putFloat(0.0f)
        putRgba(bytes, startColor)
        putRgba(bytes, endColor)
        putRgba(bytes, strokeColor)
        bytes.flip()
        return createUniformBuffer("Hypnosia gradient box uniforms", bytes)
    }

    private fun createGlassBoxUniformBuffer(
        width: Float,
        height: Float,
        radius: Float,
        strokeThickness: Float,
        bgColor: Int,
        strokeColor: Int,
        strength: Float,
        distortion: Float,
        blurRadius: Float,
    ): GpuBuffer {
        val bytes = ByteBuffer.allocateDirect(GLASS_BOX_UNIFORM_BYTES).order(ByteOrder.nativeOrder())
        bytes.putFloat(width)
        bytes.putFloat(height)
        bytes.putFloat(radius)
        bytes.putFloat(strokeThickness)
        putRgba(bytes, bgColor)
        putRgba(bytes, strokeColor)
        putRgba(bytes, 0xEFFFFFFF.toInt())
        bytes.putFloat((System.nanoTime() % 100_000_000_000L).toFloat() / 1_000_000_000.0f)
        bytes.putFloat(strength.coerceIn(0.0f, 1.0f))
        bytes.putFloat(distortion.coerceIn(0.0f, 1.0f))
        bytes.putFloat(blurRadius.coerceIn(4.0f, 48.0f))
        bytes.flip()
        return createUniformBuffer("Hypnosia glass box uniforms", bytes)
    }

    private fun createHsvCanvasUniformBuffer(width: Float, height: Float, radius: Float, hueDegrees: Float): GpuBuffer {
        val bytes = ByteBuffer.allocateDirect(HSV_CANVAS_UNIFORM_BYTES).order(ByteOrder.nativeOrder())
        bytes.putFloat(width)
        bytes.putFloat(height)
        bytes.putFloat(radius.coerceIn(0.0f, min(width, height) * 0.5f))
        bytes.putFloat(((hueDegrees % 360.0f) + 360.0f) % 360.0f)
        bytes.flip()
        return createUniformBuffer("Hypnosia HSV canvas uniforms", bytes)
    }

    private fun createHueStripUniformBuffer(width: Float, height: Float, radius: Float): GpuBuffer {
        val bytes = ByteBuffer.allocateDirect(HUE_STRIP_UNIFORM_BYTES).order(ByteOrder.nativeOrder())
        bytes.putFloat(width)
        bytes.putFloat(height)
        bytes.putFloat(radius.coerceIn(0.0f, min(width, height) * 0.5f))
        bytes.putFloat(0.0f)
        bytes.flip()
        return createUniformBuffer("Hypnosia hue strip uniforms", bytes)
    }

    private fun createAlphaStripUniformBuffer(
        width: Float,
        height: Float,
        radius: Float,
        checkerSize: Float,
        color: Int,
    ): GpuBuffer {
        val bytes = ByteBuffer.allocateDirect(ALPHA_STRIP_UNIFORM_BYTES).order(ByteOrder.nativeOrder())
        bytes.putFloat(width)
        bytes.putFloat(height)
        bytes.putFloat(radius.coerceIn(0.0f, min(width, height) * 0.5f))
        bytes.putFloat(checkerSize.coerceAtLeast(1.0f))
        putRgba(bytes, color)
        bytes.flip()
        return createUniformBuffer("Hypnosia alpha strip uniforms", bytes)
    }

    private fun createTextureBoxUniformBuffer(
        width: Float,
        height: Float,
        radius: Float,
        tintColor: Int,
        iconMaskMode: Boolean,
    ): GpuBuffer {
        val bytes = ByteBuffer.allocateDirect(TEXTURE_BOX_UNIFORM_BYTES).order(ByteOrder.nativeOrder())
        bytes.putFloat(width)
        bytes.putFloat(height)
        bytes.putFloat(radius.coerceIn(0.0f, min(width, height) * 0.5f))
        bytes.putFloat(if (iconMaskMode) 1.0f else 0.0f)
        putRgba(bytes, tintColor)
        bytes.flip()
        return createUniformBuffer("Hypnosia texture box uniforms", bytes)
    }

    private fun createUniformBuffer(label: String, bytes: ByteBuffer): GpuBuffer {
        return RenderSystem.getDevice().createBuffer(
            { label },
            GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST,
            bytes,
        )
    }

    private fun shouldUseOpaqueBoxPipeline(
        width: Float,
        height: Float,
        radius: Float,
        bgColor: Int,
        strokeColor: Int,
        strokeThickness: Float,
    ): Boolean {
        val fillOpaque = ((bgColor ushr 24) and 0xFF) == 0xFF
        if (!fillOpaque) {
            return false
        }
        if (radius > 0.001f) {
            return false
        }

        val minSize = min(width, height)
        val isTinyShape = minSize <= 18.0f
        val isCircleLike = radius >= minSize * 0.45f
        if (isTinyShape || isCircleLike) {
            return false
        }

        return strokeThickness <= 0.001f || ((strokeColor ushr 24) and 0xFF) == 0xFF
    }

    private fun putRgba(bytes: ByteBuffer, argb: Int) {
        bytes.putFloat(((argb ushr 16) and 0xFF) / 255.0f)
        bytes.putFloat(((argb ushr 8) and 0xFF) / 255.0f)
        bytes.putFloat((argb and 0xFF) / 255.0f)
        bytes.putFloat(((argb ushr 24) and 0xFF) / 255.0f)
    }
}
