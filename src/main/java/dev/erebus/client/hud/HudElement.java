package dev.erebus.client.hud;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.setting.Setting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A HUD element. Its position is stored as an anchor (screen edge or center on each axis) plus an
 * offset from that anchor, so layouts keep their place when the window or GUI scale changes.
 */
public abstract class HudElement {
    public enum HAnchor { LEFT, CENTER, RIGHT }

    public enum VAnchor { TOP, CENTER, BOTTOM }

    public final String id;
    public final String name;
    public final String description;

    private final List<Setting<?>> settings = new ArrayList<>();
    private final boolean defaultEnabled;
    private final HAnchor defaultH;
    private final VAnchor defaultV;
    private final int defaultOffX;
    private final int defaultOffY;

    private boolean enabled;
    public HAnchor hAnchor;
    public VAnchor vAnchor;
    public int offX;
    public int offY;

    /** Last computed bounds, in GUI coordinates. */
    public int x;
    public int y;
    public int width;
    public int height;

    protected HudElement(String id, String name, String description, boolean enabled,
                         HAnchor h, VAnchor v, int offX, int offY) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.defaultEnabled = enabled;
        this.enabled = enabled;
        this.defaultH = h;
        this.defaultV = v;
        this.defaultOffX = offX;
        this.defaultOffY = offY;
        resetPosition();
    }

    protected <S extends Setting<?>> S add(S setting) {
        settings.add(setting);
        return setting;
    }

    public List<Setting<?>> settings() {
        return Collections.unmodifiableList(settings);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean defaultEnabled() {
        return defaultEnabled;
    }

    public void resetPosition() {
        hAnchor = defaultH;
        vAnchor = defaultV;
        offX = defaultOffX;
        offY = defaultOffY;
    }

    /** Text and content should hug the right edge when anchored right. */
    public boolean alignRight() {
        return hAnchor == HAnchor.RIGHT;
    }

    /** Stacked content should grow upward when anchored to the bottom. */
    public boolean growUp() {
        return vAnchor == VAnchor.BOTTOM;
    }

    /**
     * Measures the element and returns {width, height}. {@code preview} is true in the HUD editor,
     * where elements show sample content when there is nothing real to display.
     */
    protected abstract int[] measure(Ui ui, boolean preview);

    /** Draws the element with its top-left corner at (x, y) using the last measured size. */
    protected abstract void draw(Ui ui, int x, int y, boolean preview);

    /** Measures and positions the element for a screen of the given size. */
    public void layout(Ui ui, int screenW, int screenH, boolean preview) {
        int[] size = measure(ui, preview);
        width = size[0];
        height = size[1];
        int px = switch (hAnchor) {
            case LEFT -> offX;
            case CENTER -> (screenW - width) / 2 + offX;
            case RIGHT -> screenW - width - offX;
        };
        int py = switch (vAnchor) {
            case TOP -> offY;
            case CENTER -> (screenH - height) / 2 + offY;
            case BOTTOM -> screenH - height - offY;
        };
        x = Math.max(0, Math.min(screenW - width, px));
        y = Math.max(0, Math.min(screenH - height, py));
    }

    public void render(Ui ui, boolean preview) {
        if (width > 0 && height > 0) draw(ui, x, y, preview);
    }

    /** Re-anchors the element to the nearest edge for an absolute position chosen in the editor. */
    public void moveTo(int px, int py, int screenW, int screenH) {
        int cx = px + width / 2;
        int cy = py + height / 2;
        hAnchor = cx < screenW / 3 ? HAnchor.LEFT : cx > screenW * 2 / 3 ? HAnchor.RIGHT : HAnchor.CENTER;
        vAnchor = cy < screenH / 3 ? VAnchor.TOP : cy > screenH * 2 / 3 ? VAnchor.BOTTOM : VAnchor.CENTER;
        offX = switch (hAnchor) {
            case LEFT -> px;
            case CENTER -> px - (screenW - width) / 2;
            case RIGHT -> screenW - width - px;
        };
        offY = switch (vAnchor) {
            case TOP -> py;
            case CENTER -> py - (screenH - height) / 2;
            case BOTTOM -> screenH - height - py;
        };
        x = px;
        y = py;
    }

    public String anchorLabel() {
        String v = switch (vAnchor) {
            case TOP -> "top";
            case CENTER -> "middle";
            case BOTTOM -> "bottom";
        };
        String h = switch (hAnchor) {
            case LEFT -> "left";
            case CENTER -> "center";
            case RIGHT -> "right";
        };
        return vAnchor == VAnchor.CENTER && hAnchor == HAnchor.CENTER ? "center" : v + " " + h;
    }
}
