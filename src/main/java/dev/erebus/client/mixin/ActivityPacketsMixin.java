package dev.erebus.client.mixin;

import dev.erebus.client.util.ActivityEvents;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Passes level events and block events on to {@link ActivityEvents}. On the handler's way out, so it runs once, on the
 * game thread (on the network thread the handler hands the packet over by throwing instead).
 */
@Mixin(ClientPacketListener.class)
public abstract class ActivityPacketsMixin {
    @Inject(method = "handleLevelEvent", at = @At("RETURN"))
    private void erebus$levelEvent(ClientboundLevelEventPacket packet, CallbackInfo ci) {
        ActivityEvents.level(packet.getType(), packet.getPos().immutable(), packet.getData(), packet.isGlobalEvent());
    }

    @Inject(method = "handleBlockEvent", at = @At("RETURN"))
    private void erebus$blockEvent(ClientboundBlockEventPacket packet, CallbackInfo ci) {
        ActivityEvents.block(packet.getPos().immutable(), packet.getBlock(), packet.getB0(), packet.getB1());
    }
}
