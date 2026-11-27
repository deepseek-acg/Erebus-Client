package dev.erebus.client.hud.elements;

import com.mojang.blaze3d.platform.InputConstants;
import dev.erebus.client.gui.Ui;
import dev.erebus.client.hud.HudElement;
import dev.erebus.client.map.MapRenderer;
import dev.erebus.client.map.MapStore;
import dev.erebus.client.map.Waypoints;
import dev.erebus.client.module.world.MapModule;
import dev.erebus.client.render.Render2D;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.KeybindSetting;
import dev.erebus.client.setting.ModeSetting;
import dev.erebus.client.setting.NumberSetting;
import dev.erebus.client.util.Colors;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The map in a corner of the screen. Needs the Map module (World) to be recording.
 *
 * <p>Laid out like the classic square minimaps: a framed map with the compass letters on its edge (turning with the
 * map when it rotates), your arrow in the middle, nearby mobs and players as radar dots, waypoints that are off the
 * map pinned to its edge, and a few lines of info underneath. Zoom with the setting or with the zoom keys.
 */
public final class MinimapElement extends HudElement {
    private static final double[] ZOOMS = {0.125, 0.25, 0.5, 0.75, 1, 1.5, 2, 3, 4, 6, 8};

    private final NumberSetting size = add(new NumberSetting("Size", "How big the minimap is.", 112, 56, 256, 4));
    private final NumberSetting zoom = add(new NumberSetting("Zoom", "Screen pixels per block: lower shows a bigger area, higher shows more "
            + "detail. The zoom keys step through the same range.", 1, 0.125, 8, 0.125).unit("x"));
    private final KeybindSetting zoomIn = add(new KeybindSetting("Zoom in key", "Key that zooms the minimap in.", KeybindSetting.NONE));
    private final KeybindSetting zoomOut = add(new KeybindSetting("Zoom out key", "Key that zooms the minimap out.", KeybindSetting.NONE));
    private final BoolSetting rotate = add(new BoolSetting("Rotate with you", "Turn the map so straight ahead is always up. Off: north is up.", false));
    private final ModeSetting frame = add(new ModeSetting("Frame", "Classic: a thin light frame with a dark edge. Accent: your theme's accent "
            + "colour with rounded corners. None: just the map.", "Classic", "Classic", "Accent", "None"));
    private final BoolSetting compass = add(new BoolSetting("Compass letters", "N, E, S and W on the edge of the map.", true));
    private final BoolSetting mobs = add(new BoolSetting("Mob dots", "Nearby mobs as dots: red for hostile, green for others.", true));
    private final BoolSetting names = add(new BoolSetting("Player names", "Names next to other players' dots.", true));
    private final BoolSetting edgeWaypoints = add(new BoolSetting("Waypoints on edge", "Waypoints that are off the map are pinned to its edge, "
            + "in the direction they're in.", true));
    private final BoolSetting grid = add(new BoolSetting("Chunk grid", "Faint chunk borders when zoomed in.", false));
    private final BoolSetting coords = add(new BoolSetting("Coordinates", "Your coordinates under the minimap.", true));
    private final BoolSetting biome = add(new BoolSetting("Biome", "The biome you're in, under the minimap.", true));
    private final BoolSetting facing = add(new BoolSetting("Facing", "The direction you're facing, under the minimap.", false));
    private final BoolSetting otherDim = add(new BoolSetting("Nether / Overworld", "Your matching coordinates in the other dimension.", false));

    private boolean inHeld;
    private boolean outHeld;
    private long zoomShownUntil;

    public MinimapElement() {
        super("minimap", "Minimap", "The map of everything you've loaded, with bases, stashes, structures and waypoints. "
                + "Needs the Map module.", true, HAnchor.LEFT, VAnchor.TOP, 4, 18);
    }

    private List<String> infoLines(Ui ui, boolean preview) {
        List<String> lines = new ArrayList<>();
        var p = ui.mc.player;
        if (coords.get()) {
            lines.add(p == null ? "12480, 118, -3051"
                    : String.format(Locale.ROOT, "%d, %d, %d", (int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ())));
        }
        if (biome.get()) {
            String b = "Plains";
            if (p != null && ui.mc.level != null) {
                b = ui.mc.level.getBiome(p.blockPosition()).unwrapKey().map(k -> pretty(k.identifier().getPath())).orElse("?");
            }
            lines.add(b);
        }
        if (facing.get()) {
            float yaw = p == null ? 0 : p.getYRot();
            String[] dirs = {"South", "South-west", "West", "North-west", "North", "North-east", "East", "South-east"};
            int i = Math.floorMod(Math.round(yaw / 45f), 8);
            lines.add(dirs[i]);
        }
        if (otherDim.get()) {
            if (p != null && ui.mc.level != null) {
                if (ui.mc.level.dimension() == Level.NETHER) lines.add(String.format(Locale.ROOT, "Overworld %d, %d", (int) Math.floor(p.getX() * 8), (int) Math.floor(p.getZ() * 8)));
                else if (ui.mc.level.dimension() == Level.OVERWORLD) lines.add(String.format(Locale.ROOT, "Nether %d, %d", (int) Math.floor(p.getX() / 8), (int) Math.floor(p.getZ() / 8)));
            } else if (preview) {
                lines.add("Nether 1560, -381");
            }
        }
        return lines;
    }

    private static String pretty(String id) {
        StringBuilder sb = new StringBuilder();
        for (String w : id.split("_")) {
            if (w.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    @Override
    protected int[] measure(Ui ui, boolean preview) {
        MapModule m = MapModule.get();
        boolean live = m != null && m.isEnabled() && ui.mc.player != null;
        if (!live && !preview) return new int[]{0, 0};
        int s = size.intValue();
        int lines = infoLines(ui, preview).size();
        return new int[]{s, s + (lines > 0 ? 3 + lines * 10 : 0)};
    }

    /** The zoom keys: one step per press, only while no screen is open. */
    private void pollZoomKeys(Ui ui) {
        if (ui.mc.screen != null) {
            inHeld = outHeld = false;
            return;
        }
        boolean in = zoomIn.get() >= 0 && InputConstants.isKeyDown(ui.mc.getWindow(), zoomIn.get());
        boolean out = zoomOut.get() >= 0 && InputConstants.isKeyDown(ui.mc.getWindow(), zoomOut.get());
        if (in && !inHeld) stepZoom(1);
        if (out && !outHeld) stepZoom(-1);
        inHeld = in;
        outHeld = out;
    }

    private void stepZoom(int dir) {
        double z = zoom.get();
        int i = 0;
        while (i < ZOOMS.length - 1 && ZOOMS[i] < z - 1e-6) i++;
        if (dir > 0 && ZOOMS[i] <= z + 1e-6) i = Math.min(ZOOMS.length - 1, i + 1);
        if (dir < 0) i = Math.max(0, ZOOMS[i] >= z - 1e-6 ? i - 1 : i);
        zoom.set(ZOOMS[i]);
        zoomShownUntil = System.currentTimeMillis() + 1500;
    }

    @Override
    protected void draw(Ui ui, int x, int y, boolean preview) {
        int s = size.intValue();
        MapModule m = MapModule.get();
        boolean live = m != null && m.isEnabled() && ui.mc.player != null && MapStore.ready();
        if (!preview) pollZoomKeys(ui);
        String style = frame.get();
        int r = style.equals("Accent") ? Math.max(2, ui.theme.radius) : 0;
        int in = style.equals("None") ? 0 : 2;
        int mx0 = x + in, my0 = y + in, ms = s - in * 2;
        if (style.equals("Accent")) Render2D.roundRect(ui.g, x, y, s, s, r, Colors.withAlpha(0x000000, 0xB0));
        else if (style.equals("Classic")) ui.rect(x, y, s, s, 0xFF000000);

        float rot = live && rotate.get() ? 180 - ui.mc.player.getYRot() : 0;
        double scale = zoom.get();
        if (live) {
            MapRenderer.Layers layers = m.layers();
            layers.chunkGrid = grid.get();
            MapRenderer.draw(ui.g, mx0, my0, ms, ms, ui.mc.player.getX(), ui.mc.player.getZ(), scale, rot, layers, null);
            ui.g.enableScissor(mx0, my0, mx0 + ms, my0 + ms);
            if (mobs.get() || names.get()) radar(ui, mx0, my0, ms, scale, rot);
            ui.g.disableScissor();
            if (edgeWaypoints.get() && layers.waypoints) edgeWaypoints(ui, mx0, my0, ms, scale, rot);
        } else {
            ui.rect(mx0, my0, ms, ms, MapRenderer.BACKGROUND);
            ui.textCentered(preview ? "Minimap" : "Map off", x + s / 2, y + s / 2 - 4, ui.theme.dim());
        }

        // frame
        if (style.equals("Classic")) {
            ui.rect(x + 1, y + 1, s - 2, 1, 0xFFD8D8DE);
            ui.rect(x + 1, y + s - 2, s - 2, 1, 0xFFD8D8DE);
            ui.rect(x + 1, y + 1, 1, s - 2, 0xFFD8D8DE);
            ui.rect(x + s - 2, y + 1, 1, s - 2, 0xFFD8D8DE);
        } else if (style.equals("Accent")) {
            Render2D.roundRectBorder(ui.g, x, y, s, s, r, Colors.withAlpha(ui.theme.accent(), 0xC0));
        }
        if (compass.get()) compassLetters(ui, x, y, s, rot);
        if (System.currentTimeMillis() < zoomShownUntil) {
            String z = trim(scale) + "x";
            int w = ui.width(z) + 6;
            ui.rect(x + s - in - w - 2, y + in + 2, w, 11, 0xB0000000);
            ui.text(z, x + s - in - w + 1, y + in + 4, 0xFFFFFFFF);
        }

        // info lines, each on its own dark strip
        List<String> lines = infoLines(ui, preview);
        int ly = y + s + 3;
        for (String line : lines) {
            int w = ui.width(line);
            int lx = x + (s - w) / 2;
            ui.rect(lx - 3, ly - 1, w + 6, 10, 0x90000000);
            ui.text(line, lx, ly, ui.theme.text());
            ly += 10;
        }
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    private static float[] onMap(double wx, double wz, double px, double pz, double scale, float rot, float cx, float cy) {
        double rad = Math.toRadians(rot);
        return MapRenderer.toScreen(wx, wz, px, pz, scale, Math.cos(rad), Math.sin(rad), cx, cy);
    }

    /** Mobs as dots (hostile red, others green) and other players' names. */
    private void radar(Ui ui, int x, int y, int s, double scale, float rot) {
        var p = ui.mc.player;
        float cx = x + s / 2f, cy = y + s / 2f;
        double reach = s / 2.0 / scale * 1.42;
        for (Entity e : ui.mc.level.entitiesForRendering()) {
            if (e == p || !(e instanceof LivingEntity le) || !le.isAlive()) continue;
            if (Math.abs(e.getX() - p.getX()) > reach || Math.abs(e.getZ() - p.getZ()) > reach) continue;
            float[] q = onMap(e.getX(), e.getZ(), p.getX(), p.getZ(), scale, rot, cx, cy);
            if (q[0] < x || q[0] > x + s || q[1] < y || q[1] > y + s) continue;
            int px = Math.round(q[0]), py = Math.round(q[1]);
            if (e instanceof Player pl) {
                if (names.get()) {
                    String n = pl.getName().getString();
                    int w = ui.width(n);
                    ui.rect(px - w / 2 - 2, py + 4, w + 4, 10, 0x90000000);
                    ui.text(n, px - w / 2, py + 5, 0xFFFFFFFF);
                }
                continue; // the dot itself is drawn by the map
            }
            if (!mobs.get()) continue;
            int c = e instanceof Enemy ? 0xFFFF4D4D : 0xFF7CDB5C;
            ui.rect(px - 2, py - 2, 4, 4, 0xC0000000);
            ui.rect(px - 1, py - 1, 2, 2, c);
        }
    }

    /** Waypoints off the map, pinned to its edge in their direction. */
    private void edgeWaypoints(Ui ui, int x, int y, int s, double scale, float rot) {
        var p = ui.mc.player;
        float cx = x + s / 2f, cy = y + s / 2f;
        for (Waypoints.Waypoint wp : Waypoints.all()) {
            float[] q = onMap(wp.x() + 0.5, wp.z() + 0.5, p.getX(), p.getZ(), scale, rot, cx, cy);
            if (q[0] >= x && q[0] <= x + s && q[1] >= y && q[1] <= y + s) continue; // on the map already
            double dx = q[0] - cx, dy = q[1] - cy;
            double k = Math.min((s / 2.0 - 4) / Math.max(1e-6, Math.abs(dx)), (s / 2.0 - 4) / Math.max(1e-6, Math.abs(dy)));
            int ex = (int) Math.round(cx + dx * k), ey = (int) Math.round(cy + dy * k);
            Render2D.circle(ui.g, ex, ey, 4, 0xD0000000);
            Render2D.circle(ui.g, ex, ey, 3, wp.color() | 0xFF000000);
        }
    }

    private void compassLetters(Ui ui, int x, int y, int s, float rot) {
        String[] letters = {"N", "E", "S", "W"};
        double rad = Math.toRadians(rot);
        for (int i = 0; i < 4; i++) {
            double a = rad + Math.toRadians(i * 90);
            double dx = Math.sin(a), dy = -Math.cos(a);
            // pinned to the square's edge along that direction
            double k = (s / 2.0 - 6) / Math.max(Math.abs(dx), Math.abs(dy));
            int lx = (int) Math.round(x + s / 2.0 + dx * k), ly = (int) Math.round(y + s / 2.0 + dy * k);
            String l = letters[i];
            int w = ui.widthBold(l);
            ui.rect(lx - 5, ly - 5, 10, 10, 0xC0000000);
            ui.textShadowed(l, lx - w / 2, ly - 4, i == 0 ? 0xFFFF5555 : 0xFFFFFFFF, true);
        }
    }
}
