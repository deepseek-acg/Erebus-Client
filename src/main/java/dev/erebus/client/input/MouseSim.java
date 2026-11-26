package dev.erebus.client.input;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Synthetic mouse movement. A target view is turned into mouse counts and fed into Minecraft's own mouse handler
 * every frame (see MouseHandlerMixin), so the camera turns exactly as if the mouse moved: through the sensitivity
 * setting, in whole mouse steps, with inversion applied, and only while the mouse is captured by the game.
 *
 * <p>Nothing here writes the player's rotation directly or sends anything; the game does the rest as for real input.
 */
public final class MouseSim {
    private static boolean active;
    private static float targetYaw;
    private static float targetPitch;
    /** Maximum turn speed in degrees per second. */
    private static float maxSpeed = 720f;
    /** While true, the player's own mouse movement is ignored (the aim isn't fought mid-turn). */
    private static boolean holdCamera;
    /** A "look back" turn: ends by itself on arrival, and the player's own mouse movement cancels it. */
    private static boolean returning;
    private static long lastFrame;
    private static double carryX;
    private static double carryY;

    private MouseSim() {
    }

    /** Starts (or retargets) a turn towards this view. */
    public static void aim(float yaw, float pitch, float degreesPerSecond, boolean hold) {
        targetYaw = yaw;
        targetPitch = Mth.clamp(pitch, -90f, 90f);
        maxSpeed = Math.max(60f, degreesPerSecond);
        holdCamera = hold;
        returning = false;
        if (!active) {
            lastFrame = System.nanoTime();
            carryX = carryY = 0;
        }
        active = true;
    }

    /** Turns back to a view, then stops by itself. Moving the mouse cancels it. */
    public static void returnTo(float yaw, float pitch, float degreesPerSecond) {
        aim(yaw, pitch, degreesPerSecond, false);
        returning = true;
    }

    public static boolean returning() {
        return active && returning;
    }

    /** Stops turning and gives the mouse back to the player. */
    public static void stop() {
        active = false;
        holdCamera = false;
        returning = false;
        carryX = carryY = 0;
    }

    public static boolean active() {
        return active;
    }

    public static boolean holdingCamera() {
        return active && holdCamera;
    }

    /** Degrees the camera turns for one mouse count, given the current sensitivity (and spyglass). */
    public static double degreesPerCount() {
        Minecraft mc = Minecraft.getInstance();
        double s = mc.options.sensitivity().get() * 0.6 + 0.2;
        double f = s * s * s;
        boolean scoping = mc.player != null && mc.options.getCameraType().isFirstPerson() && mc.player.isScoping();
        return (scoping ? f : f * 8.0) * 0.15;
    }

    /** True when the camera is within half a mouse step of the target on both axes. */
    public static boolean onTarget() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return false;
        double half = degreesPerCount() / 2 + 1e-4;
        return Math.abs(Mth.wrapDegrees(targetYaw - p.getYRot())) <= half && Math.abs(targetPitch - p.getXRot()) <= half;
    }

    /**
     * Mouse counts to add this frame, as {x, y}. Called from the mouse handler right before it turns the player.
     * Eases in like a hand on a mouse: fast at first, slowing as it lands, never faster than the speed limit.
     */
    public static double[] frameDelta() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        if (!active) return null;
        if (p == null) {
            stop();
            return null;
        }
        double perCount = degreesPerCount();
        double dYaw = Mth.wrapDegrees(targetYaw - p.getYRot());
        double dPitch = targetPitch - p.getXRot();
        double dist = Math.hypot(dYaw, dPitch);
        if (dist <= perCount / 2) {
            if (returning) stop();
            return new double[]{0, 0};
        }
        // close a fixed fraction of the gap per unit time, capped by the speed limit
        double move = Math.min(dist, Math.max(dist * (1 - Math.exp(-14 * dt)), Math.min(dist, 25 * dt)));
        move = Math.min(move, maxSpeed * dt);
        double sx = dYaw / dist * move / perCount + carryX;
        double sy = dPitch / dist * move / perCount + carryY;
        // a mouse reports whole counts: keep the remainder for the next frame
        double cx = Math.abs(dYaw) <= perCount / 2 ? 0 : Math.rint(sx);
        double cy = Math.abs(dPitch) <= perCount / 2 ? 0 : Math.rint(sy);
        if (cx == 0 && Math.abs(dYaw) > perCount / 2) cx = Math.signum(dYaw);
        if (cy == 0 && Math.abs(dPitch) > perCount / 2) cy = Math.signum(dPitch);
        carryX = sx - cx;
        carryY = sy - cy;
        if (mc.options.invertMouseX().get()) cx = -cx;
        if (mc.options.invertMouseY().get()) cy = -cy;
        return new double[]{cx, cy};
    }
}
