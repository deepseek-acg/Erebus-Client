package dev.erebus.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.erebus.client.gui.dashboard.DashboardScreen;
import dev.erebus.client.module.Modules;
import dev.erebus.client.module.world.MapModule;
import dev.erebus.client.module.PreviewModules;
import dev.erebus.client.module.WorldRender;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
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
        Modules.registerAll(Erebus.modules());
        if (Boolean.getBoolean("erebus.previewModules")) PreviewModules.register(Erebus.modules());
        Erebus.config().load();

        KeyMapping.Category category = KeyMapping.Category.register(Erebus.id("main"));
        KeyMapping menu = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.erebus.open_menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category));
        Erebus.setMenuKey(menu);
        KeyMapping map = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.erebus.open_map", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, category));
        Erebus.setMapKey(map);

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Erebus.id("hud"), Erebus.hud()::render);

        WorldRenderEvents.END_EXTRACTION.register(ctx -> {
            WorldRender wr = new WorldRender(ctx.camera(), ctx.tickCounter().getGameTimeDeltaPartialTick(false));
            dev.erebus.client.render.Projection.update(ctx.viewMatrix(), ctx.cullProjectionMatrix(), ctx.camera().position());
            Erebus.modules().renderWorld(wr);
            dev.erebus.client.module.basefinding.BaseFinds.render(wr);
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            Erebus.hud().tick(mc);
            Erebus.modules().tick();
            while (menu.consumeClick()) {
                if (mc.screen == null) mc.setScreen(new DashboardScreen());
            }
            while (map.consumeClick()) {
                if (mc.screen == null) MapModule.openFullMap();
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            Erebus.config().save();
            dev.erebus.client.map.MapStore.close();
        });
    }
}
