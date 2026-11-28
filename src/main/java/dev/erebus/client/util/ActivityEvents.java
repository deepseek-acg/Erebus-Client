package dev.erebus.client.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Level events (a dispenser firing, a block broken, a wither spawning...) and block events (a piston moving, a chest
 * opening...) as the server sends them. Kept free of module classes so the mixin feeding it can't load one early.
 */
public final class ActivityEvents {
    public interface LevelListener {
        void levelEvent(int type, BlockPos pos, int data, boolean global);
    }

    public interface BlockListener {
        void blockEvent(BlockPos pos, Block block, int b0, int b1);
    }

    private static final List<LevelListener> LEVEL = new CopyOnWriteArrayList<>();
    private static final List<BlockListener> BLOCK = new CopyOnWriteArrayList<>();

    private ActivityEvents() {
    }

    public static void onLevel(LevelListener l) {
        LEVEL.add(l);
    }

    public static void onBlock(BlockListener l) {
        BLOCK.add(l);
    }

    public static void level(int type, BlockPos pos, int data, boolean global) {
        for (LevelListener l : LEVEL) l.levelEvent(type, pos, data, global);
    }

    public static void block(BlockPos pos, Block block, int b0, int b1) {
        for (BlockListener l : BLOCK) l.blockEvent(pos, block, b0, b1);
    }
}
