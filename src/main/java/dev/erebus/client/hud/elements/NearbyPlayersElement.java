package dev.erebus.client.hud.elements;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.hud.TextHudElement;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.NumberSetting;
import dev.erebus.client.theme.Theme;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class NearbyPlayersElement extends TextHudElement {
    private final NumberSetting max = add(new NumberSetting("Max players", "How many players to list.", 5, 1, 12, 1));
    private final BoolSetting health = add(new BoolSetting("Health", true));
    private final BoolSetting distance = add(new BoolSetting("Distance", true));

    public NearbyPlayersElement() {
        super("nearby", "Nearby players", "Players in render distance, closest first.", true,
                HAnchor.LEFT, VAnchor.CENTER, 4, 50, true);
    }

    private record Entry(String name, int dist, float hp) {
    }

    @Override
    protected void buildLines(Ui ui, List<Line> out, boolean preview) {
        List<Entry> entries = new ArrayList<>();
        LocalPlayer self = ui.mc.player;
        if (self != null && ui.mc.level != null) {
            for (AbstractClientPlayer p : ui.mc.level.players()) {
                if (p == self) continue;
                entries.add(new Entry(p.getName().getString(), Math.round(self.distanceTo(p)), p.getHealth() + p.getAbsorptionAmount()));
            }
            entries.sort(Comparator.comparingInt(Entry::dist));
        } else if (preview) {
            entries.add(new Entry("obby_hoarder", 42, 18f));
            entries.add(new Entry("voidwalker_", 67, 20f));
            entries.add(new Entry("quiet_miner", 118, 9.5f));
        }
        if (entries.isEmpty()) {
            if (preview) out.add(new Line().add("No players nearby", ui.theme.dim()));
            return;
        }
        int limit = Math.min(entries.size(), max.intValue());
        int nameW = 0;
        for (int i = 0; i < limit; i++) nameW = Math.max(nameW, ui.width(entries.get(i).name));
        out.add(new Line().add("NEARBY · " + entries.size(), ui.theme.dim()));
        for (int i = 0; i < limit; i++) {
            Entry e = entries.get(i);
            Line line = new Line().add(e.name, ui.theme.text());
            int gap = nameW - ui.width(e.name) + 6;
            line.add(" ".repeat(Math.max(1, gap / Math.max(1, ui.width(" ")))), 0);
            if (distance.get()) line.add(e.dist + "m ", ui.theme.dim());
            if (health.get()) {
                int c = e.hp >= 15 ? Theme.GOOD : e.hp >= 8 ? Theme.WARN : Theme.BAD;
                line.add(String.format(Locale.ROOT, "%.1f", e.hp), c);
            }
            out.add(line);
        }
    }
}
