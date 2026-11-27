package dev.erebus.client.map;

import dev.erebus.client.seed.StructureScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Exact checks for structures built by code rather than templates, using the positions that code always puts
 * blocks at. Each looks at a 3x3-chunk area around (cx, cz) and returns where the structure is, or null.
 */
final class StructureChecks {
    private StructureChecks() {
    }

    private static LevelChunk chunk(int x, int z) {
        var level = Minecraft.getInstance().level;
        return level == null ? null : level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
    }

    /** Positions of these blocks in the area (at most {@code cap}). */
    private static List<BlockPos> find(int cx, int cz, Set<Block> blocks, int cap) {
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                LevelChunk c = chunk(cx + dx, cz + dz);
                if (c == null) continue;
                LevelChunkSection[] sections = c.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection s = sections[i];
                    if (s == null || s.hasOnlyAir() || !s.maybeHas(st -> blocks.contains(st.getBlock()))) continue;
                    int y0 = c.getSectionYFromSectionIndex(i) << 4;
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        if (!blocks.contains(s.getBlockState(x, y, z).getBlock())) continue;
                        out.add(new BlockPos(((cx + dx) << 4) + x, y0 + y, ((cz + dz) << 4) + z));
                        if (out.size() >= cap) return out;
                    }
                }
            }
        }
        return out;
    }

    /**
     * Desert pyramids, jungle temples and swamp huts start exactly at a chunk corner, with a marker at a fixed spot
     * in that chunk for each orientation (see {@link StructureScanner}).
     */
    static BlockPos scanned(int cx, int cz, StructureScanner.Kind kind) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                LevelChunk c = chunk(cx + dx, cz + dz);
                if (c == null) continue;
                for (StructureScanner.Found f : StructureScanner.scan(c, EnumSet.of(kind))) return f.marker();
            }
        }
        return null;
    }

    // the stronghold portal room's 12 frames around the 3x3 portal: 3 on each side of a 5x5 square, corners empty
    private static final int[][] RING = {{-1, -2}, {0, -2}, {1, -2}, {-1, 2}, {0, 2}, {1, 2}, {-2, -1}, {-2, 0}, {-2, 1}, {2, -1}, {2, 0}, {2, 1}};

    /** The portal room: all 12 end portal frames in their ring (frames can't be mined or obtained in survival). */
    static BlockPos strongholdPortal(Level level, int cx, int cz) {
        List<BlockPos> frames = find(cx, cz, Set.of(Blocks.END_PORTAL_FRAME), 256);
        Set<BlockPos> set = new HashSet<>(frames);
        for (BlockPos f : frames) {
            for (int[] r : RING) {
                BlockPos center = f.offset(-r[0], 0, -r[1]);
                boolean all = true;
                for (int[] q : RING) {
                    if (!set.contains(center.offset(q[0], 0, q[1]))) {
                        all = false;
                        break;
                    }
                }
                if (all) return center;
            }
        }
        return null;
    }

    /**
     * Ocean monument: the building is a 58x58 box from y 39 to 61. Its dark prismarine and sea lanterns are all
     * inside that box (only the prismarine-brick legs reach below), so they must all be at y 39..61 and within a
     * 58-block square, with the ocean over it (water at sea level across its footprint).
     */
    static BlockPos monument(Level level, int cx, int cz) {
        List<BlockPos> blocks = find(cx, cz, Set.of(Blocks.DARK_PRISMARINE, Blocks.SEA_LANTERN), 6000);
        int dark = 0, lamps = 0;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (BlockPos p : blocks) {
            if (p.getY() < 39 || p.getY() > 61) return null;
            if (level.getBlockState(p).is(Blocks.SEA_LANTERN)) lamps++;
            else dark++;
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
            maxY = Math.max(maxY, p.getY());
        }
        if (dark < 30 || lamps < 4 || maxX - minX > 57 || maxZ - minZ > 57) return null;
        // the ocean over it: water at sea level across most of its footprint
        int wet = 0, checked = 0;
        for (int x = minX; x <= maxX; x += 6) {
            for (int z = minZ; z <= maxZ; z += 6) {
                checked++;
                if (level.getFluidState(new BlockPos(x, 62, z)).is(net.minecraft.tags.FluidTags.WATER)) wet++;
            }
        }
        if (checked == 0 || wet * 3 < checked * 2) return null;
        return new BlockPos((minX + maxX) / 2, maxY, (minZ + maxZ) / 2);
    }

    /**
     * Nether fortress: either its blaze spawner (spawners can't be obtained in survival), or a stretch of its
     * standard bridge: a 5-wide, 2-thick nether brick deck with 1-high walls on both edges and open air above the
     * walkway, at least 7 blocks long.
     */
    static BlockPos fortress(Level level, int cx, int cz) {
        for (BlockPos p : find(cx, cz, Set.of(Blocks.SPAWNER), 16)) {
            if (level.getBlockEntity(p) instanceof SpawnerBlockEntity s) {
                Entity e = s.getSpawner().getOrCreateDisplayEntity(level, p);
                if (e != null && e.getType() == EntityType.BLAZE) return p;
            }
        }
        List<BlockPos> bricks = find(cx, cz, Set.of(Blocks.NETHER_BRICKS), 20000);
        Set<BlockPos> set = new HashSet<>(bricks);
        for (BlockPos p : bricks) {
            // p = middle of the walkway's top layer
            if (!level.getBlockState(p.above()).isAir()) continue;
            if (bridge(level, set, p, true) || bridge(level, set, p, false)) return p;
        }
        return null;
    }

    private static boolean bridge(Level level, Set<BlockPos> bricks, BlockPos p, boolean alongX) {
        int misses = 0;
        for (int i = 0; i < 7; i++) {
            for (int w = -2; w <= 2; w++) {
                BlockPos q = alongX ? p.offset(i, 0, w) : p.offset(w, 0, i);
                if (!bricks.contains(q)) misses++;
                if (!bricks.contains(q.below())) misses++;
                if (Math.abs(w) == 2) {
                    if (!bricks.contains(q.above())) misses++;
                } else {
                    if (!level.getBlockState(q.above()).isAir()) misses++;
                    if (!level.getBlockState(q.above(2)).isAir()) misses++;
                }
                if (misses > 4) return false;
            }
        }
        return true;
    }
}
