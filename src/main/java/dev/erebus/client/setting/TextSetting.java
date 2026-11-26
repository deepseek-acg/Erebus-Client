package dev.erebus.client.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class TextSetting extends Setting<String> {
    public final int maxLength;

    public TextSetting(String name, String description, String defaultValue, int maxLength) {
        super(name, description, defaultValue);
        this.maxLength = maxLength;
    }

    public TextSetting(String name, String defaultValue, int maxLength) {
        this(name, "", defaultValue, maxLength);
    }

    @Override
    protected String sanitize(String v) {
        if (v == null) return null;
        return v.length() > maxLength ? v.substring(0, maxLength) : v;
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
