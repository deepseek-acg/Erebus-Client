package dev.erebus.client.module;

import dev.erebus.client.notify.Notifications;
import dev.erebus.client.setting.KeybindSetting;
import dev.erebus.client.setting.ModeSetting;
import dev.erebus.client.setting.Setting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base class for future modules. The dashboard lists modules by category and renders their
 * settings automatically; no concrete modules exist yet.
 */
public abstract class Module {
    public final String name;
    public final String description;
    public final Category category;
    public final KeybindSetting keybind = new KeybindSetting("Keybind", "Key that toggles this module.", KeybindSetting.NONE);

    private final List<Setting<?>> settings = new ArrayList<>();
    private boolean enabled;

    protected Module(String name, String description, Category category) {
        this.name = name;
        this.description = description;
        this.category = category;
    }

    protected <S extends Setting<?>> S add(S setting) {
        settings.add(setting);
        return setting;
    }

    public List<Setting<?>> settings() {
        return Collections.unmodifiableList(settings);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean on) {
        if (on == enabled) return;
        enabled = on;
        if (on) onEnable();
        else onDisable();
        Notifications.moduleToggled(name, on);
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    /** Short status shown next to the name in the module list HUD, or null. */
    public String suffix() {
        for (Setting<?> s : settings) {
            if (s instanceof ModeSetting mode) return mode.get();
        }
        return null;
    }

    protected void onEnable() {
    }

    protected void onDisable() {
    }
}
