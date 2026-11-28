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

    // ---------------------------------------------------------------- inside screens (inventory)

    /**
     * Presses and lets go of a binding's key through the game's own key or mouse-button handler, so an open screen
     * gets it exactly as if it came from the keyboard or mouse (the inventory's swap-to-offhand and number keys work
     * on the hovered slot). False if the binding has no key.
     */
    public static boolean pressInScreen(KeyMapping mapping) {
        InputConstants.Key key = KeyBindingHelper.getBoundKeyOf(mapping);
        if (key == null || key.equals(InputConstants.UNKNOWN)) return false;
        long window = mc().getWindow().handle();
        if (key.getType() == InputConstants.Type.MOUSE) {
            var mouse = (dev.erebus.client.mixin.MouseHandlerAccess) mc().mouseHandler;
            var info = new net.minecraft.client.input.MouseButtonInfo(key.getValue(), 0);
            mouse.erebus$onButton(window, info, org.lwjgl.glfw.GLFW.GLFW_PRESS);
            mouse.erebus$onButton(window, info, org.lwjgl.glfw.GLFW.GLFW_RELEASE);
            return true;
        }
        int code = key.getValue();
        int scan = key.getType() == InputConstants.Type.SCANCODE ? code : org.lwjgl.glfw.GLFW.glfwGetKeyScancode(code);
        int glfwKey = key.getType() == InputConstants.Type.SCANCODE ? -1 : code;
        var kb = (dev.erebus.client.mixin.KeyboardHandlerAccess) mc().keyboardHandler;
        kb.erebus$keyPress(window, org.lwjgl.glfw.GLFW.GLFW_PRESS, new net.minecraft.client.input.KeyEvent(glfwKey, scan, 0));
        kb.erebus$keyPress(window, org.lwjgl.glfw.GLFW.GLFW_RELEASE, new net.minecraft.client.input.KeyEvent(glfwKey, scan, 0));
        return true;
    }

    /** Where the cursor is, in GUI coordinates (what screens use). */
    public static double[] cursorGui() {
        var w = mc().getWindow();
        return new double[]{mc().mouseHandler.xpos() * w.getGuiScaledWidth() / w.getScreenWidth(),
                mc().mouseHandler.ypos() * w.getGuiScaledHeight() / w.getScreenHeight()};
    }

    /**
     * Moves the mouse cursor to a point in GUI coordinates: the real cursor is moved, and the game is told the way a
     * mouse movement tells it (so the screen sees the new position at once).
     */
    public static void moveCursorGui(double gx, double gy) {
        var w = mc().getWindow();
        double x = gx * w.getScreenWidth() / w.getGuiScaledWidth(), y = gy * w.getScreenHeight() / w.getGuiScaledHeight();
        org.lwjgl.glfw.GLFW.glfwSetCursorPos(w.handle(), x, y);
        ((dev.erebus.client.mixin.MouseHandlerAccess) mc().mouseHandler).erebus$onMove(w.handle(), x, y);
    }
}
