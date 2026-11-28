package dev.erebus.client.hud.elements;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.hud.TextHudElement;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.theme.Theme;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;

import java.util.List;
import java.util.Locale;

/**
 * Where and when you are: day and time, the moon, the biome, the light at your feet (and whether monsters can spawn
 * there), and the chunk and region file you're in.
 */
public final class WorldInfoElement extends TextHudElement {
    private static final String[] MOON = {"full moon", "waning gibbous", "last quarter", "waning crescent", "new moon",
            "waxing crescent", "first quarter", "waxing gibbous"};

    private final BoolSetting time = add(new BoolSetting("Time", "Day number, time of day and the moon.", true));
    private final BoolSetting biome = add(new BoolSetting("Biome", true));
    private final BoolSetting light = add(new BoolSetting("Light", "Block and sky light at your feet, and whether monsters "
            + "can spawn there (block light 0).", true));
    private final BoolSetting chunk = add(new BoolSetting("Chunk", "The chunk, your spot in it, and the region file.", true));

    public WorldInfoElement() {
        super("worldinfo", "World info", "Day and time, moon, biome, light level (and whether monsters can spawn) and the "
                + "chunk and region you're in.", false, HAnchor.LEFT, VAnchor.TOP, 4, 40, true);
    }

    @Override
    protected void buildLines(Ui ui, List<Line> out, boolean preview) {
        LocalPlayer p = ui.mc.player;
        int a = ui.theme.accent(), t = ui.theme.text(), d = ui.theme.dim();
        if (p == null) {
            if (!preview) return;
            if (time.get()) out.add(new Line().add("Day ", a).add("132", t).add("  14:32", t).add("  waxing gibbous", d));
            if (biome.get()) out.add(new Line().add("Biome ", a).add("Dark Forest", t));
            if (light.get()) out.add(new Line().add("Light ", a).add("0", t).add(" (sky 4)", d).add("  spawnable", Theme.BAD));
            if (chunk.get()) out.add(new Line().add("Chunk ", a).add("780, -191", t).add(" [3, 12]", d).add("  r.24.-6", d));
            return;
        }
        if (time.get()) {
            long dt = p.level().getDayTime();
            long day = dt / 24000L + 1;
            long tod = (dt + 6000L) % 24000L; // 0 is 6:00
            int h = (int) (tod / 1000), m = (int) ((tod % 1000) * 60 / 1000);
            int phase = (int) ((dt / 24000L) % 8);
            out.add(new Line().add("Day ", a).add(Long.toString(day), t).add(String.format(Locale.ROOT, "  %02d:%02d", h, m), t)
                    .add("  " + MOON[phase], d));
        }
        BlockPos pos = p.blockPosition();
        if (biome.get()) {
            String b = p.level().getBiome(pos).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
            out.add(new Line().add("Biome ", a).add(pretty(b), t));
        }
        if (light.get()) {
            int bl = p.level().getBrightness(LightLayer.BLOCK, pos), sl = p.level().getBrightness(LightLayer.SKY, pos);
            Line l = new Line().add("Light ", a).add(Integer.toString(bl), t).add(" (sky " + sl + ")", d);
            // monsters need block light 0 and little enough sky light (the sky counts less at night)
            if (bl == 0) {
                int sky = sl - p.level().getSkyDarken();
                if (sky <= 7) l.add("  spawnable", Theme.BAD);
                else l.add("  spawnable at night", Theme.WARN);
            }
            out.add(l);
        }
        if (chunk.get()) {
            int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
            out.add(new Line().add("Chunk ", a).add(cx + ", " + cz, t).add(" [" + (pos.getX() & 15) + ", " + (pos.getZ() & 15) + "]", d)
                    .add("  r." + (cx >> 5) + "." + (cz >> 5), d));
        }
    }

    private static String pretty(String id) {
        StringBuilder sb = new StringBuilder();
        for (String w : id.split("_")) {
            if (w.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
