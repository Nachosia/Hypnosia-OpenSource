package dev.hypnosia.visual.cosmetic

import dev.hypnosia.other.FriendsManager
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.feature.FeatureRenderer
import net.minecraft.client.render.entity.feature.FeatureRendererContext
import net.minecraft.client.render.entity.model.PlayerEntityModel
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.client.util.math.MatrixStack

class CosmeticFeatureRenderer(
    context: FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel>
) : FeatureRenderer<PlayerEntityRenderState, PlayerEntityModel>(context) {

    override fun render(
        matrices: MatrixStack,
        queue: OrderedRenderCommandQueue,
        light: Int,
        state: PlayerEntityRenderState,
        limbAngle: Float,
        limbDistance: Float
    ) {
        if (!CosmeticRenderModule.enabled) return

        val client = MinecraftClient.getInstance()
        val localPlayer = client.player ?: return

        val isSelf = state.id == localPlayer.id
        if (!isSelf) {
            val playerName = state.playerName?.string ?: return
            if (!FriendsManager.isFriend(playerName)) return
        }

        for (effect in CosmeticRenderModule.effects) {
            if (!effect.enabled) continue

            matrices.push()
            contextModel.getRootPart().applyTransform(matrices)
            effect.anchor.apply(matrices, contextModel)
            effect.render(matrices, queue, light, state, contextModel, limbAngle, limbDistance)
            matrices.pop()
        }
    }
}
