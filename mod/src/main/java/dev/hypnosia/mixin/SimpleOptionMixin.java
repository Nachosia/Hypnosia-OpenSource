package dev.hypnosia.mixin;

import dev.hypnosia.world.WorldVisualSettings;
import net.minecraft.client.option.SimpleOption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SimpleOption.class)
public abstract class SimpleOptionMixin<T> {
    @Inject(method = "getValue", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings("unchecked")
    private void hypnosia$overrideGammaForFullbright(CallbackInfoReturnable<T> cir) {
        if (WorldVisualSettings.INSTANCE.shouldOverrideGamma(this)) {
            cir.setReturnValue((T) (Object) WorldVisualSettings.INSTANCE.fullbrightGamma());
        }
    }
}
