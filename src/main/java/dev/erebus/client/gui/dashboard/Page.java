package dev.erebus.client.gui.dashboard;

import dev.erebus.client.gui.widget.Container;

/** One sidebar destination in the dashboard. Pages keep their selection state between rebuilds. */
public abstract class Page {
    protected final DashboardScreen screen;

    protected Page(DashboardScreen screen) {
        this.screen = screen;
    }

    public abstract String id();

    public abstract String label();

    /** Small text at the right of the sidebar item, or null. */
    public String badge() {
        return null;
    }

    /** Adds this page's widgets for the content area (list column + settings pane). */
    public abstract void build(Container root, int x, int y, int w, int h);
}
