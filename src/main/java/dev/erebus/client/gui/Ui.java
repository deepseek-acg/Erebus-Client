package dev.erebus.client.gui;

import com.mojang.blaze3d.platform.cursor.CursorType;
import dev.erebus.client.Erebus;
import dev.erebus.client.gui.widget.Widget;
import dev.erebus.client.render.Render2D;
import dev.erebus.client.theme.Theme;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

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
        return font.width(s);
    }

    public int widthBold(String s) {
        return font.width(Component.literal(s).withStyle(ChatFormatting.BOLD));
    }

    /** Draws text; returns the drawn width. */
    public int text(String s, int x, int y, int color) {
        g.drawString(font, s, x, y, color, theme.textShadow);
        return font.width(s);
    }

    public int textBold(String s, int x, int y, int color) {
        Component c = Component.literal(s).withStyle(ChatFormatting.BOLD);
        g.drawString(font, c, x, y, color, theme.textShadow);
        return font.width(c);
    }

    public void textRight(String s, int right, int y, int color) {
        text(s, right - width(s), y, color);
    }

    public void textCentered(String s, int cx, int y, int color) {
        text(s, cx - width(s) / 2, y, color);
    }

    /** Draws text with each character colored along the accent gradient. */
    public int gradientText(String s, int x, int y, int from, int to, boolean bold) {
        int cx = x;
        int n = Math.max(1, s.length() - 1);
        for (int i = 0; i < s.length(); i++) {
            String ch = String.valueOf(s.charAt(i));
            int color = dev.erebus.client.util.Colors.lerp(from, to, i / (float) n);
            cx += bold ? textBold(ch, cx, y, color) : text(ch, cx, y, color);
        }
        return cx - x;
    }

    /** Y offset that vertically centers a line of text in a box of height {@code h}. */
    public static int centerY(int y, int h) {
        return y + (h - 8) / 2;
    }

    public String ellipsize(String s, int maxWidth) {
        if (width(s) <= maxWidth) return s;
        String dots = "...";
        return font.plainSubstrByWidth(s, Math.max(0, maxWidth - width(dots))) + dots;
    }

    public List<FormattedCharSequence> wrap(String s, int width) {
        return font.split(Component.literal(s), Math.max(10, width));
    }

    // ---------------------------------------------------------------- shapes

    public void rect(int x, int y, int w, int h, int color) {
        Render2D.rect(g, x, y, w, h, color);
    }

    public void round(int x, int y, int w, int h, int r, int color) {
        Render2D.roundRect(g, x, y, w, h, r, color);
    }

    public void border(int x, int y, int w, int h, int r, int color) {
        Render2D.roundRectBorder(g, x, y, w, h, r, color);
    }

    /** Theme radius scaled for small elements (buttons, inputs). */
    public int smallRadius() {
        return Math.max(0, Math.round(theme.radius * 0.6f));
    }
}
