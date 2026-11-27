package dev.erebus.client.seed;

import dev.erebus.client.map.StructureTemplates;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Finds the start chunks of igloos and villages, whose first piece is placed from the game's own structure templates
 * at a fixed spot relative to the start chunk's corner:
 * <ul>
 *   <li>a village's town centre (its meeting point: the bell, well or fountain) has its template origin exactly
 *       on the corner, turned by one of four rotations around that corner;</li>
 *   <li>an igloo's top (the dome) is turned around the point (3, 5) of its template, so its origin lands at a fixed
 *       offset from the corner for each rotation.</li>
 * </ul>
 * So for a candidate start chunk, each template and rotation gives exactly one place to look (only the height is
 * unknown), and a structure only counts if the template matches there. A player build or a structure elsewhere
 * would also have to sit exactly at a chunk corner in the right orientation, so they don't get recorded.
 */
public final class TemplateStarts {
    private TemplateStarts() {
    }

    private static final String[] VILLAGE_DIRS = {
            "village/plains/town_centers", "village/plains/zombie/town_centers", "village/desert/town_centers", "village/desert/zombie/town_centers",
            "village/savanna/town_centers", "village/savanna/zombie/town_centers", "village/snowy/town_centers", "village/snowy/zombie/town_centers",
            "village/taiga/town_centers", "village/taiga/zombie/town_centers"};
    /** The igloo's rotation pivot, in its top template (IglooPieces). */
    private static final int IGLOO_PIVOT_X = 3, IGLOO_PIVOT_Z = 5;

    /**
     * Town centre blocks that are compared: ground the template shares with the land around it (grass, dirt, sand,
     * snow) is left out, so only what the village built has to line up; mossy cobblestone counts as cobblestone.
     */
    private static final Function<Block, Block> VILLAGE = StructureTemplates.memo(b -> {
        if (StructureTemplates.ignored(b) || b == Blocks.GRASS_BLOCK || b == Blocks.DIRT || b == Blocks.COARSE_DIRT || b == Blocks.PODZOL
                || b == Blocks.SHORT_GRASS || b == Blocks.TALL_GRASS || b == Blocks.FERN || b == Blocks.LARGE_FERN || b == Blocks.SNOW
                || b == Blocks.SNOW_BLOCK || b == Blocks.SAND || b == Blocks.RED_SAND || b == Blocks.SANDSTONE || b == Blocks.STONE
                || b == Blocks.GRAVEL || b == Blocks.CAVE_AIR || b == Blocks.BELL) return null;
        if (b == Blocks.MOSSY_COBBLESTONE) return Blocks.COBBLESTONE;
        return b;
    });

    private static final Function<Block, Block> IGLOO = StructureTemplates.memo(b -> StructureTemplates.ignored(b) || b == Blocks.REDSTONE_TORCH
            || b == Blocks.REDSTONE_WALL_TORCH ? null : b);

    private static List<StructureTemplates.Template> villages() {
        List<StructureTemplates.Template> out = new ArrayList<>();
        for (String d : VILLAGE_DIRS) out.addAll(StructureTemplates.load(d, VILLAGE, 25));
        return out;
    }

    private static List<StructureTemplates.Template> igloos() {
        List<StructureTemplates.Template> out = new ArrayList<>();
        for (StructureTemplates.Template t : StructureTemplates.load("igloo", IGLOO, 40)) if (t.name().endsWith("top.nbt")) out.add(t);
        return out;
    }

    /** Whether this chunk may hold part of a village town centre or an igloo (so the chunks around it are worth checking). */
    public static boolean worthChecking(LevelChunk chunk, StructureScanner.Kind kind) {
        for (LevelChunkSection s : chunk.getSections()) {
            if (s == null || s.hasOnlyAir()) continue;
            if (kind == StructureScanner.Kind.VILLAGE && s.maybeHas(b -> b.is(Blocks.BELL) || b.is(Blocks.DIRT_PATH) || b.is(Blocks.SMOOTH_SANDSTONE)))
                return true;
            if (kind == StructureScanner.Kind.IGLOO && s.maybeHas(b -> b.is(Blocks.SNOW_BLOCK))
                    && s.maybeHas(b -> b.is(Blocks.WHITE_CARPET) || b.is(Blocks.LIGHT_GRAY_CARPET) || b.is(Blocks.RED_BED))) return true;
        }
        return false;
    }

    /**
     * Whether a structure of this kind starts in chunk (cx, cz): where its first piece is, or null. Needs the chunks
     * around it loaded.
     */
    public static BlockPos startsIn(Level level, StructureScanner.Kind kind, int cx, int cz) {
        boolean village = kind == StructureScanner.Kind.VILLAGE;
        if (!village && kind != StructureScanner.Kind.IGLOO) return null;
        List<StructureTemplates.Template> templates = village ? villages() : igloos();
        Function<Block, Block> canon = village ? VILLAGE : IGLOO;
        int cornerX = cx << 4, cornerZ = cz << 4;
        for (StructureTemplates.Template t : templates) {
            for (int tr = 0; tr < 4; tr++) { // structures are only rotated, never mirrored, here
                int ox = cornerX, oz = cornerZ;
                if (!village) { // origin = corner + pivot - rotate(pivot)
                    int[] r = StructureTemplates.transform(IGLOO_PIVOT_X, IGLOO_PIVOT_Z, tr);
                    ox += IGLOO_PIVOT_X - r[0];
                    oz += IGLOO_PIVOT_Z - r[1];
                }
                // the height: wherever one of the template's first few blocks shows up in its column near the surface
                java.util.Set<Integer> tried = new java.util.HashSet<>();
                BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                for (int k = 0; k < Math.min(3, t.size()); k++) {
                    int[] at = StructureTemplates.transform(t.x()[k], t.z()[k], tr);
                    int wx = ox + at[0], wz = oz + at[1];
                    int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz);
                    for (int y = top + 2; y >= Math.max(level.getMinY(), top - 20); y--) {
                        p.set(wx, y, wz);
                        if (canon.apply(level.getBlockState(p).getBlock()) != t.block()[k]) continue;
                        int oy = y - t.y()[k];
                        if (!tried.add(oy)) continue;
                        boolean ok = village ? StructureTemplates.fitsAt(level, t, tr, ox, oy, oz, canon, 0.75, 25)
                                : StructureTemplates.fitsAt(level, t, tr, ox, oy, oz, canon, 0.8, 50);
                        if (ok) return new BlockPos(ox, oy, oz);
                    }
                }
            }
        }
        return null;
    }

    /** Whether every chunk a start in (cx, cz) could reach into is loaded. */
    public static boolean areaLoaded(Level level, int cx, int cz) {
        // (the client level's own hasChunk always says yes)
        if (!(level.getChunkSource() instanceof net.minecraft.client.multiplayer.ClientChunkCache cache)) return false;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (cache.getChunk(cx + dx, cz + dz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) == null) return false;
        }
        return true;
    }

    /**
     * The start chunks a chunk holding part of a structure's first piece could belong to: the piece reaches at most
     * a chunk or so from its start chunk's corner, in any direction its rotation points.
     */
    public static List<int[]> candidates(int cx, int cz) {
        List<int[]> out = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) out.add(new int[]{cx + dx, cz + dz});
        return out;
    }
}
