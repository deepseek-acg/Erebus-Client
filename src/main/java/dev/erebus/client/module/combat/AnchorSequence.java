package dev.erebus.client.module.combat;

import com.mojang.blaze3d.platform.InputConstants;
import dev.erebus.client.input.Crosshair;
import dev.erebus.client.input.InputLock;
import dev.erebus.client.input.InputSim;
import dev.erebus.client.input.MouseSim;
import dev.erebus.client.module.Category;
import dev.erebus.client.module.Module;
import dev.erebus.client.module.WorldRender;
import dev.erebus.client.notify.Notifications;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.ColorSetting;
import dev.erebus.client.setting.ModeSetting;
import dev.erebus.client.setting.NumberSetting;
import dev.erebus.client.util.ExplosionDamage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CartographyTableBlock;
import net.minecraft.world.level.block.CaveVinesBlock;
import net.minecraft.world.level.block.CaveVinesPlantBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The respawn anchor sequence shared by Auto Safe Anchor and Anchor Aura: place an anchor, charge it with
 * glowstone, wall the blast off with a shield block, detonate, check it went off. Ported from Mist, stage for stage.
 *
 * <p>Everything is simulated input, never hand-built interactions or packets: the hotbar slot is picked with its
 * number key, the camera turns through synthetic mouse movement ({@link MouseSim}), crouching is the sneak key,
 * and every click is the use key on whatever the game's own crosshair hits. Before each click the crosshair is
 * predicted with the game's raycast ({@link Crosshair}), so snow layers, plants, berry bushes or entities in the
 * way are aimed around rather than clicked, and a click only goes out once the camera has been still long enough
 * that the server already has the view.
 */
public abstract class AnchorSequence extends Module {
    // timing
    protected NumberSetting startDelay;
    protected NumberSetting placeDelay;
    protected NumberSetting chargeDelay;
    protected NumberSetting shieldDelay;
    protected NumberSetting noShieldDelay;
    protected NumberSetting swapDelay;
    protected NumberSetting settleTicks;
    protected NumberSetting sneakDelay;
    protected NumberSetting standDelay;
    protected NumberSetting randomExtra;
    protected ModeSetting randomMode;
    protected NumberSetting verifyDelay;
    protected NumberSetting chargeRetries;
    protected NumberSetting detonateRetries;
    protected NumberSetting timeout;
    // camera
    protected NumberSetting aimSpeed;
    protected NumberSetting minSpeed;
    protected NumberSetting easing;
    protected NumberSetting curve;
    protected NumberSetting overshootChance;
    protected NumberSetting overshootAmount;
    protected ModeSetting aimPoint;
    protected NumberSetting edgeMargin;
    protected BoolSetting holdCamera;
    protected BoolSetting lookBack;
    protected NumberSetting lookBackDelay;
    protected NumberSetting lookBackSpeed;
    // shield
    protected ModeSetting shieldMode;
    protected NumberSetting shieldThreshold;
    protected ModeSetting shieldBlock;
    protected BoolSetting crouchPlace;
    protected NumberSetting shieldRange;
    protected NumberSetting shieldRetries;
    protected BoolSetting detonateUnshielded;
    // items
    protected ModeSetting detonateWith;
    protected NumberSetting detonateSlot;
    protected BoolSetting swapBack;
    // safety
    protected BoolSetting antiSuicide;
    protected NumberSetting safetyMargin;
    protected ModeSetting usingItem;
    protected BoolSetting onlyOnGround;
    protected BoolSetting warnings;
    // render
    protected BoolSetting showTarget;
    protected ColorSetting anchorColor;
    protected ColorSetting shieldColor;

    protected enum Stage { PLACE, CHARGE, SHIELD, SNEAK_PLACE, UNSNEAK, SHIELD_CHECK, DETONATE, VERIFY, DONE }

    /** What a stage's click managed: sent, still turning or waiting, or impossible. */
    protected enum Action { CLICKED, AIMING, REFUSED }

    private record ShieldPlan(BlockPos pos, Direction supportSide, boolean needsSneak, boolean replace) {
    }

    // ---- sequence state ----
    protected Stage stage;
    protected BlockPos anchorPos;
    private BlockHitResult lookHit;
    private ShieldPlan pendingShield;
    private int wait;
    protected int ticksRunning;
    private boolean actedThisTick;
    private boolean pressedSneak;
    private boolean forcedStand;
    private int standTick = -100;
    private int sneakTick = -100;
    private int swapTick = -100;
    private int chargeAttempts;
    private int detonateAttempts;
    private int placeChecks;
    private boolean chargeClicked;
    private BlockPos chargeFacePos;
    private boolean glowstoneBeforeCharge;
    private BlockPos shieldTarget;
    private int shieldAttempts;
    private int shieldClickTick = -1;
    private int shieldCheckWaits;
    private final Set<BlockPos> failedShields = new HashSet<>();

    // ---- input state ----
    private int originalSlot = -1;
    private float startYaw;
    private float startPitch;
    private float lastYaw = Float.NaN;
    private float lastPitch = Float.NaN;
    private int stillTicks;
    private Crosshair.Aim aim;
    private BlockPos aimBlock;
    private Direction aimFace;

    protected AnchorSequence(String name, String description, Category category) {
        super(name, description, category);
    }

    /** Adds the sequence's settings. Subclasses call this where they want them listed. */
    protected final void addSequenceSettings() {
        group("Timing");
        startDelay = add(new NumberSetting("Start delay", "Ticks to wait before the first action.", 0, 0, 20, 1));
        placeDelay = add(new NumberSetting("Place delay", "Ticks to wait after placing the anchor.", 1, 0, 20, 1));
        chargeDelay = add(new NumberSetting("Charge delay", "Ticks to wait after charging it.", 1, 0, 20, 1));
        shieldDelay = add(new NumberSetting("Shield delay", "Ticks to wait after the shield is down, before detonating.", 1, 0, 20, 1));
        noShieldDelay = add(new NumberSetting("No-shield delay", "Ticks to wait before detonating when no shield is placed.", 0, 0, 20, 1));
        swapDelay = add(new NumberSetting("Swap delay", "Ticks between switching hotbar slot and clicking. 0 clicks in the same tick as the switch.", 1, 0, 10, 1));
        settleTicks = add(new NumberSetting("Settle time", "Ticks the camera must be still before a click, so the server already has the view. "
                + "1 is the minimum that guarantees it.", 1, 1, 10, 1));
        sneakDelay = add(new NumberSetting("Crouch delay", "Ticks crouched before a crouch-place click.", 1, 1, 10, 1));
        standDelay = add(new NumberSetting("Stand delay", "Ticks standing up before charging or detonating (a crouched click wouldn't use the anchor).", 1, 1, 10, 1));
        randomExtra = add(new NumberSetting("Random extra", "Up to this many extra ticks on each delay.", 1, 0, 10, 1));
        randomMode = add(new ModeSetting("Random spread", "Uniform: any amount up to the maximum equally. Gaussian: usually about half, sometimes more or less.",
                "Uniform", "Uniform", "Gaussian").visibleWhen(() -> randomExtra.intValue() > 0));
        verifyDelay = add(new NumberSetting("Detonate check", "Ticks after detonating before checking it went off (and trying again).", 4, 2, 20, 1));
        chargeRetries = add(new NumberSetting("Charge attempts", "How many times to try charging before giving up.", 3, 1, 6, 1));
        detonateRetries = add(new NumberSetting("Detonate attempts", "How many detonate clicks before giving up.", 3, 1, 6, 1));
        timeout = add(new NumberSetting("Timeout", "Give up if a sequence takes longer than this many ticks.", 80, 20, 200, 5));

        group("Camera");
        aimSpeed = add(new NumberSetting("Max speed", "Fastest the camera turns, in degrees per second.", 900, 120, 2400, 30));
        minSpeed = add(new NumberSetting("Min speed", "Slowest it turns near the end of a turn, so the last few degrees don't crawl.", 25, 5, 200, 5));
        easing = add(new NumberSetting("Easing", "How quickly a turn closes in. Higher snaps, lower glides.", 14, 2, 40, 1));
        curve = add(new NumberSetting("Curve", "How much the path bows sideways instead of a straight line, like a hand moving a mouse.", 0.15, 0, 0.6, 0.05));
        overshootChance = add(new NumberSetting("Overshoot chance", "Chance a larger turn goes a little past the target and comes back.", 0, 0, 1, 0.05));
        overshootAmount = add(new NumberSetting("Overshoot amount", "How far past the target an overshoot goes, in degrees.", 1.5, 0.5, 8, 0.5)
                .visibleWhen(() -> overshootChance.get() > 0));
        aimPoint = add(new ModeSetting("Aim point", "Where on a block face to aim. Nearest: the smallest turn. Center: the middle of the face. "
                + "Random: anywhere that works.", "Nearest", "Nearest", "Center", "Random"));
        edgeMargin = add(new NumberSetting("Edge margin", "How far from a face's edges the aim point stays, as a fraction of the face.", 0.12, 0.05, 0.45, 0.01));
        holdCamera = add(new BoolSetting("Hold camera", "Ignore your own mouse while it's turning, so it doesn't have to fight you.", true));
        lookBack = add(new BoolSetting("Look back", "Turn back to where you were looking when it's done. Moving the mouse cancels it.", true));
        lookBackDelay = add(new NumberSetting("Look back delay", "Ticks to wait before turning back.", 0, 0, 40, 1).visibleWhen(lookBack::get));
        lookBackSpeed = add(new NumberSetting("Look back speed", "Fastest the turn back goes, in degrees per second.", 600, 120, 2400, 30).visibleWhen(lookBack::get));

        group("Shield");
        shieldMode = add(new ModeSetting("Shield", "Always: always try to wall off the blast. When exposed: only when the blast would reach you. "
                + "Never: detonate straight after charging.", "Always", "Always", "When exposed", "Never"));
        shieldThreshold = add(new NumberSetting("Exposed above", "With Shield on When exposed, shield only if the blast would do more than this much damage to you.",
                0.5, 0, 20, 0.5).visibleWhen(() -> shieldMode.is("When exposed")));
        shieldBlock = add(new ModeSetting("Shield block", "What to shield with. Obsidian stays after the blast; glowstone doesn't. "
                + "Any solid uses the first full block in your hotbar.", "Glowstone", "Glowstone", "Obsidian", "Any solid")
                .visibleWhen(() -> !shieldMode.is("Never")));
        crouchPlace = add(new BoolSetting("Crouch place", "If the anchor is the only thing to place the shield against, crouch so the click places "
                + "the block instead of using the anchor.", true).visibleWhen(() -> !shieldMode.is("Never")));
        shieldRange = add(new NumberSetting("Shield search", "How far from the anchor (blocks) to look for a shield spot between it and you.", 3, 1.5, 4.5, 0.5)
                .visibleWhen(() -> !shieldMode.is("Never")));
        shieldRetries = add(new NumberSetting("Shield retries", "How many more shield spots to try if a placement doesn't land.", 2, 0, 5, 1)
                .visibleWhen(() -> !shieldMode.is("Never")));
        detonateUnshielded = add(new BoolSetting("Detonate if shield fails", "If a shield spot existed but the block never went down, detonate anyway. "
                + "Off: cancel rather than take the blast.", false).visibleWhen(() -> !shieldMode.is("Never")));

        group("Items");
        detonateWith = add(new ModeSetting("Detonate with", "Empty slot: an empty hotbar slot, or a safe item if there's none. Safe item: an item "
                + "that won't place or be used. Slot: a hotbar slot you choose.", "Empty slot", "Empty slot", "Safe item", "Slot"));
        detonateSlot = add(new NumberSetting("Detonate slot", "Hotbar slot (1-9) to detonate with.", 9, 1, 9, 1).visibleWhen(() -> detonateWith.is("Slot")));
        swapBack = add(new BoolSetting("Swap back", "Go back to the hotbar slot you had before.", true));

        group("Safety");
        antiSuicide = add(new BoolSetting("Anti suicide", "Cancel before detonating if the blast could kill you.", true));
        safetyMargin = add(new NumberSetting("Safety margin", "Health (half hearts) to keep above the estimated blast damage.", 2, 0, 10, 0.5)
                .visibleWhen(antiSuicide::get));
        usingItem = add(new ModeSetting("While using an item", "What to do if you're eating, drinking or blocking: wait until you stop, or cancel.",
                "Wait", "Wait", "Cancel"));
        onlyOnGround = add(new BoolSetting("Only on ground", "Don't start while you're in the air.", false));
        warnings = add(new BoolSetting("Warnings", "Show a notification when a sequence can't start or gets cancelled, and why.", true));

        group("Render");
        showTarget = add(new BoolSetting("Show target", "Outline the anchor and shield spots while a sequence runs.", true));
        anchorColor = add(new ColorSetting("Anchor color", 0xFFFF6B35).visibleWhen(showTarget::get));
        shieldColor = add(new ColorSetting("Shield color", 0xFFFFD24A).visibleWhen(showTarget::get));
    }

    // =====================================================================================================
    // Hooks for subclasses
    // =====================================================================================================

    /** Called every pre-tick while no sequence is running. */
    protected abstract void idleTick();

    /** Called when a sequence ends; {@code completed} is false when it was cancelled. */
    protected void onSequenceEnd(boolean completed) {
    }

    /** True if the sequence should be dropped right now (e.g. the hold key was let go). */
    protected boolean shouldStop() {
        return false;
    }

    // =====================================================================================================
    // Lifecycle
    // =====================================================================================================

    @Override
    protected void onEnable() {
        stillTicks = 0;
        lastYaw = Float.NaN;
        stage = null;
    }

    @Override
    protected void onDisable() {
        if (sequenceActive()) endSequence(false);
    }

    protected final void resetSequence() {
        releaseSneak();
        stage = null;
        anchorPos = null;
        lookHit = null;
        pendingShield = null;
        wait = 0;
        ticksRunning = 0;
        pressedSneak = false;
        forcedStand = false;
        standTick = -100;
        sneakTick = -100;
        swapTick = -100;
        chargeAttempts = 0;
        detonateAttempts = 0;
        placeChecks = 0;
        chargeClicked = false;
        chargeFacePos = null;
        shieldTarget = null;
        shieldAttempts = 0;
        shieldClickTick = -1;
        shieldCheckWaits = 0;
        failedShields.clear();
        aim = null;
    }

    public final boolean sequenceActive() {
        return stage != null && stage != Stage.DONE;
    }

    @Override
    public void onPreTick() {
        if (mc.player == null || mc.level == null) {
            if (sequenceActive()) endSequence(false);
            setEnabled(false, false);
            return;
        }
        trackStillness();
        if (sequenceActive() && InputLock.lost(this)) { // something the player triggered took the hands over
            dropSequence();
            return;
        }
        if (sequenceActive() && shouldStop()) {
            endSequence(false);
            return;
        }
        if (!sequenceActive()) {
            if (!InputLock.heldByOther(this)) idleTick();
            return;
        }
        if (++ticksRunning > timeout.intValue()) {
            abort("timed out");
            return;
        }
        // a dead player or an open screen can't click: hold the sequence rather than spend it
        if (mc.player.isDeadOrDying() || mc.screen != null) return;
        if (wait > 0) {
            wait--;
            return;
        }
        step();
    }

    /** Whether this module's keybind is physically held. */
    protected final boolean keyHeld() {
        int key = keybind.get();
        return key >= 0 && InputConstants.isKeyDown(mc.getWindow(), key);
    }

    private void trackStillness() {
        float yaw = mc.player.getYRot(), pitch = mc.player.getXRot();
        if (yaw == lastYaw && pitch == lastPitch) stillTicks++;
        else stillTicks = 0;
        lastYaw = yaw;
        lastPitch = pitch;
    }

    // =====================================================================================================
    // Start
    // =====================================================================================================

    /** Whether an anchor sequence could start now at all (dimension, ground, items). */
    protected final String cannotStart(boolean needAnchor) {
        if (mc.level.dimension() == Level.NETHER) return "anchors don't explode in the Nether";
        if (onlyOnGround.get() && !mc.player.onGround()) return "you're in the air";
        if (needAnchor && findItemSlot(Items.RESPAWN_ANCHOR) == -1) return "no respawn anchor in your hotbar";
        if (findItemSlot(Items.GLOWSTONE) == -1) return "no glowstone in your hotbar";
        return null;
    }

    /**
     * Starts a sequence on {@code anchor}. {@code look} is where the player was looking (the face to place the
     * anchor on), or null to find a support face. Returns false if it couldn't start.
     */
    protected final boolean beginAt(BlockPos anchor, BlockHitResult look) {
        resetSequence();
        BlockState state = mc.level.getBlockState(anchor);
        boolean isAnchor = state.is(Blocks.RESPAWN_ANCHOR);
        boolean charged = isAnchor && state.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES) > 0;
        String why = charged ? cannotStartCharged() : cannotStart(!isAnchor);
        if (why != null) {
            abort(why);
            return false;
        }
        originalSlot = InputSim.selectedSlot();
        startYaw = mc.player.getYRot();
        startPitch = mc.player.getXRot();
        anchorPos = anchor;
        lookHit = look;
        if (startedByPlayer()) InputLock.take(this);
        else if (!InputLock.tryClaim(this)) return false;
        stage = charged ? Stage.SHIELD : (isAnchor ? Stage.CHARGE : Stage.PLACE);
        wait = delay(startDelay);
        standUp(); // a crouched click on an anchor places the held item instead of using the anchor
        return true;
    }

    private String cannotStartCharged() {
        if (mc.level.dimension() == Level.NETHER) return "anchors don't explode in the Nether";
        if (onlyOnGround.get() && !mc.player.onGround()) return "you're in the air";
        return null;
    }

    // =====================================================================================================
    // Stage machine
    // =====================================================================================================

    private void step() {
        actedThisTick = false; // at most one pass through the stages, and at most one click per tick
        for (int i = 0; i < Stage.values().length && sequenceActive() && wait == 0 && !actedThisTick; i++) {
            switch (stage) {
                case PLACE -> {
                    switch (placeAnchor()) {
                        case REFUSED -> {
                            abort("can't place the anchor there");
                            return;
                        }
                        case AIMING -> {
                            return;
                        }
                        case CLICKED -> {
                            stage = Stage.CHARGE;
                            wait = delay(placeDelay);
                        }
                    }
                }
                case CHARGE -> {
                    if (!mc.level.getBlockState(anchorPos).is(Blocks.RESPAWN_ANCHOR)) {
                        if (++placeChecks > 6) { // the placement never showed up
                            abort("the anchor didn't appear");
                            return;
                        }
                        stage = Stage.PLACE;
                        wait = 1;
                        return;
                    }
                    if (!readyStanding()) return;
                    switch (charge()) {
                        case REFUSED -> {
                            abort("can't reach the anchor to charge it");
                            return;
                        }
                        case AIMING -> {
                            return;
                        }
                        case CLICKED -> {
                            chargeAttempts++;
                            stage = Stage.SHIELD;
                            wait = Math.max(1, delay(chargeDelay)); // the result is checked on a later tick
                        }
                    }
                }
                case SHIELD -> {
                    if (chargeClicked) {
                        if (!mc.level.getBlockState(anchorPos).is(Blocks.RESPAWN_ANCHOR)) {
                            abort("the anchor isn't there any more");
                            return;
                        }
                        // glowstone on the clicked face means the click placed a block instead of charging
                        boolean failed = chargeFacePos != null && !glowstoneBeforeCharge && mc.level.getBlockState(chargeFacePos).is(Blocks.GLOWSTONE);
                        chargeClicked = false;
                        if (failed) {
                            if (chargeAttempts >= chargeRetries.intValue()) {
                                abort("couldn't charge the anchor");
                                return;
                            }
                            stage = Stage.CHARGE;
                            wait = 1;
                            return;
                        }
                    }
                    if (!needsShield()) {
                        stage = Stage.DETONATE;
                        wait = delay(noShieldDelay);
                        continue;
                    }
                    ShieldPlan plan = findShieldSlot() != -1 ? planShield() : null;
                    if (plan == null) {
                        if (shieldAttempts > 0 && !detonateUnshielded.get()) {
                            abort("couldn't place the shield; cancelled so you don't take the blast");
                            return;
                        }
                        stage = Stage.DETONATE; // no room for a shield: detonate straight away
                        wait = delay(noShieldDelay);
                    } else if (plan.supportSide() == null && !plan.replace()) {
                        stage = Stage.DETONATE; // a full block already sits between the blast and you
                        wait = delay(shieldDelay);
                    } else if (!plan.needsSneak()) {
                        if (placeShield(plan) == Action.AIMING) return;
                        stage = Stage.SHIELD_CHECK;
                        wait = 0;
                    } else {
                        // crouch first: the server only sees it after this tick's input, so the click comes later
                        pressSneak();
                        pendingShield = plan;
                        stage = Stage.SNEAK_PLACE;
                        wait = 0;
                        return;
                    }
                }
                case SNEAK_PLACE -> {
                    if (placeShield(pendingShield) == Action.AIMING) return;
                    pendingShield = null;
                    stage = Stage.UNSNEAK; // stay crouched until the click has been handled
                    wait = 1;
                }
                case UNSNEAK -> {
                    releaseSneak();
                    standTick = ticksRunning;
                    stage = Stage.SHIELD_CHECK;
                    wait = 0;
                }
                case SHIELD_CHECK -> {
                    if (shieldClickTick < 0 || mc.player.tickCount <= shieldClickTick) {
                        if (++shieldCheckWaits > 6) shieldClickTick = -2; // never clicked: failed
                        else {
                            wait = 1;
                            return;
                        }
                    }
                    if (shieldTarget != null && shieldClickTick != -2 && isShielded(shieldTarget)) {
                        stage = Stage.DETONATE;
                        wait = Math.max(0, delay(shieldDelay) - 1);
                    } else {
                        if (shieldTarget != null) failedShields.add(shieldTarget);
                        if (shieldAttempts > shieldRetries.intValue()) {
                            if (!detonateUnshielded.get()) {
                                abort("couldn't place the shield; cancelled so you don't take the blast");
                                return;
                            }
                            stage = Stage.DETONATE;
                        } else {
                            stage = Stage.SHIELD;
                        }
                        wait = 0;
                    }
                }
                case DETONATE -> {
                    if (!readyStanding()) return;
                    if (!mc.level.getBlockState(anchorPos).is(Blocks.RESPAWN_ANCHOR)) {
                        finish();
                        return;
                    }
                    if (antiSuicide.get()) {
                        float taken = ExplosionDamage.damage(mc.player, Vec3.atCenterOf(anchorPos), ExplosionDamage.ANCHOR_POWER, null, anchorPos);
                        if (taken > 0 && taken + safetyMargin.floatValue() >= mc.player.getHealth() + mc.player.getAbsorptionAmount()) {
                            abort("the blast could kill you (" + Math.round(taken) + " damage)");
                            return;
                        }
                    }
                    switch (detonate()) {
                        case REFUSED, AIMING -> {
                            return;
                        }
                        case CLICKED -> {
                            detonateAttempts++;
                            stage = Stage.VERIFY; // the anchor only disappears once the explosion reaches us
                            wait = Math.max(2, verifyDelay.intValue() + ThreadLocalRandom.current().nextInt(3) - 1);
                        }
                    }
                }
                case VERIFY -> {
                    BlockState state = mc.level.getBlockState(anchorPos);
                    if (!state.is(Blocks.RESPAWN_ANCHOR)) { // it went off
                        finish();
                        return;
                    }
                    if (detonateAttempts >= detonateRetries.intValue()) {
                        abort("it didn't go off after " + detonateAttempts + " clicks");
                        return;
                    }
                    if (state.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES) == 0) {
                        // the server never registered the charge (lag): charge again, then detonate
                        if (chargeAttempts >= chargeRetries.intValue()) {
                            abort("it didn't go off, and wouldn't charge");
                            return;
                        }
                        if (!readyStanding()) return;
                        switch (charge()) {
                            case REFUSED -> {
                                abort("can't reach the anchor to charge it");
                                return;
                            }
                            case AIMING -> {
                                return;
                            }
                            case CLICKED -> {
                                chargeAttempts++;
                                stage = Stage.DETONATE;
                                wait = Math.max(1, delay(chargeDelay));
                                return;
                            }
                        }
                    }
                    stage = Stage.DETONATE; // still charged and still there: the click was dropped, again
                }
                default -> {
                }
            }
        }
    }

    private boolean needsShield() {
        if (shieldMode.is("Never")) return false;
        if (shieldMode.is("Always")) return true;
        float taken = ExplosionDamage.damage(mc.player, Vec3.atCenterOf(anchorPos), ExplosionDamage.ANCHOR_POWER, null, anchorPos);
        return taken > shieldThreshold.floatValue();
    }

    // =====================================================================================================
    // Actions: every one is a slot key, a mouse turn and a use-key press
    // =====================================================================================================

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

    /**
     * Selects {@code slot}, turns the camera so the crosshair lands on {@code face} of {@code block} (any face when
     * null) and presses use, once the camera has been still long enough, the slot and crouch state have been seen
     * by the server, and the game's crosshair raycast agrees.
     */
    private Action useOn(int slot, BlockPos block, Direction face, boolean sneaking) {
        if (slot < 0) return Action.REFUSED;
        if (mc.player.isUsingItem()) {
            if (usingItem.is("Cancel")) {
                abort("you're using an item");
                return Action.REFUSED;
            }
            return Action.AIMING; // the game ignores clicks while eating or blocking
        }
        if (InputSim.selectedSlot() != slot) {
            InputSim.selectSlot(slot);
            swapTick = ticksRunning;
        }
        boolean onIt = Crosshair.hits(Crosshair.current(), block, face);
        if (!onIt || MouseSim.overshooting()) {
            boolean sameTarget = block.equals(aimBlock) && face == aimFace;
            if (aim == null || !sameTarget || !Crosshair.hits(aim.yaw(), aim.pitch(), block, face)) {
                aim = Crosshair.solve(block, face, aimMode(), edgeMargin.get());
                aimBlock = block;
                aimFace = face;
            }
            if (aim == null) return onIt ? Action.AIMING : Action.REFUSED;
            MouseSim.aim(aim.yaw(), aim.pitch(), profile(), holdCamera.get());
            return Action.AIMING;
        }
        // on target: stop turning, then wait until the server has this view, the slot and the crouch state
        MouseSim.aim(mc.player.getYRot(), mc.player.getXRot(), profile(), holdCamera.get());
        if (stillTicks < settleTicks.intValue()) return Action.AIMING;
        if (swapDelay.intValue() > 0 && (InputSim.selectedSlot() != slot || ticksRunning - swapTick < swapDelay.intValue())) return Action.AIMING;
        if (sneaking ? ticksRunning - sneakTick < sneakDelay.intValue() : ticksRunning - standTick < standDelay.intValue()) return Action.AIMING;
        if (!InputSim.use()) {
            abort("the use key isn't bound");
            return Action.REFUSED;
        }
        actedThisTick = true;
        aim = null;
        return Action.CLICKED;
    }

    private Action placeAnchor() {
        int slot = findItemSlot(Items.RESPAWN_ANCHOR);
        if (slot == -1) return Action.REFUSED;
        BlockState at = mc.level.getBlockState(anchorPos);
        if (!canPlaceBlockAt(at)) return at.is(Blocks.RESPAWN_ANCHOR) ? Action.CLICKED : Action.REFUSED;
        if (blockedByEntity(anchorPos)) return Action.REFUSED;
        // a replaceable block (snow layer, grass) takes the anchor in its place when clicked
        if (at.canBeReplaced() && !at.isAir()) {
            Action a = useOn(slot, anchorPos, null, false);
            if (a != Action.REFUSED) return a;
        }
        if (lookHit != null && lookHit.getBlockPos().relative(lookHit.getDirection()).equals(anchorPos)
                && !isClickable(mc.level.getBlockState(lookHit.getBlockPos()))) {
            Action a = useOn(slot, lookHit.getBlockPos(), lookHit.getDirection(), false);
            if (a != Action.REFUSED) return a;
        }
        for (Direction side : supportSides(anchorPos)) {
            Action a = useOn(slot, anchorPos.relative(side), side.getOpposite(), false);
            if (a != Action.REFUSED) return a;
        }
        return Action.REFUSED;
    }

    private Action charge() {
        int slot = findItemSlot(Items.GLOWSTONE);
        if (slot == -1) return Action.REFUSED;
        Action a = useOn(slot, anchorPos, null, false);
        if (a == Action.CLICKED) {
            Direction face = Crosshair.current() instanceof BlockHitResult b ? b.getDirection() : Direction.UP;
            chargeFacePos = anchorPos.relative(face);
            glowstoneBeforeCharge = mc.level.getBlockState(chargeFacePos).is(Blocks.GLOWSTONE);
            chargeClicked = true;
        }
        return a;
    }

    private Action placeShield(ShieldPlan plan) {
        int slot = findShieldSlot();
        if (plan == null || slot == -1) {
            shieldAttempts++;
            shieldClickTick = -2;
            shieldCheckWaits = 0;
            shieldTarget = plan == null ? null : plan.pos();
            return Action.REFUSED;
        }
        BlockPos target = plan.replace() ? plan.pos() : plan.pos().relative(plan.supportSide());
        Direction face = plan.replace() ? null : plan.supportSide().getOpposite();
        Action a = useOn(slot, target, face, plan.needsSneak());
        if (a == Action.AIMING) return a;
        shieldAttempts++;
        shieldCheckWaits = 0;
        shieldTarget = plan.pos();
        shieldClickTick = a == Action.CLICKED ? mc.player.tickCount : -2;
        return a;
    }

    private Action detonate() {
        int slot = findDetonateSlot();
        if (slot == -1) {
            abort("no safe slot to detonate with");
            return Action.REFUSED;
        }
        Action a = useOn(slot, anchorPos, null, false);
        if (a == Action.REFUSED) abort("can't see the anchor to detonate it");
        return a;
    }

    // =====================================================================================================
    // Shield planning
    // =====================================================================================================

    /**
     * Candidate spots are the blocks the blast would cross on its way from the anchor to your body and eyes, nearest
     * the anchor first. Each must be placeable, not inside an entity, not already failed, and reachable by the
     * crosshair on a visible face, checked with the game's own raycast.
     */
    private ShieldPlan planShield() {
        Vec3 anchorCenter = Vec3.atCenterOf(anchorPos);
        Vec3 body = mc.player.getBoundingBox().getCenter();
        Vec3 eyes = mc.player.getEyePosition();
        double reach = shieldRange.get();
        Set<BlockPos> ordered = new LinkedHashSet<>();
        for (int i = 1; i <= 60; i++) {
            double t = i / 60.0;
            for (Vec3 end : new Vec3[]{eyes, body}) {
                Vec3 p = anchorCenter.add(end.subtract(anchorCenter).scale(t));
                if (p.distanceTo(anchorCenter) > reach) continue;
                BlockPos bp = BlockPos.containing(p);
                if (!bp.equals(anchorPos)) ordered.add(bp);
            }
        }
        List<BlockPos> candidates = new ArrayList<>(ordered);
        candidates.sort((a, b) -> Double.compare(Vec3.atCenterOf(a).distanceToSqr(anchorCenter), Vec3.atCenterOf(b).distanceToSqr(anchorCenter)));
        Crosshair.AimPoint mode = aimMode();
        double margin = edgeMargin.get();
        for (BlockPos pos : candidates) {
            BlockState existing = mc.level.getBlockState(pos);
            if (!existing.canBeReplaced() && Block.isShapeFullBlock(existing.getCollisionShape(mc.level, pos))) {
                return new ShieldPlan(pos, null, false, false); // already shielded
            }
            if (failedShields.contains(pos)) continue;
            if (existing.is(Blocks.SNOW) && existing.getValue(BlockStateProperties.LAYERS) > 1) continue; // thick snow can't be replaced
            if (!canPlaceBlockAt(existing) || blockedByEntity(pos)) continue;
            if (existing.canBeReplaced() && !existing.isAir() && !existing.getShape(mc.level, pos).isEmpty()) {
                if (Crosshair.solve(pos, null, mode, margin) != null) return new ShieldPlan(pos, null, false, true);
                continue;
            }
            for (Direction support : supportSides(pos)) {
                if (Crosshair.solve(pos.relative(support), support.getOpposite(), mode, margin) != null) return new ShieldPlan(pos, support, false, false);
            }
            if (crouchPlace.get()) { // only the anchor is adjacent: crouch and place against the anchor itself
                for (Direction d : Direction.values()) {
                    if (pos.relative(d).equals(anchorPos) && Crosshair.solve(anchorPos, d.getOpposite(), mode, margin) != null) {
                        return new ShieldPlan(pos, d, true, false);
                    }
                }
            }
        }
        return null;
    }

    private boolean isShielded(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.is(Blocks.GLOWSTONE)) return true;
        return !state.canBeReplaced() && Block.isShapeFullBlock(state.getCollisionShape(mc.level, pos));
    }

    // =====================================================================================================
    // Crouching: the sneak key, held and let go like the keyboard would
    // =====================================================================================================

    private void pressSneak() {
        if (!InputSim.sneakKeyDown()) {
            InputSim.sneak(true);
            pressedSneak = true;
        }
        sneakTick = ticksRunning;
    }

    private void standUp() {
        if (!InputSim.sneakKeyDown() && !mc.player.isShiftKeyDown()) return;
        if (!pressedSneak) forcedStand = true;
        InputSim.sneak(false);
        pressedSneak = false;
        standTick = ticksRunning;
    }

    private boolean readyStanding() {
        if (InputSim.sneakKeyDown() || mc.player.isShiftKeyDown()) standUp();
        if (ticksRunning - standTick < standDelay.intValue()) {
            wait = 1;
            return false;
        }
        return true;
    }

    private void releaseSneak() {
        if (!pressedSneak) return;
        InputSim.sneak(false);
        pressedSneak = false;
    }

    // =====================================================================================================
    // End
    // =====================================================================================================

    private void finish() {
        endSequence(true);
    }

    /** Ends the sequence with a reason shown to the player (when warnings are on). */
    protected final void abort(String reason) {
        if (stage == Stage.DONE) return; // already ended this tick
        if (warnings.get()) Notifications.push(name, reason, Notifications.Kind.WARNING);
        if (sequenceActive()) endSequence(false);
        else onSequenceEnd(false);
    }

    /** Whether sequences are started by the player (and may take the input over from an automatic module). */
    protected boolean startedByPlayer() {
        return false;
    }

    /** Ends the sequence without touching the slot or camera, which another module is now driving. */
    private void dropSequence() {
        releaseSneak();
        forcedStand = false;
        pendingShield = null;
        aim = null;
        stage = Stage.DONE;
        onSequenceEnd(false);
    }

    protected final void endSequence(boolean completed) {
        if (InputLock.lost(this) && stage != null) {
            dropSequence();
            return;
        }
        InputLock.release(this);
        releaseSneak();
        if (forcedStand && mc.player != null && InputSim.sneakHeldPhysically()) InputSim.sneak(true);
        forcedStand = false;
        if (swapBack.get() && originalSlot >= 0) InputSim.selectSlot(originalSlot);
        boolean ran = stage != null;
        if (lookBack.get() && mc.player != null && ran) {
            MouseSim.Profile p = profile();
            MouseSim.returnTo(startYaw, startPitch, new MouseSim.Profile(lookBackSpeed.floatValue(), p.minSpeed(), p.ease(), p.curve(), 0, 0, 1e9f),
                    lookBackDelay.intValue() / 20.0);
        } else {
            MouseSim.stop();
        }
        pendingShield = null;
        aim = null;
        stage = Stage.DONE;
        onSequenceEnd(completed);
    }

    // =====================================================================================================
    // Render
    // =====================================================================================================

    @Override
    public void onRenderWorld(WorldRender ctx) {
        if (!showTarget.get() || !sequenceActive() || anchorPos == null) return;
        int a = anchorColor.get();
        Gizmos.cuboid(new AABB(anchorPos), GizmoStyle.strokeAndFill(a, 2f, (0x30 << 24) | (a & 0xFFFFFF))).setAlwaysOnTop();
        BlockPos shield = pendingShield != null ? pendingShield.pos() : shieldTarget;
        if (shield != null && (stage == Stage.SHIELD || stage == Stage.SNEAK_PLACE || stage == Stage.SHIELD_CHECK || stage == Stage.UNSNEAK)) {
            int s = shieldColor.get();
            Gizmos.cuboid(new AABB(shield), GizmoStyle.strokeAndFill(s, 2f, (0x30 << 24) | (s & 0xFFFFFF))).setAlwaysOnTop();
        }
    }

    // =====================================================================================================
    // Helpers
    // =====================================================================================================

    protected final int findItemSlot(Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    private int findShieldSlot() {
        switch (shieldBlock.get()) {
            case "Obsidian" -> {
                int s = findItemSlot(Items.OBSIDIAN);
                return s != -1 ? s : findItemSlot(Items.CRYING_OBSIDIAN);
            }
            case "Any solid" -> {
                int s = findItemSlot(Items.OBSIDIAN);
                if (s != -1) return s;
                for (int i = 0; i < 9; i++) {
                    ItemStack stack = mc.player.getInventory().getItem(i);
                    if (!(stack.getItem() instanceof BlockItem bi)) continue;
                    Block b = bi.getBlock();
                    if (b == Blocks.RESPAWN_ANCHOR || b instanceof FallingBlock || b instanceof EntityBlock) continue;
                    BlockState def = b.defaultBlockState();
                    if (Block.isShapeFullBlock(def.getCollisionShape(mc.level, BlockPos.ZERO))) return i;
                }
                return findItemSlot(Items.GLOWSTONE);
            }
            default -> {
                return findItemSlot(Items.GLOWSTONE);
            }
        }
    }

    protected static boolean canPlaceBlockAt(BlockState state) {
        return state.isAir() || (state.canBeReplaced() && state.getFluidState().isEmpty());
    }

    /** Any living entity or crystal whose hitbox overlaps this block (a block can't be placed there). */
    protected final boolean blockedByEntity(BlockPos pos) {
        AABB box = new AABB(pos);
        for (Entity e : mc.level.getEntities((Entity) null, box.inflate(1.0),
                entity -> entity.isAlive() && !entity.isSpectator() && (entity instanceof LivingEntity || entity instanceof EndCrystal))) {
            if (e.getBoundingBox().intersects(box)) return true;
        }
        return false;
    }

    /**
     * Solid neighbours to place against, nearest the eye first. Never the anchor itself and never a block whose
     * right-click does something else.
     */
    protected final List<Direction> supportSides(BlockPos pos) {
        Vec3 eyes = mc.player.getEyePosition();
        List<Direction> out = new ArrayList<>();
        for (Direction side : Direction.values()) {
            BlockPos n = pos.relative(side);
            if (n.equals(anchorPos)) continue;
            BlockState state = mc.level.getBlockState(n);
            if (state.isAir() || state.canBeReplaced() || !state.getFluidState().isEmpty() || isClickable(state)) continue;
            out.add(side);
        }
        out.sort((a, b) -> Double.compare(eyes.distanceToSqr(Vec3.atCenterOf(pos.relative(a))), eyes.distanceToSqr(Vec3.atCenterOf(pos.relative(b)))));
        return out;
    }

    /**
     * Blocks whose right-click does something other than letting you place against them: containers, doors, levers,
     * crafting stations, beds, cake, berry bushes and glow berries (which get harvested), toggling redstone parts,
     * and anchors.
     */
    protected static boolean isClickable(BlockState state) {
        Block b = state.getBlock();
        return b instanceof EntityBlock || b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock
                || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof RespawnAnchorBlock
                || b instanceof SweetBerryBushBlock || b instanceof CaveVinesBlock || b instanceof CaveVinesPlantBlock
                || b instanceof CakeBlock || b instanceof CraftingTableBlock || b instanceof AnvilBlock || b instanceof GrindstoneBlock
                || b instanceof StonecutterBlock || b instanceof LoomBlock || b instanceof CartographyTableBlock || b instanceof BedBlock
                || b instanceof NoteBlock || b instanceof RepeaterBlock || b instanceof FlowerPotBlock || b instanceof ComposterBlock
                || b instanceof RedStoneWireBlock;
    }

    private int findDetonateSlot() {
        if (detonateWith.is("Slot")) {
            int s = detonateSlot.intValue() - 1;
            ItemStack stack = mc.player.getInventory().getItem(s);
            if (stack.isEmpty() || isSafeToClickWith(stack)) return s;
        }
        int fallback = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty() && !detonateWith.is("Safe item")) return i;
            if (!stack.isEmpty() && fallback == -1 && isSafeToClickWith(stack)) fallback = i;
        }
        if (fallback != -1) return fallback;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    private static boolean isSafeToClickWith(ItemStack stack) {
        if (stack.getItem() instanceof BlockItem) return false;
        return !stack.is(Items.ENDER_PEARL) && !stack.is(Items.EXPERIENCE_BOTTLE) && !stack.is(Items.SPLASH_POTION)
                && !stack.is(Items.LINGERING_POTION) && !stack.is(Items.END_CRYSTAL) && !stack.is(Items.FIREWORK_ROCKET)
                && !stack.is(Items.SNOWBALL) && !stack.is(Items.EGG) && !stack.is(Items.TRIDENT) && !stack.is(Items.BOW)
                && !stack.is(Items.CROSSBOW) && !stack.is(Items.SHIELD) && !stack.is(Items.WIND_CHARGE) && !stack.is(Items.GLOWSTONE)
                && stack.getUseAnimation() == ItemUseAnimation.NONE;
    }

    /** A configured delay plus the random extra, spread uniformly or around half of it. */
    protected final int delay(NumberSetting base) {
        int extra = randomExtra.intValue();
        if (extra <= 0) return base.intValue();
        int add;
        if (randomMode.is("Gaussian")) {
            add = (int) Math.round(Math.max(0, Math.min(extra, extra / 2.0 + ThreadLocalRandom.current().nextGaussian() * extra / 4.0)));
        } else {
            add = ThreadLocalRandom.current().nextInt(extra + 1);
        }
        return base.intValue() + add;
    }

    @Override
    public String suffix() {
        return stage == null || stage == Stage.DONE ? null : stage.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
