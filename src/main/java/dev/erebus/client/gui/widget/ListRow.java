package dev.erebus.client.gui.widget;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.erebus.client.gui.Ui;
import dev.erebus.client.util.Anim;
import dev.erebus.client.util.Colors;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Row in the dashboard's middle list. Left click selects; right click runs the optional
 * secondary action (toggling a module or HUD element).
 */
public final class ListRow extends Widget {
    private final String label;
    private final BooleanSupplier selected;
    private final BooleanSupplier enabled;
    private final Runnable onSelect;
    private final Runnable onSecondary;
    private Supplier<String> trailing = () -> null;
    private boolean glided;

    /** The selected row's highlight is drawn by a {@link Glide} under the list instead (so it can slide between rows). */
    public ListRow glided() {
        glided = true;
        return this;
    }
    private IntSupplier[] swatches = new IntSupplier[0];
    private final Anim hover = new Anim(0f, 20f);
    private String tooltip;

    public ListRow tooltip(String text) {
        this.tooltip = text;
        return this;
    }

    public ListRow(int x, int y, int w, int h, String label, BooleanSupplier selected, BooleanSupplier enabled,
                   Runnable onSelect, Runnable onSecondary) {
        bounds(x, y, w, h);
        this.label = label;
        this.selected = selected;
        this.enabled = enabled;
        this.onSelect = onSelect;
        this.onSecondary = onSecondary;
    }

    /** Dim text drawn at the right edge, such as a keybind or a category name. */
    public ListRow trailing(Supplier<String> trailing) {
        this.trailing = trailing;
        return this;
    }

    /** Color dots drawn at the right edge instead of the enabled dot (used for theme presets). */
    public ListRow swatches(IntSupplier... colors) {
        this.swatches = colors;
        return this;
    }

    @Override
    public void render(Ui ui, int mx, int my) {
        boolean hot = contains(mx, my);
        if (hot) ui.cursor(CursorTypes.POINTING_HAND);
        if (hot && tooltip != null) ui.tooltip(this, tooltip);
        hover.target(hot ? 1f : 0f);
        float h = hover.update(ui.dt);
        boolean sel = selected.getAsBoolean();
        boolean on = enabled != null && enabled.getAsBoolean();
        int r = ui.smallRadius();
        if (sel && !glided) {
            ui.round(x, y, width, height, r, ui.theme.selected());
            ui.rect(x, y + 3, 2, height - 6, ui.theme.accent());
        } else if (!sel && h > 0.01f) {
            ui.round(x, y, width, height, r, Colors.mulAlpha(ui.theme.hover(), h));
        }
        int tx = x + 8;
        if (enabled != null) {
            int dot = on ? ui.theme.accent() : ui.theme.track();
            if (on) ui.round(tx - 1, y + height / 2 - 4, 8, 8, 4, Colors.withAlpha(ui.theme.accent(), 0x40));
            ui.round(tx + 1, y + height / 2 - 2, 4, 4, 2, dot);
            tx += 11;
        }
        int textColor = sel || on ? ui.theme.text() : Colors.lerp(ui.theme.dim(), ui.theme.text(), h * 0.6f);
        int rightX = right() - 6;
        if (swatches.length > 0) {
            for (int i = swatches.length - 1; i >= 0; i--) {
                ui.round(rightX - 8, y + height / 2 - 4, 8, 8, 4, swatches[i].getAsInt());
                rightX -= 11;
            }
        }
        String tail = trailing.get();
        if (tail != null && !tail.isEmpty()) {
            int tw = ui.width(tail) + 8;
            ui.round(rightX - tw, y + height / 2 - 6, tw, 12, 2, ui.theme.track());
            ui.text(tail, rightX - tw + 4, Ui.centerY(y, height), ui.theme.dim());
            rightX -= tw + 4;
        }
        ui.text(ui.ellipsize(label, rightX - tx - 2), tx, Ui.centerY(y, height), textColor);
    }

    @Override
    public boolean mouseClicked(Ui ui, double mx, double my, int button) {
        if (!contains(mx, my)) return false;
        if (button == 0) {
            onSelect.run();
            return true;
        }
        if (button == 1 && onSecondary != null) {
            onSecondary.run();
            return true;
        }
        return false;
    }
}
