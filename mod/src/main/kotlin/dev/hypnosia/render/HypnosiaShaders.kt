package dev.hypnosia.render

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.DepthTestFunction
import com.mojang.blaze3d.vertex.VertexFormat
import dev.hypnosia.HypnosiaClient
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.gl.UniformType
import net.minecraft.client.render.VertexFormats
import net.minecraft.util.Identifier

object HypnosiaShaders {
    val SDF_ROUNDED_RECT_OPAQUE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
            .withLocation(Identifier.of(HypnosiaClient.MOD_ID, "pipeline/sdf_rounded_rect_opaque"))
            .withVertexShader(Identifier.of(HypnosiaClient.MOD_ID, "core/sdf_rounded_rect"))
            .withFragmentShader(Identifier.of(HypnosiaClient.MOD_ID, "core/sdf_rounded_rect"))
            .withUniform("HypnosiaBox", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(VertexFormats.POSITION_TEXTURE, VertexFormat.DrawMode.QUADS)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build(),
    )

    val SDF_ROUNDED_RECT: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
            .withLocation(Identifier.of(HypnosiaClient.MOD_ID, "pipeline/sdf_rounded_rect"))
            .withVertexShader(Identifier.of(HypnosiaClient.MOD_ID, "core/sdf_rounded_rect"))
            .withFragmentShader(Identifier.of(HypnosiaClient.MOD_ID, "core/sdf_rounded_rect"))
            .withUniform("HypnosiaBox", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(VertexFormats.POSITION_TEXTURE, VertexFormat.DrawMode.QUADS)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build(),
    )

    val SDF_DROP_SHADOW: RenderPipeline = registerUiPipeline(
        location = "pipeline/sdf_drop_shadow",
        shader = "core/sdf_drop_shadow",
        uniform = "HypnosiaShadowBox",
    )

    val SDF_LINEAR_GRADIENT_BOX: RenderPipeline = registerUiPipeline(
        location = "pipeline/sdf_linear_gradient_box",
        shader = "core/sdf_linear_gradient_box",
        uniform = "HypnosiaGradientBox",
    )

    val SDF_LIQUID_GLASS_BOX: RenderPipeline = registerUiPipeline(
        location = "pipeline/sdf_liquid_glass_box",
        shader = "core/sdf_liquid_glass_box",
        uniform = "HypnosiaGlassBox",
        sampler = "Sampler0",
    )

    val HSV_COLOR_CANVAS: RenderPipeline = registerUiPipeline(
        location = "pipeline/hsv_color_canvas",
        shader = "core/hsv_color_canvas",
        uniform = "HypnosiaHsvCanvas",
    )

    val HSV_HUE_STRIP: RenderPipeline = registerUiPipeline(
        location = "pipeline/hsv_hue_strip",
        shader = "core/hsv_hue_strip",
        uniform = "HypnosiaHueStrip",
    )

    val HSV_ALPHA_STRIP: RenderPipeline = registerUiPipeline(
        location = "pipeline/hsv_alpha_strip",
        shader = "core/hsv_alpha_strip",
        uniform = "HypnosiaAlphaStrip",
    )

    val SDF_ROUNDED_TEXTURE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
            .withLocation(Identifier.of(HypnosiaClient.MOD_ID, "pipeline/sdf_rounded_texture"))
            .withVertexShader(Identifier.of(HypnosiaClient.MOD_ID, "core/sdf_rounded_texture"))
            .withFragmentShader(Identifier.of(HypnosiaClient.MOD_ID, "core/sdf_rounded_texture"))
            .withSampler("Sampler0")
            .withUniform("HypnosiaTextureBox", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(VertexFormats.POSITION_TEXTURE, VertexFormat.DrawMode.QUADS)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build(),
    )

    val HQ_TEXT: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
            .withLocation(Identifier.of(HypnosiaClient.MOD_ID, "pipeline/hq_text"))
            .withVertexShader(Identifier.of(HypnosiaClient.MOD_ID, "core/hq_text"))
            .withFragmentShader(Identifier.of(HypnosiaClient.MOD_ID, "core/hq_text"))
            .withSampler("Sampler0")
            .withUniform("HypnosiaTextFade", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build(),
    )

    val HQ_TEXT_GRADIENT: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
            .withLocation(Identifier.of(HypnosiaClient.MOD_ID, "pipeline/hq_text_gradient"))
            .withVertexShader(Identifier.of(HypnosiaClient.MOD_ID, "core/hq_text"))
            .withFragmentShader(Identifier.of(HypnosiaClient.MOD_ID, "core/hq_text_gradient"))
            .withSampler("Sampler0")
            .withUniform("HypnosiaTextFade", UniformType.UNIFORM_BUFFER)
            .withUniform("HypnosiaGradient", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build(),
    )

    fun initialize() {
        // Force object initialization during the Fabric client entrypoint.
        // Fabric 1.21.11's local rendering API exposes RenderPipeline registration;
        // the legacy CoreShaderRegistrationCallback is not present in this workspace.
    }

    private fun registerUiPipeline(
        location: String,
        shader: String,
        uniform: String,
        sampler: String? = null,
    ): RenderPipeline {
        val builder = RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
                .withLocation(Identifier.of(HypnosiaClient.MOD_ID, location))
                .withVertexShader(Identifier.of(HypnosiaClient.MOD_ID, shader))
                .withFragmentShader(Identifier.of(HypnosiaClient.MOD_ID, shader))
                .withUniform(uniform, UniformType.UNIFORM_BUFFER)
                .withVertexFormat(VertexFormats.POSITION_TEXTURE, VertexFormat.DrawMode.QUADS)
                .withBlend(BlendFunction.TRANSLUCENT)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .withCull(false)
        if (sampler != null) {
            builder.withSampler(sampler)
        }
        return RenderPipelines.register(builder.build())
    }
}
