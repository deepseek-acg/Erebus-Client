package dev.erebus.client.gui.dashboard;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.gui.widget.Button;
import dev.erebus.client.gui.widget.Container;
import dev.erebus.client.gui.widget.Drawable;
import dev.erebus.client.gui.widget.ListRow;
import dev.erebus.client.gui.widget.ScrollPanel;
import dev.erebus.client.gui.widget.SettingsBuilder;
import dev.erebus.client.notify.Notifications;
import dev.erebus.client.render.font.Text;
import dev.erebus.client.theme.Theme;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything the client told you this session (finds, alerts, seeds), newest first, with their buttons (Copy seed...). */
public final class MessagesPage extends Page {
    private static final int LINE_H = 11;
    /** Which source is shown (null = all of them). */
    private String source = DashboardScreen.PICKED.get("messages");

    public MessagesPage(DashboardScreen screen) {
        super(screen);
    }

    @Override
    public String id() {
        return "messages";
    }

    @Override
    public String label() {
        return "Messages";
    }

    @Override
    public dev.erebus.client.render.font.Icon icon() {
        return dev.erebus.client.render.font.Icon.INFO;
    }

    @Override
    public String badge() {
        int n = Notifications.unread();
        return n > 0 ? String.valueOf(n) : null;
    }

    @Override
    public void build(Container root, int x, int y, int w, int h) {
        Notifications.markRead();
        List<Notifications.Entry> all = Notifications.history();
        Map<String, Integer> sources = new LinkedHashMap<>();
        for (Notifications.Entry e : all) sources.merge(e.title, 1, Integer::sum);
        if (source != null && !sources.containsKey(source)) source = null;
        if (source == null) DashboardScreen.PICKED.remove("messages");
        else DashboardScreen.PICKED.put("messages", source);

        int lx = x + 6;
        int lw = DashboardScreen.LIST - 12;
        root.add(new Button(lx, y + 6, lw, 20, "Clear all", Button.Style.SECONDARY, () -> {
            Notifications.clearHistory();
            source = null;
            screen.rebuild();
        }).icon(dev.erebus.client.render.font.Icon.TRASH));
        int ry = y + 32;
        ry = SettingsBuilder.section(root, "From", lx + 2, ry, lw - 4);
        ry = row(root, lx, ry, lw, "Everything", null, all.size());
        for (Map.Entry<String, Integer> s : sources.entrySet()) ry = row(root, lx, ry, lw, s.getKey(), s.getKey(), s.getValue());

        int paneX = x + DashboardScreen.LIST + 1;
        int paneW = w - DashboardScreen.LIST - 2;
        ScrollPanel pane = screen.listPanel(root, paneX, y + 1, paneW, h - 2).key("messages:" + source);
        int px = paneX + 12;
        int pw = pane.width - 24;
        int py = y + 12;
        py = screen.paneHeader(pane, px, py, pw, source == null ? "Messages" : source, () -> "This session", null, null);
        List<Notifications.Entry> shown = new ArrayList<>();
        for (Notifications.Entry e : all) if (source == null || source.equals(e.title)) shown.add(e);
        if (shown.isEmpty()) {
            screen.emptyState(pane, px, py, pw, h - (py - y) - 20, dev.erebus.client.render.font.Icon.INFO, "Nothing yet",
                    "Finds, alerts, seeds and everything else the modules have to tell you show up here, as well as in the "
                            + "notifications in the corner. None of it goes to chat.");
            return;
        }
        for (Notifications.Entry e : shown) py = card(pane, px, py, pw, e);
    }

    private int row(Container root, int x, int y, int w, String label, String key, int count) {
        root.add(new ListRow(x, y, w, 20, label, () -> java.util.Objects.equals(source, key), null, () -> {
            source = key;
            screen.rebuild();
        }, null).trailing(() -> String.valueOf(count)));
        return y + 21;
    }

    /** One message: when and from whom, its text, and its buttons. */
    private int card(Container into, int x, int y, int w, Notifications.Entry e) {
        List<String> lines = Text.wrap(e.body, w - 16);
        int textH = lines.size() * LINE_H;
        boolean buttons = true; // every message can at least be copied
        int h = 8 + 12 + textH + (buttons ? 24 : 4);
        into.add(new Drawable(x, y, w, h, (ui, self, mx, my) -> {
            int r = ui.smallRadius();
            int accent = switch (e.kind) {
                case SUCCESS, ENABLED -> Theme.GOOD;
                case WARNING -> Theme.WARN;
                case DISABLED -> Theme.BAD;
                default -> ui.theme.accent();
            };
            ui.round(self.x, self.y, self.width, self.height, r, ui.theme.track());
            ui.rect(self.x, self.y + 4, 2, self.height - 8, accent);
            int tx = self.x + 8;
            int ty = self.y + 6;
            ui.textBold(e.title, tx, ty, accent);
            String ago = ago(System.currentTimeMillis() - e.created);
            ui.text(ago, self.x + self.width - 8 - ui.width(ago), ty, ui.theme.dim());
            int ly = ty + 13;
            for (String line : lines) {
                ui.text(line, tx, ly, ui.theme.text());
                ly += LINE_H;
            }
        }));
        int bx = x + 8, by = y + 8 + 12 + textH + 3;
        List<Notifications.Action> actions = new ArrayList<>(e.actions);
        actions.add(new Notifications.Action("Copy", () -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(e.body);
            Notifications.success("Copied", "the message");
        }));
        for (Notifications.Action a : actions) {
            int bw = Text.width(a.label()) + 20;
            into.add(new Button(bx, by, bw, 16, a.label(), a.label().equals("Copy") ? Button.Style.GHOST : Button.Style.SECONDARY, a.run()));
            bx += bw + 4;
        }
        return y + h + 6;
    }

    private static String ago(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s ago";
        long m = s / 60;
        if (m < 60) return m + "m ago";
        return (m / 60) + "h " + (m % 60) + "m ago";
    }
}
