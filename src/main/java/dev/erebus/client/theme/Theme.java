package dev.erebus.client.theme;

import dev.erebus.client.util.Anim;
import dev.erebus.client.util.Colors;

import java.util.EnumMap;
import java.util.Map;

/**
 * The live, editable theme. Every widget reads colors from here each frame, so edits apply
 * immediately. Raw colors are user-editable; derived colors (hover, selection, ...) are computed.
 */
public final class Theme {
    public static final int GOOD = 0xFF5FD38A;
    public static final int WARN = 0xFFF0C24B;
    public static final int BAD = 0xFFFF5C6C;

    private final Map<ThemeColor, Integer> colors = new EnumMap<>(ThemeColor.class);
    private ThemePreset base = ThemePreset.VOID;
    private boolean modified;

    public int radius;
    public float panelOpacity = 1f;
    public boolean shadow = true;
    public boolean blur;
    public boolean textShadow;
    public boolean animations = true;

    public Theme() {
        apply(ThemePreset.VOID);
    }

    public void apply(ThemePreset preset) {
        for (ThemeColor c : ThemeColor.values()) colors.put(c, preset.color(c));
        radius = preset.radius;
        shadow = preset.shadow;
        blur = preset.blur;
        panelOpacity = 1f;
        textShadow = false;
        base = preset;
        modified = false;
        Anim.Animations.set(animations);
    }

    public int get(ThemeColor c) {
        return colors.get(c);
    }

    public void set(ThemeColor c, int argb) {
        if (colors.get(c) != argb) {
            colors.put(c, argb);
            modified = true;
        }
    }

    public ThemePreset base() {
        return base;
    }

    public void setBase(ThemePreset base) {
        this.base = base;
    }

    public boolean modified() {
        return modified;
    }

    public void markModified() {
        modified = true;
    }

    public void setModified(boolean modified) {
        this.modified = modified;
    }

    public void setAnimations(boolean on) {
        animations = on;
        Anim.Animations.set(on);
    }

    // ---- resolved colors ----

    public int background() {
        return Colors.mulAlpha(get(ThemeColor.BACKGROUND), panelOpacity);
    }

    public int header() {
        return Colors.mulAlpha(get(ThemeColor.HEADER), panelOpacity);
    }

    public int border() {
        return get(ThemeColor.BORDER);
    }

    public int text() {
        return get(ThemeColor.TEXT);
    }

    public int dim() {
        return get(ThemeColor.TEXT_DIM);
    }

    public int accent() {
        return get(ThemeColor.ACCENT);
    }

    public int accent2() {
        return get(ThemeColor.ACCENT_2);
    }

    public int hudBackground() {
        return get(ThemeColor.HUD_BACKGROUND);
    }

    /** Background for hovered rows. */
    public int hover() {
        return Colors.withAlpha(accent(), 0x1C);
    }

    /** Background for selected / active rows. */
    public int selected() {
        return Colors.withAlpha(accent(), 0x2E);
    }

    /** Background of tracks, inputs and secondary buttons. */
    public int track() {
        return 0x22FFFFFF;
    }

    /** Recessed background for text inputs. */
    public int inset() {
        return 0x59000000;
    }

    /** Text drawn on top of the accent color. */
    public int onAccent() {
        return Colors.contrastText(accent());
    }

    public int divider() {
        return Colors.mulAlpha(border(), 0.8f);
    }

    /** Gradient color at position t (0..1) between accent and accent 2. */
    public int gradient(float t) {
        return Colors.lerp(accent(), accent2(), t);
    }
}
