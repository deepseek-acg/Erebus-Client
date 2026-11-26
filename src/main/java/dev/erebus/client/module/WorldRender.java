package dev.erebus.client.module;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;

/** Per-frame world render info handed to modules. Draw with {@link net.minecraft.gizmos.Gizmos}. */
public record WorldRender(Camera camera, float partialTick) {
    public Vec3 cameraPos() {
        return camera.position();
    }

    /** A point just in front of the camera, the natural start of tracer lines. */
    public Vec3 tracerStart() {
        return camera.position().add(Vec3.directionFromRotation(camera.xRot(), camera.yRot()).scale(0.6));
    }
}
