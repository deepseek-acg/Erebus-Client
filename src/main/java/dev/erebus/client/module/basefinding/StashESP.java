package dev.erebus.client.module.basefinding;

import dev.erebus.client.Erebus;
import dev.erebus.client.map.MapStore;
import dev.erebus.client.module.Category;
import dev.erebus.client.module.Module;
import dev.erebus.client.module.WorldRender;
import dev.erebus.client.module.world.MapModule;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.EnchantingTableBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stash ESP: outlines and tracers to storage and other signs of a base in loaded chunks (chests, barrels, hoppers,
 * shulkers, ender chests, furnaces, enchanting tables, anvils...) and base-giveaway entities (villagers, item frames,
 * armor stands, chest minecarts, donkeys with chests). Alerts when an area looks like a base or a stash.
 * Ported from Nyx.
 */
public final class StashESP extends Module {
    public enum Target {
        Chests("Chests", 0xFFFFA62B), Barrels("Barrels", 0xFFC4813A), Hoppers("Hoppers", 0xFF9AA0A6),
        EnderChests("Ender chests", 0xFFA855F7), EnchantingTables("Enchanting tables", 0xFF22D3EE), Furnaces("Furnaces", 0xFFF87171),
        SmithingTables("Smithing tables", 0xFF60A5FA), BrewingStands("Brewing stands", 0xFFFDE047), Anvils("Anvils", 0xFF94A3B8),
        Droppers("Droppers", 0xFF4ADE80), ShulkerBoxes("Shulker boxes", 0xFFF472B6), Villagers("Villagers", 0xFF34D399),
        ItemFrames("Item frames", 0xFFE5E7EB), ArmorStands("Armor stands", 0xFFD6D3D1), StorageMinecarts("Storage minecarts", 0xFFFB923C),
        ChestedAnimals("Animals with chests", 0xFFA3E635), OtherStorage("Other storage", 0xFF2DD4BF), Spawners("Spawners", 0xFFDC2626);

        public final String title;
        public final int color;

        Target(String title, int color) {
            this.title = title;
            this.color = color;
        }
    }

    private record Found(BlockPos pos, Target type) {
    }

    private final Map<Target, BoolSetting> enabled = new EnumMap<>(Target.class);
    private final BoolSetting outlines;
    private final BoolSetting tracers;
    private final NumberSetting fill;
    private final NumberSetting lineWidth;
    private final NumberSetting maxShown;
    private final BoolSetting dimLone;
    private final BoolSetting notify;
    private final NumberSetting alertArea;
    private final NumberSetting baseMinPlaced;
    private final NumberSetting baseMinStorage;
    private final NumberSetting stashMinStorage;
    private final BoolSetting clusterAlerts;
    private final NumberSetting clusterRadius;
    private final NumberSetting clusterMin;
    private final BoolSetting logFinds;

    private final Map<Long, List<Found>> byChunk = new HashMap<>();
    private final List<Long> queue = new ArrayList<>();
    private int queueIndex;
    private final Map<Target, Integer> counts = new EnumMap<>(Target.class);

    public StashESP() {
        super("Stash ESP", "Outlines and tracers to chests, shulkers, furnaces, villagers, item frames and other signs of a base, "
                + "with alerts when an area looks like a base or a stash.", Category.BASEFINDING);
        group("Find");
        for (Target t : Target.values()) {
            enabled.put(t, add(new BoolSetting(t.title, t == Target.Spawners
                    ? "Mob and trial spawners (dungeons, mineshafts, trial chambers). Shown, but never counted towards alerts since they're natural."
                    : "Find " + t.title.toLowerCase() + ".", true)));
        }
        group("Render");
        outlines = add(new BoolSetting("Outlines", "Draw a box around each one, visible through walls.", true));
        tracers = add(new BoolSetting("Tracers", "Draw a line from your crosshair to each one.", true));
        fill = add(new NumberSetting("Fill opacity", "How see-through the box fill is (0 = outline only).", 40, 0, 160, 5));
        lineWidth = add(new NumberSetting("Line width", "Thickness of the outlines and tracers.", 2, 0.5, 5, 0.5));
        maxShown = add(new NumberSetting("Max shown", "Draw this many one by one (the closest). Anything further is still shown, "
                + "grouped into one box per chunk, so distant stashes never disappear.", 500, 20, 1000, 10));
        dimLone = add(new BoolSetting("Dim lone blocks", "Draw single isolated blocks fainter than ones in groups, so real bases stand out.", false));
        group("Alerts");
        notify = add(new BoolSetting("Alerts", "Alert when an area looks like a base (player-placed blocks and storage together) or a big stash "
                + "(lots of storage). Bases need Player Blocks turned on too.", true));
        alertArea = add(new NumberSetting("Area size", "Chunks around each spot counted together (1 = 3x3 chunks, 2 = 5x5).", 1, 0, 3, 1)
                .visibleWhen(notify::get));
        baseMinPlaced = add(new NumberSetting("Base: player blocks", "A base needs at least this many player-placed blocks (from Player Blocks)...",
                40, 5, 300, 5).visibleWhen(notify::get));
        baseMinStorage = add(new NumberSetting("Base: storage", "...and at least this many storage blocks in the same area.", 6, 1, 60, 1)
                .visibleWhen(notify::get));
        stashMinStorage = add(new NumberSetting("Stash: storage", "This many storage blocks in one area counts as a stash on its own.",
                30, 5, 200, 5).visibleWhen(notify::get));
        clusterAlerts = add(new BoolSetting("Cluster alerts", "Also alert on tight clumps of stash blocks, however few chunks they span. "
                + "Catches compact stashes the area counts miss.", true).visibleWhen(notify::get));
        clusterRadius = add(new NumberSetting("Cluster radius", "Blocks closer than this to each other are in the same clump.", 6, 2, 16, 1)
                .visibleWhen(() -> notify.get() && clusterAlerts.get()));
        clusterMin = add(new NumberSetting("Cluster size", "How many stash blocks one clump needs for an alert.", 10, 3, 60, 1)
                .visibleWhen(() -> notify.get() && clusterAlerts.get()));
        logFinds = add(new BoolSetting("Log to file", "Write every alert to .minecraft/erebus/finds.txt with time, dimension, coordinates and counts.", true));
        addVisuals("A shape for each kind of find (rings over chests, halos over villagers, a spinning cage around spawners, orbits around ender chests...), and a beam with a label over each base and stash it alerted about.");
        addTagSize();
    }

    public static StashESP get() {
        return Erebus.modules().get(StashESP.class);
    }

    /** How many of each target are around right now (blocks and entities), for the legend HUD element. */
    public Map<Target, Integer> counts() {
        return counts;
    }

    public boolean shows(Target t) {
        return enabled.get(t).get();
    }

    @Override
    protected void onEnable() {
        byChunk.clear();
        queue.clear();
        queueIndex = 0;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.level == null) return;
        // build the list of chunks once per pass, then scan a handful of them each tick
        if (queueIndex >= queue.size()) {
            queue.clear();
            queueIndex = 0;
            int r = Math.max(2, Math.max(mc.options.getEffectiveRenderDistance(), mc.options.renderDistance().get())) + 2;
            ChunkPos center = mc.player.chunkPosition();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) queue.add(ChunkPos.asLong(center.x + dx, center.z + dz));
            }
            Set<Long> inRange = new HashSet<>(queue);
            byChunk.keySet().removeIf(k -> !inRange.contains(k));
            recount();
            if (notify.get()) checkAlerts();
        }
        for (int n = 0; n < 40 && queueIndex < queue.size(); n++) scanChunk(queue.get(queueIndex++));
    }

    private void recount() {
        counts.clear();
        int total = 0;
        for (List<Found> l : byChunk.values()) {
            for (Found f : l) {
                counts.merge(f.type, 1, Integer::sum);
                total++;
            }
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            Target t = entityType(e);
            if (t != null && e.isAlive() && enabled.get(t).get()) {
                counts.merge(t, 1, Integer::sum);
                total++;
            }
        }
        info = total > 0 ? String.valueOf(total) : null;
    }

    private void scanChunk(long key) {
        int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
        LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null) {
            byChunk.remove(key);
            return;
        }
        List<Found> list = new ArrayList<>();
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            Target t = typeOf(be);
            if (t != null && enabled.get(t).get()) list.add(new Found(be.getBlockPos(), t));
        }
        // smithing tables and anvils have no block entity: look through the sections that might contain them
        boolean smith = enabled.get(Target.SmithingTables).get(), anvil = enabled.get(Target.Anvils).get();
        if (smith || anvil) {
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                LevelChunkSection s = sections[i];
                if (s == null || s.hasOnlyAir()) continue;
                if (!s.maybeHas(st -> (smith && st.is(Blocks.SMITHING_TABLE)) || (anvil && st.is(BlockTags.ANVIL)))) continue;
                int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState st = s.getBlockState(x, y, z);
                            if (smith && st.is(Blocks.SMITHING_TABLE)) list.add(new Found(new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z), Target.SmithingTables));
                            else if (anvil && st.is(BlockTags.ANVIL)) list.add(new Found(new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z), Target.Anvils));
                        }
                    }
                }
            }
        }
        if (list.isEmpty()) byChunk.remove(key);
        else byChunk.put(key, list);
        // remember them on the map
        List<MapStore.Mark> marks = new ArrayList<>(list.size());
        for (Found f : list) {
            marks.add(new MapStore.Mark(f.pos.getX(), f.pos.getY(), f.pos.getZ(), f.type == Target.Spawners ? "spawner" : "storage",
                    f.type.title, f.type.color));
        }
        MapModule.stashChunk(cx, cz, marks);
    }

    private static boolean isStorage(Target t) {
        return t == Target.Chests || t == Target.Barrels || t == Target.ShulkerBoxes || t == Target.EnderChests || t == Target.Hoppers;
    }

    /** Looks for areas worth an alert: a base (player-placed blocks and storage together) or a big stash. */
    private void checkAlerts() {
        int r = alertArea.intValue();
        PlayerBlocks pb = Erebus.modules().get(PlayerBlocks.class);
        boolean pbOn = pb != null && pb.isEnabled();
        ResourceKey<Level> dim = mc.level.dimension();
        int needStorage = Math.min(stashMinStorage.intValue(), pbOn ? baseMinStorage.intValue() : Integer.MAX_VALUE);
        for (long key : new ArrayList<>(byChunk.keySet())) {
            int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
            List<BlockPos> storage = new ArrayList<>();
            int shulkers = 0;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    List<Found> l = byChunk.get(ChunkPos.asLong(cx + dx, cz + dz));
                    if (l == null) continue;
                    for (Found f : l) {
                        if (!isStorage(f.type)) continue;
                        storage.add(f.pos);
                        if (f.type == Target.ShulkerBoxes) shulkers++;
                    }
                }
            }
            if (storage.size() < needStorage) continue;
            List<BlockPos> placed = pbOn ? pb.placedIn(cx, cz, r) : List.of();
            String kind = null;
            if (pbOn && placed.size() >= baseMinPlaced.intValue() && storage.size() >= baseMinStorage.intValue()) kind = "Base";
            else if (storage.size() >= stashMinStorage.intValue()) kind = "Stash";
            if (kind == null) continue;

            List<BlockPos> all = new ArrayList<>(storage);
            all.addAll(placed);
            AABB box = BaseFinds.boxOf(all);
            if (BaseFinds.alreadyFound(dim, box)) continue;
            BaseFinds.post(kind, dim, box, placed.size(), storage.size(), shulkers, logFinds.get());
        }
        if (clusterAlerts.get()) checkClusters(dim);
    }

    /** Groups found blocks into clumps (each within cluster radius of the next) and alerts on dense ones. */
    private void checkClusters(ResourceKey<Level> dim) {
        List<Found> all = new ArrayList<>();
        for (List<Found> l : byChunk.values()) {
            for (Found f : l) if (enabled.get(f.type).get() && f.type != Target.Spawners) all.add(f);
        }
        int n = all.size();
        if (n < clusterMin.intValue() || n > 30000) return;
        int r = clusterRadius.intValue();
        long r2 = (long) r * r;
        // spatial hash with cells of size r: neighbours are always in the 27 surrounding cells
        Map<Long, List<Integer>> cells = new HashMap<>();
        for (int i = 0; i < n; i++) cells.computeIfAbsent(cellKey(all.get(i).pos, r), k -> new ArrayList<>()).add(i);
        boolean[] seen = new boolean[n];
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (seen[i]) continue;
            List<Found> group = new ArrayList<>();
            seen[i] = true;
            stack.push(i);
            while (!stack.isEmpty()) {
                Found f = all.get(stack.pop());
                group.add(f);
                int gx = Math.floorDiv(f.pos.getX(), r), gy = Math.floorDiv(f.pos.getY(), r), gz = Math.floorDiv(f.pos.getZ(), r);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            List<Integer> cell = cells.get(pack(gx + dx, gy + dy, gz + dz));
                            if (cell == null) continue;
                            for (int j : cell) {
                                if (seen[j] || all.get(j).pos.distSqr(f.pos) > r2) continue;
                                seen[j] = true;
                                stack.push(j);
                            }
                        }
                    }
                }
            }
            if (group.size() < clusterMin.intValue()) continue;
            List<BlockPos> pos = new ArrayList<>();
            int storage = 0, shulkers = 0;
            for (Found f : group) {
                pos.add(f.pos);
                if (isStorage(f.type) || f.type == Target.OtherStorage) storage++;
                if (f.type == Target.ShulkerBoxes) shulkers++;
            }
            AABB box = BaseFinds.boxOf(pos);
            if (BaseFinds.alreadyFound(dim, box)) continue;
            BaseFinds.post("Stash", dim, box, 0, storage > 0 ? storage : group.size(), shulkers, logFinds.get());
        }
    }

    private static long cellKey(BlockPos p, int r) {
        return pack(Math.floorDiv(p.getX(), r), Math.floorDiv(p.getY(), r), Math.floorDiv(p.getZ(), r));
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0xFFFFF) << 22) | (z & 0x3FFFFF);
    }

    static Target entityType(Entity e) {
        if (e instanceof Villager) return Target.Villagers;
        if (e instanceof ItemFrame) return Target.ItemFrames; // includes glow item frames
        if (e instanceof ArmorStand a && !a.isMarker()) return Target.ArmorStands;
        if (e instanceof MinecartChest || e instanceof MinecartHopper) return Target.StorageMinecarts;
        if (e instanceof AbstractChestedHorse h && h.hasChest()) return Target.ChestedAnimals;
        return null;
    }

    private static Target typeOf(BlockEntity be) {
        if (be instanceof ChestBlockEntity) return Target.Chests;
        if (be instanceof BarrelBlockEntity) return Target.Barrels;
        if (be instanceof HopperBlockEntity) return Target.Hoppers;
        if (be instanceof EnderChestBlockEntity) return Target.EnderChests;
        if (be instanceof EnchantingTableBlockEntity) return Target.EnchantingTables;
        if (be instanceof AbstractFurnaceBlockEntity) return Target.Furnaces;
        if (be instanceof BrewingStandBlockEntity) return Target.BrewingStands;
        if (be instanceof DropperBlockEntity) return Target.Droppers;
        if (be instanceof ShulkerBoxBlockEntity) return Target.ShulkerBoxes;
        if (be instanceof SpawnerBlockEntity || be instanceof TrialSpawnerBlockEntity) return Target.Spawners;
        if (be instanceof DispenserBlockEntity || be instanceof CrafterBlockEntity || be instanceof DecoratedPotBlockEntity
                || be instanceof ShelfBlockEntity || be instanceof ChiseledBookShelfBlockEntity) return Target.OtherStorage;
        return null;
    }

    private record Box(AABB box, Target type, double dist) {
    }

    @Override
    public void onRenderWorld(WorldRender ctx) {
        if (mc.player == null || mc.level == null) return;
        Vec3 camPos = ctx.cameraPos();
        Vec3 start = ctx.tracerStart();
        float width = lineWidth.floatValue();
        int alpha = fill.intValue();

        List<Box> draw = new ArrayList<>();
        for (List<Found> l : byChunk.values()) {
            for (Found f : l) {
                if (!enabled.get(f.type).get()) continue;
                AABB box = new AABB(f.pos);
                draw.add(new Box(box, f.type, box.getCenter().distanceToSqr(camPos)));
            }
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            Target t = entityType(e);
            if (t == null || !enabled.get(t).get() || !e.isAlive()) continue;
            Vec3 lerp = e.getPosition(ctx.partialTick()).subtract(e.position());
            AABB box = e.getBoundingBox().move(lerp.x, lerp.y, lerp.z);
            draw.add(new Box(box, t, box.getCenter().distanceToSqr(camPos)));
        }
        draw.sort((a, b) -> Double.compare(a.dist, b.dist));

        int limit = Math.min(draw.size(), maxShown.intValue());
        // past the cap: one box per chunk around everything left in it, so distance never hides anything
        if (draw.size() > limit) {
            Map<Long, AABB> rest = new HashMap<>();
            Map<Long, Target> restType = new HashMap<>();
            for (int i = limit; i < draw.size(); i++) {
                AABB box = draw.get(i).box;
                Vec3 c = box.getCenter();
                long key = ChunkPos.asLong(((int) Math.floor(c.x)) >> 4, ((int) Math.floor(c.z)) >> 4);
                rest.merge(key, box, AABB::minmax);
                restType.merge(key, draw.get(i).type, (a, b) -> isStorage(b) && !isStorage(a) ? b : a);
            }
            for (var e : rest.entrySet()) {
                int color = restType.get(e.getKey()).color;
                AABB box = e.getValue();
                Gizmos.cuboid(box, alpha > 0 ? GizmoStyle.strokeAndFill(color, width, (alpha << 24) | (color & 0xFFFFFF))
                        : GizmoStyle.stroke(color, width)).setAlwaysOnTop();
                if (tracers.get()) Gizmos.line(start, box.getCenter(), color, width).setAlwaysOnTop();
            }
        }
        List<Vec3> centers = null;
        if (dimLone.get()) {
            centers = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) centers.add(draw.get(i).box.getCenter());
        }
        for (int i = 0; i < limit; i++) {
            Box b = draw.get(i);
            float strength = 1f;
            if (centers != null) { // fainter when few others are within 32 blocks
                Vec3 c = centers.get(i);
                int near = 0;
                for (Vec3 o : centers) {
                    if (o != c && c.distanceToSqr(o) <= 1024) {
                        if (++near >= 5) break;
                    }
                }
                strength = 0.25f + 0.75f * (near / 5f);
            }
            int lineColor = ((int) (255 * strength) << 24) | (b.type.color & 0xFFFFFF);
            int fillA = Math.round(alpha * strength);
            if (outlines.get()) {
                Gizmos.cuboid(b.box, fillA > 0 ? GizmoStyle.strokeAndFill(lineColor, width, (fillA << 24) | (b.type.color & 0xFFFFFF))
                        : GizmoStyle.stroke(lineColor, width)).setAlwaysOnTop();
            }
            if (tracers.get()) Gizmos.line(start, b.box.getCenter(), lineColor, width).setAlwaysOnTop();
        }
        if (visuals()) {
            for (int i = 0; i < Math.min(limit, 64); i++) decorate(draw.get(i));
            for (dev.erebus.client.map.MapStore.Mark m : dev.erebus.client.map.MapStore.alerts()) {
                if (!m.kind().equals("base") && !m.kind().equals("stash")) continue;
                Vec3 at = new Vec3(m.x() + 0.5, m.y(), m.z() + 0.5);
                if (at.distanceToSqr(camPos) < 512 * 512) dev.erebus.client.render.Visuals.beam(at, 48, m.color(), 2.5f);
            }
        }
    }

    /** The cosmetic shape for one kind of find (Better visuals). */
    private void decorate(Box b) {
        AABB box = b.box;
        Vec3 c = box.getCenter();
        int col = b.type.color;
        double t = dev.erebus.client.render.Visuals.time();
        switch (b.type) {
            case Spawners -> { // a spinning cage
                for (int i = 0; i < 4; i++) {
                    double a = t * 1.6 + i * Math.PI / 2;
                    Vec3 p = c.add(Math.cos(a) * 0.95, -0.5, Math.sin(a) * 0.95);
                    Gizmos.line(p, p.add(0, 1, 0), col, 2f).setAlwaysOnTop();
                }
                dev.erebus.client.render.Visuals.ring(c.add(0, 0.5, 0), 0.95, col, 1.5f, 24);
                dev.erebus.client.render.Visuals.ring(c.add(0, -0.5, 0), 0.95, col, 1.5f, 24);
            }
            case EnderChests, EnchantingTables -> { // orbiting sparks
                for (int i = 0; i < 3; i++) {
                    double a = t * 2 + i * Math.PI * 2 / 3;
                    Gizmos.point(c.add(Math.cos(a) * 0.85, Math.sin(t * 3 + i) * 0.3, Math.sin(a) * 0.85), col, 7f).setAlwaysOnTop();
                }
            }
            case ShulkerBoxes -> {
                dev.erebus.client.render.Visuals.brackets(box.inflate(0.08), col, 2f);
                dev.erebus.client.render.Visuals.ring(new Vec3(c.x, box.maxY + 0.15, c.z), 0.45 + 0.12 * dev.erebus.client.render.Visuals.pulse(1.4), col, 2f, 24);
            }
            case Villagers, ArmorStands, ChestedAnimals -> // a halo
                    dev.erebus.client.render.Visuals.ring(new Vec3(c.x, box.maxY + 0.25, c.z), 0.32, col, 2f, 20);
            case StorageMinecarts -> dev.erebus.client.render.Visuals.ring(new Vec3(c.x, box.minY + 0.02, c.z), 0.85, col, 2f, 24);
            case Furnaces, BrewingStands -> { // rising embers or bubbles
                for (int i = 0; i < 3; i++) {
                    float p = dev.erebus.client.render.Visuals.phase(1.3, i / 3.0);
                    Gizmos.point(new Vec3(c.x + (i - 1) * 0.22, box.maxY + p * 0.9, c.z), dev.erebus.client.render.Visuals.fade(col, 1 - p), 6f).setAlwaysOnTop();
                }
            }
            case ItemFrames -> dev.erebus.client.render.Visuals.brackets(box.inflate(0.04), col, 1.4f);
            default -> { // storage blocks: brackets and a soft ring over the lid
                dev.erebus.client.render.Visuals.brackets(box.inflate(0.05), col, 1.6f);
                dev.erebus.client.render.Visuals.ring(new Vec3(c.x, box.maxY + 0.04, c.z), 0.42, dev.erebus.client.render.Visuals.fade(col, 0.4f + 0.6f * dev.erebus.client.render.Visuals.pulse(2.0)), 1.5f, 20);
            }
        }
    }

    @Override
    public void onRenderOverlay(dev.erebus.client.gui.Ui ui) {
        if (!visuals() || mc.player == null || mc.options.hideGui) return;
        for (dev.erebus.client.map.MapStore.Mark m : dev.erebus.client.map.MapStore.alerts()) {
            if (!m.kind().equals("base") && !m.kind().equals("stash")) continue;
            Vec3 at = new Vec3(m.x() + 0.5, m.y() + 3, m.z() + 0.5);
            double d = mc.player.position().distanceTo(at);
            if (d > 256) continue;
            dev.erebus.client.render.Visuals.label(ui, at, dev.erebus.client.render.font.Icon.RADAR, m.label(), Math.round(d) + "m", m.color());
        }
    }
}
