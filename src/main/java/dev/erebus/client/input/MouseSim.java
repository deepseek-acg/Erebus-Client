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

    // the player's own mouse movement, measured before anything synthetic is added: when, and how far it would turn
    // the view (yaw, pitch degrees), for the last moments
    private static final int SAMPLES = 512;
    private static final long[] moveAt = new long[SAMPLES];
    private static final double[] moveYaw = new double[SAMPLES];
    private static final double[] movePitch = new double[SAMPLES];
    private static int moveHead;

    /** Called by the mouse handler with the player's own movement this frame (raw mouse counts). */
    public static void noteUserMovement(double dx, double dy) {
        if (dx == 0 && dy == 0) return;
        Minecraft mc = Minecraft.getInstance();
        double per = degreesPerCount();
        moveHead = (moveHead + 1) % SAMPLES;
        moveAt[moveHead] = System.nanoTime();
        moveYaw[moveHead] = dx * per * (mc.options.invertMouseX().get() ? -1 : 1);
        movePitch[moveHead] = dy * per * (mc.options.invertMouseY().get() ? -1 : 1);
    }

    /** The player's own net turn over the last {@code millis} milliseconds: {yaw, pitch} degrees. */
    public static double[] userNet(long millis) {
        long since = System.nanoTime() - millis * 1_000_000L;
        double y = 0, p = 0;
        for (int i = 0, k = moveHead; i < SAMPLES; i++, k = (k - 1 + SAMPLES) % SAMPLES) {
            if (moveAt[k] == 0 || moveAt[k] < since) break;
            y += moveYaw[k];
            p += movePitch[k];
        }
        return new double[]{y, p};
    }

    /**
     * True if the player has turned the view by more than {@code degrees} (net, so steady tracking back and forth
     * doesn't add up) within the last {@code millis} milliseconds, whether or not the camera was held at the time.
     */
    public static boolean userMoved(double degrees, long millis) {
        double[] n = userNet(millis);
        return Math.hypot(n[0], n[1]) > degrees;
    }

    /**
     * Like {@link #userMoved}, but movement towards where the current turn is going doesn't count: only a flick
     * away from it (or sideways past it) does.
     */
    public static boolean userMovedAway(double degrees, long millis) {
        double[] n = userNet(millis);
        double len = Math.hypot(n[0], n[1]);
        if (len <= degrees) return false;
        LocalPlayer p = Minecraft.getInstance().player;
        if (!active || p == null) return true;
        float goalYaw = overshooting ? finalYaw : targetYaw, goalPitch = overshooting ? finalPitch : targetPitch;
        double ty = Mth.wrapDegrees(goalYaw - p.getYRot()), tp = goalPitch - p.getXRot();
        double tl = Math.hypot(ty, tp);
        if (tl < 1e-3) return true; // already there: any big move is leaving it
        return (n[0] * ty + n[1] * tp) / (len * tl) < 0.5; // not mostly towards it
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
