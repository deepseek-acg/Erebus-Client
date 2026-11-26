package dev.erebus.client.gui.widget;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.erebus.client.gui.Ui;
import dev.erebus.client.render.Render2D;
import dev.erebus.client.render.font.Icon;
import dev.erebus.client.theme.ThemeColor;
import dev.erebus.client.util.Colors;

import java.util.ArrayList;
import java.util.List;

/** A small popover menu of actions, opened at the mouse with {@link Ui#openPopover}. */
public final class ContextMenu extends Widget {
    private record Item(Icon icon, String label, Runnable action, boolean danger) {
    }

    private static final int ROW = 18;
    private final String title;
    private final List<Item> items = new ArrayList<>();

    public ContextMenu(String title) {
        this.title = title;
    }

    public ContextMenu item(Icon icon, String label, Runnable action) {
        items.add(new Item(icon, label, action, false));
        return this;
    }

    public ContextMenu danger(Icon icon, String label, Runnable action) {
        items.add(new Item(icon, label, action, true));
        return this;
    }

    /** Sizes the menu and places it at (mx, my), kept inside a {@code boundsW} x {@code boundsH} area. */
    public ContextMenu openAt(Ui ui, int mx, int my, int boundsW, int boundsH) {
        int w = title == null ? 0 : ui.width(title) + 16;
        for (Item it : items) w = Math.max(w, ui.width(it.label) + 36);
        int h = items.size() * ROW + 8 + (title == null ? 0 : 16);
        int x = Math.min(mx, boundsW - w - 4);
        int y = Math.min(my, boundsH - h - 4);
        bounds(Math.max(4, x), Math.max(4, y), w, h);
        ui.openPopover(this);
        return this;
    }

    @Override
    public void render(Ui ui, int mx, int my) {
        int r = ui.theme.radius;
        Render2D.shadow(ui.g, x, y, width, height, r, 8, 0x90000000);
        ui.round(x, y, width, height, r, Colors.withAlpha(ui.theme.get(ThemeColor.BACKGROUND), 0xF7));
        ui.border(x, y, width, height, r, ui.theme.border());
        int iy = y + 4;
        if (title != null) {
            ui.text(ui.ellipsize(title, width - 16), x + 8, iy + 3, ui.theme.dim());
            iy += 16;
        }
        for (Item it : items) {
            boolean hot = mx >= x + 3 && mx < right() - 3 && my >= iy && my < iy + ROW;
            if (hot) {
                ui.cursor(CursorTypes.POINTING_HAND);
                ui.round(x + 3, iy, width - 6, ROW, ui.smallRadius(), it.danger ? 0x30FF5C6C : ui.theme.selected());
            }
            int color = it.danger ? 0xFFFF8A95 : hot ? ui.theme.text() : Colors.lerp(ui.theme.dim(), ui.theme.text(), 0.6f);
            if (it.icon != null) ui.icon(it.icon, x + 9, iy + 5, 8, it.danger ? color : hot ? ui.theme.accent() : ui.theme.dim());
            ui.text(it.label, x + 24, Ui.centerY(iy, ROW), color);
            iy += ROW;
        }
    }

    @Override
    public boolean mouseClicked(Ui ui, double mx, double my, int button) {
        int iy = y + 4 + (title == null ? 0 : 16);
        for (Item it : items) {
            if (mx >= x + 3 && mx < right() - 3 && my >= iy && my < iy + ROW) {
                ui.closePopover();
                it.action.run();
                return true;
            }
            iy += ROW;
        }
        return true;
    }
}
