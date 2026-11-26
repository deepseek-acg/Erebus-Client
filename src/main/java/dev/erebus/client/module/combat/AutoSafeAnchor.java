package dev.erebus.client.module.combat;

import dev.erebus.client.input.Crosshair;
import dev.erebus.client.module.Category;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * One press, one anchor: place a respawn anchor where you look, charge it, shield the blast, detonate, then turn
 * off. Ported from Mist's AutoSafeAnchor. The sequence itself (and every way it can be tuned) lives in
 * {@link AnchorSequence}; this module only decides where it starts and when it repeats.
 */
public final class AutoSafeAnchor extends AnchorSequence {
    private final BoolSetting useExisting;
    private final BoolSetting repeatWhileHeld;
    private final NumberSetting repeatDelay;
    private int cooldown;

    public AutoSafeAnchor() {
        super("Auto Safe Anchor", "One press: places a respawn anchor where you look, charges it, shields the blast and detonates it. "
                + "Done entirely with simulated mouse and key input, never with crafted packets.", Category.COMBAT);
        group("General");
        useExisting = add(new BoolSetting("Use existing", "If you look at an anchor that's already there, charge and detonate that one.", true));
        repeatWhileHeld = add(new BoolSetting("Repeat while held", "Keep running sequences while the keybind is held down, instead of one per press.", false));
        repeatDelay = add(new NumberSetting("Repeat delay", "Ticks between sequences while the key is held.", 2, 0, 40, 1).visibleWhen(repeatWhileHeld::get));
        addSequenceSettings();
    }

    @Override
    protected boolean startedByPlayer() {
        return true;
    }

    @Override
    protected boolean announcesToggle() {
        return false;
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        cooldown = 0;
        if (mc.player == null || mc.level == null) { // e.g. restored from the config at startup
            setEnabled(false, false);
            return;
        }
        startFromLook();
    }

    @Override
    protected void idleTick() {
        if (repeatWhileHeld.get() && keyHeld()) {
            if (cooldown > 0) {
                cooldown--;
                return;
            }
            startFromLook();
            return;
        }
        setEnabled(false, false);
    }

    @Override
    protected boolean shouldStop() {
        return repeatWhileHeld.get() && !keyHeld(); // let go of the key: stop where it is
    }

    @Override
    protected void onSequenceEnd(boolean completed) {
        if (repeatWhileHeld.get() && keyHeld() && completed) {
            cooldown = delay(repeatDelay);
            return;
        }
        setEnabled(false, false);
    }

    private void startFromLook() {
        HitResult look = Crosshair.current();
        if (!(look instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            abort("you aren't looking at a block");
            return;
        }
        BlockPos looked = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(looked);
        BlockPos anchor;
        if (state.is(Blocks.RESPAWN_ANCHOR) && useExisting.get()) anchor = looked;
        else anchor = state.canBeReplaced() ? looked : looked.relative(hit.getDirection());
        beginAt(anchor, hit);
    }
}
