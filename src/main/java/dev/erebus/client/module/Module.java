package dev.erebus.client.module;

import dev.erebus.client.notify.Notifications;
import dev.erebus.client.setting.KeybindSetting;
import dev.erebus.client.setting.ModeSetting;
import dev.erebus.client.setting.Setting;
import dev.erebus.client.util.Chat;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base class for modules. The dashboard lists modules by category and renders their settings automatically;
 * enabled modules receive {@link #onTick()} and {@link #onRenderWorld(WorldRender)}.
 */
public abstract class Module {
    protected static final Minecraft mc = Minecraft.getInstance();

    public final String name;
    public final String description;
    public final Category category;
    public final KeybindSetting keybind = new KeybindSetting("Keybind", "Key that toggles this module.", KeybindSetting.NONE);

    private final List<Setting<?>> settings = new ArrayList<>();
    private final boolean defaultEnabled;
    private boolean enabled;
    private String currentGroup;
    /** Shown in the dashboard and module list when non-null, e.g. how many things a finder has found. */
    protected String info;

    protected Module(String name, String description, Category category) {
        this(name, description, category, false);
    }

    protected Module(String name, String description, Category category, boolean defaultEnabled) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.defaultEnabled = defaultEnabled;
    }

    private dev.erebus.client.setting.BoolSetting visualsSetting;

    /**
     * Adds this module's "Better visuals" switch (in a Visuals section): purely cosmetic extras on top of what the
     * module normally draws. Call at the end of the constructor.
     */
    protected void addVisuals(String what) {
        group("Visuals");
        visualsSetting = add(new dev.erebus.client.setting.BoolSetting("Better visuals", what
                + " Purely cosmetic. Also needs Better visuals on in the dashboard's Client page.", true));
    }

    /** Whether the cosmetic extras should be drawn: this module's switch and the client-wide one are both on. */
    public boolean visuals() {
        return visualsSetting != null && visualsSetting.get() && dev.erebus.client.Erebus.client().betterVisuals.get();
    }

    private dev.erebus.client.setting.NumberSetting tagSizeSetting;

    /** Adds a Tag size for the labels this module floats in the world (on top of the client-wide one). */
    protected void addTagSize() {
        group("Tags");
        tagSizeSetting = add(new dev.erebus.client.setting.NumberSetting("Tag size", "How big this module's labels in the world are, "
                + "on top of the Tag size on the Client page (which also has opacity, fading and when to show details).", 1.0, 0.4, 2.0, 0.05)
                .unit("x"));
    }

    /** This module's own tag size (1 when it has none). */
    public float tagSize() {
        return tagSizeSetting == null ? 1f : tagSizeSetting.floatValue();
    }

    /** Settings added after this call are listed under a section with this title. */
    protected void group(String title) {
        currentGroup = title;
    }

    protected <S extends Setting<?>> S add(S setting) {
        if (setting.group == null) setting.group = currentGroup;
        settings.add(setting);
        return setting;
    }

    public List<Setting<?>> settings() {
        return Collections.unmodifiableList(settings);
    }

    public boolean defaultEnabled() {
        return defaultEnabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean on) {
        setEnabled(on, true);
    }

    /** Turns the module on or off; {@code announce} = show a toast. Config loading turns modules on silently. */
    public void setEnabled(boolean on, boolean announce) {
        if (on == enabled) return;
        enabled = on;
        try {
            if (on) onEnable();
            else onDisable();
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("Erebus").warn("{} failed to {}", name, on ? "enable" : "disable", t);
        }
        if (announce && announcesToggle()) Notifications.moduleToggled(name, on);
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    /** Short status shown next to the name in the module list HUD, or null. */
    public String suffix() {
        if (info != null) return info;
        for (Setting<?> s : settings) {
            if (s instanceof ModeSetting mode) return mode.get();
        }
        return null;
    }

    /** A button shown on the module's dashboard page. */
    public record Action(String label, Runnable run) {
    }

    /** Buttons for the module's dashboard page (e.g. Open map). */
    public List<Action> actions() {
        return List.of();
    }

    public String info() {
        return info;
    }

    /** A message from this module: a toast, and kept on the dashboard's Messages page. */
    protected void chat(String message, dev.erebus.client.notify.Notifications.Action... actions) {
        Chat.info(name, message, actions);
    }

    /** A message worth noticing (a find, someone nearby), from this module. */
    protected void alert(String message, dev.erebus.client.notify.Notifications.Action... actions) {
        Chat.alert(name, message, actions);
    }

    protected void onEnable() {
    }

    protected void onDisable() {
    }

    /** Whether turning this module on or off shows a toast (one-shot modules report their own outcome instead). */
    protected boolean announcesToggle() {
        return true;
    }

    /**
     * Called at the start of every client tick while enabled, before the game reads the crosshair and handles
     * key presses. Input simulated here (see dev.erebus.client.input) is handled by the game in this same tick.
     */
    public void onPreTick() {
    }

    /** Called at the end of every client tick while enabled. */
    public void onTick() {
    }

    /** Called once per frame while the world is rendered, with world gizmo collection active. */
    public void onRenderWorld(WorldRender ctx) {
    }

    /** Called once per frame on the HUD, under the HUD elements. Use {@link dev.erebus.client.render.Projection} for world labels. */
    public void onRenderOverlay(dev.erebus.client.gui.Ui ui) {
    }
}
