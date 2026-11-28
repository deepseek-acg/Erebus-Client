package dev.erebus.client.mixin;

import dev.erebus.client.util.BlockChanges;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reports blocks the server changes (single and section block updates) to {@link BlockChanges}. */
@Mixin(ClientLevel.class)
public abstract class BlockUpdateMixin {
    @Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
    private void erebus$blockChanged(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        BlockState before = ((ClientLevel) (Object) this).getBlockState(pos);
        BlockChanges.fire(pos.immutable(), before, state);
    }
}
