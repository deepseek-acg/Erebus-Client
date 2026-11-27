package dev.erebus.client.seed;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Finds structure start chunks in a loaded chunk, from marker blocks at positions fixed by the structure's code.
 *
 * <p>Desert pyramids, jungle temples and swamp huts are single pieces whose bounding box starts exactly at the
 * corner of their start chunk, in one of four orientations. Each has a marker that always lands inside that chunk:
 * <ul>
 *   <li>desert pyramid: the blue terracotta in the middle of the floor, at (10, 10) in the chunk, with orange
 *       terracotta two blocks away on each side (it's symmetric, so orientation doesn't matter);</li>
 *   <li>jungle temple: the stacked sticky pistons of the lever puzzle;</li>
 *   <li>swamp hut: the cauldron next to the crafting table.</li>
 * </ul>
 * A marker only counts if it sits at exactly the chunk position one of the four orientations puts it at, with its
 * partner block where that same orientation puts it; anything else is a player build or another structure.
 * Buried treasure is a chest at (9, 9) in its chunk, buried under sand, sandstone or gravel. Igloos and villages
 * and pillager outposts are found from their templates instead, by {@link TemplateStarts}, and mineshafts from their
 * start room, by {@link MineshaftRooms}.
 */
public final class StructureScanner {
    private StructureScanner() {
    }

    public enum Kind {
        DESERT_PYRAMID("Desert pyramid", 32, 8, 14357617),
        JUNGLE_TEMPLE("Jungle temple", 32, 8, 14357619),
        SWAMP_HUT("Swamp hut", 32, 8, 14357620),
        IGLOO("Igloo", 32, 8, 14357618),
        VILLAGE("Village", 34, 8, 10387312),
        OUTPOST("Pillager outpost", 32, 8, 165745296),
        MINESHAFT("Mineshaft", 1, 0, 0),
        BURIED_TREASURE("Buried treasure", 1, 0, 0);

        public final String label;
        public final int spacing;
        public final int separation;
        public final int salt;

        Kind(String label, int spacing, int separation, int salt) {
            this.label = label;
            this.spacing = spacing;
            this.separation = separation;
            this.salt = salt;
        }

        /** What this structure says about the seed. */
        public List<SeedSolver.Clue> clues(int chunkX, int chunkZ) {
            if (this == BURIED_TREASURE) return List.of(new SeedSolver.Chance(chunkX, chunkZ, 0.01F));
            if (this == MINESHAFT) return List.of(new SeedSolver.Mineshaft(chunkX, chunkZ));
            SeedSolver.Spread spread = new SeedSolver.Spread(spacing, separation, salt, chunkX, chunkZ);
            // an outpost's start chunk also passed a 1-in-5 roll
            if (this == OUTPOST) return List.of(spread, new SeedSolver.OutpostRoll(chunkX, chunkZ));
            return List.of(spread);
        }
    }

    public record Found(Kind kind, int chunkX, int chunkZ, BlockPos marker) {
    }

    // (marker x, z, partner x, z) for each orientation, in chunk coordinates
    private static final int[][] JUNGLE = {{10, 6, 9, 6}, {10, 8, 9, 8}, {6, 10, 6, 9}, {8, 10, 8, 9}};
    private static final int[][] SWAMP = {{4, 2, 3, 2}, {4, 6, 3, 6}, {2, 4, 2, 3}, {6, 4, 6, 3}};

    public static List<Found> scan(LevelChunk chunk, Set<Kind> kinds) {
        List<Found> out = new ArrayList<>();
        int cx = chunk.getPos().x, cz = chunk.getPos().z;
        int baseX = cx << 4, baseZ = cz << 4;
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            if (s == null || s.hasOnlyAir()) continue;
            boolean pyramid = kinds.contains(Kind.DESERT_PYRAMID) && s.maybeHas(b -> b.is(Blocks.BLUE_TERRACOTTA));
            boolean jungle = kinds.contains(Kind.JUNGLE_TEMPLE) && s.maybeHas(b -> b.is(Blocks.STICKY_PISTON));
            boolean swamp = kinds.contains(Kind.SWAMP_HUT) && s.maybeHas(b -> b.is(Blocks.CAULDRON));
            boolean treasure = kinds.contains(Kind.BURIED_TREASURE) && s.maybeHas(b -> b.is(Blocks.CHEST));
            if (!pyramid && !jungle && !swamp && !treasure) continue;
            int y0 = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                int wy = y0 + y;
                if (pyramid && s.getBlockState(10, y, 10).is(Blocks.BLUE_TERRACOTTA)
                        && is(chunk, baseX + 8, wy, baseZ + 10, Blocks.ORANGE_TERRACOTTA) && is(chunk, baseX + 12, wy, baseZ + 10, Blocks.ORANGE_TERRACOTTA)
                        && is(chunk, baseX + 10, wy, baseZ + 8, Blocks.ORANGE_TERRACOTTA) && is(chunk, baseX + 10, wy, baseZ + 12, Blocks.ORANGE_TERRACOTTA)) {
                    out.add(new Found(Kind.DESERT_PYRAMID, cx, cz, new BlockPos(baseX + 10, wy, baseZ + 10)));
                }
                if (jungle) {
                    for (int[] o : JUNGLE) {
                        // the upper piston of the column, with one below it and the third beside that one
                        if (s.getBlockState(o[0], y, o[1]).is(Blocks.STICKY_PISTON)
                                && is(chunk, baseX + o[0], wy - 1, baseZ + o[1], Blocks.STICKY_PISTON)
                                && is(chunk, baseX + o[2], wy - 1, baseZ + o[3], Blocks.STICKY_PISTON)
                                && !is(chunk, baseX + o[0], wy - 2, baseZ + o[1], Blocks.STICKY_PISTON)) {
                            out.add(new Found(Kind.JUNGLE_TEMPLE, cx, cz, new BlockPos(baseX + o[0], wy, baseZ + o[1])));
                            break;
                        }
                    }
                }
                if (swamp) {
                    for (int[] o : SWAMP) {
                        if (s.getBlockState(o[0], y, o[1]).is(Blocks.CAULDRON) && s.getBlockState(o[2], y, o[3]).is(Blocks.CRAFTING_TABLE)) {
                            out.add(new Found(Kind.SWAMP_HUT, cx, cz, new BlockPos(baseX + o[0], wy, baseZ + o[1])));
                            break;
                        }
                    }
                }
                if (treasure && s.getBlockState(9, y, 9).is(Blocks.CHEST)) {
                    BlockState above = chunk.getBlockState(new BlockPos(baseX + 9, wy + 1, baseZ + 9));
                    if (above.is(Blocks.SAND) || above.is(Blocks.SANDSTONE) || above.is(Blocks.GRAVEL) || above.is(Blocks.RED_SAND)) {
                        out.add(new Found(Kind.BURIED_TREASURE, cx, cz, new BlockPos(baseX + 9, wy, baseZ + 9)));
                    }
                }
            }
        }
        // one per structure: a chunk is one start chunk at most for each kind
        List<Found> unique = new ArrayList<>();
        for (Found f : out) {
            if (unique.stream().noneMatch(u -> u.kind() == f.kind())) unique.add(f);
        }
        return unique;
    }

    private static boolean is(LevelChunk chunk, int x, int y, int z, Block block) {
        return chunk.getBlockState(new BlockPos(x, y, z)).is(block);
    }
}
