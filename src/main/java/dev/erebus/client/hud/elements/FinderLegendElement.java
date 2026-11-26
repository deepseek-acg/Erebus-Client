package dev.erebus.client.hud.elements;

import dev.erebus.client.Erebus;
import dev.erebus.client.gui.Ui;
import dev.erebus.client.hud.HudElement;
import dev.erebus.client.module.basefinding.PlayerBlocks;
import dev.erebus.client.module.basefinding.StashESP;
import dev.erebus.client.setting.BoolSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Key to the basefinding colours, with how many of each are around. Shown while Stash ESP or Player Blocks is on. */
public final class FinderLegendElement extends HudElement {
    private record Row(String label, int color, int count) {
    }

    private final BoolSetting onlyFound = add(new BoolSetting("Only what's found", "List only the types that are around right now.", true));
    private final BoolSetting background = add(new BoolSetting("Background", true));
    private final List<Row> rows = new ArrayList<>();
    private static final int LINE = 11;

    public FinderLegendElement() {
        super("legend", "Finder legend", "Colour key for Stash ESP and Player Blocks, with counts.", true,
                HAnchor.RIGHT, VAnchor.CENTER, 4, 0);
    }

    @Override
    protected int[] measure(Ui ui, boolean preview) {
        rows.clear();
        StashESP esp = StashESP.get();
        PlayerBlocks pb = Erebus.modules().get(PlayerBlocks.class);
        boolean espOn = esp != null && esp.isEnabled(), pbOn = pb != null && pb.isEnabled();
        if (espOn) {
            Map<StashESP.Target, Integer> counts = esp.counts();
            for (StashESP.Target t : StashESP.Target.values()) {
                if (!esp.shows(t)) continue;
                int n = counts.getOrDefault(t, 0);
                if (n == 0 && onlyFound.get()) continue;
                rows.add(new Row(t.title, t.color, n));
            }
        }
        if (pbOn) {
            rows.add(new Row("Player-placed", PlayerBlocks.PLACED_COLOR, pb.placedCount()));
            if (pb.showsStructures()) rows.add(new Row("Natural structures", 0xFF9CA3AF, pb.structureCount()));
        }
        if (!espOn && !pbOn) {
            if (!preview) return new int[]{0, 0};
            rows.add(new Row("Chests", StashESP.Target.Chests.color, 42));
            rows.add(new Row("Shulker boxes", StashESP.Target.ShulkerBoxes.color, 7));
            rows.add(new Row("Ender chests", StashESP.Target.EnderChests.color, 3));
            rows.add(new Row("Player-placed", PlayerBlocks.PLACED_COLOR, 186));
        }
        if (rows.isEmpty()) rows.add(new Row("Nothing nearby", 0x00000000, -1));
        int w = ui.width("FINDER");
        for (Row r : rows) w = Math.max(w, 12 + ui.width(r.label) + 12 + (r.count >= 0 ? ui.width(String.valueOf(r.count)) : 0));
        int pad = background.get() ? 5 : 1;
        return new int[]{w + pad * 2, (rows.size() + 1) * LINE + pad * 2 - 1};
    }

    @Override
    protected void draw(Ui ui, int x, int y, boolean preview) {
        int pad = background.get() ? 5 : 1;
        if (background.get()) ui.round(x, y, width, height, ui.smallRadius(), ui.theme.hudBackground());
        int ly = y + pad;
        ui.textShadowed("FINDER", x + pad, ly, ui.theme.dim(), false);
        ly += LINE;
        for (Row r : rows) {
            if (r.count >= 0) ui.round(x + pad, ly + 2, 6, 6, 2, r.color);
            ui.textShadowed(r.label, x + pad + (r.count >= 0 ? 11 : 0), ly, r.count >= 0 ? ui.theme.text() : ui.theme.dim(), false);
            if (r.count >= 0) {
                String c = String.valueOf(r.count);
                ui.textShadowed(c, x + width - pad - ui.width(c), ly, r.count > 0 ? ui.theme.accent() : ui.theme.dim(), false);
            }
            ly += LINE;
        }
    }
}
