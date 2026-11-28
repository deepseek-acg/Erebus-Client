package dev.erebus.client.module.basefinding;

import dev.erebus.client.module.Category;
import dev.erebus.client.module.Module;
import dev.erebus.client.module.WorldRender;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Highlights blocks the world never generates on its own, so anything it shows was placed by a player: concrete,
 * shulker boxes, beacons, enchanting tables, anvils, ender chests, mineral blocks, redstone parts and more. Also flags
 * blocks that only generate in another dimension, and common building materials where the world wouldn't have put
 * them (not near a natural structure, not natural for the biome). Ported from Nyx.
 */
public final class PlayerBlocks extends Module {
    /** One loud color for everything a player placed, so it stands out from Stash ESP and the world. */
    public static final int PLACED_COLOR = 0xFFFF00FF;

    private static final Set<Block> STRONG = new HashSet<>();

    /** Whether the world never generates this block, so only a player could have put it there (concrete, beacons...). */
    public static boolean isPlayerOnly(Block b) {
        return STRONG.contains(b);
    }
    private static final Set<Block> COMMON = commonDefaults();

    private final BoolSetting wrongDimension;
    private final NumberSetting minPerChunk;
    private final BoolSetting common;
    private final NumberSetting commonMin;
    private final BoolSetting structureCheck;
    private final BoolSetting biomeCheck;
    private final BoolSetting showStructures;
    private final BoolSetting fill;
    private final NumberSetting maxShown;
    private final Map<Type, BoolSetting> typeOn = new EnumMap<>(Type.class);

    private final Map<Long, ChunkData> byChunk = new HashMap<>();
    /** Illagers seen, by chunk: {radius, lowest y, highest y}. */
    private final Map<Long, int[]> entityStructures = new HashMap<>();
    private final List<Long> queue = new ArrayList<>();
    private int queueIndex, total;
    private int placedCount;
    private int structureCount;

    public PlayerBlocks() {
        super("Player Blocks", "Highlights blocks that never generate naturally, so anything it shows was placed by a player. "
                + "Also gives Stash ESP what it needs to tell bases apart from stashes.", Category.BASEFINDING);
        group("Find");
        wrongDimension = add(new BoolSetting("Wrong-dimension blocks", "Flag blocks that only generate in another dimension: end stone, "
                + "purpur and end rods outside the End, nether bricks outside the Nether.", true));
        minPerChunk = add(new NumberSetting("Min per chunk", "Ignore chunks with fewer than this many (filters out the odd stray block).", 1, 1, 20, 1));
        for (Type t : Type.values()) {
            if (t == Type.NaturalStructures || t == Type.BuildingMaterials || t == Type.Custom) continue;
            typeOn.put(t, add(new BoolSetting(t.label, "Show " + t.label.toLowerCase() + ".", true)));
        }
        group("Building materials");
        common = add(new BoolSetting("Building materials", "Also flag common building blocks (planks, glass, wool, stone bricks, cobblestone, "
                + "torches, beds, doors...) when they're not part of a natural structure and not natural for the biome.", true));
        commonMin = add(new NumberSetting("Materials per chunk", "A chunk needs at least this many building-material blocks before they're shown.",
                4, 1, 20, 1).visibleWhen(common::get));
        structureCheck = add(new BoolSetting("Skip structures", "Don't flag building materials near natural structures (villages, strongholds, "
                + "mineshafts, temples, ruins, ancient cities, trial chambers, outposts, mansions, ruined portals, bastions, end cities).", true)
                .visibleWhen(common::get));
        biomeCheck = add(new BoolSetting("Check biome", "Don't flag materials that are natural where they are: snow and ice in cold biomes, "
                + "terracotta in badlands, prismarine in oceans, shipwreck wood on beaches, Nether and End terrain.", true).visibleWhen(common::get));
        showStructures = add(new BoolSetting("Show structures", "Outline the blocks that gave away a natural structure (gray), so you can see "
                + "why an area is being ignored.", false).visibleWhen(common::get));
        group("Render");
        fill = add(new BoolSetting("Fill", "Also shade the inside of each box a little (off = outline only).", false));
        maxShown = add(new NumberSetting("Max shown", "Only outline this many blocks (the closest ones), to keep the game smooth.", 400, 50, 1500, 50));
        addVisuals("The closest placed blocks glow softly instead of a flat fill.");
    }

    /** Player-placed and natural-structure counts in view, for the legend HUD element. */
    public int placedCount() {
        return placedCount;
    }

    public int structureCount() {
        return structureCount;
    }

    public boolean showsStructures() {
        return showStructures.get() && common.get();
    }

    private static Block[] byId(Predicate<String> test) {
        List<Block> out = new ArrayList<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            if (test.test(BuiltInRegistries.BLOCK.getKey(b).getPath())) out.add(b);
        }
        return out.toArray(new Block[0]);
    }

    private static void put(Type t, Block[] blocks) {
        put(t, List.of(blocks));
    }

    @Override
    protected void onEnable() {
        byChunk.clear();
        entityStructures.clear();
        structureCache.clear();
        queue.clear();
        queueIndex = 0;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.level == null) return;
        if (queueIndex >= queue.size()) {
            queue.clear();
            queueIndex = 0;
            int r = Math.max(2, mc.options.getEffectiveRenderDistance());
            ChunkPos c = mc.player.chunkPosition();
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) queue.add(ChunkPos.asLong(c.x + dx, c.z + dz));
            Set<Long> inRange = new HashSet<>(queue);
            byChunk.keySet().removeIf(k -> !inRange.contains(k));
            entityStructures.keySet().removeIf(k -> !inRange.contains(k));
            structureCache.clear();
            // illagers live in outposts and mansions (players don't keep them around)
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e instanceof net.minecraft.world.entity.monster.illager.AbstractIllager) {
                    int y = e.blockPosition().getY();
                    entityStructures.merge(ChunkPos.asLong(e.blockPosition().getX() >> 4, e.blockPosition().getZ() >> 4), new int[]{4, y, y},
                            (a, b) -> new int[]{4, Math.min(a[1], b[1]), Math.max(a[2], b[2])});
                }
            }
            int placed = 0, structures = 0;
            for (Map.Entry<Long, ChunkData> e : byChunk.entrySet()) {
                for (Hit h : shown(e.getKey(), e.getValue())) {
                    if (h.type == Type.NaturalStructures) structures++;
                    else if (typeShown(h.type)) placed++;
                }
            }
            total = placed;
            placedCount = placed;
            structureCount = structures;
            info = total > 0 ? String.valueOf(total) : null;
        }
        int perTick = common.get() ? 12 : 24; // checking building materials reads more blocks, so spread it out more
        for (int n = 0; n < perTick && queueIndex < queue.size(); n++) scan(queue.get(queueIndex++));
    }

    /** What to show for one chunk right now (building materials depend on structures found nearby since). */
    private List<Hit> shown(long key, ChunkData d) {
        List<Hit> out = new ArrayList<>();
        int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
        boolean check = structureCheck.get();
        if (d.strong.size() >= minPerChunk.intValue()) {
            for (Hit h : d.strong) {
                if (h.excuse == 0 || !check || (h.excuse & structureKinds(cx, cz, h.pos.getY())) == 0) out.add(h);
                else if (showStructures.get()) out.add(new Hit(h.pos, Type.NaturalStructures, 0));
            }
        }
        if (common.get() && d.materials.size() >= commonMin.intValue()) {
            List<Hit> materials = new ArrayList<>();
            for (BlockPos p : d.materials) {
                if (!check || structureKinds(cx, cz, p.getY()) == 0) materials.add(new Hit(p, Type.BuildingMaterials, 0));
            }
            if (materials.size() >= commonMin.intValue()) out.addAll(materials);
        }
        if (showStructures.get()) for (BlockPos p : d.markers) out.add(new Hit(p, Type.NaturalStructures, 0));
        return out;
    }

    /** Player-placed blocks currently shown in the square of chunks around (cx, cz), used by Stash ESP's base alerts. */
    public List<BlockPos> placedIn(int cx, int cz, int r) {
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                long k = ChunkPos.asLong(cx + dx, cz + dz);
                ChunkData d = byChunk.get(k);
                if (d == null) continue;
                for (Hit h : shown(k, d)) if (h.type != Type.NaturalStructures && typeShown(h.type)) out.add(h.pos);
            }
        }
        return out;
    }

    private boolean typeShown(Type t) {
        BoolSetting s = typeOn.get(t);
        return s == null || s.get();
    }

    @Override
    public void onRenderWorld(WorldRender ctx) {
        if (mc.player == null || byChunk.isEmpty()) return;
        Vec3 camPos = ctx.cameraPos();
        List<Hit> all = new ArrayList<>();
        for (Map.Entry<Long, ChunkData> e : byChunk.entrySet()) {
            for (Hit h : shown(e.getKey(), e.getValue())) if (typeShown(h.type)) all.add(h);
        }
        all.sort((a, b) -> Double.compare(a.pos.distToCenterSqr(camPos), b.pos.distToCenterSqr(camPos)));
        int limit = Math.min(all.size(), maxShown.intValue());
        boolean shade = fill.get();
        for (int i = 0; i < limit; i++) {
            Hit h = all.get(i);
            int c = h.type == Type.NaturalStructures ? h.type.color : PLACED_COLOR;
            if (visuals() && i < 48) { // a soft breathing glow instead of a flat fill
                dev.erebus.client.render.Visuals.glowBox(new AABB(h.pos), c, 1.5f, 0x55);
                continue;
            }
            GizmoStyle style = shade ? GizmoStyle.strokeAndFill(c, 1.5f, (45 << 24) | (c & 0xFFFFFF)) : GizmoStyle.stroke(c, 1.5f);
            Gizmos.cuboid(new AABB(h.pos), style).setAlwaysOnTop();
        }
    }

    /** What kind of player-placed block it is (each type has its own color). */
    public enum Type {
        Concrete("Concrete", 0xFF38BDF8),
        ShulkerBoxes("Shulker boxes", 0xFFF472B6),
        Valuables("Valuable blocks", 0xFFFACC15),
        Utility("Utility blocks", 0xFFA78BFA),
        Redstone("Redstone parts", 0xFFF87171),
        Decoration("Decoration", 0xFF4ADE80),
        Custom("Your added blocks", 0xFFE5E7EB),
        WrongDimension("Wrong dimension", 0xFFFB923C),
        BuildingMaterials("Building materials", 0xFF2DD4BF),
        NaturalStructures("Natural structures (ignored)", 0xFF9CA3AF);

        public final String label;
        public final int color;

        Type(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    private static final Map<Block, Type> TYPES = new HashMap<>();

    private static void put(Type t, List<Block> blocks) {
        for (Block b : blocks) TYPES.put(b, t);
    }

    static {
        put(Type.Concrete, byId(id -> id.endsWith("_concrete") || id.endsWith("_concrete_powder")));
        put(Type.ShulkerBoxes, byId(id -> id.endsWith("shulker_box")));
        put(Type.Valuables, List.of(Blocks.BEACON, Blocks.NETHERITE_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK,
            Blocks.IRON_BLOCK, Blocks.LAPIS_BLOCK, Blocks.REDSTONE_BLOCK));
        put(Type.Utility, List.of(Blocks.ENCHANTING_TABLE, Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL,
            Blocks.ENDER_CHEST, Blocks.RESPAWN_ANCHOR, Blocks.LODESTONE, Blocks.CONDUIT, Blocks.BEEHIVE, Blocks.SCAFFOLDING,
            Blocks.NOTE_BLOCK, Blocks.JUKEBOX));
        put(Type.Redstone, List.of(Blocks.OBSERVER, Blocks.COMPARATOR, Blocks.DAYLIGHT_DETECTOR, Blocks.TARGET,
            Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK, Blocks.HONEYCOMB_BLOCK));
        put(Type.Decoration, List.of(Blocks.TINTED_GLASS, Blocks.OCHRE_FROGLIGHT, Blocks.VERDANT_FROGLIGHT,
            Blocks.PEARLESCENT_FROGLIGHT, Blocks.QUARTZ_BLOCK, Blocks.SMOOTH_QUARTZ, Blocks.QUARTZ_PILLAR,
            Blocks.CHISELED_QUARTZ_BLOCK, Blocks.QUARTZ_BRICKS, Blocks.RED_NETHER_BRICKS, Blocks.CRIMSON_PLANKS,
            Blocks.WARPED_PLANKS, Blocks.DRIED_KELP_BLOCK));
    }


    /** A flagged block; {@code excuse} is the kinds of structure it's part of when found in one (see {@link #EXCUSED}). */
    private record Hit(BlockPos pos, Type type, int excuse) { }

    /** Everything found in one chunk. */
    private static final class ChunkData {
        final List<Hit> strong = new ArrayList<>();      // never natural: always shown
        final List<BlockPos> materials = new ArrayList<>(); // building materials not natural for the biome
        final List<BlockPos> markers = new ArrayList<>();   // blocks that prove a natural structure is here
        int structureRadius = -1;                           // chunks around this one that belong to a structure
        final List<Area> areas = new ArrayList<>();         // what's here: how far around and at what heights
    }



    /** Common blocks players build with (by name, so every wood type and color is included). */
    private static Set<Block> commonDefaults() {
        List<Block> l = new ArrayList<>(List.of(Blocks.GLASS, Blocks.GLASS_PANE, Blocks.TORCH, Blocks.WALL_TORCH, Blocks.LANTERN,
            Blocks.SOUL_LANTERN, Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS,
            Blocks.CRACKED_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS, Blocks.BRICKS, Blocks.SMOOTH_STONE, Blocks.SMOOTH_STONE_SLAB,
            Blocks.POLISHED_ANDESITE, Blocks.POLISHED_DIORITE, Blocks.POLISHED_GRANITE, Blocks.IRON_BARS, Blocks.LADDER,
            Blocks.HAY_BLOCK, Blocks.SNOW_BLOCK, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS,
            Blocks.DARK_PRISMARINE, Blocks.SEA_LANTERN, Blocks.MUD_BRICKS, Blocks.CUT_SANDSTONE, Blocks.SMOOTH_SANDSTONE,
            Blocks.CHISELED_SANDSTONE, Blocks.NETHERRACK, Blocks.TERRACOTTA));
        for (Block b : BuiltInRegistries.BLOCK) {
            String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
            if (id.startsWith("crimson") || id.startsWith("warped") || id.startsWith("nether_brick")) continue; // natural in the Nether / already flagged
            if (id.contains("moss_carpet")) continue; // moss carpets grow naturally (lush caves, pale gardens)
            if (id.endsWith("_planks") || id.endsWith("_wool") || id.endsWith("_carpet") || id.endsWith("_bed")
                || id.endsWith("_stained_glass") || id.endsWith("_stained_glass_pane") || id.endsWith("_terracotta")
                || id.endsWith("_fence") || id.endsWith("_fence_gate") || id.endsWith("_door") || id.endsWith("_trapdoor")
                || id.endsWith("_sign") || id.endsWith("_banner") || id.endsWith("rail")
                || id.equals("cobblestone_stairs") || id.equals("cobblestone_slab") || id.equals("cobblestone_wall")
                || id.equals("stone_brick_stairs") || id.equals("stone_brick_slab") || id.equals("stone_brick_wall")
                || id.equals("brick_stairs") || id.equals("brick_slab") || id.equals("brick_wall")) {
                if (!l.contains(b)) l.add(b);
            }
        }
        return new HashSet<Block>(l);
    }

    static {
        STRONG.addAll(List.of(defaults()));
    }

    private static Block[] defaults() {
        List<Block> l = new ArrayList<>();
        l.addAll(List.of(byId(id -> id.endsWith("_concrete") || id.endsWith("_concrete_powder"))));
        l.addAll(List.of(byId(id -> id.endsWith("shulker_box"))));
        l.addAll(List.of(Blocks.BEACON, Blocks.ENCHANTING_TABLE, Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL,
            Blocks.ENDER_CHEST, Blocks.RESPAWN_ANCHOR, Blocks.NETHERITE_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK,
            Blocks.IRON_BLOCK, Blocks.LAPIS_BLOCK, Blocks.REDSTONE_BLOCK, Blocks.HONEY_BLOCK, Blocks.HONEYCOMB_BLOCK,
            Blocks.SLIME_BLOCK, Blocks.OBSERVER, Blocks.COMPARATOR, Blocks.DAYLIGHT_DETECTOR, Blocks.TARGET, Blocks.LODESTONE,
            Blocks.BEEHIVE, Blocks.CONDUIT, Blocks.SCAFFOLDING, Blocks.DRIED_KELP_BLOCK, Blocks.NOTE_BLOCK, Blocks.JUKEBOX,
            Blocks.TINTED_GLASS, Blocks.OCHRE_FROGLIGHT, Blocks.VERDANT_FROGLIGHT, Blocks.PEARLESCENT_FROGLIGHT,
            Blocks.QUARTZ_BLOCK, Blocks.SMOOTH_QUARTZ, Blocks.QUARTZ_PILLAR, Blocks.CHISELED_QUARTZ_BLOCK, Blocks.QUARTZ_BRICKS,
            Blocks.RED_NETHER_BRICKS, Blocks.CRIMSON_PLANKS, Blocks.WARPED_PLANKS));
        return l.toArray(new Block[0]);
    }


    /** Blocks that only generate naturally in one dimension. */
    private static boolean wrongDim(BlockState s, ResourceKey<Level> dim) {
        if (dim != Level.END && (s.is(Blocks.END_STONE) || s.is(Blocks.END_STONE_BRICKS) || s.is(Blocks.PURPUR_BLOCK)
            || s.is(Blocks.PURPUR_PILLAR) || s.is(Blocks.END_ROD))) return true;
        return dim != Level.NETHER && s.is(Blocks.NETHER_BRICKS);
    }

    // ---- Structures ------------------------------------------------------------------------------

    /** Natural blocks that must never count as player-placed, even if an older saved list still has them. */
    private static final Set<Block> NEVER_FLAG = Set.of(Blocks.MOSS_CARPET, Blocks.PALE_MOSS_CARPET);

    private static final Set<Block> JOB_SITES = Set.of(Blocks.COMPOSTER, Blocks.LECTERN, Blocks.FLETCHING_TABLE,
        Blocks.SMITHING_TABLE, Blocks.CARTOGRAPHY_TABLE, Blocks.LOOM, Blocks.STONECUTTER, Blocks.GRINDSTONE,
        Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.BARREL, Blocks.CAULDRON, Blocks.BREWING_STAND);

    /** How many chunks around a single give-away block count as that structure (0 = not a give-away block). */
    static int markerRadius(BlockState s, ResourceKey<Level> dim) {
        Block b = s.getBlock();
        if (b == Blocks.BELL) return 5;                                                   // village
        if (b == Blocks.END_PORTAL_FRAME) return 6;                                       // stronghold
        if (b == Blocks.INFESTED_STONE_BRICKS || b == Blocks.INFESTED_MOSSY_STONE_BRICKS
            || b == Blocks.INFESTED_CRACKED_STONE_BRICKS || b == Blocks.INFESTED_CHISELED_STONE_BRICKS) return 5; // stronghold
        if (b == Blocks.SPAWNER) return 2;                                                // dungeon / mineshaft / fortress
        if (b == Blocks.TRIAL_SPAWNER || b == Blocks.VAULT) return 4;                     // trial chambers
        if (b == Blocks.REINFORCED_DEEPSLATE) return 7;                                   // ancient city
        if (b == Blocks.SUSPICIOUS_SAND || b == Blocks.SUSPICIOUS_GRAVEL) return 3;       // pyramid / trail & ocean ruins / well
        if (b == Blocks.CRYING_OBSIDIAN) return 2;                                        // ruined portal
        if (b == Blocks.GILDED_BLACKSTONE) return 5;                                      // bastion
        if (dim == Level.END && (b == Blocks.PURPUR_BLOCK || b == Blocks.END_ROD)) return 4; // end city / ship
        return 0;
    }

    private static boolean isMarkerCandidate(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.BELL || b == Blocks.END_PORTAL_FRAME || b == Blocks.INFESTED_STONE_BRICKS
            || b == Blocks.INFESTED_MOSSY_STONE_BRICKS || b == Blocks.INFESTED_CRACKED_STONE_BRICKS
            || b == Blocks.INFESTED_CHISELED_STONE_BRICKS || b == Blocks.SPAWNER || b == Blocks.TRIAL_SPAWNER
            || b == Blocks.VAULT || b == Blocks.REINFORCED_DEEPSLATE || b == Blocks.DEEPSLATE_BRICKS || b == Blocks.DEEPSLATE_TILES
            || b == Blocks.SUSPICIOUS_SAND || b == Blocks.SUSPICIOUS_GRAVEL || b == Blocks.CRYING_OBSIDIAN
            || b == Blocks.GILDED_BLACKSTONE || b == Blocks.PURPUR_BLOCK || b == Blocks.END_ROD
            || b == Blocks.DIRT_PATH || b == Blocks.COBWEB || b == Blocks.RAIL || b == Blocks.TNT
            || JOB_SITES.contains(b) || b == Blocks.MOSSY_STONE_BRICKS || b == Blocks.CRACKED_STONE_BRICKS
            || b == Blocks.STONE_BRICKS || b == Blocks.CHISELED_SANDSTONE || b == Blocks.MOSSY_COBBLESTONE
            || b == Blocks.CHISELED_STONE_BRICKS || b == Blocks.SNOW_BLOCK || s.is(net.minecraft.tags.BlockTags.BEDS);
    }

    // Kinds of structure that build with blocks that otherwise never generate (bit = 1 << K_...)
    private static final int K_ANCIENT_CITY = 0, K_TRIAL_CHAMBERS = 1, K_MANSION = 2, K_RUINS = 3, K_BASTION = 4, K_END_CITY = 5;
    /** Any structure at all (the bit for building materials). */
    private static final int ANY = 1 << 6;

    /**
     * Never-natural blocks that some structures are built with anyway, found by reading every structure the game has:
     * the redstone in ancient cities' centers, trial chambers' concrete, mansions' diamond and lapis rooms, anvils in
     * mansions and trail ruins, bastions' quartz bridges, End cities' ender chests. Each is only excused near its own
     * structure (concrete by a trial chamber is fine, concrete by a village isn't).
     */
    private static final Map<Block, Integer> EXCUSED = Map.ofEntries(
        Map.entry(Blocks.COMPARATOR, 1 << K_ANCIENT_CITY), Map.entry(Blocks.REDSTONE_BLOCK, 1 << K_ANCIENT_CITY),
        Map.entry(Blocks.TARGET, 1 << K_ANCIENT_CITY), Map.entry(Blocks.NOTE_BLOCK, 1 << K_ANCIENT_CITY),
        Map.entry(Blocks.WHITE_CONCRETE, 1 << K_TRIAL_CHAMBERS), Map.entry(Blocks.RED_CONCRETE, 1 << K_TRIAL_CHAMBERS),
        Map.entry(Blocks.DIAMOND_BLOCK, 1 << K_MANSION), Map.entry(Blocks.LAPIS_BLOCK, 1 << K_MANSION),
        Map.entry(Blocks.DAMAGED_ANVIL, 1 << K_MANSION | 1 << K_RUINS),
        Map.entry(Blocks.QUARTZ_BLOCK, 1 << K_BASTION), Map.entry(Blocks.SMOOTH_QUARTZ, 1 << K_BASTION),
        Map.entry(Blocks.ENDER_CHEST, 1 << K_END_CITY));

    /** Which kind of structure a give-away block belongs to (-1 = none we name). */
    private static int markerKind(BlockState s, ResourceKey<Level> dim) {
        Block b = s.getBlock();
        if (b == Blocks.REINFORCED_DEEPSLATE) return K_ANCIENT_CITY;
        if (b == Blocks.TRIAL_SPAWNER || b == Blocks.VAULT) return K_TRIAL_CHAMBERS;
        if (b == Blocks.SUSPICIOUS_SAND || b == Blocks.SUSPICIOUS_GRAVEL) return K_RUINS;
        if (b == Blocks.GILDED_BLACKSTONE) return K_BASTION;
        if (dim == Level.END && (b == Blocks.PURPUR_BLOCK || b == Blocks.END_ROD)) return K_END_CITY;
        return -1;
    }

    /**
     * A natural structure found in a chunk: the chunks within {@code radius} of it belong to it, between the heights its
     * give-away blocks were found at (give or take {@link #MARGIN}). The height matters: mineshafts run under much of
     * the world, and a base on the surface above one is still a base.
     */
    private record Area(int radius, int kinds, int minY, int maxY) {
    }

    private static final int MARGIN = 24;

    /** Per chunk, the structure areas that reach it: {kinds, lowest y, highest y}. */
    private final Map<Long, List<int[]>> structureCache = new HashMap<>();

    /** The kinds of structure whose area this block is in, as bits ({@link #ANY} for any at all; 0 = none). */
    private int structureKinds(int cx, int cz, int y) {
        int kinds = 0;
        for (int[] a : structureCache.computeIfAbsent(ChunkPos.asLong(cx, cz), k -> areasReaching(cx, cz))) {
            if (y >= a[1] - MARGIN && y <= a[2] + MARGIN) kinds |= a[0];
        }
        return kinds;
    }

    private List<int[]> areasReaching(int cx, int cz) {
        List<int[]> out = new ArrayList<>();
        for (Map.Entry<Long, ChunkData> e : byChunk.entrySet()) {
            ChunkData d = e.getValue();
            if (d.areas.isEmpty()) continue;
            int ddx = Math.abs(ChunkPos.getX(e.getKey()) - cx), ddz = Math.abs(ChunkPos.getZ(e.getKey()) - cz);
            for (Area a : d.areas) if (ddx <= a.radius && ddz <= a.radius) out.add(new int[]{a.kinds, a.minY, a.maxY});
        }
        for (Map.Entry<Long, int[]> e : entityStructures.entrySet()) { // illagers: mansions and outposts
            int[] v = e.getValue();
            if (Math.abs(ChunkPos.getX(e.getKey()) - cx) <= v[0] && Math.abs(ChunkPos.getZ(e.getKey()) - cz) <= v[0]) {
                out.add(new int[]{ANY | 1 << K_MANSION, v[1], v[2]});
            }
        }
        return out;
    }

    /** The heights a kind of give-away block was found at in a chunk. */
    private static final class Span {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;

        void add(int y) {
            min = Math.min(min, y);
            max = Math.max(max, y);
        }

        boolean any() {
            return min <= max;
        }

        Span with(Span o) {
            Span s = new Span();
            s.min = Math.min(min, o.min);
            s.max = Math.max(max, o.max);
            return s;
        }
    }

    private static void area(ChunkData data, int radius, int kind, Span span) {
        data.structureRadius = Math.max(data.structureRadius, radius);
        data.areas.add(new Area(radius, ANY | (kind >= 0 ? 1 << kind : 0), span.min, span.max));
    }

    // ---- Biomes ----------------------------------------------------------------------------------

    /** True if this building material occurs naturally in the biome at that spot. */
    private String biomeAt(BlockPos pos) {
        return mc.level.getBiome(pos).unwrapKey().map(k -> k.identifier().getPath()).orElse("");
    }

    /** Cold biomes: snow, ice, glaciers, icebergs, frozen peaks and oceans. */
    private static boolean coldBiome(String biome) {
        return biome.contains("snow") || biome.contains("frozen") || biome.contains("ice") || biome.contains("grove")
            || biome.contains("peaks") || biome.contains("slopes") || biome.contains("iceberg");
    }

    /** Blocks that generate on their own in this dimension (so they're never a sign of a player there). */
    private static boolean naturalForDimension(Block b, String id, ResourceKey<Level> dim) {
        if (dim == Level.NETHER) {
            // Nether terrain, bastions, fortresses, ruined portals and fossils
            return id.contains("netherrack") || id.contains("blackstone") || id.contains("basalt") || id.startsWith("soul_")
                || id.contains("magma") || id.contains("glowstone") || id.contains("wart_block") || id.contains("shroomlight")
                || id.contains("nylium") || id.endsWith("_stem") || id.endsWith("_hyphae") || id.contains("nether_brick")
                || id.contains("quartz_ore") || id.contains("obsidian") || b == Blocks.GOLD_BLOCK || id.endsWith("chain")
                || b == Blocks.BONE_BLOCK || b == Blocks.GRAVEL || b == Blocks.LAVA || id.contains("lantern");
        }
        if (dim == Level.END) {
            // End islands, cities and ships
            return id.contains("end_stone") || id.contains("purpur") || b == Blocks.END_ROD || id.contains("obsidian")
                || id.endsWith("stained_glass") || id.endsWith("stained_glass_pane") || b == Blocks.LADDER || id.endsWith("_banner");
        }
        return false;
    }

    /**
     * A mineshaft's support: wooden fences two high on both sides of a 3-wide corridor with planks across the top. Rails
     * and cobwebs (the other give-aways) are often gone, and badlands mineshafts come up to the surface, in dark oak.
     */
    private static boolean mineshaftSupport(LevelChunk chunk, BlockPos foot) {
        if (!chunk.getBlockState(foot.above()).is(net.minecraft.tags.BlockTags.WOODEN_FENCES)) return false;
        if (!chunk.getBlockState(foot.above(2)).is(net.minecraft.tags.BlockTags.PLANKS)) return false;
        for (net.minecraft.core.Direction d : new net.minecraft.core.Direction[]{net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.SOUTH}) {
            BlockPos other = foot.relative(d, 2);
            if ((other.getX() >> 4) != chunk.getPos().x || (other.getZ() >> 4) != chunk.getPos().z) continue;
            if (chunk.getBlockState(other).is(net.minecraft.tags.BlockTags.WOODEN_FENCES)
                    && chunk.getBlockState(other.above()).is(net.minecraft.tags.BlockTags.WOODEN_FENCES)
                    && chunk.getBlockState(foot.relative(d).above(2)).is(net.minecraft.tags.BlockTags.PLANKS)
                    && chunk.getBlockState(other.above(2)).is(net.minecraft.tags.BlockTags.PLANKS)
                    && !chunk.getBlockState(foot.relative(d)).isSolidRender()) return true;
        }
        return false;
    }

    private boolean neighboursLoaded(int cx, int cz, int r) {
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (mc.level.getChunkSource().getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false) == null) return false;
        }
        return true;
    }

    private boolean touchesFluid(BlockPos pos) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            if (!mc.level.getFluidState(pos.relative(d)).isEmpty()) return true;
        }
        return false;
    }

    private record CellBlock(long cell, Block block) {
    }

    /**
     * True if this block is natural for the biome it's in or for one close by. Biome borders aren't on the 4-block grid
     * the game stores biomes in (each block's biome is picked with some jitter), terracotta bands and snow run a little
     * past them, and icebergs spill out of frozen oceans onto the shore, so one look at one spot isn't enough.
     */
    private boolean naturalNear(BlockState st, BlockPos pos, Map<Long, String> noise) {
        Block b = st.getBlock();
        int r = b == Blocks.ICE || b == Blocks.PACKED_ICE || b == Blocks.BLUE_ICE ? 4 : 2; // in 4-block cells
        int qx = pos.getX() >> 2, qy = pos.getY() >> 2, qz = pos.getZ() >> 2;
        for (int dy = -1; dy <= 1; dy++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            int x = qx + dx, y = qy + dy, z = qz + dz;
            String biome = noise.computeIfAbsent(BlockPos.asLong(x, y, z), k -> mc.level.getNoiseBiome(x, y, z).unwrapKey()
                    .map(key -> key.identifier().getPath()).orElse(""));
            if (naturalForBiome(st, biome)) return true;
        }
        return false;
    }

    private static boolean naturalForBiome(BlockState s, String biome) {
        Block b = s.getBlock();
        String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
        boolean ocean = biome.contains("ocean") || biome.contains("beach");
        if (b == Blocks.SNOW_BLOCK || b == Blocks.POWDER_SNOW) return coldBiome(biome);
        if (b == Blocks.ICE || b == Blocks.PACKED_ICE || b == Blocks.BLUE_ICE) return coldBiome(biome) || biome.contains("river");
        if (id.endsWith("terracotta") && !id.endsWith("glazed_terracotta")) return biome.contains("badlands");
        if (id.contains("prismarine") || b == Blocks.SEA_LANTERN) return biome.contains("ocean");
        if (b == Blocks.MOSSY_COBBLESTONE) return biome.contains("old_growth") && biome.contains("taiga"); // forest rocks
        // Shipwrecks and ocean ruins: wood, wooden doors/trapdoors/fences and stone bricks in oceans and on beaches
        if (ocean && (id.endsWith("_planks") || id.endsWith("_fence") || id.endsWith("_door") || id.endsWith("_trapdoor")
            || id.contains("stone_brick") || b == Blocks.CHISELED_STONE_BRICKS)) return true;
        return false;
    }


    private void scan(long key) {
        int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
        LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null) { byChunk.remove(key); return; }
        ResourceKey<Level> dim = mc.level.dimension();
        boolean bio = biomeCheck.get(), wd = wrongDimension.get();
        // building materials depend on the biome and on structures nearby: the client reads the biome of a chunk it
        // hasn't got (past your render distance) as plains, and a village's outer houses can be in view while the bell
        // and paths that give it away aren't. So chunks near the edge wait until those around them have loaded.
        boolean com = common.get() && neighboursLoaded(cx, cz, 2);
        ChunkData data = new ChunkData();
        Set<Block> jobSites = new HashSet<>();
        Span jobs = new Span(), path = new Span(), cobweb = new Span(), rail = new Span(), tnt = new Span(), chiseledSand = new Span(),
            mossyCobble = new Span(), chiseledBricks = new Span(), snow = new Span(), bed = new Span(), deepslateBricks = new Span(),
            deepslateTiles = new Span(), support = new Span();
        Span[] deepBricks = {new Span(), new Span(), new Span()}; // stone / mossy / cracked stone bricks below y 40 (strongholds)
        Map<Integer, Span> markerSpans = new HashMap<>(); // by radius * 16 + kind + 1
        Map<Long, String> noise = new HashMap<>();
        Map<CellBlock, Boolean> naturalCache = new HashMap<>();

        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            if (s == null || s.hasOnlyAir()) continue;
            if (!s.maybeHas(st -> STRONG.contains(st.getBlock()) || (wd && wrongDim(st, dim))
                || (com && COMMON.contains(st.getBlock())) || isMarkerCandidate(st))) continue;
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            // Biome is looked up per 4x4x4 cell (the size the game stores biomes in), only for blocks that need it
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState st = s.getBlockState(x, y, z);
                if (st.isAir()) continue;
                Block b = st.getBlock();
                BlockPos pos = new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z);

                int mr = markerRadius(st, dim);
                int py = pos.getY();
                if (mr > 0) {
                    data.markers.add(pos);
                    int k = markerKind(st, dim);
                    markerSpans.computeIfAbsent(mr * 16 + k + 1, q -> new Span()).add(py);
                }
                if (JOB_SITES.contains(b)) { jobSites.add(b); jobs.add(py); }
                if (b == Blocks.DIRT_PATH) path.add(py);
                if (b == Blocks.COBWEB) cobweb.add(py);
                if (b == Blocks.RAIL) rail.add(py);
                if (st.is(net.minecraft.tags.BlockTags.WOODEN_FENCES) && mineshaftSupport(chunk, pos)) support.add(py);
                if (b == Blocks.TNT) tnt.add(py);
                if (b == Blocks.CHISELED_SANDSTONE) chiseledSand.add(py);
                if (b == Blocks.MOSSY_COBBLESTONE) mossyCobble.add(py);
                if (b == Blocks.CHISELED_STONE_BRICKS) chiseledBricks.add(py);
                if (b == Blocks.SNOW_BLOCK) snow.add(py);
                if (st.is(net.minecraft.tags.BlockTags.BEDS)) bed.add(py);
                if (py < 0 && b == Blocks.DEEPSLATE_BRICKS) deepslateBricks.add(py);
                if (py < 0 && b == Blocks.DEEPSLATE_TILES) deepslateTiles.add(py);
                if (py < 40) {
                    if (b == Blocks.STONE_BRICKS) deepBricks[0].add(py);
                    if (b == Blocks.MOSSY_STONE_BRICKS) deepBricks[1].add(py);
                    if (b == Blocks.CRACKED_STONE_BRICKS) deepBricks[2].add(py);
                }

                if (STRONG.contains(b)) data.strong.add(new Hit(pos, TYPES.getOrDefault(b, Type.Custom), EXCUSED.getOrDefault(b, 0)));
                else if (wd && wrongDim(st, dim)) data.strong.add(new Hit(pos, Type.WrongDimension, 0));
                else if (com && COMMON.contains(b) && !NEVER_FLAG.contains(b)) {
                    String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
                    if (bio && naturalForDimension(b, id, dim)) continue;
                    if (bio && b == Blocks.COBBLESTONE && touchesFluid(pos)) continue; // lava flowed into water here
                    if (bio) {
                        long cell = BlockPos.asLong(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2);
                        if (naturalCache.computeIfAbsent(new CellBlock(cell, b), k -> naturalNear(st, pos, noise))) continue;
                    }
                    data.materials.add(pos);
                }
            }
        }
        for (Map.Entry<Integer, Span> e : markerSpans.entrySet()) area(data, e.getKey() / 16, e.getKey() % 16 - 1, e.getValue());
        // Structures recognised by combinations of blocks
        if (path.any() && jobSites.size() >= 2) area(data, 4, -1, path.with(jobs));                         // village
        if (rail.any() && cobweb.any()) area(data, 3, -1, rail.with(cobweb));                               // mineshaft
        if (support.any()) area(data, 3, -1, support);                                                      // mineshaft
        if (tnt.any() && chiseledSand.any()) area(data, 2, -1, tnt.with(chiseledSand));                     // desert pyramid
        if (mossyCobble.any() && chiseledBricks.any()) area(data, 2, -1, mossyCobble.with(chiseledBricks)); // jungle temple
        if (snow.any() && bed.any() && biomeAt(new BlockPos(cx << 4, 64, cz << 4)).contains("snow")) area(data, 1, -1, snow.with(bed)); // igloo
        if (deepBricks[0].any() && deepBricks[1].any() && deepBricks[2].any()) {
            area(data, 4, -1, deepBricks[0].with(deepBricks[1]).with(deepBricks[2]));                       // stronghold
        }
        // ancient city: deepslate bricks and tiles together deep down (no other structure has both; sculk shriekers,
        // which used to count, grow all over the deep dark on their own, under most big mountains)
        if (deepslateBricks.any() && deepslateTiles.any()) area(data, 3, K_ANCIENT_CITY, deepslateBricks.with(deepslateTiles));

        if (data.strong.isEmpty() && data.materials.isEmpty() && data.structureRadius < 0) { byChunk.remove(key); return; }
        ChunkData before = byChunk.put(key, data);
        if (data.structureRadius >= 0 || (before != null && before.structureRadius >= 0)) structureCache.clear();
    }
}
