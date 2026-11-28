package dev.erebus.client.gui;

import com.mojang.blaze3d.platform.cursor.CursorType;
import dev.erebus.client.Erebus;
import dev.erebus.client.gui.widget.Widget;
import dev.erebus.client.render.Render2D;
import dev.erebus.client.render.font.Icon;
import dev.erebus.client.render.font.Text;
import dev.erebus.client.theme.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * Per-screen UI context: holds the current {@link GuiGraphics}, theme and frame time for widgets,
 * and tracks focus, pointer capture and the open popover.
 */
public final class Ui {
    public static final int LINE = 9;

    public final Minecraft mc = Minecraft.getInstance();
    public GuiGraphics g;
    public Font font;
    public Theme theme;
    /** Seconds since the previous frame (clamped). */
    public float dt;

    private long lastFrame;
    private Widget focused;
    private double focusedOffsetY;
    private Widget captured;
    private double capturedOffsetY;
    private Widget popover;
    /** Vertical scroll offset accumulated while dispatching an event through scroll panels. */
    public double offsetY;

    public void begin(GuiGraphics g) {
        this.g = g;
        this.font = mc.font;
        this.theme = Erebus.theme();
        long now = System.nanoTime();
        dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
        lastFrame = now;
    }

    // ---------------------------------------------------------------- focus & capture

    public void focus(Widget w) {
        if (focused == w) return;
        Widget old = focused;
        focused = w;
        focusedOffsetY = offsetY;
        if (old != null) old.onFocusLost(this);
    }

    /** Scroll offset of the focused widget's container at the time it was focused. */
    public double focusedOffsetY() {
        return focusedOffsetY;
    }

    public boolean isFocused(Widget w) {
        return focused == w;
    }

    public Widget focused() {
        return focused;
    }

    public void capture(Widget w) {
        captured = w;
        capturedOffsetY = offsetY;
    }

    public Widget captured() {
        return captured;
    }

    public double capturedOffsetY() {
        return capturedOffsetY;
    }

    public void release() {
        captured = null;
    }

    public void openPopover(Widget w) {
        popover = w;
    }

    public void closePopover() {
        if (popover != null && focused != null) focus(null);
        popover = null;
    }

    public Widget popover() {
        return popover;
    }

    private boolean swallowChar;

    /**
     * Drops the character event that follows the current key press. Used when a key was consumed
     * as a keybind, so it doesn't also get typed into a text field.
     */
    public void swallowNextChar() {
        swallowChar = true;
    }

    /** Returns true once if the next character should be dropped, and resets the flag. */
    public boolean takeSwallowChar() {
        boolean s = swallowChar;
        swallowChar = false;
        return s;
    }

    public void cursor(CursorType type) {
        g.requestCursor(type);
    }

    // ---------------------------------------------------------------- text

    public int width(String s) {
        return Text.width(s, false);
    }

    public int widthBold(String s) {
        return Text.width(s, true);
    }

    /**
     * Opacity everything drawn through this Ui is multiplied by (fading a menu in and out, a page crossfading, rows
     * cascading in). Set it around what should fade and put it back after.
     */
    public float alpha = 1f;

    /** A colour with the current {@link #alpha} applied. */
    public int fade(int color) {
        return alpha >= 0.999f ? color : dev.erebus.client.util.Colors.mulAlpha(color, Math.max(0f, alpha));
    }

    /** Too faded to bother drawing text (and fonts draw near-zero alpha as opaque). */
    private boolean invisible(int color) {
        return alpha < 0.999f && ((fade(color) >>> 24) & 0xFF) < 6;
    }

    /** Draws text; returns the drawn width. */
    public int text(String s, int x, int y, int color) {
        if (invisible(color)) return width(s);
        return Text.draw(g, s, x, y, fade(color), false, theme.textShadow);
    }

    public int textBold(String s, int x, int y, int color) {
        if (invisible(color)) return widthBold(s);
        return Text.draw(g, s, x, y, fade(color), true, theme.textShadow);
    }

    /** Text with a drop shadow regardless of the theme setting (for text drawn over the game world). */
    public int textShadowed(String s, int x, int y, int color, boolean bold) {
        if (invisible(color)) return Text.width(s, bold);
        return Text.draw(g, s, x, y, fade(color), bold, true);
    }

    public void textRight(String s, int right, int y, int color) {
        text(s, right - width(s), y, color);
    }

    public void textCentered(String s, int cx, int y, int color) {
        text(s, cx - width(s) / 2, y, color);
    }

    /** Draws text with each character colored along a gradient; kerning is kept by measuring prefixes. */
    public int gradientText(String s, int x, int y, int from, int to, boolean bold) {
        int n = Math.max(1, s.length() - 1);
        for (int i = 0; i < s.length(); i++) {
            int cx = x + Text.width(s.substring(0, i), bold);
            int color = dev.erebus.client.util.Colors.lerp(from, to, i / (float) n);
            if (!invisible(color)) Text.draw(g, String.valueOf(s.charAt(i)), cx, y, fade(color), bold, theme.textShadow);
        }
        return Text.width(s, bold);
    }

    /** Draws an icon whose box is {@code size} units tall with its top-left at (x, y). */
    public void icon(Icon icon, float x, float y, float size, int color) {
        if (invisible(color)) return;
        Text.icon(g, icon, x, y, size, fade(color));
    }

    /** A world label: a dark pill with an optional colour icon, centred on (cx, y). */
    /** Size of the tags the module being drawn right now asks for (its own Tag size), set around each module's overlay. */
    public float tagModuleScale = 1f;

    /**
     * A label floating in the world, centred at {@code cx} with its top at {@code y} (screen units, from
     * {@link dev.erebus.client.render.Projection#toScreen}). Size, opacity, shrinking with distance, fading near the
     * crosshair and the detail part follow the World tags settings on the Client page and the module's own Tag size.
     */
    public void worldLabel(Icon icon, String text, String detail, int color, float cx, float y) {
        dev.erebus.client.ClientSettings cs = dev.erebus.client.Erebus.client();
        float scale = cs.tagSize.floatValue() * tagModuleScale;
        if (cs.tagShrink.get()) {
            float d = dev.erebus.client.render.Projection.lastDistance();
            scale *= Math.max(0.6f, 1f - Math.max(0f, d - 24f) / 340f);
        }
        float alpha = (float) (cs.tagOpacity.get() / 100.0);
        // the label is drawn around its bottom centre (where it points at), so it shrinks towards the thing it's over
        float ax = cx, ay = y + 14;
        float sw = g.guiWidth() / 2f, sh = g.guiHeight() / 2f;
        float near = (float) Math.hypot(ax - sw, ay - 7 * scale - sh);
        if (cs.tagDetails.is("Never") || cs.tagDetails.is("Near crosshair") && near > 90) detail = null;
        int tw = width(text) + (detail == null ? 0 : width(detail) + 6);
        int w = tw + (icon != null ? 18 : 12);
        if (cs.tagCrosshairFade.get()) {
            float reach = Math.max(w * scale / 2f, 7 * scale) + 10;
            if (near < reach) alpha *= 0.25f + 0.75f * near / reach;
        }
        if (alpha <= 0.02f) return;
        g.pose().pushMatrix();
        g.pose().translate(ax, ay);
        g.pose().scale(scale, scale);
        int x = Math.round(-w / 2f), yy = -14;
        round(x, yy, w, 14, 7, dev.erebus.client.util.Colors.mulAlpha(0xB8101016, alpha));
        border(x, yy, w, 14, 7, dev.erebus.client.util.Colors.mulAlpha(dev.erebus.client.util.Colors.withAlpha(color, 0x90), alpha));
        int tx = x + 6;
        if (icon != null) {
            icon(icon, tx, yy + 3, 8, dev.erebus.client.util.Colors.mulAlpha(color, alpha));
            tx += 12;
        }
        tx += text(text, tx, yy + 3, dev.erebus.client.util.Colors.mulAlpha(theme.text(), alpha));
        if (detail != null) text(detail, tx + 6, yy + 3, dev.erebus.client.util.Colors.mulAlpha(theme.text(), 0.6f * alpha));
        g.pose().popMatrix();
    }

    /** Y offset that vertically centers a line of text in a box of height {@code h}. */
    public static int centerY(int y, int h) {
        return y + (h - 8) / 2;
    }

    public String ellipsize(String s, int maxWidth) {
        return Text.ellipsize(s, maxWidth, false);
    }

    public List<String> wrap(String s, int width) {
        return Text.wrap(s, Math.max(10, width));
    }

    // ---------------------------------------------------------------- tooltips

    public int mouseX;
    public int mouseY;
    private Object hoverKey;
    private long hoverSince;
    private String tooltip;

    /** Shows {@code text} near the mouse after a short hover over the widget identified by {@code key}. */
    public void tooltip(Object key, String text) {
        if (text == null || text.isBlank()) return;
        long now = System.currentTimeMillis();
        if (key != hoverKey) {
            hoverKey = key;
            hoverSince = now;
        }
        hoverSeenThisFrame = true;
        if (now - hoverSince >= 450) tooltip = text;
    }

    private boolean hoverSeenThisFrame;

    /** Takes the tooltip requested this frame (or null) and resets hover tracking for the next one. */
    public String takeTooltip() {
        if (!hoverSeenThisFrame) hoverKey = null;
        hoverSeenThisFrame = false;
        String t = tooltip;
        tooltip = null;
        return t;
    }

    // ---------------------------------------------------------------- shapes

    public void rect(int x, int y, int w, int h, int color) {
        Render2D.rect(g, x, y, w, h, fade(color));
    }

    public void round(int x, int y, int w, int h, int r, int color) {
        Render2D.roundRect(g, x, y, w, h, r, fade(color));
    }

    public void border(int x, int y, int w, int h, int r, int color) {
        Render2D.roundRectBorder(g, x, y, w, h, r, fade(color));
    }

    // ---------------------------------------------------------------- animation state

    private final java.util.Map<String, dev.erebus.client.util.Spring> springs = new java.util.HashMap<>();

    /**
     * A spring that outlives the widgets (screens rebuild their widgets on many clicks), for things that glide from
     * where they were: a selection highlight moving to the row you picked.
     */
    public dev.erebus.client.util.Spring spring(String key, float initial) {
        return springs.computeIfAbsent(key, k -> new dev.erebus.client.util.Spring(initial, 320f, 28f));
    }

    /** Theme radius scaled for small elements (buttons, inputs). */
    public int smallRadius() {
        return Math.max(0, Math.round(theme.radius * 0.6f));
    }
}
