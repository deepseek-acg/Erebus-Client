package dev.erebus.client.module.basefinding;

import dev.erebus.client.map.MapStore;
import dev.erebus.client.module.WorldRender;
import dev.erebus.client.notify.Notifications;
import dev.erebus.client.util.Chat;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bases and stashes found by Stash ESP (with Player Blocks), shared with the map.
 *
 * <p>Each find shows a toast, posts a chat line, is written to erebus/finds.txt and is marked on the map. From the
 * map's Finds list you can highlight any base, stash or structure in the world: a gold box around what was found,
 * a beam into the sky and a tracer from your crosshair.
 */
public final class BaseFinds {
    public record Find(String kind, ResourceKey<Level> dim, AABB area, int placed, int storage, int shulkers) {
    }

    private static final int COLOR = 0xFFFFC21A;
    private static final List<Find> FINDS = new ArrayList<>();
    private static final Set<String> HIGHLIGHTED = new HashSet<>();
    private static Object connection;

    private BaseFinds() {
    }

    /** True if this area overlaps an earlier find, or a spot you've marked explored on the map. */
    static boolean alreadyFound(ResourceKey<Level> dim, AABB area) {
        resetOnNewServer();
        for (Find f : FINDS) {
            if (f.dim == dim && f.area.inflate(16).intersects(area)) return true;
        }
        Vec3 c = area.getCenter();
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && dim == mc.level.dimension() && MapStore.exploredNear(c.x, c.z, 24);
    }

    static void post(String kind, ResourceKey<Level> dim, AABB area, int placed, int storage, int shulkers, boolean log) {
        resetOnNewServer();
        Minecraft mc = Minecraft.getInstance();
        Find f = new Find(kind, dim, area, placed, storage, shulkers);
        FINDS.add(f);
        if (FINDS.size() > 300) FINDS.remove(0);

        Vec3 c = area.getCenter();
        int x = (int) Math.floor(c.x), y = (int) area.minY, z = (int) Math.floor(c.z);
        int dist = mc.player != null ? (int) Math.sqrt(mc.player.position().distanceToSqr(c.x, mc.player.getY(), c.z)) : 0;
        StringBuilder what = new StringBuilder();
        if (placed > 0) what.append(placed).append(" player blocks, ");
        what.append(storage).append(" storage");
        if (shulkers > 0) what.append(", ").append(shulkers).append(" shulkers");

        Notifications.push(kind + " found", x + " " + z + " · " + dist + "m", Notifications.Kind.WARNING);
        Chat.info(kind + " found: " + what + " at " + x + " " + y + " " + z + " (" + dist + "m). Highlight it from the map (M).");
        MapStore.addAlert(new MapStore.Mark(x, y, z, kind.toLowerCase(Locale.ROOT).contains("stash") ? "stash" : "base",
                kind + ": " + what, 0));
        if (log) log(kind, dim, x, y, z, placed, storage, shulkers);
    }

    private static void log(String kind, ResourceKey<Level> dim, int x, int y, int z, int placed, int storage, int shulkers) {
        String line = String.format(Locale.ROOT, "%s  %s  %s  x=%d y=%d z=%d  player-blocks=%d storage=%d shulkers=%d  server=%s%n",
                LocalDateTime.now().withNano(0), dim.identifier().getPath(), kind, x, y, z, placed, storage, shulkers, MapStore.server());
        try {
            File dir = new File(Minecraft.getInstance().gameDirectory, "erebus");
            dir.mkdirs();
            Files.writeString(new File(dir, "finds.txt").toPath(), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------------------------------ highlights

    private static String key(MapStore.Mark m) {
        return m.kind() + "|" + m.x() + "|" + m.y() + "|" + m.z();
    }

    public static boolean isHighlighted(MapStore.Mark m) {
        return HIGHLIGHTED.contains(key(m));
    }

    /** Turns the in-world highlight of a map marker on or off; returns the new state. */
    public static boolean toggleHighlight(MapStore.Mark m) {
        String k = key(m);
        if (HIGHLIGHTED.remove(k)) return false;
        HIGHLIGHTED.add(k);
        return true;
    }

    public static void render(WorldRender ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (HIGHLIGHTED.isEmpty() || mc.level == null || !MapStore.ready()) return;
        Vec3 start = ctx.tracerStart();
        for (MapStore.Mark m : MapStore.alerts()) {
            if (!HIGHLIGHTED.contains(key(m))) continue;
            AABB area = areaOf(m);
            int color = m.kind().equals("structure") ? m.color() | 0xFF000000 : COLOR;
            Gizmos.cuboid(area, GizmoStyle.strokeAndFill(color, 3f, (0x22 << 24) | (color & 0xFFFFFF))).setAlwaysOnTop();
            Vec3 c = area.getCenter();
            Gizmos.line(new Vec3(c.x, area.minY, c.z), new Vec3(c.x, area.maxY + 120, c.z), color, 4f).setAlwaysOnTop();
            Gizmos.line(start, c, color, 2f).setAlwaysOnTop();
        }
    }

    /** The area of a find this session, or a box around the marker for older ones. */
    private static AABB areaOf(MapStore.Mark m) {
        for (Find f : FINDS) {
            Vec3 c = f.area.getCenter();
            if ((int) Math.floor(c.x) == m.x() && (int) Math.floor(c.z) == m.z()) return f.area;
        }
        int r = m.kind().equals("structure") ? 12 : 6;
        return new AABB(m.x() - r, m.y(), m.z() - r, m.x() + r + 1, m.y() + 8, m.z() + r + 1);
    }

    private static void resetOnNewServer() {
        Object conn = Minecraft.getInstance().getConnection();
        if (conn != connection) {
            connection = conn;
            FINDS.clear();
            HIGHLIGHTED.clear();
        }
    }

    /** Bounding box of a set of block positions. */
    static AABB boxOf(List<BlockPos> blocks) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : blocks) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }
}
