package dev.erebus.client.module;

import dev.erebus.client.render.font.Icon;

public enum Category {
    COMBAT("Combat", Icon.CROSSHAIR),
    MOVEMENT("Movement", Icon.MOVEMENT),
    RENDER("Render", Icon.EYE),
    PLAYER("Player", Icon.PLAYER),
    WORLD("World", Icon.GLOBE),
    BASEFINDING("Basefinding", Icon.RADAR),
    MISC("Misc", Icon.DOTS);

    public final String label;
    public final Icon icon;

    Category(String label, Icon icon) {
        this.label = label;
        this.icon = icon;
    }
}
