package dev.erebus.client.util;

import dev.erebus.client.Erebus;
import dev.erebus.client.notify.Notifications;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

/**
 * What the client has to tell you. It goes to Erebus's own notifications (a toast, and the dashboard's Messages page),
 * not Minecraft's chat, unless Also in chat is on on the Client page. Nothing is ever sent to the server.
 */
public final class Chat {
    private Chat() {
    }

    public static void info(String message) {
        info("Erebus", message);
    }

    /** A message from {@code source} (a module's name, usually). */
    public static void info(String source, String message, Notifications.Action... actions) {
        send(source, message, Notifications.Kind.INFO, actions);
    }

    /** A message worth noticing: a find, someone nearby. */
    public static void alert(String source, String message, Notifications.Action... actions) {
        send(source, message, Notifications.Kind.WARNING, actions);
    }

    public static void success(String source, String message, Notifications.Action... actions) {
        send(source, message, Notifications.Kind.SUCCESS, actions);
    }

    /** A Copy button for a message: puts {@code text} on the clipboard. */
    public static Notifications.Action copy(String label, String text) {
        return new Notifications.Action(label, () -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(text);
            Notifications.success("Copied", text.length() > 40 ? text.substring(0, 40) + "…" : text);
        });
    }

    /** Copy coordinates. */
    public static Notifications.Action coords(int x, int y, int z) {
        return copy("Copy coordinates", x + " " + y + " " + z);
    }

    private static void send(String source, String message, Notifications.Kind kind, Notifications.Action... actions) {
        Notifications.message(source, message, kind, actions);
        if (Erebus.client().messagesInChat.get()) toChat(source, message);
    }

    private static void toChat(String source, String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null || mc.player == null) return;
        int accent = Erebus.theme().accent() & 0xFFFFFF;
        MutableComponent prefix = Component.literal(source + " ").withStyle(s -> s.withColor(TextColor.fromRgb(accent)).withBold(true));
        MutableComponent arrow = Component.literal("» ").withStyle(s -> s.withColor(TextColor.fromRgb(0x6B6680)));
        MutableComponent body = Component.literal(message).withStyle(s -> s.withColor(TextColor.fromRgb(0xD8D4E6)));
        mc.gui.getChat().addMessage(prefix.append(arrow).append(body));
    }
}
