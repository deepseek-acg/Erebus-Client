package dev.erebus.client.module.combat;

import com.mojang.blaze3d.platform.InputConstants;
import dev.erebus.client.input.Crosshair;
import dev.erebus.client.input.InputSim;
import dev.erebus.client.input.MouseSim;
import dev.erebus.client.module.Category;
import dev.erebus.client.module.Module;
import dev.erebus.client.notify.Notifications;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.NumberSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
 * One press, one anchor: place a respawn anchor, charge it with glowstone, wall the blast off with a glowstone
 * shield, detonate, then turn off. Ported from Mist's AutoSafeAnchor (itself from Nyx), stage for stage.
 *
 * <p>Everything is done with simulated input, never with hand-built interactions or packets:
 * <ul>
 *   <li>the hotbar slot is picked by pressing its number key,</li>
 *   <li>the camera is turned with synthetic mouse movement through the game's own mouse handling
 *       ({@link MouseSim}),</li>
 *   <li>sneaking is the sneak key held down,</li>
 *   <li>every click is the use key, which the game handles on whatever its own crosshair raycast hits.</li>
 * </ul>
 * Before each click the crosshair is predicted with that same raycast ({@link Crosshair}), so a snow layer,
 * a plant, a berry bush, an item frame or a crystal in the line of sight is noticed and aimed around rather than
 * clicked by mistake. A click only goes out once the camera has been still for a full tick, so the server
 * already has the view the click is made with.
 */
public final class AutoSafeAnchor extends Module {
    private final NumberSetting placeDelay;
    private final NumberSetting chargeDelay;
    private final NumberSetting shieldDelay;
    private final NumberSetting randomExtra;
    private final NumberSetting verifyDelay;
    private final NumberSetting timeout;
    private final NumberSetting aimSpeed;
    private final BoolSetting holdCamera;
    private final BoolSetting lookBack;
    private final BoolSetting useExisting;
    private final BoolSetting crouchPlace;
    private final NumberSetting shieldRetries;
    private final BoolSetting detonateUnshielded;
    private final BoolSetting swapBack;
    private final BoolSetting repeatWhileHeld;

    private enum Stage { PLACE, CHARGE, SHIELD, SNEAK_PLACE, UNSNEAK, SHIELD_CHECK, DETONATE, VERIFY, DONE }

    /** What a stage's click managed: sent, still turning/waiting, or impossible. */
    private enum Action { CLICKED, AIMING, REFUSED }

    /**
     * Where the shield goes and how to click it: {@code supportSide} is the side of the block to place against
     * (null with {@code replace} false means a full block already sits in the way), {@code needsSneak} is the
     * place-against-the-anchor case, {@code replace} is the click-the-snow-layer-or-grass case.
     */
    private record ShieldPlan(BlockPos pos, Direction supportSide, boolean needsSneak, boolean replace) {
    }

    // ---- sequence state ----
    private Stage stage;
    private BlockPos anchorPos;
    private BlockHitResult aimHit;   // where the player was looking when the key was pressed
    private ShieldPlan pendingShield;
    private int wait;
    private int ticksRunning;
    private boolean actedThisTick;
    private boolean pressedSneak;
    private boolean forcedStand;
    private int standTick = -1;
    private int sneakTick = -1;
    private int chargeAttempts;
    private int detonateAttempts;
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
    /** Ticks the camera has not moved; a click needs at least one, so the server already has the view. */
    private int stillTicks;

    public AutoSafeAnchor() {
        super("Auto Safe Anchor", "One press: places a respawn anchor where you look, charges it, shields the blast with glowstone and "
                + "detonates it. Done entirely with simulated mouse and key input, never with crafted packets.", Category.COMBAT);
        group("Timing");
        placeDelay = add(new NumberSetting("Place delay", "Ticks to wait after placing the anchor.", 1, 0, 20, 1));
        chargeDelay = add(new NumberSetting("Charge delay", "Ticks to wait after charging it.", 1, 0, 20, 1));
        shieldDelay = add(new NumberSetting("Shield delay", "Ticks to wait after the shield is down, before detonating.", 1, 0, 20, 1));
        randomExtra = add(new NumberSetting("Random extra", "Up to this many extra ticks on each delay, so the timing isn't a fixed number.", 1, 0, 10, 1));
        verifyDelay = add(new NumberSetting("Detonate check", "Ticks to wait after detonating before checking it went off (and trying again).", 4, 2, 20, 1));
        timeout = add(new NumberSetting("Timeout", "Give up if the whole sequence takes longer than this many ticks.", 80, 20, 200, 5));

        group("Aim");
        aimSpeed = add(new NumberSetting("Aim speed", "Fastest the camera turns, in degrees per second. It eases in and out like a hand on a mouse.",
                900, 180, 1800, 30));
        holdCamera = add(new BoolSetting("Hold camera", "Ignore your own mouse while the sequence is turning, so it doesn't have to fight you.", true));
        lookBack = add(new BoolSetting("Look back", "Turn back to where you were looking when it's done. Moving the mouse cancels it.", true));

        group("Behaviour");
        useExisting = add(new BoolSetting("Use existing", "If you look at an anchor that's already there, charge and detonate that one.", true));
        crouchPlace = add(new BoolSetting("Crouch place", "If the anchor is the only thing to place the shield against, crouch so the click "
                + "places glowstone instead of charging the anchor.", true));
        shieldRetries = add(new NumberSetting("Shield retries", "How many more shield spots to try if a placement doesn't land.", 2, 0, 5, 1));
        detonateUnshielded = add(new BoolSetting("Detonate if shield fails", "If a shield spot existed but the glowstone never went down, "
                + "detonate anyway. Off: cancel rather than take the blast.", false));
        swapBack = add(new BoolSetting("Swap back", "Go back to the hotbar slot you had before.", true));
        repeatWhileHeld = add(new BoolSetting("Repeat while held", "Keep running sequences while the keybind is held down, instead of one per press.", false));
    }

    @Override
    protected boolean announcesToggle() {
        return false;
    }

    // =====================================================================================================
    // Lifecycle
    // =====================================================================================================

    @Override
    protected void onEnable() {
        resetSequence();
        if (mc.player == null || mc.level == null) { // e.g. restored from the config at startup
            setEnabled(false, false);
            return;
        }
        originalSlot = InputSim.selectedSlot();
        startYaw = mc.player.getYRot();
        startPitch = mc.player.getXRot();
        startFromLook();
    }

    @Override
    protected void onDisable() {
        if (stage != null && stage != Stage.DONE) endSequence();
    }

    private void resetSequence() {
        releaseSneak();
        stage = null;
        anchorPos = null;
        aimHit = null;
        pendingShield = null;
        wait = 0;
        ticksRunning = 0;
        pressedSneak = false;
        forcedStand = false;
        standTick = -1;
        sneakTick = -1;
        chargeAttempts = 0;
        detonateAttempts = 0;
        chargeClicked = false;
        chargeFacePos = null;
        shieldTarget = null;
        shieldAttempts = 0;
        shieldClickTick = -1;
        shieldCheckWaits = 0;
        failedShields.clear();
        stillTicks = 0;
        lastYaw = Float.NaN;
    }

    private boolean sequenceActive() {
        return stage != null && stage != Stage.DONE;
    }

    @Override
    public void onPreTick() {
        if (mc.player == null || mc.level == null) {
            setEnabled(false, false);
            return;
        }
        trackStillness();
        if (!sequenceActive()) {
            if (repeatWhileHeld.get() && keyHeld()) {
                resetSequence();
                startFromLook();
                return;
            }
            setEnabled(false, false);
            return;
        }
        if (repeatWhileHeld.get() && !keyHeld()) { // let go of the key: stop where it is
            endSequence();
            setEnabled(false, false);
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

    private boolean keyHeld() {
        int key = keybind.get();
        return key >= 0 && InputConstants.isKeyDown(mc.getWindow(), key);
    }

    /** Counts ticks the camera hasn't moved. Read at the start of a tick, before the game handles input. */
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

    private void startFromLook() {
        if (mc.level.dimension() == Level.NETHER) {
            abort("anchors don't explode in the Nether");
            return;
        }
        HitResult look = Crosshair.current();
        if (!(look instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            abort("you aren't looking at a block");
            return;
        }
        BlockPos looked = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(looked);
        aimHit = hit;
        BlockPos anchor;
        if (state.is(Blocks.RESPAWN_ANCHOR) && useExisting.get()) anchor = looked;
        else anchor = state.canBeReplaced() ? looked : looked.relative(hit.getDirection());
        beginAt(anchor);
    }

    /** Checks items and dimension up front, so a sequence that can't finish never starts. */
    private void beginAt(BlockPos anchor) {
        BlockState state = mc.level.getBlockState(anchor);
        boolean isAnchor = state.is(Blocks.RESPAWN_ANCHOR);
        boolean charged = isAnchor && state.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES) > 0;
        if (!isAnchor && findItemSlot(Items.RESPAWN_ANCHOR) == -1) {
            abort("no respawn anchor in your hotbar");
            return;
        }
        if (!charged && findItemSlot(Items.GLOWSTONE) == -1) {
            abort("no glowstone in your hotbar");
            return;
        }
        anchorPos = anchor;
        stage = charged ? Stage.SHIELD : (isAnchor ? Stage.CHARGE : Stage.PLACE);
        standUp(); // a crouched click on an anchor places the held item instead of using the anchor
    }

    // =====================================================================================================
    // Stage machine
    // =====================================================================================================

    private void step() {
        // at most one pass through the stages, and at most one click per tick
        actedThisTick = false;
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
                        // the placement hasn't shown up yet (lag): give it a moment, then retry the place
                        if (++chargeAttempts > 6) {
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
                        boolean failed = chargeFacePos != null && !glowstoneBeforeCharge
                                && mc.level.getBlockState(chargeFacePos).is(Blocks.GLOWSTONE);
                        chargeClicked = false;
                        if (failed) {
                            if (chargeAttempts >= 3) {
                                abort("couldn't charge the anchor");
                                return;
                            }
                            stage = Stage.CHARGE;
                            wait = 1;
                            return;
                        }
                    }
                    ShieldPlan plan = findItemSlot(Items.GLOWSTONE) != -1 ? planShield() : null;
                    if (plan == null) {
                        if (shieldAttempts > 0 && !detonateUnshielded.get()) {
                            abort("couldn't place the shield; cancelled so you don't take the blast");
                            return;
                        }
                        stage = Stage.DETONATE; // no room for a shield: detonate straight away
                        wait = 0;
                    } else if (plan.supportSide() == null && !plan.replace()) {
                        stage = Stage.DETONATE; // a full block already sits between the blast and you
                        wait = delay(shieldDelay);
                    } else if (!plan.needsSneak()) {
                        Action a = placeShield(plan);
                        if (a == Action.AIMING) return;
                        stage = Stage.SHIELD_CHECK;
                        wait = 0;
                    } else {
                        // crouch first: the server only sees it after this tick's input, so the click comes later
                        pressSneak();
                        pendingShield = plan;
                        stage = Stage.SNEAK_PLACE;
                        wait = 1;
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
                    if (detonateAttempts >= 3) {
                        abort("it didn't go off after 3 clicks");
                        return;
                    }
                    if (state.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES) == 0) {
                        // the server never registered the charge (lag): charge again, then detonate
                        if (chargeAttempts >= 3) {
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

    // =====================================================================================================
    // Actions: every one is a slot key, a mouse turn and a use-key press
    // =====================================================================================================

    /**
     * Selects {@code slot}, turns the camera so the crosshair lands on {@code face} of {@code block} (any face
     * when null) and presses use, once the camera has been still for a tick and the game's crosshair raycast
     * agrees. {@code sneaking} is the crouch state the click must be made in.
     */
    private Action useOn(int slot, BlockPos block, Direction face, boolean sneaking) {
        if (slot < 0) return Action.REFUSED;
        if (mc.player.isUsingItem()) return Action.AIMING; // eating or blocking: the game ignores clicks
        InputSim.selectSlot(slot);
        HitResult now = Crosshair.current();
        boolean onIt = Crosshair.hits(now, block, face);
        if (!onIt) {
            Crosshair.Aim aim = Crosshair.solve(block, face);
            if (aim == null) return Action.REFUSED;
            MouseSim.aim(aim.yaw(), aim.pitch(), aimSpeed.floatValue(), holdCamera.get());
            return Action.AIMING;
        }
        // on target: stop turning, then wait until the server has this view, the slot and the crouch state
        MouseSim.aim(mc.player.getYRot(), mc.player.getXRot(), aimSpeed.floatValue(), holdCamera.get());
        if (stillTicks < 1 || InputSim.selectedSlot() != slot) return Action.AIMING;
        if (sneaking ? (sneakTick < 0 || ticksRunning <= sneakTick) : ticksRunning <= standTick) return Action.AIMING;
        if (!InputSim.use()) {
            abort("the use key isn't bound");
            return Action.REFUSED;
        }
        actedThisTick = true;
        return Action.CLICKED;
    }

    /** Places the anchor on the face that was looked at when the key was pressed, or against another support. */
    private Action placeAnchor() {
        int slot = findItemSlot(Items.RESPAWN_ANCHOR);
        if (slot == -1) return Action.REFUSED;
        if (!canPlaceBlockAt(mc.level.getBlockState(anchorPos))) {
            return mc.level.getBlockState(anchorPos).is(Blocks.RESPAWN_ANCHOR) ? Action.CLICKED : Action.REFUSED;
        }
        if (blockedByEntity(anchorPos)) return Action.REFUSED;
        // clicking a replaceable block (snow layer, grass) puts the anchor into it; otherwise click a support face
        if (mc.level.getBlockState(anchorPos).canBeReplaced() && !mc.level.getBlockState(anchorPos).isAir()) {
            Action a = useOn(slot, anchorPos, null, false);
            if (a != Action.REFUSED) return a;
        }
        if (aimHit != null && aimHit.getBlockPos().relative(aimHit.getDirection()).equals(anchorPos)
                && !isClickable(mc.level.getBlockState(aimHit.getBlockPos()))) {
            Action a = useOn(slot, aimHit.getBlockPos(), aimHit.getDirection(), false);
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
            HitResult hit = Crosshair.current();
            Direction face = hit instanceof BlockHitResult b ? b.getDirection() : Direction.UP;
            chargeFacePos = anchorPos.relative(face);
            glowstoneBeforeCharge = mc.level.getBlockState(chargeFacePos).is(Blocks.GLOWSTONE);
            chargeClicked = true;
        }
        return a;
    }

    private Action placeShield(ShieldPlan plan) {
        int slot = findItemSlot(Items.GLOWSTONE);
        if (plan == null || slot == -1) {
            shieldAttempts++;
            shieldClickTick = -2;
            shieldCheckWaits = 0;
            shieldTarget = plan == null ? null : plan.pos();
            return Action.REFUSED;
        }
        BlockPos target;
        Direction face;
        if (plan.replace()) { // snow layer or grass: clicking the spot itself replaces it
            target = plan.pos();
            face = null;
        } else {
            target = plan.pos().relative(plan.supportSide());
            face = plan.supportSide().getOpposite();
        }
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
     * Candidate spots are the blocks the blast would cross on its way from the anchor to your body and eyes,
     * nearest the anchor first. Each must be placeable, in reach, not inside an entity, not already failed, and
     * reachable by the crosshair through a visible face, checked with the game's own raycast.
     */
    private ShieldPlan planShield() {
        Vec3 anchorCenter = Vec3.atCenterOf(anchorPos);
        Vec3 body = mc.player.getBoundingBox().getCenter();
        Vec3 eyes = mc.player.getEyePosition();
        Set<BlockPos> ordered = new LinkedHashSet<>();
        for (int i = 1; i <= 60; i++) {
            double t = i / 60.0;
            for (Vec3 end : new Vec3[]{eyes, body}) {
                Vec3 p = anchorCenter.add(end.subtract(anchorCenter).scale(t));
                if (p.distanceTo(anchorCenter) > 3.0) continue;
                BlockPos bp = BlockPos.containing(p);
                if (!bp.equals(anchorPos)) ordered.add(bp);
            }
        }
        List<BlockPos> candidates = new ArrayList<>(ordered);
        candidates.sort((a, b) -> Double.compare(Vec3.atCenterOf(a).distanceToSqr(anchorCenter), Vec3.atCenterOf(b).distanceToSqr(anchorCenter)));

        for (BlockPos pos : candidates) {
            BlockState existing = mc.level.getBlockState(pos);
            if (!existing.canBeReplaced() && Block.isShapeFullBlock(existing.getCollisionShape(mc.level, pos))) {
                return new ShieldPlan(pos, null, false, false); // already shielded
            }
            if (failedShields.contains(pos)) continue;
            // thick snow can't be replaced by a block, so nothing can go in this spot
            if (existing.is(Blocks.SNOW) && existing.getValue(BlockStateProperties.LAYERS) > 1) continue;
            if (!canPlaceBlockAt(existing) || blockedByEntity(pos)) continue;

            // snow layer, grass, flowers: click the spot itself, which replaces it
            if (existing.canBeReplaced() && !existing.isAir() && !existing.getShape(mc.level, pos).isEmpty()) {
                if (Crosshair.solve(pos, null) != null) return new ShieldPlan(pos, null, false, true);
                continue;
            }
            for (Direction support : supportSides(pos)) {
                if (Crosshair.solve(pos.relative(support), support.getOpposite()) != null) return new ShieldPlan(pos, support, false, false);
            }
            // only the anchor is adjacent: crouch and place against the anchor itself
            if (crouchPlace.get()) {
                for (Direction d : Direction.values()) {
                    if (pos.relative(d).equals(anchorPos) && Crosshair.solve(anchorPos, d.getOpposite()) != null) {
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

    /** Lets go of sneak (remembering to give a crouch of the player's own back afterwards). */
    private void standUp() {
        if (!InputSim.sneakKeyDown() && !mc.player.isShiftKeyDown()) return;
        if (!pressedSneak) forcedStand = true;
        InputSim.sneak(false);
        pressedSneak = false;
        standTick = ticksRunning;
    }

    /** True once the server has seen us standing for a tick; otherwise stands up and waits. */
    private boolean readyStanding() {
        if (InputSim.sneakKeyDown() || mc.player.isShiftKeyDown()) standUp();
        if (ticksRunning <= standTick) {
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
        endSequence();
        setEnabled(false, false);
    }

    private void abort(String reason) {
        Notifications.push(name, reason, Notifications.Kind.WARNING);
        finish();
    }

    private void endSequence() {
        releaseSneak();
        // give back a crouch of the player's own, but only if they're still holding the key
        if (forcedStand && mc.player != null && InputSim.sneakHeldPhysically()) InputSim.sneak(true);
        forcedStand = false;
        if (swapBack.get() && originalSlot >= 0) InputSim.selectSlot(originalSlot);
        if (lookBack.get() && mc.player != null && stage != null) MouseSim.returnTo(startYaw, startPitch, aimSpeed.floatValue());
        else MouseSim.stop();
        pendingShield = null;
        stage = Stage.DONE;
    }

    // =====================================================================================================
    // Helpers
    // =====================================================================================================

    private int findItemSlot(Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    /** Air, or a replaceable block without fluid. */
    private static boolean canPlaceBlockAt(BlockState state) {
        return state.isAir() || (state.canBeReplaced() && state.getFluidState().isEmpty());
    }

    /** Any living entity or crystal whose hitbox overlaps this block (a block can't be placed there). */
    private boolean blockedByEntity(BlockPos pos) {
        AABB box = new AABB(pos);
        for (Entity e : mc.level.getEntities((Entity) null, box.inflate(1.0),
                entity -> entity.isAlive() && !entity.isSpectator() && (entity instanceof LivingEntity || entity instanceof EndCrystal))) {
            if (e.getBoundingBox().intersects(box)) return true;
        }
        return false;
    }

    /**
     * Solid neighbours to place against, nearest the eye first. Never the anchor itself (clicking it with
     * glowstone would charge it) and never a block whose right-click does something else.
     */
    private List<Direction> supportSides(BlockPos pos) {
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
     * Blocks whose right-click does something other than letting you place against them: containers, doors,
     * levers, crafting stations, beds, cake, berry bushes and glow berries (which get harvested), redstone parts
     * that toggle, and anchors.
     */
    private static boolean isClickable(BlockState state) {
        Block b = state.getBlock();
        return b instanceof EntityBlock || b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock
                || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof RespawnAnchorBlock
                || b instanceof SweetBerryBushBlock || b instanceof CaveVinesBlock || b instanceof CaveVinesPlantBlock
                || b instanceof CakeBlock || b instanceof CraftingTableBlock || b instanceof AnvilBlock || b instanceof GrindstoneBlock
                || b instanceof StonecutterBlock || b instanceof LoomBlock || b instanceof CartographyTableBlock || b instanceof BedBlock
                || b instanceof NoteBlock || b instanceof RepeaterBlock || b instanceof FlowerPotBlock || b instanceof ComposterBlock
                || b instanceof RedStoneWireBlock;
    }

    /**
     * What to detonate with. An empty slot is safest; otherwise a slot that will neither place a block nor be
     * used (thrown, eaten) if the server hasn't registered the charge.
     */
    private int findDetonateSlot() {
        int fallback = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) return i;
            if (stack.is(Items.GLOWSTONE)) continue;
            if (fallback == -1 && isSafeToClickWith(stack)) fallback = i;
        }
        return fallback;
    }

    private static boolean isSafeToClickWith(ItemStack stack) {
        if (stack.getItem() instanceof BlockItem) return false;
        return !stack.is(Items.ENDER_PEARL) && !stack.is(Items.EXPERIENCE_BOTTLE) && !stack.is(Items.SPLASH_POTION)
                && !stack.is(Items.LINGERING_POTION) && !stack.is(Items.END_CRYSTAL) && !stack.is(Items.FIREWORK_ROCKET)
                && !stack.is(Items.GOLDEN_APPLE) && !stack.is(Items.ENCHANTED_GOLDEN_APPLE) && !stack.is(Items.CHORUS_FRUIT)
                && !stack.is(Items.SNOWBALL) && !stack.is(Items.EGG) && !stack.is(Items.TRIDENT) && !stack.is(Items.BOW)
                && !stack.is(Items.CROSSBOW) && !stack.is(Items.SHIELD) && !stack.is(Items.WIND_CHARGE)
                && stack.getUseAnimation() == net.minecraft.world.item.ItemUseAnimation.NONE;
    }

    /** A configured delay plus 0..random-extra ticks. */
    private int delay(NumberSetting base) {
        int extra = randomExtra.intValue();
        return base.intValue() + (extra > 0 ? ThreadLocalRandom.current().nextInt(extra + 1) : 0);
    }

    @Override
    public String suffix() {
        return stage == null || stage == Stage.DONE ? null : stage.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
