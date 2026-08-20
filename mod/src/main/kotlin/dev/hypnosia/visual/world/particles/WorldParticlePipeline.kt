package dev.hypnosia.visual.world.particles

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.DepthTestFunction
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderSetup
import net.minecraft.client.render.VertexFormats
import net.minecraft.util.Identifier

/**
 * Рендер-пайплайн для частиц мира.
 *
 * Построен по тому же проверенному паттерну, что и косметика
 * ([dev.hypnosia.visual.cosmetic.CosmeticSharedPipeline]): кастомный [RenderLayer]
 * собирается из [RenderSetup] с текстурой-сэмплером. Никакого BLOCK_ATLAS.
 *
 * Blend = ADDITIVE: чёрный фон PNG ничего не добавляет (исчезает), а светящаяся
 * картинка проходит как glow. Каждая текстура получает свой [RenderLayer],
 * результаты кешируются.
 */
object WorldParticlePipeline {
    val ADDITIVE_PARTICLE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_PROJECTION_FOG_LIGHTING_SNIPPET)
            .withLocation(Identifier.of("hypnosia", "pipeline/additive_particle"))
            .withVertexShader("core/entity")
            .withFragmentShader("core/entity")
            .withShaderDefine("EMISSIVE")
            .withShaderDefine("NO_OVERLAY")
            .withShaderDefine("NO_CARDINAL_LIGHTING")
            .withSampler("Sampler0")
            .withBlend(BlendFunction.ADDITIVE)
            .withDepthWrite(false)
            .withCull(false)
            .withVertexFormat(
                VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL,
                VertexFormat.DrawMode.QUADS
            )
            .build()
    )

    val ADDITIVE_NO_DEPTH: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_PROJECTION_FOG_LIGHTING_SNIPPET)
            .withLocation(Identifier.of("hypnosia", "pipeline/additive_no_depth"))
            .withVertexShader("core/entity")
            .withFragmentShader("core/entity")
            .withShaderDefine("EMISSIVE")
            .withShaderDefine("NO_OVERLAY")
            .withShaderDefine("NO_CARDINAL_LIGHTING")
            .withSampler("Sampler0")
            .withBlend(BlendFunction.ADDITIVE)
            .withDepthWrite(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withVertexFormat(
                VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL,
                VertexFormat.DrawMode.QUADS
            )
            .build()
    )

    private val layerCache = HashMap<Identifier, RenderLayer>()
    private val noDepthLayerCache = HashMap<Identifier, RenderLayer>()

    /** Возвращает (с кешированием) [RenderLayer] для конкретной PNG-текстуры. */
    fun layerFor(textureId: Identifier): RenderLayer = layerCache.getOrPut(textureId) {
        val setup = RenderSetup.builder(ADDITIVE_PARTICLE)
            .texture("Sampler0", textureId)
            .build()
        RenderLayer.of("hypnosia_world_particle_${textureId.path.replace('/', '_').replace('.', '_')}", setup)
    }

    /** Без depth test — рисует поверх всей геометрии. */
    fun layerNoDepth(textureId: Identifier): RenderLayer = noDepthLayerCache.getOrPut(textureId) {
        val setup = RenderSetup.builder(ADDITIVE_NO_DEPTH)
            .texture("Sampler0", textureId)
            .build()
        RenderLayer.of("hypnosia_no_depth_${textureId.path.replace('/', '_').replace('.', '_')}", setup)
    }
}
