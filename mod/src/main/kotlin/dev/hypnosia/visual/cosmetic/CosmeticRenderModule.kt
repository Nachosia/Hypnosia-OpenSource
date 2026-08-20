package dev.hypnosia.visual.cosmetic

import dev.hypnosia.visual.cosmetic.effects.ChinaHatEffect
import dev.hypnosia.visual.cosmetic.effects.NimbusEffect
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback
import net.minecraft.client.render.entity.PlayerEntityRenderer
import net.minecraft.entity.EntityType

object CosmeticRenderModule {
    var enabled: Boolean = true
    private val _effects = mutableListOf<CosmeticEffect>(ChinaHatEffect(), NimbusEffect())
    val effects: List<CosmeticEffect> get() = _effects.toList()

    fun register() {
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register { type, renderer, helper, _ ->
            if (type == EntityType.PLAYER && renderer is PlayerEntityRenderer) {
                helper.register(CosmeticFeatureRenderer(renderer))
            }
        }
    }
}
