package dev.erebus.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Key presses as if they came from the keyboard or mouse. A click is queued on the key's binding and the game
 * handles it in its own input step, with its own crosshair and its own packets.
 */
public final class InputSim {
    private InputSim() {
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    /** One press of a binding (handled by the game later this tick). False if the binding has no key. */
    public static boolean click(KeyMapping mapping) {
        InputConstants.Key key = KeyBindingHelper.getBoundKeyOf(mapping);
        if (key == null || key.equals(InputConstants.UNKNOWN)) return false;
        KeyMapping.click(key);
        return true;
    }

    /** Right mouse button: use the held item on whatever the crosshair is on. */
    public static boolean use() {
        return click(mc().options.keyUse);
    }

    /**
     * Picks a hotbar slot by pressing its number key. If that key is unbound, selects the slot the way the scroll
     * wheel does. Either way the game sends the slot change itself.
     */
    public static void selectSlot(int slot) {
        if (mc().player == null || mc().player.getInventory().getSelectedSlot() == slot) return;
        if (!click(mc().options.keyHotbarSlots[slot])) mc().player.getInventory().setSelectedSlot(slot);
    }

    public static int selectedSlot() {
        return mc().player == null ? -1 : mc().player.getInventory().getSelectedSlot();
    }

    /** Holds or lets go of the sneak key, as if it were pressed on the keyboard. */
    public static void sneak(boolean down) {
        mc().options.keyShift.setDown(down);
    }

    public static boolean sneakKeyDown() {
        return mc().options.keyShift.isDown();
    }

    /** Whether the player is physically holding the sneak key, whatever its binding state says. */
    public static boolean sneakHeldPhysically() {
        InputConstants.Key bound = KeyBindingHelper.getBoundKeyOf(mc().options.keyShift);
        if (bound == null || bound.getType() != InputConstants.Type.KEYSYM) return false;
        return InputConstants.isKeyDown(mc().getWindow(), bound.getValue());
    }
}
