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

    /** Which of the points that work to aim at. */
    public enum AimPoint {
        /** The point needing the smallest turn from where the crosshair is now. */
        NEAREST,
        /** The point closest to the middle of the face. */
        CENTER,
        /** Any point that works, picked at random. */
        RANDOM
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
        return solve(pos, face, AimPoint.NEAREST, 0.12);
    }

    /**
     * Like {@link #solve(BlockPos, Direction)}, choosing among the working points with {@code mode} and keeping
     * {@code margin} (0..0.45, as a fraction of the face) away from its edges.
     */
    public static Aim solve(BlockPos pos, Direction face, AimPoint mode, double margin) {
        margin = Math.max(0.02, Math.min(0.45, margin));
        java.util.List<Aim> valid = mode == AimPoint.RANDOM ? new java.util.ArrayList<>() : null;
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
                        double u = margin + (1 - 2 * margin) * i / 4.0, v = margin + (1 - 2 * margin) * j / 4.0;
                        Vec3 pt = switch (d.getAxis()) {
                            case X -> new Vec3(plane, Mth.lerp(u, b.minY, b.maxY), Mth.lerp(v, b.minZ, b.maxZ));
                            case Y -> new Vec3(Mth.lerp(u, b.minX, b.maxX), plane, Mth.lerp(v, b.minZ, b.maxZ));
                            case Z -> new Vec3(Mth.lerp(u, b.minX, b.maxX), Mth.lerp(v, b.minY, b.maxY), plane);
                        };
                        Vec3 to = pt.subtract(eye);
                        float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
                        float pitch = (float) Math.toDegrees(-Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
                        double cost = mode == AimPoint.CENTER ? Math.hypot(u - 0.5, v - 0.5) * 100 + Math.hypot(Mth.wrapDegrees(yaw - curYaw), pitch - curPitch) * 0.01
                                : Math.hypot(Mth.wrapDegrees(yaw - curYaw), pitch - curPitch);
                        if (valid == null && cost >= bestCost) continue;
                        // pull the view onto the nearest whole-yaw step the camera can reach from here
                        float qYaw = curYaw + quantize(Mth.wrapDegrees(yaw - curYaw));
                        float qPitch = Mth.clamp(curPitch + quantize(pitch - curPitch), -90f, 90f);
                        if (!hits(qYaw, qPitch, pos, face == null ? null : d)) continue;
                        if (valid != null) {
                            valid.add(new Aim(qYaw, qPitch, pt));
                            continue;
                        }
                        best = new Aim(qYaw, qPitch, pt);
                        bestCost = cost;
                    }
                }
            }
        }
        if (valid != null) return valid.isEmpty() ? null : valid.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(valid.size()));
        return best;
    }

    /** True if this hit is on {@code entity}. */
    public static boolean hits(HitResult hit, net.minecraft.world.entity.Entity entity) {
        return hit instanceof EntityHitResult e && e.getEntity() == entity;
    }

    /**
     * A view that puts the crosshair on {@code entity}, checked with the game's raycast (so a block or another
     * entity in front doesn't take the hit, and it's within entity reach). Null if none does.
     */
    public static Aim solve(net.minecraft.world.entity.Entity entity, AimPoint mode) {
        LocalPlayer p = Minecraft.getInstance().player;
        AABB b = entity.getBoundingBox();
        Vec3 eye = p.getEyePosition(1f);
        float curYaw = p.getYRot(), curPitch = p.getXRot();
        java.util.List<Aim> valid = mode == AimPoint.RANDOM ? new java.util.ArrayList<>() : null;
        Aim best = null;
        double bestCost = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    double u = 0.2 + 0.3 * i, v = 0.2 + 0.3 * j, w = 0.2 + 0.3 * k;
                    Vec3 pt = new Vec3(Mth.lerp(u, b.minX, b.maxX), Mth.lerp(v, b.minY, b.maxY), Mth.lerp(w, b.minZ, b.maxZ));
                    Vec3 to = pt.subtract(eye);
                    float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
                    float pitch = (float) Math.toDegrees(-Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
                    double turn = Math.hypot(Mth.wrapDegrees(yaw - curYaw), pitch - curPitch);
                    double cost = mode == AimPoint.CENTER ? Math.abs(u - 0.5) + Math.abs(v - 0.5) + Math.abs(w - 0.5) + turn * 0.001 : turn;
                    if (valid == null && cost >= bestCost) continue;
                    float qYaw = curYaw + quantize(Mth.wrapDegrees(yaw - curYaw));
                    float qPitch = Mth.clamp(curPitch + quantize(pitch - curPitch), -90f, 90f);
                    if (!hits(predict(qYaw, qPitch), entity)) continue;
                    if (valid != null) {
                        valid.add(new Aim(qYaw, qPitch, pt));
                        continue;
                    }
                    best = new Aim(qYaw, qPitch, pt);
                    bestCost = cost;
                }
            }
        }
        if (valid != null) return valid.isEmpty() ? null : valid.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(valid.size()));
        return best;
    }

    /** Degrees the camera has to turn to look at {@code point}. */
    public static double turnTo(Vec3 point) {
        LocalPlayer p = Minecraft.getInstance().player;
        Vec3 to = point.subtract(p.getEyePosition(1f));
        float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
        float pitch = (float) Math.toDegrees(-Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
        return Math.hypot(Mth.wrapDegrees(yaw - p.getYRot()), pitch - p.getXRot());
    }

    /** Rounds a turn to whole mouse counts, which is the only kind of turn the camera can make. */
    private static float quantize(float degrees) {
        double step = MouseSim.degreesPerCount();
        return (float) (Math.rint(degrees / step) * step);
    }
}
