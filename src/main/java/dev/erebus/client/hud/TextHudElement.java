package dev.erebus.client.hud;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.setting.BoolSetting;

import java.util.ArrayList;
import java.util.List;

/** HUD element made of text lines built from colored segments. */
public abstract class TextHudElement extends HudElement {
    protected static final int LINE_H = 10;
    protected static final int PAD = 4;

    protected final BoolSetting background;
    private final List<Line> lines = new ArrayList<>();

    protected TextHudElement(String id, String name, String description, boolean enabled,
                             HAnchor h, VAnchor v, int offX, int offY, boolean backgroundDefault) {
        super(id, name, description, enabled, h, v, offX, offY);
        background = add(new BoolSetting("Background", "Draw a translucent box behind the text.", backgroundDefault));
    }

    protected abstract void buildLines(Ui ui, List<Line> out, boolean preview);

    @Override
    protected int[] measure(Ui ui, boolean preview) {
        lines.clear();
        buildLines(ui, lines, preview);
        if (lines.isEmpty()) return new int[]{0, 0};
        int w = 0;
        for (Line l : lines) w = Math.max(w, l.width(ui));
        int pad = background.get() ? PAD : 1;
        return new int[]{w + pad * 2, lines.size() * LINE_H - 1 + pad * 2};
    }

    @Override
    protected void draw(Ui ui, int x, int y, boolean preview) {
        int pad = background.get() ? PAD : 1;
        if (background.get()) ui.round(x, y, width, height, ui.smallRadius(), ui.theme.hudBackground());
        int ly = y + pad;
        for (Line l : lines) {
            int lx = alignRight() ? x + width - pad - l.width(ui) : x + pad;
            l.draw(ui, lx, ly);
            ly += LINE_H;
        }
    }

    /** One line of a text HUD element. */
    public static final class Line {
        private final List<Seg> segs = new ArrayList<>();

        public Line add(String text, int color) {
            segs.add(new Seg(text, color, false, false));
            return this;
        }

        public Line bold(String text, int color) {
            segs.add(new Seg(text, color, true, false));
            return this;
        }

        /** Text colored along the theme gradient. */
        public Line gradient(String text, boolean bold) {
            segs.add(new Seg(text, 0, bold, true));
            return this;
        }

        public int width(Ui ui) {
            int w = 0;
            for (Seg s : segs) w += s.bold ? ui.widthBold(s.text) : ui.width(s.text);
            return w;
        }

        void draw(Ui ui, int x, int y) {
            int cx = x;
            for (Seg s : segs) {
                if (s.gradient) cx += ui.gradientText(s.text, cx, y, ui.theme.accent(), ui.theme.accent2(), s.bold);
                else cx += s.bold ? ui.textBold(s.text, cx, y, s.color) : ui.text(s.text, cx, y, s.color);
            }
        }

        private record Seg(String text, int color, boolean bold, boolean gradient) {
        }
    }
}
