package dev.erebus.client.module.combat;

import com.mojang.blaze3d.platform.InputConstants;
import dev.erebus.client.input.Crosshair;
import dev.erebus.client.module.Category;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.KeybindSetting;
import dev.erebus.client.setting.ModeSetting;
import dev.erebus.client.setting.NumberSetting;
import dev.erebus.client.setting.TextSetting;
import dev.erebus.client.util.ExplosionDamage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Auto Safe Anchor's sequence, started by itself at whichever nearby spot hurts an opponent most. Ported from
 * Mist's AnchorAura.
 *
 * <p>A spot is a candidate if an anchor there could be placed (free or replaceable, outside every entity's
 * hitbox, the opponent's included), is in reach, has a face the crosshair can actually click, and is worth it:
 * at least Min damage to them, at most Max self damage to you, and never lethal to you. Candidates are scored by
 * damage dealt minus a weighted share of the damage taken. Damage is estimated with the anchor's block treated as
 * air (it's gone when the blast happens) and without the shield the sequence adds, so it errs towards not firing
 * when you'd be standing in the blast.
 *
 * <p>Like Auto Safe Anchor, it only ever presses keys and moves the mouse.
 */
public final class AnchorAura extends AnchorSequence {
    private record Spot(BlockPos pos, float dealt, float taken, double score) {
    }

    // targeting
    private final NumberSetting targetRange;
    private final NumberSetting placeRange;
    private final ModeSetting priority;
    private final BoolSetting ignoreNaked;
    private final TextSetting ignoreNames;
    private final BoolSetting targetMobs;
    // damage
    private final NumberSetting minDamage;
    private final NumberSetting maxSelfDamage;
    private final NumberSetting selfWeight;
    private final BoolSetting lethalOverride;
    private final NumberSetting facePlaceHealth;
    private final NumberSetting facePlaceDamage;
    // pacing
    private final NumberSetting rearmDelay;
    private final NumberSetting rearmRandom;
    private final NumberSetting spotCooldown;
    private final NumberSetting searchInterval;
    private final NumberSetting restoreAfter;
    private final BoolSetting stickToSpot;
    private final NumberSetting keepAbove;
    private final NumberSetting switchGain;
    private final NumberSetting maxAngle;
    private BlockPos lastSpot;
    private int idleTicks;
    private final KeybindSetting holdKey;
    // variation
    private final NumberSetting varyChance;
    private final NumberSetting varyWindow;

    /** Recently used spots and when each may be used again. Bounded: only the last few matter. */
    private final Map<BlockPos, Long> cooldowns = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BlockPos, Long> eldest) {
            return size() > 16;
        }
    };

    private LivingEntity target;
    private int rearm;
    private int searchWait;

    public AnchorAura() {
        super("Anchor Aura", "Places, charges, shields and detonates respawn anchors by itself at whichever spot hurts the nearest "
                + "opponent most, never inside anyone's hitbox. Simulated mouse and key input only.", Category.COMBAT);
        group("Targeting");
        targetRange = add(new NumberSetting("Target range", "How far away (blocks) an opponent can be.", 6, 2, 10, 0.5));
        placeRange = add(new NumberSetting("Place range", "How far from your eyes (blocks) an anchor can go. Vanilla reach is 4.5.", 4.5, 2, 5, 0.25));
        priority = add(new ModeSetting("Priority", "Which opponent to go for when there are several.", "Closest", "Closest", "Lowest health", "Most damage"));
        ignoreNaked = add(new BoolSetting("Ignore naked", "Skip players wearing no armor.", false));
        ignoreNames = add(new TextSetting("Ignore names", "Players never to target, separated by commas.", "", 256));
        targetMobs = add(new BoolSetting("Target mobs", "Also go for hostile mobs. Handy for trying settings out on a dummy.", false));

        group("Damage");
        minDamage = add(new NumberSetting("Min damage", "Least damage (half hearts) an anchor must do to the opponent.", 4, 0, 30, 0.5));
        maxSelfDamage = add(new NumberSetting("Max self damage", "Most damage (half hearts) the blast may do to you, before the shield.", 6, 0, 30, 0.5));
        selfWeight = add(new NumberSetting("Self damage weight", "How much your own damage counts against a spot when comparing them.", 0.5, 0, 2, 0.05));
        lethalOverride = add(new BoolSetting("Lethal override", "Take a spot that would kill the opponent even if it's under Min damage.", true));
        facePlaceHealth = add(new NumberSetting("Low health below", "When the opponent has less than this health (half hearts), Min damage drops to the "
                + "value below. 0 turns this off.", 0, 0, 36, 1));
        facePlaceDamage = add(new NumberSetting("Low health min damage", "Min damage used against a low-health opponent.", 2, 0, 20, 0.5)
                .visibleWhen(() -> facePlaceHealth.get() > 0));

        group("Pacing");
        rearmDelay = add(new NumberSetting("Re-arm delay", "Ticks between one anchor and the next.", 0, 0, 40, 1));
        rearmRandom = add(new NumberSetting("Re-arm random", "Up to this many extra ticks on the re-arm delay.", 0, 0, 20, 1));
        spotCooldown = add(new NumberSetting("Spot cooldown", "Seconds before a spot that was just used is used again. 0 lets it keep using the same spot.", 0, 0, 30, 0.5));
        searchInterval = add(new NumberSetting("Search interval", "Ticks between looking for a spot while idle. Higher is lighter on your frame rate.", 1, 1, 10, 1));
        restoreAfter = add(new NumberSetting("Restore after", "Ticks with no new anchor before swapping back and looking back (as set below), "
                + "so it doesn't flick between anchors.", 10, 0, 60, 1));
        holdKey = add(new KeybindSetting("Only while holding", "If set, anchors only go down while this key is held. The module's own keybind "
                + "still turns it on and off.", KeybindSetting.NONE));

        group("Spot");
        stickToSpot = add(new BoolSetting("Stick to spot", "Keep using the same spot instead of chasing the best one every anchor. "
                + "Moves only when the spot does little damage, the opponent is far off from it, or another spot is much better.", true));
        keepAbove = add(new NumberSetting("Keep above", "Move on when the spot would do less than this damage (half hearts).", 3, 0, 20, 0.5)
                .visibleWhen(stickToSpot::get));
        switchGain = add(new NumberSetting("Switch if better by", "Move to another spot only if it does at least this much more damage.", 4, 0, 20, 0.5)
                .visibleWhen(stickToSpot::get));
        maxAngle = add(new NumberSetting("Max angle", "Move on when the spot is more than this many degrees away from the opponent, as seen from you.",
                40, 5, 120, 5).visibleWhen(stickToSpot::get));

        group("Variation");
        varyChance = add(new NumberSetting("Vary spots", "Chance to take a different spot that's nearly as good, so cycles don't all look the same.", 0, 0, 1, 0.05));
        varyWindow = add(new NumberSetting("Vary within", "How much less damage (half hearts) a varied spot may do than the best.", 1.5, 0.5, 6, 0.5)
                .visibleWhen(() -> varyChance.get() > 0));

        addSequenceSettings();
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        CrystalAura.singleplayerHint(this, targetMobs.get());
        target = null;
        rearm = 0;
        lastSpot = null;
        idleTicks = 0;
        searchWait = 0;
        cooldowns.clear();
    }

    @Override
    protected void onDisable() {
        super.onDisable();
        restoreView(); // swap back / look back now rather than after the idle wait
        target = null;
        info = null;
    }

    @Override
    protected boolean shouldStop() {
        return !holdKeyOk();
    }

    @Override
    protected boolean deferRestore() {
        return true;
    }

    @Override
    protected void onSequenceEnd(boolean completed) {
        idleTicks = 0;
        if (completed) lastSpot = anchorPos;
        else if (anchorPos != null && anchorPos.equals(lastSpot)) lastSpot = null;
        rearm = rearmDelay.intValue() + (rearmRandom.intValue() > 0 ? ThreadLocalRandom.current().nextInt(rearmRandom.intValue() + 1) : 0);
        if (!completed) rearm = Math.max(rearm, 10); // something got in the way: give the world a moment to change
    }

    private boolean holdKeyOk() {
        int key = holdKey.get();
        return key < 0 || InputConstants.isKeyDown(mc.getWindow(), key);
    }

    @Override
    protected void idleTick() {
        if (++idleTicks >= restoreAfter.intValue() && restorePending()) restoreView();
        if (rearm > 0) {
            rearm--;
            return;
        }
        if (mc.screen != null || mc.player.isDeadOrDying() || !holdKeyOk()) return;
        if (--searchWait > 0) return;
        searchWait = searchInterval.intValue();
        String why = cannotStart(true);
        if (why != null) {
            info = why;
            target = null;
            return;
        }
        info = null;
        target = findTarget();
        if (target == null) {
            info = CrystalAura.noTargetReason(targetMobs.get());
            return;
        }
        Spot spot = bestSpot(target);
        if (spot == null) {
            info = "no good spot";
            return;
        }
        if (beginAt(spot.pos(), null)) {
            cooldowns.put(spot.pos(), System.currentTimeMillis() + (long) (spotCooldown.get() * 1000));
        }
    }

    // =====================================================================================================
    // Targets
    // =====================================================================================================

    private LivingEntity findTarget() {
        Set<String> ignored = Arrays.stream(ignoreNames.get().split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        List<LivingEntity> found = new ArrayList<>();
        double range = targetRange.get();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || e == mc.player || !le.isAlive() || le.isDeadOrDying() || e.isSpectator()) continue;
            if (le instanceof Player p) {
                if (p.isCreative() || ignored.contains(p.getGameProfile().name().toLowerCase(Locale.ROOT))) continue;
            } else if (!(targetMobs.get() && le instanceof Enemy)) {
                continue;
            }
            if (mc.player.distanceTo(le) > range) continue;
            if (ignoreNaked.get() && naked(le)) continue;
            found.add(le);
        }
        if (found.isEmpty()) return null;
        Comparator<LivingEntity> byDistance = Comparator.comparingDouble(p -> mc.player.distanceToSqr(p));
        return switch (priority.get()) {
            case "Lowest health" -> found.stream().min(Comparator.<LivingEntity>comparingDouble(p -> p.getHealth() + p.getAbsorptionAmount())
                    .thenComparing(byDistance)).orElse(null);
            case "Most damage" -> found.stream().max(Comparator.comparingDouble(p -> {
                Spot s = bestSpot(p);
                return s == null ? -1 : s.score();
            })).orElse(null);
            default -> found.stream().min(byDistance).orElse(null);
        };
    }

    private static boolean naked(LivingEntity p) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (!p.getItemBySlot(slot).isEmpty()) return false;
        }
        return true;
    }

    // =====================================================================================================
    // Spots
    // =====================================================================================================

    /**
     * The best spot around {@code opponent}. The box searched is centred on them (that's where the damage is), from
     * two blocks below their feet to three above, so an opponent in a hole or on a ledge can still be reached. Each
     * position goes through cheap checks first (placeable, in reach, upper-bound damage) and only the survivors get
     * the full exposure raycast and, for the best few, the crosshair check.
     */
    private Spot bestSpot(LivingEntity opponent) {
        Vec3 eyes = mc.player.getEyePosition();
        double reach = placeRange.get();
        double reachSq = reach * reach;
        float hp = opponent.getHealth() + opponent.getAbsorptionAmount();
        float selfHp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        float min = facePlaceHealth.get() > 0 && hp < facePlaceHealth.get() ? facePlaceDamage.floatValue() : minDamage.floatValue();
        long now = System.currentTimeMillis();
        BlockPos origin = opponent.blockPosition();
        int r = (int) Math.ceil(reach);

        List<Spot> spots = new ArrayList<>();
        for (BlockPos cursor : BlockPos.betweenClosed(origin.offset(-r, -2, -r), origin.offset(r, 3, r))) {
            BlockPos pos = cursor.immutable();
            Vec3 center = Vec3.atCenterOf(pos);
            if (center.distanceToSqr(eyes) > reachSq) continue;
            if (!mc.level.isLoaded(pos)) continue;
            Long until = cooldowns.get(pos);
            if (until != null && now < until) continue;
            BlockState state = mc.level.getBlockState(pos);
            if (state.is(Blocks.RESPAWN_ANCHOR) || !canPlaceBlockAt(state)) continue;
            boolean replace = state.canBeReplaced() && !state.isAir();
            if (state.is(Blocks.SNOW) && state.getValue(BlockStateProperties.LAYERS) > 1) continue; // thick snow can't be replaced
            if (!replace && supportSides(pos).isEmpty()) continue;
            // cheap upper bounds before the exposure raycast
            float bestCase = ExplosionDamage.maxDamage(opponent, center, ExplosionDamage.ANCHOR_POWER);
            if (bestCase < min && !(lethalOverride.get() && bestCase >= hp)) continue;
            if (blockedByEntity(pos)) continue;

            float dealt = ExplosionDamage.damage(opponent, center, ExplosionDamage.ANCHOR_POWER, null, pos);
            boolean lethal = lethalOverride.get() && dealt >= hp;
            if (dealt < min && !lethal) continue;
            float taken = ExplosionDamage.maxDamage(mc.player, center, ExplosionDamage.ANCHOR_POWER) > 0
                    ? ExplosionDamage.damage(mc.player, center, ExplosionDamage.ANCHOR_POWER, null, pos) : 0;
            if (taken > maxSelfDamage.floatValue()) continue;
            if (antiSuicide.get() && taken > 0 && taken + safetyMargin.floatValue() >= selfHp) continue;
            double score = dealt - taken * selfWeight.get() + (lethal ? 100 : 0);
            spots.add(new Spot(pos, dealt, taken, score));
        }
        if (spots.isEmpty()) return null;
        spots.sort(Comparator.comparingDouble(Spot::score).reversed());

        // a few of the best, and only ones the crosshair can actually click
        List<Spot> clickable = new ArrayList<>();
        for (Spot s : spots) {
            if (clickable.size() >= 4) break;
            if (reachable(s.pos())) clickable.add(s);
            if (clickable.isEmpty() && spots.indexOf(s) >= 12) break; // nothing in reach of the crosshair near the top
        }
        if (clickable.isEmpty()) return null;
        Spot best = clickable.get(0);
        if (stickToSpot.get() && lastSpot != null) { // keep hitting the same spot unless it's clearly not working
            for (Spot s : spots) {
                if (!s.pos().equals(lastSpot)) continue;
                boolean weak = s.dealt() < keepAbove.floatValue() && s.score() < 100;
                boolean farOff = angleBetween(Vec3.atCenterOf(s.pos()), opponent.getBoundingBox().getCenter()) > maxAngle.get();
                boolean muchBetter = best.dealt() - s.dealt() >= switchGain.floatValue() || (best.score() >= 100 && s.score() < 100);
                if (!weak && !farOff && !muchBetter && (clickable.contains(s) || reachable(s.pos()))) return s;
                break;
            }
        }
        if (clickable.size() > 1 && varyChance.get() > 0 && ThreadLocalRandom.current().nextDouble() < varyChance.get()) {
            List<Spot> near = clickable.stream().filter(s -> best.dealt() - s.dealt() <= varyWindow.get()).toList();
            if (near.size() > 1) return near.get(ThreadLocalRandom.current().nextInt(near.size()));
        }
        return best;
    }

    /** Degrees between two points as seen from the eyes. */
    static double angleBetween(Vec3 a, Vec3 b) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 u = a.subtract(eye).normalize(), v = b.subtract(eye).normalize();
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, u.dot(v)))));
    }

    /** Whether the crosshair can be turned onto something that places an anchor at {@code pos}. */
    private boolean reachable(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        Crosshair.AimPoint mode = Crosshair.AimPoint.NEAREST;
        double margin = edgeMargin.get();
        if (state.canBeReplaced() && !state.isAir() && !state.getShape(mc.level, pos).isEmpty()
                && Crosshair.solve(pos, null, mode, margin) != null) return true;
        for (Direction side : supportSides(pos)) {
            if (Crosshair.solve(pos.relative(side), side.getOpposite(), mode, margin) != null) return true;
        }
        return false;
    }

    @Override
    public String suffix() {
        String stage = super.suffix();
        if (stage != null) return stage;
        return target != null ? target.getName().getString() : info; // why it's idle when there's no one
    }

}
