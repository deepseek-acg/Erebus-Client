package dev.erebus.client.gui.widget;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.render.font.Text;

import java.util.List;

/** Word-wrapped paragraph. Height is computed from the text when constructed. */
public final class TextBlock extends Widget {
    public enum Tone { TEXT, DIM }

    private final List<String> lines;
    private final Tone tone;
    private static final int LINE_HEIGHT = 11;

    public TextBlock(int x, int y, int w, String text, Tone tone) {
        this.lines = Text.wrap(text, Math.max(10, w));
        this.tone = tone;
        bounds(x, y, w, lines.size() * LINE_HEIGHT - 2);
    }

    @Override
    public void render(Ui ui, int mx, int my) {
        int color = tone == Tone.DIM ? ui.theme.dim() : ui.theme.text();
        int ly = y;
        for (String line : lines) {
            ui.text(line, x, ly, color);
            ly += LINE_HEIGHT;
        }
    }
}
