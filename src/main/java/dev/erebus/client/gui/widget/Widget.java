package dev.erebus.client.gui.widget;

import dev.erebus.client.gui.Ui;

/**
 * Base class of the retained-mode widget toolkit. Coordinates are in the screen's design space.
 * Drag and release events go only to the widget that captured the pointer via {@link Ui#capture}.
 */
public abstract class Widget {
    public int x;
    public int y;
    public int width;
    public int height;
    public boolean visible = true;

    public Widget bounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        return this;
    }

    public boolean contains(double mx, double my) {
        return visible && mx >= x && mx < x + width && my >= y && my < y + height;
    }

    public int right() {
        return x + width;
    }

    public int bottom() {
        return y + height;
    }

    public abstract void render(Ui ui, int mx, int my);

    public boolean mouseClicked(Ui ui, double mx, double my, int button) {
        return false;
    }

    public void mouseDragged(Ui ui, double mx, double my, int button) {
    }

    public void mouseReleased(Ui ui, double mx, double my, int button) {
    }

    public boolean mouseScrolled(Ui ui, double mx, double my, double amount) {
        return false;
    }

    public boolean keyPressed(Ui ui, int key, int modifiers) {
        return false;
    }

    public boolean charTyped(Ui ui, String chars) {
        return false;
    }

    public void onFocusLost(Ui ui) {
    }
}
