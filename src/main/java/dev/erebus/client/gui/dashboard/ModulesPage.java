package dev.erebus.client.gui.dashboard;

import dev.erebus.client.Erebus;
import dev.erebus.client.gui.widget.Container;
import dev.erebus.client.gui.widget.ListRow;
import dev.erebus.client.gui.widget.ScrollPanel;
import dev.erebus.client.gui.widget.SettingsBuilder;
import dev.erebus.client.module.Category;
import dev.erebus.client.module.Module;

import java.util.List;
import java.util.Locale;

/** Modules of one category, or search results across all categories when {@code category} is null. */
public final class ModulesPage extends Page {
    private final Category category;
    private Module selected;

    public ModulesPage(DashboardScreen screen, Category category) {
        super(screen);
        this.category = category;
    }

    @Override
    public String id() {
        return category == null ? "search" : category.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public String label() {
        return category == null ? "Search" : category.label;
    }

    @Override
    public String badge() {
        if (category == null) return null;
        List<Module> mods = Erebus.modules().byCategory(category);
        if (mods.isEmpty()) return null;
        int on = 0;
        for (Module m : mods) if (m.isEnabled()) on++;
        return on + "/" + mods.size();
    }

    @Override
    public void build(Container root, int x, int y, int w, int h) {
        List<Module> mods = category == null
                ? Erebus.modules().search(screen.search())
                : Erebus.modules().byCategory(category);
        if (mods.isEmpty()) {
            if (category == null) {
                screen.emptyState(root, x, y, w, h, "No results",
                        "Nothing matches “" + screen.search().trim() + "”. Try part of a module or category name.");
            } else {
                screen.emptyState(root, x, y, w, h, "No " + category.label + " modules yet",
                        "Modules you add to this category will be listed here, with their settings on the right.");
            }
            return;
        }
        if (selected == null || !mods.contains(selected)) selected = mods.get(0);

        int lx = x + 6;
        int lw = DashboardScreen.LIST - 12;
        ScrollPanel list = screen.listPanel(root, lx, y + 6, lw, h - 12);
        int ry = y + 6;
        for (Module m : mods) {
            list.add(new ListRow(lx, ry, lw, 20, m.name, () -> m == selected, m::isEnabled,
                    () -> {
                        selected = m;
                        screen.rebuild();
                    }, m::toggle)
                    .trailing(() -> category == null ? m.category.label : m.keybind.isBound() ? m.keybind.keyName() : null));
            ry += 21;
        }

        Module m = selected;
        int paneX = x + DashboardScreen.LIST + 1;
        ScrollPanel pane = screen.listPanel(root, paneX, y + 1, w - DashboardScreen.LIST - 2, h - 2);
        int px = paneX + 12;
        int pw = pane.width - 24;
        int py = y + 12;
        py = screen.paneHeader(pane, px, py, pw, m.name, () -> m.category.label, m::isEnabled, m::setEnabled);
        py = screen.paragraph(pane, px, py, pw, m.description);
        if (!m.settings().isEmpty()) {
            py = SettingsBuilder.section(pane, "Settings", px, py, pw);
            py = SettingsBuilder.build(pane, m.settings(), px, py, pw, DashboardScreen.W, DashboardScreen.H, screen::rebuild) + 8;
        }
        py = SettingsBuilder.section(pane, "Controls", px, py, pw);
        py = SettingsBuilder.row(pane, m.keybind, px, py, pw, DashboardScreen.W, DashboardScreen.H, screen::rebuild) + 8;
        screen.paragraph(pane, px, py, pw, "Tip: right-click a module in the list to turn it on or off.");
    }
}
