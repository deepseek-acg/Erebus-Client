package dev.erebus.client.mixin;

import dev.erebus.client.render.XRayState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * X-Ray (ported from Nyx): hidden blocks have nothing to draw and don't cover the faces of blocks next to them; the
 * blocks shown are drawn at full brightness without corner shadows (buried in stone, no light reaches them).
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class XRayBlockStateMixin {
    @Inject(method = "getRenderShape", at = @At("HEAD"), cancellable = true)
    private void erebus$xrayShape(CallbackInfoReturnable<RenderShape> cir) {
        if (XRayState.enabled && XRayState.hides((BlockState) (Object) this)) cir.setReturnValue(RenderShape.INVISIBLE);
    }

    @Inject(method = "canOcclude", at = @At("HEAD"), cancellable = true)
    private void erebus$xrayOcclude(CallbackInfoReturnable<Boolean> cir) {
        if (XRayState.enabled && XRayState.hides((BlockState) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(method = "isSolidRender", at = @At("HEAD"), cancellable = true)
    private void erebus$xraySolid(CallbackInfoReturnable<Boolean> cir) {
        if (XRayState.enabled && XRayState.hides((BlockState) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(method = "getFaceOcclusionShape", at = @At("HEAD"), cancellable = true)
    private void erebus$xrayFace(Direction direction, CallbackInfoReturnable<VoxelShape> cir) {
        if (XRayState.enabled && XRayState.hides((BlockState) (Object) this)) cir.setReturnValue(Shapes.empty());
    }

    @Inject(method = "emissiveRendering", at = @At("HEAD"), cancellable = true)
    private void erebus$xrayBright(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (XRayState.enabled && !XRayState.hides((BlockState) (Object) this)) cir.setReturnValue(true);
    }

    @Inject(method = "getShadeBrightness", at = @At("HEAD"), cancellable = true)
    private void erebus$xrayShade(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
        if (XRayState.enabled) cir.setReturnValue(1.0f);
    }
}
