package dev.hypnosia.mixin;

import dev.hypnosia.other.FriendsManager;
import dev.hypnosia.other.StreamerModeSettings;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {
    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void hypnosia$decorateFriendName(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        Text decorated = FriendsManager.INSTANCE.decorateTabName(entry, cir.getReturnValue());
        cir.setReturnValue(StreamerModeSettings.INSTANCE.decoratePlayerName(entry.getProfile().name(), decorated));
    }
}
