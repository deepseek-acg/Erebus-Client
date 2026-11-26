package dev.erebus.client.gui.widget;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.erebus.client.gui.Ui;
import dev.erebus.client.util.Anim;
import dev.erebus.client.util.Colors;

import java.util.function.Supplier;

public class Button extends Widget {
    public enum Style { PRIMARY, SECONDARY, GHOST }

    private final Supplier<String> label;
    private final Runnable onClick;
    private Style style;
    private Supplier<Boolean> active = () -> false;
    private final Anim hover = new Anim(0f, 20f);
    private dev.erebus.client.render.font.Icon icon;

    public Button icon(dev.erebus.client.render.font.Icon icon) {
        this.icon = icon;
        return this;
    }

    public Button(int x, int y, int w, int h, String label, Style style, Runnable onClick) {
        this(x, y, w, h, () -> label, style, onClick);
    }

    public Button(int x, int y, int w, int h, Supplier<String> label, Style style, Runnable onClick) {
        bounds(x, y, w, h);
        this.label = label;
        this.style = style;
        this.onClick = onClick;
    }

    /** Draws the button in its "selected" state while the supplier returns true (used for presets). */
    public Button active(Supplier<Boolean> active) {
        this.active = active;
        return this;
    }

    public static int widthFor(Ui ui, String label) {
        return ui.width(label) + 16;
    }

    @Override
    public void render(Ui ui, int mx, int my) {
        boolean hot = contains(mx, my);
        if (hot) ui.cursor(CursorTypes.POINTING_HAND);
        float h = hover.update(ui.dt);
        hover.target(hot ? 1f : 0f);
        boolean on = active.get();
        int r = ui.smallRadius();
        int bg;
        int fg;
        switch (on ? Style.PRIMARY : style) {
            case PRIMARY -> {
                bg = Colors.lighten(ui.theme.accent(), 0.12f * h);
                fg = ui.theme.onAccent();
            }
            case SECONDARY -> {
                bg = Colors.lerp(ui.theme.track(), Colors.withAlpha(ui.theme.accent(), 0x40), h);
                fg = ui.theme.text();
            }
            default -> {
                bg = Colors.withAlpha(ui.theme.accent(), Math.round(0x26 * h));
                fg = Colors.lerp(ui.theme.dim(), ui.theme.text(), h);
            }
        }
        ui.round(x, y, width, height, r, bg);
        String text = label.get();
        if (icon != null && text.isEmpty()) {
            ui.icon(icon, x + width / 2f - 4f, y + (height - 8) / 2f, 8, fg);
        } else if (icon != null) {
            text = ui.ellipsize(text, width - 22);
            int tw = ui.width(text) + 12;
            int tx = x + (width - tw) / 2;
            ui.icon(icon, tx, y + (height - 8) / 2f, 8, fg);
            ui.text(text, tx + 12, Ui.centerY(y, height), fg);
        } else {
            ui.textCentered(ui.ellipsize(text, width - 8), x + width / 2, Ui.centerY(y, height), fg);
        }
    }

    @Override
    public boolean mouseClicked(Ui ui, double mx, double my, int button) {
        if (button != 0 || !contains(mx, my)) return false;
        if (dev.erebus.client.Erebus.client().clickSounds.get()) {
            ui.mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.6f, 0.18f));
        }
        onClick.run();
        return true;
    }
}
