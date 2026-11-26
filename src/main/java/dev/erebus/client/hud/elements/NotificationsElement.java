package dev.erebus.client.hud.elements;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.hud.HudElement;
import dev.erebus.client.notify.Notifications;
import dev.erebus.client.render.Render2D;
import dev.erebus.client.setting.NumberSetting;
import dev.erebus.client.theme.Theme;

import java.util.ArrayList;
import java.util.List;

/** Toasts for module toggles and client messages. */
public final class NotificationsElement extends HudElement {
    private static final int TOAST_H = 18;
    private static final int GAP = 3;

    private final NumberSetting duration = add(new NumberSetting("Duration", "How long each toast stays.", 2.5, 1, 8, 0.5)
            .unit("s").onChange(v -> Notifications.setDuration(Math.round(v * 1000))));

    private final List<Notifications.Entry> entries = new ArrayList<>();
    private int maxWidth;

    public NotificationsElement() {
        super("notifications", "Notifications", "Toasts when modules toggle or the client has something to tell you.", true,
                HAnchor.RIGHT, VAnchor.BOTTOM, 4, 84);
    }

    public void syncDuration() {
        Notifications.setDuration(Math.round(duration.get() * 1000));
    }

    @Override
    protected int[] measure(Ui ui, boolean preview) {
        entries.clear();
        entries.addAll(Notifications.active());
        maxWidth = 0;
        int count = entries.size();
        if (count == 0) {
            if (!preview) return new int[]{0, 0};
            maxWidth = toastWidth(ui, "Crystal Aura", "enabled");
            return new int[]{maxWidth, TOAST_H};
        }
        for (Notifications.Entry e : entries) maxWidth = Math.max(maxWidth, toastWidth(ui, e.title, e.detail));
        return new int[]{maxWidth, count * (TOAST_H + GAP) - GAP};
    }

    private int toastWidth(Ui ui, String title, String detail) {
        return Math.max(90, ui.width(title) + ui.width(" " + detail) + 18);
    }

    @Override
    protected void draw(Ui ui, int x, int y, boolean preview) {
        if (entries.isEmpty()) {
            toast(ui, x, y, maxWidth, "Crystal Aura", "enabled", Notifications.Kind.ENABLED, 1f);
            return;
        }
        boolean up = growUp();
        for (int i = 0; i < entries.size(); i++) {
            Notifications.Entry e = entries.get(i);
            e.slide.update(ui.dt);
            float slot = i;
            if (e.y.get() < 0) e.y.snap(slot);
            e.y.target(slot);
            float s = e.y.update(ui.dt);
            int w = toastWidth(ui, e.title, e.detail);
            int ty = up ? y + height - TOAST_H - Math.round(s * (TOAST_H + GAP)) : y + Math.round(s * (TOAST_H + GAP));
            float slide = e.slide.get();
            int shift = Math.round((1f - slide) * (w + 8));
            int tx = alignRight() ? x + width - w + shift : x - shift;
            toast(ui, tx, ty, w, e.title, e.detail, e.kind, slide);
        }
    }

    private void toast(Ui ui, int x, int y, int w, String title, String detail, Notifications.Kind kind, float alpha) {
        int r = ui.smallRadius();
        int accent = switch (kind) {
            case ENABLED, SUCCESS -> Theme.GOOD;
            case DISABLED -> Theme.BAD;
            case WARNING -> Theme.WARN;
            default -> ui.theme.accent();
        };
        ui.round(x, y, w, TOAST_H, r, ui.theme.background());
        Render2D.roundRectBorder(ui.g, x, y, w, TOAST_H, r, ui.theme.border());
        ui.rect(x, y + 3, 2, TOAST_H - 6, accent);
        int tx = x + 8;
        tx += ui.text(title, tx, Ui.centerY(y, TOAST_H), ui.theme.text());
        ui.text(" " + detail, tx, Ui.centerY(y, TOAST_H), kind == Notifications.Kind.ENABLED || kind == Notifications.Kind.DISABLED ? accent : ui.theme.dim());
    }
}
