package dev.erebus.client.module.world;

import dev.erebus.client.Erebus;
import dev.erebus.client.map.MapRenderer;
import dev.erebus.client.map.MapStore;
import dev.erebus.client.map.Waypoints;
import dev.erebus.client.module.Category;
import dev.erebus.client.module.Module;
import dev.erebus.client.module.WorldRender;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The map: remembers what every chunk you've loaded looks like from above (per server and dimension, saved to disk),
 * recognises structures from their blocks, and keeps the bases and stashes the basefinding modules found.
 * Shown by the Minimap HUD element and the full-screen map (M). Ported from Nyx's Minimap and improved.
 */
public final class MapModule extends Module {
    /** Structures that can be recognised from blocks that never generate anywhere else. */
    public enum Structure {
        TrialChamber("Trial Chamber", 0xFFE0823D, 160), AncientCity("Ancient City", 0xFF2DD4BF, 160), Stronghold("Stronghold", 0xFF86EFAC, 128),
        OceanMonument("Ocean Monument", 0xFF5EEAD4, 72), DesertPyramid("Desert Pyramid", 0xFFFDE68A, 32), JungleTemple("Jungle Temple", 0xFF65A30D, 32),
        SwampHut("Swamp Hut", 0xFF4D7C0F, 24), RuinedPortal("Ruined Portal", 0xFFA855F7, 24), Geode("Amethyst Geode", 0xFFC084FC, 24), Archaeology("Archaeology Site", 0xFFD6B98C, 32),
        NetherFortress("Nether Fortress", 0xFFB91C1C, 128), Bastion("Bastion Remnant", 0xFFFBBF24, 72), EndCity("End City", 0xFFE879F9, 80),
        EndGateway("End Gateway", 0xFF94A3B8, 16);

        public final String title;
        public final int color;
        public final int radius;

        Structure(String title, int color, int radius) {
            this.title = title;
            this.color = color;
            this.radius = radius;
        }
    }

    private final NumberSetting scanSpeed;
    public final BoolSetting showBases;
    public final BoolSetting showStashes;
    public final BoolSetting showStorage;
    public final BoolSetting showSpawners;
    public final BoolSetting showStructures;
    public final BoolSetting showPlayers;
    public final BoolSetting showWaypoints;
    private final BoolSetting autoExplore;
    public final BoolSetting hideExplored;
    private final BoolSetting worldWaypoints;
    private final BoolSetting waypointBeams;
    private final Map<Structure, BoolSetting> findStruct = new EnumMap<>(Structure.class);
    private final BoolSetting announce;
    public final BoolSetting showPredicted;
    private final Map<dev.erebus.client.seed.StructurePredictor.Kind, BoolSetting> predictKinds =
            new EnumMap<>(dev.erebus.client.seed.StructurePredictor.Kind.class);

    private final java.util.Set<Long> structScanned = new java.util.HashSet<>();
    private final Map<Long, Long> scannedAt = new HashMap<>();
    private final List<long[]> order = new ArrayList<>();
    private int orderAt, orderCx = Integer.MIN_VALUE, orderCz, orderR;

    public MapModule() {
        super("Map", "Records a map of everything you load on each server, with structures, bases and stashes marked. "
                + "Open it with M; the Minimap HUD element shows it in a corner.", Category.WORLD, true);
        group("Recording");
        scanSpeed = add(new NumberSetting("Chunks per tick", "How many loaded chunks get (re)mapped each tick. Higher fills the map faster.",
                6, 1, 30, 1));

        group("On the map");
        showBases = add(new BoolSetting("Bases", "Bases the basefinding modules found (gold rings).", true));
        showStashes = add(new BoolSetting("Stashes", "Stashes the basefinding modules found (pink rings).", true));
        showStorage = add(new BoolSetting("Storage blocks", "Every chest, shulker, barrel... Stash ESP has seen (small dots, up close).", true));
        showSpawners = add(new BoolSetting("Spawners", "Mob and trial spawners: dungeons, mineshafts, trial chambers (red diamonds).", true));
        showStructures = add(new BoolSetting("Structures", "Structures recognised from their blocks (see Structures below).", true));
        showPlayers = add(new BoolSetting("Players", "Other players you can see (red dots).", true));
        showWaypoints = add(new BoolSetting("Waypoints", "Your waypoints (flags). Add them from the full map.", true));
        autoExplore = add(new BoolSetting("Auto mark explored", "After a few seconds at a stash, base or structure, mark it explored: it turns grey "
                + "on the map and you won't be alerted about it again.", true));
        hideExplored = add(new BoolSetting("Hide explored", "Hide explored stashes, bases and structures instead of greying them out.", false));

        group("Waypoints in the world");
        worldWaypoints = add(new BoolSetting("Show in world", "Show your waypoints in the world with their name and distance.", true));
        waypointBeams = add(new BoolSetting("Beams", "A beam of light up from each waypoint, visible from far away.", true)
                .visibleWhen(() -> this.worldWaypoints.get()));

        group("Structures");
        for (Structure st : Structure.values()) {
            String how = switch (st) {
                case TrialChamber -> "vaults and trial spawners among 40+ tuff bricks";
                case AncientCity -> "12+ reinforced deepslate below y=0, 60+ deepslate tiles or bricks, and sculk shriekers, sensors or soul lanterns";
                case Stronghold -> "the portal room's full ring of 12 end portal frames";
                case OceanMonument -> "dark prismarine and sea lanterns only at the monument's height (y 39-61) and within its 58-block "
                        + "square, under water";
                case DesertPyramid -> "the blue terracotta in the middle of its floor, at the exact spot in its chunk the game puts it";
                case JungleTemple -> "the lever puzzle's sticky pistons, at the exact spot in its chunk the game puts them";
                case SwampHut -> "its cauldron next to the crafting table, at the exact spot in its chunk the game puts them";
                case RuinedPortal -> "a match with one of the game's ruined portal templates (obsidian, bricks and netherrack in place), "
                        + "and no lit portal";
                case Geode -> "budding amethyst with 20+ amethyst, 30+ calcite and 30+ smooth basalt";
                case Archaeology -> "3+ suspicious sand or gravel close together";
                case NetherFortress -> "its blaze spawner, or a stretch of its standard bridge (5-wide double deck with walls on both edges)";
                case Bastion -> "a match with one of the game's bastion templates";
                case EndCity -> "a match with one of the game's end city templates";
                case EndGateway -> "the gateway block held in bedrock";
            };
            findStruct.put(st, add(new BoolSetting(st.title, "Find " + st.title.toLowerCase(Locale.ROOT) + "s. Needs " + how
                    + ".", st != Structure.Geode && st != Structure.Archaeology && st != Structure.EndGateway)));
        }
        announce = add(new BoolSetting("Announce in chat", "Say in chat when a new structure is found.", true));

        group("Predicted structures");
        showPredicted = add(new BoolSetting("Show predicted", "Show where structures generate, worked out from the world seed with the game's "
                + "own world generation code (outlined markers). Needs the seed: known in singleplayer, or from Seed Cracker (found, or "
                + "entered as Known seed). Only right for servers with vanilla world generation settings.", false));
        for (var k : dev.erebus.client.seed.StructurePredictor.Kind.values()) {
            boolean on = switch (k) {
                case RUINED_PORTAL, SHIPWRECK, OCEAN_RUIN, TRAIL_RUINS, IGLOO -> false;
                default -> true;
            };
            predictKinds.put(k, add(new BoolSetting(k.label + "s", "Show predicted " + k.label.toLowerCase(Locale.ROOT) + "s.", on)
                    .visibleWhen(showPredicted::get)));
        }
    }

    public static MapModule get() {
        return Erebus.modules().get(MapModule.class);
    }

    /** What the map should draw on top of the terrain, from this module's settings. */
    public MapRenderer.Layers layers() {
        MapRenderer.Layers l = new MapRenderer.Layers();
        l.bases = showBases.get();
        l.stashes = showStashes.get();
        l.storage = showStorage.get();
        l.spawners = showSpawners.get();
        l.players = showPlayers.get();
        l.structures = showStructures.get();
        l.waypoints = showWaypoints.get();
        l.hideExplored = hideExplored.get();
        l.predicted = showPredicted.get();
        for (var e : predictKinds.entrySet()) if (e.getValue().get()) l.predictedKinds.add(e.getKey());
        return l;
    }

    /** Opens the full-screen map (turning the recorder on if it was off). */
    public static void openFullMap() {
        if (mc.player == null) return;
        MapModule m = get();
        if (m != null && !m.isEnabled()) m.setEnabled(true);
        mc.setScreen(new dev.erebus.client.gui.map.WorldMapScreen(mc.screen));
    }

    @Override
    public List<Action> actions() {
        return List.of(
                new Action("Open map", MapModule::openFullMap),
                new Action("Rescan structures", () -> {
                    rescanStructures();
                    chat("Looking for structures in loaded chunks again.");
                }),
                new Action("Forget structures", () -> {
                    int n = MapStore.clearStructures();
                    rescanStructures();
                    chat("Forgot " + n + " structure markers on this map; loaded chunks are checked again.");
                }));
    }

    @Override
    protected void onEnable() {
        scannedAt.clear();
        structScanned.clear();
        orderCx = Integer.MIN_VALUE;
    }

    @Override
    protected void onDisable() {
        MapStore.close();
    }

    // =====================================================================================================
    // Recording
    // =====================================================================================================

    @Override
    public void onTick() {
        String before = MapStore.ready() ? MapStore.server() + MapStore.dimension() : null;
        MapStore.update();
        if (mc.player == null || mc.level == null || !MapStore.ready()) return;
        if (before == null || !before.equals(MapStore.server() + MapStore.dimension())) {
            scannedAt.clear();
            structScanned.clear();
            structBlocks.clear();
            orderCx = Integer.MIN_VALUE;
            nearSince.clear();
        }
        if (autoExplore.get() && ++exploreTick % 10 == 0) checkExplored();

        int pcx = mc.player.chunkPosition().x, pcz = mc.player.chunkPosition().z;
        int r = Math.max(2, mc.options.getEffectiveRenderDistance());
        if (pcx != orderCx || pcz != orderCz || r != orderR) buildOrder(pcx, pcz, r);

        long now = System.currentTimeMillis();
        int budget = scanSpeed.intValue(), tries = 0;
        while (budget > 0 && tries++ < order.size()) {
            long[] c = order.get(orderAt);
            orderAt = (orderAt + 1) % order.size();
            long key = MapStore.chunkKey((int) c[0], (int) c[1]);
            Long last = scannedAt.get(key);
            // near you: refresh every 5 s (things change); further out: once a minute is plenty
            long every = c[2] <= 2 ? 5_000 : 60_000;
            if (last != null && now - last < every) continue;
            if (scanChunk((int) c[0], (int) c[1])) {
                if (structScanned.add(key)) findStructures((int) c[0], (int) c[1]);
                scannedAt.put(key, now);
                budget--;
            }
        }
        if (scannedAt.size() > 20_000) scannedAt.clear();
        long found = MapStore.alerts().stream().filter(m -> !m.explored()).count();
        info = found > 0 ? String.valueOf(found) : null;
    }

    /** All chunk positions in render distance, nearest first. */
    private void buildOrder(int pcx, int pcz, int r) {
        order.clear();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) order.add(new long[]{pcx + dx, pcz + dz, Math.max(Math.abs(dx), Math.abs(dz))});
        order.sort((a, b) -> Long.compare(a[2], b[2]));
        orderAt = 0;
        orderCx = pcx;
        orderCz = pcz;
        orderR = r;
    }

    /** From Stash ESP: what it currently sees in one chunk. */
    public static void stashChunk(int cx, int cz, List<MapStore.Mark> found) {
        MapModule m = get();
        if (m == null || !m.isEnabled() || !MapStore.ready()) return;
        MapStore.setDots(MapStore.chunkKey(cx, cz), found);
    }

    @Override
    public void onRenderOverlay(dev.erebus.client.gui.Ui ui) {
        if (!worldWaypoints.get() || mc.player == null || !MapStore.ready() || mc.options.hideGui) return;
        for (Waypoints.Waypoint w : Waypoints.all()) {
            Vec3 base = new Vec3(w.x() + 0.5, w.y() + 1.5, w.z() + 0.5);
            float[] p = dev.erebus.client.render.Projection.toScreen(base);
            if (p == null) continue;
            int dist = (int) Math.round(mc.player.position().distanceTo(base));
            String d = dist >= 1000 ? String.format(Locale.ROOT, "%.1fk", dist / 1000.0) : dist + "m";
            ui.worldLabel(dev.erebus.client.render.font.Icon.FLAG, w.name(), d, w.color(), p[0], p[1] - 16);
        }
    }

    // =====================================================================================================
    // Waypoints in the world
    // =====================================================================================================

    @Override
    public void onRenderWorld(WorldRender ctx) {
        if (!worldWaypoints.get() || mc.player == null || !MapStore.ready()) return;
        Vec3 cam = ctx.cameraPos();
        for (Waypoints.Waypoint w : Waypoints.all()) {
            Vec3 base = new Vec3(w.x() + 0.5, w.y(), w.z() + 0.5);
            double dist = cam.distanceTo(base);
            // keep the marker within render distance so it is always visible, in the waypoint's direction
            double max = Math.max(32, mc.options.getEffectiveRenderDistance() * 16 - 24);
            Vec3 at = dist > max ? cam.add(base.subtract(cam).normalize().scale(max)) : base;
            if (waypointBeams.get()) {
                Gizmos.line(new Vec3(at.x, at.y, at.z), new Vec3(at.x, at.y + 256, at.z), w.color(), 3f);
                Gizmos.cuboid(new AABB(at.x - 0.25, at.y, at.z - 0.25, at.x + 0.25, at.y + 0.5, at.z + 0.25),
                        GizmoStyle.strokeAndFill(w.color(), 2f, (0x60 << 24) | (w.color() & 0xFFFFFF))).setAlwaysOnTop();
            }
        }
    }

    /** Maps one loaded chunk; false if it isn't loaded. */
    private boolean scanChunk(int cx, int cz) {
        LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null || chunk instanceof EmptyLevelChunk) return false;
        boolean ceiling = mc.level.dimensionType().hasCeiling();
        int minY = chunk.getMinY();
        int top = ceiling ? Math.min(minY + chunk.getHeight() - 1, 126) : 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int[] heights = new int[16]; // the row to the north, for shading
        for (int lx = 0; lx < 16; lx++) heights[lx] = surfaceY(chunk, lx, -1, ceiling, top, minY, pos, cx, cz);
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int y = surfaceY(chunk, lx, lz, ceiling, top, minY, pos, cx, cz);
                int wx = (cx << 4) + lx, wz = (cz << 4) + lz;
                int color = 0xFF000000;
                if (y > minY) {
                    pos.set(wx, y, wz);
                    BlockState st = chunk.getBlockState(pos);
                    MapColor mcol = st.getMapColor(chunk, pos);
                    int depth = 0;
                    if (!st.getFluidState().isEmpty() && st.getFluidState().is(FluidTags.WATER)) {
                        // water: darker the deeper it is, like a vanilla map
                        BlockPos.MutableBlockPos p2 = new BlockPos.MutableBlockPos(wx, y, wz);
                        while (depth < 12 && p2.getY() > minY && !chunk.getBlockState(p2).getFluidState().isEmpty()) { depth++; p2.move(0, -1, 0); }
                        mcol = MapColor.WATER;
                        MapColor.Brightness b = depth > 7 ? MapColor.Brightness.LOW : depth > 3 ? MapColor.Brightness.NORMAL : MapColor.Brightness.HIGH;
                        color = mcol.calculateARGBColor(b);
                    } else if (mcol != MapColor.NONE) {
                        int north = heights[lx];
                        MapColor.Brightness b = y > north ? MapColor.Brightness.HIGH : y < north ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                        color = mcol.calculateARGBColor(b);
                    } else color = 0xFF202020;
                }
                MapStore.set(wx, wz, 0xFF000000 | color);
                heights[lx] = y;
            }
        }
        return true;
    }

    /** The y of the block you'd see from above (below the roof in the Nether), or minY if none. */
    private int surfaceY(LevelChunk chunk, int lx, int lz, boolean ceiling, int top, int minY, BlockPos.MutableBlockPos pos, int cx, int cz) {
        int wx = (cx << 4) + lx, wz = (cz << 4) + lz;
        if (lz < 0) { // the column just north of this chunk
            if (!ceiling) return mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
            LevelChunk n = mc.level.getChunkSource().getChunk(cx, cz - 1, ChunkStatus.FULL, false);
            if (n == null || n instanceof EmptyLevelChunk) return minY;
            return ceilingSurface(n, wx, wz, top, minY, pos);
        }
        if (!ceiling) {
            int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz) - 1;
            // see through things with no map colour (glass, barriers...)
            for (int i = 0; i < 6 && y > minY; i++) {
                pos.set(wx, y, wz);
                BlockState st = chunk.getBlockState(pos);
                if (!st.isAir() && (st.getMapColor(chunk, pos) != MapColor.NONE || !st.getFluidState().isEmpty())) break;
                y--;
            }
            return y;
        }
        return ceilingSurface(chunk, wx, wz, top, minY, pos);
    }

    /** Under a roof (the Nether): the first floor below the first open space under the roof. */
    private static int ceilingSurface(LevelChunk chunk, int wx, int wz, int top, int minY, BlockPos.MutableBlockPos pos) {
        boolean open = false;
        for (int y = top; y > minY; y--) {
            pos.set(wx, y, wz);
            BlockState st = chunk.getBlockState(pos);
            boolean empty = st.isAir();
            if (empty) open = true;
            else if (open) return y;
        }
        return minY;
    }


    // Each structure needs several of its own blocks together, in quantity, inside a 3x3-chunk (48x48 block) area -
    // a single stray block (or a player's build using one of them) isn't enough.
    private static net.minecraft.world.level.block.Block[] B(net.minecraft.world.level.block.Block... b) { return b; }

    private static final java.util.Set<net.minecraft.world.level.block.Block> OVER_KEYS = java.util.Set.of(B(
        net.minecraft.world.level.block.Blocks.VAULT, net.minecraft.world.level.block.Blocks.TRIAL_SPAWNER,
        net.minecraft.world.level.block.Blocks.TUFF_BRICKS, net.minecraft.world.level.block.Blocks.CHISELED_TUFF,
        net.minecraft.world.level.block.Blocks.CHISELED_TUFF_BRICKS, net.minecraft.world.level.block.Blocks.POLISHED_TUFF,
        net.minecraft.world.level.block.Blocks.REINFORCED_DEEPSLATE, net.minecraft.world.level.block.Blocks.DEEPSLATE_TILES,
        net.minecraft.world.level.block.Blocks.DEEPSLATE_BRICKS, net.minecraft.world.level.block.Blocks.SCULK_SHRIEKER,
        net.minecraft.world.level.block.Blocks.SCULK_SENSOR, net.minecraft.world.level.block.Blocks.SOUL_LANTERN,
        net.minecraft.world.level.block.Blocks.END_PORTAL_FRAME, net.minecraft.world.level.block.Blocks.STONE_BRICKS,
        net.minecraft.world.level.block.Blocks.MOSSY_STONE_BRICKS, net.minecraft.world.level.block.Blocks.CRACKED_STONE_BRICKS,
        net.minecraft.world.level.block.Blocks.PRISMARINE_BRICKS, net.minecraft.world.level.block.Blocks.DARK_PRISMARINE,
        net.minecraft.world.level.block.Blocks.SEA_LANTERN, net.minecraft.world.level.block.Blocks.TNT,
        net.minecraft.world.level.block.Blocks.STONE_PRESSURE_PLATE, net.minecraft.world.level.block.Blocks.CHISELED_SANDSTONE,
        net.minecraft.world.level.block.Blocks.CUT_SANDSTONE, net.minecraft.world.level.block.Blocks.MOSSY_COBBLESTONE,
        net.minecraft.world.level.block.Blocks.CHISELED_STONE_BRICKS, net.minecraft.world.level.block.Blocks.STICKY_PISTON,
        net.minecraft.world.level.block.Blocks.TRIPWIRE_HOOK, net.minecraft.world.level.block.Blocks.LEVER,
        net.minecraft.world.level.block.Blocks.CRYING_OBSIDIAN, net.minecraft.world.level.block.Blocks.OBSIDIAN,
        net.minecraft.world.level.block.Blocks.NETHERRACK, net.minecraft.world.level.block.Blocks.MAGMA_BLOCK,
        net.minecraft.world.level.block.Blocks.GOLD_BLOCK, net.minecraft.world.level.block.Blocks.BUDDING_AMETHYST,
        net.minecraft.world.level.block.Blocks.AMETHYST_BLOCK, net.minecraft.world.level.block.Blocks.CALCITE,
        net.minecraft.world.level.block.Blocks.SMOOTH_BASALT, net.minecraft.world.level.block.Blocks.SUSPICIOUS_SAND,
        net.minecraft.world.level.block.Blocks.SUSPICIOUS_GRAVEL, net.minecraft.world.level.block.Blocks.BLUE_TERRACOTTA,
        net.minecraft.world.level.block.Blocks.CAULDRON));
    private static final java.util.Set<net.minecraft.world.level.block.Block> NETHER_KEYS = java.util.Set.of(B(
        net.minecraft.world.level.block.Blocks.NETHER_BRICKS, net.minecraft.world.level.block.Blocks.NETHER_BRICK_FENCE,
        net.minecraft.world.level.block.Blocks.NETHER_BRICK_STAIRS, net.minecraft.world.level.block.Blocks.GILDED_BLACKSTONE,
        net.minecraft.world.level.block.Blocks.POLISHED_BLACKSTONE_BRICKS, net.minecraft.world.level.block.Blocks.CRYING_OBSIDIAN,
        net.minecraft.world.level.block.Blocks.OBSIDIAN, net.minecraft.world.level.block.Blocks.GOLD_BLOCK,
        net.minecraft.world.level.block.Blocks.IRON_CHAIN, net.minecraft.world.level.block.Blocks.SPAWNER));
    private static final java.util.Set<net.minecraft.world.level.block.Block> END_KEYS = java.util.Set.of(B(
        net.minecraft.world.level.block.Blocks.PURPUR_BLOCK, net.minecraft.world.level.block.Blocks.PURPUR_PILLAR,
        net.minecraft.world.level.block.Blocks.PURPUR_STAIRS, net.minecraft.world.level.block.Blocks.END_STONE_BRICKS,
        net.minecraft.world.level.block.Blocks.END_ROD, net.minecraft.world.level.block.Blocks.END_GATEWAY,
        net.minecraft.world.level.block.Blocks.BEDROCK));

    /** Per chunk: block -> {count, sumX, sumZ, minY} of the structure blocks in it (only chunks that have any). */
    private final Map<Long, Map<net.minecraft.world.level.block.Block, int[]>> structBlocks = new HashMap<>();

    /** Looks at every loaded chunk for structures again (after .map clearstructures). */
    public void rescanStructures() {
        structScanned.clear();
        structBlocks.clear();
    }

    private void findStructures(int cx, int cz) {
        LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null || chunk instanceof EmptyLevelChunk) return;
        String dim = mc.level.dimension().identifier().toString();
        java.util.Set<net.minecraft.world.level.block.Block> keys = dim.endsWith("the_nether") ? NETHER_KEYS : dim.endsWith("the_end") ? END_KEYS
            : dim.endsWith("overworld") ? OVER_KEYS : null;
        if (keys == null) return;
        Map<net.minecraft.world.level.block.Block, int[]> found = new HashMap<>();
        var sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            var sec = sections[i];
            if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(st -> keys.contains(st.getBlock()))) continue;
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                var b = sec.getBlockState(x, y, z).getBlock();
                if (!keys.contains(b)) continue;
                int[] f = found.computeIfAbsent(b, k -> new int[] { 0, 0, 0, Integer.MAX_VALUE });
                f[0]++;
                f[1] += (cx << 4) + x;
                f[2] += (cz << 4) + z;
                f[3] = Math.min(f[3], baseY + y);
            }
        }
        if (found.isEmpty()) return;
        structBlocks.put(MapStore.chunkKey(cx, cz), found);
        if (structBlocks.size() > 20_000) structBlocks.clear();
        // this chunk can complete a 3x3 area centred on itself or any neighbour
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) evaluate(cx + dx, cz + dz, dim);
    }

    /** Checks the 3x3-chunk area around (cx, cz) for each structure's combination of blocks. */
    private void evaluate(int cx, int cz, String dim) {
        Map<net.minecraft.world.level.block.Block, int[]> area = new HashMap<>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            var m = structBlocks.get(MapStore.chunkKey(cx + dx, cz + dz));
            if (m == null) continue;
            for (var e : m.entrySet()) {
                int[] t = area.computeIfAbsent(e.getKey(), k -> new int[] { 0, 0, 0, Integer.MAX_VALUE });
                int[] f = e.getValue();
                t[0] += f[0];
                t[1] += f[1];
                t[2] += f[2];
                t[3] = Math.min(t[3], f[3]);
            }
        }
        if (area.isEmpty()) return;
        java.util.function.ToIntFunction<net.minecraft.world.level.block.Block> n = b -> { int[] f = area.get(b); return f == null ? 0 : f[0]; };
        java.util.function.ToIntFunction<net.minecraft.world.level.block.Block> lowY = b -> { int[] f = area.get(b); return f == null ? Integer.MAX_VALUE : f[3]; };
        var K = new Object() {
            int sum(net.minecraft.world.level.block.Block... bs) { int t = 0; for (var b : bs) t += n.applyAsInt(b); return t; }
        };
        boolean over = dim.endsWith("overworld"), nether = dim.endsWith("the_nether"), end = dim.endsWith("the_end");

        if (over) {
            // Trial chamber: vaults AND trial spawners, inside lots of tuff bricks
            if (n.applyAsInt(net.minecraft.world.level.block.Blocks.VAULT) >= 1 && n.applyAsInt(net.minecraft.world.level.block.Blocks.TRIAL_SPAWNER) >= 1
                && K.sum(net.minecraft.world.level.block.Blocks.TUFF_BRICKS, net.minecraft.world.level.block.Blocks.CHISELED_TUFF,
                    net.minecraft.world.level.block.Blocks.CHISELED_TUFF_BRICKS, net.minecraft.world.level.block.Blocks.POLISHED_TUFF) >= 40)
                structure(Structure.TrialChamber, area, net.minecraft.world.level.block.Blocks.VAULT, net.minecraft.world.level.block.Blocks.TRIAL_SPAWNER);
            // Ancient city: deep down, reinforced deepslate with lots of deepslate tiles/bricks and sculk shriekers/sensors or soul lanterns
            if (n.applyAsInt(net.minecraft.world.level.block.Blocks.REINFORCED_DEEPSLATE) >= 12 && lowY.applyAsInt(net.minecraft.world.level.block.Blocks.REINFORCED_DEEPSLATE) < 0
                && K.sum(net.minecraft.world.level.block.Blocks.DEEPSLATE_TILES, net.minecraft.world.level.block.Blocks.DEEPSLATE_BRICKS) >= 60
                && K.sum(net.minecraft.world.level.block.Blocks.SCULK_SHRIEKER, net.minecraft.world.level.block.Blocks.SCULK_SENSOR, net.minecraft.world.level.block.Blocks.SOUL_LANTERN) >= 2)
                structure(Structure.AncientCity, area, net.minecraft.world.level.block.Blocks.REINFORCED_DEEPSLATE);
            // Stronghold: the portal room's ring of 12 frames (frames can't be mined or obtained in survival)
            if (n.applyAsInt(net.minecraft.world.level.block.Blocks.END_PORTAL_FRAME) >= 12)
                verified(Structure.Stronghold, cx, cz, () -> dev.erebus.client.map.StructureVerifier.strongholdPortal(cx, cz));
            // Ocean monument: lots of prismarine with sea lanterns, then the monument's exact height range and footprint
            if (K.sum(net.minecraft.world.level.block.Blocks.PRISMARINE_BRICKS, net.minecraft.world.level.block.Blocks.DARK_PRISMARINE) >= 150
                && n.applyAsInt(net.minecraft.world.level.block.Blocks.SEA_LANTERN) >= 4)
                verified(Structure.OceanMonument, cx, cz, () -> dev.erebus.client.map.StructureVerifier.monument(cx, cz));
            // Desert pyramid, jungle temple, swamp hut: their marker blocks at the exact spot in their start chunk
            if (n.applyAsInt(net.minecraft.world.level.block.Blocks.BLUE_TERRACOTTA) >= 1)
                verified(Structure.DesertPyramid, cx, cz, () -> dev.erebus.client.map.StructureVerifier.desertPyramid(cx, cz));
            if (n.applyAsInt(net.minecraft.world.level.block.Blocks.STICKY_PISTON) >= 3)
                verified(Structure.JungleTemple, cx, cz, () -> dev.erebus.client.map.StructureVerifier.jungleTemple(cx, cz));
            if (n.applyAsInt(net.minecraft.world.level.block.Blocks.CAULDRON) >= 1)
                verified(Structure.SwampHut, cx, cz, () -> dev.erebus.client.map.StructureVerifier.swampHut(cx, cz));
            // Ruined portal: obsidian with netherrack or crying obsidian, then a match with one of its templates
            if (K.sum(net.minecraft.world.level.block.Blocks.OBSIDIAN, net.minecraft.world.level.block.Blocks.CRYING_OBSIDIAN) >= 3
                && (n.applyAsInt(net.minecraft.world.level.block.Blocks.NETHERRACK) >= 3 || n.applyAsInt(net.minecraft.world.level.block.Blocks.CRYING_OBSIDIAN) >= 1))
                verified(Structure.RuinedPortal, cx, cz, () -> dev.erebus.client.map.StructureVerifier.ruinedPortal(cx, cz));
            // Geode: budding amethyst inside amethyst, calcite and smooth basalt shells
            if (n.applyAsInt(net.minecraft.world.level.block.Blocks.BUDDING_AMETHYST) >= 2 && n.applyAsInt(net.minecraft.world.level.block.Blocks.AMETHYST_BLOCK) >= 20
                && n.applyAsInt(net.minecraft.world.level.block.Blocks.CALCITE) >= 30 && n.applyAsInt(net.minecraft.world.level.block.Blocks.SMOOTH_BASALT) >= 30)
                structure(Structure.Geode, area, net.minecraft.world.level.block.Blocks.BUDDING_AMETHYST);
            // Archaeology: several suspicious blocks together
            if (K.sum(net.minecraft.world.level.block.Blocks.SUSPICIOUS_SAND, net.minecraft.world.level.block.Blocks.SUSPICIOUS_GRAVEL) >= 3)
                structure(Structure.Archaeology, area, net.minecraft.world.level.block.Blocks.SUSPICIOUS_SAND, net.minecraft.world.level.block.Blocks.SUSPICIOUS_GRAVEL);
        }
        if (nether) {
            boolean bastion = n.applyAsInt(net.minecraft.world.level.block.Blocks.GILDED_BLACKSTONE) >= 2
                && n.applyAsInt(net.minecraft.world.level.block.Blocks.POLISHED_BLACKSTONE_BRICKS) >= 100;
            // Nether fortress: lots of nether bricks (or its spawner), then the blaze spawner or a stretch of its bridge
            if ((n.applyAsInt(net.minecraft.world.level.block.Blocks.NETHER_BRICKS) >= 250 && n.applyAsInt(net.minecraft.world.level.block.Blocks.NETHER_BRICK_FENCE) >= 8
                    && n.applyAsInt(net.minecraft.world.level.block.Blocks.NETHER_BRICK_STAIRS) >= 4)
                || (n.applyAsInt(net.minecraft.world.level.block.Blocks.SPAWNER) >= 1 && n.applyAsInt(net.minecraft.world.level.block.Blocks.NETHER_BRICKS) >= 40))
                verified(Structure.NetherFortress, cx, cz, () -> dev.erebus.client.map.StructureVerifier.fortress(cx, cz));
            // Bastion: gilded blackstone among polished blackstone bricks, then a match with one of its templates
            if (bastion)
                verified(Structure.Bastion, cx, cz, () -> dev.erebus.client.map.StructureVerifier.bastion(cx, cz));
            // Ruined portal (Nether version): obsidian, then a match with one of its templates
            if (!bastion && K.sum(net.minecraft.world.level.block.Blocks.OBSIDIAN, net.minecraft.world.level.block.Blocks.CRYING_OBSIDIAN) >= 3)
                verified(Structure.RuinedPortal, cx, cz, () -> dev.erebus.client.map.StructureVerifier.ruinedPortal(cx, cz));
        }
        if (end) {
            // End city: a lot of purpur with end stone bricks and end rods
            if (K.sum(net.minecraft.world.level.block.Blocks.PURPUR_BLOCK, net.minecraft.world.level.block.Blocks.PURPUR_PILLAR) >= 60
                && n.applyAsInt(net.minecraft.world.level.block.Blocks.PURPUR_STAIRS) >= 4
                && n.applyAsInt(net.minecraft.world.level.block.Blocks.END_ROD) >= 2)
                verified(Structure.EndCity, cx, cz, () -> dev.erebus.client.map.StructureVerifier.endCity(cx, cz));
            // End gateway: the gateway block held in bedrock
            if (n.applyAsInt(net.minecraft.world.level.block.Blocks.END_GATEWAY) >= 1 && n.applyAsInt(net.minecraft.world.level.block.Blocks.BEDROCK) >= 2)
                structure(Structure.EndGateway, area, net.minecraft.world.level.block.Blocks.END_GATEWAY);
        }
    }

    private final Map<String, Long> verifyTried = new HashMap<>();

    /**
     * A structure the block counts point to, confirmed by {@code check} (its exact layout) before it's marked. A failed
     * check for an area is retried at most every 20 seconds, as more of it loads.
     */
    private void verified(Structure st, int cx, int cz, java.util.function.Supplier<net.minecraft.core.BlockPos> check) {
        if (!findStruct.get(st).get()) return;
        int ax = (cx << 4) + 8, az = (cz << 4) + 8;
        if (MapStore.hasStructure(st.title, ax, az, st.radius + 24)) return;
        String key = st.name() + ":" + cx + "," + cz;
        long now = System.currentTimeMillis();
        Long last = verifyTried.get(key);
        if (last != null && now - last < 20_000) return;
        verifyTried.put(key, now);
        if (verifyTried.size() > 5000) verifyTried.clear();
        net.minecraft.core.BlockPos p = check.get();
        if (p != null) record(st, p.getX(), p.getY(), p.getZ());
    }

    /** Records a structure at the middle of the blocks that gave it away. */
    private void structure(Structure st, Map<net.minecraft.world.level.block.Block, int[]> found, net.minecraft.world.level.block.Block... by) {
        if (!findStruct.get(st).get()) return;
        long n = 0, sx = 0, sz = 0;
        int y = Integer.MAX_VALUE;
        for (var b : by) {
            int[] f = found.get(b);
            if (f == null) continue;
            n += f[0];
            sx += f[1];
            sz += f[2];
            y = Math.min(y, f[3]);
        }
        if (n == 0) return;
        record(st, (int) (sx / n), y, (int) (sz / n));
    }

    /**
     * How far from where a large structure was spotted its start can be, for the structures that are snapped to their
     * start when the seed is known. One of these spans so much that different parts of it would otherwise give
     * markers far apart; tied to the start, every part gives the same one.
     */
    private static int snapRange(Structure st) {
        return switch (st) {
            case TrialChamber -> 144;
            case AncientCity -> 144;
            case NetherFortress -> 128;
            case Bastion -> 80;
            case EndCity -> 96;
            default -> 0;
        };
    }

    private static dev.erebus.client.seed.StructurePredictor.Kind predictedKind(Structure st) {
        for (var k : dev.erebus.client.seed.StructurePredictor.Kind.values()) if (k.label.equals(st.title)) return k;
        return null;
    }

    private void record(Structure st, int x, int y, int z) {
        int range = snapRange(st);
        var kind = range > 0 ? predictedKind(st) : null;
        Long seed = kind != null && mc.level != null ? SeedCracker.knownSeed() : null;
        if (seed == null) {
            mark(st, x, y, z);
            return;
        }
        // seed known: put the marker on the structure's start, so every part of it gives the same marker
        var level = mc.level;
        dev.erebus.client.seed.StructurePredictor.get(seed, level.dimension()).nearestStart(kind, x, z, range)
            .whenComplete((p, err) -> mc.execute(() -> {
                if (mc.level != level || !isEnabled()) return;
                if (p != null) mark(st, p.x(), y, p.z());
                else mark(st, x, y, z);
            }));
    }

    private void mark(Structure st, int x, int y, int z) {
        MapStore.Mark mark = new MapStore.Mark(x, y, z, "structure", st.title, st.color);
        if (!MapStore.addStructure(mark, st.radius)) return;
        if (announce.get()) {
            double dist = Math.sqrt(mc.player.distanceToSqr(x, mc.player.getY(), z));
            chat("Found " + (st.title.matches("^[AEIOU].*") ? "an " : "a ") + st.title + " at " + x + " " + y + " " + z
                + " (" + (int) dist + "m). It's on your map (M).");
        }
    }


    private int exploreTick;
    private final Map<MapStore.Mark, Long> nearSince = new HashMap<>();

    /** Marks a stash / base / structure explored once you've been right at it for 5 seconds. */
    private void checkExplored() {
        long now = System.currentTimeMillis();
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        java.util.Set<MapStore.Mark> near = new java.util.HashSet<>();
        for (MapStore.Mark m : List.copyOf(MapStore.alerts())) {
            if (m.explored()) continue;
            boolean st = m.kind().equals("structure");
            double h = st ? 32 : 16, v = st ? 48 : 16;
            if (Math.abs(m.x() + 0.5 - px) > h || Math.abs(m.z() + 0.5 - pz) > h || Math.abs(m.y() - py) > v) continue;
            near.add(m);
            long since = nearSince.computeIfAbsent(m, k -> now);
            if (now - since >= 5000) {
                MapStore.setExplored(m, true);
                near.remove(m);
                String what = st ? m.label() : m.kind().equals("base") ? "this base" : "this stash";
                chat("Marked " + what + " (" + m.x() + " " + m.z() + ") as explored. It's grey on the map now.");
            }
        }
        nearSince.keySet().retainAll(near);
    }

}
