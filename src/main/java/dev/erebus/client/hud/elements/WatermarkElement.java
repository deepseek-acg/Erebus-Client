package dev.erebus.client.hud.elements;

import dev.erebus.client.Erebus;
import dev.erebus.client.gui.Ui;
import dev.erebus.client.hud.TextHudElement;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.TextSetting;
import net.minecraft.client.multiplayer.ServerData;

import java.util.List;

public final class WatermarkElement extends TextHudElement {
    private final TextSetting text = add(new TextSetting("Text", "Name shown in the watermark.", "Erebus", 24));
    private final BoolSetting version = add(new BoolSetting("Version", "Show the client version.", true));
    private final BoolSetting server = add(new BoolSetting("Server", "Show the server address.", true));

    public WatermarkElement() {
        super("watermark", "Watermark", "Client name with version and server.", true,
                HAnchor.LEFT, VAnchor.TOP, 4, 4, false);
    }

    @Override
    protected void buildLines(Ui ui, List<Line> out, boolean preview) {
        Line line = new Line().gradient(text.get().isBlank() ? "Erebus" : text.get(), true);
        if (version.get()) line.add(" " + Erebus.VERSION, ui.theme.dim());
        if (server.get()) {
            ServerData data = ui.mc.getCurrentServer();
            String address = data != null ? data.ip : ui.mc.hasSingleplayerServer() ? "singleplayer" : preview ? "6b6t.org" : null;
            if (address != null) line.add(" · " + address, ui.theme.dim());
        }
        out.add(line);
    }
}
