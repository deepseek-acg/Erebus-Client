package dev.erebus.client;

import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.NumberSetting;
import dev.erebus.client.setting.Setting;

import java.util.List;

/** Client-wide interface preferences shown on the dashboard's Client page. */
public final class ClientSettings {
    public final NumberSetting uiScale = new NumberSetting("Menu scale",
            "Size of the dashboard relative to Minecraft's GUI scale. It still shrinks to fit small windows.", 1.0, 0.6, 1.6, 0.05)
            .unit("x");
    public final BoolSetting menuKeyCloses = new BoolSetting("Menu key closes menu",
            "Pressing the menu key again closes the dashboard.", true);
    public final BoolSetting clickSounds = new BoolSetting("Click sounds", "Play a soft click when pressing buttons.", true);
    public final BoolSetting rememberPage = new BoolSetting("Remember last page",
            "Reopen the dashboard on the page you last used.", true);

    public List<Setting<?>> all() {
        return List.of(uiScale, menuKeyCloses, clickSounds, rememberPage);
    }
}
