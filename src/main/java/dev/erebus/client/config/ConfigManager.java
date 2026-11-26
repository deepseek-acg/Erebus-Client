package dev.erebus.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.erebus.client.Erebus;
import dev.erebus.client.hud.HudElement;
import dev.erebus.client.hud.elements.NotificationsElement;
import dev.erebus.client.setting.Setting;
import dev.erebus.client.theme.Theme;
import dev.erebus.client.theme.ThemeColor;
import dev.erebus.client.theme.ThemePreset;
import dev.erebus.client.util.Colors;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Reads and writes config/erebus/*.json. */
public final class ConfigManager {
    private static final Logger LOG = LoggerFactory.getLogger("Erebus");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path dir = FabricLoader.getInstance().getConfigDir().resolve("erebus");

    public void load() {
        read("theme.json", this::loadTheme);
        read("hud.json", this::loadHud);
        read("client.json", json -> loadSettings(Erebus.client().all(), json));
        if (Erebus.hud().get("notifications") instanceof NotificationsElement n) n.syncDuration();
    }

    public void save() {
        write("theme.json", saveTheme());
        write("hud.json", saveHud());
        write("client.json", saveSettings(Erebus.client().all()));
    }

    // ------------------------------------------------------------------ theme

    private JsonObject saveTheme() {
        Theme t = Erebus.theme();
        JsonObject o = new JsonObject();
        o.addProperty("base", t.base().id);
        o.addProperty("modified", t.modified());
        JsonObject colors = new JsonObject();
        for (ThemeColor c : ThemeColor.values()) colors.addProperty(c.id, Colors.toHex(t.get(c), true));
        o.add("colors", colors);
        o.addProperty("radius", t.radius);
        o.addProperty("panelOpacity", t.panelOpacity);
        o.addProperty("shadow", t.shadow);
        o.addProperty("blur", t.blur);
        o.addProperty("textShadow", t.textShadow);
        o.addProperty("animations", t.animations);
        return o;
    }

    private void loadTheme(JsonObject o) {
        Theme t = Erebus.theme();
        t.apply(ThemePreset.byId(str(o, "base", "void")));
        if (o.has("colors") && o.get("colors").isJsonObject()) {
            for (var e : o.getAsJsonObject("colors").entrySet()) {
                ThemeColor c = ThemeColor.byId(e.getKey());
                Integer v = e.getValue().isJsonPrimitive() ? Colors.parseHex(e.getValue().getAsString()) : null;
                if (c != null && v != null) t.set(c, v);
            }
        }
        t.radius = Math.max(0, Math.min(12, num(o, "radius", t.radius)));
        t.panelOpacity = (float) Math.max(0.3, Math.min(1.0, dbl(o, "panelOpacity", t.panelOpacity)));
        t.shadow = bool(o, "shadow", t.shadow);
        t.blur = bool(o, "blur", t.blur);
        t.textShadow = bool(o, "textShadow", t.textShadow);
        t.setAnimations(bool(o, "animations", true));
        t.setModified(bool(o, "modified", false));
    }

    // ------------------------------------------------------------------ hud

    private JsonObject saveHud() {
        JsonObject o = new JsonObject();
        if (Erebus.hud().preset() != null) o.addProperty("preset", Erebus.hud().preset());
        JsonObject els = new JsonObject();
        for (HudElement e : Erebus.hud().all()) {
            JsonObject j = new JsonObject();
            j.addProperty("enabled", e.isEnabled());
            j.addProperty("h", e.hAnchor.name());
            j.addProperty("v", e.vAnchor.name());
            j.addProperty("x", e.offX);
            j.addProperty("y", e.offY);
            j.add("settings", saveSettings(e.settings()));
            els.add(e.id, j);
        }
        o.add("elements", els);
        return o;
    }

    private void loadHud(JsonObject o) {
        Erebus.hud().setPreset(o.has("preset") ? str(o, "preset", null) : null);
        if (!o.has("elements") || !o.get("elements").isJsonObject()) return;
        JsonObject els = o.getAsJsonObject("elements");
        for (HudElement e : Erebus.hud().all()) {
            if (!els.has(e.id) || !els.get(e.id).isJsonObject()) continue;
            JsonObject j = els.getAsJsonObject(e.id);
            e.setEnabled(bool(j, "enabled", e.defaultEnabled()));
            try {
                e.hAnchor = HudElement.HAnchor.valueOf(str(j, "h", e.hAnchor.name()));
                e.vAnchor = HudElement.VAnchor.valueOf(str(j, "v", e.vAnchor.name()));
            } catch (IllegalArgumentException ignored) {
                e.resetPosition();
            }
            e.offX = num(j, "x", e.offX);
            e.offY = num(j, "y", e.offY);
            if (j.has("settings") && j.get("settings").isJsonObject()) loadSettings(e.settings(), j.getAsJsonObject("settings"));
        }
    }

    // ------------------------------------------------------------------ settings

    private static JsonObject saveSettings(List<Setting<?>> settings) {
        JsonObject o = new JsonObject();
        for (Setting<?> s : settings) o.add(s.id, s.toJson());
        return o;
    }

    private static void loadSettings(List<Setting<?>> settings, JsonObject o) {
        for (Setting<?> s : settings) {
            if (o.has(s.id)) s.fromJson(o.get(s.id));
        }
    }

    // ------------------------------------------------------------------ io

    private interface Loader {
        void load(JsonObject json);
    }

    private void read(String file, Loader loader) {
        Path p = dir.resolve(file);
        if (!Files.isRegularFile(p)) return;
        try {
            JsonElement json = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
            if (json.isJsonObject()) loader.load(json.getAsJsonObject());
        } catch (Exception e) {
            LOG.warn("Could not read {}; using defaults", p, e);
        }
    }

    private void write(String file, JsonObject json) {
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(file);
            Path tmp = dir.resolve(file + ".tmp");
            Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.warn("Could not save {}", file, e);
        }
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }

    private static int num(JsonObject o, String k, int def) {
        return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isNumber() ? o.get(k).getAsInt() : def;
    }

    private static double dbl(JsonObject o, String k, double def) {
        return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isNumber() ? o.get(k).getAsDouble() : def;
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isBoolean() ? o.get(k).getAsBoolean() : def;
    }
}
