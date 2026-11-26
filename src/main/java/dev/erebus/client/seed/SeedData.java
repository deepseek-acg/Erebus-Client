package dev.erebus.client.seed;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** What's known about one server's seed, kept in {@code .minecraft/erebus/seeds/<server>.json}. */
public final class SeedData {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record Entry(String kind, int chunkX, int chunkZ, int x, int y, int z) {
    }

    public List<Entry> structures = new ArrayList<>();
    public long hashedSeed;
    public Long structureSeed;
    public Long worldSeed;

    private transient String key;

    public static String serverKey() {
        Minecraft mc = Minecraft.getInstance();
        var sd = mc.getCurrentServer();
        if (sd != null && sd.ip != null) return sd.ip.toLowerCase(Locale.ROOT);
        try {
            if (mc.getSingleplayerServer() != null) return "singleplayer-" + mc.getSingleplayerServer().getWorldData().getLevelName();
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    private static File file(String key) {
        return new File(Minecraft.getInstance().gameDirectory, "erebus/seeds/" + key.replaceAll("[^a-zA-Z0-9._-]", "_") + ".json");
    }

    public static SeedData load(String key) {
        SeedData d = null;
        File f = file(key);
        if (f.isFile()) {
            try (FileReader r = new FileReader(f)) {
                d = GSON.fromJson(r, SeedData.class);
            } catch (Exception ignored) {
            }
        }
        if (d == null) d = new SeedData();
        if (d.structures == null) d.structures = new ArrayList<>();
        d.key = key;
        return d;
    }

    public void save() {
        File f = file(key);
        f.getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(f)) {
            GSON.toJson(this, w);
        } catch (Exception ignored) {
        }
    }

    public String key() {
        return key;
    }

    public boolean has(StructureScanner.Kind kind, int chunkX, int chunkZ) {
        for (Entry e : structures) {
            if (e.kind().equals(kind.name()) && e.chunkX() == chunkX && e.chunkZ() == chunkZ) return true;
        }
        return false;
    }

    public List<SeedSolver.Clue> clues(boolean withChances) {
        List<SeedSolver.Clue> out = new ArrayList<>();
        for (Entry e : structures) {
            StructureScanner.Kind k;
            try {
                k = StructureScanner.Kind.valueOf(e.kind());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (k == StructureScanner.Kind.BURIED_TREASURE && !withChances) continue;
            out.add(k.clue(e.chunkX(), e.chunkZ()));
        }
        return out;
    }
}
