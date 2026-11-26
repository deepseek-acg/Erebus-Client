package dev.erebus.client.map;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Everything the map remembers, per server and per dimension, in .minecraft/erebus/maps/&lt;server&gt;/&lt;dimension&gt;/:
 * the map itself as 512x512-block PNG tiles (one pixel per block column) and the detected stashes / bases / spawners
 * in markers.txt. Only the tiles near what you're looking at are kept in memory; the rest stays on disk.
 */
public final class MapStore {
    public static final int R = 512;      // blocks per tile side
    public static final int LOW = 128;    // the zoomed-out copy of a tile (1 pixel = 4x4 blocks)
    private static final int MAX_FULL = 40, MAX_LOW = 600;

    /** A detected thing on the map. kind: base, stash, storage (one stash block), spawner, structure. explored: you've been there. */
    public record Mark(int x, int y, int z, String kind, String label, int color, boolean explored) {
        public Mark(int x, int y, int z, String kind, String label, int color) {
            this(x, y, z, kind, label, color, false);
        }

        public Mark withExplored(boolean e) {
            return new Mark(x, y, z, kind, label, color, e);
        }

        /** Same thing, ignoring whether it's been explored. */
        public boolean same(Mark o) {
            return x == o.x && y == o.y && z == o.z && kind.equals(o.kind) && label.equals(o.label);
        }
    }

    private static final class Tile {
        final int rx, rz;
        NativeImage full;         // owned by fullTex once it exists
        DynamicTexture fullTex;
        Identifier fullId;
        NativeImage low;
        DynamicTexture lowTex;
        Identifier lowId;
        boolean dirty, fullGpu, lowGpu;

        Tile(int rx, int rz) {
            this.rx = rx;
            this.rz = rz;
        }
    }

    private static String context;               // "server|dimension"
    private static File dir;
    private static String serverName = "", dimName = "";
    private static final LinkedHashMap<Long, Tile> tiles = new LinkedHashMap<>(64, 0.75f, true);
    private static final Set<Long> missing = new HashSet<>();   // no file on disk (checked once)
    private static int loadBudget;
    private static long lastSave;
    private static int texCounter;

    // thumbnails: per tile, 4x4 average colours (one per 128x128 blocks) - what's drawn when zoomed far out
    private static final Map<Long, int[]> thumbs = new HashMap<>();
    private static final java.util.ArrayDeque<Long> thumbQueue = new java.util.ArrayDeque<>();
    private static boolean thumbsDirty;


    // markers
    private static final List<Mark> alerts = new ArrayList<>();
    private static final Map<Long, List<Mark>> dots = new HashMap<>(); // per chunk: stash blocks / spawners
    private static boolean marksDirty;

    private static final Logger LOG = LoggerFactory.getLogger("Erebus");

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private MapStore() {
    }

    // =====================================================================================================
    // Which map we're on
    // =====================================================================================================

    /** Call every tick: switches maps when you change server or dimension (saving the old one). */
    public static void update() {
        String ctx = mc().level == null ? null : serverKey() + "|" + mc().level.dimension().identifier();
        if (ctx == null) {
            if (context != null) close();
            return;
        }
        if (!ctx.equals(context)) {
            close();
            context = ctx;
            serverName = serverKey();
            dimName = mc().level.dimension().identifier().toString();
            dir = new File(new File(new File(new File(mc().gameDirectory, "erebus"), "maps"), safe(serverName)), safe(dimName));
            dir.mkdirs();
            loadMarks();
            loadThumbs();
        }
        long now = System.currentTimeMillis();
        if (now - lastSave > 1500) { // save one changed tile at a time, so it never stutters
            lastSave = now;
            for (Tile t : tiles.values()) if (t.dirty && t.full != null) { saveTile(t); break; }
            if (marksDirty) saveMarks();
            if (thumbsDirty) saveThumbs();
        }
        // older tiles without a thumbnail yet: make one, a tile per tick
        Long q = thumbQueue.poll();
        if (q != null && !thumbs.containsKey(q)) {
            int rx = (int) (q >> 32), rz = (int) (long) q;
            try (InputStream in = new FileInputStream(file(rx, rz))) {
                NativeImage img = NativeImage.read(in);
                thumbs.put(q, thumb(img));
                img.close();
                thumbsDirty = true;
            } catch (Throwable ignored) {
            }
        }
    }

    public static boolean ready() {
        return context != null;
    }

    public static String server() {
        return serverName;
    }

    public static String dimension() {
        return dimName;
    }

    public static File folder() {
        return dir;
    }

    private static String serverKey() {
        var sd = mc().getCurrentServer();
        if (sd != null && sd.ip != null) return sd.ip.toLowerCase(Locale.ROOT);
        try {
            if (mc().getSingleplayerServer() != null) return "singleplayer-" + mc().getSingleplayerServer().getWorldData().getLevelName();
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    private static String safe(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /** Saves everything and lets go of all tiles (leaving a server or dimension, or turning the map off). */
    public static void close() {
        if (context == null) return;
        for (Tile t : tiles.values()) {
            if (t.dirty && t.full != null) saveTile(t);
            freeFull(t);
            freeLow(t);
        }
        tiles.clear();
        missing.clear();
        if (marksDirty) saveMarks();
        if (thumbsDirty) saveThumbs();
        thumbs.clear();
        thumbQueue.clear();
        alerts.clear();
        dots.clear();
        context = null;
    }

    // =====================================================================================================
    // Writing the map
    // =====================================================================================================

    /** Sets the colour of one block column (ARGB). */
    public static void set(int x, int z, int argb) {
        if (context == null) return;
        int rx = Math.floorDiv(x, R), rz = Math.floorDiv(z, R);
        Tile t = tile(rx, rz, true, true);
        if (t == null || t.full == null) return;
        int lx = Math.floorMod(x, R), lz = Math.floorMod(z, R);
        if (t.full.getPixel(lx, lz) == argb) return;
        t.full.setPixel(lx, lz, argb);
        t.dirty = true;
        t.fullGpu = true;
        if ((lx & 3) == 0 && (lz & 3) == 0 && t.low != null) {
            t.low.setPixel(lx >> 2, lz >> 2, argb);
            t.lowGpu = true;
        }
    }

    /** Whether this column has been mapped at all (used to fill in chunks you've never seen first). */
    public static boolean has(int x, int z) {
        Tile t = tile(Math.floorDiv(x, R), Math.floorDiv(z, R), false, true);
        return t != null && t.full != null && (t.full.getPixel(Math.floorMod(x, R), Math.floorMod(z, R)) >>> 24) != 0;
    }

    // =====================================================================================================
    // Drawing the map
    // =====================================================================================================

    /** Call once per drawn frame: limits how many tiles get read from disk per frame. */
    public static void beginFrame() {
        loadBudget = 3;
    }

    /** The texture of a tile (low = the zoomed-out copy), or null if that area was never mapped. */
    public static Identifier texture(int rx, int rz, boolean low) {
        if (context == null) return null;
        Tile t = tile(rx, rz, false, !low);
        if (t == null) return null;
        if (low) {
            if (t.low == null) return null;
            if (t.lowTex == null) {
                t.lowTex = new DynamicTexture(() -> "erebus map low", t.low);
                t.lowId = Identifier.fromNamespaceAndPath("erebus", "map/l" + (texCounter++));
                mc().getTextureManager().register(t.lowId, t.lowTex);
                t.lowGpu = false;
            } else if (t.lowGpu) {
                t.lowTex.upload();
                t.lowGpu = false;
            }
            return t.lowId;
        }
        if (t.full == null) return null;
        if (t.fullTex == null) {
            t.fullTex = new DynamicTexture(() -> "erebus map", t.full);
            t.fullId = Identifier.fromNamespaceAndPath("erebus", "map/f" + (texCounter++));
            mc().getTextureManager().register(t.fullId, t.fullTex);
            t.fullGpu = false;
        } else if (t.fullGpu) {
            t.fullTex.upload();
            t.fullGpu = false;
        }
        return t.fullId;
    }

    // =====================================================================================================
    // Tiles in memory / on disk
    // =====================================================================================================

    private static File file(int rx, int rz) {
        return new File(dir, "r." + rx + "." + rz + ".png");
    }

    private static Tile tile(int rx, int rz, boolean create, boolean needFull) {
        long key = ((long) rx << 32) ^ (rz & 0xFFFFFFFFL);
        Tile t = tiles.get(key);
        if (t != null && (!needFull || t.full != null)) return t;
        if (t == null && missing.contains(key) && !create) return null;
        if (!create && loadBudget <= 0) return t; // read it from disk in a later frame
        File f = file(rx, rz);
        NativeImage img = null;
        if (f.exists()) {
            loadBudget--;
            try (InputStream in = new FileInputStream(f)) {
                img = NativeImage.read(in);
                if (img.getWidth() != R) { img.close(); img = null; }
            } catch (Throwable e) {
                img = null;
            }
        }
        if (img == null) {
            if (!create) { missing.add(key); return t; }
            img = new NativeImage(R, R, true);
            img.fillRect(0, 0, R, R, 0);
        }
        missing.remove(key);
        if (t == null) {
            t = new Tile(rx, rz);
            tiles.put(key, t);
        }
        if (t.low == null) {
            t.low = new NativeImage(LOW, LOW, true);
            for (int x = 0; x < LOW; x++) for (int z = 0; z < LOW; z++) t.low.setPixel(x, z, img.getPixel(x << 2, z << 2));
        }
        if (needFull) {
            t.full = img;
        } else {
            img.close();
        }
        trim();
        return t;
    }

    private static void trim() {
        int full = 0;
        for (Tile t : tiles.values()) if (t.full != null) full++;
        Iterator<Tile> it = tiles.values().iterator(); // least recently used first
        while (it.hasNext() && (full > MAX_FULL || tiles.size() > MAX_LOW)) {
            Tile t = it.next();
            if (t.full != null && full > MAX_FULL) {
                if (t.dirty) saveTile(t);
                freeFull(t);
                full--;
            }
            if (tiles.size() > MAX_LOW && t.full == null) {
                freeLow(t);
                it.remove();
            }
        }
    }

    private static void freeFull(Tile t) {
        if (t.fullTex != null) {
            mc().getTextureManager().release(t.fullId); // also frees the image
            t.fullTex = null;
            t.fullId = null;
        } else if (t.full != null) t.full.close();
        t.full = null;
    }

    private static void freeLow(Tile t) {
        if (t.lowTex != null) {
            mc().getTextureManager().release(t.lowId);
            t.lowTex = null;
            t.lowId = null;
        } else if (t.low != null) t.low.close();
        t.low = null;
    }

    private static void saveTile(Tile t) {
        try {
            dir.mkdirs();
            t.full.writeToFile(file(t.rx, t.rz));
            t.dirty = false;
            thumbs.put(((long) t.rx << 32) ^ (t.rz & 0xFFFFFFFFL), thumb(t.full));
            thumbsDirty = true;
        } catch (Throwable e) {
            LOG.warn("Erebus map: couldn't save a map tile", e);
            t.dirty = false; // don't retry every second
        }
    }

    // =====================================================================================================
    // Thumbnails
    // =====================================================================================================

    /** 4x4 average colours of a tile (0 = nothing mapped in that 128x128 part). */
    private static int[] thumb(NativeImage img) {
        int[] out = new int[16];
        for (int cx = 0; cx < 4; cx++) for (int cz = 0; cz < 4; cz++) {
            long r = 0, g = 0, b = 0, n = 0;
            for (int x = cx * 128; x < cx * 128 + 128; x += 8) for (int z = cz * 128; z < cz * 128 + 128; z += 8) {
                int p = img.getPixel(x, z);
                if ((p >>> 24) == 0) continue;
                r += (p >> 16) & 255;
                g += (p >> 8) & 255;
                b += p & 255;
                n++;
            }
            out[cz * 4 + cx] = n == 0 ? 0 : 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
        }
        return out;
    }

    /** Every mapped tile's thumbnail: key = tile (rx << 32 | rz). */
    public static Map<Long, int[]> thumbs() {
        return thumbs;
    }

    private static void loadThumbs() {
        thumbs.clear();
        thumbQueue.clear();
        thumbsDirty = false;
        File f = new File(dir, "thumbs.txt");
        if (f.exists()) {
            try {
                for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                    String[] p = line.trim().split(" ");
                    if (p.length != 18) continue;
                    int[] t = new int[16];
                    for (int i = 0; i < 16; i++) t[i] = (int) Long.parseLong(p[i + 2], 16);
                    thumbs.put(((long) Integer.parseInt(p[0]) << 32) ^ (Integer.parseInt(p[1]) & 0xFFFFFFFFL), t);
                }
            } catch (Throwable e) {
                LOG.warn("Erebus map: couldn't read thumbnails", e);
            }
        }
        File[] files = dir.listFiles((d, n) -> n.startsWith("r.") && n.endsWith(".png"));
        if (files != null) for (File file : files) {
            String[] p = file.getName().split("\\.");
            if (p.length != 4) continue;
            try {
                long key = ((long) Integer.parseInt(p[1]) << 32) ^ (Integer.parseInt(p[2]) & 0xFFFFFFFFL);
                if (!thumbs.containsKey(key)) thumbQueue.add(key);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static void saveThumbs() {
        thumbsDirty = false;
        StringBuilder sb = new StringBuilder();
        for (var e : thumbs.entrySet()) {
            sb.append((int) (e.getKey() >> 32)).append(' ').append((int) (long) e.getKey());
            for (int c : e.getValue()) sb.append(' ').append(Integer.toHexString(c));
            sb.append('\n');
        }
        try {
            Files.writeString(new File(dir, "thumbs.txt").toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            LOG.warn("Erebus map: couldn't save thumbnails", e);
        }
    }

    // =====================================================================================================
    // Markers
    // =====================================================================================================

    public static void addAlert(Mark m) {
        if (context == null) return;
        for (Mark a : alerts) if (a.kind().equals(m.kind()) && Math.abs(a.x() - m.x()) < 24 && Math.abs(a.z() - m.z()) < 24) return; // already there
        alerts.add(m);
        marksDirty = true;
    }

    /** A recognised structure; false if one of the same kind was already recorded within {@code radius} blocks. */
    public static boolean addStructure(Mark m, int radius) {
        if (context == null) return false;
        for (Mark a : alerts)
            if (a.kind().equals("structure") && a.label().equals(m.label()) && Math.abs(a.x() - m.x()) < radius && Math.abs(a.z() - m.z()) < radius) return false;
        alerts.add(m);
        marksDirty = true;
        return true;
    }

    /** Forgets every structure marker on this server/dimension's map; returns how many. */
    public static int clearStructures() {
        int before = alerts.size();
        alerts.removeIf(m -> m.kind().equals("structure"));
        marksDirty = true;
        return before - alerts.size();
    }

    /** What Stash ESP found in one chunk (replaces what we had there; empty list = nothing there now). */
    public static void setDots(long chunk, List<Mark> found) {
        if (context == null) return;
        List<Mark> old = dots.get(chunk);
        if (found.isEmpty()) {
            if (old != null) { dots.remove(chunk); marksDirty = true; }
            return;
        }
        if (found.equals(old)) return;
        dots.put(chunk, List.copyOf(found));
        marksDirty = true;
    }

    /** Marks (or unmarks) a base / stash / structure as explored. */
    public static void setExplored(Mark m, boolean explored) {
        for (int i = 0; i < alerts.size(); i++) {
            Mark a = alerts.get(i);
            if (a.same(m) && a.explored() != explored) {
                alerts.set(i, a.withExplored(explored));
                marksDirty = true;
            }
        }
    }

    /** Forgets a base / stash / structure marker. */
    public static void removeAlert(Mark m) {
        if (alerts.removeIf(a -> a.same(m))) marksDirty = true;
    }

    /** True if an explored base or stash marker is within {@code r} blocks of this spot. */
    public static boolean exploredNear(double x, double z, double r) {
        if (context == null) return false;
        for (Mark a : alerts)
            if (a.explored() && !a.kind().equals("structure") && Math.abs(a.x() - x) <= r && Math.abs(a.z() - z) <= r) return true;
        return false;
    }

    public static List<Mark> alerts() {
        return alerts;
    }

    public static Iterable<List<Mark>> dots() {
        return dots.values();
    }

    public static int dotCount() {
        int n = 0;
        for (List<Mark> l : dots.values()) n += l.size();
        return n;
    }

    private static void loadMarks() {
        alerts.clear();
        dots.clear();
        marksDirty = false;
        File f = new File(dir, "markers.txt");
        if (!f.exists()) return;
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String[] p = line.split("\\|", 6);
                if (p.length < 6) continue;
                boolean ex = p[0].endsWith("!");
                String kind = ex ? p[0].substring(0, p[0].length() - 1) : p[0];
                Mark m = new Mark(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), kind, p[5], (int) Long.parseLong(p[4], 16), ex);
                if (m.kind().equals("base") || m.kind().equals("stash") || m.kind().equals("structure")) alerts.add(m);
                else dots.computeIfAbsent(chunkKey(m.x() >> 4, m.z() >> 4), k -> new ArrayList<>()).add(m);
            }
        } catch (Throwable e) {
            LOG.warn("Erebus map: couldn't read markers", e);
        }
    }

    private static void saveMarks() {
        marksDirty = false;
        StringBuilder sb = new StringBuilder();
        for (Mark m : alerts) line(sb, m);
        for (List<Mark> l : dots.values()) for (Mark m : l) line(sb, m);
        try {
            dir.mkdirs();
            Files.writeString(new File(dir, "markers.txt").toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            LOG.warn("Erebus map: couldn't save markers", e);
        }
    }

    private static void line(StringBuilder sb, Mark m) {
        sb.append(m.kind()).append(m.explored() ? "!" : "").append('|').append(m.x()).append('|').append(m.y()).append('|').append(m.z()).append('|')
            .append(Integer.toHexString(m.color())).append('|').append(m.label().replace('\n', ' ')).append('\n');
    }

    public static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }
}
