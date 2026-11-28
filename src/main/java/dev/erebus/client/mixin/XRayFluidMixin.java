package dev.erebus.client.mixin;

import dev.erebus.client.render.XRayState;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** X-Ray: water and lava aren't drawn either, when set to hide them. */
@Mixin(LiquidBlockRenderer.class)
public abstract class XRayFluidMixin {
    @Inject(method = "tesselate", at = @At("HEAD"), cancellable = true)
    private void erebus$xrayFluid(CallbackInfo ci) {
        if (XRayState.hidesFluids()) ci.cancel();
    }
}
