package dev.erebus.client.gui.dashboard;

import dev.erebus.client.Erebus;
import dev.erebus.client.gui.Ui;
import dev.erebus.client.gui.widget.Button;
import dev.erebus.client.gui.widget.ColorButton;
import dev.erebus.client.gui.widget.Container;
import dev.erebus.client.gui.widget.Drawable;
import dev.erebus.client.gui.widget.ListRow;
import dev.erebus.client.gui.widget.ScrollPanel;
import dev.erebus.client.gui.widget.SettingsBuilder;
import dev.erebus.client.gui.widget.Slider;
import dev.erebus.client.gui.widget.Switch;
import dev.erebus.client.notify.Notifications;
import dev.erebus.client.theme.Theme;
import dev.erebus.client.theme.ThemeColor;
import dev.erebus.client.theme.ThemePreset;
import dev.erebus.client.util.Colors;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** Theme editor: pick a preset, then edit colors, shape and effects. Changes apply live. */
public final class ThemePage extends Page {
    public ThemePage(DashboardScreen screen) {
        super(screen);
    }

    @Override
    public String id() {
        return "theme";
    }

    @Override
    public String label() {
        return "Theme";
    }

    @Override
    public void build(Container root, int x, int y, int w, int h) {
        Theme t = Erebus.theme();
        int lx = x + 6;
        int lw = DashboardScreen.LIST - 12;
        int ry = SettingsBuilder.section(root, "Presets", lx + 2, y + 10, lw - 4);
        for (ThemePreset p : ThemePreset.values()) {
            root.add(new ListRow(lx, ry, lw, 20, p.label, () -> t.base() == p, null, () -> apply(t, p), null)
                    .trailing(() -> t.base() == p && t.modified() ? "edited" : null)
                    .swatches(() -> Colors.withAlpha(p.color(ThemeColor.BACKGROUND), 255),
                            () -> p.color(ThemeColor.ACCENT), () -> p.color(ThemeColor.ACCENT_2)));
            ry += 21;
        }
        ry += 8;
        screen.paragraph(root, lx + 2, ry, lw - 4,
                "Start from a preset, then change anything on the right. Edits apply right away and are saved when you close the menu.");

        int paneX = x + DashboardScreen.LIST + 1;
        ScrollPanel pane = screen.listPanel(root, paneX, y + 1, w - DashboardScreen.LIST - 2, h - 2);
        int px = paneX + 12;
        int pw = pane.width - 24;
        int py = y + 12;
        py = screen.paneHeader(pane, px, py, pw, "Theme",
                () -> t.base().label + (t.modified() ? " · edited" : ""), null, null);

        py = SettingsBuilder.section(pane, "Colors", px, py, pw);
        for (ThemeColor c : ThemeColor.values()) {
            screen.label(pane, px, py, pw - 110, SettingsBuilder.ROW, c.label);
            pane.add(new ColorButton(px + pw - 104, py + 1, 104, 16, () -> t.get(c), v -> t.set(c, v),
                    DashboardScreen.W, DashboardScreen.H));
            py += SettingsBuilder.ROW;
        }
        py += 8;

        py = SettingsBuilder.section(pane, "Shape", px, py, pw);
        py = slider(pane, px, py, pw, "Corner radius", () -> t.radius + "px",
                () -> t.radius / 10.0, f -> {
                    int r = (int) Math.round(f * 10);
                    if (r != t.radius) {
                        t.radius = r;
                        t.markModified();
                    }
                });
        py = slider(pane, px, py, pw, "Panel opacity", () -> Math.round(t.panelOpacity * 100) + "%",
                () -> (t.panelOpacity - 0.3) / 0.7, f -> {
                    float v = Math.round((0.3f + (float) f * 0.7f) * 20f) / 20f;
                    if (v != t.panelOpacity) {
                        t.panelOpacity = v;
                        t.markModified();
                    }
                });
        py += 6;

        py = SettingsBuilder.section(pane, "Effects", px, py, pw);
        py = toggle(pane, px, py, pw, "Drop shadow", () -> t.shadow, v -> {
            t.shadow = v;
            t.markModified();
        });
        py = toggle(pane, px, py, pw, "Blur game behind menu", () -> t.blur, v -> {
            t.blur = v;
            t.markModified();
        });
        py = toggle(pane, px, py, pw, "Text shadow", () -> t.textShadow, v -> {
            t.textShadow = v;
            t.markModified();
        });
        py = toggle(pane, px, py, pw, "Animations", () -> t.animations, t::setAnimations);
        py += 10;

        pane.add(new Button(px, py, 140, 18, () -> "Reset to " + t.base().label, Button.Style.SECONDARY,
                () -> apply(t, t.base())));
        py += 26;
        screen.paragraph(pane, px, py, pw,
                "Blur uses Minecraft's Menu Background Blur setting; it has no effect if that is set to 0.");
    }

    private void apply(Theme t, ThemePreset p) {
        boolean animations = t.animations;
        t.apply(p);
        t.setAnimations(animations);
        Notifications.info("Theme", p.label + " applied");
    }

    private int slider(Container into, int x, int y, int w, String label, Supplier<String> value,
                       DoubleSupplier get, DoubleConsumer set) {
        into.add(new Drawable(x, y, w, 12, (ui, self, mx, my) -> {
            ui.text(label, self.x, self.y + 1, ui.theme.text());
            ui.textRight(value.get(), self.right(), self.y + 1, ui.theme.accent());
        }));
        into.add(new Slider(x, y + 12, w, get, set));
        return y + 28;
    }

    private int toggle(Container into, int x, int y, int w, String label, BooleanSupplier get, Consumer<Boolean> set) {
        into.add(new Drawable(x, y, w - 30, SettingsBuilder.ROW, (ui, self, mx, my) ->
                ui.text(label, self.x, Ui.centerY(self.y, self.height) - 1, ui.theme.text())));
        into.add(new Switch(x + w - Switch.W, y + (SettingsBuilder.ROW - Switch.H) / 2 - 1, get, set));
        return y + SettingsBuilder.ROW;
    }
}
