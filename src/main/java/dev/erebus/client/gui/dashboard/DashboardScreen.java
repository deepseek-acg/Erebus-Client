package dev.erebus.client.gui.dashboard;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.erebus.client.Erebus;
import dev.erebus.client.gui.Ui;
import dev.erebus.client.gui.UiScreen;
import dev.erebus.client.gui.widget.Button;
import dev.erebus.client.gui.widget.Container;
import dev.erebus.client.gui.widget.Drawable;
import dev.erebus.client.gui.widget.ScrollPanel;
import dev.erebus.client.gui.widget.Switch;
import dev.erebus.client.gui.widget.TextBlock;
import dev.erebus.client.gui.widget.TextField;
import dev.erebus.client.gui.widget.Widget;
import dev.erebus.client.module.Category;
import dev.erebus.client.module.Module;
import dev.erebus.client.render.Render2D;
import dev.erebus.client.theme.Theme;
import dev.erebus.client.util.Anim;
import dev.erebus.client.util.Colors;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.InputQuirks;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The main Erebus window: brand bar with search, a sidebar of pages, a list column and a settings
 * pane. Opened with the menu key (Right Shift by default).
 */
public final class DashboardScreen extends UiScreen {
    public static final int W = 600;
    public static final int H = 360;
    public static final int TOP = 30;
    public static final int SIDE = 118;
    public static final int LIST = 182;

    private static String lastPageId = "combat";

    private final List<Page> modulePages = new ArrayList<>();
    private final List<Page> clientPages = new ArrayList<>();
    private final ModulesPage searchPage;
    private Page current;
    private String search = "";
    private TextField searchField;
    private final Anim open = new Anim(0f, 16f);

    public DashboardScreen() {
        super(Component.translatable("erebus.screen.dashboard"));
        for (Category c : Category.values()) modulePages.add(new ModulesPage(this, c));
        clientPages.add(new HudPage(this));
        clientPages.add(new ThemePage(this));
        clientPages.add(new ClientPage(this));
        searchPage = new ModulesPage(this, null);
        String wanted = Erebus.client().rememberPage.get() ? lastPageId : "combat";
        current = modulePages.get(0);
        for (Page p : allPages()) {
            if (p.id().equals(wanted)) current = p;
        }
        open.target(1f);
    }

    private List<Page> allPages() {
        List<Page> all = new ArrayList<>(modulePages);
        all.addAll(clientPages);
        return all;
    }

    public String search() {
        return search;
    }

    public void show(Page page) {
        if (page != current) pageIn.snap(0f);
        pageIn.target(1f);
        current = page;
        lastPageId = page.id();
        search = "";
        rebuild();
    }

    public void openHudEditor() {
        Erebus.config().save();
        minecraft.setScreen(new dev.erebus.client.gui.hud.HudEditorScreen(this));
    }

    @Override
    protected int designWidth() {
        return W;
    }

    @Override
    protected int designHeight() {
        return H;
    }

    @Override
    protected float userScale() {
        return Erebus.client().uiScale.floatValue();
    }

    @Override
    protected float frameScale() {
        return scale * (0.965f + 0.035f * open.get());
    }

    // ------------------------------------------------------------------ build

    @Override
    protected void build() {
        int brandW = 110;
        searchField = root.add(new TextField(brandW + 14, 6, 230, 18, search, "Search modules", 40)
                .icon(dev.erebus.client.render.font.Icon.SEARCH)
                .onChange(s -> {
                    search = s;
                    rebuild();
                }));
        root.add(new Button(W - 26, 6, 18, 18, "", Button.Style.GHOST, this::onClose).icon(dev.erebus.client.render.font.Icon.CLOSE));

        // sidebar
        int sy = TOP + 8;
        sy = sideLabel("Modules", sy);
        for (Page p : modulePages) sy = sideItem(p, sy);
        sy += 6;
        sy = sideLabel("Client", sy);
        for (Page p : clientPages) sy = sideItem(p, sy);
        root.add(new Button(8, H - 28, SIDE - 16, 20, "Open map", Button.Style.SECONDARY, () -> {
            onClose();
            dev.erebus.client.module.world.MapModule.openFullMap();
        }).icon(dev.erebus.client.render.font.Icon.MAP));

        Page page = search.isBlank() ? current : searchPage;
        SlideIn content = root.add(new SlideIn());
        page.build(content, SIDE, TOP, W - SIDE, H - TOP);
    }

    private final Anim pageIn = new Anim(1f, 14f);

    /** Page content, sliding in a few pixels when the page changes. */
    private final class SlideIn extends Container {
        @Override
        public void render(Ui ui, int mx, int my) {
            float off = (1f - pageIn.update(ui.dt)) * 10f;
            if (off < 0.05f) {
                super.render(ui, mx, my);
                return;
            }
            ui.g.pose().pushMatrix();
            ui.g.pose().translate(off, 0);
            super.render(ui, mx - Math.round(off), my);
            ui.g.pose().popMatrix();
        }
    }

    @Override
    public void rebuild() {
        boolean typing = searchField != null && ui.focused() == searchField;
        super.rebuild();
        if (typing) ui.focus(searchField);
    }

    private int sideLabel(String text, int y) {
        root.add(new Drawable(10, y, SIDE - 20, 12, (ui, self, mx, my) ->
                ui.text(text.toUpperCase(java.util.Locale.ROOT), self.x + 2, self.y + 2, Colors.mulAlpha(ui.theme.dim(), 0.8f))));
        return y + 14;
    }

    private int sideItem(Page page, int y) {
        root.add(new SideItem(6, y, SIDE - 12, 18, page));
        return y + 19;
    }

    private final class SideItem extends Widget {
        private final Page page;
        private final Anim hover = new Anim(0f, 20f);

        SideItem(int x, int y, int w, int h, Page page) {
            bounds(x, y, w, h);
            this.page = page;
        }

        @Override
        public void render(Ui ui, int mx, int my) {
            boolean hot = contains(mx, my);
            if (hot) ui.cursor(CursorTypes.POINTING_HAND);
            hover.target(hot ? 1f : 0f);
            float h = hover.update(ui.dt);
            boolean active = search.isBlank() && current == page;
            int r = ui.smallRadius();
            if (active) {
                ui.round(x, y, width, height, r, ui.theme.selected());
                ui.rect(x, y + 3, 2, height - 6, ui.theme.accent());
            } else if (h > 0.01f) {
                ui.round(x, y, width, height, r, Colors.mulAlpha(ui.theme.hover(), h));
            }
            int color = active ? ui.theme.text() : Colors.lerp(ui.theme.dim(), ui.theme.text(), h * 0.7f);
            ui.icon(page.icon(), x + 8, y + (height - 8) / 2f, 8, active ? ui.theme.accent() : Colors.lerp(ui.theme.dim(), ui.theme.accent(), h * 0.6f));
            ui.text(page.label(), x + 22, Ui.centerY(y, height), color);
            String badge = page.badge();
            if (badge != null) ui.textRight(badge, right() - 7, Ui.centerY(y, height), Colors.mulAlpha(ui.theme.dim(), 0.9f));
        }

        @Override
        public boolean mouseClicked(Ui ui, double mx, double my, int button) {
            if (button != 0 || !contains(mx, my)) return false;
            show(page);
            return true;
        }
    }

    // ------------------------------------------------------------------ shared page helpers

    public ScrollPanel listPanel(Container root, int x, int y, int w, int h) {
        return root.add(new ScrollPanel(x, y, w, h));
    }

    /** Pane title with an optional category chip and enable switch. Returns the y below it. */
    public int paneHeader(Container into, int x, int y, int w, String title, Supplier<String> chip,
                          BooleanSupplier on, Consumer<Boolean> set) {
        into.add(new Drawable(x, y, w, 20, (ui, self, mx, my) -> {
            int tx = self.x;
            tx += ui.textBold(ui.ellipsize(title, self.width - 90), tx, Ui.centerY(self.y, 20), ui.theme.text());
            String c = chip == null ? null : chip.get();
            if (c != null) {
                int cw = ui.width(c) + 10;
                ui.round(tx + 7, self.y + 4, cw, 12, 6, ui.theme.track());
                ui.text(c, tx + 12, Ui.centerY(self.y + 4, 12), ui.theme.dim());
            }
        }));
        if (on != null) into.add(new Switch(x + w - 30, y + 2, 30, 16, on, set));
        return y + 26;
    }

    public int paragraph(Container into, int x, int y, int w, String text) {
        if (text == null || text.isBlank()) return y;
        TextBlock tb = into.add(new TextBlock(x, y, w, text, TextBlock.Tone.DIM));
        return tb.bottom() + 10;
    }

    /** Centered message for pages with nothing to list yet. */
    public void emptyState(Container into, int x, int y, int w, int h, String title, String body) {
        emptyState(into, x, y, w, h, dev.erebus.client.render.font.Icon.DOTS, title, body);
    }

    public void emptyState(Container into, int x, int y, int w, int h, dev.erebus.client.render.font.Icon icon, String title, String body) {
        into.add(new Drawable(x, y, w, h, (ui, self, mx, my) -> {
            List<String> lines = ui.wrap(body, Math.min(260, self.width - 40));
            int blockH = 30 + 16 + lines.size() * 11;
            int top = self.y + (self.height - blockH) / 2;
            int cx = self.x + self.width / 2;
            int r = ui.smallRadius();
            ui.round(cx - 13, top, 26, 26, r + 2, ui.theme.track());
            ui.border(cx - 13, top, 26, 26, r + 2, ui.theme.border());
            ui.icon(icon, cx - 5.5f, top + 7, 12, ui.theme.accent());
            ui.textCentered(title, cx, top + 36, ui.theme.text());
            int ly = top + 52;
            for (String line : lines) {
                ui.textCentered(line, cx, ly, ui.theme.dim());
                ly += 11;
            }
        }));
    }

    /** Label on the left, {@code control} placed by the caller on the right. */
    public void label(Container into, int x, int y, int w, int h, String text) {
        into.add(new Drawable(x, y, w, h, (ui, self, mx, my) ->
                ui.text(ui.ellipsize(text, self.width), self.x, Ui.centerY(self.y, self.height) - 1, ui.theme.text())));
    }

    public void minecraftOptionsSave() {
        minecraft.options.save();
    }

    public int moduleCount() {
        return Erebus.modules().all().size();
    }

    public int enabledCount() {
        int n = 0;
        for (Module m : Erebus.modules().all()) if (m.isEnabled()) n++;
        return n;
    }

    // ------------------------------------------------------------------ frame

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (minecraft.level == null) {
            super.renderBackground(g, mouseX, mouseY, delta);
            return;
        }
        Theme t = Erebus.theme();
        if (t.blur && minecraft.options.getMenuBackgroundBlurriness() >= 1) {
            try {
                g.blurBeforeThisStratum();
            } catch (IllegalStateException ignored) {
                // another layer already blurred this frame
            }
        }
        float a = open.get();
        g.fillGradient(0, 0, width, height, Colors.withAlpha(0x000000, Math.round(0x50 * a)), Colors.withAlpha(0x000000, Math.round(0x78 * a)));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        open.update(ui.dt);
        super.render(g, mouseX, mouseY, delta);
    }

    @Override
    protected void renderFrame(Ui ui, int mx, int my) {
        Theme t = ui.theme;
        int r = t.radius;
        if (t.shadow) Render2D.shadow(ui.g, 0, 0, W, H, r, 10, 0xA0000000);
        Render2D.roundRect(ui.g, 0, 0, W, H, r, t.background());
        Render2D.roundRect(ui.g, 0, 0, W, TOP, r, t.header(), true, false);
        ui.rect(0, TOP, W, 1, t.divider());
        ui.rect(SIDE, TOP + 1, 1, H - TOP - 1, t.divider());
        ui.rect(SIDE + LIST, TOP + 1, 1, H - TOP - 1, t.divider());
        Render2D.roundRectBorder(ui.g, 0, 0, W, H, r, t.border());

        // brand
        int by = Ui.centerY(0, TOP);
        ui.icon(dev.erebus.client.render.font.Icon.LOGO, 12, by, 8, t.accent());
        int bx = 25;
        String brand = "EREBUS";
        for (int i = 0; i < brand.length(); i++) {
            String ch = String.valueOf(brand.charAt(i));
            bx += ui.textBold(ch, bx, by, t.gradient(i / (float) (brand.length() - 1))) + 1;
        }
        ui.text(Erebus.VERSION, bx + 5, by, t.dim());

        String meta = moduleCount() + " modules · " + enabledCount() + " on";
        ui.textRight(meta, W - 34, by, t.dim());
    }

    // ------------------------------------------------------------------ input

    @Override
    protected boolean onKey(int key, int modifiers) {
        if (key == GLFW.GLFW_KEY_F && (modifiers & InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER) != 0) {
            ui.focus(searchField);
            return true;
        }
        // only when nothing has focus: Right Shift is also how people type capitals in the search box
        if (ui.focused() == null && Erebus.client().menuKeyCloses.get() && Erebus.menuKey() != null
                && KeyBindingHelper.getBoundKeyOf(Erebus.menuKey()).getValue() == key) {
            onClose();
            return true;
        }
        return false;
    }

    @Override
    protected boolean onChar(String chars) {
        if (ui.focused() == null && searchField != null && !chars.isBlank()) {
            ui.focus(searchField);
            return searchField.charTyped(ui, chars);
        }
        return false;
    }

    @Override
    public void onClose() {
        Erebus.config().save();
        super.onClose();
    }
}
