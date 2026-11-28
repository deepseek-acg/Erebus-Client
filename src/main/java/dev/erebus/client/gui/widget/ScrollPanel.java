package dev.erebus.client.gui.widget;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.util.Anim;
import dev.erebus.client.util.Colors;

/** A clipped, vertically scrolling container. Children are laid out in unscrolled coordinates. */
public class ScrollPanel extends Container {
    private final Anim scroll = new Anim(0f, 20f);
    private float target;
    public int paddingBottom = 6;
    /**
     * What this panel shows (a module's settings, a page...). When the screen is rebuilt, a new panel with the same
     * key takes over the old one's scroll position, so changing a setting doesn't jump back to the top.
     */
    public String key;
    private boolean draggingBar;
    private double dragStartY;
    private float dragStartScroll;

    public ScrollPanel(int x, int y, int w, int h) {
        bounds(x, y, w, h);
    }

    public int maxScroll() {
        return Math.max(0, contentBottom() + paddingBottom - (y + height));
    }

    private long revealStart = -1;

    /** Cascade the content in from the top, row by row (a newly picked module's settings). */
    public ScrollPanel reveal() {
        revealStart = System.currentTimeMillis();
        return this;
    }

    public ScrollPanel key(String key) {
        this.key = key;
        return this;
    }

    public float scroll() {
        return scroll.get();
    }

    /** Where it's scrolling to (the same as {@link #scroll()} once it settles). */
    public float targetScroll() {
        return target;
    }

    public void setScroll(float value) {
        target = Math.max(0, Math.min(maxScroll(), value));
        scroll.snap(target);
    }

    @Override
    public void render(Ui ui, int mx, int my) {
        target = Math.max(0, Math.min(maxScroll(), target));
        scroll.target(target);
        float s = scroll.update(ui.dt);
        int off = Math.round(s);
        boolean inside = contains(mx, my);
        ui.g.enableScissor(x, y, x + width, y + height);
        ui.g.pose().pushMatrix();
        ui.g.pose().translate(0, -off);
        if (revealStart < 0 || !dev.erebus.client.util.Anim.Animations.enabled()) {
            super.render(ui, inside ? mx : -10000, inside ? my + off : -10000);
        } else {
            renderRevealing(ui, inside ? mx : -10000, inside ? my + off : -10000, off);
        }
        ui.g.pose().popMatrix();
        ui.g.disableScissor();

        int max = maxScroll();
        if (max > 0) {
            int trackH = height - 4;
            int barH = Math.max(14, Math.round(trackH * (height / (float) (height + max))));
            int barY = y + 2 + Math.round((trackH - barH) * (s / max));
            boolean hot = draggingBar || (inside && mx >= x + width - 6);
            ui.round(x + width - 4, barY, 3, barH, 1, Colors.withAlpha(ui.theme.text(), hot ? 0x70 : 0x30));
        }
    }

    /** Each row fades in and drops into place a little after the one above it. */
    private void renderRevealing(Ui ui, int mx, int my, int off) {
        long t = System.currentTimeMillis() - revealStart;
        boolean done = true;
        float before = ui.alpha;
        for (Widget w : children) {
            if (!w.visible) continue;
            long delay = Math.min(220, Math.max(0, (w.y - y - off)) * 2L / 3);
            float p = Math.max(0f, Math.min(1f, (t - delay) / 200f));
            if (p < 1f) done = false;
            if (p <= 0f) continue;
            float e = 1f - (1f - p) * (1f - p) * (1f - p); // ease out
            ui.alpha = before * e;
            ui.g.pose().pushMatrix();
            ui.g.pose().translate(0, (1f - e) * 6f);
            w.render(ui, mx, my);
            ui.g.pose().popMatrix();
        }
        ui.alpha = before;
        if (done) revealStart = -1;
    }

    @Override
    public boolean mouseClicked(Ui ui, double mx, double my, int button) {
        if (!contains(mx, my)) return false;
        if (button == 0 && maxScroll() > 0 && mx >= x + width - 6) {
            draggingBar = true;
            dragStartY = my;
            dragStartScroll = target;
            ui.capture(this);
            return true;
        }
        double prev = ui.offsetY;
        ui.offsetY += scroll.get();
        try {
            return super.mouseClicked(ui, mx, my + Math.round(scroll.get()), button);
        } finally {
            ui.offsetY = prev;
        }
    }

    @Override
    public void mouseDragged(Ui ui, double mx, double my, int button) {
        if (!draggingBar) return;
        int max = maxScroll();
        float ratio = (height + max) / (float) height;
        target = Math.max(0, Math.min(max, dragStartScroll + (float) ((my - dragStartY) * ratio)));
    }

    @Override
    public void mouseReleased(Ui ui, double mx, double my, int button) {
        draggingBar = false;
    }

    @Override
    public boolean mouseScrolled(Ui ui, double mx, double my, double amount) {
        if (!contains(mx, my)) return false;
        double prev = ui.offsetY;
        ui.offsetY += scroll.get();
        try {
            if (super.mouseScrolled(ui, mx, my + Math.round(scroll.get()), amount)) return true;
        } finally {
            ui.offsetY = prev;
        }
        if (maxScroll() <= 0) return false;
        target = Math.max(0, Math.min(maxScroll(), target - (float) amount * 22f));
        return true;
    }
}
