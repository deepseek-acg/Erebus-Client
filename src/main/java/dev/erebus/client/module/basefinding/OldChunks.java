package dev.erebus.client.module.basefinding;

import dev.erebus.client.module.Category;
import dev.erebus.client.module.Module;
import dev.erebus.client.module.WorldRender;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.NumberSetting;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shows which loaded chunks were first generated in an old version of the game. Old chunks mean someone travelled
 * there long ago, so following a line of them leads along old trails toward old bases.
 *
 * <p>Overworld: chunks made before 1.18 still have the old bedrock floor at y 0-4. Nether: chunks made before 1.16 have
 * none of the 1.16 blocks (basalt, blackstone, nylium, stems, soul soil, ancient debris, nether gold ore). Ported from Nyx.
 */
public final class OldChunks extends Module {
    private enum Age { OLD, NEW, UNKNOWN }

    private final BoolSetting showOld = add(new BoolSetting("Show old", "Mark chunks generated in an old version (red).", true));
    private final BoolSetting showNew = add(new BoolSetting("Show new", "Also mark chunks generated in a recent version (faint green).", false));
    private final NumberSetting height = add(new NumberSetting("Draw height", "How far below your feet the chunk squares are drawn (0 = at your feet).",
            0, -32, 32, 1));
    private final BoolSetting outline = add(new BoolSetting("Outline only", "Draw just the chunk border, without the fill.", false));

    private final Map<Long, Age> ages = new HashMap<>();
    private final List<Long> queue = new ArrayList<>();
    private int queueIndex;

    public OldChunks() {
        super("Old Chunks", "Shows chunks generated in old versions of the game. Old chunks mark old trails and old bases.", Category.BASEFINDING);
        addVisuals("Old chunks near you get posts at their corners and a slow shimmer.");
    }

    @Override
    protected void onEnable() {
        ages.clear();
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
            ages.keySet().removeIf(k -> !inRange.contains(k));
            int n = 0;
            for (Age a : ages.values()) if (a == Age.OLD) n++;
            info = n > 0 ? String.valueOf(n) : null;
        }
        for (int n = 0; n < 40 && queueIndex < queue.size(); n++) {
            long key = queue.get(queueIndex++);
            Age a = ages.get(key);
            if (a == null || a == Age.UNKNOWN) ages.put(key, judge(key));
        }
    }

    private Age judge(long key) {
        LevelChunk chunk = mc.level.getChunkSource().getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), ChunkStatus.FULL, false);
        if (chunk == null) return Age.UNKNOWN;
        LevelChunkSection[] sections = chunk.getSections();
        if (mc.level.dimension() == Level.OVERWORLD) {
            // the section holding y 0-15: old chunks keep their old bedrock floor there
            int idx = chunk.getSectionIndex(0);
            if (idx < 0 || idx >= sections.length) return Age.UNKNOWN;
            LevelChunkSection s = sections[idx];
            if (s == null || s.hasOnlyAir()) return Age.UNKNOWN;
            if (!s.maybeHas(st -> st.is(Blocks.BEDROCK))) return Age.NEW;
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (s.getBlockState(x, y, z).is(Blocks.BEDROCK)) return Age.OLD;
                    }
                }
            }
            return Age.NEW;
        }
        if (mc.level.dimension() == Level.NETHER) {
            boolean any = false;
            for (LevelChunkSection s : sections) {
                if (s == null || s.hasOnlyAir()) continue;
                any = true;
                if (s.maybeHas(OldChunks::isNewNetherBlock)) {
                    // maybeHas can be a false alarm on mixed palettes: confirm
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                if (isNewNetherBlock(s.getBlockState(x, y, z))) return Age.NEW;
                            }
                        }
                    }
                }
            }
            return any ? Age.OLD : Age.UNKNOWN;
        }
        return Age.UNKNOWN; // the End has no reliable marker
    }

    private static boolean isNewNetherBlock(BlockState s) {
        return s.is(Blocks.BASALT) || s.is(Blocks.BLACKSTONE) || s.is(Blocks.CRIMSON_NYLIUM) || s.is(Blocks.WARPED_NYLIUM)
                || s.is(Blocks.SOUL_SOIL) || s.is(Blocks.CRIMSON_STEM) || s.is(Blocks.WARPED_STEM) || s.is(Blocks.ANCIENT_DEBRIS)
                || s.is(Blocks.NETHER_GOLD_ORE);
    }

    @Override
    public void onRenderWorld(WorldRender ctx) {
        if (mc.player == null || ages.isEmpty()) return;
        double y = Math.floor(mc.player.getY()) - height.intValue();
        for (Map.Entry<Long, Age> e : ages.entrySet()) {
            Age a = e.getValue();
            int color;
            if (a == Age.OLD && showOld.get()) color = 0xFFFF3B3B;
            else if (a == Age.NEW && showNew.get()) color = 0xFF3BFF7A;
            else continue;
            int x = ChunkPos.getX(e.getKey()) << 4, z = ChunkPos.getZ(e.getKey()) << 4;
            AABB box = new AABB(x, y, z, x + 16, y + 0.05, z + 16);
            int fillA = outline.get() ? 0 : a == Age.OLD ? 0x45 : 0x20;
            Gizmos.cuboid(box, fillA > 0 ? GizmoStyle.strokeAndFill(color, 1f, (fillA << 24) | (color & 0xFFFFFF)) : GizmoStyle.stroke(color, 1f));
            if (a == Age.OLD && visuals() && Math.abs((x >> 4) - mc.player.chunkPosition().x) <= 6 && Math.abs((z >> 4) - mc.player.chunkPosition().z) <= 6) {
                // posts at the corners and a shimmer sweeping across
                for (int k = 0; k < 4; k++) {
                    double px = x + ((k & 1) == 0 ? 0 : 16), pz = z + ((k & 2) == 0 ? 0 : 16);
                    Gizmos.line(new Vec3(px, y, pz), new Vec3(px, y + 2.5, pz), color, 2f);
                }
                double sweep = x + 16 * dev.erebus.client.render.Visuals.phase(3.0, ((x >> 4) + (z >> 4)) * 0.13);
                Gizmos.line(new Vec3(sweep, y + 0.06, z), new Vec3(sweep, y + 0.06, z + 16), dev.erebus.client.render.Visuals.fade(color, 0.7f), 2f);
            }
        }
    }
}
