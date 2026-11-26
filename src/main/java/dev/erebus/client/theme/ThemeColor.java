package dev.erebus.client.theme;

/** The user-editable colors of a theme, in the order the theme editor lists them. */
public enum ThemeColor {
    BACKGROUND("background", "Background"),
    HEADER("header", "Header"),
    BORDER("border", "Border"),
    TEXT("text", "Text"),
    TEXT_DIM("text_dim", "Muted text"),
    ACCENT("accent", "Accent"),
    ACCENT_2("accent_2", "Accent 2"),
    HUD_BACKGROUND("hud_background", "HUD background");

    public final String id;
    public final String label;

    ThemeColor(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public static ThemeColor byId(String id) {
        for (ThemeColor c : values()) {
            if (c.id.equals(id)) return c;
        }
        return null;
    }
}
