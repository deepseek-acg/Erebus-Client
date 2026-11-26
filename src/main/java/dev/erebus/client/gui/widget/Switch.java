package dev.erebus.client.gui.widget;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.erebus.client.gui.Ui;
import dev.erebus.client.util.Anim;
import dev.erebus.client.util.Colors;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Animated on/off toggle. */
public final class Switch extends Widget {
    public static final int W = 22;
    public static final int H = 12;

    private final BooleanSupplier getter;
    private final Consumer<Boolean> setter;
    private final Anim knob;

    public Switch(int x, int y, BooleanSupplier getter, Consumer<Boolean> setter) {
        this(x, y, W, H, getter, setter);
    }

    public Switch(int x, int y, int w, int h, BooleanSupplier getter, Consumer<Boolean> setter) {
        bounds(x, y, w, h);
        this.getter = getter;
        this.setter = setter;
        this.knob = new Anim(getter.getAsBoolean() ? 1f : 0f, 22f);
    }

    @Override
    public void render(Ui ui, int mx, int my) {
        boolean on = getter.getAsBoolean();
        knob.target(on ? 1f : 0f);
        float k = knob.update(ui.dt);
        boolean hot = contains(mx, my);
        if (hot) ui.cursor(CursorTypes.POINTING_HAND);
        int track = Colors.lerp(ui.theme.track(), ui.theme.accent(), k);
        if (hot && !on) track = Colors.lerp(track, Colors.withAlpha(ui.theme.accent(), 0x55), 0.5f);
        ui.round(x, y, width, height, height / 2, track);
        int d = height - 4;
        int kx = x + 2 + Math.round((width - 4 - d) * k);
        int knobColor = Colors.lerp(ui.theme.dim(), ui.theme.onAccent(), k);
        ui.round(kx, y + 2, d, d, d / 2, knobColor);
    }

    @Override
    public boolean mouseClicked(Ui ui, double mx, double my, int button) {
        if (button != 0 || !contains(mx, my)) return false;
        setter.accept(!getter.getAsBoolean());
        return true;
    }
}
