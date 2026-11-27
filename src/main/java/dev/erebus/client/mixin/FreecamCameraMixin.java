package dev.erebus.client.mixin;

import dev.erebus.client.module.render.Freecam;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam: the camera goes where Freecam says, and counts as detached so your own player is drawn. */
@Mixin(Camera.class)
public abstract class FreecamCameraMixin {
    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(Vec3 pos);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "setup", at = @At("TAIL"))
    private void erebus$freecam(Level level, Entity entity, boolean detached, boolean mirror, float partialTick, CallbackInfo ci) {
        Freecam fc = Freecam.active();
        if (fc == null) return;
        setPosition(fc.cameraPos(partialTick));
        setRotation(fc.yaw(), fc.pitch());
        this.detached = true;
    }
}
