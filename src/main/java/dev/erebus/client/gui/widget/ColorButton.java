package dev.erebus.client.gui.widget;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.erebus.client.gui.Ui;
import dev.erebus.client.render.Render2D;
import dev.erebus.client.util.Colors;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Swatch plus hex code; clicking opens a {@link ColorPicker} popover. */
public final class ColorButton extends Widget {
    private final IntSupplier getter;
    private final IntConsumer setter;
    private final int boundsW;
    private final int boundsH;

    /**
     * @param boundsW width of the design space, used to keep the popover on screen
     * @param boundsH height of the design space
     */
    public ColorButton(int x, int y, int w, int h, IntSupplier getter, IntConsumer setter, int boundsW, int boundsH) {
        bounds(x, y, w, h);
        this.getter = getter;
        this.setter = setter;
        this.boundsW = boundsW;
        this.boundsH = boundsH;
    }

    @Override
    public void render(Ui ui, int mx, int my) {
        boolean hot = contains(mx, my);
        if (hot) ui.cursor(CursorTypes.POINTING_HAND);
        int color = getter.getAsInt();
        int r = ui.smallRadius();
        ui.round(x, y, width, height, r, hot ? Colors.withAlpha(ui.theme.accent(), 0x40) : ui.theme.track());
        int sw = height - 6;
        Render2D.checker(ui.g, x + 3, y + 3, sw, sw, 3);
        ui.rect(x + 3, y + 3, sw, sw, color);
        Render2D.outline(ui.g, x + 3, y + 3, sw, sw, 0x40FFFFFF);
        boolean alpha = Colors.alpha(color) != 255;
        ui.text(Colors.toHex(color, alpha), x + sw + 8, Ui.centerY(y, height), ui.theme.text());
    }

    @Override
    public boolean mouseClicked(Ui ui, double mx, double my, int button) {
        if (button != 0 || !contains(mx, my)) return false;
        int py = (int) Math.round(y + height + 4 - ui.offsetY);
        if (py + ColorPicker.H > boundsH - 4) py = (int) Math.round(y - 4 - ColorPicker.H - ui.offsetY);
        int px = Math.max(4, Math.min(boundsW - ColorPicker.W - 4, right() - ColorPicker.W));
        ui.openPopover(new ColorPicker(px, Math.max(4, py), getter, setter));
        return true;
    }
}
