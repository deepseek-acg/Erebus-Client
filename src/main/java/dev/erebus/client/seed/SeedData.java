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

    /**
     * A recorded structure. {@code rejected}: it doesn't fit the seed (most likely misread), so it's left out.
     * {@code piece} and {@code rotation}: how its first piece came out, if known (null in older files): a
     * village's town centre template, or a mineshaft's start room size as "room 10x12" (-1 where unmeasured).
     */
    public record Entry(String kind, int chunkX, int chunkZ, int x, int y, int z, boolean rejected, String piece, Integer rotation) {
        public Entry withRejected(boolean r) {
            return new Entry(kind, chunkX, chunkZ, x, y, z, r, piece, rotation);
        }

        public List<SeedSolver.Clue> clues() {
            StructureScanner.Kind k = kindOrNull();
            return k == null ? List.of() : k.clues(chunkX, chunkZ, piece, rotation);
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
    /**
     * True once the seed turned out to need each kind of structure on its own salt (a Spigot or Paper server with
     * its {@code seed-village}, {@code seed-desert}... settings changed). Null if not known to.
     */
    public Boolean customSalts;
    /** The salts worked out, by structure kind ({@link StructureScanner.Kind} name); kinds not in it aren't known yet. */
    public java.util.Map<String, Integer> salts;
    /** Whether mineshafts have their own salt too (Paper's {@code seed-mineshaft}): only their room sizes fit the seed. */
    public boolean shaftSalted;

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

    public boolean salted() {
        return Boolean.TRUE.equals(customSalts);
    }

    /**
     * What a recorded structure says about the seed, with this server's salts: on a server with its own salts, a
     * structure of a kind whose salt isn't known yet only counts for what doesn't depend on it (how its first piece
     * came out, an outpost's extra roll), and mineshafts and buried treasure only where their salt is the game's own.
     */
    public List<SeedSolver.Clue> fitClues(Entry e) {
        if (!salted()) return e.clues();
        StructureScanner.Kind k = e.kindOrNull();
        if (k == null || k == StructureScanner.Kind.BURIED_TREASURE) return List.of();
        if (k == StructureScanner.Kind.MINESHAFT && shaftSalted) return List.of();
        Integer salt = salts == null ? null : salts.get(k.name());
        List<SeedSolver.Clue> out = new ArrayList<>();
        for (SeedSolver.Clue c : e.clues()) {
            if (c instanceof SeedSolver.Spread s) {
                if (salt != null) out.add(new SeedSolver.Spread(s.spacing(), s.separation(), salt, s.chunkX(), s.chunkZ()));
            } else {
                out.add(c);
            }
        }
        return out;
    }

    /** For {@link SaltSolver}: what doesn't depend on salts (End pillars, first pieces, outpost rolls, mineshafts). */
    public List<SeedSolver.Clue> saltAnchors() {
        List<SeedSolver.Clue> out = new ArrayList<>();
        for (SeedSolver.Clue c : clues(false)) {
            if (!(c instanceof SeedSolver.Spread)) out.add(c);
        }
        return out;
    }

    /** For {@link SaltSolver}: the structures of each kind whose salt Spigot can change. */
    public List<SaltSolver.Group> saltGroups() {
        java.util.Map<StructureScanner.Kind, List<SeedSolver.Spread>> by = new java.util.EnumMap<>(StructureScanner.Kind.class);
        for (Entry e : structures) {
            StructureScanner.Kind k = e.kindOrNull();
            if (k == null || e.rejected() || !k.spread()) continue;
            for (SeedSolver.Clue c : e.clues()) {
                if (c instanceof SeedSolver.Spread s) by.computeIfAbsent(k, x -> new ArrayList<>()).add(s);
            }
        }
        List<SaltSolver.Group> out = new ArrayList<>();
        for (var en : by.entrySet()) {
            StructureScanner.Kind k = en.getKey();
            out.add(new SaltSolver.Group(k.name(), k.spigotSetting, k.salt, en.getValue()));
        }
        return out;
    }

    public List<SeedSolver.Clue> clues(boolean withChances) {
        List<SeedSolver.Clue> out = new ArrayList<>();
        for (Entry e : structures) {
            StructureScanner.Kind k = e.kindOrNull();
            if (k == null || e.rejected()) continue;
            if (k == StructureScanner.Kind.BURIED_TREASURE && !withChances) continue;
            out.addAll(e.clues());
        }
        if (pillarKeys != null && pillarKeys.length > 0 && pillarKeys.length < 65536) out.add(new SeedSolver.PillarKeys(pillarKeys));
        return out;
    }
}
