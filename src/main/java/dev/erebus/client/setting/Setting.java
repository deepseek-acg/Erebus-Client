package dev.erebus.client.setting;

import com.google.gson.JsonElement;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A named, persisted value that the dashboard renders as an editing widget.
 * Shared by HUD elements and (later) modules.
 */
public abstract class Setting<T> {
    public final String id;
    public final String name;
    public final String description;
    protected final T defaultValue;
    protected T value;
    private Consumer<T> onChange;
    private BooleanSupplier visible = () -> true;

    protected Setting(String name, String description, T defaultValue) {
        this.id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        this.name = name;
        this.description = description;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public T get() {
        return value;
    }

    public void set(T v) {
        T sanitized = sanitize(v);
        if (sanitized == null || sanitized.equals(value)) return;
        value = sanitized;
        if (onChange != null) onChange.accept(value);
    }

    public T defaultValue() {
        return defaultValue;
    }

    public void reset() {
        set(defaultValue);
    }

    protected T sanitize(T v) {
        return v;
    }

    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S onChange(Consumer<T> listener) {
        this.onChange = listener;
        return (S) this;
    }

    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S visibleWhen(BooleanSupplier condition) {
        this.visible = condition;
        return (S) this;
    }

    public boolean isVisible() {
        return visible.getAsBoolean();
    }

    public abstract JsonElement toJson();

    public abstract void fromJson(JsonElement json);
}
