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

    /** A recorded structure. {@code rejected}: it doesn't fit the seed (most likely misread), so it's left out. */
    public record Entry(String kind, int chunkX, int chunkZ, int x, int y, int z, boolean rejected) {
        public Entry withRejected(boolean r) {
            return new Entry(kind, chunkX, chunkZ, x, y, z, r);
        }

        public StructureScanner.Kind kindOrNull() {
            try {
                return StructureScanner.Kind.valueOf(kind);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    public List<Entry> structures = new ArrayList<>();
    public long hashedSeed;
    public Long structureSeed;
    public Long worldSeed;
    /**
     * What was seen of the End's pillars: per pillar (in the game's order), its slot 0-9 (from the bedrock on top),
     * 10 + its radius class (from its width) when only that's known, or -1. Null if never seen.
     */
    public int[] pillars;
    /** The pillar shuffle keys (0-65535) that fit {@link #pillars}, sorted; null if none were seen. */
    public int[] pillarKeys;

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

    /** The recorded structures that count (not rejected). */
    public long counted() {
        return structures.stream().filter(e -> !e.rejected()).count();
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
            StructureScanner.Kind k = e.kindOrNull();
            if (k == null || e.rejected()) continue;
            if (k == StructureScanner.Kind.BURIED_TREASURE && !withChances) continue;
            out.addAll(k.clues(e.chunkX(), e.chunkZ()));
        }
        if (pillarKeys != null && pillarKeys.length > 0 && pillarKeys.length < 65536) out.add(new SeedSolver.PillarKeys(pillarKeys));
        return out;
    }
}
