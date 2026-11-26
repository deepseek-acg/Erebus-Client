package dev.erebus.client.gui.widget;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.erebus.client.gui.Ui;
import dev.erebus.client.setting.KeybindSetting;
import dev.erebus.client.util.Colors;
import org.lwjgl.glfw.GLFW;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Click, then press a key to bind it. Escape cancels; Backspace or Delete unbinds. */
public final class KeybindButton extends Widget {
    private final IntSupplier getter;
    private final IntConsumer setter;

    public KeybindButton(int x, int y, int w, int h, IntSupplier getter, IntConsumer setter) {
        bounds(x, y, w, h);
        this.getter = getter;
        this.setter = setter;
    }

    @Override
    public void render(Ui ui, int mx, int my) {
        boolean listening = ui.isFocused(this);
        boolean hot = contains(mx, my);
        if (hot) ui.cursor(CursorTypes.POINTING_HAND);
        int bg = listening ? ui.theme.accent() : hot ? Colors.withAlpha(ui.theme.accent(), 0x40) : ui.theme.track();
        ui.round(x, y, width, height, ui.smallRadius(), bg);
        String label = listening ? "Press a key..." : KeybindSetting.keyName(getter.getAsInt());
        ui.textCentered(ui.ellipsize(label, width - 8), x + width / 2, Ui.centerY(y, height),
                listening ? ui.theme.onAccent() : ui.theme.text());
    }

    @Override
    public boolean mouseClicked(Ui ui, double mx, double my, int button) {
        if (!contains(mx, my)) return false;
        if (button == 1) {
            setter.accept(KeybindSetting.NONE);
            ui.focus(null);
            return true;
        }
        ui.focus(ui.isFocused(this) ? null : this);
        return true;
    }

    @Override
    public boolean keyPressed(Ui ui, int key, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            ui.focus(null);
        } else if (key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE) {
            setter.accept(KeybindSetting.NONE);
            ui.focus(null);
        } else {
            setter.accept(key);
            ui.focus(null);
        }
        return true;
    }
}
