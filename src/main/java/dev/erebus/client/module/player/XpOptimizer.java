package dev.erebus.client.module.player;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.input.InputSim;
import dev.erebus.client.input.MouseSim;
import dev.erebus.client.mixin.MinecraftAccess;
import dev.erebus.client.module.Category;
import dev.erebus.client.module.Module;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.ModeSetting;
import dev.erebus.client.setting.NumberSetting;
import dev.erebus.client.util.Colors;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Throws experience bottles for you, clicking the use key like a person would (a click speed that wanders between
 * two values, with Gaussian jitter on every gap), and stops as soon as the XP already in the air and on the ground
 * will finish repairing your mending gear, so no bottle is wasted. Only real key presses (and, to look down, mouse
 * movement): the game throws each bottle itself.
 */
public final class XpOptimizer extends Module {
    /** A bottle gives 3 + 0..4 + 0..4 experience: 7 on average. */
    private static final double XP_PER_BOTTLE = 7.0;

    private final ModeSetting activation;
    private final BoolSetting onlyLookingDown;
    private final BoolSetting lookDown;
    private final BoolSetting autoSwitch;
    private final BoolSetting stopWhenRepaired;
    private final NumberSetting extraBottles;
    private final NumberSetting minCps;
    private final NumberSetting maxCps;
    private final NumberSetting jitter;
    private final NumberSetting drift;
    private final NumberSetting maxPerTick;

    private long nextClick;
    private long nextDrift;
    private double cps;
    private double targetCps;
    private int previousSlot = -1;
    private int waitTicks;
    private boolean lookedDown;
    private float startYaw, startPitch;
    private int thrown;
    private long lastActive;
    private int need;
    private double incoming;
    /**
     * XP thrown but not yet absorbed, by our own count. The orbs on the ground undercount it: the game merges orbs
     * near each other into one, and doesn't tell the client how many it holds.
     */
    private double pending;
    private int prevNeed = -1;
    private int prevTotalXp = -1;
    private long lastThrow;

    public XpOptimizer() {
        super("XP Optimizer", "Throws experience bottles with a human click rhythm (a wandering click speed and Gaussian jitter on "
                + "every click) and stops the moment the XP in the air and on the ground will finish repairing your mending "
                + "gear, so no bottle is wasted. Real key presses only.", Category.PLAYER);
        group("When");
        activation = add(new ModeSetting("Activation", "Hold use: takes over when you hold right-click with bottles (the game's "
                + "own repeat is held back). While enabled: switches to bottles, looks down and throws until your gear is repaired, "
                + "then turns itself off (bind it to a key).", "Hold use", "Hold use", "While enabled"));
        onlyLookingDown = add(new BoolSetting("Only looking down", "With Hold use, only take over while you look down (so a bottle "
                + "thrown at something isn't held back).", true).visibleWhen(() -> activation.is("Hold use")));
        lookDown = add(new BoolSetting("Look down", "With While enabled, turn the view straight down by simulated mouse "
                + "movement first, and back afterwards.", true).visibleWhen(() -> activation.is("While enabled")));
        autoSwitch = add(new BoolSetting("Switch to bottles", "With While enabled, pick the bottles from the hotbar with its "
                + "number key, and go back to the slot you had afterwards.", true).visibleWhen(() -> activation.is("While enabled")));

        group("Repair");
        stopWhenRepaired = add(new BoolSetting("Stop when repaired", "Stop throwing once the XP in the air and on the ground "
                + "covers what your mending gear (armor and hands) still needs. With Hold use, holding right-click longer "
                + "throws nothing more.", true));
        extraBottles = add(new NumberSetting("Extra bottles", "Throw this many more than the estimate says (each gives 3 to 11 "
                + "XP, 7 on average).", 0, 0, 8, 1).visibleWhen(stopWhenRepaired::get));

        group("Clicking");
        minCps = add(new NumberSetting("Min CPS", "Slowest click speed (clicks per second).", 9, 1, 20, 0.5));
        maxCps = add(new NumberSetting("Max CPS", "Fastest click speed. The speed wanders between the two.", 13, 1, 20, 0.5));
        jitter = add(new NumberSetting("Jitter", "Gaussian noise on every gap between clicks (standard deviation).", 16, 0, 60, 1)
                .unit("ms"));
        drift = add(new NumberSetting("Speed change", "How often the click speed picks a new value to drift towards.", 0.9, 0.2, 3, 0.1)
                .unit("s"));
        maxPerTick = add(new NumberSetting("Max per tick", "Most bottles in one game tick (20 a second).", 2, 1, 4, 1));
        addVisuals("A repair bar under the crosshair: your mending gear's durability, what the XP on its way will add, and "
                + "how much is still needed.");
    }

    @Override
    protected void onEnable() {
        reset();
        thrown = 0;
        if (mc.player != null && activation.is("While enabled")) {
            startYaw = mc.player.getYRot();
            startPitch = mc.player.getXRot();
        }
    }

    @Override
    protected void onDisable() {
        finish();
    }

    private void reset() {
        pending = 0;
        prevNeed = -1;
        prevTotalXp = -1;
        nextClick = 0;
        nextDrift = 0;
        cps = 0;
        lookedDown = false;
        waitTicks = 0;
    }

    @Override
    public String info() {
        if (mc.player == null) return null;
        int bottles = mc.player.getInventory().countItem(Items.EXPERIENCE_BOTTLE);
        return need > 0 ? need + " xp · " + bottles : String.valueOf(bottles);
    }

    @Override
    public void onPreTick() {
        if (mc.player == null || mc.level == null) return;
        need = xpNeeded();
        double visible = xpIncoming();
        // what's been absorbed since last tick: repairs (one point per two durability) and the XP bar
        if (prevNeed >= 0 && need < prevNeed) pending -= prevNeed - need;
        int total = mc.player.totalExperience;
        if (prevTotalXp >= 0 && total > prevTotalXp) pending -= total - prevTotalXp;
        prevNeed = need;
        prevTotalXp = total;
        if (visible <= 0 && System.currentTimeMillis() - lastThrow > 1500) pending = 0; // everything has landed
        pending = Math.max(0, pending);
        incoming = Math.max(visible, pending);
        boolean covered = stopWhenRepaired.get() && incoming >= need + extraBottles.get() * XP_PER_BOTTLE;
        if (mc.screen != null || mc.player.isUsingItem() || dev.erebus.client.input.InputLock.heldByOther(this)) {
            nextClick = 0;
            return;
        }
        if (activation.is("Hold use")) holdUse(covered);
        else whileEnabled(covered);
    }

    // ---- Hold use: the player holds right-click with bottles, we do the clicking

    private void holdUse(boolean covered) {
        boolean bottle = holding();
        boolean held = mc.options.keyUse.isDown();
        if (!bottle || !held || onlyLookingDown.get() && mc.player.getXRot() < 50f) {
            nextClick = 0;
            return;
        }
        holdBackRepeat(); // whether we throw or not, the game's own hold-repeat doesn't
        lastActive = System.currentTimeMillis();
        if (covered) {
            nextClick = 0;
            return;
        }
        clicks();
    }

    // ---- While enabled: switch, look down, throw until repaired, put everything back

    private void whileEnabled(boolean covered) {
        lastActive = System.currentTimeMillis();
        if (waitTicks > 0) { // the slot change reaches the server at the end of a tick: throw from the next one
            waitTicks--;
            return;
        }
        boolean done = stopWhenRepaired.get() ? need <= 0 || covered : false;
        if (done) {
            if (incoming <= 0.5 || need <= 0) toggle(); // wait until what's in the air has landed, then hand everything back
            return;
        }
        if (!holding()) {
            int slot = hotbarBottles();
            if (slot < 0 || !autoSwitch.get()) {
                chat(slot < 0 ? "No experience bottles in your hotbar." : "Hold the bottles (or turn on Switch to bottles).");
                toggle();
                return;
            }
            if (previousSlot < 0) previousSlot = InputSim.selectedSlot();
            InputSim.selectSlot(slot);
            waitTicks = 1;
            return;
        }
        if (lookDown.get() && mc.player.getXRot() < 80f) {
            if (!lookedDown) {
                startYaw = mc.player.getYRot();
                startPitch = mc.player.getXRot();
                lookedDown = true;
            }
            MouseSim.aim(mc.player.getYRot(), 90f, MouseSim.Profile.DEFAULT, false);
            return;
        }
        clicks();
    }

    private void finish() {
        if (mc.player == null) return;
        if (lookedDown) MouseSim.returnTo(startYaw, startPitch, MouseSim.Profile.DEFAULT);
        if (previousSlot >= 0) InputSim.selectSlot(previousSlot);
        previousSlot = -1;
        lookedDown = false;
        if (activation.is("While enabled")) {
            if (thrown > 0) chat("Threw " + thrown + (thrown == 1 ? " bottle" : " bottles") + (need <= 0 ? ", gear repaired." : "."));
            else if (need <= 0) chat("Nothing to repair: no mending gear you wear or hold is damaged.");
        }
    }

    // ---- Clicking

    /** Presses use for every click that's due by now (a few at most per tick, never catching up a backlog). */
    private void clicks() {
        long now = System.nanoTime();
        if (nextClick == 0) nextClick = now;
        if (now - nextClick > 250_000_000L) nextClick = now; // paused for a while: start fresh, no burst
        int n = 0;
        while (now >= nextClick && n < maxPerTick.intValue()) {
            if (!InputSim.use()) return;
            thrown++;
            n++;
            pending += XP_PER_BOTTLE;
            lastThrow = System.currentTimeMillis();
            nextClick += gapNanos(now);
        }
    }

    /** The gap before the next click: from a click speed that drifts between Min and Max CPS, plus Gaussian jitter. */
    private long gapNanos(long now) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double lo = Math.min(minCps.get(), maxCps.get()), hi = Math.max(minCps.get(), maxCps.get());
        if (cps <= 0) cps = lo + r.nextDouble() * (hi - lo);
        if (now >= nextDrift) {
            targetCps = lo + r.nextDouble() * (hi - lo);
            nextDrift = now + (long) (drift.get() * (0.6 + 0.8 * r.nextDouble()) * 1e9);
        }
        cps += (targetCps - cps) * 0.25;
        double base = 1000.0 / cps;
        double ms = base + r.nextGaussian() * jitter.get();
        ms = Math.max(base * 0.35, Math.min(base * 2.5, ms));
        return (long) (ms * 1e6);
    }

    /** Keeps the game's own repeat-while-held from firing on its own schedule. */
    private void holdBackRepeat() {
        MinecraftAccess a = (MinecraftAccess) mc;
        if (a.erebus$rightClickDelay() < 2) a.erebus$setRightClickDelay(2);
    }

    // ---- What the gear needs and what's on its way

    /** Experience still needed to fully repair the mending gear you wear and hold (one point repairs two durability). */
    private int xpNeeded() {
        int total = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR && slot.getType() != EquipmentSlot.Type.HAND) continue;
            ItemStack s = mc.player.getItemBySlot(slot);
            if (s.isEmpty() || !s.isDamaged() || !mending(s)) continue;
            total += (s.getDamageValue() + 1) / 2;
        }
        return total;
    }

    /** Durability of the mending gear, as {current, max}, for the bar. */
    private int[] durability() {
        int cur = 0, max = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR && slot.getType() != EquipmentSlot.Type.HAND) continue;
            ItemStack s = mc.player.getItemBySlot(slot);
            if (s.isEmpty() || !s.isDamageableItem() || !mending(s)) continue;
            cur += s.getMaxDamage() - s.getDamageValue();
            max += s.getMaxDamage();
        }
        return new int[]{cur, max};
    }

    private static boolean mending(ItemStack s) {
        for (Holder<Enchantment> h : s.getEnchantments().keySet()) if (h.is(Enchantments.MENDING)) return true;
        return false;
    }

    /** XP on its way to you: bottles in the air near you (7 each on average) and orbs close enough to be pulled in. */
    private double xpIncoming() {
        AABB box = mc.player.getBoundingBox().inflate(8);
        double xp = 0;
        for (Entity e : mc.level.getEntities(mc.player, box, e -> e instanceof ThrownExperienceBottle || e instanceof ExperienceOrb)) {
            if (e instanceof ExperienceOrb orb) xp += orb.getValue();
            else xp += XP_PER_BOTTLE;
        }
        return xp;
    }

    private boolean holding() {
        return mc.player.getMainHandItem().is(Items.EXPERIENCE_BOTTLE);
    }

    private int hotbarBottles() {
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).is(Items.EXPERIENCE_BOTTLE)) return i;
        return -1;
    }

    // ---- The repair bar

    @Override
    public void onRenderOverlay(Ui ui) {
        if (!visuals() || mc.player == null || mc.options.hideGui) return;
        if (System.currentTimeMillis() - lastActive > 1500) return;
        int[] d = durability();
        if (d[1] == 0) return;
        int w = 90, h = 5;
        int x = ui.g.guiWidth() / 2 - w / 2, y = ui.g.guiHeight() / 2 + 14;
        float have = d[0] / (float) d[1];
        float coming = (float) Math.min(1.0 - have, incoming * 2 / d[1]);
        ui.round(x - 1, y - 1, w + 2, h + 2, 3, 0xB0101016);
        ui.round(x, y, Math.max(2, Math.round(w * have)), h, 2, ui.theme.accent());
        if (coming > 0.001f) {
            int cx = x + Math.round(w * have);
            ui.round(cx, y, Math.max(1, Math.round(w * coming)), h, 2, Colors.withAlpha(ui.theme.accent(), 0x60));
        }
        String text = need <= 0 ? "Repaired" : need + " xp needed · ~" + (int) Math.ceil(Math.max(0, need - incoming) / XP_PER_BOTTLE)
                + " more bottles";
        ui.textCentered(text, x + w / 2, y + h + 3, need <= 0 ? dev.erebus.client.theme.Theme.GOOD : ui.theme.text());
        ui.textCentered(String.format(Locale.ROOT, "%.0f cps", cps), x + w / 2, y + h + 13, ui.theme.dim());
    }
}
