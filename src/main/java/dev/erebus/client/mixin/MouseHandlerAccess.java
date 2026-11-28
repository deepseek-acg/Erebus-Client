package dev.erebus.client.mixin;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The game's own mouse handler, so a simulated cursor move or button goes the way the mouse's would. */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccess {
    @Invoker("onMove")
    void erebus$onMove(long window, double x, double y);

    @Invoker("onButton")
    void erebus$onButton(long window, MouseButtonInfo button, int action);
}
