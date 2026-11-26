package dev.erebus.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

/**
 * Projects world positions onto the screen, using the camera of the last rendered frame. Lets the client draw
 * world labels as crisp 2D text on the HUD instead of in-world text.
 */
public final class Projection {
    private static final Matrix4f VIEW_PROJ = new Matrix4f();
    private static Vec3 camera = Vec3.ZERO;
    private static boolean valid;

    private Projection() {
    }

    /** Called once per frame from world rendering. The view matrix works on camera-relative positions. */
    public static void update(Matrix4fc view, Matrix4fc projection, Vec3 cameraPos) {
        VIEW_PROJ.set(projection).mul(view);
        camera = cameraPos;
        valid = true;
    }

    /** Screen position in GUI units, or null when the point is behind the camera. */
    public static float[] toScreen(Vec3 world) {
        if (!valid) return null;
        Vector4f v = new Vector4f((float) (world.x - camera.x), (float) (world.y - camera.y), (float) (world.z - camera.z), 1f);
        VIEW_PROJ.transform(v);
        if (v.w <= 0.05f) return null;
        float nx = v.x / v.w, ny = v.y / v.w;
        var window = Minecraft.getInstance().getWindow();
        float sx = (nx * 0.5f + 0.5f) * window.getGuiScaledWidth();
        float sy = (1f - (ny * 0.5f + 0.5f)) * window.getGuiScaledHeight();
        return new float[]{sx, sy};
    }

    public static Vec3 camera() {
        return camera;
    }
}
