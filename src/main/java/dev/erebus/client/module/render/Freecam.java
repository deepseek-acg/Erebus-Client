package dev.erebus.client.module.render;

import dev.erebus.client.input.InputLock;
import dev.erebus.client.input.MouseSim;
import dev.erebus.client.module.Category;
import dev.erebus.client.module.Module;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.NumberSetting;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/**
 * Detaches the camera from your player so you can fly it around while your player stays where it is. Ported from
 * Nyx.
 *
 * <p>Purely client-side, with no packet changes: the camera is moved in the renderer, your mouse turns the camera
 * instead of your player, and with Freeze player on your movement keys stop reaching your player (as if you'd let
 * go of them). The server sees a player standing still with their real position and rotation. Nyx's silent
 * rotation and packet-freezing options aren't ported, and the player is no longer pinned in place against
 * knockback, which would put the client out of step with the server.
 */
public final class Freecam extends Module {
    private static Freecam instance;

    private final NumberSetting speed;
    private final BoolSetting freezePlayer;
    private final BoolSetting disableOnDamage;
    private final BoolSetting keepEntities;
    private final BoolSetting blockClicks;
    private final BoolSetting hideHand;
    private final BoolSetting spectate;
    private final NumberSetting verticalSpeed;
    private final BoolSetting unlimited;
    private final NumberSetting maxDistance;

    private Vec3 pos = Vec3.ZERO;
    private Vec3 prevPos = Vec3.ZERO;
    private Vec3 anchor = Vec3.ZERO;
    private float yaw;
    private float pitch;
    private float lastHealth;
    private boolean savedSmartCull = true;

    public Freecam() {
        super("Freecam", "Fly the camera away from your player and look around while your player stays put. Client-side only.", Category.RENDER);
        speed = add(new NumberSetting("Speed", "How fast the camera flies, in blocks per tick. Hold sprint to go 2.5x faster.", 1, 0.05, 10, 0.05));
        freezePlayer = add(new BoolSetting("Freeze player", "Your movement keys only move the camera, so your player stands still. "
                + "Off: your player keeps walking with the keys too.", true));
        disableOnDamage = add(new BoolSetting("Disable on damage", "Turn Freecam off when your player takes damage.", true));
        keepEntities = add(new BoolSetting("Keep entities visible", "Mobs, players and items near your player stay drawn when the camera is far "
                + "away from them.", true));
        blockClicks = add(new BoolSetting("Block clicks", "Ignore attack and use clicks while the camera is out, so you don't hit or use "
                + "whatever your real player happens to be looking at.", true));
        hideHand = add(new BoolSetting("Hide hand", "Don't draw your hand and held item while the camera is out.", true));
        group("Spectate");
        spectate = add(new BoolSetting("Spectate mode", "Keep the camera inside what your client has loaded: it can't leave loaded chunks or "
                + "your render distance, and it follows you if the server moves you.", false));
        verticalSpeed = add(new NumberSetting("Vertical speed", "Camera speed when flying up or down in spectate mode, in blocks per tick.",
                0.8, 0.05, 8, 0.05).visibleWhen(spectate::get));
        unlimited = add(new BoolSetting("No distance limit", "No maximum distance from your player in spectate mode (it still stops at the "
                + "edge of loaded chunks).", false).visibleWhen(spectate::get));
        maxDistance = add(new NumberSetting("Max distance", "How far the camera may go from your player in spectate mode, in blocks. Also "
                + "limited by your render distance.", 128, 16, 512, 8).visibleWhen(() -> spectate.get() && !unlimited.get()));
        instance = this;
        addVisuals("While the camera is out, marks your body: a halo over it, brackets around it and a line back to it.");
    }

    @Override
    public void onRenderWorld(dev.erebus.client.module.WorldRender ctx) {
        if (!visuals() || active() == null) return;
        // where your body is while the camera's away
        Vec3 lerp = mc.player.getPosition(ctx.partialTick()).subtract(mc.player.position());
        net.minecraft.world.phys.AABB b = mc.player.getBoundingBox().move(lerp.x, lerp.y, lerp.z);
        int c = 0xFF7DD3FC;
        dev.erebus.client.render.Visuals.brackets(b.inflate(0.1), c, 2f);
        dev.erebus.client.render.Visuals.ring(new Vec3(b.getCenter().x, b.maxY + 0.25, b.getCenter().z), 0.32, c, 2f, 20);
        dev.erebus.client.render.Visuals.ripple(new Vec3(b.getCenter().x, b.minY + 0.03, b.getCenter().z), 1.4, c, 1.5f, 2.0, 0);
        dev.erebus.client.render.Visuals.arc(ctx.tracerStart(), b.getCenter(), 0.6, dev.erebus.client.render.Visuals.fade(c, 0.7f), 1.5f);
    }

    @Override
    public void onRenderOverlay(dev.erebus.client.gui.Ui ui) {
        if (!visuals() || active() == null || mc.options.hideGui) return;
        Vec3 at = mc.player.position().add(0, mc.player.getBbHeight() + 0.9, 0);
        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        dev.erebus.client.render.Visuals.label(ui, at, dev.erebus.client.render.font.Icon.PLAYER, "You", Math.round(cam.distanceTo(mc.player.position())) + "m away",
                0xFF7DD3FC);
    }

    /** The running Freecam, or null when it's off. */
    public static Freecam active() {
        Freecam f = instance;
        return f != null && f.isEnabled() && mc.player != null ? f : null;
    }

    @Override
    protected void onEnable() {
        if (mc.player == null) { // e.g. restored from the config at startup: freecam never starts on its own
            setEnabled(false, false);
            return;
        }
        pos = mc.gameRenderer.getMainCamera().position();
        prevPos = pos;
        anchor = mc.player.position();
        yaw = mc.player.getYRot();
        pitch = mc.player.getXRot();
        lastHealth = mc.player.getHealth();
        // the game hides chunk sections it thinks the camera can't see; with the camera inside walls or far from
        // the player that guess goes wrong and blocks flicker, so draw them all meanwhile
        savedSmartCull = mc.smartCull;
        mc.smartCull = false;
        // the camera and the player's hands belong to the player now: automatic modules wait
        InputLock.take(this);
        if (MouseSim.active()) MouseSim.stop();
    }

    @Override
    protected void onDisable() {
        mc.smartCull = savedSmartCull;
        InputLock.release(this);
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.level == null || mc.player.isDeadOrDying()) {
            setEnabled(false);
            return;
        }
        float health = mc.player.getHealth();
        if (disableOnDamage.get() && health < lastHealth) {
            setEnabled(false);
            return;
        }
        lastHealth = health;
        InputLock.take(this);

        prevPos = pos;
        // a teleport or setback moves the player for real: in spectate mode the camera follows it
        Vec3 actual = mc.player.position();
        if (spectate.get() && actual.distanceToSqr(anchor) > 16) {
            Vec3 shift = actual.subtract(anchor);
            pos = pos.add(shift);
            prevPos = prevPos.add(shift);
        }
        anchor = actual;
        if (mc.screen != null) return;

        double forward = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        double strafe = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
        double vertical = (mc.options.keyJump.isDown() ? 1 : 0) - (mc.options.keyShift.isDown() ? 1 : 0);
        double s = speed.get() * (mc.options.keySprint.isDown() ? 2.5 : 1);
        double rad = Math.toRadians(yaw);
        double sin = Math.sin(rad), cos = Math.cos(rad);
        double dx = -sin * forward + cos * strafe;
        double dz = cos * forward + sin * strafe;
        double len = Math.hypot(dx, dz);
        if (len > 1) {
            dx /= len;
            dz /= len;
        }
        if (dx == 0 && dz == 0 && vertical == 0) return;
        if (spectate.get()) pos = clamp(pos.add(dx * s, vertical * verticalSpeed.get(), dz * s));
        else pos = pos.add(dx * s, vertical * s, dz * s);
    }

    /** Spectate mode: inside loaded chunks and within reach of the player. */
    private Vec3 clamp(Vec3 target) {
        double x = target.x, z = target.z;
        if (!loaded(x, pos.z)) x = pos.x;
        if (!loaded(x, z)) z = pos.z;
        Vec3 c = new Vec3(x, target.y, z);
        if (unlimited.get()) return c;
        double limit = Math.min(maxDistance.get(), renderDistanceBlocks());
        Vec3 off = c.subtract(anchor);
        double d = off.length();
        return d > limit ? anchor.add(off.scale(limit / d)) : c;
    }

    private boolean loaded(double x, double z) {
        return mc.level.getChunkSource().getChunk(Mth.floor(x) >> 4, Mth.floor(z) >> 4, ChunkStatus.FULL, false) != null;
    }

    private double renderDistanceBlocks() {
        return Math.max(16, mc.options.getEffectiveRenderDistance() * 16.0 - 16);
    }

    // ---- read by the mixins ----

    public Vec3 cameraPos(float partialTick) {
        return prevPos.lerp(pos, Mth.clamp(partialTick, 0, 1));
    }

    /** Mouse movement, scaled the way the game scales it for the player. */
    public void turn(double yRot, double xRot) {
        yaw += (float) yRot * 0.15F;
        pitch = Mth.clamp(pitch + (float) xRot * 0.15F, -90, 90);
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public boolean freezesPlayer() {
        return freezePlayer.get();
    }

    public boolean blocksClicks() {
        return blockClicks.get();
    }

    public boolean hidesHand() {
        return hideHand.get();
    }

    /** Whether this entity should stay drawn although the camera is far from it. */
    public boolean keepsVisible(Entity entity) {
        if (entity == mc.player) return true;
        if (!keepEntities.get()) return false;
        double limit = renderDistanceBlocks();
        return entity.distanceToSqr(mc.player) <= limit * limit;
    }

    @Override
    public String suffix() {
        if (mc.player == null) return null;
        return Math.round(Math.sqrt(pos.distanceToSqr(mc.player.getEyePosition()))) + "m";
    }
}
