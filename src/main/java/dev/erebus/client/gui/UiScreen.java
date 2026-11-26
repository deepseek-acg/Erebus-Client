package dev.erebus.client.gui;

import dev.erebus.client.gui.widget.Container;
import dev.erebus.client.gui.widget.Widget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Base screen for Erebus UIs. Widgets live in a fixed-size design space that is scaled and
 * centered to fit the window, so layouts work at every GUI scale.
 */
public abstract class UiScreen extends net.minecraft.client.gui.screens.Screen {
    protected final Ui ui = new Ui();
    protected final Container root = new Container();
    protected float scale = 1f;
    protected float originX;
    protected float originY;

    protected UiScreen(Component title) {
        super(title);
    }

    /** Width of the design space. */
    protected abstract int designWidth();

    /** Height of the design space. */
    protected abstract int designHeight();

    /** Creates the widget tree. Called on open and whenever the window is resized. */
    protected abstract void build();

    /** Extra multiplier on top of fit-to-window scaling (1 = native GUI scale). */
    protected float userScale() {
        return 1f;
    }

    /** Scale applied this frame; subclasses may animate it. */
    protected float frameScale() {
        return scale;
    }

    /** Draws everything under the widgets (window frame etc.), in design space. */
    protected void renderFrame(Ui ui, int mx, int my) {
    }

    /** Draws above widgets but below the popover, in design space. */
    protected void renderOverlay(Ui ui, int mx, int my) {
    }

    /** True for screens whose design space is the whole window at GUI scale (no fitting). */
    protected boolean fullWindow() {
        return false;
    }

    @Override
    protected void init() {
        if (fullWindow()) {
            scale = 1f;
        } else {
            float fit = Math.min((width - 16f) / designWidth(), (height - 16f) / designHeight());
            scale = Math.min(fit, userScale());
        }
        rebuild();
    }

    public void rebuild() {
        Widget focused = ui.focused();
        if (focused != null) ui.focus(null);
        ui.release();
        root.clear();
        root.bounds(0, 0, designWidth(), designHeight());
        build();
    }

    private void updateOrigin() {
        float s = frameScale();
        originX = (width - designWidth() * s) / 2f;
        originY = (height - designHeight() * s) / 2f;
    }

    protected double toLocalX(double x) {
        return (x - originX) / frameScale();
    }

    protected double toLocalY(double y) {
        return (y - originY) / frameScale();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        ui.begin(g);
        updateOrigin();
        int mx = (int) Math.floor(toLocalX(mouseX));
        int my = (int) Math.floor(toLocalY(mouseY));
        ui.mouseX = mx;
        ui.mouseY = my;
        Widget pop = ui.popover();
        boolean overPopover = pop != null && pop.contains(mx, my);
        int wmx = overPopover ? -10000 : mx;
        int wmy = overPopover ? -10000 : my;

        g.pose().pushMatrix();
        g.pose().translate(originX, originY);
        g.pose().scale(frameScale(), frameScale());
        renderFrame(ui, wmx, wmy);
        root.render(ui, wmx, wmy);
        renderOverlay(ui, wmx, wmy);
        if (pop != null) pop.render(ui, mx, my);
        String tip = ui.takeTooltip();
        if (tip != null) drawTooltip(tip, mx, my);
        g.pose().popMatrix();
    }

    /** A wrapped description bubble near the mouse, kept inside the design space. */
    protected void drawTooltip(String text, int mx, int my) {
        java.util.List<String> lines = ui.wrap(text, 190);
        int w = 0;
        for (String l : lines) w = Math.max(w, ui.width(l));
        w += 14;
        int h = lines.size() * 11 + 9;
        int x = mx + 10;
        int y = my + 12;
        if (x + w > designWidth() - 2) x = mx - w - 6;
        if (y + h > designHeight() - 2) y = my - h - 6;
        x = Math.max(2, x);
        y = Math.max(2, y);
        int r = ui.smallRadius();
        dev.erebus.client.render.Render2D.shadow(ui.g, x, y, w, h, r, 4, 0x70000000);
        ui.round(x, y, w, h, r, dev.erebus.client.util.Colors.withAlpha(ui.theme.get(dev.erebus.client.theme.ThemeColor.BACKGROUND), 0xF7));
        ui.border(x, y, w, h, r, ui.theme.border());
        int ly = y + 5;
        for (String l : lines) {
            ui.text(l, x + 7, ly, ui.theme.text());
            ly += 11;
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = toLocalX(event.x());
        double my = toLocalY(event.y());
        int button = event.button();
        ui.offsetY = 0;
        Widget pop = ui.popover();
        if (pop != null) {
            if (pop.contains(mx, my)) {
                pop.mouseClicked(ui, mx, my, button);
                return true;
            }
            ui.closePopover();
            return true;
        }
        Widget focused = ui.focused();
        if (focused != null && !focused.contains(mx, my + ui.focusedOffsetY())) ui.focus(null);
        return root.mouseClicked(ui, mx, my, button) || super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        Widget c = ui.captured();
        if (c != null) {
            c.mouseDragged(ui, toLocalX(event.x()), toLocalY(event.y()) + ui.capturedOffsetY(), event.button());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        Widget c = ui.captured();
        if (c != null) {
            c.mouseReleased(ui, toLocalX(event.x()), toLocalY(event.y()) + ui.capturedOffsetY(), event.button());
            ui.release();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        double mx = toLocalX(x);
        double my = toLocalY(y);
        ui.offsetY = 0;
        Widget pop = ui.popover();
        if (pop != null && pop.contains(mx, my)) return pop.mouseScrolled(ui, mx, my, scrollY);
        return root.mouseScrolled(ui, mx, my, scrollY) || super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        ui.takeSwallowChar();
        Widget focused = ui.focused();
        if (focused != null && focused.keyPressed(ui, event.key(), event.modifiers())) return true;
        if (ui.popover() != null && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            ui.closePopover();
            return true;
        }
        if (onKey(event.key(), event.modifiers())) return true;
        return super.keyPressed(event);
    }

    /** Screen-level shortcuts, called when no focused widget consumed the key. */
    protected boolean onKey(int key, int modifiers) {
        return false;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (ui.takeSwallowChar()) return true;
        Widget focused = ui.focused();
        if (focused != null && focused.charTyped(ui, event.codepointAsString())) return true;
        return onChar(event.codepointAsString()) || super.charTyped(event);
    }

    /** Typed characters that no focused widget consumed. */
    protected boolean onChar(String chars) {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
