package dev.erebus.client.module.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who Crystal Aura and Anchor Aura may go for, and, when that's nobody, why: the nearest player (or hostile mob, with
 * Target mobs) is named with the reason it was left out, so "nobody in range" never hides an obvious opponent.
 */
final class Targets {
    private Targets() {
    }

    /** The candidates (unsorted), and when there are none, why. */
    record Found(List<LivingEntity> list, String whyNone) {
    }

    static Found find(double range, boolean mobs, boolean ignoreNaked, String ignoreNames) {
        Minecraft mc = Minecraft.getInstance();
        Set<String> ignored = Arrays.stream(ignoreNames.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        List<LivingEntity> found = new ArrayList<>();
        String nearestWhy = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || e == mc.player || !le.isAlive() || le.isDeadOrDying()) continue;
            if (!(le instanceof Player) && !(mobs && le instanceof Enemy)) continue;
            double dist = mc.player.distanceTo(le);
            String why = skipReason(le, dist, range, ignoreNaked, ignored);
            if (why == null) {
                found.add(le);
            } else if (dist < nearestDist) {
                nearestDist = dist;
                nearestWhy = why;
            }
        }
        String whyNone = null;
        if (found.isEmpty()) whyNone = nearestWhy != null ? nearestWhy : nobody(mobs);
        return new Found(found, whyNone);
    }

    /** Why this one isn't a target, or null if it is. */
    private static String skipReason(LivingEntity le, double dist, double range, boolean ignoreNaked, Set<String> ignored) {
        String name = le.getName().getString();
        if (le.isSpectator()) return name + " is spectating";
        if (le instanceof Player p) {
            if (p.isCreative()) return name + " is in creative (explosions can't hurt them)";
            if (ignored.contains(p.getGameProfile().name().toLowerCase(Locale.ROOT))) return name + " is on Ignore names";
        }
        if (ignoreNaked && naked(le)) return name + " has no armor (Ignore naked is on)";
        if (dist > range) return String.format(Locale.ROOT, "%s is %.1f blocks away (range %.1f)", name, dist, range);
        return null;
    }

    static boolean naked(LivingEntity p) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (!p.getItemBySlot(slot).isEmpty()) return false;
        }
        return true;
    }

    /**
     * When there's nobody at all. In singleplayer there are never other players, so without Target mobs it can't do
     * anything: say so rather than sit there quietly.
     */
    private static String nobody(boolean mobs) {
        Minecraft mc = Minecraft.getInstance();
        if (!mobs && mc.getSingleplayerServer() != null && mc.level.players().size() <= 1) return "no players: turn on Target mobs";
        return mobs ? "no player or hostile mob nearby" : "no player nearby";
    }

    /** A one-time hint when an aura is turned on where it can't find anyone (singleplayer, players only). */
    static void singleplayerHint(dev.erebus.client.module.Module m, boolean mobs) {
        Minecraft mc = Minecraft.getInstance();
        if (mobs || mc.getSingleplayerServer() == null || mc.level == null || mc.level.players().size() > 1) return;
        dev.erebus.client.util.Chat.info(m.name, "Only goes for other players, and there are none in singleplayer. Turn on Target mobs in its "
                + "settings to try it on hostile mobs (a zombie works well as a dummy).");
    }
}
