package dev.erebus.client.module.combat;

import com.mojang.blaze3d.platform.InputConstants;
import dev.erebus.client.input.Crosshair;
import dev.erebus.client.input.InputLock;
import dev.erebus.client.input.InputSim;
import dev.erebus.client.input.MouseSim;
import dev.erebus.client.module.Category;
import dev.erebus.client.module.Module;
import dev.erebus.client.module.WorldRender;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.ColorSetting;
import dev.erebus.client.setting.KeybindSetting;
import dev.erebus.client.setting.ModeSetting;
import dev.erebus.client.setting.NumberSetting;
import dev.erebus.client.setting.TextSetting;
import dev.erebus.client.util.ExplosionDamage;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Places end crystals next to opponents and breaks them, placing obsidian first only when it's worth it. Ported
 * from Mist's CrystalAura (itself from Nyx), rebuilt on simulated input: hotbar number keys, synthetic mouse
 * movement, the use key to place and the attack key to break, each only once the game's own crosshair raycast is
 * on the right block face or crystal and the camera has been still long enough for the server to have the view.
 *
 * <p>It locks onto one spot and keeps placing and breaking crystals there. The view that places a crystal on the
 * top of a block also hits the crystal standing on it, so once the camera is on the spot every place and every
 * break goes out without turning at all: a crystal per round trip to the server. It only moves to another spot
 * when the current one genuinely stops working (little damage, the opponent far off from it, unsafe, out of reach)
 * or another spot is much better, instead of chasing the best spot as the opponent moves around above it.
 *
 * <p>Obsidian is placed sparingly: an existing base wins unless a new block does clearly more damage, a new block
 * must still pay off where the opponent will be by the time the crystal is down, there's a per-window cap, and
 * since the aura sticks to a spot, one block serves many crystals.
 */
public final class CrystalAura extends Module {
    // activation
    private final ModeSetting activation;
    private final KeybindSetting holdKey;
    private final NumberSetting hitWindow;
    private final BoolSetting requireWeapon;
    private final BoolSetting pauseOnUse;
    private final BoolSetting yieldToMouse;
    private final NumberSetting yieldAmount;
    private final NumberSetting yieldPause;
    // targeting
    private final NumberSetting targetRange;
    private final NumberSetting placeRange;
    private final ModeSetting priority;
    private final NumberSetting predictTicks;
    private final BoolSetting ignoreNaked;
    private final TextSetting ignoreNames;
    private final BoolSetting targetMobs;
    // spot
    private final BoolSetting stickToSpot;
    private final NumberSetting keepAbove;
    private final NumberSetting switchGain;
    private final NumberSetting maxAngle;
    private final NumberSetting recheckInterval;
    // damage
    private final NumberSetting minDamage;
    private final NumberSetting maxSelfDamage;
    private final NumberSetting selfWeight;
    private final BoolSetting antiSuicide;
    private final NumberSetting safetyMargin;
    private final BoolSetting lethalOverride;
    private final NumberSetting lowHealth;
    private final NumberSetting lowHealthDamage;
    // obsidian
    private final ModeSetting obsidianMode;
    private final NumberSetting obsidianGain;
    private final NumberSetting obsidianLimit;
    private final NumberSetting obsidianWindow;
    private final BoolSetting avoidPath;
    private final NumberSetting pathClearance;
    // breaking
    private final ModeSetting breakMode;
    private final BoolSetting antiWeakness;
    private final NumberSetting breakAttempts;
    // timing
    private final NumberSetting placeDelay;
    private final NumberSetting breakDelay;
    private final NumberSetting obsidianDelay;
    private final NumberSetting swapDelay;
    private final NumberSetting settleTicks;
    private final NumberSetting retryTicks;
    private final NumberSetting randomExtra;
    private final ModeSetting randomMode;
    private final NumberSetting searchInterval;
    // camera
    private final NumberSetting maxTurn;
    private final NumberSetting aimSpeed;
    private final NumberSetting minSpeed;
    private final NumberSetting easing;
    private final NumberSetting curve;
    private final NumberSetting overshootChance;
    private final NumberSetting overshootAmount;
    private final ModeSetting aimPoint;
    private final NumberSetting edgeMargin;
    private final BoolSetting holdCamera;
    private final BoolSetting lookBack;
    private final NumberSetting lookBackAfter;
    private final NumberSetting lookBackSpeed;
    // items
    private final BoolSetting preferOffhand;
    private final BoolSetting swapBack;
    private final NumberSetting swapBackAfter;
    // render
    private final BoolSetting showTarget;
    private final ColorSetting baseColor;
    private final ColorSetting obsidianColor;

    private enum Stage { IDLE, OBSIDIAN, SPAM }

    private enum Action { CLICKED, AIMING, REFUSED }

    /** A place to put crystals, or (with {@code crystal} set) one standing already that's worth breaking. */
    private record Spot(BlockPos base, boolean needsObsidian, EndCrystal crystal, float dealt, float taken, double score) {
    }

    // ---- the locked spot ----
    private Stage stage = Stage.IDLE;
    private LivingEntity target;
    private BlockPos base;
    private int ticks;
    private int stageTicks;
    private int wait;
    private boolean obsidianClicked;
    private int obsidianClickTick;
    private int nextRecheck;
    private int pausedTicks;
    private int blockedTicks;
    // place/break loop
    private int placeTick = -100;
    private int placesUnanswered;
    private int crystalSeenTick = -1;
    private int crystalGoneTick = -100;
    private int crystalId = -1;
    private int hitsOnCrystal;
    private int attackTick = -100;

    // ---- idle ----
    private int idleTicks;
    private int searchWait;
    private int yieldTicks;
    private int cooldown;

    // ---- memory ----
    private final Map<BlockPos, Integer> parked = new HashMap<>();
    private final Deque<Integer> obsidianTicks = new ArrayDeque<>();
    private final Set<BlockPos> ownCrystals = new LinkedHashSet<>();
    private BlockPos lastBase;
    private LivingEntity lastHit;
    private int lastHitTick = -10_000;
    private LivingEntity pendingTarget;
    private static boolean hooked;

    // ---- input ----
    private int originalSlot = -1;
    private int ourSlot = -1;
    private boolean lookPending;
    private float startYaw;
    private float startPitch;
    private float lastYaw = Float.NaN;
    private float lastPitch = Float.NaN;
    private int stillTicks;
    private int swapTick = -100;
    private Crosshair.Aim aim;
    private Object aimKey;

    public CrystalAura() {
        super("Crystal Aura", "Places and breaks end crystals on nearby opponents, sticking to one spot and placing obsidian only when it's "
                + "worth it. Simulated mouse and key input only.", Category.COMBAT);
        group("Activation");
        activation = add(new ModeSetting("Activation", "Always: whenever there's a good spot. After a hit: only for a moment after you hit "
                + "someone with a weapon. Hold key: only while a key is held.", "Always", "Always", "After a hit", "Hold key"));
        holdKey = add(new KeybindSetting("Hold key", "Key to hold for it to run.", KeybindSetting.NONE).visibleWhen(() -> activation.is("Hold key")));
        hitWindow = add(new NumberSetting("Hit window", "How long (ticks) after a weapon hit counts as a knock-up: crystals at that player go "
                + "first, and obsidian may be placed for them.", 15, 3, 60, 1));
        requireWeapon = add(new BoolSetting("Require weapon", "Only run while you hold a sword, axe, mace, pickaxe, an end crystal or nothing, "
                + "so it never fires while you build or eat.", true));
        pauseOnUse = add(new BoolSetting("Pause on use key", "Pause while you hold the use key yourself (placing, eating, blocking).", true));
        yieldToMouse = add(new BoolSetting("Yield to mouse", "If you flick the mouse, let go of the spot and give the camera back for a moment.", true));
        yieldAmount = add(new NumberSetting("Yield above", "How far (degrees) you have to move the mouse within a moment for it to yield.", 25, 3, 90, 1)
                .visibleWhen(yieldToMouse::get));
        yieldPause = add(new NumberSetting("Yield pause", "Ticks to stay out of the way after yielding.", 6, 0, 40, 1).visibleWhen(yieldToMouse::get));

        group("Targeting");
        targetRange = add(new NumberSetting("Target range", "How far away (blocks) an opponent can be.", 8, 2, 12, 0.5));
        placeRange = add(new NumberSetting("Place range", "How far from your eyes (blocks) a crystal can be placed. Vanilla reach is 4.5.", 4.5, 2, 5, 0.25));
        priority = add(new ModeSetting("Priority", "Which opponent to go for when there are several (someone you just hit always comes first).",
                "Closest", "Closest", "Lowest health", "Most damage"));
        predictTicks = add(new NumberSetting("Predict", "Ticks ahead to extrapolate a moving opponent when choosing a spot.", 2, 0, 6, 1));
        ignoreNaked = add(new BoolSetting("Ignore naked", "Skip players wearing no armor.", false));
        ignoreNames = add(new TextSetting("Ignore names", "Players never to target, separated by commas.", "", 256));
        targetMobs = add(new BoolSetting("Target mobs", "Also go for hostile mobs. Handy for trying settings out on a dummy.", false));

        group("Spot");
        stickToSpot = add(new BoolSetting("Stick to spot", "Keep placing and breaking at one spot, so the camera doesn't have to move between "
                + "crystals. Off: go for the best spot every crystal.", true));
        keepAbove = add(new NumberSetting("Keep above", "Move on when the spot would do less than this damage (half hearts).", 4, 0, 20, 0.5)
                .visibleWhen(stickToSpot::get));
        switchGain = add(new NumberSetting("Switch if better by", "Move to another spot only if it does at least this much more damage.", 5, 0, 20, 0.5)
                .visibleWhen(stickToSpot::get));
        maxAngle = add(new NumberSetting("Max angle", "Move on when the spot is more than this many degrees away from the opponent, as seen from you.",
                45, 5, 120, 5).visibleWhen(stickToSpot::get));
        recheckInterval = add(new NumberSetting("Recheck every", "Ticks between looking for a much better spot while it's on one.", 10, 1, 40, 1)
                .visibleWhen(stickToSpot::get));

        group("Damage");
        minDamage = add(new NumberSetting("Min damage", "Least damage (half hearts) a new spot must do to the opponent.", 6, 0, 36, 0.5));
        maxSelfDamage = add(new NumberSetting("Max self damage", "Most damage (half hearts) a crystal may do to you.", 8, 0, 36, 0.5));
        selfWeight = add(new NumberSetting("Self damage weight", "How much your own damage counts against a spot when comparing them.", 0.5, 0, 2, 0.05));
        antiSuicide = add(new BoolSetting("Anti suicide", "Never place or break a crystal that could kill you.", true));
        safetyMargin = add(new NumberSetting("Safety margin", "Health (half hearts) to keep above the estimated damage.", 2, 0, 10, 0.5).visibleWhen(antiSuicide::get));
        lethalOverride = add(new BoolSetting("Lethal override", "Take a spot that would kill the opponent even if it's under Min damage.", true));
        lowHealth = add(new NumberSetting("Low health below", "When the opponent has less than this health (half hearts), Min damage drops to the "
                + "value below. 0 turns this off.", 0, 0, 36, 1));
        lowHealthDamage = add(new NumberSetting("Low health min damage", "Min damage used against a low-health opponent.", 2, 0, 20, 0.5)
                .visibleWhen(() -> lowHealth.get() > 0));

        group("Obsidian");
        obsidianMode = add(new ModeSetting("Place obsidian", "Never: only use obsidian and bedrock that's already there. After a hit: only for "
                + "someone you just knocked up. When needed: whenever no existing base is good enough.", "After a hit", "Never", "After a hit", "When needed"));
        obsidianGain = add(new NumberSetting("Min gain", "A new obsidian block is only placed if its crystal would do at least this much more damage "
                + "(half hearts) than the best existing base.", 2, 0, 20, 0.5).visibleWhen(() -> !obsidianMode.is("Never")));
        obsidianLimit = add(new NumberSetting("Obsidian limit", "Most obsidian blocks to place within the window below.", 3, 1, 10, 1)
                .visibleWhen(() -> !obsidianMode.is("Never")));
        obsidianWindow = add(new NumberSetting("Limit window", "Seconds the obsidian limit counts over.", 10, 1, 60, 1)
                .visibleWhen(() -> !obsidianMode.is("Never")));
        avoidPath = add(new BoolSetting("Avoid path", "Don't place obsidian where the opponent is about to move or land.", true)
                .visibleWhen(() -> !obsidianMode.is("Never")));
        pathClearance = add(new NumberSetting("Path clearance", "How wide (blocks) a path to keep clear.", 1, 0.5, 3, 0.25)
                .visibleWhen(() -> !obsidianMode.is("Never") && avoidPath.get()));

        group("Breaking");
        breakMode = add(new ModeSetting("Break", "Own: only crystals it placed. Any: also crystals already standing near the opponent, "
                + "if breaking them is worth it and safe.", "Any", "Own", "Any"));
        antiWeakness = add(new BoolSetting("Anti weakness", "With Weakness, switch to a sword or axe to break (a fist can't).", true));
        breakAttempts = add(new NumberSetting("Break attempts", "How many hits on one crystal before giving up on the spot.", 3, 1, 6, 1));

        group("Timing");
        placeDelay = add(new NumberSetting("Place delay", "Ticks after a crystal is gone before placing the next one.", 0, 0, 10, 1));
        breakDelay = add(new NumberSetting("Break delay", "Ticks after a crystal appears before breaking it.", 0, 0, 10, 1));
        obsidianDelay = add(new NumberSetting("Obsidian delay", "Ticks after placing obsidian before the first crystal on it.", 0, 0, 10, 1));
        swapDelay = add(new NumberSetting("Swap delay", "Ticks between switching hotbar slot and clicking. 0 clicks in the same tick as the switch.", 0, 0, 5, 1));
        settleTicks = add(new NumberSetting("Settle time", "Ticks the camera must be still before a click after it turns, so the server already "
                + "has the view. 1 is the minimum that guarantees it.", 1, 1, 5, 1));
        retryTicks = add(new NumberSetting("Retry after", "Ticks to wait for the server to answer a place or break before clicking again. "
                + "Raise it on a laggy connection.", 4, 2, 20, 1));
        randomExtra = add(new NumberSetting("Random extra", "Up to this many extra ticks on each delay.", 0, 0, 5, 1));
        randomMode = add(new ModeSetting("Random spread", "Uniform: any amount up to the maximum equally. Gaussian: usually about half.",
                "Uniform", "Uniform", "Gaussian").visibleWhen(() -> randomExtra.intValue() > 0));
        searchInterval = add(new NumberSetting("Search interval", "Ticks between looking for a spot while idle. Higher is lighter on your frame rate.", 1, 1, 10, 1));

        group("Camera");
        maxTurn = add(new NumberSetting("Max turn", "Skip new spots that need the camera to turn more than this (degrees) from where you look.", 75, 15, 180, 5));
        aimSpeed = add(new NumberSetting("Max speed", "Fastest the camera turns, in degrees per second.", 1800, 120, 3000, 30));
        minSpeed = add(new NumberSetting("Min speed", "Slowest it turns near the end of a turn, so the last degrees don't crawl.", 200, 5, 400, 5));
        easing = add(new NumberSetting("Easing", "How quickly a turn closes in. Higher snaps, lower glides.", 30, 2, 50, 1));
        curve = add(new NumberSetting("Curve", "How much the path bows sideways instead of a straight line.", 0.05, 0, 0.6, 0.05));
        overshootChance = add(new NumberSetting("Overshoot chance", "Chance a larger turn goes a little past the target and comes back.", 0, 0, 1, 0.05));
        overshootAmount = add(new NumberSetting("Overshoot amount", "How far past the target an overshoot goes, in degrees.", 1.5, 0.5, 8, 0.5)
                .visibleWhen(() -> overshootChance.get() > 0));
        aimPoint = add(new ModeSetting("Aim point", "Where on the block or crystal to aim. Nearest: the smallest turn. Center: the middle. "
                + "Random: anywhere that works.", "Nearest", "Nearest", "Center", "Random"));
        edgeMargin = add(new NumberSetting("Edge margin", "How far from a face's edges the aim point stays, as a fraction of the face.", 0.15, 0.05, 0.45, 0.01));
        holdCamera = add(new BoolSetting("Hold camera", "Ignore your own mouse while it's turning. Off: your movement adds to the turn.", false));
        lookBack = add(new BoolSetting("Look back", "Turn back to where you were looking once the fight pauses. Moving the mouse cancels it.", false));
        lookBackAfter = add(new NumberSetting("Look back after", "Ticks with no crystal before turning back.", 10, 0, 60, 1).visibleWhen(lookBack::get));
        lookBackSpeed = add(new NumberSetting("Look back speed", "Fastest the turn back goes, in degrees per second.", 600, 120, 2400, 30).visibleWhen(lookBack::get));

        group("Items");
        preferOffhand = add(new BoolSetting("Prefer offhand", "With crystals in your offhand, place them without switching away from your weapon.", true));
        swapBack = add(new BoolSetting("Swap back", "Go back to the hotbar slot you had once the fight pauses.", true));
        swapBackAfter = add(new NumberSetting("Swap back after", "Ticks with no crystal before swapping back, so it doesn't flick slots between "
                + "crystals.", 10, 0, 40, 1).visibleWhen(swapBack::get));

        group("Render");
        showTarget = add(new BoolSetting("Show target", "Outline the spot it's working on.", true));
        baseColor = add(new ColorSetting("Crystal color", 0xFFB46BFF).visibleWhen(showTarget::get));
        obsidianColor = add(new ColorSetting("Obsidian color", 0xFF6B8CFF).visibleWhen(showTarget::get));

        if (!hooked) {
            hooked = true;
            AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
                if (level.isClientSide() && player == mc.player) onAttack(entity);
                return InteractionResult.PASS;
            });
        }
    }

    // =====================================================================================================
    // Lifecycle
    // =====================================================================================================

    @Override
    protected void onEnable() {
        stage = Stage.IDLE;
        target = null;
        base = null;
        cooldown = 0;
        yieldTicks = 0;
        idleTicks = 0;
        originalSlot = -1;
        lookPending = false;
        lastYaw = Float.NaN;
        stillTicks = 0;
        lastBase = null;
        parked.clear();
        lastHit = null;
    }

    @Override
    protected void onDisable() {
        if (mc.player != null && !InputLock.heldByOther(this)) {
            if (stage != Stage.IDLE && MouseSim.active() && !MouseSim.returning()) MouseSim.stop();
            if (swapBack.get() && originalSlot >= 0 && InputSim.selectedSlot() == ourSlot) InputSim.selectSlot(originalSlot);
        }
        InputLock.release(this);
        stage = Stage.IDLE;
        target = null;
        base = null;
        originalSlot = -1;
        info = null;
    }

    private void onAttack(Entity entity) {
        if (!isEnabled() || !(entity instanceof LivingEntity le) || entity instanceof EndCrystal || !holdingWeapon(mc.player.getMainHandItem())) return;
        lastHit = le;
        lastHitTick = ticks;
    }

    private boolean knockup(LivingEntity t) {
        if (t == null || t != lastHit || !t.isAlive() || t.isDeadOrDying()) return false;
        int since = ticks - lastHitTick;
        if (since > hitWindow.intValue()) return false;
        return !t.onGround() || since <= 5; // the knockback reaches us a tick or more after the hit
    }

    @Override
    public void onPreTick() {
        if (mc.player == null || mc.level == null) {
            stage = Stage.IDLE;
            InputLock.release(this);
            return;
        }
        ticks++;
        trackStillness();
        if (stage != Stage.IDLE && InputLock.lost(this)) { // the player triggered something that took the hands over
            dropLock();
            return;
        }
        if (stage == Stage.IDLE) {
            idle();
            return;
        }
        if (yieldToMouse.get() && !MouseSim.returning() && MouseSim.userMoved(yieldAmount.get(), 150)) {
            yieldTicks = yieldPause.intValue();
            if (MouseSim.active()) MouseSim.stop();
            unlock(0);
            return;
        }
        if (mc.player.isDeadOrDying() || mc.screen != null || pauseReason() != null) { // eating, holding use...: hold the spot for a bit
            if (++pausedTicks > 40) unlock(0);
            return;
        }
        pausedTicks = 0;
        stageTicks++;
        if (wait > 0) {
            wait--;
            return;
        }
        if (stage == Stage.OBSIDIAN) obsidianStage();
        else spam();
    }

    private void trackStillness() {
        float yaw = mc.player.getYRot(), pitch = mc.player.getXRot();
        if (yaw == lastYaw && pitch == lastPitch) stillTicks++;
        else stillTicks = 0;
        lastYaw = yaw;
        lastPitch = pitch;
    }

    // =====================================================================================================
    // Idle: restore the slot and view once the fight pauses, then look for a spot
    // =====================================================================================================

    private void idle() {
        idleTicks++;
        if (!InputLock.heldByOther(this)) {
            if (originalSlot >= 0 && idleTicks >= swapBackAfter.intValue()) {
                if (swapBack.get() && InputSim.selectedSlot() == ourSlot) InputSim.selectSlot(originalSlot);
                originalSlot = -1;
            }
            if (lookPending && idleTicks >= lookBackAfter.intValue()) {
                lookPending = false;
                if (lookBack.get()) {
                    MouseSim.Profile p = profile();
                    MouseSim.returnTo(startYaw, startPitch, new MouseSim.Profile(lookBackSpeed.floatValue(), p.minSpeed(), p.ease(), p.curve(), 0, 0, 1e9f));
                }
            }
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (yieldTicks > 0) {
            yieldTicks--;
            return;
        }
        if (mc.screen != null || mc.player.isDeadOrDying() || InputLock.heldByOther(this)) return;
        String why = pauseReason();
        if (why != null) {
            info = why;
            return;
        }
        if (yieldToMouse.get() && MouseSim.userMoved(yieldAmount.get(), 150) && !MouseSim.returning()) return; // mid-flick: don't start now
        if (--searchWait > 0) return;
        searchWait = searchInterval.intValue();
        if (!haveCrystals()) {
            info = "no crystals";
            target = null;
            return;
        }
        info = null;
        Spot spot = plan();
        if (spot == null) return;
        if (!InputLock.tryClaim(this)) return;
        if (originalSlot < 0) originalSlot = InputSim.selectedSlot();
        if (!lookPending && !MouseSim.returning()) {
            startYaw = mc.player.getYRot();
            startPitch = mc.player.getXRot();
        }
        lookPending = true;
        lockOn(spot);
    }

    /** Why it shouldn't act right now, or null. */
    private String pauseReason() {
        if (mc.player.isUsingItem()) return "using an item";
        if (pauseOnUse.get() && mc.options.keyUse.isDown()) return "use key held";
        if (requireWeapon.get()) {
            ItemStack held = originalSlot >= 0 ? mc.player.getInventory().getItem(originalSlot) : mc.player.getMainHandItem();
            if (!holdingWeapon(held)) return "no weapon in hand";
        }
        switch (activation.get()) {
            case "Hold key" -> {
                int key = holdKey.get();
                if (key < 0 || !InputConstants.isKeyDown(mc.getWindow(), key)) return "waiting for key";
            }
            case "After a hit" -> {
                if (stage == Stage.IDLE && !knockup(lastHit)) return "waiting for a hit";
            }
            default -> {
            }
        }
        return null;
    }

    private static boolean holdingWeapon(ItemStack s) {
        return s.isEmpty() || s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES) || s.is(ItemTags.PICKAXES) || s.is(Items.MACE)
                || s.is(Items.END_CRYSTAL) || s.is(Items.TOTEM_OF_UNDYING);
    }

    // =====================================================================================================
    // The locked spot
    // =====================================================================================================

    private void lockOn(Spot spot) {
        target = pendingTarget;
        base = spot.base();
        lastBase = base;
        stageTicks = 0;
        pausedTicks = 0;
        blockedTicks = 0;
        placesUnanswered = 0;
        hitsOnCrystal = 0;
        crystalId = -1;
        crystalSeenTick = -1;
        crystalGoneTick = ticks - 100;
        placeTick = -100;
        attackTick = -100;
        nextRecheck = ticks + recheckInterval.intValue();
        aim = null;
        if (spot.needsObsidian()) {
            stage = Stage.OBSIDIAN;
            obsidianClicked = false;
            wait = 0;
        } else {
            stage = Stage.SPAM;
            wait = 0;
        }
    }

    /** Lets go of the spot. {@code parkTicks} > 0 also keeps it out of the search for that long. */
    private void unlock(int parkTicks) {
        if (base != null && parkTicks > 0) parked.put(base, ticks + parkTicks);
        if (MouseSim.active() && !MouseSim.returning()) MouseSim.stop();
        InputLock.release(this);
        stage = Stage.IDLE;
        base = null;
        aim = null;
        idleTicks = 0;
        cooldown = 0;
    }

    /** Another module took the input over: stop without touching the slot or the camera. */
    private void dropLock() {
        stage = Stage.IDLE;
        base = null;
        aim = null;
        originalSlot = -1;
        lookPending = false;
        idleTicks = 0;
        cooldown = 10;
    }

    private void obsidianStage() {
        BlockState state = mc.level.getBlockState(base);
        if (isCrystalBase(state)) { // it's down: crystals from here on
            stage = Stage.SPAM;
            stageTicks = 0;
            wait = obsidianClicked ? delay(obsidianDelay) : 0;
            return;
        }
        if (obsidianClicked) {
            if (stageTicks - obsidianClickTick > retryTicks.intValue() + 4) unlock(60); // never showed up
            return;
        }
        if (stageTicks > 40 || !canPlaceBlockAt(state) || blockedByEntity(base) || !crystalSpaceFree(base)) {
            unlock(40);
            return;
        }
        int slot = findSlot(Items.OBSIDIAN);
        if (slot < 0) {
            unlock(0);
            return;
        }
        Action a = Action.REFUSED;
        if (state.canBeReplaced() && !state.isAir() && !state.getShape(mc.level, base).isEmpty()) a = useOn(slot, base, null);
        if (a == Action.REFUSED) {
            for (Direction side : supportSides(base)) {
                a = useOn(slot, base.relative(side), side.getOpposite());
                if (a != Action.REFUSED) break;
            }
        }
        switch (a) {
            case REFUSED -> unlock(60);
            case CLICKED -> {
                obsidianClicked = true;
                obsidianClickTick = stageTicks;
                obsidianTicks.addLast(ticks);
            }
            default -> {
            }
        }
    }

    /**
     * Places and breaks crystals on the locked spot, one click per tick: break whatever crystal stands there, place
     * a new one when it's gone. The camera stays on the spot, so after the first turn nothing has to move.
     */
    private void spam() {
        String why = lockProblem();
        if (why != null) {
            unlock(why.equals("unsafe") || why.equals("unreachable") ? 40 : 0);
            return;
        }
        // now and then: is there a spot so much better it's worth moving?
        if (!stickToSpot.get() || ticks >= nextRecheck) {
            nextRecheck = ticks + (stickToSpot.get() ? recheckInterval.intValue() : 1);
            Spot better = plan();
            if (better != null && !better.base().equals(base) && worthSwitching(better)) {
                lockOn(better);
                return;
            }
        }
        EndCrystal crystal = findCrystalOn(base);
        if (crystal != null) {
            breakCrystal(crystal);
            return;
        }
        if (crystalSeenTick >= 0) { // it was there and now it's gone
            crystalSeenTick = -1;
            crystalGoneTick = ticks;
            placesUnanswered = 0;
            hitsOnCrystal = 0;
        }
        if (!isCrystalBase(mc.level.getBlockState(base))) {
            unlock(0);
            return;
        }
        if (ticks - crystalGoneTick < delay(placeDelay)) return;
        if (ticks - placeTick < retryTicks.intValue()) return; // waiting for the server to spawn it
        if (!crystalSpaceFree(base)) { // someone or something standing in it: wait a little, then move on
            if (++blockedTicks > 10) unlock(20);
            return;
        }
        blockedTicks = 0;
        if (placesUnanswered >= 3) { // the server keeps refusing placements here
            unlock(60);
            return;
        }
        int slot = crystalSlot();
        if (slot == -2) {
            unlock(0);
            return;
        }
        Action a = useOn(slot, base, Direction.UP);
        if (a == Action.REFUSED) unlock(40);
        else if (a == Action.CLICKED) {
            placeTick = ticks;
            placesUnanswered++;
            ownCrystals.add(base);
            trim(ownCrystals, 16);
        }
    }

    private void breakCrystal(EndCrystal crystal) {
        if (crystal.getId() != crystalId) {
            crystalId = crystal.getId();
            crystalSeenTick = ticks;
            hitsOnCrystal = 0;
            placesUnanswered = 0;
        }
        if (ticks - crystalSeenTick < breakDelay.intValue()) return;
        if (ticks - attackTick < retryTicks.intValue()) return; // waiting for the server to remove it
        if (hitsOnCrystal >= breakAttempts.intValue()) {
            unlock(60);
            return;
        }
        if (antiSuicide.get()) {
            float taken = ExplosionDamage.damage(mc.player, crystal.position(), ExplosionDamage.CRYSTAL_POWER, null, null);
            if (taken > 0 && taken + safetyMargin.floatValue() >= mc.player.getHealth() + mc.player.getAbsorptionAmount()) {
                unlock(40);
                return;
            }
        }
        int slot = -1;
        if (antiWeakness.get() && mc.player.hasEffect(MobEffects.WEAKNESS) && !mc.player.hasEffect(MobEffects.STRENGTH)) {
            ItemStack held = mc.player.getMainHandItem();
            if (!held.is(ItemTags.SWORDS) && !held.is(ItemTags.AXES)) {
                slot = findWeaponSlot();
                if (slot < 0) {
                    unlock(100);
                    return;
                }
            }
        }
        Action a = click(slot, crystal, hit -> Crosshair.hits(hit, crystal), () -> Crosshair.solve(crystal, aimMode()), mc.options.keyAttack);
        if (a == Action.REFUSED) unlock(20);
        else if (a == Action.CLICKED) {
            attackTick = ticks;
            hitsOnCrystal++;
        }
    }

    /** Why the locked spot shouldn't be used any more, or null if it's still good. */
    private String lockProblem() {
        LivingEntity t = target;
        if (t == null || !t.isAlive() || t.isDeadOrDying() || t.isSpectator() || mc.player.distanceTo(t) > targetRange.get()) return "target";
        Vec3 eyes = mc.player.getEyePosition();
        Vec3 top = new Vec3(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
        if (top.distanceTo(eyes) > placeRange.get() + 0.25 || !inBreakReach(eyes, base)) return "unreachable";
        Vec3 c = crystalCenter(base);
        float dealt = ExplosionDamage.damage(t, c, ExplosionDamage.CRYSTAL_POWER, null, null);
        float hp = t.getHealth() + t.getAbsorptionAmount();
        boolean lethal = lethalOverride.get() && dealt >= hp;
        float floor = stickToSpot.get() ? keepAbove.floatValue() : minDamageFor(t);
        if (dealt < floor && !lethal) return "weak";
        if (stickToSpot.get() && angleBetween(crystalBody(base), t.getBoundingBox().getCenter()) > maxAngle.get()) return "angle";
        float taken = ExplosionDamage.damage(mc.player, c, ExplosionDamage.CRYSTAL_POWER, null, null);
        if (taken > maxSelfDamage.floatValue()) return "unsafe";
        if (antiSuicide.get() && taken > 0 && taken + safetyMargin.floatValue() >= mc.player.getHealth() + mc.player.getAbsorptionAmount()) return "unsafe";
        return null;
    }

    private boolean worthSwitching(Spot other) {
        if (!stickToSpot.get()) return other.score() > 0;
        LivingEntity t = target;
        float current = t == null ? 0 : ExplosionDamage.damage(t, crystalCenter(base), ExplosionDamage.CRYSTAL_POWER, null, null);
        boolean otherLethal = t != null && other.score() >= 100;
        boolean currentLethal = t != null && lethalOverride.get() && current >= t.getHealth() + t.getAbsorptionAmount();
        if (otherLethal && !currentLethal) return true;
        return other.dealt() - current >= switchGain.floatValue();
    }

    private static double angleBetween(Vec3 a, Vec3 b) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 u = a.subtract(eye).normalize(), v = b.subtract(eye).normalize();
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, u.dot(v)))));
    }

    // =====================================================================================================
    // Planning
    // =====================================================================================================

    private Spot plan() {
        List<LivingEntity> targets = targets();
        if (targets.isEmpty()) {
            if (stage == Stage.IDLE) target = null;
            return null;
        }
        // someone you just knocked up comes first
        if (lastHit != null && knockup(lastHit) && targets.contains(lastHit)) {
            Spot s = planFor(lastHit);
            if (s != null) {
                pendingTarget = lastHit;
                return s;
            }
        }
        Spot best = null;
        LivingEntity bestTarget = null;
        boolean all = priority.is("Most damage");
        for (LivingEntity t : targets) {
            Spot s = planFor(t);
            if (s != null && (best == null || s.score() > best.score())) {
                best = s;
                bestTarget = t;
            }
            if (!all && s != null) break;
        }
        if (stage == Stage.IDLE) target = bestTarget != null ? bestTarget : targets.get(0);
        pendingTarget = bestTarget;
        return best;
    }

    private Spot planFor(LivingEntity t) {
        float hp = t.getHealth() + t.getAbsorptionAmount();
        float min = minDamageFor(t);
        float selfHp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        Vec3 eyes = mc.player.getEyePosition();
        Vec3 targetCenter = t.getBoundingBox().getCenter();

        // 1) a crystal already standing near them: breaking it costs one click
        Spot breakSpot = null;
        for (Entity e : mc.level.getEntities((Entity) null, t.getBoundingBox().inflate(12), e -> e instanceof EndCrystal && e.isAlive())) {
            EndCrystal c = (EndCrystal) e;
            BlockPos under = c.blockPosition().below();
            if (breakMode.is("Own") && !ownCrystals.contains(under)) continue;
            if (eyes.distanceTo(c.getBoundingBox().getCenter()) > mc.player.entityInteractionRange() + 0.5) continue;
            if (Crosshair.turnTo(c.getBoundingBox().getCenter()) > maxTurn.get()) continue;
            float dealt = ExplosionDamage.damage(t, c.position(), ExplosionDamage.CRYSTAL_POWER, null, null);
            boolean lethal = lethalOverride.get() && dealt >= hp;
            if (dealt < min && !lethal) continue;
            float taken = ExplosionDamage.damage(mc.player, c.position(), ExplosionDamage.CRYSTAL_POWER, null, null);
            if (taken > maxSelfDamage.floatValue() || (antiSuicide.get() && taken > 0 && taken + safetyMargin.floatValue() >= selfHp)) continue;
            double score = dealt - taken * selfWeight.get() + (lethal ? 100 : 0) + 1; // a free crystal beats placing one
            if ((breakSpot == null || score > breakSpot.score()) && Crosshair.solve(c, aimMode()) != null) {
                breakSpot = new Spot(under, false, c, dealt, taken, score);
            }
        }

        // 2) bases: existing obsidian/bedrock, and (if allowed) spots for a new obsidian block
        boolean obsidianAllowed = obsidianAllowed(t);
        List<Spot> existing = new ArrayList<>();
        List<Spot> fresh = new ArrayList<>();
        double reach = placeRange.get();
        Vec3 motion = new Vec3(t.getX() - t.xo, 0, t.getZ() - t.zo);
        Vec3 ahead = motion.scale(predictTicks.intValue());
        // a new block takes longer: obsidian, then the crystal, then the break
        Vec3 aheadObsidian = motion.scale(predictTicks.intValue() + 3 + obsidianDelay.intValue() + breakDelay.intValue());
        BlockPos origin = BlockPos.containing(t.position().add(ahead));
        int r = (int) Math.ceil(reach);
        double angleLimit = stickToSpot.get() ? maxAngle.get() : 180;
        for (BlockPos cursor : BlockPos.betweenClosed(origin.offset(-r, -3, -r), origin.offset(r, 1, r))) {
            BlockPos pos = cursor.immutable();
            Vec3 top = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
            if (top.distanceTo(eyes) > reach) continue;
            Integer until = parked.get(pos);
            if (until != null && ticks < until) continue;
            BlockState state = mc.level.getBlockState(pos);
            boolean isBase = isCrystalBase(state);
            if (!isBase && !obsidianAllowed) continue;
            boolean current = pos.equals(base);
            if (!current && !crystalSpaceFree(pos)) continue;
            if (!isBase) {
                if (state.is(Blocks.SNOW) && state.getValue(BlockStateProperties.LAYERS) > 1) continue;
                if (!canPlaceBlockAt(state)) continue;
                boolean replace = state.canBeReplaced() && !state.isAir() && !state.getShape(mc.level, pos).isEmpty();
                if (!replace && supportSides(pos).isEmpty()) continue;
                if (blockedByEntity(pos)) continue;
                if (avoidPath.get() && inPath(t, pos)) continue;
            }
            if (!current && Crosshair.turnTo(top) > maxTurn.get()) continue;
            if (angleBetween(crystalBody(pos), targetCenter) > angleLimit) continue; // a spot it would leave again straight away
            if (!inBreakReach(eyes, pos)) continue; // the crystal has to be hit by hand afterwards
            Vec3 c = crystalCenter(pos);
            float bound = ExplosionDamage.maxDamage(t, c, ExplosionDamage.CRYSTAL_POWER); // cheap bound before the raycasts
            if (bound < min && !(lethalOverride.get() && bound >= hp)) continue;
            BlockPos extra = isBase ? null : pos;
            float dealt = ExplosionDamage.damage(t, ahead, c, ExplosionDamage.CRYSTAL_POWER, extra, null);
            if (!isBase) { // a new block has to pay off where they'll be once the crystal is down, too
                dealt = Math.min(dealt, ExplosionDamage.damage(t, aheadObsidian, c, ExplosionDamage.CRYSTAL_POWER, extra, null));
                dealt = Math.min(dealt, ExplosionDamage.damage(t, c, ExplosionDamage.CRYSTAL_POWER, extra, null));
            }
            boolean lethal = lethalOverride.get() && dealt >= hp;
            if (dealt < min && !lethal) continue;
            float taken = ExplosionDamage.maxDamage(mc.player, c, ExplosionDamage.CRYSTAL_POWER) > 0
                    ? ExplosionDamage.damage(mc.player, c, ExplosionDamage.CRYSTAL_POWER, extra, null) : 0;
            if (taken > maxSelfDamage.floatValue()) continue;
            if (antiSuicide.get() && taken > 0 && taken + safetyMargin.floatValue() >= selfHp) continue;
            double score = dealt - taken * selfWeight.get() - Crosshair.turnTo(top) * 0.004 + (lethal ? 100 : 0);
            (isBase ? existing : fresh).add(new Spot(pos, !isBase, null, dealt, taken, score));
        }
        Spot bestExisting = pick(existing);
        Spot bestFresh = null;
        if (!fresh.isEmpty() && findSlot(Items.OBSIDIAN) >= 0) {
            fresh.sort(Comparator.comparingDouble(Spot::score).reversed());
            for (Spot s : fresh) {
                // only worth a block if it clearly beats what's already there
                if (bestExisting != null && s.dealt() < bestExisting.dealt() + obsidianGain.floatValue() && s.score() < 100) break;
                if (obsidianReachable(s.base())) {
                    bestFresh = s;
                    break;
                }
            }
        }
        Spot best = bestFresh != null ? bestFresh : bestExisting;
        if (breakSpot != null && (best == null || breakSpot.score() >= best.score() - 1)) return breakSpot;
        return best;
    }

    /** The best of these whose top face the crosshair can actually reach, staying on the last base when it's about as good. */
    private Spot pick(List<Spot> spots) {
        if (spots.isEmpty()) return null;
        spots.sort(Comparator.comparingDouble(Spot::score).reversed());
        double top = spots.get(0).score();
        if (lastBase != null) {
            for (Spot s : spots) {
                if (s.base().equals(lastBase) && s.score() >= top - 1 && Crosshair.solve(s.base(), Direction.UP, aimMode(), edgeMargin.get()) != null) return s;
            }
        }
        int checked = 0;
        for (Spot s : spots) {
            if (checked++ >= 8) break;
            if (Crosshair.solve(s.base(), Direction.UP, aimMode(), edgeMargin.get()) != null) return s;
        }
        return null;
    }

    private boolean obsidianReachable(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.canBeReplaced() && !state.isAir() && !state.getShape(mc.level, pos).isEmpty()
                && Crosshair.solve(pos, null, aimMode(), edgeMargin.get()) != null) return true;
        for (Direction side : supportSides(pos)) {
            if (Crosshair.solve(pos.relative(side), side.getOpposite(), aimMode(), edgeMargin.get()) != null) return true;
        }
        return false;
    }

    /** Whether a crystal standing on {@code base} would be within the crosshair's entity reach. */
    private boolean inBreakReach(Vec3 eyes, BlockPos base) {
        AABB box = new AABB(base.getX() - 0.5, base.getY() + 1, base.getZ() - 0.5, base.getX() + 1.5, base.getY() + 3, base.getZ() + 1.5);
        double dx = Math.max(Math.max(box.minX - eyes.x, 0), eyes.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - eyes.y, 0), eyes.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - eyes.z, 0), eyes.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz) < mc.player.entityInteractionRange() - 0.1;
    }

    private boolean obsidianAllowed(LivingEntity t) {
        if (obsidianMode.is("Never")) return false;
        if (obsidianMode.is("After a hit") && !knockup(t)) return false;
        int window = obsidianWindow.intValue() * 20;
        while (!obsidianTicks.isEmpty() && ticks - obsidianTicks.peekFirst() > window) obsidianTicks.pollFirst();
        return obsidianTicks.size() < obsidianLimit.intValue();
    }

    private float minDamageFor(LivingEntity t) {
        float hp = t.getHealth() + t.getAbsorptionAmount();
        return lowHealth.get() > 0 && hp < lowHealth.get() ? lowHealthDamage.floatValue() : minDamage.floatValue();
    }

    private List<LivingEntity> targets() {
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
        Comparator<LivingEntity> byDistance = Comparator.comparingDouble(p -> mc.player.distanceToSqr(p));
        if (priority.is("Lowest health")) {
            found.sort(Comparator.<LivingEntity>comparingDouble(p -> p.getHealth() + p.getAbsorptionAmount()).thenComparing(byDistance));
        } else {
            found.sort(byDistance);
        }
        // stay on the opponent we're fighting if they're still a candidate
        if (target != null && found.remove(target) && !priority.is("Most damage")) found.add(0, target);
        return found;
    }

    private static boolean naked(LivingEntity p) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (!p.getItemBySlot(slot).isEmpty()) return false;
        }
        return true;
    }

    /** Whether a block at {@code pos} is where the opponent is about to move (or land), from their last movement. */
    private boolean inPath(LivingEntity t, BlockPos pos) {
        double rx = pos.getX() + 0.5 - t.getX();
        double rz = pos.getZ() + 0.5 - t.getZ();
        double clearance = pathClearance.get();
        if (rx * rx + rz * rz < clearance * clearance) return true;
        double vx = t.getX() - t.xo, vz = t.getZ() - t.zo;
        double speed = Math.hypot(vx, vz);
        if (speed < 0.05) return false;
        double dx = vx / speed, dz = vz / speed;
        double along = rx * dx + rz * dz;
        double across = Math.abs(rx * dz - rz * dx);
        return along > -0.5 && along < speed * 10 + 1 && across < clearance;
    }

    // =====================================================================================================
    // Clicks: a slot key, a mouse turn, and the use or attack key
    // =====================================================================================================

    private Action useOn(int slot, BlockPos block, Direction face) {
        return click(slot, block.asLong() * 7 + (face == null ? 6 : face.ordinal()),
                hit -> Crosshair.hits(hit, block, face), () -> Crosshair.solve(block, face, aimMode(), edgeMargin.get()), mc.options.keyUse);
    }

    /**
     * Selects {@code slot} (unless it's -1), turns the camera until the predicted crosshair satisfies {@code onTarget},
     * and presses {@code key} once the camera has been still for the settle time and the slot has been held for the
     * swap delay. When the crosshair is already on target and the camera hasn't moved, the click goes out at once.
     */
    private Action click(int slot, Object key, Predicate<HitResult> onTarget, Supplier<Crosshair.Aim> solver, KeyMapping keyMapping) {
        if (mc.player.isUsingItem()) return Action.AIMING;
        if (slot >= 0 && InputSim.selectedSlot() != slot) {
            InputSim.selectSlot(slot);
            ourSlot = slot;
            swapTick = ticks;
        }
        boolean onIt = onTarget.test(Crosshair.current());
        if (!onIt || MouseSim.overshooting()) {
            if (aim == null || !key.equals(aimKey) || !onTarget.test(Crosshair.predict(aim.yaw(), aim.pitch()))) {
                aim = solver.get();
                aimKey = key;
            }
            if (aim == null) return onIt ? Action.AIMING : Action.REFUSED;
            MouseSim.aim(aim.yaw(), aim.pitch(), profile(), holdCamera.get());
            return Action.AIMING;
        }
        if (stillTicks < settleTicks.intValue()) { // just arrived: hold it here until the server has this view
            MouseSim.aim(mc.player.getYRot(), mc.player.getXRot(), profile(), true);
            return Action.AIMING;
        }
        if (slot >= 0 && swapDelay.intValue() > 0 && ticks - swapTick < swapDelay.intValue()) return Action.AIMING;
        if (!InputSim.click(keyMapping)) return Action.REFUSED;
        if (MouseSim.active() && !MouseSim.returning()) MouseSim.stop(); // the mouse is the player's again
        aim = null;
        return Action.CLICKED;
    }

    private MouseSim.Profile profile() {
        return new MouseSim.Profile(aimSpeed.floatValue(), minSpeed.floatValue(), easing.floatValue(), curve.floatValue(),
                overshootChance.floatValue(), overshootAmount.floatValue(), 10f);
    }

    private Crosshair.AimPoint aimMode() {
        return switch (aimPoint.get()) {
            case "Center" -> Crosshair.AimPoint.CENTER;
            case "Random" -> Crosshair.AimPoint.RANDOM;
            default -> Crosshair.AimPoint.NEAREST;
        };
    }

    private int delay(NumberSetting base) {
        int extra = randomExtra.intValue();
        if (extra <= 0) return base.intValue();
        int add = randomMode.is("Gaussian")
                ? (int) Math.round(Math.max(0, Math.min(extra, extra / 2.0 + ThreadLocalRandom.current().nextGaussian() * extra / 4.0)))
                : ThreadLocalRandom.current().nextInt(extra + 1);
        return base.intValue() + add;
    }

    // =====================================================================================================
    // Items
    // =====================================================================================================

    private boolean haveCrystals() {
        return mc.player.getOffhandItem().is(Items.END_CRYSTAL) || findSlot(Items.END_CRYSTAL) >= 0;
    }

    /**
     * The slot to place a crystal from: -1 keeps the current slot (crystals in the offhand and a main-hand item that
     * does nothing on a block), a hotbar slot otherwise, -2 when there are none.
     */
    private int crystalSlot() {
        boolean offhand = mc.player.getOffhandItem().is(Items.END_CRYSTAL);
        if (offhand && preferOffhand.get()) {
            if (passive(mc.player.getMainHandItem())) return -1;
            for (int i = 0; i < 9; i++) {
                if (passive(mc.player.getInventory().getItem(i))) return i;
            }
        }
        if (mc.player.getMainHandItem().is(Items.END_CRYSTAL)) return -1;
        int slot = findSlot(Items.END_CRYSTAL);
        if (slot >= 0) return slot;
        if (offhand) {
            for (int i = 0; i < 9; i++) {
                if (passive(mc.player.getInventory().getItem(i))) return i;
            }
        }
        return -2;
    }

    /** Items whose right-click on obsidian does nothing, so the click falls through to the offhand crystal. */
    private static boolean passive(ItemStack s) {
        return s.isEmpty() || s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES) || s.is(ItemTags.PICKAXES) || s.is(ItemTags.SHOVELS)
                || s.is(ItemTags.HOES) || s.is(Items.MACE) || s.is(Items.TOTEM_OF_UNDYING);
    }

    private int findSlot(net.minecraft.world.item.Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    private int findWeaponSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES)) return i;
        }
        return -1;
    }

    // =====================================================================================================
    // World helpers
    // =====================================================================================================

    private static boolean isCrystalBase(BlockState state) {
        return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
    }

    private static Vec3 crystalCenter(BlockPos base) {
        return new Vec3(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
    }

    /** The middle of a crystal standing on {@code base}, which is what "how far off from the opponent" is measured to. */
    private static Vec3 crystalBody(BlockPos base) {
        return new Vec3(base.getX() + 0.5, base.getY() + 1.5, base.getZ() + 0.5);
    }

    private static boolean canPlaceBlockAt(BlockState state) {
        return state.isAir() || (state.canBeReplaced() && state.getFluidState().isEmpty());
    }

    /** The two blocks above a base are empty and nothing stands in them (the game's own check for a crystal). */
    private boolean crystalSpaceFree(BlockPos base) {
        BlockPos above = base.above();
        if (!mc.level.isEmptyBlock(above)) return false;
        AABB box = new AABB(above.getX(), above.getY(), above.getZ(), above.getX() + 1, above.getY() + 2, above.getZ() + 1);
        return mc.level.getEntities((Entity) null, box, Entity::isAlive).isEmpty();
    }

    private EndCrystal findCrystalOn(BlockPos base) {
        if (base == null) return null;
        BlockPos above = base.above();
        AABB box = new AABB(above.getX(), above.getY(), above.getZ(), above.getX() + 1, above.getY() + 2, above.getZ() + 1);
        for (Entity e : mc.level.getEntities((Entity) null, box, e -> e instanceof EndCrystal && e.isAlive())) return (EndCrystal) e;
        return null;
    }

    private boolean blockedByEntity(BlockPos pos) {
        AABB box = new AABB(pos);
        for (Entity e : mc.level.getEntities((Entity) null, box.inflate(1.0),
                entity -> entity.isAlive() && !entity.isSpectator() && (entity instanceof LivingEntity || entity instanceof EndCrystal))) {
            if (e.getBoundingBox().intersects(box)) return true;
        }
        return false;
    }

    /** Solid neighbours to place obsidian against, nearest the eye first, skipping blocks whose right-click does something else. */
    private List<Direction> supportSides(BlockPos pos) {
        Vec3 eyes = mc.player.getEyePosition();
        List<Direction> out = new ArrayList<>();
        for (Direction side : Direction.values()) {
            BlockState state = mc.level.getBlockState(pos.relative(side));
            if (state.isAir() || state.canBeReplaced() || !state.getFluidState().isEmpty() || AnchorSequence.isClickable(state)) continue;
            out.add(side);
        }
        out.sort(Comparator.comparingDouble(d -> eyes.distanceToSqr(Vec3.atCenterOf(pos.relative(d)))));
        return out;
    }

    private static <T> void trim(Set<T> set, int max) {
        var it = set.iterator();
        while (set.size() > max && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    // =====================================================================================================
    // Render
    // =====================================================================================================

    @Override
    public void onRenderWorld(WorldRender ctx) {
        if (!showTarget.get() || stage == Stage.IDLE || base == null) return;
        int c = stage == Stage.OBSIDIAN ? obsidianColor.get() : baseColor.get();
        Gizmos.cuboid(new AABB(base), GizmoStyle.strokeAndFill(c, 2f, (0x30 << 24) | (c & 0xFFFFFF))).setAlwaysOnTop();
    }

    @Override
    public String suffix() {
        return target == null ? null : target.getName().getString();
    }
}
