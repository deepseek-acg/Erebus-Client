package dev.erebus.client.seed;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
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
 * structure sets (spacing, salts, frequency rules), the vanilla biome sources and terrain noise, and each
 * structure's own placement checks (biome, and terrain height for the ones that need flat or high ground). The
 * pieces the client can't run as-is are copied from the game step for step: the outposts' distance from villages,
 * the stronghold rings, and where a jigsaw structure's start piece goes. Everything is built on the client from the
 * game's built-in registries and the vanilla tag and template files in the game jar, so it also works in
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

    /** One structure of a set, with the biomes it may generate in (resolved from the vanilla tag files). */
    private record Entry(Holder<Structure> structure, int weight, Kind kind, String path, Set<net.minecraft.resources.Identifier> biomes) {
        boolean allows(Holder<Biome> biome) {
            return biome.unwrapKey().map(k -> biomes.contains(k.identifier())).orElse(false);
        }
    }

    private record SetInfo(int index, String path, RandomSpreadStructurePlacement spread, ConcentricRingsStructurePlacement rings, List<Entry> entries) {
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
    private RandomSpreadStructurePlacement villages;
    private static final Map<String, java.util.Set<net.minecraft.resources.Identifier>> TAGS = new ConcurrentHashMap<>();
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
            if (lookup == null) {
                HolderLookup.Provider l = VanillaRegistries.createLookup();
                bindBiomeTags(l);
                lookup = l;
            }
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
            // the structure sets that can generate in this dimension: any of their structures allows one of its biomes
            java.util.Set<net.minecraft.resources.Identifier> possible = new java.util.HashSet<>();
            for (Holder<Biome> b : biomes.possibleBiomes()) b.unwrapKey().ifPresent(k -> possible.add(k.identifier()));
            int i = 0;
            for (var ref : lookup.lookupOrThrow(Registries.STRUCTURE_SET).listElements().toList()) {
                StructureSet set = ref.value();
                List<Entry> entries = new ArrayList<>();
                boolean here = false;
                for (StructureSet.StructureSelectionEntry e : set.structures()) {
                    String path = e.structure().unwrapKey().map(k -> k.identifier().getPath()).orElse("");
                    java.util.Set<net.minecraft.resources.Identifier> allowed = allowedBiomes(e.structure().value().biomes());
                    for (var id : allowed) if (possible.contains(id)) here = true;
                    entries.add(new Entry(e.structure(), e.weight(), Kind.of(path), path, allowed));
                }
                if (!here) continue;
                String setPath = ref.key().identifier().getPath();
                if (set.placement() instanceof RandomSpreadStructurePlacement spread) {
                    sets.add(new SetInfo(i++, setPath, spread, null, entries));
                    if (setPath.equals("villages")) villages = spread;
                } else if (set.placement() instanceof ConcentricRingsStructurePlacement rings) {
                    sets.add(new SetInfo(i++, setPath, null, rings, entries));
                }
            }
            ready = true;
            // strongholds: the game works their ring positions out itself (it takes a few seconds)
            for (SetInfo s : sets) {
                if (s.rings() == null) continue;
                List<ChunkPos> ring = ringPositions(s.rings());
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
     * The built-in registries come without tags (a data pack normally supplies them), so every biome is given the
     * list of vanilla tags it's in, read from the tag files in the game jar; some placement checks ask a biome whether
     * it's in a tag. Structures' own biome tags are resolved separately ({@link #allowedBiomes}).
     */
    private static void bindBiomeTags(HolderLookup.Provider l) {
        var biomes = l.lookupOrThrow(Registries.BIOME);
        Map<net.minecraft.resources.Identifier, List<net.minecraft.tags.TagKey<Biome>>> tagsOf = new java.util.HashMap<>();
        for (String tag : biomeTagFiles()) {
            var key = net.minecraft.tags.TagKey.create(Registries.BIOME, net.minecraft.resources.Identifier.withDefaultNamespace(tag));
            for (var id : resolveTag(key.location(), TAGS, new java.util.HashSet<>())) tagsOf.computeIfAbsent(id, k -> new ArrayList<>()).add(key);
        }
        biomes.listElements().forEach(ref -> ((dev.erebus.client.mixin.HolderTagAccess.Reference) (Object) ref)
                .erebus$bindTags(tagsOf.getOrDefault(ref.key().identifier(), List.of())));
    }

    /** The biomes a holder set names: a tag resolved from the vanilla tag files, or the listed biomes. */
    private static java.util.Set<net.minecraft.resources.Identifier> allowedBiomes(HolderSet<Biome> set) {
        var key = set.unwrapKey();
        if (key.isPresent()) return resolveTag(key.get().location(), TAGS, new java.util.HashSet<>());
        java.util.Set<net.minecraft.resources.Identifier> out = new java.util.HashSet<>();
        set.stream().forEach(h -> h.unwrapKey().ifPresent(k -> out.add(k.identifier())));
        return out;
    }

    /**
     * Where a jigsaw structure's start piece is centred, the way JigsawStructure and JigsawPlacement work it out:
     * the start height, a random rotation and a random template from the start pool (same random sequence), the
     * rotated template's bounding box from its real size, and the surface height at its middle when the structure is
     * projected to the surface. Null for structures that start from a named jigsaw (they need the template's
     * jigsaw blocks).
     */
    private BlockPos jigsawStart(Structure structure, ChunkPos chunk) {
        var js = (dev.erebus.client.mixin.JigsawAccess.Structure) structure;
        if (js.erebus$startJigsawName().isPresent()) return null;
        WorldgenRandom r = new WorldgenRandom(new LegacyRandomSource(0L));
        r.setLargeFeatureSeed(seed, chunk.x, chunk.z);
        int y0 = js.erebus$startHeight().sample(r, new net.minecraft.world.level.levelgen.WorldGenerationContext(generator, heights));
        net.minecraft.world.level.block.Rotation rotation = net.minecraft.world.level.block.Rotation.getRandom(r);
        var element = js.erebus$startPool().value().getRandomTemplate(r);
        if (!(element instanceof net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement single)) return null;
        var id = ((dev.erebus.client.mixin.JigsawAccess.Element) single).erebus$template().left().orElse(null);
        if (id == null) return null;
        int[] size = templateSize(id);
        if (size == null) return null;
        int sx = size[0] - 1, sz = size[2] - 1;
        // the template's corners, rotated around its origin (StructureTemplate.transform with no mirror)
        int[] a = rotate(0, 0, rotation), b = rotate(sx, sz, rotation);
        int minX = chunk.getMinBlockX() + Math.min(a[0], b[0]), maxX = chunk.getMinBlockX() + Math.max(a[0], b[0]);
        int minZ = chunk.getMinBlockZ() + Math.min(a[1], b[1]), maxZ = chunk.getMinBlockZ() + Math.max(a[1], b[1]);
        int cx = (maxX + minX) / 2, cz = (maxZ + minZ) / 2;
        var project = js.erebus$projectStartToHeightmap();
        int y = project.isEmpty() ? y0 : y0 + generator.getFirstFreeHeight(cx, cz, project.get(), heights, random);
        return new BlockPos(cx, y, cz);
    }

    private static int[] rotate(int x, int z, net.minecraft.world.level.block.Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new int[]{-z, x};
            case CLOCKWISE_180 -> new int[]{-x, -z};
            case COUNTERCLOCKWISE_90 -> new int[]{z, -x};
            default -> new int[]{x, z};
        };
    }

    private static final Map<net.minecraft.resources.Identifier, int[]> SIZES = new ConcurrentHashMap<>();

    /** A structure template's size, read from its file in the game jar. */
    private static int[] templateSize(net.minecraft.resources.Identifier id) {
        int[] cached = SIZES.get(id);
        if (cached != null) return cached.length == 0 ? null : cached;
        int[] out = new int[0];
        String file = "/data/" + id.getNamespace() + "/structure/" + id.getPath() + ".nbt";
        try (var in = net.minecraft.client.Minecraft.class.getResourceAsStream(file)) {
            if (in != null) {
                var tag = net.minecraft.nbt.NbtIo.readCompressed(in, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                var size = tag.getListOrEmpty("size");
                if (size.size() == 3) out = new int[]{size.getIntOr(0, 1), size.getIntOr(1, 1), size.getIntOr(2, 1)};
            }
        } catch (Exception ignored) {
        }
        SIZES.put(id, out);
        return out.length == 0 ? null : out;
    }

    /** Whether a village start chunk is within {@code range} chunks (the outposts' exclusion zone). */
    private boolean villageNear(int x, int z, int range) {
        for (int cx = x - range; cx <= x + range; cx++) {
            for (int cz = z - range; cz <= z + range; cz++) {
                ChunkPos p = villages.getPotentialStructureChunk(seed, cx, cz);
                if (p.x == cx && p.z == cz) return true;
            }
        }
        return false;
    }

    /** Stronghold positions: the game's own ring algorithm (ChunkGeneratorStructureState), step for step. */
    private List<ChunkPos> ringPositions(ConcentricRingsStructurePlacement placement) {
        List<ChunkPos> out = new ArrayList<>();
        if (placement.count() == 0) return out;
        int distance = placement.distance();
        int count = placement.count();
        int spread = placement.spread();
        java.util.Set<net.minecraft.resources.Identifier> preferred = allowedBiomes(placement.preferredBiomes());
        java.util.function.Predicate<Holder<Biome>> ok = h -> h.unwrapKey().map(k -> preferred.contains(k.identifier())).orElse(false);
        net.minecraft.util.RandomSource rnd = net.minecraft.util.RandomSource.create();
        rnd.setSeed(seed);
        double angle = rnd.nextDouble() * Math.PI * 2.0;
        int inRing = 0, ring = 0;
        for (int n = 0; n < count; n++) {
            double dist = 4 * distance + distance * ring * 6 + (rnd.nextDouble() - 0.5) * (distance * 2.5);
            int cx = (int) Math.round(Math.cos(angle) * dist);
            int cz = (int) Math.round(Math.sin(angle) * dist);
            net.minecraft.util.RandomSource fork = rnd.fork();
            var found = biomes.findBiomeHorizontal(net.minecraft.core.SectionPos.sectionToBlockCoord(cx, 8), 0,
                    net.minecraft.core.SectionPos.sectionToBlockCoord(cz, 8), 112, ok, fork, random.sampler());
            if (found != null) {
                BlockPos b = found.getFirst();
                out.add(new ChunkPos(net.minecraft.core.SectionPos.blockToSectionCoord(b.getX()), net.minecraft.core.SectionPos.blockToSectionCoord(b.getZ())));
            } else {
                out.add(new ChunkPos(cx, cz));
            }
            angle += (Math.PI * 2) / spread;
            if (++inRing == spread) {
                ring++;
                inRing = 0;
                spread += 2 * spread / (ring + 1);
                spread = Math.min(spread, count - n);
                angle += rnd.nextDouble() * Math.PI * 2.0;
            }
        }
        return out;
    }

    /** Every vanilla biome tag file, as paths like "has_structure/village_plains". */
    private static List<String> biomeTagFiles() {
        List<String> out = new ArrayList<>();
        String root = "/data/minecraft/tags/worldgen/biome";
        try {
            var url = net.minecraft.client.Minecraft.class.getResource(root);
            if (url == null) return out;
            var uri = url.toURI();
            java.nio.file.Path base;
            if ("jar".equals(uri.getScheme())) {
                java.nio.file.FileSystem fs;
                try {
                    fs = java.nio.file.FileSystems.getFileSystem(uri);
                } catch (java.nio.file.FileSystemNotFoundException e) {
                    fs = java.nio.file.FileSystems.newFileSystem(uri, Map.of());
                }
                base = fs.getPath(root);
            } else {
                base = java.nio.file.Path.of(uri);
            }
            try (var files = java.nio.file.Files.walk(base)) {
                for (var p : (Iterable<java.nio.file.Path>) files.filter(f -> f.toString().endsWith(".json"))::iterator) {
                    String rel = base.relativize(p).toString().replace('\\', '/');
                    out.add(rel.substring(0, rel.length() - 5));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** The biomes in a vanilla biome tag, following tags it includes. */
    private static java.util.Set<net.minecraft.resources.Identifier> resolveTag(net.minecraft.resources.Identifier tag,
            Map<String, java.util.Set<net.minecraft.resources.Identifier>> cache, java.util.Set<String> visiting) {
        String path = tag.getNamespace() + ":" + tag.getPath();
        var cached = cache.get(path);
        if (cached != null) return cached;
        java.util.Set<net.minecraft.resources.Identifier> out = new java.util.LinkedHashSet<>();
        if (!visiting.add(path)) return out;
        String file = "/data/" + tag.getNamespace() + "/tags/worldgen/biome/" + tag.getPath() + ".json";
        try (var in = net.minecraft.client.Minecraft.class.getResourceAsStream(file)) {
            if (in != null) {
                var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                for (var v : json.getAsJsonArray("values")) {
                    String id = v.isJsonObject() ? v.getAsJsonObject().get("id").getAsString() : v.getAsString();
                    if (id.startsWith("#")) out.addAll(resolveTag(net.minecraft.resources.Identifier.parse(id.substring(1)), cache, visiting));
                    else out.add(net.minecraft.resources.Identifier.parse(id));
                }
            }
        } catch (Exception ignored) {
        }
        cache.put(path, out);
        return out;
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
                                    regions.put(key, compute(s, fx, fz));
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

    /**
     * Where the structure of this kind nearest to (x, z) starts, if one is predicted within {@code range} blocks
     * (null otherwise). Worked out on the predictor's own thread; regions it computes are kept for the map too.
     */
    public java.util.concurrent.CompletableFuture<Predicted> nearestStart(Kind kind, int x, int z, int range) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (!ready) return null;
            Predicted best = null;
            long bestD = (long) range * range;
            for (SetInfo s : sets) {
                if (s.rings() != null || !s.shows(Set.of(kind))) continue;
                int spacing = s.spread().spacing();
                int rx0 = Math.floorDiv((x - range) >> 4, spacing), rx1 = Math.floorDiv((x + range) >> 4, spacing);
                int rz0 = Math.floorDiv((z - range) >> 4, spacing), rz1 = Math.floorDiv((z + range) >> 4, spacing);
                for (int rx = rx0; rx <= rx1; rx++) {
                    for (int rz = rz0; rz <= rz1; rz++) {
                        long key = ((long) s.index() << 48) ^ ((long) (rx & 0xFFFFFF) << 24) ^ (rz & 0xFFFFFF);
                        Optional<Predicted> r = regions.get(key);
                        if (r == null) {
                            try {
                                r = compute(s, rx, rz);
                            } catch (Throwable t) {
                                r = Optional.empty();
                            }
                            regions.put(key, r);
                        }
                        if (r.isEmpty() || r.get().kind() != kind) continue;
                        long dx = r.get().x() - x, dz = r.get().z() - z, d = dx * dx + dz * dz;
                        if (d <= bestD) {
                            bestD = d;
                            best = r.get();
                        }
                    }
                }
            }
            return best;
        }, WORKER);
    }

    /** What, if anything, generates in this region of a random-spread structure set. */
    private Optional<Predicted> compute(SetInfo s, int rx, int rz) {
        RandomSpreadStructurePlacement spread = s.spread();
        ChunkPos chunk = spread.getPotentialStructureChunk(seed, rx * spread.spacing(), rz * spread.spacing());
        // the set's own rules: frequency, and pillager outposts keep 10 chunks away from village start chunks
        if (!spread.applyAdditionalChunkRestrictions(chunk.x, chunk.z, seed)) return Optional.empty();
        if (s.path().equals("pillager_outposts") && villages != null && villageNear(chunk.x, chunk.z, 10)) return Optional.empty();
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
                        heights, e::allows);
                Optional<Structure.GenerationStub> stub = structure.findValidGenerationPoint(ctx);
                if (stub.isEmpty()) return Optional.empty();
                BlockPos p = stub.get().position();
                return e.kind() == null ? Optional.empty() : Optional.of(new Predicted(e.kind(), p.getX(), p.getZ()));
            } catch (Throwable ignored) {
                // needs something only a server has (structure templates): fall back to the biome check below
            }
        }
        // jigsaw structures (villages, outposts, trail ruins...): the biome is checked at the middle of the start piece
        if (structure instanceof net.minecraft.world.level.levelgen.structure.structures.JigsawStructure) {
            try {
                BlockPos start = jigsawStart(structure, chunk);
                if (start != null) {
                    Holder<Biome> biome = biomes.getNoiseBiome(QuartPos.fromBlock(start.getX()), QuartPos.fromBlock(start.getY()),
                            QuartPos.fromBlock(start.getZ()), random.sampler());
                    if (!e.allows(biome) || e.kind() == null) return Optional.empty();
                    return Optional.of(new Predicted(e.kind(), start.getX(), start.getZ()));
                }
            } catch (Throwable ignored) {
                // fall through to the simpler check
            }
        }
        int x = chunk.getMinBlockX() + 8, z = chunk.getMinBlockZ() + 8;
        int y;
        if (e.path().equals("ancient_city")) y = -27;
        else if (e.path().equals("trial_chambers")) y = -30;
        else if (dimension == Level.NETHER) y = 64;
        else y = generator.getFirstOccupiedHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, heights, random);
        Holder<Biome> biome = biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), random.sampler());
        if (!e.allows(biome) || e.kind() == null) return Optional.empty();
        return Optional.of(new Predicted(e.kind(), x, z));
    }
}
