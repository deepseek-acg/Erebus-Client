package dev.erebus.client.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The game's own key handler, so a simulated key goes exactly the way a key from the keyboard does (screens included). */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerAccess {
    @Invoker("keyPress")
    void erebus$keyPress(long window, int action, KeyEvent event);
}
