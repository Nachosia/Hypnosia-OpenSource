package dev.hypnosia.mixin;

import dev.hypnosia.world.WorldVisualSettings;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.world.ClientWorld;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    @ModifyArgs(
        method = "applyFog(Lnet/minecraft/client/render/Camera;ILnet/minecraft/client/render/RenderTickCounter;FLnet/minecraft/client/world/ClientWorld;)Lorg/joml/Vector4f;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/fog/FogRenderer;applyFog(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"
        )
    )
    private void hypnosia$applyCustomWorldFog(
        Args args,
        Camera camera,
        int viewDistance,
        RenderTickCounter tickCounter,
        float skyDarkness,
        ClientWorld world
    ) {
        if (!WorldVisualSettings.INSTANCE.customFogEnabled()) {
            return;
        }

        // Keep vanilla fog untouched for water, lava, and other submersion effects.
        if (camera.getSubmersionType() != CameraSubmersionType.NONE) {
            return;
        }

        Vector4f color = args.get(2);
        float colorBlend = WorldVisualSettings.INSTANCE.fogColorBlend();
        color.x += (WorldVisualSettings.INSTANCE.fogRed() - color.x) * colorBlend;
        color.y += (WorldVisualSettings.INSTANCE.fogGreen() - color.y) * colorBlend;
        color.z += (WorldVisualSettings.INSTANCE.fogBlue() - color.z) * colorBlend;

        float end = WorldVisualSettings.INSTANCE.fogDistance();
        float strength = WorldVisualSettings.INSTANCE.fogStrength();
        float softness = WorldVisualSettings.INSTANCE.fogSoftness();
        float band = Math.max(4.0F, end * (0.12F + 0.78F * softness) * strength);
        float renderStart = Math.max(0.0F, end - band);
        float environmentalStart = Math.max(0.0F, renderStart - band * softness * 0.45F);
        args.set(2, color);
        args.set(3, environmentalStart);
        args.set(4, end);
        args.set(5, renderStart);
        args.set(6, end);
        args.set(7, end);
        args.set(8, end);
    }
}
