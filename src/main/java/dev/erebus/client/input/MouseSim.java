package dev.erebus.client.input;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Synthetic mouse movement. A target view is turned into mouse counts and fed into Minecraft's own mouse handler
 * every frame (see MouseHandlerMixin), so the camera turns exactly as if the mouse moved: through the sensitivity
 * setting, in whole mouse steps, with inversion applied, and only while the mouse is captured by the game.
 *
 * <p>Nothing here writes the player's rotation directly or sends anything; the game does the rest as for real input.
 */
public final class MouseSim {
    /**
     * How a turn moves.
     *
     * @param maxSpeed        fastest turn, degrees per second
     * @param minSpeed        slowest turn near the end, degrees per second (keeps the last few degrees from crawling)
     * @param ease            how quickly the gap closes; higher is snappier, lower glides in
     * @param curve           how far the path bows sideways (0 = straight line), as a fraction of the turn
     * @param overshootChance chance that a turn first goes past the target and comes back
     * @param overshootDeg    how far past it goes, in degrees
     * @param overshootMin    turns smaller than this (degrees) never overshoot
     */
    public record Profile(float maxSpeed, float minSpeed, float ease, float curve, float overshootChance, float overshootDeg,
                          float overshootMin) {
        public static final Profile DEFAULT = new Profile(900, 25, 14, 0.15f, 0f, 1.5f, 10f);
    }

    private static boolean active;
    private static float targetYaw;
    private static float targetPitch;
    private static Profile profile = Profile.DEFAULT;
    /** While true, the player's own mouse movement is ignored (the aim isn't fought mid-turn). */
    private static boolean holdCamera;
    /** A "look back" turn: ends by itself on arrival, and the player's own mouse movement cancels it. */
    private static boolean returning;
    private static long lastFrame;
    private static double carryX;
    private static double carryY;

    // the current turn's shape
    private static double turnStart;      // angular distance when the turn began
    private static float bendSign;
    private static boolean overshooting;
    private static float finalYaw;
    private static float finalPitch;

    private MouseSim() {
    }

    /** Starts (or retargets) a turn towards this view. */
    public static void aim(float yaw, float pitch, Profile p, boolean hold) {
        pitch = Mth.clamp(pitch, -90f, 90f);
        holdCamera = hold;
        returning = false;
        profile = p;
        float goalYaw = overshooting ? finalYaw : targetYaw;
        float goalPitch = overshooting ? finalPitch : targetPitch;
        boolean newTurn = !active || Math.abs(Mth.wrapDegrees(yaw - goalYaw)) > 0.75f || Math.abs(pitch - goalPitch) > 0.75f;
        if (!active) {
            lastFrame = System.nanoTime();
            carryX = carryY = 0;
        }
        active = true;
        if (!newTurn) {
            if (!overshooting) {
                targetYaw = yaw;
                targetPitch = pitch;
            }
            return;
        }
        LocalPlayer pl = Minecraft.getInstance().player;
        double dist = pl == null ? 0 : Math.hypot(Mth.wrapDegrees(yaw - pl.getYRot()), pitch - pl.getXRot());
        turnStart = Math.max(dist, 1e-3);
        bendSign = ThreadLocalRandom.current().nextBoolean() ? 1f : -1f;
        finalYaw = yaw;
        finalPitch = pitch;
        overshooting = pl != null && dist >= p.overshootMin() && p.overshootChance() > 0
                && ThreadLocalRandom.current().nextFloat() < p.overshootChance();
        if (overshooting) {
            double dy = Mth.wrapDegrees(yaw - pl.getYRot()) / dist, dp = (pitch - pl.getXRot()) / dist;
            double extra = p.overshootDeg() * (0.6 + 0.8 * ThreadLocalRandom.current().nextDouble());
            targetYaw = (float) (yaw + dy * extra);
            targetPitch = Mth.clamp((float) (pitch + dp * extra), -90f, 90f);
        } else {
            targetYaw = yaw;
            targetPitch = pitch;
        }
    }

    public static void aim(float yaw, float pitch, float degreesPerSecond, boolean hold) {
        Profile d = Profile.DEFAULT;
        aim(yaw, pitch, new Profile(degreesPerSecond, d.minSpeed(), d.ease(), d.curve(), 0f, d.overshootDeg(), d.overshootMin()), hold);
    }

    /** Turns back to a view, then stops by itself. Moving the mouse cancels it. */
    public static void returnTo(float yaw, float pitch, Profile p) {
        returnTo(yaw, pitch, p, 0);
    }

    /** Like {@link #returnTo(float, float, Profile)}, starting after {@code delaySeconds}. */
    public static void returnTo(float yaw, float pitch, Profile p, double delaySeconds) {
        overshooting = false;
        aim(yaw, pitch, new Profile(p.maxSpeed(), p.minSpeed(), p.ease(), p.curve(), 0f, 0f, 1e9f), false);
        returning = true;
        returnAt = System.nanoTime() + (long) (delaySeconds * 1e9);
    }

    private static long returnAt;

    // the player's own mouse movement, measured before anything synthetic is added
    private static long userMoveAt;
    private static double userMoveCounts;

    /** Called by the mouse handler with the player's own movement this frame. */
    public static void noteUserMovement(double dx, double dy) {
        double c = Math.hypot(dx, dy);
        if (c == 0) return;
        long now = System.nanoTime();
        if (now - userMoveAt > 250_000_000L) userMoveCounts = 0; // a new gesture
        userMoveCounts += c;
        userMoveAt = now;
    }

    /**
     * True if the player has moved the mouse by more than {@code degrees} of camera turn within the last
     * {@code millis} milliseconds (whether or not the camera was held at the time).
     */
    public static boolean userMoved(double degrees, long millis) {
        return System.nanoTime() - userMoveAt < millis * 1_000_000L && userMoveCounts * degreesPerCount() > degrees;
    }

    public static boolean overshooting() {
        return active && overshooting;
    }

    public static boolean returning() {
        return active && returning;
    }

    /** Stops turning and gives the mouse back to the player. */
    public static void stop() {
        active = false;
        holdCamera = false;
        returning = false;
        overshooting = false;
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

    /**
     * Mouse counts to add this frame, as {x, y}. Called from the mouse handler right before it turns the player.
     * Eases in like a hand on a mouse: fast at first, slowing as it lands, never faster than the speed limit, along
     * a slightly bowed path when a curve is set.
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
        if (returning && now < returnAt) return new double[]{0, 0};
        double perCount = degreesPerCount();
        double dYaw = Mth.wrapDegrees(targetYaw - p.getYRot());
        double dPitch = targetPitch - p.getXRot();
        double dist = Math.hypot(dYaw, dPitch);
        if (dist <= perCount / 2) {
            if (overshooting) { // reached the overshoot point: now come back to the real target
                overshooting = false;
                targetYaw = finalYaw;
                targetPitch = finalPitch;
                turnStart = Math.max(Math.hypot(Mth.wrapDegrees(targetYaw - p.getYRot()), targetPitch - p.getXRot()), 1e-3);
                return new double[]{0, 0};
            }
            if (returning) stop();
            return new double[]{0, 0};
        }
        double move = Math.max(dist * (1 - Math.exp(-profile.ease() * dt)), profile.minSpeed() * dt);
        move = Math.min(Math.min(move, profile.maxSpeed() * dt), dist);
        // direction: straight at the target, bent sideways by an amount that fades as the turn finishes
        double ux = dYaw / dist, uy = dPitch / dist;
        double bend = profile.curve() * bendSign * Math.min(1.0, dist / turnStart);
        double vx = ux - uy * bend, vy = uy + ux * bend;
        double vl = Math.hypot(vx, vy);
        vx /= vl;
        vy /= vl;
        double sx = vx * move / perCount + carryX;
        double sy = vy * move / perCount + carryY;
        // a mouse reports whole counts: keep the remainder for the next frame
        double cx = Math.abs(dYaw) <= perCount / 2 ? 0 : Math.rint(sx);
        double cy = Math.abs(dPitch) <= perCount / 2 ? 0 : Math.rint(sy);
        if (cx == 0 && Math.abs(dYaw) > perCount / 2 && Math.abs(dYaw) < perCount * 3) cx = Math.signum(dYaw);
        if (cy == 0 && Math.abs(dPitch) > perCount / 2 && Math.abs(dPitch) < perCount * 3) cy = Math.signum(dPitch);
        carryX = sx - cx;
        carryY = sy - cy;
        if (mc.options.invertMouseX().get()) cx = -cx;
        if (mc.options.invertMouseY().get()) cy = -cy;
        return new double[]{cx, cy};
    }
}
