package dev.erebus.client.util;

import dev.erebus.client.Erebus;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

/** Client-side chat messages (never sent to the server). */
public final class Chat {
    private Chat() {
    }

    public static void info(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null || mc.player == null) return;
        int accent = Erebus.theme().accent() & 0xFFFFFF;
        MutableComponent prefix = Component.literal("Erebus ").withStyle(s -> s.withColor(TextColor.fromRgb(accent)).withBold(true));
        MutableComponent arrow = Component.literal("» ").withStyle(s -> s.withColor(TextColor.fromRgb(0x6B6680)));
        MutableComponent body = Component.literal(message).withStyle(s -> s.withColor(TextColor.fromRgb(0xD8D4E6)));
        mc.gui.getChat().addMessage(prefix.append(arrow).append(body));
    }
}
