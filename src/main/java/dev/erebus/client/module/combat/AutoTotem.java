package dev.erebus.client.module.combat;

import dev.erebus.client.input.InputLock;
import dev.erebus.client.input.InputSim;
import dev.erebus.client.mixin.ContainerScreenAccess;
import dev.erebus.client.module.Category;
import dev.erebus.client.module.Module;
import dev.erebus.client.notify.Notifications;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.ModeSetting;
import dev.erebus.client.setting.NumberSetting;
import dev.erebus.client.util.ExplosionDamage;
import dev.erebus.client.util.Pops;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Keeps a totem in your offhand, the way you would by hand, with a random, ping-adjusted reaction time (ported from
 * Nyx's Smarter AutoTotem).
 *
 * <p>Everything is real input. With a totem in the hotbar: its number key, then your swap-hands key, then back to the
 * slot you had. Otherwise: your inventory key, the cursor moved over a totem, your swap-hands key on it, and the
 * inventory key again to close it, each step after its own short random pause. Nothing is clicked in a container the
 * game hasn't shown.
 *
 * <p>Smart mode only swaps a totem in when it's needed, and "needed" is more than low health: an end crystal, a charged
 * respawn anchor or a bed (where it explodes) close enough to take most of your health, a fall that would, or someone
 * nearby coming down at you with a mace. When it's urgent it skips the delay and takes the fastest way. It can also
 * keep a spare totem in a hotbar slot, refilled while it's safe, so the fast way is there when it matters.
 */
public final class AutoTotem extends Module {
    private final ModeSetting mode;
    private final NumberSetting smartHealth;
    private final BoolSetting dangerChecks;
    private final NumberSetting dangerMargin;
    private final ModeSetting source;
    private final BoolSetting swapBack;
    private final NumberSetting keepSlot;
    private final BoolSetting refillOnlySafe;
    private final NumberSetting minDelay;
    private final NumberSetting maxDelay;
    private final NumberSetting popMin;
    private final NumberSetting popMax;
    private final NumberSetting pingComp;
    private final NumberSetting instantHealth;
    private final NumberSetting openMin;
    private final NumberSetting openMax;
    private final NumberSetting cursorTime;
    private final NumberSetting closeMin;
    private final NumberSetting closeMax;
    private final BoolSetting notify;

    private enum Phase { IDLE, HOTBAR_SWAP, HOTBAR_BACK, OPENING, MOVING, PRESS, CLOSING, SETTLE }

    private enum Goal { OFFHAND, HOTBAR }

    private Phase phase = Phase.IDLE;
    private Goal goal;
    private int wait;
    private int invIndex;
    private boolean openedByUs;
    private int originalSlot = -1;
    private int hotbarUsed = -1;
    private double[] from, to;
    private int steps, step, pressTries, openTicks;
    private boolean pending, popped;
    private long lastPopSeen;
    private int lastDelayMs = -1, lastRollMs;
    private float lastDanger;
    private int dangerTicks;
    private String why;

    public AutoTotem() {
        super("Auto Totem", "Keeps a totem in your offhand with a random, ping-adjusted delay, by real key presses and cursor moves. "
                + "Smart mode watches for crystals, anchors, beds, falls and maces, not just low health. Ported from Nyx.", Category.COMBAT);
        group("When");
        mode = add(new ModeSetting("Mode", "Strict: always keep a totem in your offhand. Smart: only when your offhand is empty, your health "
                + "is low, or something nearby could take most of it (lets you hold crystals or a shield otherwise).", "Strict", "Strict", "Smart"));
        smartHealth = add(new NumberSetting("Smart health", "Smart: swap a totem in at or below this health (half hearts, with absorption).",
                14, 1, 36, 1).visibleWhen(() -> mode.is("Smart")));
        dangerChecks = add(new BoolSetting("Danger checks", "Smart: also swap one in for an end crystal, charged respawn anchor or bed "
                + "(where it explodes) that could take most of your health, a fall that could, or a player coming down at you with a mace.",
                true).visibleWhen(() -> mode.is("Smart")));
        dangerMargin = add(new NumberSetting("Danger margin", "How close a threat has to come to your health to count (half hearts).",
                4, 0, 20, 1).visibleWhen(() -> mode.is("Smart") && dangerChecks.get()));
        group("Where from");
        source = add(new ModeSetting("Take from", "Inventory first: keep hotbar totems for emergencies (the fastest way, used whenever it's "
                + "urgent). Hotbar first: always the fastest way.", "Inventory first", "Inventory first", "Hotbar first"));
        swapBack = add(new BoolSetting("Swap back", "After using a hotbar totem, go back to the slot you had.", true));
        keepSlot = add(new NumberSetting("Keep hotbar slot", "Keep a spare totem in this hotbar slot (1-9), refilled from your inventory, so "
                + "the fast way is ready. 0 = off.", 0, 0, 9, 1));
        refillOnlySafe = add(new BoolSetting("Refill only when safe", "Only refill that slot when nobody is within 12 blocks and you're "
                + "standing still (opening the inventory stops you moving).", true).visibleWhen(() -> keepSlot.intValue() > 0));
        group("Timing");
        minDelay = add(new NumberSetting("Min delay", "Shortest random reaction time before a refill.", 60, 0, 1000, 5).unit(" ms"));
        maxDelay = add(new NumberSetting("Max delay", "Longest random reaction time; a new one is picked for every refill.", 160, 0, 1000, 5)
                .unit(" ms"));
        popMin = add(new NumberSetting("After a pop min", "Reaction time right after a totem pops (the next hit can kill).", 30, 0, 500, 5)
                .unit(" ms"));
        popMax = add(new NumberSetting("After a pop max", 90, 0, 500, 5).unit(" ms"));
        pingComp = add(new NumberSetting("Ping compensation", "How much of your ping is taken off the delay: your key press reaches the "
                + "server that much later anyway.", 50, 0, 100, 5).unit("%"));
        instantHealth = add(new NumberSetting("Instant below", "At or below this health (half hearts), skip the delay. 0 = never.", 6, 0, 36, 1));
        group("Inventory");
        openMin = add(new NumberSetting("Open pause min", "Pause after the inventory opens before the cursor moves.", 30, 0, 500, 5).unit(" ms"));
        openMax = add(new NumberSetting("Open pause max", 90, 0, 500, 5).unit(" ms"));
        cursorTime = add(new NumberSetting("Cursor time", "How long the cursor takes to reach the totem.", 80, 0, 400, 10).unit(" ms"));
        closeMin = add(new NumberSetting("Close pause min", "Pause after the swap before the inventory closes.", 40, 0, 500, 5).unit(" ms"));
        closeMax = add(new NumberSetting("Close pause max", 110, 0, 500, 5).unit(" ms"));
        notify = add(new BoolSetting("Pop alerts", "A notification when you pop a totem, with how many are left.", true));
    }

    @Override
    protected void onEnable() {
        reset();
        lastPopSeen = Pops.selfPopAt();
    }

    @Override
    protected void onDisable() {
        if (openedByUs && mc.screen instanceof InventoryScreen) close();
        reset();
        InputLock.release(this);
    }

    private void reset() {
        phase = Phase.IDLE;
        openedByUs = false;
        pending = false;
        popped = false;
        originalSlot = -1;
    }

    @Override
    public String suffix() {
        if (mc.player == null) return null;
        int n = count();
        String s = String.valueOf(n);
        if (why != null) return s + " · " + why;
        return lastDelayMs >= 0 ? s + " (" + lastDelayMs + "ms)" : s;
    }

    private int count() {
        int n = 0;
        for (int i = 0; i < 36; i++) if (mc.player.getInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) n++;
        if (mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) n++;
        return n;
    }

    // =====================================================================================================
    // Each tick
    // =====================================================================================================

    @Override
    public void onPreTick() {
        if (mc.player == null || mc.level == null) {
            reset();
            return;
        }
        long pop = Pops.selfPopAt();
        if (pop != lastPopSeen) {
            lastPopSeen = pop;
            popped = true;
            pending = false; // re-roll with the after-a-pop delay
            if (notify.get()) {
                // the server's inventory update comes after the pop: the used totem may still show in the offhand
                int left = count() - (mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) ? 1 : 0);
                Notifications.push(name, "Popped a totem: " + (left == 0 ? "none left!" : left + " left"),
                        left <= 1 ? Notifications.Kind.WARNING : Notifications.Kind.INFO);
            }
        }
        switch (phase) {
            case HOTBAR_SWAP -> {
                // the slot change reaches the server at the end of the tick it was made: swap one tick later, or the server
                // would swap whatever was held before
                if (InputSim.selectedSlot() != hotbarUsed || !mc.player.getInventory().getItem(hotbarUsed).is(Items.TOTEM_OF_UNDYING)) {
                    finish();
                    return;
                }
                if (!InputSim.click(mc.options.keySwapOffhand)) {
                    why = "bind swap hands";
                    finish();
                    return;
                }
                phase = Phase.HOTBAR_BACK;
                wait = 2;
            }
            case HOTBAR_BACK -> {
                if (--wait > 0) return;
                if (swapBack.get() && originalSlot >= 0 && InputSim.selectedSlot() == hotbarUsed) InputSim.selectSlot(originalSlot);
                settle();
            }
            case SETTLE -> { // wait for the server to confirm the swap before deciding anything again
                if (mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) || --wait <= 0) finish();
            }
            case OPENING -> {
                if (mc.screen instanceof InventoryScreen) {
                    if (--wait > 0) return;
                    startMove();
                } else if (++openTicks > 10) { // it never opened
                    finish();
                }
            }
            case MOVING -> {
                if (!(mc.screen instanceof AbstractContainerScreen<?>)) {
                    finish();
                    return;
                }
                step++;
                double t = Math.min(1, (double) step / steps);
                double e = t * t * (3 - 2 * t); // eased, like a hand
                double wob = t < 1 ? 1.5 : 0;
                InputSim.moveCursorGui(from[0] + (to[0] - from[0]) * e + gauss() * wob, from[1] + (to[1] - from[1]) * e + gauss() * wob);
                if (step >= steps) phase = Phase.PRESS; // the screen picks up the hovered slot on its next frame
            }
            case PRESS -> press();
            case CLOSING -> {
                if (!(mc.screen instanceof InventoryScreen)) { // closed by the player
                    finish();
                    return;
                }
                if (--wait > 0) return;
                if (openedByUs) close();
                settle();
            }
            default -> idle();
        }
    }

    private void idle() {
        why = null;
        if (mc.player.isCreative() || mc.player.isSpectator()) {
            why = "creative";
            return;
        }
        boolean invOpen = mc.screen instanceof InventoryScreen;
        if (mc.screen != null && !invOpen) return; // chat, a chest, the pause menu...
        if (!mc.player.containerMenu.getCarried().isEmpty()) return;
        float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (++dangerTicks >= 2) { // the block scan is light, but there's no need for it every tick
            dangerTicks = 0;
            lastDanger = mode.is("Smart") && dangerChecks.get() ? danger() : 0;
        }
        boolean danger = lastDanger > 0 && lastDanger + dangerMargin.floatValue() >= hp;
        ItemStack off = mc.player.getOffhandItem();
        boolean wanted = !off.is(Items.TOTEM_OF_UNDYING)
                && (mode.is("Strict") || off.isEmpty() || hp <= smartHealth.floatValue() || danger || popped);
        if (!wanted) {
            pending = false;
            popped = false;
            refillHotbar(hp, invOpen);
            return;
        }
        int hot = findTotem(0, 9), inv = findTotem(9, 36);
        if (hot < 0 && inv < 0) {
            why = "no totems";
            pending = false;
            return;
        }
        boolean urgent = danger || (instantHealth.get() > 0 && hp <= instantHealth.floatValue());
        if (!pending) {
            pending = true;
            wait = popped ? roll(popMin.intValue(), popMax.intValue(), pingComp.intValue()) : roll(minDelay.intValue(), maxDelay.intValue(), pingComp.intValue());
            lastDelayMs = lastRollMs;
        }
        if (urgent) wait = 0;
        if (wait-- > 0) return;
        pending = false;
        popped = false;
        InputLock.take(this); // the hands are ours for a moment: staying alive comes first
        boolean fast = hot >= 0 && !invOpen && (urgent || inv < 0 || source.is("Hotbar first"));
        if (fast) {
            originalSlot = InputSim.selectedSlot();
            hotbarUsed = hot;
            if (InputSim.selectedSlot() == hot) { // already holding it: swap straight away
                if (!InputSim.click(mc.options.keySwapOffhand)) {
                    why = "bind swap hands";
                    finish();
                    return;
                }
                phase = Phase.HOTBAR_BACK;
                wait = 2;
                return;
            }
            InputSim.selectSlot(hot);
            phase = Phase.HOTBAR_SWAP;
            return;
        }
        startInventory(inv >= 0 ? inv : hot, Goal.OFFHAND, urgent);
    }

    /** Keeps a spare totem in the chosen hotbar slot, while it's safe. */
    private void refillHotbar(float hp, boolean invOpen) {
        int slot = keepSlot.intValue() - 1;
        if (slot < 0 || mc.player.getInventory().getItem(slot).is(Items.TOTEM_OF_UNDYING)) return;
        int inv = findTotem(9, 36);
        if (inv < 0 || lastDanger > 0 || hp <= smartHealth.floatValue()) return;
        if (refillOnlySafe.get() && !invOpen) {
            if (mc.player.getDeltaMovement().horizontalDistanceSqr() > 1e-4 || !mc.player.onGround()) return;
            for (Player p : mc.level.players()) if (p != mc.player && p.distanceTo(mc.player) < 12) return;
        }
        InputLock.take(this);
        startInventory(inv, Goal.HOTBAR, false);
    }

    private void startInventory(int index, Goal g, boolean urgent) {
        invIndex = index;
        goal = g;
        pressTries = 0;
        if (mc.screen instanceof InventoryScreen) { // already open (the player's): just move it, and leave it open
            openedByUs = false;
            startMove();
            return;
        }
        if (!InputSim.click(mc.options.keyInventory)) {
            why = "bind inventory";
            finish();
            return;
        }
        openedByUs = true;
        openTicks = 0;
        phase = Phase.OPENING;
        wait = urgent ? 1 : Math.max(1, roll(openMin.intValue(), openMax.intValue(), 0));
    }

    private void startMove() {
        double[] at = slotCenter(invIndex);
        if (at == null) {
            phase = Phase.CLOSING;
            wait = 1;
            return;
        }
        from = InputSim.cursorGui();
        // somewhere on the slot, not always its exact centre
        to = new double[]{at[0] + clamp(gauss() * 2.5, 5), at[1] + clamp(gauss() * 2.5, 5)};
        steps = Math.max(1, (int) Math.round(cursorTime.get() / 50.0));
        step = 0;
        phase = Phase.MOVING;
    }

    private void press() {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            finish();
            return;
        }
        Slot hovered = ((ContainerScreenAccess) screen).erebus$hoveredSlot();
        if (hovered == null || hovered.container != mc.player.getInventory() || hovered.getContainerSlot() != invIndex) {
            if (++pressTries > 3) { // can't get onto it (moved by hand?): give up for now
                phase = Phase.CLOSING;
                wait = 1;
                return;
            }
            double[] at = slotCenter(invIndex);
            if (at != null) InputSim.moveCursorGui(at[0], at[1]);
            return;
        }
        KeyMapping key = goal == Goal.HOTBAR ? mc.options.keyHotbarSlots[keepSlot.intValue() - 1] : mc.options.keySwapOffhand;
        if (!InputSim.pressInScreen(key)) why = goal == Goal.HOTBAR ? "bind hotbar keys" : "bind swap hands";
        phase = Phase.CLOSING;
        float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        boolean urgent = instantHealth.get() > 0 && hp <= instantHealth.floatValue();
        wait = urgent ? 1 : Math.max(1, roll(closeMin.intValue(), closeMax.intValue(), 0));
    }

    private void close() {
        if (!InputSim.pressInScreen(mc.options.keyInventory) && mc.screen != null) mc.screen.onClose();
        KeyMapping.setAll(); // pick up the keys you're still holding (a screen lets go of them all)
    }

    /** Done acting: wait (a few ticks at most) until the offhand shows the totem, then decide again. */
    private void settle() {
        phase = Phase.SETTLE;
        wait = 8;
        openedByUs = false;
        originalSlot = -1;
        InputLock.release(this);
    }

    private void finish() {
        phase = Phase.IDLE;
        openedByUs = false;
        originalSlot = -1;
        InputLock.release(this);
    }

    /** Screen position (GUI coordinates) of the middle of the slot holding inventory index {@code index}, or null. */
    private double[] slotCenter(int index) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return null;
        var acc = (ContainerScreenAccess) screen;
        for (Slot s : screen.getMenu().slots) {
            if (s.container == mc.player.getInventory() && s.getContainerSlot() == index) {
                return new double[]{acc.erebus$leftPos() + s.x + 8, acc.erebus$topPos() + s.y + 8};
            }
        }
        return null;
    }

    /** Inventory index of a totem in [from, to), or -1. */
    private int findTotem(int from, int to) {
        for (int i = from; i < to; i++) if (mc.player.getInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) return i;
        return -1;
    }

    // =====================================================================================================
    // Danger
    // =====================================================================================================

    /** The most damage something nearby could do to you right now (half hearts), roughly; 0 if nothing. */
    private float danger() {
        Player me = mc.player;
        float worst = 0;
        // end crystals
        for (Entity e : mc.level.getEntities(me, me.getBoundingBox().inflate(12), e -> e instanceof EndCrystal && e.isAlive())) {
            worst = Math.max(worst, ExplosionDamage.damage(me, e.position(), ExplosionDamage.CRYSTAL_POWER, null, null));
        }
        // respawn anchors outside the Nether, beds outside the Overworld: both explode when used there
        boolean nether = mc.level.dimension() == Level.NETHER, overworld = mc.level.dimension() == Level.OVERWORLD;
        BlockPos c = me.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-6, -4, -6), c.offset(6, 4, 6))) {
            BlockState s = mc.level.getBlockState(p);
            if (!nether && s.is(Blocks.RESPAWN_ANCHOR) && s.getValue(RespawnAnchorBlock.CHARGE) > 0) {
                worst = Math.max(worst, ExplosionDamage.damage(me, Vec3.atCenterOf(p), ExplosionDamage.ANCHOR_POWER, null, p.immutable()));
            } else if (!overworld && s.getBlock() instanceof BedBlock) {
                worst = Math.max(worst, ExplosionDamage.damage(me, Vec3.atCenterOf(p), 5f, null, p.immutable()));
            }
        }
        // a fall (feather falling and armor aside, which only makes this cautious)
        if (!me.isFallFlying() && !me.isInWater() && me.fallDistance > 3) worst = Math.max(worst, (float) me.fallDistance - 3);
        // someone coming down at you with a mace: a smash can take everything
        for (Player p : mc.level.players()) {
            if (p == me || p.distanceTo(me) > 5 || !p.getMainHandItem().is(Items.MACE)) continue;
            if (p.fallDistance > 1.5 && p.getDeltaMovement().y < 0) worst = Math.max(worst, 6 + 4 * (float) Math.min(3, p.fallDistance) + 2 * (float) Math.max(0, p.fallDistance - 3));
        }
        return worst;
    }

    // =====================================================================================================
    // Randomness
    // =====================================================================================================

    /** Random time between a and b ms (minus pingPercent of your ping), as ticks rounded randomly. */
    private int roll(int a, int b, int pingPercent) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        double ms = lo + ThreadLocalRandom.current().nextDouble() * (hi - lo);
        if (pingPercent > 0 && mc.getConnection() != null && mc.player != null) {
            var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if (info != null) ms = Math.max(0, ms - info.getLatency() * pingPercent / 100.0);
        }
        lastRollMs = (int) Math.round(ms);
        double t = ms / 50.0;
        int ticks = (int) Math.floor(t);
        if (ThreadLocalRandom.current().nextDouble() < t - ticks) ticks++; // 75 ms is 1 or 2 ticks, 1.5 on average
        return ticks;
    }

    private static double gauss() {
        return ThreadLocalRandom.current().nextGaussian();
    }

    private static double clamp(double v, double m) {
        return Math.max(-m, Math.min(m, v));
    }
}
