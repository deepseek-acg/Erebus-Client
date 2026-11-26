package dev.erebus.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Estimates explosion damage the way the game computes it: distance falloff times exposure (sampled rays from the
 * entity's hitbox to the blast), difficulty, armor and toughness, Resistance, and Protection / Blast Protection.
 * Ported from Mist's DamageUtil; enchantment levels are read from the armor rather than assumed.
 */
public final class ExplosionDamage {
    /** Respawn anchor explosion power. */
    public static final float ANCHOR_POWER = 5f;
    /** End crystal explosion power. */
    public static final float CRYSTAL_POWER = 6f;

    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private ExplosionDamage() {
    }

    /**
     * @param extraSolid   a block treated as solid (not placed yet), or null
     * @param ignoredSolid a block treated as air (an anchor is removed before its blast), or null
     */
    public static float damage(LivingEntity target, Vec3 center, float power, BlockPos extraSolid, BlockPos ignoredSolid) {
        return damage(target, Vec3.ZERO, center, power, extraSolid, ignoredSolid);
    }

    /** Like {@link #damage(LivingEntity, Vec3, float, BlockPos, BlockPos)} with the target moved by {@code offset} (a prediction). */
    public static float damage(LivingEntity target, Vec3 offset, Vec3 center, float power, BlockPos extraSolid, BlockPos ignoredSolid) {
        Minecraft mc = Minecraft.getInstance();
        if (target == null || mc.level == null) return 0;
        if (target instanceof Player p && (p.isCreative() || p.isSpectator())) return 0;
        double radius = power * 2;
        double dist = Math.sqrt(target.position().add(offset).distanceToSqr(center)) / radius;
        if (dist > 1) return 0;
        double exposure = exposure(center, target.getBoundingBox().move(offset), extraSolid, ignoredSolid);
        double impact = (1 - dist) * exposure;
        float damage = (float) ((impact * impact + impact) / 2.0 * 7.0 * radius + 1);
        return afterReductions(target, damage);
    }

    /** Upper bound ignoring blocks (full exposure): cheap pre-check before the ray sampling. */
    public static float maxDamage(LivingEntity target, Vec3 center, float power) {
        double radius = power * 2;
        double dist = Math.sqrt(target.distanceToSqr(center)) / radius;
        if (dist > 1) return 0;
        double impact = 1 - dist;
        return afterReductions(target, (float) ((impact * impact + impact) / 2.0 * 7.0 * radius + 1));
    }

    private static float afterReductions(LivingEntity target, float damage) {
        Minecraft mc = Minecraft.getInstance();
        if (target instanceof Player) {
            Difficulty d = mc.level.getDifficulty();
            if (d == Difficulty.PEACEFUL) return 0;
            if (d == Difficulty.EASY) damage = Math.min(damage / 2 + 1, damage);
            if (d == Difficulty.HARD) damage = damage * 3 / 2;
        }
        float armor = target.getArmorValue();
        float toughness = (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        float f = 2 + toughness / 4;
        float effective = Mth.clamp(armor - damage / f, armor * 0.2f, 20f);
        damage *= 1 - effective / 25f;

        MobEffectInstance res = target.getEffect(MobEffects.RESISTANCE);
        if (res != null) damage *= Math.max(0, 1 - (res.getAmplifier() + 1) * 0.2f);

        damage *= 1 - Math.min(20, protectionPoints(target)) / 25f;
        return Math.max(0, damage);
    }

    /** Enchantment protection points against explosions: Protection 1 per level, Blast Protection 2 per level. */
    private static int protectionPoints(LivingEntity target) {
        Minecraft mc = Minecraft.getInstance();
        Holder<Enchantment> prot = enchant(mc, Enchantments.PROTECTION);
        Holder<Enchantment> blast = enchant(mc, Enchantments.BLAST_PROTECTION);
        int points = 0;
        for (EquipmentSlot slot : ARMOR) {
            ItemStack stack = target.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            int known = 0;
            if (prot != null) known += EnchantmentHelper.getItemEnchantmentLevel(prot, stack);
            if (blast != null) known += EnchantmentHelper.getItemEnchantmentLevel(blast, stack) * 2;
            // enchanted, but the server hid which enchantment: assume Protection IV, the common case
            if (known == 0 && stack.isEnchanted()) known = 4;
            points += known;
        }
        return points;
    }

    private static Holder<Enchantment> enchant(Minecraft mc, ResourceKey<Enchantment> key) {
        try {
            return mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
        } catch (Exception e) {
            return null;
        }
    }

    /** Fraction (0..1) of sample points on the box with a clear line to the blast, like the game's exposure. */
    private static double exposure(Vec3 source, AABB box, BlockPos extraSolid, BlockPos ignoredSolid) {
        Minecraft mc = Minecraft.getInstance();
        double sx = 1.0 / ((box.maxX - box.minX) * 2 + 1);
        double sy = 1.0 / ((box.maxY - box.minY) * 2 + 1);
        double sz = 1.0 / ((box.maxZ - box.minZ) * 2 + 1);
        double ox = (1 - Math.floor(1.0 / sx) * sx) / 2;
        double oz = (1 - Math.floor(1.0 / sz) * sz) / 2;
        if (sx < 0 || sy < 0 || sz < 0) return 0;
        AABB solid = extraSolid == null ? null : new AABB(extraSolid);
        int seen = 0, total = 0;
        for (double x = 0; x <= 1; x += sx) {
            for (double y = 0; y <= 1; y += sy) {
                for (double z = 0; z <= 1; z += sz) {
                    Vec3 p = new Vec3(Mth.lerp(x, box.minX, box.maxX) + ox, Mth.lerp(y, box.minY, box.maxY), Mth.lerp(z, box.minZ, box.maxZ) + oz);
                    total++;
                    if (solid != null && solid.clip(p, source).isPresent()) continue;
                    HitResult hit = mc.level.clip(new ClipContext(p, source, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
                    if (hit.getType() == HitResult.Type.MISS) seen++;
                    else if (ignoredSolid != null && hit instanceof BlockHitResult b && b.getBlockPos().equals(ignoredSolid)) seen++;
                }
            }
        }
        return total == 0 ? 0 : (double) seen / total;
    }

    private static final class Mth {
        static float clamp(float v, float lo, float hi) {
            return Math.max(lo, Math.min(hi, v));
        }

        static double lerp(double t, double a, double b) {
            return a + (b - a) * t;
        }
    }
}
