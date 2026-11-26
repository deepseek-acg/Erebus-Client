package dev.erebus.client.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ModuleManager {
    private final List<Module> modules = new ArrayList<>();

    public void register(Module module) {
        modules.add(module);
    }

    public List<Module> all() {
        return Collections.unmodifiableList(modules);
    }

    public List<Module> byCategory(Category category) {
        List<Module> out = new ArrayList<>();
        for (Module m : modules) {
            if (m.category == category) out.add(m);
        }
        return out;
    }

    public List<Module> search(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Module> out = new ArrayList<>();
        for (Module m : modules) {
            if (m.name.toLowerCase(Locale.ROOT).contains(q) || m.category.label.toLowerCase(Locale.ROOT).contains(q)) out.add(m);
        }
        return out;
    }

    public List<Module> enabled() {
        List<Module> out = new ArrayList<>();
        for (Module m : modules) {
            if (m.isEnabled()) out.add(m);
        }
        return out;
    }

    public Module byName(String name) {
        for (Module m : modules) {
            if (m.name.equalsIgnoreCase(name)) return m;
        }
        return null;
    }

    /** Toggles every module bound to {@code key}. Called for key presses while no screen is open. */
    public void onKey(int key) {
        for (Module m : modules) {
            if (m.keybind.get() == key) m.toggle();
        }
    }
}
