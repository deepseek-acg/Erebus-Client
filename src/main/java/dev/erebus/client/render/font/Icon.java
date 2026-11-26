package dev.erebus.client.render.font;

/** UI icons drawn into Erebus Sans's Private Use Area (see tools/build_font.py). */
public enum Icon {
    SEARCH(0xE000), CLOSE(0xE001), CHEVRON_RIGHT(0xE002), CHEVRON_DOWN(0xE003), CHECK(0xE004), SLIDERS(0xE005),
    PIN(0xE006), CROSSHAIR(0xE007), MOVEMENT(0xE008), EYE(0xE009), PLAYER(0xE00A), GLOBE(0xE00B), RADAR(0xE00C),
    DOTS(0xE00D), GRID(0xE00E), DROP(0xE00F), LOGO(0xE010), PLUS(0xE011), FLAG(0xE012), INFO(0xE013),
    CHEVRON_LEFT(0xE014), CHEVRON_UP(0xE015), MAP(0xE016), TRASH(0xE017), COPY(0xE018), TARGET(0xE019);

    public final String glyph;

    Icon(int codepoint) {
        this.glyph = new String(Character.toChars(codepoint));
    }
}
