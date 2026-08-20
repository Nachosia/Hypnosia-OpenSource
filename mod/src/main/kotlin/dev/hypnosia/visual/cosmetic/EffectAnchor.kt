package dev.hypnosia.visual.cosmetic

import net.minecraft.client.render.entity.model.PlayerEntityModel
import net.minecraft.client.util.math.MatrixStack

enum class EffectAnchor {
    HEAD,
    BODY,
    BACK,
    HAND_LEFT,
    HAND_RIGHT;

    fun apply(matrices: MatrixStack, model: PlayerEntityModel) {
        when (this) {
            HEAD -> model.head.applyTransform(matrices)
            BODY -> model.body.applyTransform(matrices)
            BACK -> {
                model.body.applyTransform(matrices)
                matrices.translate(0.0, 0.0, -0.15)
            }
            HAND_LEFT -> model.leftArm.applyTransform(matrices)
            HAND_RIGHT -> model.rightArm.applyTransform(matrices)
        }
    }
}
