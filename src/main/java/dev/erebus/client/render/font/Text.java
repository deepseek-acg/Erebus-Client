package dev.erebus.client.render.font;

import dev.erebus.client.Erebus;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Text drawing for the whole client. Uses Erebus Sans when the theme's font option is on (and the font loaded),
 * otherwise Minecraft's font. Both share the same 9-unit line box, so layouts work with either.
 */
public final class Text {
    private Text() {
    }

    public static boolean custom() {
        return Erebus.theme().customFont && ErebusFont.available();
    }

    private static Font mcFont() {
        return Minecraft.getInstance().font;
    }

    public static int width(String s) {
        return width(s, false);
    }

    public static int width(String s, boolean bold) {
        if (s == null || s.isEmpty()) return 0;
        if (custom()) return (int) Math.ceil(ErebusFont.width(s, bold));
        return bold ? mcFont().width(Component.literal(s).withStyle(ChatFormatting.BOLD)) : mcFont().width(s);
    }

    /** Draws text with the line box's top-left at (x, y); returns the width drawn. */
    public static int draw(GuiGraphics g, String s, int x, int y, int color, boolean bold, boolean shadow) {
        if (s == null || s.isEmpty()) return 0;
        if (custom()) {
            ErebusFont.draw(g, s, x, y, color, bold, shadow);
            return width(s, bold);
        }
        if (bold) {
            Component c = Component.literal(s).withStyle(ChatFormatting.BOLD);
            g.drawString(mcFont(), c, x, y, color, shadow);
            return mcFont().width(c);
        }
        g.drawString(mcFont(), s, x, y, color, shadow);
        return mcFont().width(s);
    }

    /** Draws an icon glyph whose box is {@code size} GUI units tall, with its top-left at (x, y). */
    public static void icon(GuiGraphics g, Icon icon, float x, float y, float size, int color) {
        if (!ErebusFont.available()) return;
        // icons are drawn 0..700 units above the baseline; size the em so that box is `size` tall
        float em = size / 0.7f;
        ErebusFont.draw(g, icon.glyph, x, y + size - ErebusFont.BASELINE * em / ErebusFont.SIZE, color, false, false, em);
    }

    public static float iconWidth(float size) {
        return size / 0.7f * 0.76f;
    }

    public static String ellipsize(String s, int maxWidth, boolean bold) {
        if (width(s, bold) <= maxWidth) return s;
        String dots = "…";
        int dw = width(dots, bold);
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (width(s.substring(0, mid), bold) + dw <= maxWidth) lo = mid;
            else hi = mid - 1;
        }
        return s.substring(0, lo).stripTrailing() + dots;
    }

    /** Word-wraps text to lines no wider than {@code maxWidth}. */
    public static List<String> wrap(String s, int maxWidth) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : s.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (width(candidate) <= maxWidth || line.isEmpty()) {
                    line.setLength(0);
                    line.append(candidate);
                } else {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }
}
