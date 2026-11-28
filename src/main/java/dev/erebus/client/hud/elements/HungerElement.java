package dev.erebus.client.hud.elements;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.hud.HudElement;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.theme.Theme;
import dev.erebus.client.util.Colors;
import net.minecraft.client.player.LocalPlayer;

import java.util.Locale;

/**
 * Food and the saturation the game hides: ten segments of food with your saturation laid over them in gold, and a
 * word when it matters (health stops regenerating below 18 food, sprinting stops at 6).
 */
public final class HungerElement extends HudElement {
    private static final int W = 104;

    private final BoolSetting numbers = add(new BoolSetting("Numbers", "Food and saturation as numbers too.", true));
    private final BoolSetting hints = add(new BoolSetting("Hints", "Say when health stops regenerating or sprinting stops.", true));
    private final BoolSetting background = add(new BoolSetting("Background", true));
    private float shownSat = -1;

    public HungerElement() {
        super("hunger", "Hunger", "Food with the hidden saturation laid over it, and when health stops regenerating or "
                + "sprinting stops.", false, HAnchor.CENTER, VAnchor.BOTTOM, 98, 40);
    }

    @Override
    protected int[] measure(Ui ui, boolean preview) {
        if (ui.mc.player == null && !preview) return new int[]{0, 0};
        return new int[]{W + 8, 8 + 7 + (numbers.get() || hints.get() ? 11 : 0)};
    }

    @Override
    protected void draw(Ui ui, int x, int y, boolean preview) {
        LocalPlayer p = ui.mc.player;
        int food = p == null ? 17 : p.getFoodData().getFoodLevel();
        float sat = p == null ? 4.6f : p.getFoodData().getSaturationLevel();
        if (shownSat < 0) shownSat = sat;
        shownSat += (sat - shownSat) * Math.min(1f, ui.dt * 8f); // eases when you eat
        if (background.get()) ui.round(x, y, width, height, ui.smallRadius(), ui.theme.hudBackground());
        int bx = x + 4, by = y + 4;
        int seg = (W - 9) / 10;
        for (int i = 0; i < 10; i++) {
            int sx = bx + i * (seg + 1);
            float f = Math.max(0f, Math.min(1f, (food - i * 2) / 2f));
            float s = Math.max(0f, Math.min(1f, (shownSat - i * 2) / 2f));
            ui.round(sx, by, seg, 7, 2, ui.theme.track());
            if (f > 0) ui.round(sx, by, Math.max(2, Math.round(seg * f)), 7, 2, 0xFFD9822B);
            if (s > 0) ui.round(sx, by + 4, Math.max(2, Math.round(seg * s)), 3, 1, 0xFFFFD24A);
        }
        if (!numbers.get() && !hints.get()) return;
        int ty = by + 10;
        int tx = bx;
        if (numbers.get()) {
            tx += ui.text("Food ", tx, ty, ui.theme.dim());
            tx += ui.text(Integer.toString(food), tx, ty, ui.theme.text());
            tx += ui.text("  Sat ", tx, ty, ui.theme.dim());
            tx += ui.text(String.format(Locale.ROOT, "%.1f", sat), tx, ty, 0xFFFFD24A);
        }
        if (hints.get()) {
            String hint = food <= 6 ? "can't sprint" : food < 18 ? "no regen" : sat <= 0 ? "eat soon" : null;
            if (hint != null) {
                int c = food <= 6 ? Theme.BAD : food < 18 ? Theme.WARN : Colors.mulAlpha(ui.theme.dim(), 0.9f);
                ui.textRight(hint, x + width - 4, ty, c);
            }
        }
    }
}
