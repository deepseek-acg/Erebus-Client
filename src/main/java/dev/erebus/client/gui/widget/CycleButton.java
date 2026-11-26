package dev.erebus.client.gui.widget;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.erebus.client.gui.Ui;
import dev.erebus.client.util.Colors;

import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Shows the current option; left click goes forward, right click goes back. */
public final class CycleButton extends Widget {
    private final Supplier<String> label;
    private final IntConsumer cycle;

    public CycleButton(int x, int y, int w, int h, Supplier<String> label, IntConsumer cycle) {
        bounds(x, y, w, h);
        this.label = label;
        this.cycle = cycle;
    }

    @Override
    public void render(Ui ui, int mx, int my) {
        boolean hot = contains(mx, my);
        if (hot) ui.cursor(CursorTypes.POINTING_HAND);
        ui.round(x, y, width, height, ui.smallRadius(), hot ? Colors.withAlpha(ui.theme.accent(), 0x40) : ui.theme.track());
        int cy = Ui.centerY(y, height);
        ui.text("<", x + 5, cy, ui.theme.dim());
        ui.text(">", right() - 5 - ui.width(">"), cy, ui.theme.dim());
        ui.textCentered(ui.ellipsize(label.get(), width - 24), x + width / 2, cy, ui.theme.text());
    }

    @Override
    public boolean mouseClicked(Ui ui, double mx, double my, int button) {
        if (!contains(mx, my) || (button != 0 && button != 1)) return false;
        cycle.accept(button == 0 ? 1 : -1);
        return true;
    }
}
