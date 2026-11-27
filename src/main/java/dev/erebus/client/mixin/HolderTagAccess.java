package dev.erebus.client.mixin;

import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Collection;

/**
 * Gives biomes in the game's built-in registry lookup their tags, which it comes without (they normally come from a
 * data pack). Used by the structure predictor, which reads them from the vanilla tag files in the game jar.
 */
public final class HolderTagAccess {
    private HolderTagAccess() {
    }

    @Mixin(Holder.Reference.class)
    public interface Reference {
        @Invoker("bindTags")
        void erebus$bindTags(Collection<?> tags);
    }
}
