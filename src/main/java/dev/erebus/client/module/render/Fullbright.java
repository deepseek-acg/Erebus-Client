package dev.erebus.client.module.render;

import dev.erebus.client.module.Category;
import dev.erebus.client.module.Module;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * Lights everything up, like a permanent Night Vision that only you see (ported from Nyx). It's purely client-side:
 * the effect is only added to your own client's copy of your player, and the server never hears about it. It's
 * marked with amplifier 42, so a real Night Vision potion is never touched or removed.
 */
public final class Fullbright extends Module {
    /** Amplifier that marks our own effect apart from a real potion. */
    private static final int MARK = 42;

    public Fullbright() {
        super("Fullbright", "See clearly in the dark: caves, nights and the Nether look fully lit. Client-side only.", Category.RENDER);
    }

    @Override
    protected void onDisable() {
        if (mc.player == null) return;
        MobEffectInstance nv = mc.player.getEffect(MobEffects.NIGHT_VISION);
        if (nv != null && nv.getAmplifier() == MARK) mc.player.removeEffect(MobEffects.NIGHT_VISION);
    }

    @Override
    public void onTick() {
        if (mc.player == null) return;
        MobEffectInstance nv = mc.player.getEffect(MobEffects.NIGHT_VISION);
        // kept above 10 seconds left, so it never flickers like a potion running out (and comes back after a
        // respawn or dimension change, which give the client a new player)
        if (nv == null || (nv.getAmplifier() == MARK && nv.getDuration() < 300)) {
            mc.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 600, MARK, false, false));
        }
    }
}
