package dev.erebus.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Checks blocks in the world against the game's own structure templates ({@code data/minecraft/structure/*.nbt},
 * shipped in the client for singleplayer), so a template-built structure (end city, bastion, ruined portal) only
 * counts when a real piece of it is there: dozens of blocks in exactly the template's relative positions, in one of
 * its 8 rotations and mirrorings. A player build made of the same blocks doesn't line up with any template.
 *
 * <p>Blocks are compared after {@code canon}, which folds together the variants the game's processors swap in
 * (cracked or mossy bricks, crying obsidian, gilded blackstone...) and drops ones that are often looted or random
 * (chests, gold blocks, lanterns, lava), and a match only needs most of the template, since pieces decay and get
 * mined.
 */
public final class StructureTemplates {
    private StructureTemplates() {
    }

    /** One template, reduced to its comparable blocks. */
    public record Template(String name, int[] x, int[] y, int[] z, Block[] block) {
        public int size() {
            return block.length;
        }
    }

    private static final Map<String, List<Template>> CACHE = new HashMap<>();

    /** Templates under {@code data/minecraft/structure/<dir>} with at least {@code minBlocks} comparable blocks. */
    public static synchronized List<Template> load(String dir, Function<Block, Block> canon, int minBlocks) {
        String key = dir + "#" + minBlocks + "#" + System.identityHashCode(canon);
        List<Template> cached = CACHE.get(key);
        if (cached != null) return cached;
        List<Template> out = new ArrayList<>();
        try {
            String root = "/data/minecraft/structure/" + dir;
            URL url = Minecraft.class.getResource(root);
            if (url != null) {
                URI uri = url.toURI();
                Path base;
                if ("jar".equals(uri.getScheme())) {
                    FileSystem fs;
                    try {
                        fs = FileSystems.getFileSystem(uri);
                    } catch (FileSystemNotFoundException e) {
                        fs = FileSystems.newFileSystem(uri, Map.of());
                    }
                    base = fs.getPath(root);
                } else {
                    base = Path.of(uri);
                }
                try (Stream<Path> files = Files.walk(base)) {
                    for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".nbt"))::iterator) {
                        try (InputStream in = Files.newInputStream(p)) {
                            Template t = parse(p.toString(), NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()), canon);
                            if (t != null && t.size() >= minBlocks) out.add(t);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        CACHE.put(key, out);
        return out;
    }

    private static Template parse(String name, CompoundTag tag, Function<Block, Block> canon) {
        ListTag palette = tag.getListOrEmpty("palette");
        if (palette.isEmpty()) palette = tag.getListOrEmpty("palettes").getListOrEmpty(0);
        Block[] states = new Block[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            String id = palette.getCompoundOrEmpty(i).getStringOr("Name", "minecraft:air");
            Block b = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
            states[i] = b == null ? null : canon.apply(b);
        }
        ListTag blocks = tag.getListOrEmpty("blocks");
        int n = 0;
        int[] xs = new int[blocks.size()], ys = new int[blocks.size()], zs = new int[blocks.size()];
        Block[] bs = new Block[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag b = blocks.getCompoundOrEmpty(i);
            int s = b.getIntOr("state", -1);
            if (s < 0 || s >= states.length || states[s] == null) continue;
            ListTag pos = b.getListOrEmpty("pos");
            xs[n] = pos.getIntOr(0, 0);
            ys[n] = pos.getIntOr(1, 0);
            zs[n] = pos.getIntOr(2, 0);
            bs[n] = states[s];
            n++;
        }
        if (n == 0) return null;
        // spread the comparison order through the piece, so an early stop on a mismatch is a fair sample
        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = (int) ((i * 0x9E3779B1L) % n);
        boolean[] used = new boolean[n];
        int k = 0;
        int[] ox = new int[n], oy = new int[n], oz = new int[n];
        Block[] ob = new Block[n];
        for (int i = 0; i < n; i++) {
            int j = order[i];
            while (used[j]) j = (j + 1) % n;
            used[j] = true;
            ox[k] = xs[j];
            oy[k] = ys[j];
            oz[k] = zs[j];
            ob[k] = bs[j];
            k++;
        }
        return new Template(name, ox, oy, oz, ob);
    }

    /**
     * Finds a template that the world matches around the given area of chunks (center cx, cz and its neighbours):
     * at least {@code minFraction} of its blocks, and at least {@code minMatched} of them. Returns where it lines up
     * (the anchor block's position), or null.
     */
    static BlockPos match(Level level, int cx, int cz, List<Template> templates, Function<Block, Block> canon, double minFraction, int minMatched) {
        return match(level, cx, cz, templates, canon, minFraction, minMatched, null, 0);
    }

    /**
     * Like {@link #match(Level, int, int, List, Function, double, int)}, and additionally at least half of the
     * template's {@code key} blocks (and at least {@code minKey} of them, or all if it has fewer) must be in place:
     * for blocks a lookalike build is unlikely to copy exactly, like a ruined portal's stone brick trim.
     */
    static BlockPos match(Level level, int cx, int cz, List<Template> templates, Function<Block, Block> canon, double minFraction, int minMatched,
            java.util.function.Predicate<Block> key, int minKey) {
        if (templates.isEmpty()) return null;
        // where each comparable block type is in the area
        Map<Block, List<BlockPos>> world = new HashMap<>();
        java.util.Set<Block> wanted = new java.util.HashSet<>();
        for (Template t : templates) wanted.addAll(java.util.Arrays.asList(t.block()));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                LevelChunk chunk = level.getChunkSource() instanceof net.minecraft.client.multiplayer.ClientChunkCache c
                        ? c.getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false) : null;
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection s = sections[i];
                    if (s == null || s.hasOnlyAir() || !s.maybeHas(st -> wanted.contains(canon.apply(st.getBlock())))) continue;
                    int y0 = chunk.getSectionYFromSectionIndex(i) << 4;
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        Block b = canon.apply(s.getBlockState(x, y, z).getBlock());
                        if (!wanted.contains(b)) continue;
                        List<BlockPos> l = world.computeIfAbsent(b, k -> new ArrayList<>());
                        if (l.size() < 4096) l.add(new BlockPos(((cx + dx) << 4) + x, y0 + y, ((cz + dz) << 4) + z));
                    }
                }
            }
        }
        for (Template t : templates) {
            // anchor on the template's rarest block type that's in the area
            Block anchor = null;
            int best = Integer.MAX_VALUE;
            Map<Block, Integer> counts = new HashMap<>();
            for (Block b : t.block()) counts.merge(b, 1, Integer::sum);
            for (var e : counts.entrySet()) {
                List<BlockPos> w = world.get(e.getKey());
                if (w == null || w.isEmpty()) continue;
                long cost = (long) e.getValue() * w.size();
                if (cost < best) {
                    best = (int) Math.min(Integer.MAX_VALUE, cost);
                    anchor = e.getKey();
                }
            }
            if (anchor == null) continue;
            List<BlockPos> anchorsWorld = world.get(anchor);
            int step = Math.max(1, anchorsWorld.size() / 256);
            for (int ti = 0; ti < t.size(); ti++) {
                if (t.block()[ti] != anchor) continue;
                for (int wi = 0; wi < anchorsWorld.size(); wi += step) {
                    BlockPos w = anchorsWorld.get(wi);
                    for (int tr = 0; tr < 8; tr++) {
                        int ax = tx(t.x()[ti], t.z()[ti], tr), az = tz(t.x()[ti], t.z()[ti], tr);
                        int ox = w.getX() - ax, oy = w.getY() - t.y()[ti], oz = w.getZ() - az;
                        if (fits(level, t, tr, ox, oy, oz, canon, minFraction, minMatched, key, minKey)) return w;
                    }
                }
                break; // one template position of the anchor type is enough: any real match includes it
            }
        }
        return null;
    }

    private static boolean fits(Level level, Template t, int tr, int ox, int oy, int oz, Function<Block, Block> canon, double minFraction, int minMatched,
            java.util.function.Predicate<Block> key, int minKey) {
        int n = t.size();
        int allowedMisses = (int) Math.floor(n * (1 - minFraction));
        int misses = 0, hits = 0, keyTotal = 0, keyHits = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < n; i++) {
            p.set(ox + tx(t.x()[i], t.z()[i], tr), oy + t.y()[i], oz + tz(t.x()[i], t.z()[i], tr));
            boolean isKey = key != null && key.test(t.block()[i]);
            if (isKey) keyTotal++;
            if (canon.apply(level.getBlockState(p).getBlock()) == t.block()[i]) {
                hits++;
                if (isKey) keyHits++;
            } else if (++misses > allowedMisses) {
                return false;
            }
        }
        if (hits < minMatched) return false;
        if (key == null) return true;
        return keyTotal > 0 && keyHits >= Math.min(minKey, keyTotal) && keyHits * 2 >= keyTotal;
    }

    /**
     * How well the template matches the world placed with its origin at (ox, oy, oz) and transform {@code tr} (0-3:
     * rotations by 0, 90, 180, 270 degrees clockwise; 4-7: the same mirrored): whether at least {@code minFraction}
     * of its blocks, and at least {@code minMatched} of them, are in place. A quick look at a few of its blocks
     * rules most wrong placements out first.
     */
    public static boolean fitsAt(Level level, Template t, int tr, int ox, int oy, int oz, Function<Block, Block> canon, double minFraction, int minMatched) {
        int sample = Math.min(8, t.size()), hits = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < sample; i++) {
            p.set(ox + tx(t.x()[i], t.z()[i], tr), oy + t.y()[i], oz + tz(t.x()[i], t.z()[i], tr));
            if (canon.apply(level.getBlockState(p).getBlock()) == t.block()[i]) hits++;
        }
        if (hits * 8 < sample * 3) return false;
        return fits(level, t, tr, ox, oy, oz, canon, minFraction, minMatched, null, 0);
    }

    /** The fraction of the template's blocks that are in place with its origin at (ox, oy, oz) under {@code tr}. */
    public static double matchFraction(Level level, Template t, int tr, int ox, int oy, int oz, Function<Block, Block> canon) {
        int hits = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < t.size(); i++) {
            p.set(ox + tx(t.x()[i], t.z()[i], tr), oy + t.y()[i], oz + tz(t.x()[i], t.z()[i], tr));
            if (canon.apply(level.getBlockState(p).getBlock()) == t.block()[i]) hits++;
        }
        return t.size() == 0 ? 0 : hits / (double) t.size();
    }

    /** Where the template's block at (x, z) goes, relative to its origin, under transform {@code tr}: {x, z}. */
    public static int[] transform(int x, int z, int tr) {
        return new int[]{tx(x, z, tr), tz(x, z, tr)};
    }

    // the 8 rotations / mirrorings of a template's x and z
    private static int tx(int x, int z, int tr) {
        int mx = tr >= 4 ? -x : x;
        return switch (tr & 3) {
            case 0 -> mx;
            case 1 -> -z;
            case 2 -> -mx;
            default -> z;
        };
    }

    private static int tz(int x, int z, int tr) {
        int mx = tr >= 4 ? -x : x;
        return switch (tr & 3) {
            case 0 -> z;
            case 1 -> mx;
            case 2 -> -z;
            default -> -mx;
        };
    }

    // ---- block classes for each structure ----

    /** Blocks that never count either way: air, markers, loot, light sources and liquids that get changed or taken. */
    public static boolean ignored(Block b) {
        return b == Blocks.AIR || b == Blocks.CAVE_AIR || b == Blocks.VOID_AIR || b == Blocks.STRUCTURE_VOID || b == Blocks.JIGSAW
                || b == Blocks.STRUCTURE_BLOCK || b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL || b == Blocks.SPAWNER
                || b == Blocks.LAVA || b == Blocks.WATER || b == Blocks.GOLD_BLOCK || b == Blocks.LANTERN || b == Blocks.SOUL_LANTERN
                || b == Blocks.TORCH || b == Blocks.WALL_TORCH || b == Blocks.LADDER || b == Blocks.VINE || b == Blocks.BREWING_STAND
                || b == Blocks.DRAGON_WALL_HEAD || b == Blocks.DRAGON_HEAD || b == Blocks.MAGENTA_BANNER || b == Blocks.MAGENTA_WALL_BANNER
                || b == Blocks.ENDER_CHEST || b == Blocks.IRON_BARS || b == Blocks.MAGMA_BLOCK;
    }

    /** Remembers a canon function's answer per block type (some look up the block's id). */
    public static Function<Block, Block> memo(Function<Block, Block> f) {
        Map<Block, Block> cache = new java.util.concurrent.ConcurrentHashMap<>();
        Block none = Blocks.STRUCTURE_VOID;
        return b -> {
            Block r = cache.computeIfAbsent(b, k -> {
                Block v = f.apply(k);
                return v == null ? none : v;
            });
            return r == none ? null : r;
        };
    }

    static final Function<Block, Block> END_CITY = memo(b -> ignored(b) ? null : b);

    static final Function<Block, Block> BASTION = memo(b -> {
        if (ignored(b)) return null;
        if (b == Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS) return Blocks.POLISHED_BLACKSTONE_BRICKS;
        if (b == Blocks.GILDED_BLACKSTONE) return Blocks.BLACKSTONE;
        if (b == Blocks.IRON_CHAIN) return null;
        return b;
    });

    /**
     * Ruined portals: obsidian and crying obsidian are one class; the brick, stone and (in the Nether) blackstone
     * blocks the processors swap between are one class per shape.
     */
    static final Function<Block, Block> RUINED_PORTAL = memo(b -> {
        if (ignored(b)) return null;
        if (b == Blocks.CRYING_OBSIDIAN) return Blocks.OBSIDIAN;
        if (b == Blocks.NETHERRACK) return Blocks.NETHERRACK;
        String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
        boolean stoneish = id.contains("stone_brick") || id.contains("blackstone") || id.equals("smooth_stone_slab")
                || id.equals("stone_slab") || id.equals("stone_stairs");
        if (!stoneish) return b;
        if (id.endsWith("_stairs")) return Blocks.STONE_BRICK_STAIRS;
        if (id.endsWith("_slab")) return Blocks.STONE_BRICK_SLAB;
        if (id.endsWith("_wall")) return Blocks.STONE_BRICK_WALL;
        return Blocks.STONE_BRICKS;
    });
}
