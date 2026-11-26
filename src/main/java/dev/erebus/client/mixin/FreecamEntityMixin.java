package dev.erebus.client.mixin;

import dev.erebus.client.module.render.Freecam;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Freecam: the mouse turns the camera instead of the player, and entities near the player stay drawn. */
@Mixin(Entity.class)
public abstract class FreecamEntityMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void erebus$freecamTurn(double yRot, double xRot, CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().player) return;
        Freecam fc = Freecam.active();
        if (fc == null) return;
        fc.turn(yRot, xRot);
        ci.cancel();
    }

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void erebus$freecamRender(double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        Freecam fc = Freecam.active();
        if (fc != null && fc.keepsVisible((Entity) (Object) this)) cir.setReturnValue(true);
    }
}
