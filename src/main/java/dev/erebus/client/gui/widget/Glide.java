package dev.erebus.client.gui.widget;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.util.Spring;

/**
 * The selection highlight of a list, drawn under its rows, that glides to the row you pick instead of jumping. Its
 * position lives in the {@link Ui} (keyed), so it carries over when the screen rebuilds its widgets.
 */
public final class Glide extends Widget {
    private final String key;
    private final int rowH;
    private int targetY = Integer.MIN_VALUE;

    public Glide(String key, int x, int y, int w, int rowH) {
        this.key = key;
        this.rowH = rowH;
        bounds(x, y, w, 0);
    }

    /** The selected row's top (call when adding it). */
    public void at(int rowY) {
        targetY = rowY;
    }

    @Override
    public void render(Ui ui, int mx, int my) {
        if (targetY == Integer.MIN_VALUE) return;
        Spring s = ui.spring(key, targetY);
        s.target(targetY);
        int y = Math.round(s.update(ui.dt));
        int r = ui.smallRadius();
        ui.round(x, y, width, rowH, r, ui.theme.selected());
        ui.rect(x, y + 3, 2, rowH - 6, ui.theme.accent());
    }
}
