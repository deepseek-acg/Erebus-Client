package dev.erebus.client.hud.elements;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.hud.HudElement;
import dev.erebus.client.map.MapRenderer;
import dev.erebus.client.map.MapStore;
import dev.erebus.client.module.world.MapModule;
import dev.erebus.client.render.Render2D;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.NumberSetting;
import dev.erebus.client.util.Colors;

import java.util.Locale;

/** The map in a corner of the screen. Needs the Map module (World) to be recording. */
public final class MinimapElement extends HudElement {
    private final NumberSetting size = add(new NumberSetting("Size", "How big the minimap is.", 104, 56, 240, 4));
    private final NumberSetting zoom = add(new NumberSetting("Zoom", "Screen pixels per block. Lower shows a bigger area.", 1, 0.25, 4, 0.25).unit("x"));
    private final BoolSetting rotate = add(new BoolSetting("Rotate with you", "Turn the map so straight ahead is always up. Off: north is up.", false));
    private final BoolSetting coords = add(new BoolSetting("Coordinates", "Show your coordinates under the minimap.", true));
    private final BoolSetting grid = add(new BoolSetting("Chunk grid", "Faint chunk borders when zoomed in.", false));

    public MinimapElement() {
        super("minimap", "Minimap", "The map of everything you've loaded, with bases, stashes, structures and waypoints. "
                + "Needs the Map module.", true, HAnchor.LEFT, VAnchor.TOP, 4, 18);
    }

    private int textH() {
        return coords.get() ? 13 : 0;
    }

    @Override
    protected int[] measure(Ui ui, boolean preview) {
        MapModule m = MapModule.get();
        boolean live = m != null && m.isEnabled() && ui.mc.player != null;
        if (!live && !preview) return new int[]{0, 0};
        int s = size.intValue();
        return new int[]{s, s + textH()};
    }

    @Override
    protected void draw(Ui ui, int x, int y, boolean preview) {
        int s = size.intValue();
        int r = Math.max(2, ui.theme.radius);
        Render2D.roundRect(ui.g, x, y, s, s, r, Colors.withAlpha(0x000000, 0xB0));
        MapModule m = MapModule.get();
        int in = 2;
        if (m != null && m.isEnabled() && ui.mc.player != null && MapStore.ready()) {
            MapRenderer.Layers layers = m.layers();
            layers.chunkGrid = grid.get();
            float rot = rotate.get() ? 180 - ui.mc.player.getYRot() : 0;
            MapRenderer.draw(ui.g, x + in, y + in, s - in * 2, s - in * 2, ui.mc.player.getX(), ui.mc.player.getZ(), zoom.get(), rot, layers, null);
            // north marker on the edge
            double rad = Math.toRadians(rot);
            int nx = (int) Math.round(x + s / 2.0 + Math.sin(rad) * (s / 2.0 - 8));
            int ny = (int) Math.round(y + s / 2.0 - Math.cos(rad) * (s / 2.0 - 8));
            Render2D.circle(ui.g, nx, ny, 5, 0xD0101014);
            ui.textShadowed("N", nx - ui.widthBold("N") / 2, ny - 4, 0xFFFF6B6B, true);
        } else {
            ui.rect(x + in, y + in, s - in * 2, s - in * 2, MapRenderer.BACKGROUND);
            ui.textCentered("Map off", x + s / 2, y + s / 2 - 4, ui.theme.dim());
        }
        Render2D.roundRectBorder(ui.g, x, y, s, s, r, Colors.withAlpha(ui.theme.accent(), 0xB0));
        if (coords.get()) {
            String c;
            if (ui.mc.player != null) {
                c = String.format(Locale.ROOT, "%d, %d, %d", (int) Math.floor(ui.mc.player.getX()), (int) Math.floor(ui.mc.player.getY()),
                        (int) Math.floor(ui.mc.player.getZ()));
            } else {
                c = "12480, 118, -3051";
            }
            ui.textShadowed(c, x + (s - ui.width(c)) / 2, y + s + 3, ui.theme.text(), false);
        }
    }
}
