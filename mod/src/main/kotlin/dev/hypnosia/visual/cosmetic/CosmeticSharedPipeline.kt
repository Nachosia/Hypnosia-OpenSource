package dev.hypnosia.visual.cosmetic

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderSetup
import net.minecraft.client.render.VertexFormats
import net.minecraft.util.Identifier

object CosmeticSharedPipeline {
    val PIPELINE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_PROJECTION_FOG_LIGHTING_SNIPPET)
            .withLocation(Identifier.of("hypnosia", "pipeline/cosmetic_shared"))
            .withVertexShader("core/entity")
            .withFragmentShader("core/entity")
            .withShaderDefine("EMISSIVE")
            .withShaderDefine("NO_OVERLAY")
            .withShaderDefine("NO_CARDINAL_LIGHTING")
            .withSampler("Sampler0")
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthWrite(false)
            .withCull(false)
            .withVertexFormat(
                VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL,
                VertexFormat.DrawMode.QUADS
            )
            .build()
    )

    val RENDER_LAYER: RenderLayer = run {
        val setup = RenderSetup.builder(PIPELINE)
            .texture("Sampler0", Identifier.of("hypnosia", "textures/white.png"))
            .build()
        RenderLayer.of("hypnosia_cosmetic_shared", setup)
    }
}
