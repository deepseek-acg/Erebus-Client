package dev.erebus.client.hud.elements;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.hud.HudElement;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.theme.Theme;
import dev.erebus.client.util.Colors;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Your potion effects with their level and time left, each with a bar running down; one about to run out blinks.
 * Good effects are listed first.
 */
public final class EffectsElement extends HudElement {
    private static final int ROW = 13;
    private static final String[] ROMAN = {"", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X"};

    private final BoolSetting bars = add(new BoolSetting("Bars", "A bar under each effect running down as it wears off.", true));
    private final BoolSetting background = add(new BoolSetting("Background", true));
    /** The longest each effect has had since it started, for its bar. */
    private final Map<Holder<MobEffect>, Integer> longest = new HashMap<>();
    private final List<Row> rows = new ArrayList<>();

    private record Row(String name, String time, int color, float left, boolean harmful, boolean blink) {
    }

    public EffectsElement() {
        super("effects", "Effects", "Potion effects with their level and time left, each with a bar running down.", false,
                HAnchor.RIGHT, VAnchor.CENTER, 4, 0);
    }

    @Override
    protected int[] measure(Ui ui, boolean preview) {
        rows.clear();
        LocalPlayer p = ui.mc.player;
        if (p == null || p.getActiveEffects().isEmpty()) {
            if (!preview) {
                longest.clear();
                return new int[]{0, 0};
            }
            rows.add(new Row("Strength II", "2:41", 0xFFFFC700, 0.9f, false, false));
            rows.add(new Row("Fire Resistance", "7:12", 0xFFFF9900, 0.6f, false, false));
            rows.add(new Row("Weakness", "0:08", 0xFF484D48, 0.1f, true, true));
        } else {
            longest.keySet().removeIf(h -> !p.hasEffect(h));
            List<MobEffectInstance> list = new ArrayList<>(p.getActiveEffects());
            list.sort((a, b) -> {
                boolean ha = a.getEffect().value().getCategory() == MobEffectCategory.HARMFUL;
                boolean hb = b.getEffect().value().getCategory() == MobEffectCategory.HARMFUL;
                return ha != hb ? (ha ? 1 : -1) : Integer.compare(b.getDuration(), a.getDuration());
            });
            for (MobEffectInstance e : list) {
                Holder<MobEffect> h = e.getEffect();
                int d = e.getDuration();
                int max = longest.merge(h, d, Math::max);
                String name = h.value().getDisplayName().getString() + ROMAN[Math.min(ROMAN.length - 1, e.getAmplifier())];
                boolean infinite = e.isInfiniteDuration();
                String time = infinite ? "∞" : d >= 72000 ? (d / 72000) + "h" : (d / 1200) + ":" + String.format("%02d", (d / 20) % 60);
                boolean harmful = h.value().getCategory() == MobEffectCategory.HARMFUL;
                rows.add(new Row(name, time, 0xFF000000 | h.value().getColor(), infinite ? 1f : d / (float) Math.max(1, max),
                        harmful, !infinite && d < 200));
            }
        }
        int w = 0;
        for (Row r : rows) w = Math.max(w, ui.width(r.name) + ui.width(r.time) + 22);
        return new int[]{Math.max(90, w), rows.size() * ROW + 4};
    }

    @Override
    protected void draw(Ui ui, int x, int y, boolean preview) {
        if (background.get()) ui.round(x, y, width, height, ui.smallRadius(), ui.theme.hudBackground());
        float before = ui.alpha;
        long now = System.currentTimeMillis();
        int ry = y + 3;
        for (Row r : rows) {
            if (r.blink) ui.alpha = before * (0.55f + 0.45f * (float) Math.abs(Math.sin(now / 160.0)));
            ui.round(x + 4, ry + 2, 5, 5, 2, Colors.withAlpha(r.color, 0xFF));
            ui.text(r.name, x + 13, ry, r.harmful ? Colors.lerp(ui.theme.text(), Theme.BAD, 0.45f) : ui.theme.text());
            ui.textRight(r.time, x + width - 4, ry, r.blink ? Theme.BAD : ui.theme.dim());
            if (bars.get()) {
                int bw = width - 17;
                ui.rect(x + 13, ry + 9, bw, 1, ui.theme.track());
                ui.rect(x + 13, ry + 9, Math.round(bw * Math.max(0f, Math.min(1f, r.left))), 1, Colors.withAlpha(r.color, 0xC0));
            }
            ui.alpha = before;
            ry += ROW;
        }
    }
}
