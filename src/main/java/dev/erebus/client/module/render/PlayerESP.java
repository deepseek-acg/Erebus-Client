package dev.erebus.client.module.render;

import dev.erebus.client.module.Category;
import dev.erebus.client.module.Module;
import dev.erebus.client.module.WorldRender;
import dev.erebus.client.render.Projection;
import dev.erebus.client.render.Visuals;
import dev.erebus.client.render.font.Icon;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.ColorSetting;
import dev.erebus.client.setting.ModeSetting;
import dev.erebus.client.setting.NumberSetting;
import dev.erebus.client.setting.TextSetting;
import dev.erebus.client.util.Pops;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shows every other player through walls: a box (or corner brackets), a tracer, and a crisp name tag with what
 * matters in a fight (health, distance, ping, what they're holding, armor, totems popped this life). Colored by
 * distance, health or team; friends in their own color. Ported from Nyx: tags are drawn as HUD text rather than
 * world text, so they stay sharp and readable at any distance, and pops, ping, held item and armor were added.
 */
public final class PlayerESP extends Module {
    private final ModeSetting box;
    private final NumberSetting fill;
    private final BoolSetting tracers;
    private final NumberSetting lineWidth;
    private final ModeSetting colorMode;
    private final ColorSetting color;
    private final NumberSetting range;
    private final BoolSetting showInvisible;
    private final BoolSetting ignoreBots;
    private final TextSetting friends;
    private final ColorSetting friendColor;
    // tags
    private final BoolSetting tags;
    private final BoolSetting tagHealth;
    private final BoolSetting tagDistance;
    private final BoolSetting tagPing;
    private final BoolSetting tagItem;
    private final BoolSetting tagArmor;
    private final BoolSetting tagPops;
    private final BoolSetting tagCoords;
    private final NumberSetting tagRange;

    private static final int FIXED = 0xFFB46CFF;

    public PlayerESP() {
        super("Player ESP", "Boxes, tracers and name tags on other players through walls, with health, distance, ping, held item, "
                + "armor and totem pops. Ported from Nyx.", Category.RENDER);
        group("Players");
        box = add(new ModeSetting("Box", "Full box, just the corners, or none.", "Box", "Box", "Corners", "None"));
        fill = add(new NumberSetting("Fill", "How opaque the box's fill is (0 = outline only).", 40, 0, 160, 5).visibleWhen(() -> box.is("Box")));
        tracers = add(new BoolSetting("Tracers", "A line from your crosshair to each player.", true));
        lineWidth = add(new NumberSetting("Line width", "Thickness of boxes and tracers.", 2, 0.5, 5, 0.5));
        colorMode = add(new ModeSetting("Color by", "Distance: red close, through orange and yellow, to green far. Health: red low to green "
                + "full. Team: their name color. Fixed: one color.", "Distance", "Distance", "Health", "Team", "Fixed"));
        color = add(new ColorSetting("Color", FIXED).visibleWhen(() -> colorMode.is("Fixed")));
        range = add(new NumberSetting("Range", "Farthest player shown (blocks).", 512, 16, 2048, 16).unit(" blocks"));
        showInvisible = add(new BoolSetting("Show invisible", "Also show players with Invisibility (marked on their tag).", true));
        ignoreBots = add(new BoolSetting("Ignore bots", "Skip players missing from the tab list (NPCs and fake players).", false));
        friends = add(new TextSetting("Friends", "Names shown in the friend color, separated by commas.", "", 256));
        friendColor = add(new ColorSetting("Friend color", 0xFF4ADE80));
        group("Name tags");
        tags = add(new BoolSetting("Name tags", "A tag above each player, readable through walls at any distance.", true));
        tagHealth = add(new BoolSetting("Health", "Health including absorption.", true).visibleWhen(tags::get));
        tagDistance = add(new BoolSetting("Distance", true).visibleWhen(tags::get));
        tagPing = add(new BoolSetting("Ping", "Their ping from the tab list.", false).visibleWhen(tags::get));
        tagItem = add(new BoolSetting("Held item", "What's in their main hand (and a totem in their offhand).", true).visibleWhen(tags::get));
        tagArmor = add(new BoolSetting("Armor", "How many armor pieces they wear, and if any is about to break.", true).visibleWhen(tags::get));
        tagPops = add(new BoolSetting("Totem pops", "Totems they've popped this life.", true).visibleWhen(tags::get));
        tagCoords = add(new BoolSetting("Coordinates", false).visibleWhen(tags::get));
        tagRange = add(new NumberSetting("Tag range", "Only tag players this close (blocks); the boxes go farther.", 256, 8, 2048, 8)
                .unit(" blocks").visibleWhen(tags::get));
        addVisuals("A ring under each player that shows their health, and a burst when someone pops a totem.");
        addTagSize();
    }

    @Override
    public String suffix() {
        if (mc.level == null) return null;
        int n = players().size();
        return n > 0 ? String.valueOf(n) : null;
    }

    private Set<String> friendNames() {
        return Arrays.stream(friends.get().split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /** Other players worth showing, nearest first. */
    private List<Player> players() {
        List<Player> out = new ArrayList<>();
        if (mc.level == null || mc.player == null) return out;
        double r2 = range.get() * range.get();
        for (Player p : mc.level.players()) {
            if (p == mc.player || !p.isAlive() || p.isSpectator()) continue;
            if (p.isInvisible() && !showInvisible.get()) continue;
            if (ignoreBots.get() && mc.getConnection() != null && mc.getConnection().getPlayerInfo(p.getUUID()) == null) continue;
            if (p.distanceToSqr(mc.player) > r2) continue;
            out.add(p);
        }
        out.sort((a, b) -> Double.compare(a.distanceToSqr(mc.player), b.distanceToSqr(mc.player)));
        return out;
    }

    private int colorFor(Player p, Set<String> friendNames) {
        if (friendNames.contains(p.getGameProfile().name().toLowerCase(Locale.ROOT))) return friendColor.get();
        return switch (colorMode.get()) {
            case "Health" -> {
                float f = Math.max(0, Math.min(1, (p.getHealth() + p.getAbsorptionAmount()) / p.getMaxHealth()));
                yield f < 0.33f ? 0xFFFF4040 : f < 0.66f ? 0xFFFFC23D : 0xFF4ADE80;
            }
            case "Team" -> {
                int c = p.getTeamColor();
                yield c == 0xFFFFFF ? FIXED : 0xFF000000 | c;
            }
            case "Fixed" -> color.get();
            default -> {
                double d = mc.player.distanceTo(p);
                yield d < 8 ? 0xFFFF4040 : d < 20 ? 0xFFFF9A2E : d < 48 ? 0xFFFFE04A : 0xFF4ADE80;
            }
        };
    }

    @Override
    public void onRenderWorld(WorldRender ctx) {
        if (mc.player == null) return;
        Set<String> fn = friendNames();
        float w = lineWidth.floatValue();
        Vec3 start = ctx.tracerStart();
        for (Player p : players()) {
            Vec3 lerp = p.getPosition(ctx.partialTick()).subtract(p.position());
            AABB b = p.getBoundingBox().move(lerp.x, lerp.y, lerp.z);
            int c = colorFor(p, fn);
            if (box.is("Box")) {
                int a = fill.intValue();
                Gizmos.cuboid(b, a > 0 ? GizmoStyle.strokeAndFill(c, w, (a << 24) | (c & 0xFFFFFF)) : GizmoStyle.stroke(c, w)).setAlwaysOnTop();
            } else if (box.is("Corners")) {
                Visuals.brackets(b.inflate(0.05), c, w);
            }
            if (tracers.get()) {
                Gizmos.line(start, new Vec3(b.getCenter().x, b.minY + b.getYsize() * 0.6, b.getCenter().z), c, w).setAlwaysOnTop();
            }
            if (visuals()) {
                Vec3 feet = new Vec3(b.getCenter().x, b.minY + 0.05, b.getCenter().z);
                float hp = Math.max(0, Math.min(1, (p.getHealth() + p.getAbsorptionAmount()) / p.getMaxHealth()));
                // the ring's lit part is their health
                int segs = 32, lit = Math.round(segs * hp);
                for (int i = 0; i < segs; i++) {
                    double a0 = Math.PI * 2 * i / segs - Math.PI / 2, a1 = Math.PI * 2 * (i + 1) / segs - Math.PI / 2;
                    Vec3 p0 = feet.add(Math.cos(a0) * 0.6, 0, Math.sin(a0) * 0.6), p1 = feet.add(Math.cos(a1) * 0.6, 0, Math.sin(a1) * 0.6);
                    Gizmos.line(p0, p1, i < lit ? c : Visuals.alpha(c, 0x40), 2f).setAlwaysOnTop();
                }
                long since = Pops.sinceLast(p);
                if (since >= 0 && since < 900) { // a totem just popped: a gold burst
                    float t = since / 900f;
                    Visuals.ring(b.getCenter(), 0.4 + 2.2 * t, Visuals.fade(0xFFFFD54A, 1 - t), 2.5f, 32);
                    Visuals.ring(b.getCenter().add(0, 0.5, 0), 0.3 + 1.6 * t, Visuals.fade(0xFFFFD54A, 1 - t), 2f, 32);
                }
            }
        }
    }

    @Override
    public void onRenderOverlay(dev.erebus.client.gui.Ui ui) {
        if (!tags.get() || mc.player == null || mc.options.hideGui) return;
        Set<String> fn = friendNames();
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        List<Player> ps = players();
        double tr2 = tagRange.get() * tagRange.get();
        // farthest first, so nearer tags are drawn on top
        for (int i = ps.size() - 1; i >= 0; i--) {
            Player p = ps.get(i);
            if (p.distanceToSqr(mc.player) > tr2) continue;
            Vec3 at = p.getPosition(pt).add(0, p.getBbHeight() + 0.35, 0);
            float[] s = Projection.toScreen(at);
            if (s == null) continue;
            int c = colorFor(p, fn);
            String name = p.getName().getString() + (p.isInvisible() ? " (invisible)" : "");
            List<String> parts = new ArrayList<>();
            if (tagHealth.get()) parts.add(String.format(Locale.ROOT, "%.1f HP", p.getHealth() + p.getAbsorptionAmount()));
            if (tagDistance.get()) parts.add(Math.round(mc.player.distanceTo(p)) + "m");
            if (tagPing.get() && mc.getConnection() != null) {
                PlayerInfo info = mc.getConnection().getPlayerInfo(p.getUUID());
                if (info != null) parts.add(info.getLatency() + "ms");
            }
            if (tagPops.get()) {
                int pops = Pops.count(p);
                if (pops > 0) parts.add(pops + " pop" + (pops == 1 ? "" : "s"));
            }
            if (tagArmor.get()) parts.add(armor(p));
            if (tagItem.get()) {
                String held = itemName(p.getMainHandItem());
                if (p.getOffhandItem().is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)) held = (held == null ? "" : held + " + ") + "totem";
                if (held != null) parts.add(held);
            }
            if (tagCoords.get()) parts.add((int) Math.floor(p.getX()) + " " + (int) Math.floor(p.getY()) + " " + (int) Math.floor(p.getZ()));
            parts.removeIf(x -> x == null || x.isEmpty());
            ui.worldLabel(Icon.PLAYER, name, parts.isEmpty() ? null : String.join(" · ", parts), c, s[0], s[1] - 16);
        }
    }

    /** "4/4 armor", with "low" when a piece is nearly broken; null for none (which says a lot too, so it's shown). */
    private static String armor(Player p) {
        int pieces = 0;
        boolean low = false;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack s = p.getItemBySlot(slot);
            if (s.isEmpty()) continue;
            pieces++;
            if (s.isDamageableItem() && s.getMaxDamage() > 0 && (s.getMaxDamage() - s.getDamageValue()) < s.getMaxDamage() * 0.15) low = true;
        }
        return pieces == 0 ? "no armor" : pieces + "/4 armor" + (low ? " (low)" : "");
    }

    private static String itemName(ItemStack s) {
        if (s.isEmpty()) return null;
        String n = s.getHoverName().getString();
        return n.length() > 22 ? n.substring(0, 21) + "…" : n;
    }
}
