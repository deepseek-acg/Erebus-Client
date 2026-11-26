package dev.erebus.client.render;

import dev.erebus.client.util.Colors;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Low-level 2D shape drawing on top of {@link GuiGraphics#fill}. Rounded shapes are drawn as
 * horizontal scanlines so they stay pixel-crisp at any GUI scale.
 */
public final class Render2D {
    private Render2D() {
    }

    public static void rect(GuiGraphics g, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0 || Colors.alpha(color) == 0) return;
        g.fill(x, y, x + w, y + h, color);
    }

    public static void gradientV(GuiGraphics g, int x, int y, int w, int h, int top, int bottom) {
        if (w <= 0 || h <= 0) return;
        g.fillGradient(x, y, x + w, y + h, top, bottom);
    }

    public static void gradientH(GuiGraphics g, int x, int y, int w, int h, int left, int right) {
        if (w <= 0 || h <= 0) return;
        if (left == right) {
            rect(g, x, y, w, h, left);
            return;
        }
        for (int i = 0; i < w; i++) {
            float t = w == 1 ? 0f : i / (float) (w - 1);
            g.fill(x + i, y, x + i + 1, y + h, Colors.lerp(left, right, t));
        }
    }

    /** 1px rectangle outline. */
    public static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        rect(g, x, y, w, 1, color);
        rect(g, x, y + h - 1, w, 1, color);
        rect(g, x, y + 1, 1, h - 2, color);
        rect(g, x + w - 1, y + 1, 1, h - 2, color);
    }

    /** 1px dashed outline, used by the HUD editor. */
    public static void dashedOutline(GuiGraphics g, int x, int y, int w, int h, int color, int dash) {
        for (int i = 0; i < w; i += dash * 2) {
            int len = Math.min(dash, w - i);
            rect(g, x + i, y, len, 1, color);
            rect(g, x + i, y + h - 1, len, 1, color);
        }
        for (int i = 0; i < h; i += dash * 2) {
            int len = Math.min(dash, h - i);
            rect(g, x, y + i, 1, len, color);
            rect(g, x + w - 1, y + i, 1, len, color);
        }
    }

    public static void roundRect(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        roundRect(g, x, y, w, h, r, color, true, true);
    }

    /** Filled rounded rectangle. {@code roundTop}/{@code roundBottom} choose which corners are rounded. */
    public static void roundRect(GuiGraphics g, int x, int y, int w, int h, int r, int color, boolean roundTop, boolean roundBottom) {
        if (w <= 0 || h <= 0 || Colors.alpha(color) == 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) {
            rect(g, x, y, w, h, color);
            return;
        }
        int top = roundTop ? r : 0;
        int bottom = roundBottom ? r : 0;
        rect(g, x, y + top, w, h - top - bottom, color);
        for (int i = 0; i < r; i++) {
            int inset = cornerInset(r, i);
            if (roundTop) rect(g, x + inset, y + i, w - inset * 2, 1, color);
            if (roundBottom) rect(g, x + inset, y + h - 1 - i, w - inset * 2, 1, color);
        }
    }

    /** 1px rounded border that does not overdraw the interior, so it works on translucent panels. */
    public static void roundRectBorder(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        if (w <= 1 || h <= 1 || Colors.alpha(color) == 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) {
            outline(g, x, y, w, h, color);
            return;
        }
        rect(g, x + r, y, w - r * 2, 1, color);
        rect(g, x + r, y + h - 1, w - r * 2, 1, color);
        rect(g, x, y + r, 1, h - r * 2, color);
        rect(g, x + w - 1, y + r, 1, h - r * 2, color);
        int prev = r;
        for (int i = 0; i < r; i++) {
            int start = cornerInset(r, i);
            int end = Math.max(start, prev - 1);
            int len = end - start + 1;
            rect(g, x + start, y + i, len, 1, color);
            rect(g, x + w - 1 - end, y + i, len, 1, color);
            rect(g, x + start, y + h - 1 - i, len, 1, color);
            rect(g, x + w - 1 - end, y + h - 1 - i, len, 1, color);
            prev = start;
        }
    }

    /** Soft drop shadow made of stacked translucent rounded rectangles. */
    public static void shadow(GuiGraphics g, int x, int y, int w, int h, int r, int size, int color) {
        if (size <= 0 || Colors.alpha(color) == 0) return;
        int base = Colors.alpha(color);
        for (int i = size; i >= 1; i--) {
            float t = 1f - (i - 1) / (float) size;
            int a = Math.max(1, Math.round(base * t * t / size));
            roundRect(g, x - i, y - i + 2, w + i * 2, h + i * 2, r + i, Colors.withAlpha(color, a));
        }
    }

    public static void circle(GuiGraphics g, int cx, int cy, int radius, int color) {
        roundRect(g, cx - radius, cy - radius, radius * 2, radius * 2, radius, color);
    }

    /** Checkerboard, used behind translucent color swatches. */
    public static void checker(GuiGraphics g, int x, int y, int w, int h, int cell) {
        rect(g, x, y, w, h, 0xFFBDBDBD);
        for (int cy = 0; cy < h; cy += cell) {
            for (int cx = ((cy / cell) % 2) * cell; cx < w; cx += cell * 2) {
                rect(g, x + cx, y + cy, Math.min(cell, w - cx), Math.min(cell, h - cy), 0xFF7A7A7A);
            }
        }
    }

    private static int cornerInset(int r, int row) {
        double dy = r - row - 0.5;
        double dx = Math.sqrt(Math.max(0.0, (double) r * r - dy * dy));
        return (int) Math.round(r - dx);
    }
}
