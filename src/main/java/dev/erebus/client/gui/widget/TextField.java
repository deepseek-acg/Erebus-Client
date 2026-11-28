package dev.erebus.client.gui.widget;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.erebus.client.gui.Ui;
import dev.erebus.client.util.Colors;
import net.minecraft.client.input.InputQuirks;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;
import java.util.function.Predicate;

/** Single-line text input with caret, select-all and clipboard support. */
public final class TextField extends Widget {
    private String text;
    private int cursor;
    private boolean allSelected;
    private int scrollX;
    private final String placeholder;
    private final int maxLength;
    private Consumer<String> onChange = s -> { };
    private Consumer<String> onSubmit = s -> { };
    private Predicate<Character> filter = c -> true;
    private long focusTime;
    private boolean drawBackground = true;
    private dev.erebus.client.render.font.Icon icon;

    public TextField(int x, int y, int w, int h, String initial, String placeholder, int maxLength) {
        bounds(x, y, w, h);
        this.text = initial == null ? "" : initial;
        this.cursor = text.length();
        this.placeholder = placeholder;
        this.maxLength = maxLength;
    }

    public TextField onChange(Consumer<String> c) {
        this.onChange = c;
        return this;
    }

    public TextField onSubmit(Consumer<String> c) {
        this.onSubmit = c;
        return this;
    }

    public TextField filter(Predicate<Character> f) {
        this.filter = f;
        return this;
    }

    /** An icon drawn at the left edge, with the text starting after it. */
    public TextField icon(dev.erebus.client.render.font.Icon icon) {
        this.icon = icon;
        return this;
    }

    private int padLeft() {
        return (drawBackground ? 6 : 0) + (icon != null ? 13 : 0);
    }

    public TextField noBackground() {
        this.drawBackground = false;
        return this;
    }

    public String text() {
        return text;
    }

    /** Replaces the text without firing change listeners. */
    public void setText(String s) {
        text = s == null ? "" : s;
        cursor = Math.min(cursor, text.length());
        allSelected = false;
    }

    @Override
    public void render(Ui ui, int mx, int my) {
        boolean focused = ui.isFocused(this);
        if (contains(mx, my)) ui.cursor(CursorTypes.IBEAM);
        if (drawBackground) {
            int r = ui.smallRadius();
            ui.round(x, y, width, height, r, ui.theme.inset());
            ui.border(x, y, width, height, r, focused ? ui.theme.accent() : ui.theme.border());
        }
        int pad = drawBackground ? 6 : 0;
        int left = padLeft();
        int innerW = width - pad - left;
        int ty = Ui.centerY(y, height);
        if (icon != null) ui.icon(icon, x + pad, y + (height - 8) / 2f, 8, focused ? ui.theme.accent() : ui.theme.dim());

        int cursorPx = ui.width(text.substring(0, cursor));
        if (cursorPx - scrollX > innerW - 2) scrollX = cursorPx - innerW + 2;
        if (cursorPx - scrollX < 0) scrollX = cursorPx;
        scrollX = Math.max(0, Math.min(scrollX, Math.max(0, ui.width(text) - innerW + 2)));

        ui.g.enableScissor(x + left, y, x + width - pad + 1, y + height);
        if (text.isEmpty() && !focused) {
            ui.text(placeholder, x + left, ty, Colors.mulAlpha(ui.theme.dim(), 0.85f));
        } else {
            if (focused && allSelected && !text.isEmpty()) {
                ui.rect(x + left - scrollX, ty - 1, ui.width(text), 10, Colors.withAlpha(ui.theme.accent(), 0x66));
            }
            // while not being edited, text that doesn't fit ends in "…" rather than being cut off mid-letter
            String shown = focused ? text : ui.ellipsize(text, innerW - 2);
            ui.text(shown, x + left - (focused ? scrollX : 0), ty, ui.theme.text());
            if (text.isEmpty()) ui.text(placeholder, x + left, ty, Colors.mulAlpha(ui.theme.dim(), 0.6f));
        }
        if (focused && ((System.currentTimeMillis() - focusTime) / 530) % 2 == 0) {
            ui.rect(x + left + cursorPx - scrollX, ty - 1, 1, 10, ui.theme.accent());
        }
        ui.g.disableScissor();
    }

    @Override
    public boolean mouseClicked(Ui ui, double mx, double my, int button) {
        if (!contains(mx, my)) return false;
        if (button == 1) {
            change("");
        }
        ui.focus(this);
        focusTime = System.currentTimeMillis();
        allSelected = false;
        double local = mx - (x + padLeft()) + scrollX;
        int best = text.length();
        for (int i = 0; i <= text.length(); i++) {
            if (ui.width(text.substring(0, i)) > local) {
                best = Math.max(0, i - 1);
                break;
            }
        }
        cursor = best;
        return true;
    }

    @Override
    public boolean keyPressed(Ui ui, int key, int modifiers) {
        boolean ctrl = (modifiers & InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER) != 0;
        focusTime = System.currentTimeMillis();
        switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (allSelected) change("");
                else if (cursor > 0) {
                    int from = ctrl ? wordStart(cursor) : cursor - 1;
                    String next = text.substring(0, from) + text.substring(cursor);
                    cursor = from;
                    change(next);
                }
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (allSelected) change("");
                else if (cursor < text.length()) change(text.substring(0, cursor) + text.substring(cursor + 1));
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                cursor = allSelected ? 0 : Math.max(0, ctrl ? wordStart(cursor) : cursor - 1);
                allSelected = false;
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                cursor = allSelected ? text.length() : Math.min(text.length(), cursor + 1);
                allSelected = false;
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                cursor = 0;
                allSelected = false;
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                cursor = text.length();
                allSelected = false;
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                onSubmit.accept(text);
                ui.focus(null);
                return true;
            }
            case GLFW.GLFW_KEY_ESCAPE -> {
                ui.focus(null);
                return true;
            }
            default -> {
            }
        }
        if (ctrl) {
            switch (key) {
                case GLFW.GLFW_KEY_A -> {
                    allSelected = true;
                    cursor = text.length();
                    return true;
                }
                case GLFW.GLFW_KEY_C -> {
                    ui.mc.keyboardHandler.setClipboard(text);
                    return true;
                }
                case GLFW.GLFW_KEY_X -> {
                    ui.mc.keyboardHandler.setClipboard(text);
                    change("");
                    return true;
                }
                case GLFW.GLFW_KEY_V -> {
                    insert(ui.mc.keyboardHandler.getClipboard());
                    return true;
                }
                default -> {
                }
            }
        }
        return false;
    }

    @Override
    public boolean charTyped(Ui ui, String chars) {
        insert(chars);
        return true;
    }

    @Override
    public void onFocusLost(Ui ui) {
        allSelected = false;
    }

    private void insert(String s) {
        StringBuilder clean = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= 32 && c != 127 && filter.test(c)) clean.append(c);
        }
        if (clean.isEmpty()) return;
        String base = allSelected ? "" : text;
        int at = allSelected ? 0 : cursor;
        String next = base.substring(0, at) + clean + base.substring(at);
        if (next.length() > maxLength) return;
        cursor = at + clean.length();
        change(next);
    }

    private void change(String next) {
        allSelected = false;
        text = next;
        cursor = Math.min(cursor, text.length());
        onChange.accept(text);
    }

    private int wordStart(int from) {
        int i = from - 1;
        while (i > 0 && text.charAt(i - 1) == ' ') i--;
        while (i > 0 && text.charAt(i - 1) != ' ') i--;
        return Math.max(0, i);
    }
}
