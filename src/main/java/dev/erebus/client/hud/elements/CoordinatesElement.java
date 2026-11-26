package dev.erebus.client.hud.elements;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.hud.TextHudElement;
import dev.erebus.client.setting.BoolSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Locale;

public final class CoordinatesElement extends TextHudElement {
    private final BoolSetting otherDimension = add(new BoolSetting("Other dimension",
            "Also show the matching Nether or Overworld coordinates.", true));
    private final BoolSetting facing = add(new BoolSetting("Facing", "Show the direction you are facing.", true));

    public CoordinatesElement() {
        super("coordinates", "Coordinates", "Your position, the matching coordinates in the other dimension, and facing.",
                true, HAnchor.LEFT, VAnchor.BOTTOM, 4, 4, true);
    }

    @Override
    protected void buildLines(Ui ui, List<Line> out, boolean preview) {
        LocalPlayer p = ui.mc.player;
        int accent = ui.theme.accent();
        int text = ui.theme.text();
        int dim = ui.theme.dim();
        if (p == null) {
            if (!preview) return;
            out.add(new Line().add("XYZ ", accent).add("12480", text).add(" / ", dim).add("118", text).add(" / ", dim).add("-3051", text));
            if (otherDimension.get()) out.add(new Line().add("Nether ", accent).add("1560", text).add(" / ", dim).add("-381", text));
            if (facing.get()) out.add(new Line().add("Facing ", accent).add("North", text).add(" [-Z]", dim));
            return;
        }
        int x = (int) Math.floor(p.getX());
        int y = (int) Math.floor(p.getY());
        int z = (int) Math.floor(p.getZ());
        out.add(new Line().add("XYZ ", accent).add(Integer.toString(x), text).add(" / ", dim)
                .add(Integer.toString(y), text).add(" / ", dim).add(Integer.toString(z), text));
        if (otherDimension.get()) {
            boolean nether = p.level().dimension() == Level.NETHER;
            boolean overworld = p.level().dimension() == Level.OVERWORLD;
            if (nether || overworld) {
                double f = nether ? 8.0 : 0.125;
                out.add(new Line().add(nether ? "Overworld " : "Nether ", accent)
                        .add(Integer.toString((int) Math.floor(p.getX() * f)), text).add(" / ", dim)
                        .add(Integer.toString((int) Math.floor(p.getZ() * f)), text));
            }
        }
        if (facing.get()) {
            Direction d = p.getDirection();
            String axis = switch (d) {
                case NORTH -> "-Z";
                case SOUTH -> "+Z";
                case EAST -> "+X";
                case WEST -> "-X";
                default -> "";
            };
            String name = d.getName();
            name = name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
            out.add(new Line().add("Facing ", accent).add(name, text).add(" [" + axis + "]", dim));
        }
    }
}
