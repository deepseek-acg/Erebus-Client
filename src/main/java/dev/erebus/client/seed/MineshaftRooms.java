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
 * there and isn't part of a bigger open space), open in the middle above the band where the dome is (which rules
 * out rooms players dig, with their flat ceilings), and mineshaft woodwork, rails or cobwebs around it. A cave
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

    /**
     * A start room: its corner at floor height, and its width along x and z (8 to 13, rolled from the seed right
     * after the chunk's mineshaft roll), or -1 where it couldn't be measured or the chunk predates 1.18 (which
     * rolled one more number first).
     */
    public record Room(BlockPos corner, int sizeX, int sizeZ) {
    }

    /** Where the start room in chunk (cx, cz) is (its corner, at floor height), or null. */
    public static BlockPos find(Level level, int cx, int cz) {
        Room r = findRoom(level, cx, cz);
        return r == null ? null : r.corner();
    }

    /** Like {@link #find}, with the room's size. */
    public static Room findRoom(Level level, int cx, int cz) {
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
            if (room(level, chunk, p, x0, f, z0)) {
                boolean old = oldChunk(chunk);
                return new Room(new BlockPos(x0, f, z0), old ? -1 : width(level, p, x0, f, z0, true), old ? -1 : width(level, p, x0, f, z0, false));
            }
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
        // the dome: the game clears a half-ellipsoid of air on top of the band, so the layer above it is open in the
        // middle (a room dug by a player nearly always has a flat ceiling there)
        int dome = 0;
        int[][] domeAt = {{4, 4}, {3, 3}, {2, 4}, {6, 4}, {4, 2}, {4, 6}};
        for (int[] d : domeAt) if (air(chunk, p, x0 + d[0], f + 4, z0 + d[1])) dome++;
        if (dome < 5) return false;
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

    /**
     * How far the band of air runs from the corner before it meets the far wall, along x ({@code alongX}) or z: the
     * most common length (8 to 13) over the band's rows, which corridor openings and caves only make longer or
     * shorter here and there. -1 if no length is clearly the most common.
     */
    private static int width(Level level, BlockPos.MutableBlockPos p, int x0, int f, int z0, boolean alongX) {
        int[] count = new int[14];
        int rows = 0;
        for (int across = 0; across < 8; across++) {
            for (int dy = 1; dy <= 3; dy++) {
                int run = 0;
                while (run < 14) {
                    int x = alongX ? x0 + run : x0 + across, z = alongX ? z0 + across : z0 + run;
                    if (!level.getBlockState(p.set(x, f + dy, z)).isAir()) break;
                    run++;
                }
                rows++;
                if (run >= 8 && run <= 13) count[run]++;
            }
        }
        int best = -1;
        for (int w = 8; w <= 13; w++) if (best < 0 || count[w] > count[best]) best = w;
        int second = 0;
        for (int w = 8; w <= 13; w++) if (w != best) second = Math.max(second, count[w]);
        return count[best] * 3 >= rows && count[best] >= 2 * second + 2 ? best : -1;
    }

    /** Whether the chunk was generated before 1.18: it keeps its old bedrock floor at y 0-4 (as Old Chunks tells). */
    private static boolean oldChunk(LevelChunk chunk) {
        int idx = chunk.getSectionIndex(0);
        LevelChunkSection[] sections = chunk.getSections();
        if (idx < 0 || idx >= sections.length) return false;
        LevelChunkSection s = sections[idx];
        if (s == null || s.hasOnlyAir() || !s.maybeHas(st -> st.is(Blocks.BEDROCK))) return false;
        for (int y = 0; y < 5; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            if (s.getBlockState(x, y, z).is(Blocks.BEDROCK)) return true;
        }
        return false;
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
