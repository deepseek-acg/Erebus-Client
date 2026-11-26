package dev.erebus.client.notify;

import dev.erebus.client.util.Anim;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Short-lived toasts shown by the Notifications HUD element. */
public final class Notifications {
    public enum Kind { INFO, SUCCESS, WARNING, ENABLED, DISABLED }

    public static final class Entry {
        public final String title;
        public final String detail;
        public final Kind kind;
        public final long created = System.currentTimeMillis();
        public final Anim slide = new Anim(0f, 16f);
        public final Anim y = new Anim(-1f, 16f);

        Entry(String title, String detail, Kind kind) {
            this.title = title;
            this.detail = detail;
            this.kind = kind;
            slide.target(1f);
        }
    }

    private static final int MAX = 6;
    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static long durationMs = 2500;

    private Notifications() {
    }

    public static synchronized void push(String title, String detail, Kind kind) {
        ENTRIES.add(0, new Entry(title, detail, kind));
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
            if (now - e.created > durationMs) {
                e.slide.target(0f);
                if (e.slide.get() <= 0.01f) it.remove();
            }
        }
        return new ArrayList<>(ENTRIES);
    }
}
