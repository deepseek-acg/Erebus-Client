package dev.erebus.client.mixin;

import dev.erebus.client.module.render.Freecam;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam with Freeze player: the movement keys fly the camera, so the player gets no movement input at all. */
@Mixin(KeyboardInput.class)
public abstract class FreecamInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void erebus$freecamInput(CallbackInfo ci) {
        Freecam fc = Freecam.active();
        if (fc == null || !fc.freezesPlayer()) return;
        this.keyPresses = Input.EMPTY;
        this.moveVector = Vec2.ZERO;
    }
}
