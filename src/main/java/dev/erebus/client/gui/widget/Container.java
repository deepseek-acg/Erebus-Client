package dev.erebus.client.gui.widget;

import dev.erebus.client.gui.Ui;

import java.util.ArrayList;
import java.util.List;

/** Holds child widgets; later children draw on top and receive clicks first. */
public class Container extends Widget {
    protected final List<Widget> children = new ArrayList<>();

    public <W extends Widget> W add(W widget) {
        children.add(widget);
        return widget;
    }

    public void clear() {
        children.clear();
    }

    public List<Widget> children() {
        return children;
    }

    @Override
    public void render(Ui ui, int mx, int my) {
        for (Widget w : children) {
            if (w.visible) w.render(ui, mx, my);
        }
    }

    @Override
    public boolean mouseClicked(Ui ui, double mx, double my, int button) {
        for (int i = children.size() - 1; i >= 0; i--) {
            Widget w = children.get(i);
            if (w.visible && w.mouseClicked(ui, mx, my, button)) return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(Ui ui, double mx, double my, double amount) {
        for (int i = children.size() - 1; i >= 0; i--) {
            Widget w = children.get(i);
            if (w.visible && w.mouseScrolled(ui, mx, my, amount)) return true;
        }
        return false;
    }

    /** Lowest child edge, used to size scrollable content. */
    public int contentBottom() {
        int b = y;
        for (Widget w : children) {
            if (w.visible) b = Math.max(b, w.bottom());
        }
        return b;
    }
}
