package dev.erebus.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Totem pops, counted per player from the entity event the server sends when one is used (the same one that plays the
 * totem animation). Counts reset when the player dies or leaves, so they're "pops this life".
 */
public final class Pops {
    private static final Map<UUID, Integer> COUNT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    private static volatile long selfPopAt;

    private Pops() {
    }

    /** Called (on the client) when an entity uses a totem. */
    public static void popped(LivingEntity e) {
        if (!(e instanceof Player p)) return;
        COUNT.merge(p.getUUID(), 1, Integer::sum);
        LAST.put(p.getUUID(), System.currentTimeMillis());
        if (p == Minecraft.getInstance().player) selfPopAt = System.currentTimeMillis();
    }

    public static int count(Player p) {
        return COUNT.getOrDefault(p.getUUID(), 0);
    }

    /** Milliseconds since this player last popped, or -1. */
    public static long sinceLast(Player p) {
        Long t = LAST.get(p.getUUID());
        return t == null ? -1 : System.currentTimeMillis() - t;
    }

    /** When you last popped (System.currentTimeMillis), or 0. */
    public static long selfPopAt() {
        return selfPopAt;
    }

    /** Forget a player's count (they died). */
    public static void reset(Player p) {
        COUNT.remove(p.getUUID());
        LAST.remove(p.getUUID());
    }

    public static void clear() {
        COUNT.clear();
        LAST.clear();
    }
}
