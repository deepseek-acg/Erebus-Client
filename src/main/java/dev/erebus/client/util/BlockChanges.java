package dev.erebus.client.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Blocks the server says changed (block update packets), as they arrive: what was there before and what is now. Kept
 * free of module classes so the mixin that feeds it can't load one early.
 */
public final class BlockChanges {
    public interface Listener {
        void changed(BlockPos pos, BlockState before, BlockState after);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private BlockChanges() {
    }

    public static void listen(Listener l) {
        LISTENERS.add(l);
    }

    public static void fire(BlockPos pos, BlockState before, BlockState after) {
        if (before == after) return;
        for (Listener l : LISTENERS) l.changed(pos, before, after);
    }
}
