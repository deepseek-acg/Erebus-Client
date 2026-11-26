package dev.erebus.client.util;

import java.util.Locale;

/** ARGB color helpers. All colors are packed as 0xAARRGGBB ints. */
public final class Colors {
    public static final int WHITE = 0xFFFFFFFF;
    public static final int BLACK = 0xFF000000;
    public static final int TRANSPARENT = 0x00000000;

    private Colors() {
    }

    public static int alpha(int c) {
        return c >>> 24;
    }

    public static int red(int c) {
        return (c >> 16) & 0xFF;
    }

    public static int green(int c) {
        return (c >> 8) & 0xFF;
    }

    public static int blue(int c) {
        return c & 0xFF;
    }

    public static int argb(int a, int r, int g, int b) {
        return (clamp(a) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    public static int withAlpha(int c, int a) {
        return (c & 0x00FFFFFF) | (clamp(a) << 24);
    }

    public static int mulAlpha(int c, float f) {
        return withAlpha(c, Math.round(alpha(c) * f));
    }

    public static int lerp(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return argb(
                Math.round(alpha(a) + (alpha(b) - alpha(a)) * t),
                Math.round(red(a) + (red(b) - red(a)) * t),
                Math.round(green(a) + (green(b) - green(a)) * t),
                Math.round(blue(a) + (blue(b) - blue(a)) * t));
    }

    /** Mixes {@code c} toward white by {@code t} (0..1), keeping alpha. */
    public static int lighten(int c, float t) {
        return withAlpha(lerp(c, WHITE, t), alpha(c));
    }

    /** h, s, v in 0..1. */
    public static int fromHsv(float h, float s, float v, int alpha) {
        h = h - (float) Math.floor(h);
        s = Math.max(0f, Math.min(1f, s));
        v = Math.max(0f, Math.min(1f, v));
        float r, g, b;
        if (s == 0f) {
            r = g = b = v;
        } else {
            float sector = h * 6f;
            int i = (int) Math.floor(sector);
            float f = sector - i;
            float p = v * (1f - s);
            float q = v * (1f - s * f);
            float t = v * (1f - s * (1f - f));
            switch (i % 6) {
                case 0 -> { r = v; g = t; b = p; }
                case 1 -> { r = q; g = v; b = p; }
                case 2 -> { r = p; g = v; b = t; }
                case 3 -> { r = p; g = q; b = v; }
                case 4 -> { r = t; g = p; b = v; }
                default -> { r = v; g = p; b = q; }
            }
        }
        return argb(alpha, Math.round(r * 255), Math.round(g * 255), Math.round(b * 255));
    }

    /** Returns {h, s, v}, each 0..1. */
    public static float[] toHsv(int c) {
        float r = red(c) / 255f, g = green(c) / 255f, b = blue(c) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        float h;
        if (d == 0f) {
            h = 0f;
        } else if (max == r) {
            h = ((g - b) / d) % 6f;
        } else if (max == g) {
            h = (b - r) / d + 2f;
        } else {
            h = (r - g) / d + 4f;
        }
        h /= 6f;
        if (h < 0f) h += 1f;
        float s = max == 0f ? 0f : d / max;
        return new float[]{h, s, max};
    }

    /** Relative luminance, 0..1. */
    public static float luminance(int c) {
        return (0.2126f * red(c) + 0.7152f * green(c) + 0.0722f * blue(c)) / 255f;
    }

    /** A readable text color for content placed on {@code background}. */
    public static int contrastText(int background) {
        return luminance(background) > 0.6f ? 0xFF111113 : 0xFFFFFFFF;
    }

    public static String toHex(int c, boolean withAlpha) {
        return withAlpha
                ? String.format(Locale.ROOT, "#%08X", c)
                : String.format(Locale.ROOT, "#%06X", c & 0xFFFFFF);
    }

    /** Parses #RGB, #RRGGBB or #AARRGGBB (leading # optional). Returns null when invalid. */
    public static Integer parseHex(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        try {
            return switch (s.length()) {
                case 3 -> {
                    int r = Integer.parseInt(s.substring(0, 1), 16);
                    int g = Integer.parseInt(s.substring(1, 2), 16);
                    int b = Integer.parseInt(s.substring(2, 3), 16);
                    yield argb(255, r * 17, g * 17, b * 17);
                }
                case 6 -> 0xFF000000 | Integer.parseInt(s, 16);
                case 8 -> (int) Long.parseLong(s, 16);
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
