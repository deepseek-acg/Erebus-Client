package dev.erebus.client.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.List;

public final class ModeSetting extends Setting<String> {
    public final List<String> modes;

    public ModeSetting(String name, String description, String defaultValue, String... modes) {
        super(name, description, defaultValue);
        this.modes = List.of(modes);
    }

    /** Mode setting without a description. */
    public static ModeSetting of(String name, String defaultValue, String... modes) {
        return new ModeSetting(name, "", defaultValue, modes);
    }

    public boolean is(String mode) {
        return value.equals(mode);
    }

    public void cycle(int dir) {
        int i = modes.indexOf(value);
        set(modes.get(Math.floorMod(i + dir, modes.size())));
    }

    @Override
    protected String sanitize(String v) {
        return modes.contains(v) ? v : null;
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(JsonElement json) {
        if (json != null && json.isJsonPrimitive()) set(json.getAsString());
    }
}
