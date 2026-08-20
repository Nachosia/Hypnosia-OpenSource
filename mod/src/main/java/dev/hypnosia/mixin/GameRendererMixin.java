package dev.hypnosia.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.hypnosia.visual.AspectRatioSettings;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @ModifyExpressionValue(
        method = "getBasicProjectionMatrix(F)Lorg/joml/Matrix4f;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/Window;getFramebufferWidth()I")
    )
    private int hypnosia$aspectWidth(int original) {
        if (!AspectRatioSettings.INSTANCE.isEnabled()) {
            return original;
        }
        int height = net.minecraft.client.MinecraftClient.getInstance().getWindow().getFramebufferHeight();
        return Math.max(1, Math.round(height * AspectRatioSettings.INSTANCE.aspect()));
    }
}
