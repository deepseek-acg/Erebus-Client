package dev.erebus.client.hud.elements;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.hud.HudElement;
import dev.erebus.client.notify.Notifications;
import dev.erebus.client.render.Render2D;
import dev.erebus.client.setting.NumberSetting;
import dev.erebus.client.theme.Theme;

import java.util.ArrayList;
import java.util.List;

/** Toasts for module toggles and everything the client has to tell you (finds, alerts, seeds). */
public final class NotificationsElement extends HudElement {
    private static final int TOAST_H = 18;
    private static final int LINE_H = 10;
    private static final int GAP = 3;

    private final NumberSetting duration = add(new NumberSetting("Duration", "How long each toast stays. Longer messages stay a "
            + "little longer.", 2.5, 1, 8, 0.5).unit("s").onChange(v -> Notifications.setDuration(Math.round(v * 1000))));
    private final NumberSetting maxWidth = add(new NumberSetting("Message width", "How wide a message can get before it wraps onto "
            + "more lines.", 240, 140, 400, 10).unit("px"));
    private final NumberSetting maxLines = add(new NumberSetting("Message lines", "How many lines of a long message are shown; the "
            + "whole message is on the dashboard's Messages page.", 4, 1, 10, 1));

    private final List<Notifications.Entry> entries = new ArrayList<>();
    private final List<Layout> layouts = new ArrayList<>();
    private int widest;

    /** One toast as it's drawn: its size and its lines. */
    private record Layout(int w, int h, List<String> lines) {
    }

    public NotificationsElement() {
        super("notifications", "Notifications", "Toasts when modules toggle, and every message the client has for you: finds, "
                + "alerts, seeds. They're also kept on the dashboard's Messages page.", true, HAnchor.RIGHT, VAnchor.BOTTOM, 4, 84);
    }

    public void syncDuration() {
        Notifications.setDuration(Math.round(duration.get() * 1000));
    }

    @Override
    protected int[] measure(Ui ui, boolean preview) {
        entries.clear();
        layouts.clear();
        entries.addAll(Notifications.active());
        widest = 0;
        if (entries.isEmpty()) {
            if (!preview) return new int[]{0, 0};
            Layout l = layout(ui, "Crystal Aura", "enabled", null);
            layouts.add(l);
            widest = l.w;
            return new int[]{l.w, l.h};
        }
        int total = 0;
        for (Notifications.Entry e : entries) {
            Layout l = layout(ui, e.title, e.detail, e.body);
            layouts.add(l);
            widest = Math.max(widest, l.w);
            total += l.h + GAP;
        }
        return new int[]{widest, total - GAP};
    }

    private Layout layout(Ui ui, String title, String detail, String body) {
        if (body == null) return new Layout(Math.max(90, ui.width(title) + ui.width(" " + (detail == null ? "" : detail)) + 18), TOAST_H, List.of());
        int max = maxWidth.intValue();
        List<String> lines = new ArrayList<>(ui.wrap(body, max - 18));
        int n = maxLines.intValue();
        if (lines.size() > n) {
            List<String> cut = new ArrayList<>(lines.subList(0, n));
            cut.set(n - 1, ui.ellipsize(cut.get(n - 1) + " …", max - 18));
            lines = cut;
        }
        int w = ui.width(title) + 18;
        for (String l : lines) w = Math.max(w, ui.width(l) + 18);
        return new Layout(Math.max(110, Math.min(max, w)), TOAST_H + lines.size() * LINE_H - 2, lines);
    }

    @Override
    protected void draw(Ui ui, int x, int y, boolean preview) {
        if (entries.isEmpty()) {
            Layout l = layouts.get(0);
            toast(ui, x, y, l, "Crystal Aura", "enabled", Notifications.Kind.ENABLED, 0.6f);
            return;
        }
        boolean up = growUp();
        int offset = 0;
        for (int i = 0; i < entries.size(); i++) {
            Notifications.Entry e = entries.get(i);
            Layout l = layouts.get(i);
            e.slide.update(ui.dt);
            if (e.y.get() < 0) e.y.snap(offset);
            e.y.target(offset);
            int s = Math.round(e.y.update(ui.dt));
            int ty = up ? y + height - l.h - s : y + s;
            float slide = e.slide.get();
            int shift = Math.round((1f - slide) * (l.w + 8));
            int tx = alignRight() ? x + width - l.w + shift : x - shift;
            float before = ui.alpha;
            ui.alpha *= Math.max(0f, Math.min(1f, slide * 1.4f));
            toast(ui, tx, ty, l, e.title, e.detail, e.kind, e.remaining());
            ui.alpha = before;
            offset += l.h + GAP;
        }
    }

    private void toast(Ui ui, int x, int y, Layout l, String title, String detail, Notifications.Kind kind, float left) {
        int r = ui.smallRadius();
        int accent = switch (kind) {
            case ENABLED, SUCCESS -> Theme.GOOD;
            case DISABLED -> Theme.BAD;
            case WARNING -> Theme.WARN;
            default -> ui.theme.accent();
        };
        ui.round(x, y, l.w, l.h, r, ui.theme.background());
        ui.border(x, y, l.w, l.h, r, ui.theme.border());
        ui.rect(x, y + 3, 2, l.h - 6, accent);
        // how long it has left, as a thin line along the bottom
        int bar = Math.round((l.w - 2 * r - 4) * left);
        if (bar > 0) ui.rect(x + r + 2, y + l.h - 2, bar, 1, dev.erebus.client.util.Colors.withAlpha(accent, 0x70));
        int tx = x + 8;
        int top = Ui.centerY(y, TOAST_H);
        int w = ui.text(title, tx, top, l.lines.isEmpty() ? ui.theme.text() : accent);
        if (detail != null) {
            ui.text(" " + detail, tx + w, top, kind == Notifications.Kind.ENABLED || kind == Notifications.Kind.DISABLED ? accent : ui.theme.dim());
        }
        int ly = top + LINE_H + 1;
        for (String line : l.lines) {
            ui.text(line, tx, ly, ui.theme.text());
            ly += LINE_H;
        }
    }
}
