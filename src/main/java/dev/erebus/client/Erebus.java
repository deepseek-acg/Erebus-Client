package dev.erebus.client;

import dev.erebus.client.config.ConfigManager;
import dev.erebus.client.hud.HudManager;
import dev.erebus.client.module.ModuleManager;
import dev.erebus.client.theme.Theme;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Static access to the client's shared state. */
public final class Erebus {
    public static final String MOD_ID = "erebus";
    public static final String NAME = "Erebus";
    public static final String VERSION = FabricLoader.getInstance().getModContainer(MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");

    private static final Theme THEME = new Theme();
    private static final HudManager HUD = new HudManager();
    private static final ModuleManager MODULES = new ModuleManager();
    private static final ClientSettings CLIENT = new ClientSettings();
    private static final ConfigManager CONFIG = new ConfigManager();
    private static KeyMapping menuKey;

    private Erebus() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static Theme theme() {
        return THEME;
    }

    public static HudManager hud() {
        return HUD;
    }

    public static ModuleManager modules() {
        return MODULES;
    }

    public static ClientSettings client() {
        return CLIENT;
    }

    public static ConfigManager config() {
        return CONFIG;
    }

    public static KeyMapping menuKey() {
        return menuKey;
    }

    static void setMenuKey(KeyMapping key) {
        menuKey = key;
    }
}
