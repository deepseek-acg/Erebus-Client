package dev.erebus.client.gui.widget;

import dev.erebus.client.gui.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** Word-wrapped paragraph. Height is computed from the text when constructed. */
public final class TextBlock extends Widget {
    public enum Tone { TEXT, DIM }

    private final List<FormattedCharSequence> lines;
    private final Tone tone;
    private final int lineHeight = 11;

    public TextBlock(int x, int y, int w, String text, Tone tone) {
        this.lines = Minecraft.getInstance().font.split(Component.literal(text), Math.max(10, w));
        this.tone = tone;
        bounds(x, y, w, lines.size() * lineHeight - 2);
    }

    @Override
    public void render(Ui ui, int mx, int my) {
        int color = tone == Tone.DIM ? ui.theme.dim() : ui.theme.text();
        int ly = y;
        for (FormattedCharSequence line : lines) {
            ui.g.drawString(ui.font, line, x, ly, color, ui.theme.textShadow);
            ly += lineHeight;
        }
    }
}
