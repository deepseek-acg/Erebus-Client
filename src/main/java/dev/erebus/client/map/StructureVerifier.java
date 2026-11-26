package dev.erebus.client.map;

import dev.erebus.client.seed.StructureScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Confirms a structure before the map marks it, so builds that happen to use the same blocks aren't flagged. Each
 * method looks at the 3x3-chunk area around (cx, cz) and returns where the structure is, or null.
 *
 * <ul>
 *   <li>Code-built structures are checked against the exact positions their generation code puts blocks at
 *       ({@link StructureChecks}).</li>
 *   <li>Template-built ones are matched against the game's own structure templates ({@link StructureTemplates}).</li>
 * </ul>
 */
public final class StructureVerifier {
    private StructureVerifier() {
    }

    private static Level level() {
        return Minecraft.getInstance().level;
    }

    public static BlockPos desertPyramid(int cx, int cz) {
        return StructureChecks.scanned(cx, cz, StructureScanner.Kind.DESERT_PYRAMID);
    }

    public static BlockPos jungleTemple(int cx, int cz) {
        return StructureChecks.scanned(cx, cz, StructureScanner.Kind.JUNGLE_TEMPLE);
    }

    public static BlockPos swampHut(int cx, int cz) {
        return StructureChecks.scanned(cx, cz, StructureScanner.Kind.SWAMP_HUT);
    }

    public static BlockPos strongholdPortal(int cx, int cz) {
        return level() == null ? null : StructureChecks.strongholdPortal(level(), cx, cz);
    }

    public static BlockPos monument(int cx, int cz) {
        return level() == null ? null : StructureChecks.monument(level(), cx, cz);
    }

    public static BlockPos fortress(int cx, int cz) {
        return level() == null ? null : StructureChecks.fortress(level(), cx, cz);
    }

    public static BlockPos endCity(int cx, int cz) {
        if (level() == null) return null;
        List<StructureTemplates.Template> t = StructureTemplates.load("end_city", StructureTemplates.END_CITY, 40);
        return StructureTemplates.match(level(), cx, cz, t, StructureTemplates.END_CITY, 0.8, 40);
    }

    public static BlockPos bastion(int cx, int cz) {
        if (level() == null) return null;
        List<StructureTemplates.Template> t = StructureTemplates.load("bastion", StructureTemplates.BASTION, 60);
        return StructureTemplates.match(level(), cx, cz, t, StructureTemplates.BASTION, 0.6, 60);
    }

    /** A ruined portal template match, and not a lit portal (a player's working portal is never a ruin). */
    public static BlockPos ruinedPortal(int cx, int cz) {
        Level level = level();
        if (level == null) return null;
        List<StructureTemplates.Template> t = StructureTemplates.load("ruined_portal", StructureTemplates.RUINED_PORTAL, 15);
        // the brick trim (bricks, slabs, stairs, walls) has to be in place too: a decorative frame on a netherrack
        // floor matches the obsidian and netherrack of the plainer templates, but not their trim
        BlockPos p = StructureTemplates.match(level, cx, cz, t, StructureTemplates.RUINED_PORTAL, 0.7, 15,
                b -> b == Blocks.STONE_BRICKS || b == Blocks.STONE_BRICK_SLAB || b == Blocks.STONE_BRICK_STAIRS || b == Blocks.STONE_BRICK_WALL
                        || b == Blocks.STONE || b == Blocks.STONE_SLAB || b == Blocks.SMOOTH_STONE_SLAB, 3);
        if (p == null) return null;
        for (int dx = -6; dx <= 6; dx++) for (int dy = -6; dy <= 6; dy++) for (int dz = -6; dz <= 6; dz++) {
            if (level.getBlockState(p.offset(dx, dy, dz)).is(Blocks.NETHER_PORTAL)) return null;
        }
        return p;
    }

    /** How many templates of each kind loaded (for checking the game's data is reachable). */
    public static String templateCounts() {
        return "end_city " + StructureTemplates.load("end_city", StructureTemplates.END_CITY, 40).size()
                + ", bastion " + StructureTemplates.load("bastion", StructureTemplates.BASTION, 60).size()
                + ", ruined_portal " + StructureTemplates.load("ruined_portal", StructureTemplates.RUINED_PORTAL, 15).size();
    }

}
