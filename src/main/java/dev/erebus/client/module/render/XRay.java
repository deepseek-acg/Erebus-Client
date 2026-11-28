package dev.erebus.client.module.render;

import dev.erebus.client.module.Category;
import dev.erebus.client.module.Module;
import dev.erebus.client.module.WorldRender;
import dev.erebus.client.module.basefinding.PlayerBlocks;
import dev.erebus.client.render.XRayState;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.ModeSetting;
import dev.erebus.client.setting.NumberSetting;
import dev.erebus.client.setting.TextSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shows only the blocks you choose (ores, spawners, storage...) and hides everything else, so they can be seen through
 * the rock. Ported from Nyx, with the ore list following the dimension you're in, fluids hidden too, and anti-xray
 * awareness: servers running anti-xray (Paper's engine modes) send fake ores in place of buried stone, and real ores
 * only where they touch air, so ores with no air next to them are dimmed (probably fake) and ores you could actually
 * reach get an outline in their color. Empty space underground (caves, tunnels, lava, water) can be shaded so the
 * shape of the caves shows through the rock.
 *
 * <p>Hiding works on the chunk meshes (through {@link XRayState}, read by the block-state mixin): hidden blocks have no shape to draw and don't cover their neighbours, and the
 * ones shown are drawn at full brightness. Only what you see changes; nothing is sent.
 */
public final class XRay extends Module {
    private final ModeSetting ores;
    private final BoolSetting diamond, emerald, gold, iron, copper, redstone, lapis, coal, debris, quartz;
    private final TextSetting extra;
    private final BoolSetting storage;
    private final BoolSetting playerBlocks;
    private final BoolSetting bedrock;
    private final BoolSetting hideFluids;
    private final BoolSetting fullbright;
    private final ModeSetting buried;
    private final BoolSetting outlineExposed;
    private final NumberSetting oreRange;
    private final BoolSetting empty;
    private final BoolSetting fluids;
    private final NumberSetting range;
    private final NumberSetting maxY;
    private final NumberSetting opacity;
    private final NumberSetting maxShapes;

    private boolean addedNightVision;
    private int lastSignature;
    private Level lastLevel;

    public XRay() {
        super("X-Ray", "Shows only ores, spawners, storage and the blocks you pick, through everything else. Marks ores that are probably "
                + "fakes on servers with anti-xray, and shades caves through the rock. Ported from Nyx.", Category.RENDER);
        group("Ores");
        ores = add(new ModeSetting("Ores", "Which ores show: the ones of the dimension you're in (from the toggles below), or only the "
                + "Extra blocks list.", "This dimension", "This dimension", "Extra list only"));
        diamond = ore("Diamond", true);
        emerald = ore("Emerald", true);
        gold = ore("Gold", true);
        iron = ore("Iron", true);
        copper = ore("Copper", false);
        redstone = ore("Redstone", false);
        lapis = ore("Lapis", false);
        coal = ore("Coal", false);
        debris = ore("Ancient debris", true);
        quartz = ore("Nether quartz", false);
        group("Other blocks");
        extra = add(new TextSetting("Extra blocks", "More blocks to show, by id, separated by commas (\"spawner, minecraft:obsidian\").",
                "spawner, end_portal_frame", 512));
        storage = add(new BoolSetting("Storage", "Chests, barrels, shulkers, ender chests, hoppers, furnaces, droppers, dispensers, brewing "
                + "stands.", true));
        playerBlocks = add(new BoolSetting("Player blocks", "Blocks only players place (Player Blocks' list: concrete, beacons, beds...).",
                true));
        bedrock = add(new BoolSetting("Bedrock", "For your bearings near the bottom of the world.", false));
        hideFluids = add(new BoolSetting("Hide water and lava", "Don't draw fluids either (use the shading below to see where they are).", true));
        fullbright = add(new BoolSetting("Fullbright", "Light everything up while X-Ray is on (ores deep underground are pitch black).", true));
        group("Anti-xray");
        buried = add(new ModeSetting("Buried ores", "Ores with no air, water or lava next to them. Servers with anti-xray put fake ores in "
                + "buried stone and only send real ones where they touch air, so these are probably fake there. Dim: darken them. "
                + "Show: leave them.", "Dim", "Dim", "Show"));
        outlineExposed = add(new BoolSetting("Outline exposed ores", "Outline ores that touch air (real even with anti-xray, and reachable "
                + "from a cave) in their color.", true));
        oreRange = add(new NumberSetting("Ore range", "How far around you ores are checked and outlined.", 32, 8, 64, 4).unit(" blocks"));
        group("Empty areas");
        empty = add(new BoolSetting("Shade caves", "Shade empty space underground (caves, tunnels, other people's tunnels) so its shape "
                + "shows through the rock.", true));
        fluids = add(new BoolSetting("Shade lava and water", "Shade underground lava (orange) and water (blue).", true));
        range = add(new NumberSetting("Range", "How far around you empty areas are shaded.", 32, 8, 96, 4).unit(" blocks"));
        maxY = add(new NumberSetting("Max height", "Nothing above this Y is shaded (Overworld), so overhangs and the undersides of "
                + "trees don't count as caves.", 60, -64, 320, 1));
        opacity = add(new NumberSetting("Opacity", "How strongly empty areas are shaded.", 40, 5, 160, 5));
        maxShapes = add(new NumberSetting("Max shapes", "Most shapes drawn at once, nearest first. Lower it if your frame rate drops.", 2500,
                200, 10000, 100));
    }

    private BoolSetting ore(String name, boolean on) {
        return add(new BoolSetting(name, on).visibleWhen(() -> ores.is("This dimension")));
    }

    // =====================================================================================================
    // What's hidden (called from chunk building threads)
    // =====================================================================================================

    private Set<Block> buildShown() {
        Set<Block> s = new HashSet<>();
        if (ores.is("This dimension") && mc.level != null) s.addAll(dimensionOres());
        for (String id : extra.get().split(",")) {
            String t = id.trim();
            if (t.isEmpty()) continue;
            Identifier key = Identifier.tryParse(t.contains(":") ? t : "minecraft:" + t);
            if (key != null) BuiltInRegistries.BLOCK.getOptional(key).ifPresent(b -> {
                if (b != Blocks.AIR) s.add(b);
            });
        }
        if (bedrock.get()) s.add(Blocks.BEDROCK);
        if (storage.get() || playerBlocks.get()) {
            for (Block b : BuiltInRegistries.BLOCK) {
                if (storage.get() && isStorage(b)) s.add(b);
                if (playerBlocks.get() && PlayerBlocks.isPlayerOnly(b)) s.add(b);
            }
        }
        return Set.copyOf(s);
    }

    /** The ores of the dimension you're in, as the toggles pick them. */
    private Set<Block> dimensionOres() {
        Set<Block> s = new HashSet<>();
        if (mc.level.dimension() == Level.NETHER) {
            if (debris.get()) s.add(Blocks.ANCIENT_DEBRIS);
            if (gold.get()) {
                s.add(Blocks.NETHER_GOLD_ORE);
                s.add(Blocks.GILDED_BLACKSTONE);
            }
            if (quartz.get()) s.add(Blocks.NETHER_QUARTZ_ORE);
        } else if (mc.level.dimension() == Level.OVERWORLD) {
            if (diamond.get()) s.addAll(List.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE));
            if (emerald.get()) s.addAll(List.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE));
            if (gold.get()) s.addAll(List.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE));
            if (iron.get()) s.addAll(List.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE));
            if (copper.get()) s.addAll(List.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE));
            if (redstone.get()) s.addAll(List.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE));
            if (lapis.get()) s.addAll(List.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE));
            if (coal.get()) s.addAll(List.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE));
        }
        return s;
    }

    private static boolean isStorage(Block b) {
        return b instanceof net.minecraft.world.level.block.ChestBlock || b instanceof net.minecraft.world.level.block.BarrelBlock
                || b instanceof net.minecraft.world.level.block.ShulkerBoxBlock || b instanceof net.minecraft.world.level.block.EnderChestBlock
                || b instanceof net.minecraft.world.level.block.HopperBlock || b instanceof net.minecraft.world.level.block.AbstractFurnaceBlock
                || b instanceof net.minecraft.world.level.block.DispenserBlock || b instanceof net.minecraft.world.level.block.BrewingStandBlock;
    }

    /** A cheap fingerprint of the settings that change what's shown. */
    private int signature() {
        int h = ores.get().hashCode() * 31 + extra.get().hashCode();
        for (BoolSetting b : new BoolSetting[]{diamond, emerald, gold, iron, copper, redstone, lapis, coal, debris, quartz, storage, playerBlocks,
                bedrock, hideFluids}) h = h * 31 + (b.get() ? 1 : 0);
        return h * 31 + (mc.level == null ? 0 : mc.level.dimension().hashCode());
    }

    // =====================================================================================================
    // Lifecycle
    // =====================================================================================================

    @Override
    protected void onEnable() {
        XRayState.serverThread = mc.getSingleplayerServer() != null ? mc.getSingleplayerServer().getRunningThread() : null;
        XRayState.shown = buildShown();
        XRayState.hideFluids = hideFluids.get();
        lastSignature = signature();
        lastLevel = mc.level;
        XRayState.enabled = true;
        addedNightVision = false;
        sections.clear();
        queue.clear();
        queueIndex = 0;
        reload();
    }

    @Override
    protected void onDisable() {
        XRayState.enabled = false;
        if (addedNightVision && mc.player != null && !fullbrightOn()) {
            MobEffectInstance nv = mc.player.getEffect(MobEffects.NIGHT_VISION);
            if (nv != null && nv.getAmplifier() == 42) mc.player.removeEffect(MobEffects.NIGHT_VISION);
        }
        addedNightVision = false;
        sections.clear();
        reload();
    }

    private static boolean fullbrightOn() {
        var f = dev.erebus.client.Erebus.modules().get(Fullbright.class);
        return f != null && f.isEnabled();
    }

    /** Rebuilds every chunk mesh (like F3+A) so the change shows. */
    private void reload() {
        try {
            if (mc.levelRenderer != null) mc.levelRenderer.allChanged();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.level == null) return;
        int sig = signature();
        if (sig != lastSignature || mc.level != lastLevel) { // settings or dimension changed
            lastSignature = sig;
            lastLevel = mc.level;
            XRayState.serverThread = mc.getSingleplayerServer() != null ? mc.getSingleplayerServer().getRunningThread() : null;
            XRayState.shown = buildShown();
            XRayState.hideFluids = hideFluids.get();
            sections.clear();
            queue.clear();
            reload();
        }
        if (fullbright.get()) {
            MobEffectInstance nv = mc.player.getEffect(MobEffects.NIGHT_VISION);
            if (nv == null || (nv.getAmplifier() == 42 && nv.getDuration() < 300)) {
                mc.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 600, 42, false, false)); // client-side only
                addedNightVision = true;
            }
        } else if (addedNightVision && !fullbrightOn()) {
            MobEffectInstance nv = mc.player.getEffect(MobEffects.NIGHT_VISION);
            if (nv != null && nv.getAmplifier() == 42) mc.player.removeEffect(MobEffects.NIGHT_VISION);
            addedNightVision = false;
        }
        scanTick();
        info = exposedCount + buriedCount > 0 ? exposedCount + " exposed, " + buriedCount + " buried" : null;
    }

    // =====================================================================================================
    // Scanning: empty space and ores, one 16x16x16 section at a time, nearest first
    // =====================================================================================================

    private record Ore(AABB box, int color) {
    }

    private record Section(List<AABB> air, List<AABB> lava, List<AABB> water, List<Ore> exposed, List<AABB> buried) {
    }

    private final Map<Long, Section> sections = new HashMap<>();
    private final List<long[]> queue = new ArrayList<>();
    private int queueIndex;
    private int exposedCount, buriedCount;

    private void scanTick() {
        if (queueIndex >= queue.size()) { // a new round
            queue.clear();
            queueIndex = 0;
            int r = (int) (Math.max(range.get(), oreRange.get()) / 16) + 1;
            int pcx = mc.player.getBlockX() >> 4, pcy = mc.player.getBlockY() >> 4, pcz = mc.player.getBlockZ() >> 4;
            int minS = mc.level.getMinSectionY(), maxS = minS + mc.level.getSectionsCount() - 1;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -r; dy <= r; dy++) {
                int sy = pcy + dy;
                if (sy < minS || sy > maxS) continue;
                queue.add(new long[]{pcx + dx, sy, pcz + dz, (long) dx * dx + dy * dy + dz * dz});
            }
            queue.sort(Comparator.comparingLong(a -> a[3]));
            Set<Long> keep = new HashSet<>();
            for (long[] q : queue) keep.add(key((int) q[0], (int) q[1], (int) q[2]));
            sections.keySet().retainAll(keep);
            int ex = 0, bu = 0;
            for (Section s : sections.values()) {
                ex += s.exposed.size();
                bu += s.buried.size();
            }
            exposedCount = ex;
            buriedCount = bu;
        }
        for (int n = 0; n < 6 && queueIndex < queue.size(); n++) {
            long[] q = queue.get(queueIndex++);
            Section s = scan((int) q[0], (int) q[1], (int) q[2]);
            long k = key((int) q[0], (int) q[1], (int) q[2]);
            if (s == null) sections.remove(k);
            else sections.put(k, s);
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFF) << 42 | ((long) y & 0xFFFFF) << 22 | ((long) z & 0x3FFFFF);
    }

    private Section scan(int cx, int sy, int cz) {
        LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null) return null;
        int idx = sy - mc.level.getMinSectionY();
        LevelChunkSection[] arr = chunk.getSections();
        if (idx < 0 || idx >= arr.length || arr[idx] == null) return null;
        LevelChunkSection sec = arr[idx];
        int ox = cx << 4, oy = sy << 4, oz = cz << 4;
        boolean overworld = mc.level.dimension() == Level.OVERWORLD;
        int topY = overworld ? maxY.intValue() : Integer.MAX_VALUE;
        // 1 = underground air, 2 = lava, 3 = water (anything else 0); "open" = air or fluid anywhere, for exposure
        byte[] kind = new byte[4096];
        boolean[] open = new boolean[4096];
        int[] surface = new int[256];
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) surface[x * 16 + z] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        Set<Block> visible = XRayState.shown;
        List<int[]> oreCells = new ArrayList<>();
        boolean onlyAir = sec.hasOnlyAir();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int i = (y * 16 + z) * 16 + x;
            BlockState st = onlyAir ? Blocks.AIR.defaultBlockState() : sec.getBlockState(x, y, z);
            boolean air = st.isAir(), fluid = !st.getFluidState().isEmpty();
            open[i] = air || fluid;
            int wy = oy + y;
            if ((air || fluid) && wy < surface[x * 16 + z] && wy <= topY) {
                kind[i] = (byte) (air ? 1 : st.getFluidState().is(FluidTags.LAVA) ? 2 : 3);
            }
            if (!air && isOre(st.getBlock()) && visible.contains(st.getBlock())) oreCells.add(new int[]{x, y, z});
        }
        List<Ore> exposed = new ArrayList<>();
        List<AABB> buriedOres = new ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int[] c : oreCells) {
            boolean touches = false;
            for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                int nx = c[0] + d[0], ny = c[1] + d[1], nz = c[2] + d[2];
                if (nx >= 0 && nx < 16 && ny >= 0 && ny < 16 && nz >= 0 && nz < 16) {
                    if (open[(ny * 16 + nz) * 16 + nx]) touches = true;
                } else { // across the section's edge
                    BlockState n = mc.level.getBlockState(p.set(ox + nx, oy + ny, oz + nz));
                    if (n.isAir() || !n.getFluidState().isEmpty()) touches = true;
                }
                if (touches) break;
            }
            AABB box = new AABB(ox + c[0], oy + c[1], oz + c[2], ox + c[0] + 1, oy + c[1] + 1, oz + c[2] + 1);
            if (touches) exposed.add(new Ore(box, oreColor(sec.getBlockState(c[0], c[1], c[2]).getBlock())));
            else buriedOres.add(box);
        }
        return new Section(merge(kind, (byte) 1, ox, oy, oz), merge(kind, (byte) 2, ox, oy, oz), merge(kind, (byte) 3, ox, oy, oz),
                exposed, buriedOres);
    }

    private static boolean isOre(Block b) {
        return b == Blocks.ANCIENT_DEBRIS || b == Blocks.GILDED_BLACKSTONE || BuiltInRegistries.BLOCK.getKey(b).getPath().endsWith("_ore");
    }

    private static int oreColor(Block b) {
        String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
        if (id.contains("diamond")) return 0xFF4DE8F0;
        if (id.contains("emerald")) return 0xFF34D96B;
        if (id.contains("gold") || b == Blocks.GILDED_BLACKSTONE) return 0xFFFFD23F;
        if (id.contains("iron")) return 0xFFE0B48C;
        if (id.contains("copper")) return 0xFFE07A3F;
        if (id.contains("redstone")) return 0xFFFF3B3B;
        if (id.contains("lapis")) return 0xFF3B6BFF;
        if (id.contains("coal")) return 0xFF6B6B6B;
        if (id.contains("quartz")) return 0xFFF2EEE8;
        if (b == Blocks.ANCIENT_DEBRIS) return 0xFFB0703F;
        return 0xFFB46CFF;
    }

    /** Greedy merge of same-kind cells into boxes (runs along x, then rows along z, then layers along y). From Nyx. */
    private static List<AABB> merge(byte[] kind, byte want, int ox, int oy, int oz) {
        List<AABB> out = new ArrayList<>();
        boolean[] used = new boolean[4096];
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int i = (y * 16 + z) * 16 + x;
            if (used[i] || kind[i] != want) continue;
            int x2 = x;
            while (x2 + 1 < 16 && !used[i + (x2 + 1 - x)] && kind[i + (x2 + 1 - x)] == want) x2++;
            int z2 = z;
            outer:
            while (z2 + 1 < 16) {
                for (int xx = x; xx <= x2; xx++) {
                    int j = (y * 16 + z2 + 1) * 16 + xx;
                    if (used[j] || kind[j] != want) break outer;
                }
                z2++;
            }
            int y2 = y;
            outerY:
            while (y2 + 1 < 16) {
                for (int zz = z; zz <= z2; zz++) for (int xx = x; xx <= x2; xx++) {
                    int j = ((y2 + 1) * 16 + zz) * 16 + xx;
                    if (used[j] || kind[j] != want) break outerY;
                }
                y2++;
            }
            for (int yy = y; yy <= y2; yy++) for (int zz = z; zz <= z2; zz++) for (int xx = x; xx <= x2; xx++) used[(yy * 16 + zz) * 16 + xx] = true;
            out.add(new AABB(ox + x, oy + y, oz + z, ox + x2 + 1, oy + y2 + 1, oz + z2 + 1));
        }
        return out;
    }

    // =====================================================================================================
    // Drawing
    // =====================================================================================================

    @Override
    public void onRenderWorld(WorldRender ctx) {
        if (mc.player == null || sections.isEmpty()) return;
        int alpha = opacity.intValue();
        int budget = maxShapes.intValue();
        Vec3 me = mc.player.position();
        double r2 = range.get() * range.get() * 1.5, or2 = oreRange.get() * oreRange.get();
        boolean dim = buried.is("Dim");
        for (long[] q : queue) { // nearest sections first
            Section s = sections.get(key((int) q[0], (int) q[1], (int) q[2]));
            if (s == null) continue;
            if (outlineExposed.get()) for (Ore o : s.exposed) {
                if (budget-- <= 0) return;
                if (o.box.getCenter().distanceToSqr(me) > or2) continue;
                Gizmos.cuboid(o.box.inflate(0.01), GizmoStyle.strokeAndFill(o.color, 1.5f, (0x30 << 24) | (o.color & 0xFFFFFF))).setAlwaysOnTop();
            }
            if (dim) for (AABB b : s.buried) {
                if (budget-- <= 0) return;
                if (b.getCenter().distanceToSqr(me) > or2) continue;
                Gizmos.cuboid(b.inflate(0.005), GizmoStyle.strokeAndFill(0xA0101010, 1f, 0xB0101010));
            }
            if (empty.get()) for (AABB b : s.air) {
                if (budget-- <= 0) return;
                if (b.getCenter().distanceToSqr(me) > r2) continue;
                Gizmos.cuboid(b, GizmoStyle.strokeAndFill(0x60A0E8FF, 1f, (alpha << 24) | 0x7FC8FF));
            }
            if (fluids.get()) {
                for (AABB b : s.lava) {
                    if (budget-- <= 0) return;
                    Gizmos.cuboid(b, GizmoStyle.strokeAndFill(0xC0FF6A00, 1f, (Math.min(255, alpha * 2) << 24) | 0xFF6A00));
                }
                for (AABB b : s.water) {
                    if (budget-- <= 0) return;
                    Gizmos.cuboid(b, GizmoStyle.strokeAndFill(0x803060FF, 1f, (alpha << 24) | 0x3060FF));
                }
            }
        }
    }
}
