package dev.erebus.client.hud.elements;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.hud.HudElement;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.theme.Theme;
import dev.erebus.client.util.Colors;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import java.util.Locale;

/**
 * Your exact yaw and pitch, on a little dial and a pitch gauge, and how far you are off the nearest axis or diagonal:
 * for lining up on a highway (it goes green when you're on it) or a long straight tunnel.
 */
public final class RotationElement extends HudElement {
    private static final String[] AXES = {"+Z (south)", "-X +Z", "-X (west)", "-X -Z", "-Z (north)", "+X -Z", "+X (east)", "+X +Z"};
    private static final int D = 30;

    private final BoolSetting dial = add(new BoolSetting("Dial", "Draw the heading dial and pitch gauge.", true));
    private final BoolSetting axis = add(new BoolSetting("Axis helper", "How far you are from the nearest axis or diagonal "
            + "(green within a tenth of a degree).", true));
    private final BoolSetting background = add(new BoolSetting("Background", true));

    public RotationElement() {
        super("rotation", "Rotation", "Exact yaw and pitch with a dial, and how far off the nearest axis or diagonal you are "
                + "(for highways).", false, HAnchor.LEFT, VAnchor.CENTER, 4, 0);
    }

    @Override
    protected int[] measure(Ui ui, boolean preview) {
        if (ui.mc.player == null && !preview) return new int[]{0, 0};
        int text = 112;
        return new int[]{(dial.get() ? D + 14 : 0) + text + 8, (dial.get() ? D : 20) + 8};
    }

    @Override
    protected void draw(Ui ui, int x, int y, boolean preview) {
        LocalPlayer p = ui.mc.player;
        float yaw = p == null ? -91.3f : Mth.wrapDegrees(p.getYRot());
        float pitch = p == null ? 12.5f : p.getXRot();
        if (background.get()) ui.round(x, y, width, height, ui.smallRadius(), ui.theme.hudBackground());
        int tx = x + 4;
        if (dial.get()) {
            int cx = x + 4 + D / 2, cy = y + 4 + D / 2;
            dev.erebus.client.render.Render2D.circle(ui.g, cx, cy, D / 2, ui.fade(ui.theme.track()));
            dev.erebus.client.render.Render2D.circle(ui.g, cx, cy, D / 2 - 2, ui.fade(ui.theme.hudBackground()));
            float b = CompassElement.bearing(yaw);
            for (int i = 0; i < 4; i++) { // N E S W ticks
                double a = Math.toRadians(i * 90);
                int px = cx + (int) Math.round(Math.sin(a) * (D / 2f - 3)), py = cy - (int) Math.round(Math.cos(a) * (D / 2f - 3));
                ui.rect(px, py, 1, 1, i == 0 ? 0xFFFF5C6C : ui.theme.dim());
            }
            double a = Math.toRadians(b);
            for (int s = 0; s <= D / 2 - 4; s++) { // the needle
                int px = cx + (int) Math.round(Math.sin(a) * s), py = cy - (int) Math.round(Math.cos(a) * s);
                ui.rect(px, py, s > D / 2 - 7 ? 2 : 1, s > D / 2 - 7 ? 2 : 1, ui.theme.accent());
            }
            // pitch gauge: up at the top, down at the bottom
            int gx = x + D + 9, gy = y + 4;
            ui.rect(gx, gy, 2, D, ui.theme.track());
            int my = gy + Math.round((pitch + 90f) / 180f * (D - 3));
            ui.rect(gx - 1, my, 4, 3, ui.theme.accent());
            tx = x + D + 18;
        }
        int ty = y + 4;
        ui.text("Yaw ", tx, ty, ui.theme.accent());
        ui.text(String.format(Locale.ROOT, "%.1f°", yaw), tx + 26, ty, ui.theme.text());
        ui.text("Pitch ", tx + 62, ty, ui.theme.accent());
        ui.text(String.format(Locale.ROOT, "%.1f°", pitch), tx + 92, ty - 0, ui.theme.text());
        if (axis.get()) {
            float snapped = Math.round(yaw / 45f) * 45f;
            float off = Mth.wrapDegrees(yaw - snapped);
            int idx = Math.floorMod(Math.round(snapped / 45f), 8);
            boolean on = Math.abs(off) < 0.1f;
            int c = on ? Theme.GOOD : Math.abs(off) < 1f ? Theme.WARN : ui.theme.dim();
            ui.text(AXES[idx], tx, ty + 11, ui.theme.text());
            String o = on ? "on axis" : String.format(Locale.ROOT, "%+.2f° off", off);
            ui.text(o, tx, ty + 21, c);
            if (!on && Math.abs(off) < 5f) { // which way to turn
                ui.text(off > 0 ? "◂ turn left" : "turn right ▸", tx + 58, ty + 21, Colors.mulAlpha(c, 0.8f));
            }
        } else {
            ui.text(String.format(Locale.ROOT, "Bearing %.1f°", CompassElement.bearing(yaw)), tx, ty + 11, ui.theme.dim());
        }
    }
}
