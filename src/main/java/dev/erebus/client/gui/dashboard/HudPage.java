package dev.erebus.client.gui.dashboard;

import dev.erebus.client.Erebus;
import dev.erebus.client.gui.Ui;
import dev.erebus.client.gui.widget.Button;
import dev.erebus.client.gui.widget.Container;
import dev.erebus.client.gui.widget.Drawable;
import dev.erebus.client.gui.widget.ListRow;
import dev.erebus.client.gui.widget.ScrollPanel;
import dev.erebus.client.gui.widget.SettingsBuilder;
import dev.erebus.client.hud.HudElement;
import dev.erebus.client.hud.HudManager;
import dev.erebus.client.notify.Notifications;

import java.util.ArrayList;
import java.util.List;

/** HUD element visibility, presets and per-element settings, plus the entry to the HUD editor. */
public final class HudPage extends Page {
    private HudElement selected;

    public HudPage(DashboardScreen screen) {
        super(screen);
    }

    @Override
    public String id() {
        return "hud";
    }

    @Override
    public dev.erebus.client.render.font.Icon icon() {
        return dev.erebus.client.render.font.Icon.GRID;
    }

    @Override
    public String label() {
        return "HUD";
    }

    @Override
    public String badge() {
        int on = 0;
        for (HudElement e : Erebus.hud().all()) if (e.isEnabled()) on++;
        return on + "/" + Erebus.hud().all().size();
    }

    @Override
    public void build(Container root, int x, int y, int w, int h) {
        HudManager hud = Erebus.hud();
        if (selected == null) selected = hud.all().get(0);

        int lx = x + 6;
        int lw = DashboardScreen.LIST - 12;
        root.add(new Button(lx, y + 6, lw, 20, "Open HUD editor", Button.Style.PRIMARY, screen::openHudEditor));
        int ry = y + 32;
        for (HudElement e : hud.all()) {
            root.add(new ListRow(lx, ry, lw, 20, e.name, () -> e == selected, e::isEnabled,
                    () -> {
                        selected = e;
                        screen.rebuild();
                    },
                    () -> hud.setEnabled(e, !e.isEnabled())));
            ry += 21;
        }
        ry += 6;
        ry = SettingsBuilder.section(root, "Layout presets", lx + 2, ry, lw - 4);
        List<String> names = new ArrayList<>(HudManager.PRESETS.keySet());
        int bw = (lw - 4) / 2;
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            int bx = lx + (i % 2) * (bw + 4);
            int by = ry + (i / 2) * 22;
            root.add(new Button(bx, by, bw, 18, name, Button.Style.SECONDARY, () -> {
                hud.applyPreset(name);
                Notifications.info("HUD", name + " preset applied");
            }).active(() -> name.equals(hud.preset())));
        }

        HudElement e = selected;
        int paneX = x + DashboardScreen.LIST + 1;
        ScrollPanel pane = screen.listPanel(root, paneX, y + 1, w - DashboardScreen.LIST - 2, h - 2);
        int px = paneX + 12;
        int pw = pane.width - 24;
        int py = y + 12;
        py = screen.paneHeader(pane, px, py, pw, e.name, () -> "HUD", e::isEnabled, v -> hud.setEnabled(e, v));
        py = screen.paragraph(pane, px, py, pw, e.description);

        py = SettingsBuilder.section(pane, "Position", px, py, pw);
        pane.add(new Drawable(px, py, pw - 100, 20, (ui, self, mx, my) -> {
            String where = "Anchored " + e.anchorLabel() + ", offset " + e.offX + ", " + e.offY;
            ui.text(ui.ellipsize(where, self.width), self.x, Ui.centerY(self.y, 20) - 1, ui.theme.dim());
        }));
        pane.add(new Button(px + pw - 94, py + 1, 94, 16, "Reset position", Button.Style.SECONDARY, () -> {
            e.resetPosition();
            Notifications.info(e.name, "position reset");
        }));
        py += 28;

        if (!e.settings().isEmpty()) {
            if (SettingsBuilder.startsUngrouped(e.settings())) py = SettingsBuilder.section(pane, "Settings", px, py, pw);
            SettingsBuilder.build(pane, e.settings(), px, py, pw, DashboardScreen.W, DashboardScreen.H, screen::rebuild);
        }
    }
}
