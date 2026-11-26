package dev.erebus.client.hud.elements;

import dev.erebus.client.Erebus;
import dev.erebus.client.gui.Ui;
import dev.erebus.client.hud.TextHudElement;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.theme.Theme;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.List;
import java.util.Locale;

public final class InfoElement extends TextHudElement {
    private final BoolSetting fps = add(new BoolSetting("FPS", true));
    private final BoolSetting ping = add(new BoolSetting("Ping", true));
    private final BoolSetting speed = add(new BoolSetting("Speed", true));

    public InfoElement() {
        super("info", "Server info", "Frame rate, ping and movement speed.", true,
                HAnchor.RIGHT, VAnchor.BOTTOM, 4, 4, true);
    }

    @Override
    protected void buildLines(Ui ui, List<Line> out, boolean preview) {
        if (ui.mc.player == null && !preview) return;
        int accent = ui.theme.accent();
        int text = ui.theme.text();
        if (fps.get()) out.add(new Line().add("FPS ", accent).add(Integer.toString(ui.mc.getFps()), text));
        if (ping.get()) {
            int ms = -1;
            ClientPacketListener conn = ui.mc.getConnection();
            if (conn != null && ui.mc.player != null) {
                PlayerInfo info = conn.getPlayerInfo(ui.mc.player.getUUID());
                if (info != null) ms = info.getLatency();
            } else if (preview) {
                ms = 87;
            }
            if (ms >= 0) {
                int c = ms < 120 ? Theme.GOOD : ms < 250 ? Theme.WARN : Theme.BAD;
                out.add(new Line().add("Ping ", accent).add(ms + " ms", c));
            }
        }
        if (speed.get()) {
            double kmh = ui.mc.player == null ? 5.6 : Erebus.hud().speedKmh();
            out.add(new Line().add("Speed ", accent).add(String.format(Locale.ROOT, "%.1f km/h", kmh), text));
        }
    }
}
