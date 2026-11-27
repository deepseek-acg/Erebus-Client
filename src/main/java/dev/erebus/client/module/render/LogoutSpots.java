package dev.erebus.client.module.render;

import dev.erebus.client.module.Category;
import dev.erebus.client.module.Module;
import dev.erebus.client.module.WorldRender;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.NumberSetting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Logout Spots: when a player you can see leaves the server, marks exactly where they were standing with a box and a
 * tag (name, health, how long ago). The marker goes away when they come back online. Ported from Nyx.
 */
public final class LogoutSpots extends Module {
    private record Snap(String name, AABB box, float health, ResourceKey<Level> dim, int tick) {
    }

    private record Spot(UUID id, String name, AABB box, float health, ResourceKey<Level> dim, long time) {
    }

    private final BoolSetting notify = add(new BoolSetting("Chat messages", "Say in chat when someone logs out near you (and when they come back).", true));
    private final BoolSetting tracers = add(new BoolSetting("Tracers", "Draw a line to each logout spot.", false));
    private final NumberSetting tagScale = add(new NumberSetting("Tag size", "How big the name tags are.", 1.0, 0.5, 2.5, 0.1));
    private final NumberSetting maxAge = add(new NumberSetting("Forget after", "Remove spots after this many minutes (0 = keep until they "
            + "log back in or you leave the server).", 0, 0, 120, 5).unit(" min"));

    private static final int COLOR = 0xFFFF9A2E;

    private final Map<UUID, Snap> seen = new HashMap<>();
    private final Map<UUID, Snap> gone = new HashMap<>(); // vanished from view: waiting to see if they left the server
    private final List<Spot> spots = new ArrayList<>();
    private Object lastConnection;
    private int tick;

    public LogoutSpots() {
        super("Logout Spots", "Marks where players were standing when they logged out.", Category.RENDER);
        addVisuals("A ghost of the player where they logged out, with a halo.");
    }

    @Override
    protected void onEnable() {
        seen.clear();
        gone.clear();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;
        tick++;
        // New server: old spots don't apply any more
        if (lastConnection != mc.getConnection()) {
            lastConnection = mc.getConnection();
            spots.clear();
            seen.clear();
            gone.clear();
        }
        ClientLevel level = mc.level;
        var dim = level.dimension();

        Map<UUID, Snap> now = new HashMap<>();
        for (Player p : level.players()) {
            if (p == mc.player) continue;
            now.put(p.getUUID(), new Snap(p.getName().getString(), p.getBoundingBox(), p.getHealth() + p.getAbsorptionAmount(), dim, tick));
        }
        // Players that just disappeared from view
        for (var e : seen.entrySet()) {
            if (!now.containsKey(e.getKey()) && e.getValue().dim == dim) gone.put(e.getKey(), e.getValue());
        }
        // Did they leave the server (gone from the player list)? Give the list update a couple of seconds to arrive.
        for (Iterator<Map.Entry<UUID, Snap>> it = gone.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (now.containsKey(e.getKey())) { it.remove(); continue; }
            if (mc.getConnection().getPlayerInfo(e.getKey()) == null) {
                Snap s = e.getValue();
                spots.removeIf(sp -> sp.id.equals(e.getKey()));
                spots.add(new Spot(e.getKey(), s.name, s.box, s.health, s.dim, System.currentTimeMillis()));
                if (notify.get()) {
                    Vec3 c = s.box.getBottomCenter();
                    chat(s.name + " logged out at " + (int) Math.floor(c.x) + " " + (int) Math.floor(c.y) + " " + (int) Math.floor(c.z)
                        + " (" + Math.round(mc.player.position().distanceTo(c)) + "m away).");
                }
                it.remove();
            } else if (tick - e.getValue().tick > 60) {
                it.remove(); // still online: they just walked or teleported out of range
            }
        }
        seen.clear();
        seen.putAll(now);
        info = spots.isEmpty() ? null : String.valueOf(spots.size());

        // Back online: remove their spot
        long nowMs = System.currentTimeMillis();
        for (Iterator<Spot> it = spots.iterator(); it.hasNext(); ) {
            Spot sp = it.next();
            if (mc.getConnection().getPlayerInfo(sp.id) != null) {
                if (notify.get()) chat(sp.name + " logged back in.");
                it.remove();
            } else if (maxAge.intValue() > 0 && nowMs - sp.time > maxAge.intValue() * 60_000L) {
                it.remove();
            }
        }
    }

    @Override
    public void onRenderWorld(WorldRender ctx) {
        if (mc.player == null || mc.level == null || spots.isEmpty()) return;
        Vec3 camPos = ctx.cameraPos();
        Vec3 start = ctx.tracerStart();
        var dim = mc.level.dimension();
        long nowMs = System.currentTimeMillis();
        for (Spot sp : spots) {
            if (sp.dim != dim) continue;
            Gizmos.cuboid(sp.box, GizmoStyle.strokeAndFill(COLOR, 2f, 0x40FF9A2E)).setAlwaysOnTop();
            if (visuals()) { // a ghost: head and body, fading in and out, with a halo
                AABB b = sp.box;
                Vec3 c = b.getCenter();
                float f = 0.35f + 0.45f * dev.erebus.client.render.Visuals.pulse(2.4);
                double hw = 0.25, bodyTop = b.maxY - 0.5;
                Gizmos.cuboid(new AABB(c.x - hw, bodyTop, c.z - hw, c.x + hw, b.maxY, c.z + hw),
                        GizmoStyle.strokeAndFill(dev.erebus.client.render.Visuals.fade(0xFFFFE8CC, f), 1.5f, dev.erebus.client.render.Visuals.alpha(0xFFFFE8CC, Math.round(0x60 * f)))).setAlwaysOnTop();
                Gizmos.cuboid(new AABB(c.x - 0.25, b.minY + 0.75, c.z - 0.13, c.x + 0.25, bodyTop, c.z + 0.13),
                        GizmoStyle.strokeAndFill(dev.erebus.client.render.Visuals.fade(0xFFFFE8CC, f), 1.5f, dev.erebus.client.render.Visuals.alpha(0xFFFFE8CC, Math.round(0x40 * f)))).setAlwaysOnTop();
                dev.erebus.client.render.Visuals.ring(new Vec3(c.x, b.maxY + 0.2, c.z), 0.3, COLOR, 2f, 20);
            }
            if (tracers.get()) Gizmos.line(start, sp.box.getCenter(), COLOR, 1.5f).setAlwaysOnTop();
        }
    }

    @Override
    public void onRenderOverlay(dev.erebus.client.gui.Ui ui) {
        if (mc.player == null || mc.level == null || spots.isEmpty() || mc.options.hideGui) return;
        var dim = mc.level.dimension();
        long nowMs = System.currentTimeMillis();
        for (Spot sp : spots) {
            if (sp.dim != dim) continue;
            Vec3 top = new Vec3(sp.box.getCenter().x, sp.box.maxY + 0.4, sp.box.getCenter().z);
            float[] p = dev.erebus.client.render.Projection.toScreen(top);
            if (p == null) continue;
            String detail = Math.round(sp.health) + " hp \u00b7 " + ago(nowMs - sp.time) + " \u00b7 "
                    + Math.round(mc.player.position().distanceTo(sp.box.getBottomCenter())) + "m";
            float scale = tagScale.floatValue();
            ui.g.pose().pushMatrix();
            ui.g.pose().translate(p[0], p[1]);
            ui.g.pose().scale(scale, scale);
            ui.worldLabel(dev.erebus.client.render.font.Icon.PLAYER, sp.name, detail, COLOR, 0, -16);
            ui.g.pose().popMatrix();
        }
    }

    private static String ago(long ms) {
        long s = ms / 1000;
        if (s < 60) return "logged out " + s + "s ago";
        long m = s / 60;
        if (m < 60) return "logged out " + m + "m ago";
        return "logged out " + (m / 60) + "h " + (m % 60) + "m ago";
    }
}
