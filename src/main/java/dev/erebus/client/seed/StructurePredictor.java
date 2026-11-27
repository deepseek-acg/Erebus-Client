package dev.erebus.client.seed;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Works out where structures generate from a world seed, with the game's own world generation code: the vanilla
 * structure sets (spacing, salts, frequency and exclusion rules, stronghold rings), the vanilla biome sources and
 * terrain noise, and each structure's own placement checks (biome, and terrain height for the ones that need flat
 * or high ground). Everything is built on the client from the game's built-in registries, so it also works in
 * multiplayer, for servers with vanilla world generation settings.
 *
 * <p>Results are computed on a background thread, one region at a time, and cached.
 */
public final class StructurePredictor {
    /** The structure kinds that can be shown, with the labels the map uses for found structures. */
    public enum Kind {
        VILLAGE("Village", 0xFF8BC34A), DESERT_PYRAMID("Desert Pyramid", 0xFFFDE68A), JUNGLE_TEMPLE("Jungle Temple", 0xFF65A30D),
        SWAMP_HUT("Swamp Hut", 0xFF4D7C0F), IGLOO("Igloo", 0xFFBAE6FD), OUTPOST("Pillager Outpost", 0xFF9CA3AF),
        MONUMENT("Ocean Monument", 0xFF5EEAD4), MANSION("Woodland Mansion", 0xFFA16207), ANCIENT_CITY("Ancient City", 0xFF2DD4BF),
        TRAIL_RUINS("Trail Ruins", 0xFFC08457), TRIAL_CHAMBERS("Trial Chamber", 0xFFE0823D), RUINED_PORTAL("Ruined Portal", 0xFFA855F7),
        SHIPWRECK("Shipwreck", 0xFF92400E), OCEAN_RUIN("Ocean Ruin", 0xFF38BDF8), STRONGHOLD("Stronghold", 0xFF86EFAC),
        FORTRESS("Nether Fortress", 0xFFB91C1C), BASTION("Bastion Remnant", 0xFFFBBF24), END_CITY("End City", 0xFFE879F9);

        public final String label;
        public final int color;

        Kind(String label, int color) {
            this.label = label;
            this.color = color;
        }

        static Kind of(String path) {
            if (path.startsWith("village")) return VILLAGE;
            if (path.startsWith("ruined_portal")) return RUINED_PORTAL;
            if (path.startsWith("shipwreck")) return SHIPWRECK;
            if (path.startsWith("ocean_ruin")) return OCEAN_RUIN;
            return switch (path) {
                case "desert_pyramid" -> DESERT_PYRAMID;
                case "jungle_pyramid" -> JUNGLE_TEMPLE;
                case "swamp_hut" -> SWAMP_HUT;
                case "igloo" -> IGLOO;
                case "pillager_outpost" -> OUTPOST;
                case "monument" -> MONUMENT;
                case "mansion" -> MANSION;
                case "ancient_city" -> ANCIENT_CITY;
                case "trail_ruins" -> TRAIL_RUINS;
                case "trial_chambers" -> TRIAL_CHAMBERS;
                case "stronghold" -> STRONGHOLD;
                case "fortress" -> FORTRESS;
                case "bastion_remnant" -> BASTION;
                case "end_city" -> END_CITY;
                default -> null;
            };
        }
    }

    /** A structure expected at (x, z). */
    public record Predicted(Kind kind, int x, int z) {
    }

    private record Entry(Holder<Structure> structure, int weight, Kind kind, String path) {
    }

    private record SetInfo(int index, RandomSpreadStructurePlacement spread, ConcentricRingsStructurePlacement rings, List<Entry> entries) {
        boolean shows(Set<Kind> kinds) {
            for (Entry e : entries) if (e.kind() != null && kinds.contains(e.kind())) return true;
            return false;
        }
    }

    private static HolderLookup.Provider lookup;
    private static StructurePredictor current;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Erebus structure predictor");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private final long seed;
    private final ResourceKey<Level> dimension;
    private volatile boolean ready;
    private volatile String failure;
    private BiomeSource biomes;
    private RandomState random;
    private NoiseBasedChunkGenerator generator;
    private ChunkGeneratorStructureState state;
    private LevelHeightAccessor heights;
    private final List<SetInfo> sets = new ArrayList<>();
    private final Map<Long, Optional<Predicted>> regions = new ConcurrentHashMap<>();
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();
    private volatile List<Predicted> strongholds = List.of();

    private StructurePredictor(long seed, ResourceKey<Level> dimension) {
        this.seed = seed;
        this.dimension = dimension;
        WORKER.submit(this::init);
    }

    /** The predictor for this seed and dimension (created, and set up in the background, on first use). */
    public static synchronized StructurePredictor get(long seed, ResourceKey<Level> dimension) {
        if (current == null || current.seed != seed || !current.dimension.equals(dimension)) current = new StructurePredictor(seed, dimension);
        return current;
    }

    public boolean ready() {
        return ready;
    }

    /** Why predictions aren't available, or null. */
    public String failure() {
        return failure;
    }

    private void init() {
        try {
            if (lookup == null) lookup = VanillaRegistries.createLookup();
            var settingsLookup = lookup.lookupOrThrow(Registries.NOISE_SETTINGS);
            Holder<NoiseGeneratorSettings> settings;
            if (dimension == Level.NETHER) {
                settings = settingsLookup.getOrThrow(NoiseGeneratorSettings.NETHER);
                biomes = MultiNoiseBiomeSource.createFromPreset(lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                        .getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER));
                heights = LevelHeightAccessor.create(0, 256);
            } else if (dimension == Level.END) {
                settings = settingsLookup.getOrThrow(NoiseGeneratorSettings.END);
                biomes = TheEndBiomeSource.create(lookup.lookupOrThrow(Registries.BIOME));
                heights = LevelHeightAccessor.create(0, 256);
            } else {
                settings = settingsLookup.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
                biomes = MultiNoiseBiomeSource.createFromPreset(lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                        .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
                heights = LevelHeightAccessor.create(-64, 384);
            }
            random = RandomState.create(settings.value(), lookup.lookupOrThrow(Registries.NOISE), seed);
            generator = new NoiseBasedChunkGenerator(biomes, settings);
            state = ChunkGeneratorStructureState.createForNormal(random, seed, biomes, lookup.lookupOrThrow(Registries.STRUCTURE_SET));
            int i = 0;
            for (Holder<StructureSet> h : state.possibleStructureSets()) {
                StructureSet set = h.value();
                List<Entry> entries = new ArrayList<>();
                for (StructureSet.StructureSelectionEntry e : set.structures()) {
                    String path = e.structure().unwrapKey().map(k -> k.identifier().getPath()).orElse("");
                    entries.add(new Entry(e.structure(), e.weight(), Kind.of(path), path));
                }
                if (set.placement() instanceof RandomSpreadStructurePlacement spread) sets.add(new SetInfo(i++, spread, null, entries));
                else if (set.placement() instanceof ConcentricRingsStructurePlacement rings) sets.add(new SetInfo(i++, null, rings, entries));
            }
            ready = true;
            System.out.println("[PRED-DEBUG] ready, " + sets.size() + " structure sets");
            // strongholds: the game works their ring positions out itself (it takes a few seconds)
            for (SetInfo s : sets) {
                if (s.rings() == null) continue;
                List<ChunkPos> ring = state.getRingPositionsFor(s.rings());
                if (ring == null) continue;
                List<Predicted> out = new ArrayList<>();
                for (ChunkPos p : ring) out.add(new Predicted(Kind.STRONGHOLD, p.getMiddleBlockX(), p.getMiddleBlockZ()));
                strongholds = out;
            }
        } catch (Throwable t) {
            failure = "couldn't set up world generation: " + t;
            org.slf4j.LoggerFactory.getLogger("Erebus").error("Structure predictor setup failed", t);
        }
    }

    /**
     * The structures of these kinds predicted inside the block rectangle. Regions not worked out yet are queued
     * and show up on a later call.
     */
    public List<Predicted> query(int minX, int minZ, int maxX, int maxZ, Set<Kind> kinds) {
        List<Predicted> out = new ArrayList<>();
        if (!ready || kinds.isEmpty()) return out;
        int cx0 = minX >> 4, cz0 = minZ >> 4, cx1 = maxX >> 4, cz1 = maxZ >> 4;
        for (SetInfo s : sets) {
            if (!s.shows(kinds)) continue;
            if (s.rings() != null) {
                for (Predicted p : strongholds) {
                    if (kinds.contains(p.kind()) && p.x() >= minX && p.x() <= maxX && p.z() >= minZ && p.z() <= maxZ) out.add(p);
                }
                continue;
            }
            int spacing = s.spread().spacing();
            int rx0 = Math.floorDiv(cx0, spacing), rx1 = Math.floorDiv(cx1, spacing);
            int rz0 = Math.floorDiv(cz0, spacing), rz1 = Math.floorDiv(cz1, spacing);
            if ((long) (rx1 - rx0 + 1) * (rz1 - rz0 + 1) > 2500) continue; // zoomed too far out for this one
            for (int rx = rx0; rx <= rx1; rx++) {
                for (int rz = rz0; rz <= rz1; rz++) {
                    long key = ((long) s.index() << 48) ^ ((long) (rx & 0xFFFFFF) << 24) ^ (rz & 0xFFFFFF);
                    Optional<Predicted> r = regions.get(key);
                    if (r == null) {
                        if (queued.size() < 4096 && queued.add(key)) {
                            int fx = rx, fz = rz;
                            WORKER.submit(() -> {
                                try {
                                    Optional<Predicted> res = compute(s, fx, fz);
                                    res.ifPresent(pp -> System.out.println("[PRED-DEBUG] " + pp.kind() + " " + pp.x() + " " + pp.z()));
                                    regions.put(key, res);
                                } catch (Throwable t) {
                                    regions.put(key, Optional.empty());
                                } finally {
                                    queued.remove(key);
                                }
                            });
                        }
                        continue;
                    }
                    if (r.isPresent() && kinds.contains(r.get().kind())) {
                        Predicted p = r.get();
                        if (p.x() >= minX - 16 && p.x() <= maxX + 16 && p.z() >= minZ - 16 && p.z() <= maxZ + 16) out.add(p);
                    }
                }
            }
        }
        if (regions.size() > 200_000) regions.clear();
        return out;
    }

    /** What, if anything, generates in this region of a random-spread structure set. */
    private Optional<Predicted> compute(SetInfo s, int rx, int rz) {
        RandomSpreadStructurePlacement spread = s.spread();
        ChunkPos chunk = spread.getPotentialStructureChunk(seed, rx * spread.spacing(), rz * spread.spacing());
        if (!spread.isStructureChunk(state, chunk.x, chunk.z)) return Optional.empty();
        List<Entry> entries = s.entries();
        if (entries.size() == 1) return place(entries.get(0), chunk);
        // several structures share the set: the game picks one by weight, and the next if that one can't go there
        List<Entry> left = new ArrayList<>(entries);
        WorldgenRandom r = new WorldgenRandom(new LegacyRandomSource(0L));
        r.setLargeFeatureSeed(seed, chunk.x, chunk.z);
        int total = 0;
        for (Entry e : left) total += e.weight();
        while (!left.isEmpty()) {
            int j = r.nextInt(total);
            int k = 0;
            for (Entry e : left) {
                j -= e.weight();
                if (j < 0) break;
                k++;
            }
            Entry e = left.get(k);
            Optional<Predicted> p = place(e, chunk);
            if (p.isPresent()) return p;
            left.remove(k);
            total -= e.weight();
        }
        return Optional.empty();
    }

    /** Whether this structure generates at this chunk, and where. */
    private Optional<Predicted> place(Entry e, ChunkPos chunk) {
        Structure structure = e.structure().value();
        if (structure.type() != StructureType.JIGSAW) {
            try {
                Structure.GenerationContext ctx = new Structure.GenerationContext(RegistryAccess.EMPTY, generator, biomes, random, null, seed, chunk,
                        heights, structure.biomes()::contains);
                Optional<Structure.GenerationStub> stub = structure.findValidGenerationPoint(ctx);
                if (stub.isEmpty()) return Optional.empty();
                BlockPos p = stub.get().position();
                return e.kind() == null ? Optional.empty() : Optional.of(new Predicted(e.kind(), p.getX(), p.getZ()));
            } catch (Throwable ignored) {
                // needs something only a server has (structure templates): fall back to the biome check below
            }
        }
        // jigsaw structures (villages, outposts, cities...): the game checks the biome where the start piece goes
        int x = chunk.getMinBlockX() + 8, z = chunk.getMinBlockZ() + 8;
        int y;
        if (e.path().equals("ancient_city")) y = -27;
        else if (e.path().equals("trial_chambers")) y = -30;
        else if (dimension == Level.NETHER) y = 64;
        else y = generator.getFirstOccupiedHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, heights, random);
        Holder<Biome> biome = biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), random.sampler());
        if (!structure.biomes().contains(biome) || e.kind() == null) return Optional.empty();
        return Optional.of(new Predicted(e.kind(), x, z));
    }
}
