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
import java.util.Map;

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

    /** One line as it's drawn: sliding in from the edge when a module turns on, out when it turns off, gliding up or down when the order changes. */
    private static final class Line {
        Entry entry;
        int width;
        int order;
        final dev.erebus.client.util.Spring in = new dev.erebus.client.util.Spring(0f, 260f, 26f);
        final dev.erebus.client.util.Spring y;

        Line(float y) {
            this.y = new dev.erebus.client.util.Spring(y, 300f, 30f);
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final java.util.Map<String, Line> lines = new java.util.LinkedHashMap<>();

    public ModuleListElement() {
        super("modules", "Module list", "Enabled modules, sorted so the list forms a clean edge. They slide in and out as you "
                + "turn them on and off.", true, HAnchor.RIGHT, VAnchor.TOP, 2, 2);
    }

    @Override
    protected int[] measure(Ui ui, boolean preview) {
        entries.clear();
        for (Module m : Erebus.modules().enabled()) entries.add(new Entry(m.name, suffixes.get() ? m.suffix() : null));
        if (entries.isEmpty() && preview) {
            entries.add(new Entry("Crystal Aura", suffixes.get() ? "Damage" : null));
            entries.add(new Entry("AutoTotem", null));
            entries.add(new Entry("Surround", suffixes.get() ? "Teleport" : null));
            entries.add(new Entry("ElytraFly", suffixes.get() ? "Control" : null));
            entries.add(new Entry("ESP", suffixes.get() ? "Box" : null));
            entries.add(new Entry("Sprint", null));
        }
        if (sort.is("Width")) entries.sort(Comparator.comparingInt((Entry e) -> textWidth(ui, e)).reversed());
        else entries.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        if (growUp()) java.util.Collections.reverse(entries);
        java.util.Set<String> now = new java.util.HashSet<>();
        int w = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            now.add(e.name);
            int fi = i;
            Line l = lines.computeIfAbsent(e.name, k -> new Line(fi * LINE_H));
            l.entry = e;
            l.order = i;
            l.width = textWidth(ui, e) + 6 + (sideBar.get() ? 2 : 0);
            l.in.target(1f);
            l.y.target(i * LINE_H);
            w = Math.max(w, l.width);
        }
        // the ones turned off slide out where they were
        lines.entrySet().removeIf(en -> !now.contains(en.getKey()) && en.getValue().in.target() == 0f && en.getValue().in.get() < 0.02f);
        for (Map.Entry<String, Line> en : lines.entrySet()) {
            if (!now.contains(en.getKey())) {
                en.getValue().in.target(0f);
                w = Math.max(w, en.getValue().width);
            }
        }
        if (lines.isEmpty()) return new int[]{0, 0};
        return new int[]{w, Math.max(1, entries.size()) * LINE_H};
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
        float before = ui.alpha;
        for (Line l : lines.values()) {
            Entry e = l.entry;
            float in = l.in.update(ui.dt);
            float ly = l.y.update(ui.dt);
            if (in <= 0.01f) continue;
            int lw = l.width;
            int shift = Math.round((1f - in) * (lw + 4));
            int lx = right ? x + width - lw + shift : x - shift;
            int yy = y + Math.round(ly);
            ui.alpha = before * Math.max(0f, Math.min(1f, in));
            int c = lineColor(ui, l.order);
            if (background.get()) ui.rect(lx, yy, lw, LINE_H, ui.theme.hudBackground());
            int bar = sideBar.get() ? 2 : 0;
            if (bar > 0) ui.rect(right ? lx + lw - bar : lx, yy, bar, LINE_H, c);
            int tx = right ? lx + 3 : lx + bar + 3;
            tx += ui.text(e.name, tx, yy + 2, c);
            if (e.suffix != null) ui.text(" " + e.suffix, tx, yy + 2, ui.theme.dim());
        }
        ui.alpha = before;
    }
}
