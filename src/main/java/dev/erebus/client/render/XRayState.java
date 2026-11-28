package dev.erebus.client.render;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * What X-Ray hides, read by the block-state and fluid mixins from chunk-building threads. Kept apart from the module:
 * block states are asked about their shape while the game is still starting up, and touching a module class then
 * would load it before the game is there.
 */
public final class XRayState {
    public static volatile boolean enabled;
    public static volatile Set<Block> shown = Set.of();
    public static volatile boolean hideFluids;
    public static volatile Thread serverThread;

    private XRayState() {
    }

    /** True if this block is hidden (and it isn't the singleplayer server asking, which must see the real world). */
    public static boolean hides(BlockState state) {
        if (Thread.currentThread() == serverThread) return false;
        return !shown.contains(state.getBlock());
    }

    /** True if fluids shouldn't be drawn. */
    public static boolean hidesFluids() {
        return enabled && hideFluids && Thread.currentThread() != serverThread;
    }
}
