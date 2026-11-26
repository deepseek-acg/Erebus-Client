package dev.erebus.client.gui.widget;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.erebus.client.gui.Ui;
import dev.erebus.client.render.Render2D;
import dev.erebus.client.util.Colors;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** HSV color picker popover: saturation/value square, hue bar, alpha bar and hex input. */
public final class ColorPicker extends Container {
    public static final int W = 178;
    public static final int H = 140;

    private static final int PAD = 8;
    private static final int SV_W = 128;
    private static final int SV_H = 96;
    private static final int BAR_W = 10;

    private enum Drag { NONE, SV, HUE, ALPHA }

    private final IntSupplier getter;
    private final IntConsumer setter;
    private final TextField hex;
    private float hue;
    private float sat;
    private float val;
    private int alpha;
    private Drag drag = Drag.NONE;
    private int lastColor;

    public ColorPicker(int x, int y, IntSupplier getter, IntConsumer setter) {
        bounds(x, y, W, H);
        this.getter = getter;
        this.setter = setter;
        loadFrom(getter.getAsInt());
        int fy = y + PAD + SV_H + 10;
        hex = add(new TextField(x + PAD + 26, fy, 92, 18, Colors.toHex(lastColor, true), "#AARRGGBB", 9)
                .filter(c -> c == '#' || Character.digit(c, 16) >= 0)
                .onChange(s -> {
                    Integer c = Colors.parseHex(s);
                    if (c != null) {
                        loadFrom(c);
                        setter.accept(c);
                    }
                }));
    }

    private void loadFrom(int color) {
        float[] hsv = Colors.toHsv(color);
        if (hsv[1] > 0.001f && hsv[2] > 0.001f) hue = hsv[0];
        sat = hsv[1];
        val = hsv[2];
        alpha = Colors.alpha(color);
        lastColor = color;
    }

    private int current() {
        return Colors.fromHsv(hue, sat, val, alpha);
    }

    private void push(Ui ui) {
        int c = current();
        lastColor = c;
        setter.accept(c);
        if (!ui.isFocused(hex)) hex.setText(Colors.toHex(c, true));
    }

    @Override
    public void render(Ui ui, int mx, int my) {
        int external = getter.getAsInt();
        if (external != lastColor && drag == Drag.NONE && !ui.isFocused(hex)) {
            loadFrom(external);
            hex.setText(Colors.toHex(external, true));
        }
        int r = ui.theme.radius;
        Render2D.shadow(ui.g, x, y, width, height, r, 8, 0x90000000);
        ui.round(x, y, width, height, r, Colors.withAlpha(ui.theme.get(dev.erebus.client.theme.ThemeColor.BACKGROUND), 0xF5));
        ui.border(x, y, width, height, r, ui.theme.border());

        int sx = x + PAD;
        int sy = y + PAD;
        // saturation / value square: one vertical gradient per column
        for (int i = 0; i < SV_W; i++) {
            int top = Colors.fromHsv(hue, i / (float) (SV_W - 1), 1f, 255);
            Render2D.gradientV(ui.g, sx + i, sy, 1, SV_H, top, 0xFF000000);
        }
        int cx = sx + Math.round(sat * (SV_W - 1));
        int cy = sy + Math.round((1f - val) * (SV_H - 1));
        Render2D.outline(ui.g, cx - 3, cy - 3, 7, 7, 0xFF000000);
        Render2D.outline(ui.g, cx - 2, cy - 2, 5, 5, 0xFFFFFFFF);

        int hx = sx + SV_W + 6;
        for (int i = 0; i < SV_H; i++) {
            ui.rect(hx, sy + i, BAR_W, 1, Colors.fromHsv(i / (float) SV_H, 1f, 1f, 255));
        }
        int hy = sy + Math.round(hue * (SV_H - 1));
        ui.rect(hx - 1, hy - 1, BAR_W + 2, 3, 0xFF000000);
        ui.rect(hx - 1, hy, BAR_W + 2, 1, 0xFFFFFFFF);

        int ax = hx + BAR_W + 6;
        Render2D.checker(ui.g, ax, sy, BAR_W, SV_H, 5);
        int opaque = Colors.withAlpha(current(), 255);
        Render2D.gradientV(ui.g, ax, sy, BAR_W, SV_H, opaque, Colors.withAlpha(opaque, 0));
        int ay = sy + Math.round((1f - alpha / 255f) * (SV_H - 1));
        ui.rect(ax - 1, ay - 1, BAR_W + 2, 3, 0xFF000000);
        ui.rect(ax - 1, ay, BAR_W + 2, 1, 0xFFFFFFFF);

        int fy = sy + SV_H + 10;
        Render2D.checker(ui.g, sx, fy, 20, 18, 4);
        ui.rect(sx, fy, 20, 18, current());
        Render2D.outline(ui.g, sx, fy, 20, 18, 0x40FFFFFF);
        ui.text(Math.round(alpha / 2.55f) + "%", hex.right() + 6, Ui.centerY(fy, 18), ui.theme.dim());

        if (drag != Drag.NONE || (mx >= sx && mx < ax + BAR_W && my >= sy && my < sy + SV_H)) {
            ui.cursor(CursorTypes.CROSSHAIR);
        }
        super.render(ui, mx, my);
    }

    @Override
    public boolean mouseClicked(Ui ui, double mx, double my, int button) {
        if (!contains(mx, my)) return false;
        if (super.mouseClicked(ui, mx, my, button)) return true;
        int sx = x + PAD;
        int sy = y + PAD;
        int hx = sx + SV_W + 6;
        int ax = hx + BAR_W + 6;
        if (my >= sy - 2 && my < sy + SV_H + 2) {
            if (mx >= sx && mx < sx + SV_W) drag = Drag.SV;
            else if (mx >= hx - 2 && mx < hx + BAR_W + 2) drag = Drag.HUE;
            else if (mx >= ax - 2 && mx < ax + BAR_W + 2) drag = Drag.ALPHA;
        }
        if (drag != Drag.NONE) {
            ui.focus(null);
            ui.capture(this);
            apply(ui, mx, my);
        }
        return true;
    }

    @Override
    public void mouseDragged(Ui ui, double mx, double my, int button) {
        apply(ui, mx, my);
    }

    @Override
    public void mouseReleased(Ui ui, double mx, double my, int button) {
        drag = Drag.NONE;
    }

    private void apply(Ui ui, double mx, double my) {
        int sx = x + PAD;
        int sy = y + PAD;
        float fy = (float) Math.max(0, Math.min(1, (my - sy) / (SV_H - 1)));
        switch (drag) {
            case SV -> {
                sat = (float) Math.max(0, Math.min(1, (mx - sx) / (SV_W - 1)));
                val = 1f - fy;
            }
            case HUE -> hue = Math.min(0.999f, fy);
            case ALPHA -> alpha = Math.round((1f - fy) * 255);
            default -> {
                return;
            }
        }
        push(ui);
    }
}
