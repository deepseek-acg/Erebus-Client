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
    /** The module whose settings were last shown, so picking another one animates and a rebuild doesn't. */
    private String shownModule;

    public ModulesPage(DashboardScreen screen, Category category) {
        super(screen);
        this.category = category;
    }

    @Override
    public String id() {
        return category == null ? "search" : category.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public dev.erebus.client.render.font.Icon icon() {
        return category == null ? dev.erebus.client.render.font.Icon.SEARCH : category.icon;
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
                screen.emptyState(root, x, y, w, h, dev.erebus.client.render.font.Icon.SEARCH, "No results",
                        "Nothing matches “" + screen.search().trim() + "”. Try part of a module or category name.");
            } else {
                screen.emptyState(root, x, y, w, h, category.icon, "No " + category.label + " modules yet",
                        "Modules you add to this category will be listed here, with their settings on the right.");
            }
            return;
        }
        if (selected == null) { // the module this page had picked when the menu was last closed
            String was = DashboardScreen.PICKED.get(id());
            for (Module m : mods) if (m.name.equals(was)) selected = m;
        }
        if (selected == null || !mods.contains(selected)) selected = mods.get(0);
        if (category != null) DashboardScreen.PICKED.put(id(), selected.name);

        int lx = x + 6;
        int lw = DashboardScreen.LIST - 12;
        ScrollPanel list = screen.listPanel(root, lx, y + 6, lw, h - 12).key("modules-list:" + id());
        int ry = y + 6;
        dev.erebus.client.gui.widget.Glide glide = list.add(new dev.erebus.client.gui.widget.Glide("list:" + id(), lx, ry, lw, 20));
        for (Module m : mods) {
            if (m == selected) glide.at(ry);
            list.add(new ListRow(lx, ry, lw, 20, m.name, () -> m == selected, m::isEnabled,
                    () -> {
                        selected = m;
                        screen.rebuild();
                    }, m::toggle).glided()
                    .trailing(() -> category == null ? m.category.label : m.keybind.isBound() ? m.keybind.keyName() : m.info())
                    .tooltip(m.description));
            ry += 21;
        }

        Module m = selected;
        int paneX = x + DashboardScreen.LIST + 1;
        ScrollPanel pane = screen.listPanel(root, paneX, y + 1, w - DashboardScreen.LIST - 2, h - 2).key("module:" + m.name);
        if (!m.name.equals(shownModule)) { // a module just picked: its settings cascade in (not on every rebuild)
            if (shownModule != null) pane.reveal();
            shownModule = m.name;
        }
        int px = paneX + 12;
        int pw = pane.width - 24;
        int py = y + 12;
        py = screen.paneHeader(pane, px, py, pw, m.name, () -> m.category.label, m::isEnabled, m::setEnabled);
        py = screen.paragraph(pane, px, py, pw, m.description);
        if (!m.actions().isEmpty()) {
            int bx = px;
            for (Module.Action a : m.actions()) {
                int bw = dev.erebus.client.render.font.Text.width(a.label()) + 34;
                if (bx > px && bx + bw > px + pw) { // no room left on this row: start the next
                    bx = px;
                    py += 24;
                }
                pane.add(new dev.erebus.client.gui.widget.Button(bx, py, bw, 18, a.label(), dev.erebus.client.gui.widget.Button.Style.PRIMARY, () -> {
                    screen.onClose();
                    a.run().run();
                }).icon(dev.erebus.client.render.font.Icon.MAP));
                bx += bw + 6;
            }
            py += 28;
        }
        if (!m.settings().isEmpty()) {
            if (SettingsBuilder.startsUngrouped(m.settings())) py = SettingsBuilder.section(pane, "Settings", px, py, pw);
            py = SettingsBuilder.build(pane, m.settings(), px, py, pw, DashboardScreen.W, DashboardScreen.H, screen::rebuild) + 8;
        }
        py = SettingsBuilder.section(pane, "Controls", px, py, pw);
        py = SettingsBuilder.row(pane, m.keybind, px, py, pw, DashboardScreen.W, DashboardScreen.H, screen::rebuild) + 8;
        screen.paragraph(pane, px, py, pw, "Tip: right-click a module in the list to turn it on or off.");
    }
}
