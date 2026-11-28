package dev.erebus.client.notify;

import dev.erebus.client.util.Spring;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Everything the client tells you: short toasts (a module turned on) and messages (a find, a seed, an alert), shown by
 * the Notifications HUD element. Messages are also kept, newest first, for the dashboard's Messages page. Nothing goes
 * to Minecraft's chat.
 */
public final class Notifications {
    public enum Kind { INFO, SUCCESS, WARNING, ENABLED, DISABLED }

    /** A button offered with a message on the Messages page, e.g. Copy seed. */
    public record Action(String label, Runnable run) {
    }

    public static final class Entry {
        public final String title;
        public final String detail;
        /** The full text of a message, wrapped on several lines (null for a one-line toast). */
        public final String body;
        public final Kind kind;
        public final List<Action> actions;
        public final long created = System.currentTimeMillis();
        final long life;
        /** 0 off screen, 1 in place; springy, so it lands with a little overshoot. */
        public final Spring slide = new Spring(0f, 240f, 22f);
        public final Spring y = new Spring(-1f, 320f, 32f);

        Entry(String title, String detail, String body, Kind kind, List<Action> actions, long life) {
            this.title = title;
            this.detail = detail;
            this.body = body;
            this.kind = kind;
            this.actions = actions;
            this.life = life;
            slide.target(1f);
        }

        /** How much of its time on screen is left, 1 to 0. */
        public float remaining() {
            return Math.max(0f, 1f - (System.currentTimeMillis() - created) / (float) life);
        }
    }

    private static final int MAX = 6;
    private static final int HISTORY_MAX = 300;
    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final List<Entry> HISTORY = new ArrayList<>();
    private static long durationMs = 2500;
    private static int unread;

    private Notifications() {
    }

    /** A one-line toast: {@code title} and a short {@code detail} after it. Not kept. */
    public static synchronized void push(String title, String detail, Kind kind) {
        add(new Entry(title, detail, null, kind, List.of(), durationMs));
    }

    /**
     * A message: {@code title} (usually the module) over {@code text}, which can be long and is wrapped. It stays up a
     * little longer the longer it is, and is kept on the Messages page with its {@code actions}.
     */
    public static synchronized void message(String title, String text, Kind kind, Action... actions) {
        long life = Math.max(durationMs, Math.min(15_000, 4_000 + text.length() * 45L));
        Entry e = new Entry(title, null, text, kind, List.of(actions), life);
        add(e);
        HISTORY.add(0, e);
        while (HISTORY.size() > HISTORY_MAX) HISTORY.remove(HISTORY.size() - 1);
        unread++;
    }

    private static void add(Entry e) {
        ENTRIES.add(0, e);
        while (ENTRIES.size() > MAX) ENTRIES.remove(ENTRIES.size() - 1);
    }

    public static void info(String title, String detail) {
        push(title, detail, Kind.INFO);
    }

    public static void success(String title, String detail) {
        push(title, detail, Kind.SUCCESS);
    }

    public static void moduleToggled(String module, boolean on) {
        push(module, on ? "enabled" : "disabled", on ? Kind.ENABLED : Kind.DISABLED);
    }

    public static void setDuration(long ms) {
        durationMs = ms;
    }

    /** Live entries, newest first. Entries past their lifetime animate out, then are removed. */
    public static synchronized List<Entry> active() {
        long now = System.currentTimeMillis();
        Iterator<Entry> it = ENTRIES.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (now - e.created > e.life) {
                e.slide.target(0f);
                if (e.slide.get() <= 0.02f) it.remove();
            }
        }
        return new ArrayList<>(ENTRIES);
    }

    /** Messages kept so far, newest first. */
    public static synchronized List<Entry> history() {
        return new ArrayList<>(HISTORY);
    }

    public static synchronized void clearHistory() {
        HISTORY.clear();
        unread = 0;
    }

    /** Messages that came in since the Messages page was last looked at. */
    public static synchronized int unread() {
        return unread;
    }

    public static synchronized void markRead() {
        unread = 0;
    }
}
