package dev.erebus.client.hud.elements;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.hud.HudElement;
import dev.erebus.client.map.MapStore;
import dev.erebus.client.map.Waypoints;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.NumberSetting;
import dev.erebus.client.util.Colors;
import net.minecraft.client.player.LocalPlayer;

import java.util.Locale;

/**
 * A heading tape across the top of the screen: the cardinal directions and degrees sliding past as you turn, with
 * your waypoints and the bases and stashes found so far marked where they lie (and an arrow at the edge for those
 * behind you).
 */
public final class CompassElement extends HudElement {
    private static final String[] NAMES = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final int H = 22;

    private final NumberSetting tapeWidth = add(new NumberSetting("Width", "How wide the tape is.", 220, 120, 400, 10).unit("px"));
    private final NumberSetting span = add(new NumberSetting("Field", "How many degrees the tape shows from edge to edge.", 150, 60, 240, 10)
            .unit("°"));
    private final BoolSetting waypoints = add(new BoolSetting("Waypoints", "Mark your waypoints on the tape.", true));
    private final BoolSetting finds = add(new BoolSetting("Bases and stashes", "Mark the bases and stashes found so far.", true));
    private final BoolSetting background = add(new BoolSetting("Background", true));

    public CompassElement() {
        super("compass", "Compass", "A heading tape: directions and degrees sliding past as you turn, with waypoints and finds "
                + "marked where they lie.", false, HAnchor.CENTER, VAnchor.TOP, 0, 4);
    }

    @Override
    protected int[] measure(Ui ui, boolean preview) {
        if (ui.mc.player == null && !preview) return new int[]{0, 0};
        return new int[]{tapeWidth.intValue(), H};
    }

    /** Compass bearing (0 north, 90 east) from a Minecraft yaw. */
    static float bearing(float yaw) {
        return ((yaw + 180f) % 360f + 360f) % 360f;
    }

    @Override
    protected void draw(Ui ui, int x, int y, boolean preview) {
        LocalPlayer p = ui.mc.player;
        float heading = p == null ? 127f : bearing(p.getYRot());
        int w = width, cx = x + w / 2;
        float half = span.floatValue() / 2f;
        float ppd = (w / 2f) / half;
        if (background.get()) ui.round(x, y, w, H, ui.smallRadius(), ui.theme.hudBackground());
        float before = ui.alpha;
        int lineY = y + H - 4;
        for (int deg = (int) Math.floor((heading - half) / 5) * 5; deg <= heading + half; deg += 5) {
            float dx = (deg - heading) * ppd;
            int tx = Math.round(cx + dx);
            float edge = 1f - Math.abs(dx) / (w / 2f);
            if (edge <= 0) continue;
            ui.alpha = before * Math.min(1f, edge * 2.5f); // fades out towards the ends
            int d = ((deg % 360) + 360) % 360;
            if (d % 45 == 0) {
                String n = NAMES[d / 45];
                boolean cardinal = d % 90 == 0;
                ui.rect(tx, lineY - 4, 1, 4, cardinal ? ui.theme.accent() : ui.theme.text());
                int nw = cardinal ? ui.widthBold(n) : ui.width(n);
                if (cardinal) ui.textBold(n, tx - nw / 2, y + 3, d == 0 ? 0xFFFF5C6C : ui.theme.text());
                else ui.text(n, tx - nw / 2, y + 3, ui.theme.dim());
            } else if (d % 15 == 0) {
                ui.rect(tx, lineY - 3, 1, 3, Colors.mulAlpha(ui.theme.text(), 0.6f));
                String n = Integer.toString(d);
                if (Math.abs(dx) > 14) ui.text(n, tx - ui.width(n) / 2, y + 3, Colors.mulAlpha(ui.theme.dim(), 0.7f));
            } else {
                ui.rect(tx, lineY - 1, 1, 1, Colors.mulAlpha(ui.theme.dim(), 0.6f));
            }
        }
        ui.alpha = before;
        if (p != null) {
            if (waypoints.get() && MapStore.ready()) {
                for (Waypoints.Waypoint wp : Waypoints.all()) marker(ui, p, heading, cx, w, ppd, y, wp.x(), wp.z(), wp.color(), wp.name());
            }
            if (finds.get() && MapStore.ready()) {
                for (MapStore.Mark m : MapStore.alerts()) {
                    if (!m.kind().equals("base") && !m.kind().equals("stash")) continue;
                    marker(ui, p, heading, cx, w, ppd, y, m.x(), m.z(), m.kind().equals("base") ? 0xFFFF5C6C : 0xFFFFC21A, null);
                }
            }
        }
        // where you're facing
        String hd = String.format(Locale.ROOT, "%d°", Math.round(heading) % 360);
        int hw = ui.width(hd) + 8;
        ui.round(cx - hw / 2, y + H - 1, hw, 11, 3, ui.theme.accent());
        ui.text(hd, cx - hw / 2 + 4, y + H, ui.theme.onAccent());
        ui.rect(cx, lineY - 6, 1, 6, ui.theme.accent());
    }

    /** A dot where (tx, tz) lies; at the edge, with a little arrow, when it's outside the tape. */
    private void marker(Ui ui, LocalPlayer p, float heading, int cx, int w, float ppd, int y, int tx, int tz, int color, String name) {
        double dx = tx + 0.5 - p.getX(), dz = tz + 0.5 - p.getZ();
        float b = (float) Math.toDegrees(Math.atan2(dx, -dz));
        float diff = ((b - heading) % 360f + 540f) % 360f - 180f;
        float px = diff * ppd;
        boolean outside = Math.abs(px) > w / 2f - 4;
        int mx = Math.round(cx + Math.max(-(w / 2f - 4), Math.min(w / 2f - 4, px)));
        int my = y + H - 9;
        ui.round(mx - 2, my - 2, 5, 5, 2, color);
        if (outside) ui.text(diff < 0 ? "‹" : "›", diff < 0 ? mx + 4 : mx - 8, my - 4, color);
        else if (name != null && Math.abs(diff) < 8) {
            int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
            String s = name + " " + (dist >= 1000 ? String.format(Locale.ROOT, "%.1fk", dist / 1000.0) : dist + "m");
            ui.text(s, mx - ui.width(s) / 2, y - 9, color);
        }
    }
}
