package dev.erebus.client.input;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * Predicts what the crosshair would hit for a given view, with the same raycast the game uses for the crosshair
 * (block outlines, no fluids, entities in front of blocks, block and entity reach), and finds a view that puts
 * the crosshair on a chosen block face.
 */
public final class Crosshair {
    private Crosshair() {
    }

    /** The view a target is reached with, and where on the block the crosshair lands. */
    public record Aim(float yaw, float pitch, Vec3 point) {
    }

    /** What the game's crosshair raycast would hit with the player looking at (yaw, pitch). */
    public static HitResult predict(float yaw, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        double blockRange = p.blockInteractionRange();
        double entityRange = p.entityInteractionRange();
        double g = Math.max(blockRange, entityRange);
        double h = g * g;
        Vec3 eye = p.getEyePosition(1f);
        Vec3 view = Vec3.directionFromRotation(pitch, yaw);
        BlockHitResult blocks = p.level().clip(new ClipContext(eye, eye.add(view.scale(g)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        double blockDist = blocks.getLocation().distanceToSqr(eye);
        if (blocks.getType() != HitResult.Type.MISS) {
            h = blockDist;
            g = Math.sqrt(blockDist);
        }
        Vec3 end = eye.add(view.scale(g));
        AABB box = p.getBoundingBox().expandTowards(view.scale(g)).inflate(1.0);
        EntityHitResult entity = ProjectileUtil.getEntityHitResult(p, eye, end, box, EntitySelector.CAN_BE_PICKED, h);
        if (entity != null && entity.getLocation().distanceToSqr(eye) < blockDist) {
            return entity.getLocation().closerThan(eye, entityRange) ? entity : BlockHitResult.miss(entity.getLocation(), Direction.UP, BlockPos.containing(entity.getLocation()));
        }
        return blocks.getLocation().closerThan(eye, blockRange) ? blocks
                : BlockHitResult.miss(blocks.getLocation(), Direction.UP, BlockPos.containing(blocks.getLocation()));
    }

    /** True if the crosshair with this view would land on {@code pos} (on {@code face}, when given). */
    public static boolean hits(float yaw, float pitch, BlockPos pos, Direction face) {
        return hits(predict(yaw, pitch), pos, face);
    }

    public static boolean hits(HitResult hit, BlockPos pos, Direction face) {
        return hit instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(pos)
                && (face == null || b.getDirection() == face);
    }

    /** Where the crosshair is now. */
    public static HitResult current() {
        LocalPlayer p = Minecraft.getInstance().player;
        return predict(p.getYRot(), p.getXRot());
    }

    /**
     * A view that puts the crosshair on {@code face} of the block at {@code pos} (any face when null), checked with
     * the game's own raycast so nothing in the way (snow, plants, berry bushes, item frames, crystals...) takes
     * the click. Of all the views that work, the one closest to where the player looks now. Null if none does.
     */
    public static Aim solve(BlockPos pos, Direction face) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        BlockState state = p.level().getBlockState(pos);
        VoxelShape shape = state.getShape(p.level(), pos);
        List<AABB> boxes = shape.isEmpty() ? List.of(new AABB(0, 0, 0, 1, 1, 1)) : shape.toAabbs();
        Vec3 eye = p.getEyePosition(1f);
        float curYaw = p.getYRot(), curPitch = p.getXRot();
        Aim best = null;
        double bestCost = Double.MAX_VALUE;
        for (AABB local : boxes) {
            AABB b = local.move(pos);
            for (Direction d : Direction.values()) {
                if (face != null && d != face) continue;
                // only faces turned towards the eye can be hit
                double plane = switch (d) {
                    case DOWN -> b.minY;
                    case UP -> b.maxY;
                    case NORTH -> b.minZ;
                    case SOUTH -> b.maxZ;
                    case WEST -> b.minX;
                    case EAST -> b.maxX;
                };
                double eyeSide = switch (d.getAxis()) {
                    case X -> eye.x;
                    case Y -> eye.y;
                    case Z -> eye.z;
                };
                if ((d.getAxisDirection() == Direction.AxisDirection.POSITIVE) ? eyeSide <= plane : eyeSide >= plane) continue;
                for (int i = 0; i < 5; i++) {
                    for (int j = 0; j < 5; j++) {
                        double u = 0.12 + 0.76 * i / 4.0, v = 0.12 + 0.76 * j / 4.0;
                        Vec3 pt = switch (d.getAxis()) {
                            case X -> new Vec3(plane, Mth.lerp(u, b.minY, b.maxY), Mth.lerp(v, b.minZ, b.maxZ));
                            case Y -> new Vec3(Mth.lerp(u, b.minX, b.maxX), plane, Mth.lerp(v, b.minZ, b.maxZ));
                            case Z -> new Vec3(Mth.lerp(u, b.minX, b.maxX), Mth.lerp(v, b.minY, b.maxY), plane);
                        };
                        Vec3 to = pt.subtract(eye);
                        float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
                        float pitch = (float) Math.toDegrees(-Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
                        double cost = Math.hypot(Mth.wrapDegrees(yaw - curYaw), pitch - curPitch);
                        if (cost >= bestCost) continue;
                        // pull the view onto the nearest whole-yaw step the camera can reach from here
                        float qYaw = curYaw + quantize(Mth.wrapDegrees(yaw - curYaw));
                        float qPitch = Mth.clamp(curPitch + quantize(pitch - curPitch), -90f, 90f);
                        if (!hits(qYaw, qPitch, pos, face == null ? null : d)) continue;
                        best = new Aim(qYaw, qPitch, pt);
                        bestCost = cost;
                    }
                }
            }
        }
        return best;
    }

    /** Rounds a turn to whole mouse counts, which is the only kind of turn the camera can make. */
    private static float quantize(float degrees) {
        double step = MouseSim.degreesPerCount();
        return (float) (Math.rint(degrees / step) * step);
    }
}
