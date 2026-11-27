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
 * Finds the start chunks of igloos, villages and pillager outposts, which are placed from the game's own structure
 * templates at a fixed spot relative to the start chunk's corner:
 * <ul>
 *   <li>a village's town centre (its meeting point: the bell, well or fountain) has its template origin exactly
 *       on the corner, turned by one of four rotations around that corner;</li>
 *   <li>an igloo's top (the dome) is turned around the point (3, 5) of its template, so its origin lands at a fixed
 *       offset from the corner for each rotation;</li>
 *   <li>an outpost starts with an invisible base plate on the corner; its watchtower hangs off one of the plate's
 *       two tower jigsaws, turned the same way, so the tower's origin is (0 or 1, 0, 0) in the plate's frame.</li>
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

    /** Watchtower blocks: the overgrown overlay's mossy variants count as the plain ones, its vines and banners don't count. */
    private static final Function<Block, Block> OUTPOST = StructureTemplates.memo(b -> {
        if (StructureTemplates.ignored(b) || b == Blocks.VINE || b == Blocks.WHITE_WALL_BANNER || b == Blocks.WHITE_BANNER) return null;
        if (b == Blocks.MOSSY_COBBLESTONE) return Blocks.COBBLESTONE;
        if (b == Blocks.MOSSY_COBBLESTONE_STAIRS) return Blocks.COBBLESTONE_STAIRS;
        if (b == Blocks.MOSSY_COBBLESTONE_SLAB) return Blocks.COBBLESTONE_SLAB;
        if (b == Blocks.MOSSY_COBBLESTONE_WALL) return Blocks.COBBLESTONE_WALL;
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

    private static List<StructureTemplates.Template> outposts() {
        List<StructureTemplates.Template> out = new ArrayList<>();
        for (StructureTemplates.Template t : StructureTemplates.load("pillager_outpost", OUTPOST, 200))
            if (t.name().endsWith("/watchtower.nbt")) out.add(t);
        return out;
    }

    /** Whether this chunk may hold part of a village town centre, an igloo or an outpost (so the chunks around it are worth checking). */
    public static boolean worthChecking(LevelChunk chunk, StructureScanner.Kind kind) {
        if (kind == StructureScanner.Kind.MINESHAFT) return MineshaftRooms.worthChecking(chunk);
        for (LevelChunkSection s : chunk.getSections()) {
            if (s == null || s.hasOnlyAir()) continue;
            if (kind == StructureScanner.Kind.VILLAGE && s.maybeHas(b -> b.is(Blocks.BELL) || b.is(Blocks.DIRT_PATH) || b.is(Blocks.SMOOTH_SANDSTONE)))
                return true;
            if (kind == StructureScanner.Kind.IGLOO && s.maybeHas(b -> b.is(Blocks.SNOW_BLOCK))
                    && s.maybeHas(b -> b.is(Blocks.WHITE_CARPET) || b.is(Blocks.LIGHT_GRAY_CARPET) || b.is(Blocks.RED_BED))) return true;
            if (kind == StructureScanner.Kind.OUTPOST && s.maybeHas(b -> b.is(Blocks.DARK_OAK_LOG)) && s.maybeHas(b -> b.is(Blocks.BIRCH_PLANKS)))
                return true;
        }
        return false;
    }

    /**
     * A structure's first piece as found: where its template's origin is, which template (for villages, its town
     * centre) and its rotation (0-3 clockwise; null when two rotations fit about equally well, as a nearly
     * symmetric igloo dome can).
     */
    public record Start(BlockPos pos, String template, Integer rotation) {
    }

    /**
     * Whether a structure of this kind starts in chunk (cx, cz): where its first piece is, or null. Needs the chunks
     * around it loaded.
     */
    public static BlockPos startsIn(Level level, StructureScanner.Kind kind, int cx, int cz) {
        Start s = startPiece(level, kind, cx, cz);
        return s == null ? null : s.pos();
    }

    /** Like {@link #startsIn}, with which template matched and how it's turned. */
    public static Start startPiece(Level level, StructureScanner.Kind kind, int cx, int cz) {
        if (kind == StructureScanner.Kind.MINESHAFT) {
            MineshaftRooms.Room room = MineshaftRooms.findRoom(level, cx, cz);
            return room == null ? null : new Start(room.corner(), "room " + room.sizeX() + "x" + room.sizeZ(), null);
        }
        List<StructureTemplates.Template> templates;
        Function<Block, Block> canon;
        double fraction;
        int minMatched;
        switch (kind) {
            case VILLAGE -> { templates = villages(); canon = VILLAGE; fraction = 0.75; minMatched = 25; }
            case IGLOO -> { templates = igloos(); canon = IGLOO; fraction = 0.8; minMatched = 50; }
            case OUTPOST -> { templates = outposts(); canon = OUTPOST; fraction = 0.6; minMatched = 250; }
            default -> { return null; }
        }
        // every template and rotation that fits, keeping the best match, and the best score for each rotation
        int cornerX = cx << 4, cornerZ = cz << 4;
        Start best = null;
        double[] byRotation = {-1, -1, -1, -1};
        for (StructureTemplates.Template t : templates) {
            for (int tr = 0; tr < 4; tr++) { // structures are only rotated, never mirrored, here
                for (int[] origin : origins(kind, tr)) {
                    BlockPos at = fitsNear(level, t, tr, cornerX + origin[0], cornerZ + origin[1], canon, fraction, minMatched);
                    if (at == null) continue;
                    double score = StructureTemplates.matchFraction(level, t, tr, at.getX(), at.getY(), at.getZ(), canon);
                    if (best == null || score > byRotation[best.rotation()]) best = new Start(at, t.name(), tr);
                    byRotation[tr] = Math.max(byRotation[tr], score);
                }
            }
        }
        if (best == null) return null;
        // the rotation only counts if no other one fits about as well
        for (int tr = 0; tr < 4; tr++) {
            if (tr != best.rotation() && byRotation[tr] > byRotation[best.rotation()] - 0.05) return new Start(best.pos(), best.template(), null);
        }
        return best;
    }

    /**
     * Where a village's town centre sits in its biome's start pool, which the game picks from with
     * {@code nextInt(poolSize)}, each piece taking as many slots as its weight: {poolSize, lo, hi, lo, hi...} for
     * the centre and its abandoned (zombie) twin, which can't be told apart from their blocks. Null if unknown.
     */
    public static int[] villageCentreSlots(String template) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("village/([a-z]+)/(?:zombie/)?town_centers/([a-z0-9_]+)\\.nbt$").matcher(template);
        if (!m.find()) return null;
        String biome = m.group(1), name = m.group(2);
        int[] slots = CENTRE_SLOTS.computeIfAbsent(biome + "/" + name, k -> {
            String file = "/data/minecraft/worldgen/template_pool/village/" + biome + "/town_centers.json";
            try (var in = net.minecraft.client.Minecraft.class.getResourceAsStream(file)) {
                if (in == null) return new int[0];
                var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in)).getAsJsonObject();
                List<Integer> out = new ArrayList<>();
                int slot = 0;
                for (var el : json.getAsJsonArray("elements")) {
                    var o = el.getAsJsonObject();
                    int weight = o.get("weight").getAsInt();
                    String location = o.getAsJsonObject("element").get("location").getAsString();
                    if (location.endsWith("/" + name)) {
                        out.add(slot);
                        out.add(slot + weight);
                    }
                    slot += weight;
                }
                if (out.isEmpty()) return new int[0];
                int[] r = new int[out.size() + 1];
                r[0] = slot;
                for (int i = 0; i < out.size(); i++) r[i + 1] = out.get(i);
                return r;
            } catch (Exception e) {
                return new int[0];
            }
        });
        return slots.length == 0 ? null : slots;
    }

    private static final java.util.Map<String, int[]> CENTRE_SLOTS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Where the template's origin goes relative to the start chunk's corner, for rotation {@code tr}: {x, z} pairs. */
    private static List<int[]> origins(StructureScanner.Kind kind, int tr) {
        return switch (kind) {
            case IGLOO -> { // origin = corner + pivot - rotate(pivot)
                int[] r = StructureTemplates.transform(IGLOO_PIVOT_X, IGLOO_PIVOT_Z, tr);
                yield List.of(new int[]{IGLOO_PIVOT_X - r[0], IGLOO_PIVOT_Z - r[1]});
            }
            // the tower's jigsaw (7, 1, 13) sits just north of the plate's (7 or 8, 1, 14)
            case OUTPOST -> List.of(StructureTemplates.transform(0, 0, tr), StructureTemplates.transform(1, 0, tr));
            default -> List.of(new int[]{0, 0});
        };
    }

    /** Whether the template matches with its origin at (ox, ?, oz), at a height near the surface there; where, or null. */
    private static BlockPos fitsNear(Level level, StructureTemplates.Template t, int tr, int ox, int oz, Function<Block, Block> canon,
            double fraction, int minMatched) {
        // the height: wherever one of the template's first few blocks shows up in its column near the surface
        java.util.Set<Integer> tried = new java.util.HashSet<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int k = 0; k < Math.min(3, t.size()); k++) {
            int[] at = StructureTemplates.transform(t.x()[k], t.z()[k], tr);
            int wx = ox + at[0], wz = oz + at[1];
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz);
            for (int y = top + 2; y >= Math.max(level.getMinY(), top - 30); y--) {
                p.set(wx, y, wz);
                if (canon.apply(level.getBlockState(p).getBlock()) != t.block()[k]) continue;
                int oy = y - t.y()[k];
                if (!tried.add(oy)) continue;
                if (StructureTemplates.fitsAt(level, t, tr, ox, oy, oz, canon, fraction, minMatched)) return new BlockPos(ox, oy, oz);
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
