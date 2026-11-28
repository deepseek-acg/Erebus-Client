package dev.erebus.client.module.basefinding;

import dev.erebus.client.map.MapStore;
import dev.erebus.client.module.Category;
import dev.erebus.client.module.Module;
import dev.erebus.client.module.WorldRender;
import dev.erebus.client.module.world.MapModule;
import dev.erebus.client.notify.Notifications;
import dev.erebus.client.render.Projection;
import dev.erebus.client.render.Visuals;
import dev.erebus.client.render.font.Icon;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.NumberSetting;
import dev.erebus.client.util.BlockChanges;
import dev.erebus.client.util.Chat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds light that nature doesn't explain: torches, lanterns, glowstone and the like where the world wouldn't have put
 * them, and lit space underground or indoors that no natural source lights.
 *
 * <p>Every light-giving block in a loaded chunk is sorted into natural (lava, fire, magma, glow lichen, glow berries,
 * amethyst, sculk...; glowstone and shroomlight in the Nether, End rods in the End) or player-made. Player-made
 * kinds that natural structures also have (torches, lanterns, campfires, candles, copper bulbs, sea lanterns) are
 * excused near one: villages, strongholds, trial chambers, ancient cities, bastions, End cities, monuments, outposts
 * and mansions, recognised from their give-away blocks, illagers, or the Map module's structure marks.
 *
 * <p>Then the light itself: the block light the client already has for every chunk is read, and enclosed air (with
 * blocks above it) lit at least to the chosen level is counted unless a natural or excused source explains it. Light
 * drops by one per block, so a source emitting e only explains a spot lit to L within e - L blocks; anything else
 * there was lit by something a player put down. A big lit volume underground is a base or a tunnel network even when
 * the lights themselves are few.
 *
 * <p>Signs it was used recently stand out: lit furnaces, smokers and blast furnaces (they burn out on their own),
 * beacons, conduits, lit nether portals and charged respawn anchors, and light above the Nether roof. Lights placed
 * while you're nearby are caught as the server sends them, which shows someone working out of sight.
 */
public final class LightFinder extends Module {
    /** Player-made light, by kind. Contextual kinds also generate in natural structures. */
    enum Kind {
        Torches("Torches", "torch", 0xFFFFB347, 1, true),
        Lanterns("Lanterns", "lantern", 0xFFFFD27A, 1, true),
        GlowBlocks("Glow blocks", "glow block", 0xFFFFF3A0, 1.5, true),
        Fires("Campfires and candles", "campfire or candle", 0xFFFF8A3D, 0.7, true),
        Furnaces("Lit furnaces", "lit furnace", 0xFFFF5A3C, 3, false),
        Special("Beacons, portals, anchors", "beacon, portal or anchor", 0xFFB46CFF, 4, false),
        Other("Other lights", "other light", 0xFFE0E0E0, 1, true);

        final String title;
        final String one;
        final int color;
        final double weight;
        final boolean contextual;

        Kind(String title, String one, int color, double weight, boolean contextual) {
            this.title = title;
            this.one = one;
            this.color = color;
            this.weight = weight;
            this.contextual = contextual;
        }
    }

    /** {@code contextual}: natural structures generate this block too, so it's excused near one. */
    private record Source(BlockPos pos, Kind kind, int emission, boolean contextual) {
    }

    /** What one chunk holds: player-made sources, natural sources, give-away markers and lit cells (packed). */
    private static final class ChunkLight {
        final List<Source> sources = new ArrayList<>();
        final List<int[]> natural = new ArrayList<>(); // x, y, z, emission
        int markerRadius = -1;
        int[] lit = new int[0]; // x | z << 4 | (y - minY) << 8 | light << 24
        // evaluated
        int unexplained;
        AABB litBox;
        double weight;
        boolean active, roof, excused, flagged;
        final EnumMap<Kind, Integer> counts = new EnumMap<>(Kind.class);
    }

    private record Fresh(BlockPos pos, Kind kind, long at, String what) {
    }

    private final NumberSetting minLight;
    private final BoolSetting outdoors;
    private final NumberSetting minSources;
    private final NumberSetting minLit;
    private final Map<Kind, BoolSetting> kindOn = new EnumMap<>(Kind.class);
    private final BoolSetting structures;
    private final NumberSetting spawnRadius;
    private final BoolSetting highways;
    private final NumberSetting highwayWidth;
    private final BoolSetting alerts;
    private final NumberSetting alertLights;
    private final NumberSetting alertLit;
    private final BoolSetting freshAlerts;
    private final BoolSetting logFinds;
    private final BoolSetting showSources;
    private final BoolSetting showLit;
    private final NumberSetting range;

    private final Map<Long, ChunkLight> chunks = new HashMap<>();
    private final Set<Long> queue = new LinkedHashSet<>();
    private final Set<Long> dirty = new HashSet<>();
    private final Map<Long, Integer> illagers = new HashMap<>();
    private final List<Fresh> fresh = new ArrayList<>();
    private final Map<Long, Long> freshNotified = new HashMap<>();
    private ResourceKey<Level> dim;
    private int ticks;
    private int litChunks;
    private static LightFinder instance;

    public LightFinder() {
        super("Light Finder", "Finds light nature doesn't explain: player-placed lights and lit space underground or indoors, ignoring "
                + "lava, glow lichen, Nether glowstone and the lights of natural structures. Flags lit furnaces, beacons, portals and "
                + "lights above the Nether roof, and catches lights being placed nearby.", Category.BASEFINDING);
        group("Light");
        minLight = add(new NumberSetting("Min light", "Block light (0-15) a spot needs to count as lit. A torch gives 14 on itself and "
                + "loses one per block.", 10, 1, 15, 1));
        outdoors = add(new BoolSetting("Count outdoors", "Also count lit space open to the sky (off: only underground or under a roof, "
                + "where light is a far stronger sign).", false));
        minSources = add(new NumberSetting("Min lights per chunk", "Player-made lights a chunk needs to be marked (beacons, lit furnaces "
                + "and portals count for several).", 3, 1, 50, 1));
        minLit = add(new NumberSetting("Min lit blocks per chunk", "Unexplained lit blocks a chunk needs to be marked, however few lights "
                + "there are.", 60, 5, 2000, 5));
        group("Kinds");
        for (Kind k : Kind.values()) kindOn.put(k, add(new BoolSetting(k.title, true)));
        group("Filters");
        structures = add(new BoolSetting("Ignore structures", "Torches, lanterns, campfires, candles, copper bulbs and sea lanterns near "
                + "villages, strongholds, trial chambers, ancient cities, bastions, End cities, monuments, outposts and mansions don't count.",
                true));
        spawnRadius = add(new NumberSetting("Ignore near spawn", "Skip everything within this many blocks of 0, 0 (every block there is "
                + "player-made on an old server). 0 = off.", 0, 0, 20000, 100).unit(" blocks"));
        highways = add(new BoolSetting("Ignore highways", "Skip the axes and diagonals, where travellers light the way.", false));
        highwayWidth = add(new NumberSetting("Highway width", "How far either side of a highway to skip.", 8, 1, 64, 1).unit(" blocks")
                .visibleWhen(highways::get));
        group("Alerts");
        alerts = add(new BoolSetting("Alerts", "Alert when an area looks lived in: many lights, a big unexplained lit volume, a lit "
                + "furnace, or lights above the Nether roof. Marked on the map.", true));
        alertLights = add(new NumberSetting("Alert lights", "Player-made lights an area needs for an alert.", 12, 2, 200, 1)
                .visibleWhen(alerts::get));
        alertLit = add(new NumberSetting("Alert lit blocks", "Or unexplained lit blocks.", 400, 20, 20000, 20).visibleWhen(alerts::get));
        freshAlerts = add(new BoolSetting("New light alerts", "Alert when a light is placed (or a furnace lit) within loaded chunks and not "
                + "by you: someone is there right now.", true));
        logFinds = add(new BoolSetting("Log to file", "Write alerts to .minecraft/erebus/finds.txt.", true));
        group("Show");
        showSources = add(new BoolSetting("Show lights", "Outline player-made lights in marked chunks, in their kind's color.", true));
        showLit = add(new BoolSetting("Show lit space", "Shade the unexplained lit space in marked chunks.", true));
        range = add(new NumberSetting("Range", "How far away things are drawn.", 160, 32, 512, 16).unit(" blocks"));
        addVisuals("Lights glow with a soft halo, marked areas get a beam and a label with what was found, and new lights pulse.");
        instance = this;
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            LightFinder m = instance;
            if (m != null && m.isEnabled()) m.queue.add(chunk.getPos().toLong());
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            LightFinder m = instance;
            if (m != null) m.chunks.remove(chunk.getPos().toLong());
        });
        BlockChanges.listen((pos, before, after) -> {
            LightFinder m = instance;
            if (m != null && m.isEnabled()) m.blockChanged(pos, before, after);
        });
    }

    @Override
    protected void onEnable() {
        reset();
    }

    @Override
    protected void onDisable() {
        reset();
        info = null;
    }

    private void reset() {
        chunks.clear();
        queue.clear();
        dirty.clear();
        illagers.clear();
        fresh.clear();
        dim = null;
    }

    // =====================================================================================================
    // What's natural
    // =====================================================================================================

    /** The player-made kind of a light-giving block, or null if it's natural (or gives no light). */
    static Kind classify(BlockState st, ResourceKey<Level> dim) {
        int e = st.getLightEmission();
        if (e <= 0) return null;
        Block b = st.getBlock();
        String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
        if (b == Blocks.LAVA || b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.MAGMA_BLOCK || b == Blocks.GLOW_LICHEN
                || b == Blocks.CAVE_VINES || b == Blocks.CAVE_VINES_PLANT || b == Blocks.SEA_PICKLE || id.contains("amethyst")
                || id.contains("sculk") || b == Blocks.BROWN_MUSHROOM || b == Blocks.DRAGON_EGG || b == Blocks.END_PORTAL
                || b == Blocks.END_PORTAL_FRAME || b == Blocks.END_GATEWAY || b == Blocks.CRYING_OBSIDIAN || id.contains("redstone_ore")
                || b == Blocks.TRIAL_SPAWNER || b == Blocks.VAULT || b == Blocks.BREWING_STAND || id.contains("creaking")
                || id.contains("firefly")) {
            return null;
        }
        if (dim == Level.NETHER && (b == Blocks.GLOWSTONE || b == Blocks.SHROOMLIGHT || id.contains("lantern"))) return null;
        if (dim == Level.END && b == Blocks.END_ROD) return null;
        if (id.endsWith("torch")) return Kind.Torches;
        if (id.contains("lantern") && b != Blocks.SEA_LANTERN && b != Blocks.JACK_O_LANTERN) return Kind.Lanterns;
        if (b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER) return Kind.Furnaces; // only give light while lit
        if (id.contains("campfire") || id.contains("candle")) return Kind.Fires;
        if (b == Blocks.BEACON || b == Blocks.CONDUIT || b == Blocks.NETHER_PORTAL || b == Blocks.RESPAWN_ANCHOR || b == Blocks.ENCHANTING_TABLE) {
            return Kind.Special;
        }
        if (b == Blocks.GLOWSTONE || b == Blocks.SEA_LANTERN || b == Blocks.SHROOMLIGHT || id.contains("froglight") || b == Blocks.JACK_O_LANTERN
                || b == Blocks.REDSTONE_LAMP || b == Blocks.END_ROD || id.contains("copper_bulb") || b == Blocks.LIGHT) {
            return Kind.GlowBlocks;
        }
        return e >= 6 ? Kind.Other : null;
    }

    /**
     * Whether natural structures generate this light too (so it's excused near one): torches, lanterns, campfires and
     * candles, sea lanterns (monuments), copper bulbs (trial chambers), End rods (End cities). Glowstone, froglights,
     * jack o'lanterns, redstone lamps and the like never generate in one.
     */
    static boolean contextual(BlockState st, Kind k) {
        if (k == Kind.GlowBlocks) {
            Block b = st.getBlock();
            return b == Blocks.SEA_LANTERN || b == Blocks.END_ROD || BuiltInRegistries.BLOCK.getKey(b).getPath().contains("copper_bulb");
        }
        return k.contextual;
    }

    /** Skipped by position: near spawn, or on a highway. */
    private boolean filtered(int x, int z) {
        double r = spawnRadius.get();
        if (r > 0 && (double) x * x + (double) z * z < r * r) return true;
        if (!highways.get() || dim == Level.END) return false;
        int w = highwayWidth.intValue();
        return Math.abs(x) <= w || Math.abs(z) <= w || Math.abs(Math.abs(x) - Math.abs(z)) <= w;
    }

    /** Whether this chunk is part of a natural structure that has lights of its own. */
    private boolean inStructure(int cx, int cz) {
        if (!structures.get()) return false;
        for (int dx = -7; dx <= 7; dx++) {
            for (int dz = -7; dz <= 7; dz++) {
                ChunkLight c = chunks.get(ChunkPos.asLong(cx + dx, cz + dz));
                if (c != null && c.markerRadius >= Math.max(Math.abs(dx), Math.abs(dz))) return true;
                Integer il = illagers.get(ChunkPos.asLong(cx + dx, cz + dz));
                if (il != null && il >= Math.max(Math.abs(dx), Math.abs(dz))) return true;
            }
        }
        if (MapStore.ready()) {
            double bx = (cx << 4) + 8, bz = (cz << 4) + 8;
            for (MapStore.Mark m : MapStore.alerts()) {
                if (!m.kind().equals("structure")) continue;
                int r = litStructureRadius(m.label());
                if (r > 0 && Math.abs(m.x() - bx) <= r && Math.abs(m.z() - bz) <= r) return true;
            }
        }
        return false;
    }

    /** How far around a structure the Map module recorded its lights reach, or 0 for structures without lights. */
    private static int litStructureRadius(String title) {
        for (MapModule.Structure s : MapModule.Structure.values()) {
            if (!s.title.equals(title)) continue;
            return switch (s) {
                case TrialChamber, AncientCity, Stronghold, OceanMonument, Bastion, EndCity, Archaeology -> s.radius + 16;
                default -> 0;
            };
        }
        return 0;
    }

    // =====================================================================================================
    // Scanning
    // =====================================================================================================

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) return;
        if (dim != mc.level.dimension()) {
            reset();
            dim = mc.level.dimension();
            queueLoaded();
        }
        ticks++;
        if (ticks % 200 == 0) queueLoaded(); // pick up changes (and light that settled) now and then
        if (ticks % 20 == 0) {
            illagers.clear();
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e instanceof AbstractIllager) illagers.merge(ChunkPos.asLong(e.blockPosition()), 4, Math::max); // outposts, mansions
            }
        }
        for (int n = 0; n < 4 && !queue.isEmpty(); n++) {
            long k = queue.iterator().next();
            queue.remove(k);
            scan(ChunkPos.getX(k), ChunkPos.getZ(k));
        }
        if (ticks % 5 == 0 && !dirty.isEmpty()) {
            // a bounded number per pass, nearest first, so a burst of loaded chunks doesn't stall a frame
            ChunkPos me = mc.player.chunkPosition();
            List<Long> todo = new ArrayList<>(dirty);
            todo.sort((a, b) -> Integer.compare(cdist(a, me), cdist(b, me)));
            for (int i = 0; i < Math.min(24, todo.size()); i++) {
                evaluate(todo.get(i));
                dirty.remove(todo.get(i));
            }
            litChunks = (int) chunks.values().stream().filter(c -> c.flagged).count();
            if (alerts.get()) checkAlerts();
        }
        fresh.removeIf(f -> System.currentTimeMillis() - f.at > 120_000);
        info = litChunks > 0 ? litChunks + (litChunks == 1 ? " chunk" : " chunks") : null;
    }

    private static int cdist(long k, ChunkPos me) {
        return Math.max(Math.abs(ChunkPos.getX(k) - me.x), Math.abs(ChunkPos.getZ(k) - me.z));
    }

    private void queueLoaded() {
        int r = mc.options.getEffectiveRenderDistance() + 1;
        ChunkPos c = mc.player.chunkPosition();
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) queue.add(ChunkPos.asLong(c.x + x, c.z + z));
    }

    private void scan(int cx, int cz) {
        LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        long key = ChunkPos.asLong(cx, cz);
        if (chunk == null) {
            chunks.remove(key);
            return;
        }
        ChunkLight c = new ChunkLight();
        int minY = mc.level.getMinY();
        int[] surface = new int[256];
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) surface[x * 16 + z] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        var blockLight = mc.level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        int threshold = minLight.intValue();
        boolean open = outdoors.get();
        List<Integer> lit = new ArrayList<>();
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            int sy = chunk.getSectionYFromSectionIndex(i), baseY = sy << 4;
            boolean emitters = s != null && !s.hasOnlyAir() && s.maybeHas(st -> st.getLightEmission() > 0);
            boolean markers = s != null && !s.hasOnlyAir() && s.maybeHas(st -> markerRadius(st) > 0);
            DataLayer light = blockLight.getDataLayerData(SectionPos.of(cx, sy, cz));
            boolean anyLight = light != null && !light.isEmpty();
            if (!emitters && !markers && !anyLight) continue;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState st = s == null ? Blocks.AIR.defaultBlockState() : s.getBlockState(x, y, z);
                        int wx = (cx << 4) + x, wy = baseY + y, wz = (cz << 4) + z;
                        if (markers) c.markerRadius = Math.max(c.markerRadius, markerRadius(st));
                        if (emitters) {
                            int e = st.getLightEmission();
                            if (e > 0) {
                                Kind k = classify(st, dim);
                                if (k == Kind.Torches && mineshaftTorch(new BlockPos(wx, wy, wz), st)) k = null;
                                if (k == null) c.natural.add(new int[]{wx, wy, wz, e});
                                else if (!filtered(wx, wz)) c.sources.add(new Source(new BlockPos(wx, wy, wz), k, e, contextual(st, k)));
                            }
                        }
                        if (anyLight && st.isAir()) {
                            int l = light.get(x, y, z);
                            if (l >= threshold && (open || wy < surface[x * 16 + z]) && !filtered(wx, wz)) {
                                lit.add(x | z << 4 | (wy - minY) << 8 | l << 24);
                            }
                        }
                    }
                }
            }
        }
        c.lit = lit.stream().mapToInt(Integer::intValue).toArray();
        chunks.put(key, c);
        // this chunk's sources and markers change what's explained around it
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) dirty.add(ChunkPos.asLong(cx + dx, cz + dz));
        if (c.markerRadius > 0) {
            int r = c.markerRadius;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) dirty.add(ChunkPos.asLong(cx + dx, cz + dz));
        }
    }

    /**
     * Mineshaft corridors hang wall torches on their support beams: a wall torch on oak or dark oak planks with
     * mineshaft woodwork, rails or cobwebs right around it is the mineshaft's own.
     */
    private boolean mineshaftTorch(BlockPos pos, BlockState st) {
        if (!st.is(Blocks.WALL_TORCH) || !st.hasProperty(net.minecraft.world.level.block.WallTorchBlock.FACING)) return false;
        BlockState support = mc.level.getBlockState(pos.relative(st.getValue(net.minecraft.world.level.block.WallTorchBlock.FACING).getOpposite()));
        if (!support.is(Blocks.OAK_PLANKS) && !support.is(Blocks.DARK_OAK_PLANKS)) return false;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 1; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockState b = mc.level.getBlockState(p.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz));
                    if (b.is(Blocks.OAK_FENCE) || b.is(Blocks.DARK_OAK_FENCE) || b.is(Blocks.RAIL) || b.is(Blocks.COBWEB)) return true;
                }
            }
        }
        return false;
    }

    /** Give-away blocks of natural structures with lights (a spawner or a ruined portal says nothing about lights). */
    private int markerRadius(BlockState st) {
        Block b = st.getBlock();
        if (b == Blocks.SPAWNER || b == Blocks.CRYING_OBSIDIAN) return 0;
        return PlayerBlocks.markerRadius(st, dim);
    }

    /** Works out what in this chunk nature (or a structure) doesn't explain. */
    private void evaluate(long key) {
        ChunkLight c = chunks.get(key);
        if (c == null) return;
        int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
        c.excused = inStructure(cx, cz);
        c.counts.clear();
        c.weight = 0;
        c.active = c.roof = false;
        // what explains light here: natural sources, and excused or switched-off ones, in this chunk and around it, by
        // position (a source only reaches a spot lit to L within 15 - L blocks, so only a few positions need looking at)
        Map<Long, Integer> explain = new HashMap<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ChunkLight n = chunks.get(ChunkPos.asLong(cx + dx, cz + dz));
                if (n == null) continue;
                for (int[] e : n.natural) explain.merge(BlockPos.asLong(e[0], e[1], e[2]), e[3], Math::max);
                boolean ex = n == c ? c.excused : inStructure(cx + dx, cz + dz);
                for (Source s : n.sources) {
                    if ((ex && s.contextual) || !kindOn.get(s.kind).get()) {
                        explain.merge(s.pos.asLong(), s.emission, Math::max);
                    }
                }
            }
        }
        for (Source s : c.sources) {
            if ((c.excused && s.contextual) || !kindOn.get(s.kind).get()) continue;
            c.counts.merge(s.kind, 1, Integer::sum);
            c.weight += s.kind.weight;
            if (s.kind == Kind.Furnaces) c.active = true;
            if (dim == Level.NETHER && s.pos.getY() >= 128) c.roof = true;
        }
        // light crosses chunk borders: until every neighbour has been read, its sources are unknown and the lit space here
        // can't be judged (the edge of what you have loaded would light up otherwise)
        boolean neighbours = true;
        for (int dx = -1; dx <= 1 && neighbours; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!chunks.containsKey(ChunkPos.asLong(cx + dx, cz + dz))) {
                    neighbours = false;
                    break;
                }
            }
        }
        int minY = mc.level.getMinY();
        int unexplained = 0;
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
        for (int p : neighbours ? c.lit : new int[0]) {
            int x = (cx << 4) + (p & 15), z = (cz << 4) + ((p >> 4) & 15), y = ((p >> 8) & 0xFFFF) + minY, l = (p >>> 24) & 15;
            boolean explained = false;
            if (!explain.isEmpty()) {
                for (int[] o : ball(15 - l)) {
                    Integer e = explain.get(BlockPos.asLong(x + o[0], y + o[1], z + o[2]));
                    if (e != null && e - o[3] >= l) {
                        explained = true;
                        break;
                    }
                }
            }
            if (explained) continue;
            unexplained++;
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            z0 = Math.min(z0, z);
            x1 = Math.max(x1, x + 1);
            y1 = Math.max(y1, y + 1);
            z1 = Math.max(z1, z + 1);
        }
        c.unexplained = unexplained;
        c.litBox = unexplained > 0 ? new AABB(x0, y0, z0, x1, y1, z1) : null;
        if (dim == Level.NETHER && c.litBox != null && c.litBox.maxY > 128 && c.weight > 0) c.roof = true;
        c.flagged = c.weight >= minSources.get() || unexplained >= minLit.get() || c.roof || c.active;
    }

    private static final List<int[]>[] BALLS = new List[16];

    /** Offsets within Manhattan distance r (with the distance), nearest first. */
    private static List<int[]> ball(int r) {
        r = Math.max(0, Math.min(15, r));
        List<int[]> b = BALLS[r];
        if (b != null) return b;
        b = new ArrayList<>();
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    int d = Math.abs(x) + Math.abs(y) + Math.abs(z);
                    if (d <= r) b.add(new int[]{x, y, z, d});
                }
            }
        }
        b.sort((a, c) -> Integer.compare(a[3], c[3]));
        BALLS[r] = b;
        return b;
    }

    // =====================================================================================================
    // Areas and alerts
    // =====================================================================================================

    private record Area(List<Long> chunks, double weight, int lit, boolean active, boolean roof, EnumMap<Kind, Integer> counts, AABB box) {
    }

    /** Marked chunks grouped with their neighbours. */
    private List<Area> areas() {
        List<Area> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Map.Entry<Long, ChunkLight> e : chunks.entrySet()) {
            if (!e.getValue().flagged || !seen.add(e.getKey())) continue;
            List<Long> group = new ArrayList<>();
            ArrayDeque<Long> stack = new ArrayDeque<>();
            stack.push(e.getKey());
            while (!stack.isEmpty()) {
                long k = stack.pop();
                group.add(k);
                int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long n = ChunkPos.asLong(cx + dx, cz + dz);
                        ChunkLight c = chunks.get(n);
                        if (c != null && c.flagged && seen.add(n)) stack.push(n);
                    }
                }
            }
            double w = 0;
            int lit = 0;
            boolean active = false, roof = false;
            EnumMap<Kind, Integer> counts = new EnumMap<>(Kind.class);
            AABB box = null;
            for (long k : group) {
                ChunkLight c = chunks.get(k);
                w += c.weight;
                lit += c.unexplained;
                active |= c.active;
                roof |= c.roof;
                c.counts.forEach((kind, n) -> counts.merge(kind, n, Integer::sum));
                for (Source s : c.sources) if (c.counts.containsKey(s.kind)) box = grow(box, new AABB(s.pos));
                if (c.litBox != null) box = grow(box, c.litBox);
            }
            if (box != null) out.add(new Area(group, w, lit, active, roof, counts, box));
        }
        return out;
    }

    private static AABB grow(AABB a, AABB b) {
        return a == null ? b : a.minmax(b);
    }

    private void checkAlerts() {
        for (Area a : areas()) {
            boolean worth = a.weight >= alertLights.get() || a.lit >= alertLit.get() || (a.roof && a.weight >= 2) || (a.active && a.weight >= 4);
            if (!worth || BaseFinds.alreadyFound(dim, a.box)) continue;
            String kind = a.roof ? "Nether roof base" : a.active ? "Active base" : "Lit area";
            BaseFinds.post(kind, dim, a.box, describe(a), details(a), logFinds.get());
        }
    }

    private static String describe(Area a) {
        List<String> parts = new ArrayList<>();
        int lights = a.counts.values().stream().mapToInt(Integer::intValue).sum();
        if (lights > 0) {
            List<String> kinds = new ArrayList<>();
            a.counts.forEach((k, n) -> kinds.add(n + " " + (n == 1 ? k.one : k.title.toLowerCase(Locale.ROOT))));
            parts.add(lights + " lights (" + String.join(", ", kinds) + ")");
        }
        if (a.lit > 0) parts.add(a.lit + " lit blocks nothing natural explains");
        parts.add("down to y " + (int) a.box.minY);
        return String.join(", ", parts);
    }

    private static String details(Area a) {
        int lights = a.counts.values().stream().mapToInt(Integer::intValue).sum();
        return String.format(Locale.ROOT, "lights=%d lit-blocks=%d active=%b nether-roof=%b min-y=%d", lights, a.lit, a.active, a.roof,
                (int) a.box.minY);
    }

    // =====================================================================================================
    // Lights appearing now
    // =====================================================================================================

    private void blockChanged(BlockPos pos, BlockState before, BlockState after) {
        if (mc.player == null || mc.level == null || dim == null) return;
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        queue.add(key); // rescan it soon
        if (after.getLightEmission() <= before.getLightEmission()) return;
        Kind k = classify(after, dim);
        if (k == null || !kindOn.get(k).get() || filtered(pos.getX(), pos.getZ())) return;
        if (contextual(after, k) && inStructure(pos.getX() >> 4, pos.getZ() >> 4)) return;
        if (mc.player.blockPosition().distSqr(pos) < 36) return; // most likely your own
        String what = before.getBlock() == after.getBlock() ? name(after) + " lit" : name(after) + " placed";
        fresh.add(new Fresh(pos, k, System.currentTimeMillis(), what));
        if (fresh.size() > 200) fresh.remove(0);
        if (!freshAlerts.get()) return;
        // one alert per area every 30 seconds (redstone lamps on a clock would flood it otherwise)
        long area = ChunkPos.asLong(pos.getX() >> 5, pos.getZ() >> 5);
        Long last = freshNotified.get(area);
        long now = System.currentTimeMillis();
        if (last != null && now - last < 30_000) return;
        freshNotified.put(area, now);
        int dist = (int) Math.sqrt(mc.player.blockPosition().distSqr(pos));
        Notifications.push(name, what + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " · " + dist + "m", Notifications.Kind.WARNING);
        Chat.info(what + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " (" + dist + "m away): someone is there now.");
    }

    private static String name(BlockState st) {
        return st.getBlock().getName().getString();
    }

    // =====================================================================================================
    // Drawing
    // =====================================================================================================

    @Override
    public void onRenderWorld(WorldRender ctx) {
        if (mc.player == null) return;
        Vec3 me = mc.player.position();
        double r2 = range.get() * range.get();
        boolean vis = visuals();
        int budget = 3000;
        for (Map.Entry<Long, ChunkLight> e : chunks.entrySet()) {
            ChunkLight c = e.getValue();
            if (!c.flagged) continue;
            double ccx = (ChunkPos.getX(e.getKey()) << 4) + 8, ccz = (ChunkPos.getZ(e.getKey()) << 4) + 8;
            if ((ccx - me.x) * (ccx - me.x) + (ccz - me.z) * (ccz - me.z) > r2) continue;
            if (showLit.get() && c.litBox != null) {
                Gizmos.cuboid(c.litBox, GizmoStyle.strokeAndFill(0x66FFE27A, 1f, 0x1EFFE27A));
            }
            if (showSources.get()) {
                for (Source s : c.sources) {
                    if (!c.counts.containsKey(s.kind) || budget-- <= 0) continue;
                    AABB b = new AABB(s.pos).deflate(0.2);
                    Gizmos.cuboid(b, GizmoStyle.strokeAndFill(s.kind.color, 1.5f, (0x40 << 24) | (s.kind.color & 0xFFFFFF))).setAlwaysOnTop();
                    if (vis) Visuals.ring(Vec3.atCenterOf(s.pos), 0.45 + 0.1 * Visuals.pulse(1.8), Visuals.fade(s.kind.color, 0.7f), 1.2f, 16);
                }
            }
        }
        if (vis) {
            for (Area a : areas()) {
                Vec3 c = a.box.getCenter();
                if ((c.x - me.x) * (c.x - me.x) + (c.z - me.z) * (c.z - me.z) > r2 * 4) continue;
                int col = a.active ? 0xFFFF5A3C : a.roof ? 0xFFB46CFF : 0xFFFFD27A;
                Visuals.beam(new Vec3(c.x, a.box.minY, c.z), Math.max(40, a.box.getYsize() + 40), Visuals.fade(col, 0.8f), 2f);
            }
        }
        long now = System.currentTimeMillis();
        for (Fresh f : fresh) {
            float t = (now - f.at) / 1000f;
            if (t > 60) continue;
            int col = f.kind.color;
            float pulse = 0.5f + 0.5f * Visuals.pulse(0.8);
            Gizmos.cuboid(new AABB(f.pos).inflate(0.08 + 0.1 * pulse), GizmoStyle.strokeAndFill(col, 2.5f, (Math.round(0x50 * pulse) << 24) | (col & 0xFFFFFF)))
                    .setAlwaysOnTop();
            Gizmos.line(ctx.tracerStart(), Vec3.atCenterOf(f.pos), Visuals.fade(col, 0.8f), 1.5f).setAlwaysOnTop();
        }
    }

    @Override
    public void onRenderOverlay(dev.erebus.client.gui.Ui ui) {
        if (mc.player == null || mc.options.hideGui) return;
        Vec3 me = mc.player.position();
        long now = System.currentTimeMillis();
        for (Fresh f : fresh) {
            long s = (now - f.at) / 1000;
            if (s > 60) continue;
            Visuals.label(ui, Vec3.atCenterOf(f.pos).add(0, 0.8, 0), Icon.TARGET, f.what, s + "s ago", f.kind.color);
        }
        if (!visuals()) return;
        for (Area a : areas()) {
            Vec3 c = a.box.getCenter();
            if (c.distanceTo(me) > range.get()) continue;
            int lights = a.counts.values().stream().mapToInt(Integer::intValue).sum();
            String title = a.roof ? "Above the roof" : a.active ? "Lit furnace" : "Lit area";
            String detail = lights + " lights" + (a.lit > 0 ? " · " + a.lit + " lit" : "") + " · y " + (int) a.box.minY;
            int col = a.active ? 0xFFFF5A3C : a.roof ? 0xFFB46CFF : 0xFFFFD27A;
            float[] p = Projection.toScreen(new Vec3(c.x, a.box.maxY + 2, c.z));
            if (p != null) ui.worldLabel(Icon.EYE, title, detail, col, p[0], p[1] - 16);
        }
    }
}
