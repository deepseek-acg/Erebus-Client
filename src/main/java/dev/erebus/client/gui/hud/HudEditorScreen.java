package dev.erebus.client.gui.hud;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.erebus.client.Erebus;
import dev.erebus.client.gui.Ui;
import dev.erebus.client.gui.UiScreen;
import dev.erebus.client.gui.widget.Button;
import dev.erebus.client.gui.widget.Drawable;
import dev.erebus.client.hud.HudElement;
import dev.erebus.client.hud.HudManager;
import dev.erebus.client.notify.Notifications;
import dev.erebus.client.render.Render2D;
import dev.erebus.client.theme.Theme;
import dev.erebus.client.util.Colors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Drag HUD elements into place. Positions snap to a grid (hold Alt to move freely) and are stored
 * relative to the nearest screen edge.
 */
public final class HudEditorScreen extends UiScreen {
    private static final int GRID = 4;
    private static final int EDGE_MARGIN = 2;

    private final Screen parent;
    private HudElement dragging;
    private HudElement selected;
    private int grabX;
    private int grabY;
    private int toolbarBottom;

    public HudEditorScreen(Screen parent) {
        super(Component.translatable("erebus.screen.hud_editor"));
        this.parent = parent;
    }

    @Override
    protected boolean fullWindow() {
        return true;
    }

    @Override
    protected int designWidth() {
        return width;
    }

    @Override
    protected int designHeight() {
        return height;
    }

    @Override
    protected void build() {
        HudManager hud = Erebus.hud();
        List<String> presets = new ArrayList<>(HudManager.PRESETS.keySet());
        int bh = 16;
        int gap = 3;
        int title = 74;
        int total = title;
        int[] widths = new int[presets.size()];
        for (int i = 0; i < presets.size(); i++) {
            widths[i] = font.width(presets.get(i)) + 14;
            total += widths[i] + gap;
        }
        int resetW = font.width("Reset") + 14;
        int doneW = font.width("Done") + 18;
        total += 6 + resetW + gap + doneW;
        int x = (width - total) / 2;
        int y = 6;
        int barX = x - 6;
        int barW = total + 12;
        root.add(new Drawable(barX, y - 4, barW, bh + 8, (ui, self, mx, my) -> {
            int r = ui.theme.radius;
            if (ui.theme.shadow) Render2D.shadow(ui.g, self.x, self.y, self.width, self.height, r, 6, 0x80000000);
            ui.round(self.x, self.y, self.width, self.height, r, ui.theme.background());
            ui.border(self.x, self.y, self.width, self.height, r, ui.theme.border());
            ui.textBold("HUD editor", self.x + 8, Ui.centerY(self.y, self.height), ui.theme.text());
        }));
        int bx = x + title;
        for (int i = 0; i < presets.size(); i++) {
            String name = presets.get(i);
            root.add(new Button(bx, y, widths[i], bh, name, Button.Style.SECONDARY, () -> {
                hud.applyPreset(name);
                selected = null;
            }).active(() -> name.equals(hud.preset())));
            bx += widths[i] + gap;
        }
        bx += 6;
        root.add(new Button(bx, y, resetW, bh, "Reset", Button.Style.GHOST, () -> {
            hud.resetLayout();
            Notifications.info("HUD", "layout reset");
        }));
        bx += resetW + gap;
        root.add(new Button(bx, y, doneW, bh, "Done", Button.Style.PRIMARY, this::onClose));
        toolbarBottom = y + bh + 4;

        root.add(new Drawable(0, toolbarBottom + 4, width, 10, (ui, self, mx, my) -> {
            String hint = dragging != null
                    ? dragging.name + " · anchored " + dragging.anchorLabel() + " · " + dragging.offX + ", " + dragging.offY
                    : "Drag to move · Alt: no snapping · Arrows: nudge · Right-click: hide";
            ui.textCentered(hint, width / 2, self.y, Colors.mulAlpha(ui.theme.text(), 0.85f));
        }));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (minecraft.level == null) {
            super.renderBackground(g, mouseX, mouseY, delta);
        }
        g.fill(0, 0, width, height, 0x38000000);
        int line = 0x0EFFFFFF;
        for (int x = 0; x < width; x += GRID * 4) g.fill(x, 0, x + 1, height, line);
        for (int y = 0; y < height; y += GRID * 4) g.fill(0, y, width, y + 1, line);
        int guide = 0x30FFFFFF;
        for (int y = 0; y < height; y += 6) g.fill(width / 2, y, width / 2 + 1, y + 3, guide);
        for (int x = 0; x < width; x += 6) g.fill(x, height / 2, x + 3, height / 2 + 1, guide);
    }

    @Override
    protected void renderFrame(Ui ui, int mx, int my) {
        Theme t = ui.theme;
        HudElement hovered = dragging != null ? dragging : elementAt(mx, my);
        if (hovered != null) ui.cursor(dragging != null ? CursorTypes.RESIZE_ALL : CursorTypes.POINTING_HAND);
        for (HudElement e : Erebus.hud().all()) {
            if (!e.isEnabled()) continue;
            if (e != dragging) e.layout(ui, width, height, true);
            ui.rect(e.x - 2, e.y - 2, e.width + 4, e.height + 4, e == hovered || e == selected ? 0x1EFFFFFF : 0x0CFFFFFF);
            e.render(ui, true);
            boolean hot = e == hovered || e == selected;
            int outline = hot ? t.accent() : Colors.withAlpha(t.accent(), 0x90);
            Render2D.dashedOutline(ui.g, e.x - 2, e.y - 2, e.width + 4, e.height + 4, outline, 3);
            if (hot) {
                String label = e.name;
                int lw = ui.width(label) + 8;
                int ly = e.y - 15 >= 0 ? e.y - 15 : e.y + e.height + 4;
                int lx = Math.max(0, Math.min(width - lw, e.x - 2));
                ui.round(lx, ly, lw, 11, 2, t.accent());
                ui.text(label, lx + 4, ly + 2, t.onAccent());
            }
        }
    }

    private HudElement elementAt(double mx, double my) {
        List<HudElement> all = Erebus.hud().all();
        for (int i = all.size() - 1; i >= 0; i--) {
            HudElement e = all.get(i);
            if (e.isEnabled() && mx >= e.x - 2 && mx < e.x + e.width + 2 && my >= e.y - 2 && my < e.y + e.height + 2) return e;
        }
        return null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        double mx = event.x();
        double my = event.y();
        HudElement e = elementAt(mx, my);
        if (e == null) {
            selected = null;
            return false;
        }
        if (event.button() == 1) {
            Erebus.hud().setEnabled(e, false);
            if (selected == e) selected = null;
            Notifications.info(e.name, "hidden · turn it back on from the HUD page");
            return true;
        }
        if (event.button() == 0) {
            dragging = e;
            selected = e;
            grabX = (int) Math.round(mx) - e.x;
            grabY = (int) Math.round(my) - e.y;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging == null) return super.mouseDragged(event, dx, dy);
        boolean free = InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_ALT)
                || InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_ALT);
        int nx = (int) Math.round(event.x()) - grabX;
        int ny = (int) Math.round(event.y()) - grabY;
        if (!free) {
            nx = Math.round(nx / (float) GRID) * GRID;
            ny = Math.round(ny / (float) GRID) * GRID;
            if (nx < GRID * 2) nx = EDGE_MARGIN;
            if (ny < GRID * 2) ny = EDGE_MARGIN;
            if (nx + dragging.width > width - GRID * 2) nx = width - dragging.width - EDGE_MARGIN;
            if (ny + dragging.height > height - GRID * 2) ny = height - dragging.height - EDGE_MARGIN;
            int cx = (width - dragging.width) / 2;
            if (Math.abs(nx - cx) <= GRID) nx = cx;
        }
        nx = Math.max(0, Math.min(width - dragging.width, nx));
        ny = Math.max(0, Math.min(height - dragging.height, ny));
        dragging.moveTo(nx, ny, width, height);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            dragging = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    protected boolean onKey(int key, int modifiers) {
        if (selected == null) return false;
        int step = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? GRID * 2 : 1;
        int dx = 0;
        int dy = 0;
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> dx = -step;
            case GLFW.GLFW_KEY_RIGHT -> dx = step;
            case GLFW.GLFW_KEY_UP -> dy = -step;
            case GLFW.GLFW_KEY_DOWN -> dy = step;
            case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> {
                Erebus.hud().setEnabled(selected, false);
                Notifications.info(selected.name, "hidden");
                selected = null;
                return true;
            }
            default -> {
                return false;
            }
        }
        int nx = Math.max(0, Math.min(width - selected.width, selected.x + dx));
        int ny = Math.max(0, Math.min(height - selected.height, selected.y + dy));
        selected.moveTo(nx, ny, width, height);
        return true;
    }

    @Override
    public void onClose() {
        Erebus.config().save();
        minecraft.setScreen(parent);
    }
}
