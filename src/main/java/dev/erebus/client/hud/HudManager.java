package dev.erebus.client.hud;

import dev.erebus.client.gui.Ui;
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
        for (HudElement e : elements) {
            if (!e.isEnabled()) continue;
            e.layout(ui, sw, sh, false);
            e.render(ui, false);
        }
    }
}
