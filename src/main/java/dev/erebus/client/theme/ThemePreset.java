package dev.erebus.client.theme;

import java.util.EnumMap;
import java.util.Map;

/** Built-in starting points for the theme editor. */
public enum ThemePreset {
    VOID("void", "Void", 6, true, false,
            0xE60D0918, 0xF51E1336, 0x479B6BFF, 0xFFEFE9FF, 0xFF968DB3, 0xFF9B6BFF, 0xFFE06BFF, 0x66000000),
    ABYSS("abyss", "Abyss", 8, true, true,
            0xC70C1822, 0xD9122634, 0x3378DCF0, 0xFFE6F6FA, 0xFF93B3BE, 0xFF4FD6E8, 0xFF6F8DFF, 0x5C04121A),
    EMBER("ember", "Ember", 1, true, false,
            0xED100705, 0xF52C0D07, 0x5CFF5A36, 0xFFFFECE4, 0xFFB89286, 0xFFFF5A36, 0xFFFFB02E, 0x6E0A0302),
    MONO("mono", "Mono", 0, false, false,
            0xF2151517, 0xFA212124, 0x1FFFFFFF, 0xFFF1F1F3, 0xFF8D8D96, 0xFFE6E6EA, 0xFF80808A, 0x6B000000);

    public final String id;
    public final String label;
    public final int radius;
    public final boolean shadow;
    public final boolean blur;
    private final Map<ThemeColor, Integer> colors = new EnumMap<>(ThemeColor.class);

    ThemePreset(String id, String label, int radius, boolean shadow, boolean blur,
                int background, int header, int border, int text, int textDim, int accent, int accent2, int hudBackground) {
        this.id = id;
        this.label = label;
        this.radius = radius;
        this.shadow = shadow;
        this.blur = blur;
        colors.put(ThemeColor.BACKGROUND, background);
        colors.put(ThemeColor.HEADER, header);
        colors.put(ThemeColor.BORDER, border);
        colors.put(ThemeColor.TEXT, text);
        colors.put(ThemeColor.TEXT_DIM, textDim);
        colors.put(ThemeColor.ACCENT, accent);
        colors.put(ThemeColor.ACCENT_2, accent2);
        colors.put(ThemeColor.HUD_BACKGROUND, hudBackground);
    }

    public int color(ThemeColor c) {
        return colors.get(c);
    }

    public static ThemePreset byId(String id) {
        for (ThemePreset p : values()) {
            if (p.id.equals(id)) return p;
        }
        return VOID;
    }
}
