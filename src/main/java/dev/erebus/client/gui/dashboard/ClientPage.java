package dev.erebus.client.gui.dashboard;

import com.mojang.blaze3d.platform.InputConstants;
import dev.erebus.client.Erebus;
import dev.erebus.client.gui.Ui;
import dev.erebus.client.gui.widget.Button;
import dev.erebus.client.gui.widget.Container;
import dev.erebus.client.gui.widget.Drawable;
import dev.erebus.client.gui.widget.KeybindButton;
import dev.erebus.client.gui.widget.ScrollPanel;
import dev.erebus.client.gui.widget.SettingsBuilder;
import dev.erebus.client.notify.Notifications;
import dev.erebus.client.setting.KeybindSetting;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.util.Util;
import net.minecraft.client.KeyMapping;

/** Client-wide preferences, the menu keybind and version info. */
public final class ClientPage extends Page {
    public ClientPage(DashboardScreen screen) {
        super(screen);
    }

    @Override
    public String id() {
        return "client";
    }

    @Override
    public dev.erebus.client.render.font.Icon icon() {
        return dev.erebus.client.render.font.Icon.SLIDERS;
    }

    @Override
    public String label() {
        return "Client";
    }

    @Override
    public void build(Container root, int x, int y, int w, int h) {
        int lx = x + 8;
        int lw = DashboardScreen.LIST - 16;
        String loader = FabricLoader.getInstance().getModContainer("fabricloader")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        String mc = SharedConstants.getCurrentVersion().name();
        int cy = SettingsBuilder.section(root, "About", lx, y + 10, lw);
        root.add(new Drawable(lx, cy, lw, 52, (ui, self, mx, my) -> {
            int ty = self.y;
            int gw = ui.gradientText("Erebus", self.x, ty, ui.theme.accent(), ui.theme.accent2(), true);
            ui.text(" " + Erebus.VERSION, self.x + gw, ty, ui.theme.dim());
            ui.text("Minecraft " + mc, self.x, ty + 14, ui.theme.dim());
            ui.text("Fabric Loader " + loader, self.x, ty + 26, ui.theme.dim());
            ui.text(Erebus.modules().all().size() + " modules loaded", self.x, ty + 38, ui.theme.dim());
        }));
        cy += 62;
        root.add(new Button(lx, cy, lw, 18, "Open config folder", Button.Style.SECONDARY, () ->
                Util.getPlatform().openPath(FabricLoader.getInstance().getConfigDir().resolve("erebus"))));
        root.add(new Button(lx, cy + 22, lw, 18, "Save settings now", Button.Style.SECONDARY, () -> {
            Erebus.config().save();
            Notifications.success("Settings", "saved");
        }));

        int paneX = x + DashboardScreen.LIST + 1;
        ScrollPanel pane = screen.listPanel(root, paneX, y + 1, w - DashboardScreen.LIST - 2, h - 2);
        int px = paneX + 12;
        int pw = pane.width - 24;
        int py = y + 12;
        py = screen.paneHeader(pane, px, py, pw, "Client", null, null, null);

        py = SettingsBuilder.section(pane, "Interface", px, py, pw);
        py = SettingsBuilder.build(pane, Erebus.client().all(), px, py, pw, DashboardScreen.W, DashboardScreen.H, screen::rebuild);
        py = screen.paragraph(pane, px, py + 2, pw, "Menu scale applies the next time you open the menu.");

        py = SettingsBuilder.section(pane, "Keybinds", px, py, pw);
        for (Object[] kb : new Object[][]{{"Open menu", Erebus.menuKey()}, {"Open map", Erebus.mapKey()}}) {
            KeyMapping mapping = (KeyMapping) kb[1];
            screen.label(pane, px, py, pw - 110, SettingsBuilder.ROW, (String) kb[0]);
            pane.add(new KeybindButton(px + pw - 96, py + 1, 96, 16,
                    () -> mapping == null ? KeybindSetting.NONE : KeyBindingHelper.getBoundKeyOf(mapping).getValue(),
                    key -> {
                        if (mapping == null) return;
                        mapping.setKey(key == KeybindSetting.NONE ? InputConstants.UNKNOWN : InputConstants.Type.KEYSYM.getOrCreate(key));
                        KeyMapping.resetMapping();
                        screen.minecraftOptionsSave();
                    }));
            py += SettingsBuilder.ROW;
        }
        py += 4;
        pane.add(new Drawable(px, py, pw, 12, (ui, self, mx, my) ->
                ui.text("Right-click a keybind to clear it.", self.x, self.y, ui.theme.dim())));
    }
}
