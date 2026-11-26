package dev.erebus.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.erebus.client.gui.dashboard.DashboardScreen;
import dev.erebus.client.module.PreviewModules;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class ErebusClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Erebus.hud().registerDefaults();
        if (Boolean.getBoolean("erebus.previewModules")) PreviewModules.register(Erebus.modules());
        Erebus.config().load();

        KeyMapping.Category category = KeyMapping.Category.register(Erebus.id("main"));
        KeyMapping menu = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.erebus.open_menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category));
        Erebus.setMenuKey(menu);

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Erebus.id("hud"), Erebus.hud()::render);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            Erebus.hud().tick(mc);
            while (menu.consumeClick()) {
                if (mc.screen == null) mc.setScreen(new DashboardScreen());
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> Erebus.config().save());
    }
}
