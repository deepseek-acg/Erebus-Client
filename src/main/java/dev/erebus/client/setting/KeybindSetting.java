package dev.erebus.client.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;

/** A keyboard key code (GLFW), or {@link #NONE} when unbound. */
public final class KeybindSetting extends Setting<Integer> {
    public static final int NONE = -1;

    public KeybindSetting(String name, String description, int defaultValue) {
        super(name, description, defaultValue);
    }

    public KeybindSetting(String name, int defaultValue) {
        this(name, "", defaultValue);
    }

    public boolean isBound() {
        return value != NONE;
    }

    public String keyName() {
        return keyName(value);
    }

    public static String keyName(int key) {
        if (key == NONE) return "None";
        return InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(JsonElement json) {
        if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) set(json.getAsInt());
    }
}
