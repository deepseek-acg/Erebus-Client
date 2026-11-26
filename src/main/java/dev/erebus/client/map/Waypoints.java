package dev.erebus.client.map;

import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Your own waypoints, saved per server and dimension next to the map tiles (waypoints.txt). */
public final class Waypoints {
    public record Waypoint(String name, int x, int y, int z, int color) {
    }

    public static final int[] COLORS = {0xFFFFD24A, 0xFF4FD6E8, 0xFFFF5C6C, 0xFF5FD38A, 0xFFB98CFF, 0xFFFF9A2E};

    private static final List<Waypoint> LIST = new ArrayList<>();
    private static File loadedFrom;

    private Waypoints() {
    }

    /** Waypoints for the current server and dimension (empty when the map isn't recording). */
    public static List<Waypoint> all() {
        sync();
        return Collections.unmodifiableList(LIST);
    }

    public static Waypoint add(String name, int x, int y, int z) {
        sync();
        if (!MapStore.ready()) return null;
        Waypoint w = new Waypoint(name, x, y, z, COLORS[LIST.size() % COLORS.length]);
        LIST.add(w);
        save();
        return w;
    }

    public static void remove(Waypoint w) {
        sync();
        if (LIST.remove(w)) save();
    }

    public static Waypoint rename(Waypoint w, String name) {
        sync();
        int i = LIST.indexOf(w);
        if (i < 0 || name.isBlank()) return w;
        Waypoint renamed = new Waypoint(name.trim(), w.x(), w.y(), w.z(), w.color());
        LIST.set(i, renamed);
        save();
        return renamed;
    }

    public static String nextName() {
        sync();
        return "Waypoint " + (LIST.size() + 1);
    }

    private static void sync() {
        File dir = MapStore.ready() ? MapStore.folder() : null;
        if (dir == null) {
            LIST.clear();
            loadedFrom = null;
            return;
        }
        if (dir.equals(loadedFrom)) return;
        loadedFrom = dir;
        LIST.clear();
        File f = new File(dir, "waypoints.txt");
        if (!f.exists()) return;
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String[] p = line.split("\\|", 5);
                if (p.length < 5) continue;
                LIST.add(new Waypoint(p[4], Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                        (int) Long.parseLong(p[3], 16)));
            }
        } catch (Exception e) {
            LoggerFactory.getLogger("Erebus").warn("Couldn't read waypoints", e);
        }
    }

    private static void save() {
        if (loadedFrom == null) return;
        StringBuilder sb = new StringBuilder();
        for (Waypoint w : LIST) {
            sb.append(w.x()).append('|').append(w.y()).append('|').append(w.z()).append('|')
                    .append(Integer.toHexString(w.color())).append('|').append(w.name().replace('\n', ' ')).append('\n');
        }
        try {
            loadedFrom.mkdirs();
            Files.writeString(new File(loadedFrom, "waypoints.txt").toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LoggerFactory.getLogger("Erebus").warn("Couldn't save waypoints", e);
        }
    }
}
