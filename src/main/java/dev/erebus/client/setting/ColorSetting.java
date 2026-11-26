package dev.erebus.client.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.erebus.client.util.Colors;

public final class ColorSetting extends Setting<Integer> {
    public ColorSetting(String name, String description, int defaultValue) {
        super(name, description, defaultValue);
    }

    public ColorSetting(String name, int defaultValue) {
        this(name, "", defaultValue);
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(Colors.toHex(value, true));
    }

    @Override
    public void fromJson(JsonElement json) {
        if (json != null && json.isJsonPrimitive()) {
            Integer c = Colors.parseHex(json.getAsString());
            if (c != null) set(c);
        }
    }
}
