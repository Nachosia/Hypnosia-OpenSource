package dev.hypnosia.mixin;

import dev.hypnosia.other.StreamerModeSettings;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    @Inject(method = "updateRenderState", at = @At("TAIL"))
    private void hypnosia$replaceDisplayName(Entity entity, EntityRenderState state, float tickDelta, CallbackInfo ci) {
        Text displayName = state.displayName;
        if (displayName != null) {
            state.displayName = StreamerModeSettings.INSTANCE.decoratePlayerName(entity.getName().getString(), displayName);
        }
    }
}
