package dev.hypnosia.visual.cosmetic

import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.model.PlayerEntityModel
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.client.util.math.MatrixStack

interface CosmeticEffect {
    val id: String
    val anchor: EffectAnchor
    var enabled: Boolean

    fun render(
        matrices: MatrixStack,
        queue: OrderedRenderCommandQueue,
        light: Int,
        state: PlayerEntityRenderState,
        model: PlayerEntityModel,
        limbAngle: Float,
        limbDistance: Float
    )
}
