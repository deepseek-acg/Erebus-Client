package dev.erebus.client.module.combat;

import dev.erebus.client.input.MouseSim;
import dev.erebus.client.module.Category;
import dev.erebus.client.module.Module;
import dev.erebus.client.module.WorldRender;
import dev.erebus.client.render.Projection;
import dev.erebus.client.render.Visuals;
import dev.erebus.client.render.font.Icon;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.ModeSetting;
import dev.erebus.client.setting.NumberSetting;
import dev.erebus.client.setting.TextSetting;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Helps your aim along: while you aim near an opponent, the view is pulled towards them. It never takes the mouse
 * over: the pull is extra mouse movement added to your own each frame (through your sensitivity, in whole mouse
 * counts, like everything else Erebus does with the camera), and it lets go when you flick away.
 *
 * <p>How hard it pulls: a base speed, plus a variable part that grows with how far the target is from your
 * crosshair, never more than the cap, eased in over a moment when it picks someone, and softened once the crosshair
 * is on them. Two Gaussian processes keep it from looking mechanical: the speed wanders around its value (spread and
 * smoothness adjustable), and the point it aims at drifts around inside the hitbox.
 */
public final class AimAssist extends Module {
    private static AimAssist instance;

    // targets
    private final NumberSetting range;
    private final NumberSetting fov;
    private final ModeSetting priority;
    private final BoolSetting mobs;
    private final TextSetting friends;
    private final BoolSetting ignoreTeam;
    private final BoolSetting ignoreInvisible;
    private final BoolSetting ignoreBots;
    private final BoolSetting ignoreCreative;
    private final BoolSetting ignoreNaked;
    private final BoolSetting throughWalls;
    private final BoolSetting sticky;
    private final NumberSetting switchDelay;
    // aim
    private final ModeSetting aimAt;
    private final NumberSetting prediction;
    private final BoolSetting projectiles;
    private final NumberSetting projectileRange;
    // speed
    private final NumberSetting baseSpeed;
    private final NumberSetting variableSpeed;
    private final NumberSetting speedCap;
    private final NumberSetting rampUp;
    private final NumberSetting onTarget;
    private final NumberSetting horizontal;
    private final NumberSetting vertical;
    // gaussian
    private final NumberSetting speedSigma;
    private final NumberSetting speedSmooth;
    private final NumberSetting pointSigma;
    private final NumberSetting pointSmooth;
    // activation
    private final BoolSetting weaponsOnly;
    private final BoolSetting attackHeld;
    private final BoolSetting needMouse;
    private final NumberSetting mouseThreshold;
    private final BoolSetting flickRelease;
    private final NumberSetting flickSpeed;
    private final BoolSetting pauseEating;
    // render
    private final BoolSetting showFov;

    /** The current target, picked each tick. */
    private LivingEntity target;
    private long acquiredAt;
    private long switchedAt;
    /** After a flick away: this one isn't picked again until then. */
    private Entity released;
    private long releasedUntil;
    /** Why there's no target (shown in the module list). */
    private String why;

    // per frame
    private long lastFrame;
    private double carryX, carryY;
    private double userSpeed; // the player's own mouse speed, degrees per second, smoothed
    private double speedNoise; // Ornstein-Uhlenbeck around 0, stationary spread = 1
    private double pointNoiseX, pointNoiseY;
    /** What the last frame aimed at, for the visuals. */
    private volatile Vec3 lastAim;
    private volatile boolean lastOnTarget;
    private volatile int lastFlight;

    public AimAssist() {
        super("Aim Assist", "Pulls your view towards the opponent you're aiming near, on top of your own mouse movement, with adjustable "
                + "speed, a speed cap and Gaussian randomness. Never takes the mouse over: flick away and it lets go. Done with simulated "
                + "mouse movement only.", Category.COMBAT);
        group("Targets");
        range = add(new NumberSetting("Range", "Farthest an opponent can be, in blocks (melee).", 5.0, 1, 12, 0.1).unit(" blocks"));
        fov = add(new NumberSetting("FOV", "Only opponents within this cone around your crosshair are helped towards (full angle).",
                70, 5, 180, 1).unit("°"));
        priority = add(new ModeSetting("Priority", "Who to pick when several are in the cone: the one closest to your crosshair, the "
                + "nearest, or the one with the least health.", "Crosshair", "Crosshair", "Distance", "Health"));
        mobs = add(new BoolSetting("Target mobs", "Hostile mobs count too (players always do).", false));
        friends = add(new TextSetting("Friends", "Names never targeted, separated by commas.", "", 256));
        ignoreTeam = add(new BoolSetting("Ignore teammates", "Skip players on your scoreboard team, or with the same name color as you.",
                true));
        ignoreInvisible = add(new BoolSetting("Ignore invisible", "Skip players you can't see (invisibility).", false));
        ignoreBots = add(new BoolSetting("Ignore bots", "Skip players missing from the tab list: NPCs and the fake players some "
                + "anticheats spawn around you to catch aim helpers.", true));
        ignoreCreative = add(new BoolSetting("Ignore creative", "Skip players in creative mode (they can't be hurt).", true));
        ignoreNaked = add(new BoolSetting("Ignore naked", "Skip players with no armor.", false));
        throughWalls = add(new BoolSetting("Through walls", "Also pull towards opponents behind blocks.", false));
        sticky = add(new BoolSetting("Sticky target", "Keep the same opponent while they stay in range and a little beyond the cone, "
                + "instead of switching to whoever is closest to the crosshair.", true));
        switchDelay = add(new NumberSetting("Switch delay", "Shortest time between switching targets.", 250, 0, 2000, 25).unit(" ms"));
        group("Aim");
        aimAt = add(new ModeSetting("Aim at", "Which part of the hitbox: the point nearest your crosshair (the most natural), or a fixed "
                + "height.", "Nearest", "Nearest", "Head", "Chest", "Body", "Legs"));
        prediction = add(new NumberSetting("Prediction", "Lead a moving opponent by this many ticks of their movement (helps with ping).",
                1, 0, 6, 0.5).unit(" ticks"));
        projectiles = add(new BoolSetting("Projectile aim", "With a bow being drawn, a charged crossbow or a trident being thrown: aim "
                + "where the shot will land, allowing for drop and the flight time.", true));
        projectileRange = add(new NumberSetting("Projectile range", "Farthest an opponent can be for projectile aim.", 40, 5, 100, 1)
                .unit(" blocks").visibleWhen(projectiles::get));
        group("Speed");
        baseSpeed = add(new NumberSetting("Base speed", "How fast it pulls when the target is right next to your crosshair.", 30, 0, 300, 1)
                .unit("°/s"));
        variableSpeed = add(new NumberSetting("Variable speed", "How much faster it pulls for each degree the target is away from your "
                + "crosshair: far off pulls harder, close in eases off.", 3, 0, 30, 0.1).unit("°/s per °"));
        speedCap = add(new NumberSetting("Speed cap", "Never pulls faster than this.", 160, 5, 1000, 5).unit("°/s"));
        rampUp = add(new NumberSetting("Ramp up", "Eases the pull in over this long after picking a target, so it never snaps.", 200, 0,
                1500, 25).unit(" ms"));
        onTarget = add(new NumberSetting("On-target strength", "How much of the pull is left while your crosshair is already on them.",
                40, 0, 100, 5).unit("%"));
        horizontal = add(new NumberSetting("Horizontal", "Strength of the sideways pull.", 100, 0, 100, 5).unit("%"));
        vertical = add(new NumberSetting("Vertical", "Strength of the up-and-down pull.", 50, 0, 100, 5).unit("%"));
        group("Gaussian");
        speedSigma = add(new NumberSetting("Speed spread", "Standard deviation of the speed's Gaussian wander, as a share of the speed. "
                + "0 is perfectly steady.", 25, 0, 100, 1).unit("%"));
        speedSmooth = add(new NumberSetting("Speed smoothness", "How slowly the speed wanders: the time it takes to drift to a new value.",
                180, 20, 2000, 10).unit(" ms"));
        pointSigma = add(new NumberSetting("Point spread", "Standard deviation of where in the hitbox it aims, as a share of half the "
                + "hitbox. 0 always aims at the same spot.", 20, 0, 80, 1).unit("%"));
        pointSmooth = add(new NumberSetting("Point smoothness", "How slowly the aim point drifts around the hitbox.", 450, 50, 3000, 10)
                .unit(" ms"));
        group("Activation");
        weaponsOnly = add(new BoolSetting("Weapons only", "Only with a sword, axe, mace or trident in hand (or a bow or crossbow, with "
                + "projectile aim).", true));
        attackHeld = add(new BoolSetting("Only while attacking", "Only while the attack button is held.", false));
        needMouse = add(new BoolSetting("Only while aiming", "Only while you're moving the mouse yourself: it helps your aim rather "
                + "than aiming for you.", true));
        mouseThreshold = add(new NumberSetting("Mouse threshold", "How fast you have to be moving the mouse for it to help.", 4, 0, 60, 1)
                .unit("°/s").visibleWhen(needMouse::get));
        flickRelease = add(new BoolSetting("Flick to release", "Moving the mouse quickly away from the target lets go of them for a "
                + "moment.", true));
        flickSpeed = add(new NumberSetting("Flick speed", "How fast a flick away has to be.", 300, 60, 1500, 10).unit("°/s")
                .visibleWhen(flickRelease::get));
        pauseEating = add(new BoolSetting("Pause while eating", "No pull while eating, drinking or blocking.", true));
        group("Render");
        showFov = add(new BoolSetting("Show FOV", "Draw the cone as a circle around your crosshair.", false));
        addVisuals("Brackets around the target with the point it's aiming at, a guide line from your crosshair, and a label with their "
                + "name, health and distance; green when your crosshair is on them.");
        addTagSize();
        instance = this;
    }

    @Override
    protected void onEnable() {
        target = null;
        why = null;
        carryX = carryY = 0;
        lastFrame = System.nanoTime();
    }

    @Override
    protected void onDisable() {
        target = null;
        lastAim = null;
        info = null;
    }

    @Override
    public String suffix() {
        return target != null ? target.getName().getString() : why;
    }

    // =====================================================================================================
    // Picking a target (each tick)
    // =====================================================================================================

    @Override
    public void onTick() {
        if (mc.player == null || mc.level == null) {
            target = null;
            return;
        }
        long now = System.currentTimeMillis();
        double reach = projectile() > 0 ? projectileRange.get() : range.get();
        Set<String> friendNames = Arrays.stream(friends.get().split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        // keep the current one while it's still fair game (a little beyond the cone, so it doesn't drop at the edge)
        if (target != null && (!valid(target, reach, friendNames) || (sticky.get() ? angleTo(target) > fov.get() * 0.75 : false))) {
            target = null;
        }
        if (target != null && sticky.get()) {
            why = null;
            return;
        }
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        int inCone = 0;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || e == mc.player) continue;
            if (!(le instanceof Player) && !(mobs.get() && le instanceof Enemy)) continue;
            if (le == released && now < releasedUntil) continue;
            if (!valid(le, reach, friendNames)) continue;
            double angle = angleTo(le);
            if (angle > fov.get() / 2) continue;
            inCone++;
            double score = switch (priority.get()) {
                case "Distance" -> mc.player.distanceTo(le);
                case "Health" -> le.getHealth() + le.getAbsorptionAmount();
                default -> angle;
            };
            if (score < bestScore) {
                bestScore = score;
                best = le;
            }
        }
        if (best != target && (target == null || now - switchedAt >= switchDelay.get())) {
            target = best;
            switchedAt = now;
            acquiredAt = System.nanoTime();
        }
        why = target != null ? null : inCone == 0 ? "nobody in the cone" : null;
    }

    /** Whether this one may be helped towards at all (range, filters, line of sight). */
    private boolean valid(LivingEntity le, double reach, Set<String> friendNames) {
        if (!le.isAlive() || le.isDeadOrDying() || le.isRemoved() || le.isSpectator()) return false;
        if (mc.player.distanceTo(le) > reach) return false;
        if (le instanceof Player p) {
            if (friendNames.contains(p.getGameProfile().name().toLowerCase(Locale.ROOT))) return false;
            if (ignoreCreative.get() && p.isCreative()) return false;
            if (ignoreBots.get() && mc.getConnection() != null && mc.getConnection().getPlayerInfo(p.getUUID()) == null) return false;
            if (ignoreTeam.get() && (mc.player.isAlliedTo(p) || (p.getTeamColor() == mc.player.getTeamColor() && p.getTeamColor() != 0xFFFFFF))) {
                return false;
            }
        }
        if (ignoreInvisible.get() && le.isInvisibleTo(mc.player)) return false;
        if (ignoreNaked.get() && Targets.naked(le)) return false;
        return throughWalls.get() || visible(le);
    }

    /** Whether any of the head, middle or feet can be seen from your eyes. */
    private boolean visible(LivingEntity le) {
        Vec3 eye = mc.player.getEyePosition();
        AABB b = le.getBoundingBox();
        double cx = (b.minX + b.maxX) / 2, cz = (b.minZ + b.maxZ) / 2;
        for (double f : new double[]{0.9, 0.5, 0.1}) {
            Vec3 p = new Vec3(cx, b.minY + (b.maxY - b.minY) * f, cz);
            if (mc.level.clip(new ClipContext(eye, p, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)).getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }

    /** Degrees between where you look and the target's middle. */
    private double angleTo(LivingEntity le) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 to = le.getBoundingBox().getCenter().subtract(eye).normalize();
        Vec3 look = mc.player.getViewVector(1f);
        return Math.toDegrees(Math.acos(Mth.clamp(to.dot(look), -1, 1)));
    }

    // =====================================================================================================
    // The pull (each frame, from the mouse handler)
    // =====================================================================================================

    /** Mouse counts to add this frame on top of the player's own ({@code userDx, userDy}), or null. */
    public static double[] frameDelta(double userDx, double userDy) {
        AimAssist m = instance;
        if (m == null || !m.isEnabled()) return null;
        return m.delta(userDx, userDy);
    }

    private double[] delta(double userDx, double userDy) {
        long now = System.nanoTime();
        double dt = Math.min(0.05, (now - lastFrame) / 1e9);
        lastFrame = now;
        if (dt <= 0) return null;
        double perCount = MouseSim.degreesPerCount();
        // the player's own movement, in degrees (the game flips inverted axes after this point)
        double ux = userDx * perCount * (mc.options.invertMouseX().get() ? -1 : 1);
        double uy = userDy * perCount * (mc.options.invertMouseY().get() ? -1 : 1);
        double frameUser = Math.hypot(ux, uy) / dt;
        userSpeed = frameUser > userSpeed ? frameUser : userSpeed + (frameUser - userSpeed) * (1 - Math.exp(-dt / 0.12));
        // the Gaussian wanders (Ornstein-Uhlenbeck: smooth, spread 1, back towards 0 over its smoothness time)
        speedNoise = ou(speedNoise, dt, speedSmooth.get() / 1000.0);
        pointNoiseX = ou(pointNoiseX, dt, pointSmooth.get() / 1000.0);
        pointNoiseY = ou(pointNoiseY, dt, pointSmooth.get() / 1000.0);

        LivingEntity t = target;
        if (t == null || !allowed()) {
            lastAim = null;
            carryX = carryY = 0;
            return null;
        }
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 eye = mc.player.getEyePosition(pt);
        Vec3 aim = aimPoint(t, eye, pt);
        float[] rot = rotationTo(eye, aim, t);
        if (rot == null) {
            lastAim = null;
            return null;
        }
        double dYaw = Mth.wrapDegrees(rot[0] - mc.player.getYRot());
        double dPitch = rot[1] - mc.player.getXRot();
        double dist = Math.hypot(dYaw, dPitch);
        boolean onIt = onTarget(t, eye, pt);
        lastOnTarget = onIt;
        if (dist > fov.get() * 0.75 || dist < 1e-4) return null;
        // a quick flick away from them: let go for a moment
        if (flickRelease.get() && frameUser > flickSpeed.get() && (ux * dYaw + uy * dPitch) / (Math.hypot(ux, uy) * dist) < -0.5) {
            released = t;
            releasedUntil = System.currentTimeMillis() + 600;
            target = null;
            lastAim = null;
            return null;
        }
        if (needMouse.get() && userSpeed < mouseThreshold.get()) return null;

        double speed = Math.min(speedCap.get(), baseSpeed.get() + variableSpeed.get() * dist);
        speed *= Math.max(0, 1 + speedNoise * speedSigma.get() / 100.0);
        speed = Math.min(speed, speedCap.get());
        double ramp = rampUp.get() <= 0 ? 1 : Math.min(1, (now - acquiredAt) / (rampUp.get() * 1e6));
        speed *= ramp * ramp * (3 - 2 * ramp); // eased
        if (onIt) speed *= onTarget.get() / 100.0;
        double move = Math.min(speed * dt, dist);
        double mx = dYaw / dist * move * horizontal.get() / 100.0;
        double my = dPitch / dist * move * vertical.get() / 100.0;
        double sx = mx / perCount + carryX, sy = my / perCount + carryY;
        double cx = Math.rint(sx), cy = Math.rint(sy); // a mouse reports whole counts: keep the rest for the next frame
        carryX = sx - cx;
        carryY = sy - cy;
        if (mc.options.invertMouseX().get()) cx = -cx;
        if (mc.options.invertMouseY().get()) cy = -cy;
        return new double[]{cx, cy};
    }

    /** Whether helping is allowed right now (hands free, right item, no screen...). */
    private boolean allowed() {
        if (mc.player == null || mc.screen != null || !mc.mouseHandler.isMouseGrabbed()) return false;
        if (dev.erebus.client.module.render.Freecam.active() != null) return false;
        if (MouseSim.active()) return false; // another module is turning the camera
        if (attackHeld.get() && !mc.options.keyAttack.isDown()) return false;
        int proj = projectile();
        if (weaponsOnly.get() && proj == 0 && !weapon(mc.player.getMainHandItem())) return false;
        if (pauseEating.get() && mc.player.isUsingItem() && proj == 0) return false;
        return true;
    }

    private static boolean weapon(ItemStack s) {
        return s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES) || s.is(Items.MACE) || s.is(Items.TRIDENT);
    }

    /** One step of an Ornstein-Uhlenbeck process with spread 1 and time constant {@code tau} seconds. */
    private static double ou(double x, double dt, double tau) {
        double a = Math.exp(-dt / tau);
        return x * a + Math.sqrt(1 - a * a) * ThreadLocalRandom.current().nextGaussian();
    }

    /** Whether your crosshair is on the target's hitbox. */
    private boolean onTarget(LivingEntity t, Vec3 eye, float pt) {
        Vec3 look = mc.player.getViewVector(pt);
        AABB box = lerpedBox(t, pt).inflate(t.getPickRadius());
        return box.clip(eye, eye.add(look.scale(projectileRange.get() + 10))).isPresent();
    }

    private static AABB lerpedBox(Entity t, float pt) {
        Vec3 d = t.getPosition(pt).subtract(t.position());
        return t.getBoundingBox().move(d.x, d.y, d.z);
    }

    /** Where in the target's hitbox to aim, with the drift and the lead for its movement. */
    private Vec3 aimPoint(LivingEntity t, Vec3 eye, float pt) {
        AABB box = lerpedBox(t, pt);
        double w = (box.maxX - box.minX) / 2, h = box.maxY - box.minY;
        AABB inner = box.deflate(w * 0.15, h * 0.08, w * 0.15);
        Vec3 look = mc.player.getViewVector(pt);
        Vec3 p = switch (aimAt.get()) {
            case "Head" -> new Vec3(inner.getCenter().x, box.minY + h * 0.88, inner.getCenter().z);
            case "Chest" -> new Vec3(inner.getCenter().x, box.minY + h * 0.68, inner.getCenter().z);
            case "Body" -> new Vec3(inner.getCenter().x, box.minY + h * 0.5, inner.getCenter().z);
            case "Legs" -> new Vec3(inner.getCenter().x, box.minY + h * 0.25, inner.getCenter().z);
            default -> { // the point of the hitbox nearest the line you're looking along
                Vec3 c = inner.getCenter();
                double along = Math.max(0, c.subtract(eye).dot(look));
                Vec3 q = eye.add(look.scale(along));
                yield new Vec3(Mth.clamp(q.x, inner.minX, inner.maxX), Mth.clamp(q.y, inner.minY, inner.maxY), Mth.clamp(q.z, inner.minZ, inner.maxZ));
            }
        };
        // the Gaussian drift, across the view and up and down, kept inside the hitbox
        double s = pointSigma.get() / 100.0;
        if (s > 0) {
            Vec3 right = look.cross(new Vec3(0, 1, 0));
            right = right.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : right.normalize();
            p = p.add(right.scale(pointNoiseX * s * w)).add(0, pointNoiseY * s * h / 2, 0);
            p = new Vec3(Mth.clamp(p.x, inner.minX, inner.maxX), Mth.clamp(p.y, inner.minY, inner.maxY), Mth.clamp(p.z, inner.minZ, inner.maxZ));
        }
        // lead a moving target (their movement over the last tick)
        double ticks = prediction.get();
        if (ticks > 0) p = p.add(new Vec3(t.getX() - t.xo, (t.getY() - t.yo) * 0.5, t.getZ() - t.zo).scale(ticks));
        return p;
    }

    /**
     * Yaw and pitch from {@code eye} to {@code aim}; with a projectile ready, the pitch that lands it there (and the
     * aim moved on by the target's movement during the flight). Null if a projectile can't reach.
     */
    private float[] rotationTo(Vec3 eye, Vec3 aim, LivingEntity t) {
        double v0 = projectiles.get() ? projectileSpeed() : 0;
        lastFlight = 0;
        if (v0 > 0) {
            Vec3 vel = new Vec3(t.getX() - t.xo, 0, t.getZ() - t.zo);
            Vec3 at = aim;
            double[] sol = null;
            for (int i = 0; i < 3; i++) { // aim where they'll be when it arrives
                sol = ballistic(at.subtract(eye), v0);
                if (sol == null) return null;
                at = aim.add(vel.scale(sol[1]));
            }
            lastFlight = (int) Math.round(sol[1]);
            lastAim = at;
            Vec3 d = at.subtract(eye);
            float yaw = (float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90f;
            return new float[]{yaw, (float) -sol[0]};
        }
        lastAim = aim;
        Vec3 d = aim.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        return new float[]{yaw, pitch};
    }

    /** 1 for a drawn bow or loaded crossbow, 2 for a trident being thrown, 0 otherwise. */
    private int projectile() {
        if (!projectiles.get() || mc.player == null) return 0;
        ItemStack use = mc.player.getUseItem();
        if (mc.player.isUsingItem() && use.getItem() instanceof BowItem) return 1;
        if (mc.player.isUsingItem() && use.is(Items.TRIDENT)) return 2;
        for (ItemStack s : new ItemStack[]{mc.player.getMainHandItem(), mc.player.getOffhandItem()}) {
            if (s.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(s)) return 1;
        }
        return 0;
    }

    /** Launch speed of the projectile ready to fire, in blocks per tick, or 0. */
    private double projectileSpeed() {
        if (projectile() == 0) return 0;
        ItemStack use = mc.player.getUseItem();
        if (mc.player.isUsingItem() && use.getItem() instanceof BowItem) {
            double p = BowItem.getPowerForTime(mc.player.getTicksUsingItem()) * 3.0;
            return p < 0.3 ? 0 : p;
        }
        if (mc.player.isUsingItem() && use.is(Items.TRIDENT)) return 2.5;
        return 3.15; // crossbow
    }

    /**
     * The upward angle (degrees) that lands a shot at {@code d} (from the eye), and the ticks it takes, or null:
     * the flat (lower) arc, found by bisection over the game's arrow motion (drag 0.99, gravity 0.05 per tick).
     */
    private static double[] ballistic(Vec3 d, double v0) {
        double dist = Math.hypot(d.x, d.z), dy = d.y;
        double lo = -60, hi = 45;
        double[] hiRes = flight(hi, v0, dist);
        if (hiRes == null || hiRes[0] < dy) return null; // out of reach even at 45 degrees
        double[] res = null;
        for (int i = 0; i < 30; i++) {
            double mid = (lo + hi) / 2;
            double[] r = flight(mid, v0, dist);
            if (r == null || r[0] < dy) lo = mid;
            else {
                hi = mid;
                res = r;
            }
        }
        return res == null ? null : new double[]{hi, res[1]};
    }

    /** Height and time when a shot at {@code angle} degrees has travelled {@code dist} blocks sideways, or null. */
    private static double[] flight(double angle, double v0, double dist) {
        double a = Math.toRadians(angle);
        double vx = Math.cos(a) * v0, vy = Math.sin(a) * v0, x = 0, y = 0;
        for (int tick = 1; tick <= 200; tick++) {
            double nx = x + vx, ny = y + vy;
            if (nx >= dist) {
                double f = (dist - x) / (nx - x);
                return new double[]{y + (ny - y) * f, tick - 1 + f};
            }
            x = nx;
            y = ny;
            vx *= 0.99;
            vy = vy * 0.99 - 0.05;
            if (vx < 1e-3) return null;
        }
        return null;
    }

    // =====================================================================================================
    // Drawing
    // =====================================================================================================

    @Override
    public void onRenderWorld(WorldRender ctx) {
        LivingEntity t = target;
        Vec3 aim = lastAim;
        if (!visuals() || t == null || aim == null) return;
        int c = lastOnTarget ? 0xFF4ADE80 : 0xFFF472B6;
        AABB box = lerpedBox(t, ctx.partialTick());
        Visuals.brackets(box.inflate(0.08), c, 2f);
        Gizmos.point(aim, c, 9f).setAlwaysOnTop();
        Visuals.ring(new Vec3(box.getCenter().x, box.minY + 0.02, box.getCenter().z), (box.maxX - box.minX) * 0.85
                + 0.08 * Visuals.pulse(1.2), Visuals.fade(c, 0.8f), 1.6f, 28);
        if (lastFlight > 0) { // where the shot will meet them, from where they are now
            Gizmos.line(box.getCenter(), aim, Visuals.fade(c, 0.7f), 1.4f).setAlwaysOnTop();
        }
    }

    @Override
    public void onRenderOverlay(dev.erebus.client.gui.Ui ui) {
        if (mc.player == null || mc.options.hideGui || mc.screen != null) return;
        int cx = mc.getWindow().getGuiScaledWidth() / 2, cy = mc.getWindow().getGuiScaledHeight() / 2;
        if (showFov.get()) {
            float r = fovRadius(cx, cy);
            if (r > 0) dottedCircle(ui, cx, cy, r, target != null ? 0x99F472B6 : 0x66FFFFFF);
        }
        LivingEntity t = target;
        Vec3 aim = lastAim;
        if (!visuals() || t == null || aim == null) return;
        int c = lastOnTarget ? 0xFF4ADE80 : 0xFFF472B6;
        float[] p = Projection.toScreen(aim);
        if (p != null) {
            // a guide line from the crosshair to the aim point, fading towards the crosshair
            double len = Math.hypot(p[0] - cx, p[1] - cy);
            for (double d = 6; d < len - 3; d += 4) {
                double f = d / len;
                ui.rect((int) Math.round(cx + (p[0] - cx) * f), (int) Math.round(cy + (p[1] - cy) * f), 1, 1, Visuals.fade(c, (float) (0.25 + 0.6 * f)));
            }
            ui.rect(Math.round(p[0]) - 1, Math.round(p[1]) - 1, 3, 3, c);
        }
        AABB box = t.getBoundingBox();
        String detail = String.format(Locale.ROOT, "%.1f HP · %.1fm", t.getHealth() + t.getAbsorptionAmount(), mc.player.distanceTo(t))
                + (lastFlight > 0 ? " · " + lastFlight + "t flight" : "");
        Visuals.label(ui, new Vec3(box.getCenter().x, box.maxY + 0.45, box.getCenter().z), Icon.TARGET, t.getName().getString(), detail, c);
    }

    /** The FOV cone's radius on screen, worked out by projecting a point at its edge. */
    private float fovRadius(int cx, int cy) {
        var cam = mc.gameRenderer.getMainCamera();
        Vec3 dir = Vec3.directionFromRotation(cam.xRot(), cam.yRot() + (float) (fov.get() / 2));
        if (fov.get() >= 170) return -1;
        float[] p = Projection.toScreen(cam.position().add(dir.scale(16)));
        if (p == null) return -1;
        return (float) Math.hypot(p[0] - cx, p[1] - cy);
    }

    private static void dottedCircle(dev.erebus.client.gui.Ui ui, int cx, int cy, float r, int color) {
        int n = Math.max(24, (int) (r * 2 * Math.PI / 4));
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            ui.rect((int) Math.round(cx + Math.cos(a) * r), (int) Math.round(cy + Math.sin(a) * r), 1, 1, color);
        }
    }
}
