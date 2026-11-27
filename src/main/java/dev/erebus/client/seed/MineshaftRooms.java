package dev.erebus.client.seed;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Finds mineshaft start rooms. The game starts every mineshaft with a room whose corner is exactly 2 blocks into its
 * start chunk on x and z, 8 to 13 blocks wide each way (so it never leaves the chunk), and clears a band of cave air
 * 3 blocks high over the whole of it, above a floor it leaves as it was, with a dome on top and corridors leading
 * off its walls. That's been the case since 1.12.
 *
 * <p>A room counts when, at some height, there's an 8x8 band of air three high starting exactly at that corner,
 * standing on a mostly solid floor, with mostly solid walls just outside its two near sides (so it really starts
 * there and isn't part of a bigger open space), and mineshaft woodwork, rails or cobwebs around it. A cave
 * would have to be a flat-floored rectangle lined up with a chunk corner, next to a mineshaft, to pass.
 */
public final class MineshaftRooms {
    private MineshaftRooms() {
    }

    /** Whether this chunk has mineshaft corridor blocks, so the start chunks around it are worth checking. */
    public static boolean worthChecking(LevelChunk chunk) {
        for (LevelChunkSection s : chunk.getSections()) {
            if (s == null || s.hasOnlyAir()) continue;
            if (s.maybeHas(b -> b.is(Blocks.RAIL) || b.is(Blocks.COBWEB))) return true;
            if (s.maybeHas(b -> b.is(Blocks.OAK_FENCE)) && s.maybeHas(b -> b.is(Blocks.OAK_PLANKS))) return true;
            if (s.maybeHas(b -> b.is(Blocks.DARK_OAK_FENCE)) && s.maybeHas(b -> b.is(Blocks.DARK_OAK_PLANKS))) return true;
        }
        return false;
    }

    /** Where the start room in chunk (cx, cz) is (its corner, at floor height), or null. */
    public static BlockPos find(Level level, int cx, int cz) {
        if (!(level.getChunkSource() instanceof net.minecraft.client.multiplayer.ClientChunkCache cache)) return null;
        LevelChunk chunk = cache.getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null) return null;
        int top = chunk.getHighestFilledSectionIndex();
        if (top < 0) return null;
        int maxY = (chunk.getSectionYFromSectionIndex(top) << 4) + 15;
        int x0 = (cx << 4) + 2, z0 = (cz << 4) + 2;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int f = level.getMinY() + 1; f + 4 <= maxY; f++) {
            // quick look first: air in three corners of the band and a solid floor under its middle
            if (!air(chunk, p, x0, f + 1, z0) || !air(chunk, p, x0 + 7, f + 3, z0 + 7) || !air(chunk, p, x0 + 7, f + 2, z0)
                    || !air(chunk, p, x0, f + 2, z0 + 7) || !solid(chunk, p, x0 + 3, f, z0 + 4)) continue;
            if (room(level, chunk, p, x0, f, z0)) return new BlockPos(x0, f, z0);
        }
        return null;
    }

    private static boolean room(Level level, LevelChunk chunk, BlockPos.MutableBlockPos p, int x0, int f, int z0) {
        int air = 0, floor = 0;
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                for (int dy = 1; dy <= 3; dy++) if (air(chunk, p, x0 + dx, f + dy, z0 + dz)) air++;
                if (solid(chunk, p, x0 + dx, f, z0 + dz)) floor++;
            }
        }
        if (air < 184 || floor < 45) return false; // 96% of the band, 70% of the floor
        // the near walls (just outside the corner) are mostly there: the room starts here
        int west = 0, north = 0;
        for (int d = 0; d < 8; d++) {
            for (int dy = 1; dy <= 3; dy++) {
                if (!air(level, p, x0 - 1, f + dy, z0 + d)) west++;
                if (!air(level, p, x0 + d, f + dy, z0 - 1)) north++;
            }
        }
        if (west < 9 || north < 9) return false;
        // and it's part of a mineshaft: corridors leave the room anywhere up its walls (it's 5 to 10 high), and their
        // first wooden support can be a few blocks out
        int shaft = 0;
        for (int x = x0 - 16; x <= x0 + 29 && shaft < 4; x++) {
            for (int z = z0 - 16; z <= z0 + 29 && shaft < 4; z++) {
                for (int y = f - 4; y <= f + 14; y++) {
                    if (shaftBlock(level.getBlockState(p.set(x, y, z)).getBlock()) && ++shaft >= 4) break;
                }
            }
        }
        return shaft >= 4;
    }

    private static boolean shaftBlock(Block b) {
        return b == Blocks.OAK_PLANKS || b == Blocks.OAK_FENCE || b == Blocks.DARK_OAK_PLANKS || b == Blocks.DARK_OAK_FENCE
                || b == Blocks.RAIL || b == Blocks.COBWEB;
    }

    private static boolean air(LevelChunk chunk, BlockPos.MutableBlockPos p, int x, int y, int z) {
        return chunk.getBlockState(p.set(x, y, z)).isAir();
    }

    private static boolean air(Level level, BlockPos.MutableBlockPos p, int x, int y, int z) {
        return level.getBlockState(p.set(x, y, z)).isAir();
    }

    private static boolean solid(LevelChunk chunk, BlockPos.MutableBlockPos p, int x, int y, int z) {
        BlockState s = chunk.getBlockState(p.set(x, y, z));
        return !s.isAir() && s.getFluidState().isEmpty();
    }
}
