package dev.erebus.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a container screen sits and which slot the cursor is over. */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccess {
    @Accessor("leftPos")
    int erebus$leftPos();

    @Accessor("topPos")
    int erebus$topPos();

    @Accessor("hoveredSlot")
    Slot erebus$hoveredSlot();
}
