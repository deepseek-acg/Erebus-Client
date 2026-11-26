package dev.erebus.client.hud.elements;

import dev.erebus.client.Erebus;
import dev.erebus.client.gui.Ui;
import dev.erebus.client.hud.HudElement;
import dev.erebus.client.module.Module;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.ModeSetting;
import dev.erebus.client.util.Colors;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Enabled modules, sorted by width, with an animated gradient. */
public final class ModuleListElement extends HudElement {
    private static final int LINE_H = 11;

    private final ModeSetting color = add(new ModeSetting("Color", "How lines are colored.", "Gradient", "Gradient", "Accent", "Rainbow"));
    private final ModeSetting sort = add(ModeSetting.of("Sort", "Width", "Width", "Alphabetical"));
    private final BoolSetting suffixes = add(new BoolSetting("Suffixes", "Show each module's mode next to its name.", true));
    private final BoolSetting background = add(new BoolSetting("Background", true));
    private final BoolSetting sideBar = add(new BoolSetting("Side bar", "Colored bar on the outer edge of each line.", true));
    private final BoolSetting animate = add(new BoolSetting("Animate", "Move the gradient over time.", true));

    private record Entry(String name, String suffix) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<Integer> widths = new ArrayList<>();

    public ModuleListElement() {
        super("modules", "Module list", "Enabled modules, sorted so the list forms a clean edge.", true,
                HAnchor.RIGHT, VAnchor.TOP, 2, 2);
    }

    @Override
    protected int[] measure(Ui ui, boolean preview) {
        entries.clear();
        widths.clear();
        for (Module m : Erebus.modules().enabled()) entries.add(new Entry(m.name, suffixes.get() ? m.suffix() : null));
        if (entries.isEmpty() && preview) {
            entries.add(new Entry("AutoCrystal", suffixes.get() ? "Damage" : null));
            entries.add(new Entry("AutoTotem", null));
            entries.add(new Entry("Surround", suffixes.get() ? "Teleport" : null));
            entries.add(new Entry("ElytraFly", suffixes.get() ? "Control" : null));
            entries.add(new Entry("ESP", suffixes.get() ? "Box" : null));
            entries.add(new Entry("Sprint", null));
        }
        if (entries.isEmpty()) return new int[]{0, 0};
        if (sort.is("Width")) entries.sort(Comparator.comparingInt((Entry e) -> textWidth(ui, e)).reversed());
        else entries.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        if (growUp()) java.util.Collections.reverse(entries);
        int w = 0;
        for (Entry e : entries) {
            int tw = textWidth(ui, e) + 6 + (sideBar.get() ? 2 : 0);
            widths.add(tw);
            w = Math.max(w, tw);
        }
        return new int[]{w, entries.size() * LINE_H};
    }

    private int textWidth(Ui ui, Entry e) {
        return ui.width(e.name) + (e.suffix != null ? ui.width(" " + e.suffix) : 0);
    }

    private int lineColor(Ui ui, int index) {
        float time = animate.get() ? (System.currentTimeMillis() % 100_000L) / 1000f : 0f;
        return switch (color.get()) {
            case "Accent" -> ui.theme.accent();
            case "Rainbow" -> Colors.fromHsv(time * 0.12f + index * 0.06f, 0.55f, 1f, 255);
            default -> {
                float t = (index * 0.09f + time * 0.25f) % 2f;
                if (t > 1f) t = 2f - t;
                yield ui.theme.gradient(t);
            }
        };
    }

    @Override
    protected void draw(Ui ui, int x, int y, boolean preview) {
        boolean right = alignRight();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            int lw = widths.get(i);
            int lx = right ? x + width - lw : x;
            int ly = y + i * LINE_H;
            int c = lineColor(ui, i);
            if (background.get()) ui.rect(lx, ly, lw, LINE_H, ui.theme.hudBackground());
            int bar = sideBar.get() ? 2 : 0;
            if (bar > 0) ui.rect(right ? lx + lw - bar : lx, ly, bar, LINE_H, c);
            int tx = right ? lx + 3 : lx + bar + 3;
            tx += ui.text(e.name, tx, ly + 2, c);
            if (e.suffix != null) ui.text(" " + e.suffix, tx, ly + 2, ui.theme.dim());
        }
    }
}
