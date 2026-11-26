package dev.erebus.client.mixin;

import dev.erebus.client.module.render.Freecam;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam with Hide hand: no first-person hand floating in front of the free camera. */
@Mixin(GameRenderer.class)
public abstract class FreecamHandMixin {
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void erebus$hideHand(float partialTick, boolean sleeping, Matrix4f projection, CallbackInfo ci) {
        Freecam fc = Freecam.active();
        if (fc != null && fc.hidesHand()) ci.cancel();
    }
}
