package dev.erebus.client.mixin;

import dev.erebus.client.module.render.Freecam;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Freecam with Block clicks: attack and use clicks do nothing while the camera is out. */
@Mixin(Minecraft.class)
public abstract class FreecamClickMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void erebus$noAttack(CallbackInfoReturnable<Boolean> cir) {
        Freecam fc = Freecam.active();
        if (fc != null && fc.blocksClicks()) cir.setReturnValue(false);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void erebus$noMining(boolean held, CallbackInfo ci) {
        Freecam fc = Freecam.active();
        if (fc != null && fc.blocksClicks()) ci.cancel();
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void erebus$noUse(CallbackInfo ci) {
        Freecam fc = Freecam.active();
        if (fc != null && fc.blocksClicks()) ci.cancel();
    }
}
