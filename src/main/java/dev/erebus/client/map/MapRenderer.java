package dev.erebus.client.map;

import dev.erebus.client.render.Render2D;
import dev.erebus.client.render.font.Icon;
import dev.erebus.client.render.font.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Draws the map (tiles, markers, waypoints and players) into a rectangle. Used by the minimap and the full map. */
public final class MapRenderer {
    public static final int BASE = 0xFFFFC21A;
    public static final int STASH = 0xFFF472B6;
    public static final int SPAWNER = 0xFFDC2626;
    public static final int PLAYER = 0xFFFF5C6C;
    public static final int EXPLORED = 0xFF7A7A80;
    public static final int BACKGROUND = 0xFF0E0D13;

    /** What to draw on top of the map. */
    public static final class Layers {
        public boolean bases = true, stashes = true, storage = true, spawners = true, players = true, structures = true, waypoints = true;
        public boolean hideExplored;
        public boolean chunkGrid;
        /** Structures predicted from the seed, and which kinds. */
        public boolean predicted;
        public java.util.Set<dev.erebus.client.seed.StructurePredictor.Kind> predictedKinds =
                java.util.EnumSet.noneOf(dev.erebus.client.seed.StructurePredictor.Kind.class);
    }

    /** Something drawn on the map, and where it ended up on screen (for hover text and clicks). */
    public record Drawn(float sx, float sy, String title, String detail, int color, MapStore.Mark mark, Waypoints.Waypoint waypoint) {
    }

    private MapRenderer() {
    }

    /**
     * @param cx,cz    world position at the middle of the rectangle
     * @param scale    screen units per block
     * @param rotation degrees to turn the map (0 = north up)
     * @param out      filled with the markers drawn (may be null)
     */
    public static void draw(GuiGraphics g, int x, int y, int w, int h, double cx, double cz, double scale, float rotation,
                            Layers layers, List<Drawn> out) {
        g.fill(x, y, x + w, y + h, BACKGROUND);
        if (!MapStore.ready()) return;
        MapStore.beginFrame();
        g.enableScissor(x, y, x + w, y + h);
        float mx = x + w / 2f, my = y + h / 2f;
        double rad = Math.toRadians(rotation);
        double reach = Math.hypot(w, h) / 2 / scale + 2; // world blocks from the middle to a corner
        boolean low = scale < 1.0;
        int r0x = (int) Math.floor((cx - reach) / MapStore.R), r1x = (int) Math.floor((cx + reach) / MapStore.R);
        int r0z = (int) Math.floor((cz - reach) / MapStore.R), r1z = (int) Math.floor((cz + reach) / MapStore.R);
        // zoomed far out (or a lot of tiles on screen): each tile as 4x4 averaged colours instead of its texture
        long visibleTiles = (long) (r1x - r0x + 1) * (r1z - r0z + 1);
        boolean far = rotation == 0 && (scale < 1 / 6.0 || visibleTiles > 160);

        if (far) drawThumbnails(g, x, y, w, h, cx, cz, scale, mx, my);

        g.pose().pushMatrix();
        g.pose().translate(mx, my);
        if (rotation != 0) g.pose().rotate((float) rad);
        g.pose().scale((float) scale, (float) scale);
        if (!far) {
            for (int rx = r0x; rx <= r1x; rx++) {
                for (int rz = r0z; rz <= r1z; rz++) {
                    Identifier tex = MapStore.texture(rx, rz, low);
                    boolean useLow = low;
                    if (tex == null && !low) { // full-size copy still loading: show the zoomed-out one meanwhile
                        tex = MapStore.texture(rx, rz, true);
                        useLow = true;
                    }
                    if (tex == null) continue;
                    g.pose().pushMatrix();
                    g.pose().translate((float) ((double) rx * MapStore.R - cx), (float) ((double) rz * MapStore.R - cz));
                    if (useLow) {
                        g.pose().scale(4f, 4f);
                        g.blit(RenderPipelines.GUI_TEXTURED, tex, 0, 0, 0f, 0f, MapStore.LOW, MapStore.LOW, MapStore.LOW, MapStore.LOW);
                    } else {
                        g.blit(RenderPipelines.GUI_TEXTURED, tex, 0, 0, 0f, 0f, MapStore.R, MapStore.R, MapStore.R, MapStore.R);
                    }
                    g.pose().popMatrix();
                }
            }
        }
        g.pose().popMatrix();

        if (layers.chunkGrid && rotation == 0 && scale >= 2) drawChunkGrid(g, x, y, w, h, cx, cz, scale, mx, my);

        // ---- markers (drawn flat, so they stay crisp at any zoom) -------------------------------------
        double cos = Math.cos(rad), sin = Math.sin(rad);
        float dotSize = (float) Math.max(1, Math.min(3, scale * 1.5));
        // zoomed out, markers would pile up: draw one per little patch of screen ("+N more" on hover)
        int cell = scale >= 1 ? 0 : scale >= 0.25 ? 5 : 9;
        Map<Long, int[]> cells = cell > 0 ? new HashMap<>() : null;
        boolean showStorage = layers.storage && scale >= 0.5, showSpawners = layers.spawners && scale >= 1 / 24.0;
        if (showStorage || showSpawners) {
            for (List<MapStore.Mark> l : MapStore.dots()) {
                for (MapStore.Mark m : l) {
                    boolean spawner = m.kind().equals("spawner");
                    if (spawner ? !showSpawners : !showStorage) continue;
                    float[] p = toScreen(m.x() + 0.5, m.z() + 0.5, cx, cz, scale, cos, sin, mx, my);
                    if (!inside(p, x, y, w, h) || crowded(cells, cell, p, spawner ? 1 : 2)) continue;
                    if (spawner) diamond(g, p[0], p[1], 3, SPAWNER);
                    else square(g, p[0], p[1], dotSize, m.color());
                    if (out != null) out.add(new Drawn(p[0], p[1], m.label(), coords(m), spawner ? SPAWNER : m.color(), null, null));
                    claim(cells, cell, p, spawner ? 1 : 2, out);
                }
            }
        }
        for (MapStore.Mark m : MapStore.alerts()) {
            if (m.explored() && layers.hideExplored) continue;
            String detail = coords(m) + (m.explored() ? " · explored" : "");
            if (m.kind().equals("structure")) {
                if (!layers.structures) continue;
                float[] p = toScreen(m.x() + 0.5, m.z() + 0.5, cx, cz, scale, cos, sin, mx, my);
                if (!inside(p, x, y, w, h) || crowded(cells, cell * 2, p, 3)) continue;
                int c = m.explored() ? EXPLORED : m.color() | 0xFF000000;
                if (scale >= 1 / 48.0) badge(g, p[0], p[1], m.label().substring(0, 1), c);
                else square(g, p[0], p[1], 2, c);
                if (out != null) out.add(new Drawn(p[0], p[1], m.label(), detail, c, m, null));
                claim(cells, cell * 2, p, 3, out);
                continue;
            }
            boolean base = m.kind().equals("base");
            if (base ? !layers.bases : !layers.stashes) continue;
            float[] p = toScreen(m.x() + 0.5, m.z() + 0.5, cx, cz, scale, cos, sin, mx, my);
            if (!inside(p, x, y, w, h) || crowded(cells, cell * 2, p, 4)) continue;
            int c = m.explored() ? EXPLORED : base ? BASE : STASH;
            ring(g, p[0], p[1], 5, c);
            square(g, p[0], p[1], 1.5f, c);
            if (out != null) out.add(new Drawn(p[0], p[1], m.label(), detail, c, m, null));
            claim(cells, cell * 2, p, 4, out);
        }
        if (cells != null && out != null) { // "+N more here" on markers that stand for several
            for (int[] c : cells.values()) {
                if (c[0] >= 0 && c[1] > 1 && c[0] < out.size()) {
                    Drawn d = out.get(c[0]);
                    out.set(c[0], new Drawn(d.sx(), d.sy(), d.title(), d.detail() + " · +" + (c[1] - 1) + " more here, zoom in",
                            d.color(), d.mark(), d.waypoint()));
                }
            }
        }

        // ---- predicted structures (from the seed) ------------------------------------------------------
        if (layers.predicted && !layers.predictedKinds.isEmpty() && Minecraft.getInstance().level != null) {
            Long seed = dev.erebus.client.module.world.SeedCracker.knownSeed();
            if (seed != null) {
                var predictor = dev.erebus.client.seed.StructurePredictor.get(seed, Minecraft.getInstance().level.dimension());
                int r = (int) Math.ceil(reach);
                for (var p : predictor.query((int) cx - r, (int) cz - r, (int) cx + r, (int) cz + r, layers.predictedKinds)) {
                    // already found and on the map: the real marker is enough
                    if (MapStore.hasStructure(p.kind().label, p.x(), p.z(), 64)) continue;
                    float[] sp = toScreen(p.x() + 0.5, p.z() + 0.5, cx, cz, scale, cos, sin, mx, my);
                    if (!inside(sp, x, y, w, h) || crowded(cells, cell * 2, sp, 5)) continue;
                    int c = p.kind().color | 0xFF000000;
                    if (scale >= 1 / 48.0) outlineBadge(g, sp[0], sp[1], p.kind().label.substring(0, 1), c);
                    else square(g, sp[0], sp[1], 1.5f, c & 0x80FFFFFF);
                    if (out != null) out.add(new Drawn(sp[0], sp[1], "Predicted " + p.kind().label, "~" + p.x() + " ~" + p.z()
                            + " · from the seed, not seen yet", c, null, null));
                    claim(cells, cell * 2, sp, 5, out);
                }
            }
        }

        // ---- waypoints ------------------------------------------------------------------------------
        if (layers.waypoints) {
            for (Waypoints.Waypoint wp : Waypoints.all()) {
                float[] p = toScreen(wp.x() + 0.5, wp.z() + 0.5, cx, cz, scale, cos, sin, mx, my);
                if (!inside(p, x, y, w, h)) continue;
                Render2D.circle(g, Math.round(p[0]), Math.round(p[1]), 6, 0xC0000000);
                Text.icon(g, Icon.FLAG, p[0] - 3.5f, p[1] - 4.5f, 8, wp.color());
                if (out != null) out.add(new Drawn(p[0], p[1], wp.name(), wp.x() + " " + wp.y() + " " + wp.z() + " · waypoint", wp.color(), null, wp));
            }
        }

        // ---- other players you can see ----------------------------------------------------------------
        Minecraft mc = Minecraft.getInstance();
        if (layers.players && mc.level != null) {
            for (Player pl : mc.level.players()) {
                if (pl == mc.player) continue;
                float[] p = toScreen(pl.getX(), pl.getZ(), cx, cz, scale, cos, sin, mx, my);
                if (!inside(p, x, y, w, h)) continue;
                Render2D.circle(g, Math.round(p[0]), Math.round(p[1]), 3, 0xD0000000);
                Render2D.circle(g, Math.round(p[0]), Math.round(p[1]), 2, PLAYER);
                if (out != null) out.add(new Drawn(p[0], p[1], pl.getName().getString(),
                        (int) pl.getX() + " " + (int) pl.getY() + " " + (int) pl.getZ() + " · player", PLAYER, null, null));
            }
        }

        // ---- you ------------------------------------------------------------------------------------
        if (mc.player != null) {
            float[] p = toScreen(mc.player.getX(), mc.player.getZ(), cx, cz, scale, cos, sin, mx, my);
            if (inside(p, x, y, w, h)) arrow(g, p[0], p[1], mc.player.getYRot() + 180 + rotation);
        }
        g.disableScissor();
    }

    private static String coords(MapStore.Mark m) {
        return m.x() + " " + m.y() + " " + m.z();
    }

    private static void drawThumbnails(GuiGraphics g, int x, int y, int w, int h, double cx, double cz, double scale, float mx, float my) {
        double tilePx = MapStore.R * scale, cellPx = tilePx / 4;
        HashSet<Long> filled = cellPx < 1.5 ? new HashSet<>() : null; // tiny tiles: one fill per screen pixel
        for (var e : MapStore.thumbs().entrySet()) {
            int rx = (int) (e.getKey() >> 32), rz = (int) (long) e.getKey();
            double sx = mx + (rx * (double) MapStore.R - cx) * scale, sy = my + (rz * (double) MapStore.R - cz) * scale;
            if (sx > x + w || sy > y + h || sx + tilePx < x || sy + tilePx < y) continue;
            int[] t = e.getValue();
            if (cellPx < 1.5) {
                long r = 0, gg = 0, b = 0, n = 0;
                for (int c : t) {
                    if (c != 0) {
                        r += (c >> 16) & 255;
                        gg += (c >> 8) & 255;
                        b += c & 255;
                        n++;
                    }
                }
                if (n == 0) continue;
                int x0 = (int) Math.floor(sx), y0 = (int) Math.floor(sy);
                if (tilePx < 2 && !filled.add(((long) x0 << 32) ^ (y0 & 0xFFFFFFFFL))) continue;
                g.fill(x0, y0, Math.max(x0 + 1, (int) Math.ceil(sx + tilePx)), Math.max(y0 + 1, (int) Math.ceil(sy + tilePx)),
                        0xFF000000 | (int) (r / n) << 16 | (int) (gg / n) << 8 | (int) (b / n));
                continue;
            }
            for (int i = 0; i < 16; i++) {
                if (t[i] == 0) continue;
                double cx0 = sx + (i % 4) * cellPx, cy0 = sy + (i / 4) * cellPx;
                g.fill((int) Math.floor(cx0), (int) Math.floor(cy0), (int) Math.ceil(cx0 + cellPx), (int) Math.ceil(cy0 + cellPx), t[i]);
            }
        }
    }

    /** Faint chunk borders when zoomed in. */
    private static void drawChunkGrid(GuiGraphics g, int x, int y, int w, int h, double cx, double cz, double scale, float mx, float my) {
        double left = cx - (mx - x) / scale, top = cz - (my - y) / scale;
        int color = scale >= 6 ? 0x26FFFFFF : 0x18FFFFFF;
        for (double wx = Math.floor(left / 16) * 16; wx <= left + w / scale; wx += 16) {
            int sx = (int) Math.round(mx + (wx - cx) * scale);
            g.fill(sx, y, sx + 1, y + h, color);
        }
        for (double wz = Math.floor(top / 16) * 16; wz <= top + h / scale; wz += 16) {
            int sy = (int) Math.round(my + (wz - cz) * scale);
            g.fill(x, sy, x + w, sy + 1, color);
        }
    }

    private static long cellKey(int cell, float[] p, int layer) {
        return ((long) layer << 56) ^ ((long) Math.floorDiv((int) p[0], cell) << 28) ^ (Math.floorDiv((int) p[1], cell) & 0xFFFFFFFL);
    }

    private static boolean crowded(Map<Long, int[]> cells, int cell, float[] p, int layer) {
        if (cells == null || cell <= 0) return false;
        int[] c = cells.get(cellKey(cell, p, layer));
        if (c == null) return false;
        c[1]++;
        return true;
    }

    private static void claim(Map<Long, int[]> cells, int cell, float[] p, int layer, List<Drawn> out) {
        if (cells == null || cell <= 0) return;
        cells.put(cellKey(cell, p, layer), new int[]{out != null ? out.size() - 1 : -1, 1});
    }

    public static float[] toScreen(double wx, double wz, double cx, double cz, double scale, double cos, double sin, float mx, float my) {
        double dx = (wx - cx) * scale, dz = (wz - cz) * scale;
        return new float[]{(float) (mx + dx * cos - dz * sin), (float) (my + dx * sin + dz * cos)};
    }

    private static boolean inside(float[] p, int x, int y, int w, int h) {
        return p[0] >= x - 4 && p[0] <= x + w + 4 && p[1] >= y - 4 && p[1] <= y + h + 4;
    }

    private static void square(GuiGraphics g, float sx, float sy, float r, int color) {
        int ri = Math.max(1, Math.round(r));
        int px = Math.round(sx), py = Math.round(sy);
        g.fill(px - ri - 1, py - ri - 1, px + ri + 1, py + ri + 1, 0xC0000000);
        g.fill(px - ri, py - ri, px + ri, py + ri, color);
    }

    /** A structure: a rounded coloured tile with its first letter. */
    private static void badge(GuiGraphics g, float sx, float sy, String letter, int color) {
        int px = Math.round(sx), py = Math.round(sy);
        Render2D.roundRect(g, px - 6, py - 6, 12, 12, 3, 0xE0000000);
        Render2D.roundRect(g, px - 5, py - 5, 10, 10, 2, color);
        Text.draw(g, letter, px - Text.width(letter, true) / 2, py - 4, 0xFF101014, true, false);
    }

    /** A predicted structure: an outlined tile with its first letter in its colour (found ones are filled). */
    private static void outlineBadge(GuiGraphics g, float sx, float sy, String letter, int color) {
        int px = Math.round(sx), py = Math.round(sy);
        Render2D.roundRect(g, px - 6, py - 6, 12, 12, 3, 0xC0000000);
        Render2D.roundRectBorder(g, px - 5, py - 5, 10, 10, 2, color);
        Text.draw(g, letter, px - Text.width(letter, true) / 2, py - 4, color, true, false);
    }

    private static void diamond(GuiGraphics g, float sx, float sy, int r, int color) {
        int px = Math.round(sx), py = Math.round(sy);
        for (int i = -r - 1; i <= r + 1; i++) {
            int half = r + 1 - Math.abs(i);
            g.fill(px - half - 1, py + i, px + half + 1, py + i + 1, 0xA0000000);
        }
        for (int i = -r; i <= r; i++) {
            int half = r - Math.abs(i);
            g.fill(px - half - 1, py + i, px + half + 1, py + i + 1, color);
        }
    }

    private static void ring(GuiGraphics g, float sx, float sy, int r, int color) {
        int px = Math.round(sx), py = Math.round(sy);
        Render2D.roundRectBorder(g, px - r - 1, py - r - 1, r * 2 + 2, r * 2 + 2, r + 1, 0x90000000);
        Render2D.roundRectBorder(g, px - r, py - r, r * 2, r * 2, r, color);
        Render2D.roundRectBorder(g, px - r + 1, py - r + 1, r * 2 - 2, r * 2 - 2, r - 1, color);
    }

    /** Your position: a white arrow pointing where you look. */
    private static void arrow(GuiGraphics g, float sx, float sy, float degrees) {
        g.pose().pushMatrix();
        g.pose().translate(sx, sy);
        g.pose().rotate((float) Math.toRadians(degrees));
        int[][] rows = {{-5, 0}, {-4, 1}, {-3, 1}, {-2, 2}, {-1, 2}, {0, 3}, {1, 3}, {2, 4}, {3, 4}};
        for (int[] r : rows) g.fill(-r[1] - 1, r[0] - 1, r[1] + 1, r[0] + 1, 0xFF000000);
        for (int[] r : rows) {
            if (r[0] >= 2) { // notch at the back
                g.fill(-r[1], r[0], -1, r[0] + 1, 0xFFFFFFFF);
                g.fill(1, r[0], r[1], r[0] + 1, 0xFFFFFFFF);
            } else {
                g.fill(-r[1], r[0], r[1] + (r[1] == 0 ? 1 : 0), r[0] + 1, 0xFFFFFFFF);
            }
        }
        g.pose().popMatrix();
    }

    /** The nearest drawn marker within a few pixels of the mouse, or null. */
    public static Drawn hovered(List<Drawn> drawn, double mx, double my) {
        Drawn best = null;
        double bestD = 40;
        for (Drawn d : drawn) {
            double dd = (d.sx() - mx) * (d.sx() - mx) + (d.sy() - my) * (d.sy() - my);
            if (dd < bestD) {
                bestD = dd;
                best = d;
            }
        }
        return best;
    }
}
