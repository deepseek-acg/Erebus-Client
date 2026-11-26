package dev.erebus.client.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Locale;

public final class NumberSetting extends Setting<Double> {
    public final double min;
    public final double max;
    public final double step;
    private String unit = "";

    public NumberSetting(String name, String description, double defaultValue, double min, double max, double step) {
        super(name, description, defaultValue);
        this.min = min;
        this.max = max;
        this.step = step;
    }

    public NumberSetting(String name, double defaultValue, double min, double max, double step) {
        this(name, "", defaultValue, min, max, step);
    }

    public NumberSetting unit(String unit) {
        this.unit = unit;
        return this;
    }

    public int intValue() {
        return (int) Math.round(value);
    }

    public float floatValue() {
        return value.floatValue();
    }

    /** Normalized position of the value between min and max. */
    public double fraction() {
        return max == min ? 0 : (value - min) / (max - min);
    }

    public void setFraction(double f) {
        set(min + Math.max(0, Math.min(1, f)) * (max - min));
    }

    @Override
    protected Double sanitize(Double v) {
        if (v == null || v.isNaN()) return null;
        double snapped = step > 0 ? Math.round((v - min) / step) * step + min : v;
        snapped = Math.max(min, Math.min(max, snapped));
        return Math.round(snapped * 1e6) / 1e6;
    }

    public String format() {
        String num = step >= 1 && Math.abs(step - Math.rint(step)) < 1e-9
                ? Integer.toString(intValue())
                : String.format(Locale.ROOT, step >= 0.1 ? "%.1f" : "%.2f", value);
        return num + unit;
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(JsonElement json) {
        if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) set(json.getAsDouble());
    }
}
