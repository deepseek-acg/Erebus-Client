package dev.erebus.client.gui.widget;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.ColorSetting;
import dev.erebus.client.setting.KeybindSetting;
import dev.erebus.client.setting.ModeSetting;
import dev.erebus.client.setting.NumberSetting;
import dev.erebus.client.setting.Setting;
import dev.erebus.client.setting.TextSetting;

import java.util.List;

/** Turns a list of {@link Setting}s into labeled editing rows. */
public final class SettingsBuilder {
    public static final int ROW = 20;
    private static final int CONTROL_W = 96;

    private SettingsBuilder() {
    }

    /**
     * Adds rows for {@code settings} to {@code into}, starting at {@code y}.
     *
     * @param relayout called after a change that can show or hide other settings
     * @return the y coordinate below the last row
     */
    public static int build(Container into, List<? extends Setting<?>> settings, int x, int y, int w,
                            int designW, int designH, Runnable relayout) {
        for (Setting<?> s : settings) {
            if (!s.isVisible()) continue;
            y = row(into, s, x, y, w, designW, designH, relayout);
        }
        return y;
    }

    public static int row(Container into, Setting<?> s, int x, int y, int w, int designW, int designH, Runnable relayout) {
        int cx = x + w - CONTROL_W;
        if (s instanceof NumberSetting n) {
            into.add(label(x, y + 3, w, s.name, n::format));
            into.add(new Slider(x, y + 15, w, n::fraction, f -> n.setFraction(f)));
            return y + 31;
        }
        into.add(label(x, y, w - CONTROL_W - 6, s.name, null));
        if (s instanceof BoolSetting b) {
            into.add(new Switch(x + w - Switch.W, y + (ROW - Switch.H) / 2 - 1, b::get, v -> {
                b.set(v);
                relayout.run();
            }));
        } else if (s instanceof ModeSetting m) {
            into.add(new CycleButton(cx, y + 1, CONTROL_W, 16, m::get, dir -> {
                m.cycle(dir);
                relayout.run();
            }));
        } else if (s instanceof ColorSetting c) {
            into.add(new ColorButton(cx, y + 1, CONTROL_W, 16, c::get, c::set, designW, designH));
        } else if (s instanceof TextSetting t) {
            into.add(new TextField(cx, y + 1, CONTROL_W, 16, t.get(), t.name, t.maxLength).onChange(t::set));
        } else if (s instanceof KeybindSetting k) {
            into.add(new KeybindButton(cx, y + 1, CONTROL_W, 16, k::get, k::set));
        }
        return y + ROW;
    }

    private static Drawable label(int x, int y, int w, String name, java.util.function.Supplier<String> value) {
        return new Drawable(x, y, w, ROW, (ui, self, mx, my) -> {
            int ty = Ui.centerY(self.y, value == null ? ROW : 10) + (value == null ? -1 : 0);
            ui.text(ui.ellipsize(name, self.width - (value == null ? 0 : 40)), self.x, ty, ui.theme.text());
            if (value != null) ui.textRight(value.get(), self.right(), ty, ui.theme.accent());
        });
    }

    /** Uppercase section label with a divider line underneath. */
    public static int section(Container into, String title, int x, int y, int w) {
        into.add(new Drawable(x, y, w, 14, (ui, self, mx, my) -> {
            ui.text(title.toUpperCase(java.util.Locale.ROOT), self.x, self.y + 1, ui.theme.dim());
            ui.rect(self.x, self.y + 12, self.width, 1, ui.theme.divider());
        }));
        return y + 18;
    }
}
