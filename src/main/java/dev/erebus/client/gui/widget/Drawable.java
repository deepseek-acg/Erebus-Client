package dev.erebus.client.gui.widget;

import dev.erebus.client.gui.Ui;

/** A non-interactive widget that renders through a lambda. */
public final class Drawable extends Widget {
    public interface Painter {
        void paint(Ui ui, Drawable self, int mx, int my);
    }

    private final Painter painter;

    public Drawable(int x, int y, int w, int h, Painter painter) {
        bounds(x, y, w, h);
        this.painter = painter;
    }

    @Override
    public void render(Ui ui, int mx, int my) {
        painter.paint(ui, this, mx, my);
    }
}
