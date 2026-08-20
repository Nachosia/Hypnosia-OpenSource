package dev.hypnosia.mixin;

import dev.hypnosia.ui.HypnosiaHomeV2Screen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void hypnosia$hideCrosshairOnV2(CallbackInfo ci) {
        if (MinecraftClient.getInstance().currentScreen instanceof HypnosiaHomeV2Screen) {
            ci.cancel();
        }
    }
}
