package dev.erebus.client.mixin;

import dev.erebus.client.input.MouseSim;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds {@link MouseSim}'s synthetic mouse counts (and Aim Assist's pull) into the game's own mouse handling, once per frame. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Shadow
    private double accumulatedDX;
    @Shadow
    private double accumulatedDY;

    @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
    private void erebus$syntheticMouse(CallbackInfo ci) {
        MouseSim.noteUserMovement(accumulatedDX, accumulatedDY);
        if (!MouseSim.active()) { // nothing is turning the camera: Aim Assist may add its pull to the player's own movement
            double[] a = dev.erebus.client.module.combat.AimAssist.frameDelta(accumulatedDX, accumulatedDY);
            if (a != null) {
                accumulatedDX += a[0];
                accumulatedDY += a[1];
            }
            return;
        }
        if (MouseSim.returning() && (accumulatedDX != 0 || accumulatedDY != 0)) { // the player took the mouse back
            MouseSim.stop();
            return;
        }
        double[] d = MouseSim.frameDelta();
        if (d == null) return;
        if (MouseSim.holdingCamera()) { // the player's own movement would fight the turn
            accumulatedDX = 0;
            accumulatedDY = 0;
        }
        accumulatedDX += d[0];
        accumulatedDY += d[1];
    }
}
