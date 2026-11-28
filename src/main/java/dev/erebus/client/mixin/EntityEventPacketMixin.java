package dev.erebus.client.mixin;

import dev.erebus.client.util.Pops;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notices totem pops (entity event 35, which the client handles right here rather than passing to the entity) and
 * deaths (event 3). On the handler's way out, so it only runs once, on the game thread (on the network thread the handler hands the
 * packet over by throwing instead).
 */
@Mixin(ClientPacketListener.class)
public abstract class EntityEventPacketMixin {
    @Inject(method = "handleEntityEvent", at = @At("RETURN"))
    private void erebus$entityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        ClientPacketListener self = (ClientPacketListener) (Object) this;
        if (self.getLevel() == null) return;
        Entity e = packet.getEntity(self.getLevel());
        if (!(e instanceof LivingEntity le)) return;
        if (packet.getEventId() == 35) Pops.popped(le);
        else if (packet.getEventId() == 3 && e instanceof Player p) Pops.reset(p);
    }
}
