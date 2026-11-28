package dev.erebus.client.module;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ModuleManager {
    private static final Logger LOG = LoggerFactory.getLogger("Erebus");
    private final List<Module> modules = new ArrayList<>();
    private final Set<Module> failed = new HashSet<>();

    public void register(Module module) {
        modules.add(module);
    }

    public List<Module> all() {
        return Collections.unmodifiableList(modules);
    }

    @SuppressWarnings("unchecked")
    public <M extends Module> M get(Class<M> type) {
        for (Module m : modules) {
            if (type.isInstance(m)) return (M) m;
        }
        return null;
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
            if (m.name.toLowerCase(Locale.ROOT).contains(q) || m.category.label.toLowerCase(Locale.ROOT).contains(q)
                    || m.description.toLowerCase(Locale.ROOT).contains(q)) out.add(m);
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

    public void preTick() {
        for (Module m : modules) {
            if (!m.isEnabled()) continue;
            try {
                m.onPreTick();
            } catch (Throwable t) {
                report(m, t);
            }
        }
    }

    public void tick() {
        for (Module m : modules) {
            if (!m.isEnabled()) continue;
            try {
                m.onTick();
            } catch (Throwable t) {
                report(m, t);
            }
        }
    }

    public void renderWorld(WorldRender ctx) {
        for (Module m : modules) {
            if (!m.isEnabled()) continue;
            try {
                m.onRenderWorld(ctx);
            } catch (Throwable t) {
                report(m, t);
            }
        }
    }

    public void renderOverlay(dev.erebus.client.gui.Ui ui) {
        for (Module m : modules) {
            if (!m.isEnabled()) continue;
            try {
                ui.tagModuleScale = m.tagSize();
                m.onRenderOverlay(ui);
            } catch (Throwable t) {
                report(m, t);
            } finally {
                ui.tagModuleScale = 1f;
            }
        }
    }

    /** Logs a module's first error instead of spamming the log every tick. */
    private void report(Module m, Throwable t) {
        if (failed.add(m)) LOG.error("Module {} threw an error (further errors from it are not logged)", m.name, t);
    }
}
