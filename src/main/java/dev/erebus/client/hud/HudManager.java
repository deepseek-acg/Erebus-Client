package dev.erebus.client.hud;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.util.Anim;
import dev.erebus.client.gui.hud.HudEditorScreen;
import dev.erebus.client.hud.elements.ArmorElement;
import dev.erebus.client.hud.elements.CoordinatesElement;
import dev.erebus.client.hud.elements.InfoElement;
import dev.erebus.client.hud.elements.ModuleListElement;
import dev.erebus.client.hud.elements.NearbyPlayersElement;
import dev.erebus.client.hud.elements.NotificationsElement;
import dev.erebus.client.hud.elements.SuppliesElement;
import dev.erebus.client.hud.elements.WatermarkElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HudManager {
    /** Layout presets: which elements are visible for each kind of play. */
    public static final Map<String, Set<String>> PRESETS = new LinkedHashMap<>();

    static {
        PRESETS.put("PvP", Set.of("watermark", "modules", "nearby", "supplies", "armor", "info", "notifications", "target", "effects", "hunger"));
        PRESETS.put("Travel", Set.of("watermark", "modules", "coordinates", "info", "notifications", "minimap", "compass", "rotation", "speedgraph"));
        PRESETS.put("Base", Set.of("watermark", "modules", "coordinates", "nearby", "notifications", "minimap", "legend", "compass", "worldinfo"));
        PRESETS.put("All", Set.of("watermark", "modules", "coordinates", "info", "nearby", "supplies", "armor", "notifications", "minimap", "legend",
                "compass", "rotation", "hunger", "effects", "target", "speedgraph", "worldinfo"));
    }

    private final List<HudElement> elements = new ArrayList<>();
    private final Ui ui = new Ui();
    private String preset = "All";
    private double speedKmh;
    private double lastX;
    private double lastZ;
    private boolean hasLast;

    public void registerDefaults() {
        register(new WatermarkElement());
        register(new ModuleListElement());
        register(new CoordinatesElement());
        register(new InfoElement());
        register(new NearbyPlayersElement());
        register(new SuppliesElement());
        register(new ArmorElement());
        register(new dev.erebus.client.hud.elements.MinimapElement());
        register(new dev.erebus.client.hud.elements.FinderLegendElement());
        register(new dev.erebus.client.hud.elements.CompassElement());
        register(new dev.erebus.client.hud.elements.RotationElement());
        register(new dev.erebus.client.hud.elements.HungerElement());
        register(new dev.erebus.client.hud.elements.EffectsElement());
        register(new dev.erebus.client.hud.elements.TargetElement());
        register(new dev.erebus.client.hud.elements.SpeedGraphElement());
        register(new dev.erebus.client.hud.elements.WorldInfoElement());
        register(new NotificationsElement()); // last, so toasts are drawn over the other elements
    }

    /** Where each element is drawn, easing to where there's room for it. */
    private final java.util.Map<HudElement, Anim> shownX = new java.util.HashMap<>();
    private final java.util.Map<HudElement, Anim> shownY = new java.util.HashMap<>();

    /**
     * The y where the element doesn't cover anything already taken: its own place if that's free, else slid away from its
     * screen edge (down from the top, up from the bottom) past whatever is in the way, or the other way if that runs off.
     */
    private static int[] clearOf(HudElement e, List<int[]> taken, int sw, int sh) {
        boolean up = e.vAnchor == HudElement.VAnchor.BOTTOM;
        boolean left = e.hAnchor == HudElement.HAnchor.RIGHT;
        // slide away from its edge first, then towards it, then sideways (away from its side, then towards)
        int[][] dirs = {{0, up ? -1 : 1}, {0, up ? 1 : -1}, {left ? -1 : 1, 0}, {left ? 1 : -1, 0}};
        int[] best = {e.x, e.y};
        long bestArea = overlap(e.x, e.y, e, taken);
        if (bestArea == 0) return best;
        for (int[] d : dirs) {
            int x = e.x, y = e.y;
            for (int i = 0; i < 24; i++) {
                int[] hit = firstHit(x, y, e, taken);
                if (hit == null) return new int[]{x, y};
                if (d[1] < 0) y = hit[1] - e.height;
                else if (d[1] > 0) y = hit[3];
                else if (d[0] < 0) x = hit[0] - e.width;
                else x = hit[2];
                if (x < 0 || y < 0 || x + e.width > sw || y + e.height > sh) break;
                long a = overlap(x, y, e, taken);
                if (a < bestArea) {
                    bestArea = a;
                    best = new int[]{x, y};
                }
            }
        }
        return best; // nowhere entirely free (a crowded screen): where it covers the least
    }

    private static int[] firstHit(int x, int y, HudElement e, List<int[]> taken) {
        for (int[] r : taken) if (x < r[2] && x + e.width > r[0] && y < r[3] && y + e.height > r[1]) return r;
        return null;
    }

    private static long overlap(int x, int y, HudElement e, List<int[]> taken) {
        long a = 0;
        for (int[] r : taken) {
            int w = Math.min(x + e.width, r[2]) - Math.max(x, r[0]), h = Math.min(y + e.height, r[3]) - Math.max(y, r[1]);
            if (w > 0 && h > 0) a += (long) w * h;
        }
        return a;
    }

    /** What the game itself draws that elements shouldn't cover: hotbar and bars, potion icons, the chat box. */
    private static List<int[]> vanillaAreas(Minecraft mc, int sw, int sh) {
        List<int[]> out = new java.util.ArrayList<>();
        var p = mc.player;
        if (p == null) return out;
        int top = sh - 23; // hotbar
        if (mc.gameMode != null && mc.gameMode.canHurtPlayer()) {
            float hp = p.getMaxHealth() + p.getAbsorptionAmount();
            int rows = (int) Math.ceil(hp / 20f);
            int rowH = Math.max(10 - (rows - 2), 3);
            top = sh - 39 - (rows - 1) * rowH - 1;
            if (p.getArmorValue() > 0) top -= 10;
            if (p.getAirSupply() < p.getMaxAirSupply() || p.isUnderWater()) top = Math.min(top, sh - 49 - 1);
        } else if (mc.gameMode != null && mc.gameMode.hasExperience()) {
            top = sh - 30;
        }
        out.add(new int[]{sw / 2 - 92, top, sw / 2 + 92, sh});
        int good = 0, bad = 0;
        for (var fx : p.getActiveEffects()) {
            if (!fx.showIcon()) continue;
            if (fx.getEffect().value().isBeneficial()) good++;
            else bad++;
        }
        if (good + bad > 0) out.add(new int[]{sw - 25 * Math.max(good, bad) - 2, 0, sw, (bad > 0 ? 51 : 25) + 2});
        if (mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen) out.add(new int[]{0, sh - 16, sw, sh});
        return out;
    }

    public void register(HudElement e) {
        elements.add(e);
    }

    public List<HudElement> all() {
        return Collections.unmodifiableList(elements);
    }

    public HudElement get(String id) {
        for (HudElement e : elements) {
            if (e.id.equals(id)) return e;
        }
        return null;
    }

    /** Name of the last applied preset, or null when visibility was changed by hand. */
    public String preset() {
        return preset;
    }

    public void setPreset(String preset) {
        this.preset = preset;
    }

    public void applyPreset(String name) {
        Set<String> ids = PRESETS.get(name);
        if (ids == null) return;
        for (HudElement e : elements) e.setEnabled(ids.contains(e.id));
        preset = name;
    }

    public void setEnabled(HudElement e, boolean on) {
        e.setEnabled(on);
        preset = null;
        for (Map.Entry<String, Set<String>> p : PRESETS.entrySet()) {
            boolean match = true;
            for (HudElement el : elements) {
                if (el.isEnabled() != p.getValue().contains(el.id)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                preset = p.getKey();
                break;
            }
        }
    }

    public void resetLayout() {
        for (HudElement e : elements) e.resetPosition();
    }

    public double speedKmh() {
        return speedKmh;
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) {
            hasLast = false;
            speedKmh = 0;
            return;
        }
        if (hasLast) {
            double dx = p.getX() - lastX;
            double dz = p.getZ() - lastZ;
            double blocksPerTick = Math.sqrt(dx * dx + dz * dz);
            double instant = blocksPerTick * 20 * 3.6;
            speedKmh = speedKmh * 0.6 + instant * 0.4;
        }
        lastX = p.getX();
        lastZ = p.getZ();
        hasLast = true;
    }

    /** HUD layer callback, registered with Fabric's HUD element registry. */
    public void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen instanceof HudEditorScreen || mc.getDebugOverlay().showDebugScreen()) return;
        ui.begin(g);
        dev.erebus.client.Erebus.modules().renderOverlay(ui);
        int sw = g.guiWidth();
        int sh = g.guiHeight();
        boolean apart = dev.erebus.client.Erebus.client().hudApart.get();
        List<int[]> taken = apart ? vanillaAreas(mc, sw, sh) : new java.util.ArrayList<>();
        for (HudElement e : elements) {
            if (!e.isEnabled()) continue;
            e.layout(ui, sw, sh, false);
            if (apart && e.width > 0 && !(e instanceof NotificationsElement)) {
                int[] want = clearOf(e, taken, sw, sh);
                Anim ax = shownX.computeIfAbsent(e, k -> new Anim(want[0], 18f));
                Anim ay = shownY.computeIfAbsent(e, k -> new Anim(want[1], 18f));
                ax.target(want[0]);
                ay.target(want[1]);
                e.x = Math.round(ax.update(ui.dt));
                e.y = Math.round(ay.update(ui.dt));
                taken.add(new int[]{want[0] - 2, want[1] - 2, want[0] + e.width + 2, want[1] + e.height + 2});
            }
            e.render(ui, false);
        }
    }
}
