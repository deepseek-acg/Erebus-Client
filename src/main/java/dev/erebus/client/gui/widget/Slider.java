package dev.erebus.client.gui.widget;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.erebus.client.gui.Ui;
import dev.erebus.client.util.Anim;
import dev.erebus.client.util.Colors;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/** Horizontal slider over a normalized 0..1 fraction. */
public final class Slider extends Widget {
    private final DoubleSupplier fraction;
    private final DoubleConsumer setFraction;
    private boolean dragging;
    private final Anim shown;

    public Slider(int x, int y, int w, DoubleSupplier fraction, DoubleConsumer setFraction) {
        bounds(x, y, w, 10);
        this.fraction = fraction;
        this.setFraction = setFraction;
        this.shown = new Anim((float) fraction.getAsDouble(), 30f);
    }

    @Override
    public void render(Ui ui, int mx, int my) {
        boolean hot = dragging || contains(mx, my);
        if (hot) ui.cursor(CursorTypes.RESIZE_EW);
        shown.target((float) fraction.getAsDouble());
        float f = dragging ? shown.target() : shown.update(ui.dt);
        if (dragging) shown.snap(f);
        int ty = y + height / 2 - 2;
        ui.round(x, ty, width, 4, 2, ui.theme.track());
        int filled = Math.round(width * f);
        ui.round(x, ty, Math.max(4, filled), 4, 2, ui.theme.accent());
        int knob = hot ? 10 : 8;
        int kx = x + filled - knob / 2;
        kx = Math.max(x - 1, Math.min(x + width - knob + 1, kx));
        ui.round(kx, y + height / 2 - knob / 2, knob, knob, knob / 2, Colors.lighten(ui.theme.accent(), 0.35f));
    }

    @Override
    public boolean mouseClicked(Ui ui, double mx, double my, int button) {
        if (button != 0 || !(mx >= x - 3 && mx < x + width + 3 && my >= y && my < y + height)) return false;
        dragging = true;
        ui.capture(this);
        update(mx);
        return true;
    }

    @Override
    public void mouseDragged(Ui ui, double mx, double my, int button) {
        if (dragging) update(mx);
    }

    @Override
    public void mouseReleased(Ui ui, double mx, double my, int button) {
        dragging = false;
    }

    private void update(double mx) {
        setFraction.accept(Math.max(0, Math.min(1, (mx - x) / width)));
    }
}
