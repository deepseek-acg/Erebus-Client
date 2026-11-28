package dev.erebus.client.hud.elements;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.hud.HudElement;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.ModeSetting;
import dev.erebus.client.setting.NumberSetting;
import dev.erebus.client.util.Colors;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Your speed over the last few seconds as a filled graph, with now, average and peak: how fast an elytra flight
 * really is, whether a highway is slowing you down, when the server rubber-bands you.
 */
public final class SpeedGraphElement extends HudElement {
    private static final int SAMPLES = 120;

    private final NumberSetting seconds = add(new NumberSetting("Length", "How many seconds the graph covers.", 8, 2, 30, 1).unit("s"));
    private final ModeSetting unit = add(ModeSetting.of("Unit", "Blocks/s", "Blocks/s", "km/h"));
    private final BoolSetting vertical = add(new BoolSetting("Include vertical", "Count up and down movement too (off: only "
            + "across the ground).", false));
    private final BoolSetting background = add(new BoolSetting("Background", true));

    private final float[] samples = new float[SAMPLES];
    private int head;
    private long lastSample;
    private Vec3 lastPos;

    public SpeedGraphElement() {
        super("speedgraph", "Speed graph", "Your speed over the last few seconds, with now, average and peak.", false,
                HAnchor.LEFT, VAnchor.BOTTOM, 4, 92);
    }

    @Override
    protected int[] measure(Ui ui, boolean preview) {
        if (ui.mc.player == null && !preview) return new int[]{0, 0};
        return new int[]{130, 46};
    }

    private void sample(LocalPlayer p) {
        long now = System.nanoTime();
        long every = (long) (seconds.get() * 1e9 / SAMPLES);
        if (lastPos == null) {
            lastPos = p.position();
            lastSample = now;
            return;
        }
        if (now - lastSample < every) return;
        double dt = (now - lastSample) / 1e9;
        Vec3 pos = p.position();
        double dx = pos.x - lastPos.x, dy = vertical.get() ? pos.y - lastPos.y : 0, dz = pos.z - lastPos.z;
        float v = (float) (Math.sqrt(dx * dx + dy * dy + dz * dz) / dt);
        if (v > 400) v = 0; // a teleport, not a speed
        samples[head] = v;
        head = (head + 1) % SAMPLES;
        lastPos = pos;
        lastSample = now;
    }

    @Override
    protected void draw(Ui ui, int x, int y, boolean preview) {
        LocalPlayer p = ui.mc.player;
        if (p != null) sample(p);
        else if (preview) for (int i = 0; i < SAMPLES; i++) samples[i] = (float) (18 + 10 * Math.sin(i / 9.0) + 4 * Math.sin(i / 3.1));
        if (background.get()) ui.round(x, y, width, height, ui.smallRadius(), ui.theme.hudBackground());
        float peak = 0, sum = 0;
        for (float s : samples) {
            peak = Math.max(peak, s);
            sum += s;
        }
        float now = samples[(head - 1 + SAMPLES) % SAMPLES];
        float scale = Math.max(4.3f, peak * 1.1f); // at least walking speed tall
        int gx = x + 4, gy = y + 14, gw = width - 8, gh = height - 18;
        ui.rect(gx, gy + gh, gw, 1, ui.theme.track());
        int accent = ui.theme.accent();
        for (int i = 0; i < gw; i++) {
            int idx = (head + (int) ((long) i * SAMPLES / gw)) % SAMPLES;
            float v = samples[idx];
            int h = Math.round(gh * Math.min(1f, v / scale));
            if (h <= 0) continue;
            ui.rect(gx + i, gy + gh - h, 1, h, Colors.withAlpha(accent, 0x38));
            ui.rect(gx + i, gy + gh - h, 1, 1, accent);
        }
        boolean kmh = unit.is("km/h");
        float f = kmh ? 3.6f : 1f;
        String u = kmh ? " km/h" : " b/s";
        ui.text(String.format(Locale.ROOT, "%.1f", now * f), x + 4, y + 3, ui.theme.text());
        ui.text(u.trim(), x + 6 + ui.width(String.format(Locale.ROOT, "%.1f", now * f)), y + 3, ui.theme.dim());
        String stats = String.format(Locale.ROOT, "avg %.1f  max %.1f", sum / SAMPLES * f, peak * f);
        ui.textRight(stats, x + width - 4, y + 3, ui.theme.dim());
    }
}
