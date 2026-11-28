package dev.erebus.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The game's cooldown between uses while the use key is held. Held, the game uses the item again whenever it reaches
 * zero; XP Optimizer keeps it from doing that while it does the clicking itself.
 */
@Mixin(Minecraft.class)
public interface MinecraftAccess {
    @Accessor("rightClickDelay")
    int erebus$rightClickDelay();

    @Accessor("rightClickDelay")
    void erebus$setRightClickDelay(int ticks);
}
