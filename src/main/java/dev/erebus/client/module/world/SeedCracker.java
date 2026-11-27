package dev.erebus.client.module.world;

import dev.erebus.client.mixin.BiomeManagerAccessor;
import dev.erebus.client.module.Category;
import dev.erebus.client.module.Module;
import dev.erebus.client.notify.Notifications;
import dev.erebus.client.seed.SeedData;
import dev.erebus.client.seed.SeedSolver;
import dev.erebus.client.seed.StructureScanner;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.util.Chat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Works out the world seed from structures you come across. Proof of concept for servers with vanilla world
 * generation settings.
 *
 * <p>It watches loaded overworld chunks for desert pyramids, jungle temples, swamp huts, igloos, villages and buried
 * treasure, keeps every one it finds per server, and once there are enough, recovers the low 48 bits of the seed
 * from where they generated and the top 16 bits from the hashed seed the server sends. See {@link SeedSolver} for
 * the maths. It only reads chunks the server already sent; nothing is sent to the server.
 */
public final class SeedCracker extends Module {
    private static SeedCracker instance;

    private final BoolSetting pyramids;
    private final BoolSetting jungleTemples;
    private final BoolSetting swampHuts;
    private final BoolSetting treasure;
    private final BoolSetting igloos;
    private final BoolSetting villages;
    private final BoolSetting autoCrack;
    private final BoolSetting announce;
    private final BoolSetting tolerate;
    private final dev.erebus.client.setting.TextSetting knownSeed;

    private SeedData data;
    private final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
    private volatile boolean cracking;
    private final AtomicLong progress = new AtomicLong();
    private int attemptedWith = -1;

    public SeedCracker() {
        super("Seed Cracker", "Works out the world seed from desert pyramids, jungle temples, swamp huts, igloos, villages and buried "
                + "treasure you come across. Proof of concept: needs a server with vanilla world generation settings.", Category.WORLD);
        group("Collect");
        pyramids = add(new BoolSetting("Desert pyramids", "Record desert pyramids.", true));
        jungleTemples = add(new BoolSetting("Jungle temples", "Record jungle temples.", true));
        swampHuts = add(new BoolSetting("Swamp huts", "Record swamp huts.", true));
        igloos = add(new BoolSetting("Igloos", "Record igloos (matched against the game's igloo template, at the spot its start chunk puts it).", true));
        villages = add(new BoolSetting("Villages", "Record villages, from their meeting point (bell, well or fountain), matched against the "
                + "game's templates at the spot its start chunk puts it. Each says less than a temple, so it takes more of them, but "
                + "they're common.", true));
        treasure = add(new BoolSetting("Buried treasure", "Record buried treasure chests (a chest at the same spot in a chunk, under sand). "
                + "Speeds up cracking; ignored automatically if one turns out to be a player's chest.", true));
        group("Cracking");
        autoCrack = add(new BoolSetting("Crack automatically", "Start cracking as soon as there are enough structures.", true));
        announce = add(new BoolSetting("Chat messages", "Say in chat when a structure is recorded and when the seed is found.", true));
        tolerate = add(new BoolSetting("Allow misreads", "If no seed fits every structure, look for one that fits all but up to 3 of them, "
                + "checked against the server's hashed seed, and ignore the ones that don't fit. Needs a few more structures than "
                + "an exact fit.", true));
        knownSeed = add(new dev.erebus.client.setting.TextSetting("Known seed", "If you already know this server's seed, enter it here: "
                + "it's used for predicted structures on the map instead of cracking. Leave empty to crack it.", "", 24));
        instance = this;
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            SeedCracker m = instance;
            if (m != null && m.isEnabled() && level.dimension() == Level.OVERWORLD) m.queue.add(chunk.getPos());
        });
    }

    /**
     * The world seed for where you are, if known: the real one in singleplayer, the Known seed setting, or the one
     * cracked for this server. Null otherwise.
     */
    public static Long knownSeed() {
        try {
            if (mc.getSingleplayerServer() != null) return mc.getSingleplayerServer().getWorldData().worldGenOptions().seed();
        } catch (Throwable ignored) {
        }
        SeedCracker m = instance;
        if (m != null) {
            String t = m.knownSeed.get().trim();
            if (!t.isEmpty()) {
                try {
                    return Long.parseLong(t);
                } catch (NumberFormatException e) {
                    return (long) t.hashCode(); // a text seed, the way the game turns it into a number
                }
            }
        }
        String key = SeedData.serverKey();
        if (m != null && m.data != null && key.equals(m.data.key())) return m.data.worldSeed;
        if (cachedKey == null || !cachedKey.equals(key)) {
            cachedKey = key;
            cachedSeed = SeedData.load(key).worldSeed;
        }
        return cachedSeed;
    }

    private static String cachedKey;
    private static Long cachedSeed;

    @Override
    public List<Action> actions() {
        return List.of(
                new Action("Crack now", () -> crack(true)),
                new Action("Copy seed", this::copySeed),
                new Action("Forget this server", this::forget));
    }

    @Override
    protected void onEnable() {
        data = null; // reloaded (and loaded chunks queued) on the next tick
    }

    @Override
    protected void onDisable() {
        clearQueues();
        info = null;
    }

    private void clearQueues() {
        queue.clear();
        templateQueue.clear();
        templateWaiting.clear();
        templateQueued.clear();
    }

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) return;
        String key = SeedData.serverKey();
        if (data == null || !key.equals(data.key())) {
            data = SeedData.load(key);
            attemptedWith = -1;
            clearQueues();
            if (mc.level.dimension() == Level.OVERWORLD) queueLoaded();
        }
        long hashed = ((BiomeManagerAccessor) mc.level.getBiomeManager()).erebus$hashedSeed();
        if (hashed != data.hashedSeed) {
            data.hashedSeed = hashed;
            data.save();
        }
        if (mc.level.dimension() != Level.OVERWORLD && (!queue.isEmpty() || !templateQueued.isEmpty())) clearQueues();
        Set<StructureScanner.Kind> kinds = kinds();
        for (int i = 0; i < 8 && !queue.isEmpty(); i++) {
            ChunkPos p = queue.poll();
            LevelChunk chunk = mc.level.getChunkSource().getChunk(p.x, p.z, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            for (StructureScanner.Found f : StructureScanner.scan(chunk, kinds)) record(f);
            for (StructureScanner.Kind k : TEMPLATE_KINDS) {
                if (kinds.contains(k) && dev.erebus.client.seed.TemplateStarts.worthChecking(chunk, k)) {
                    for (int[] c : dev.erebus.client.seed.TemplateStarts.candidates(p.x, p.z)) {
                        if (templateQueued.add(k.name() + c[0] + "," + c[1])) templateQueue.add(new Object[]{k, c[0], c[1]});
                    }
                }
            }
        }
        checkTemplateStarts();
        if (autoCrack.get() && !cracking && data.worldSeed == null && knownSeed.get().isBlank() && data.structures.size() != attemptedWith
                && SeedSolver.notEnough(data.clues(true)) == null) {
            crack(false);
        }
        info = status();
    }

    private static final StructureScanner.Kind[] TEMPLATE_KINDS = {StructureScanner.Kind.IGLOO, StructureScanner.Kind.VILLAGE};
    /** Possible igloo or village start chunks to check: {kind, chunkX, chunkZ}. */
    private final ArrayDeque<Object[]> templateQueue = new ArrayDeque<>();
    /** What's in {@link #templateQueue} or waiting, so each possible start chunk is queued once. */
    private final Set<String> templateQueued = new java.util.HashSet<>();
    /** When each possible start chunk was last checked without a match (not checked again for 30 seconds). */
    private final java.util.Map<String, Long> templateTried = new java.util.HashMap<>();

    /** Possible start chunks whose surroundings weren't all loaded yet: looked at again every 2 seconds while they're in range. */
    private final List<Object[]> templateWaiting = new ArrayList<>();
    private int waitTicks;

    /** Checks a few possible igloo and village start chunks against their templates (a few per tick, it's slower). */
    private void checkTemplateStarts() {
        if (++waitTicks >= 40) {
            waitTicks = 0;
            templateQueue.addAll(templateWaiting);
            templateWaiting.clear();
        }
        long now = System.currentTimeMillis();
        int budget = 12, looked = 0;
        while (budget > 0 && looked++ < 256 && !templateQueue.isEmpty()) {
            Object[] q = templateQueue.poll();
            StructureScanner.Kind k = (StructureScanner.Kind) q[0];
            int cx = (int) q[1], cz = (int) q[2];
            String key = k.name() + cx + "," + cz;
            templateQueued.remove(key);
            if (data.has(k, cx, cz)) continue;
            Long last = templateTried.get(key);
            if (last != null && now - last < 30_000) continue;
            if (!dev.erebus.client.seed.TemplateStarts.areaLoaded(mc.level, cx, cz)) {
                // the rest of it hasn't arrived yet (new terrain can take a while): wait while it's within render distance
                ChunkPos me = mc.player.chunkPosition();
                int r = mc.options.getEffectiveRenderDistance() + 1;
                if (Math.abs(cx - me.x) <= r && Math.abs(cz - me.z) <= r && templateQueued.add(key)) templateWaiting.add(q);
                continue;
            }
            budget--;
            templateTried.put(key, now);
            if (templateTried.size() > 20_000) templateTried.clear();
            var at = dev.erebus.client.seed.TemplateStarts.startsIn(mc.level, k, cx, cz);
            if (at != null) record(new StructureScanner.Found(k, cx, cz, at));
        }
        if (templateQueue.size() > 50_000 || templateWaiting.size() > 5_000) {
            templateQueue.clear();
            templateWaiting.clear();
            templateQueued.clear();
        }
    }

    private Set<StructureScanner.Kind> kinds() {
        Set<StructureScanner.Kind> k = EnumSet.noneOf(StructureScanner.Kind.class);
        if (pyramids.get()) k.add(StructureScanner.Kind.DESERT_PYRAMID);
        if (jungleTemples.get()) k.add(StructureScanner.Kind.JUNGLE_TEMPLE);
        if (swampHuts.get()) k.add(StructureScanner.Kind.SWAMP_HUT);
        if (treasure.get()) k.add(StructureScanner.Kind.BURIED_TREASURE);
        if (igloos.get()) k.add(StructureScanner.Kind.IGLOO);
        if (villages.get()) k.add(StructureScanner.Kind.VILLAGE);
        return k;
    }

    private void queueLoaded() {
        int r = mc.options.getEffectiveRenderDistance() + 1;
        ChunkPos c = mc.player.chunkPosition();
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) queue.add(new ChunkPos(c.x + x, c.z + z));
        }
    }

    private void record(StructureScanner.Found f) {
        if (data.has(f.kind(), f.chunkX(), f.chunkZ())) return;
        var m = f.marker();
        boolean misfit = data.worldSeed != null && !SeedSolver.fits(data.worldSeed, List.of(f.kind().clue(f.chunkX(), f.chunkZ())));
        data.structures.add(new SeedData.Entry(f.kind().name(), f.chunkX(), f.chunkZ(), m.getX(), m.getY(), m.getZ(), misfit));
        data.save();
        if (misfit) {
            if (announce.get()) chat(f.kind().label + " at " + m.getX() + ", " + m.getZ() + " doesn't fit the seed found, so it was probably "
                    + "misread; ignored.");
            return;
        }
        int spreads = (int) data.structures.stream().filter(e -> !e.rejected() && !e.kind().equals(StructureScanner.Kind.BURIED_TREASURE.name())).count();
        String detail = f.kind().label + " at " + m.getX() + ", " + m.getZ() + " (" + spreads + " structures, "
                + (data.counted() - spreads) + " treasures)";
        Notifications.push(name, detail, Notifications.Kind.INFO);
        if (announce.get()) chat("Recorded " + detail + ".");
    }

    private String status() {
        if (data == null) return null;
        if (data.worldSeed != null) return "seed " + data.worldSeed;
        if (cracking) return "cracking " + progress.get() / 10_000 + "%";
        if (data.structureSeed != null) return "structure seed " + data.structureSeed;
        return data.structures.size() + " structures";
    }

    @Override
    public String suffix() {
        if (data == null) return null;
        if (data.worldSeed != null) return "found";
        if (cracking) return progress.get() / 10_000 + "%";
        return String.valueOf(data.structures.size());
    }

    // =====================================================================================================
    // Cracking (on a background thread)
    // =====================================================================================================

    private void crack(boolean byHand) {
        if (data == null || cracking) return;
        String why = SeedSolver.notEnough(data.clues(true));
        if (why != null) {
            if (byHand) chat("Not enough structures yet: " + why + ".");
            return;
        }
        attemptedWith = data.structures.size();
        cracking = true;
        progress.set(0);
        SeedData d = data;
        List<SeedSolver.Clue> all = d.clues(true);
        long hashed = d.hashedSeed;
        if (announce.get() || byHand) chat("Cracking with " + d.structures.size() + " structures...");
        boolean allowMisreads = tolerate.get();
        Thread t = new Thread(() -> {
            String message;
            Long world = null, structure = null;
            List<SeedSolver.Clue> misfits = List.of();
            try {
                int spreads = (int) all.stream().filter(c -> c instanceof SeedSolver.Spread).count();
                // misreads are only allowed with the hashed seed to confirm the result, and enough structures left over
                int maxMisses = allowMisreads && hashed != 0 ? Math.min(3, Math.max(0, spreads - 4)) : 0;
                message = "No seed fits all " + all.size() + " structures. One may have been misread (turn on Allow misreads and find a "
                        + "few more), or this server changed its structure settings.";
                for (int k = 0; k <= maxMisses; k++) {
                    List<SeedSolver.Candidate> found = SeedSolver.structureSeeds(all, 50_000, progress, k);
                    if (found.isEmpty()) continue;
                    if (hashed == 0) {
                        if (found.size() == 1) structure = found.get(0).seed();
                        message = "The server doesn't send a hashed seed, so only the structure seed (low 48 bits) can be found: "
                                + (found.size() == 1 ? found.get(0).seed() : found.size() + " candidates left, find more structures") + ".";
                        break;
                    }
                    if (found.size() >= 50_000) {
                        message = "Too many possible seeds still; find a few more structures.";
                        break;
                    }
                    SeedSolver.Candidate w = SeedSolver.firstWorldSeed(found, hashed, BiomeManager::obfuscateSeed);
                    if (w != null) {
                        world = w.seed();
                        structure = world & ((1L << 48) - 1);
                        misfits = SeedSolver.misfits(world, all);
                        message = null;
                        break;
                    }
                    message = found.size() + " structure seeds fit, but none matches the server's hashed seed. Find a few more structures.";
                }
            } catch (Throwable e) {
                message = "Cracking failed: " + e;
            }
            String msg = message;
            Long w = world, s = structure;
            List<SeedSolver.Clue> bad = misfits;
            mc.execute(() -> finish(d, w, s, bad, msg, byHand));
        }, "Erebus seed cracker");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void finish(SeedData d, Long world, Long structure, List<SeedSolver.Clue> misfits, String message, boolean byHand) {
        cracking = false;
        if (structure != null) d.structureSeed = structure;
        if (world != null) {
            // set aside the structures that don't fit, then check the rest with the game's own placement code
            List<String> ignored = new java.util.ArrayList<>();
            for (int i = 0; i < d.structures.size(); i++) {
                SeedData.Entry e = d.structures.get(i);
                StructureScanner.Kind k = e.kindOrNull();
                if (k == null || e.rejected()) continue;
                if (!SeedSolver.fits(world, List.of(k.clue(e.chunkX(), e.chunkZ())))) {
                    d.structures.set(i, e.withRejected(true));
                    ignored.add(k.label + " at " + e.x() + ", " + e.z());
                }
            }
            if (ignored.size() == misfits.size() && verified(d, world)) {
                d.worldSeed = world;
                d.save();
                Notifications.push(name, "Seed found: " + world, Notifications.Kind.SUCCESS);
                seedMessage(world, (int) d.counted());
                if (!ignored.isEmpty()) chat("Ignored " + (ignored.size() == 1 ? "1 structure that doesn't" : ignored.size() + " structures that don't")
                        + " fit the seed (probably misread): " + String.join("; ", ignored) + ".");
                return;
            }
            for (int i = 0; i < d.structures.size(); i++) d.structures.set(i, d.structures.get(i).withRejected(false));
        }
        if (world != null) message = "Found " + world + " but it doesn't reproduce every structure; ignoring it.";
        d.save();
        if (message != null && (announce.get() || byHand)) chat(message);
    }

    /** Checks the seed with the game's own structure placement code. */
    private static boolean verified(SeedData d, long seed) {
        for (SeedData.Entry e : d.structures) {
            StructureScanner.Kind k = e.kindOrNull();
            if (k == null || e.rejected() || k == StructureScanner.Kind.BURIED_TREASURE) continue;
            var placement = new RandomSpreadStructurePlacement(k.spacing, k.separation, RandomSpreadType.LINEAR, k.salt);
            ChunkPos p = placement.getPotentialStructureChunk(seed, e.chunkX(), e.chunkZ());
            if (p.x != e.chunkX() || p.z != e.chunkZ()) return false;
        }
        return true;
    }

    private void seedMessage(long seed, int count) {
        if (mc.gui == null) return;
        Component seedText = Component.literal(String.valueOf(seed)).withStyle(s -> s.withColor(TextColor.fromRgb(0x7CE0B0)).withUnderlined(true)
                .withClickEvent(new ClickEvent.CopyToClipboard(String.valueOf(seed)))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy"))));
        Chat.info("Seed found from " + count + " structures and the server's hashed seed:");
        mc.gui.getChat().addMessage(Component.literal("  ").append(seedText));
    }

    private void copySeed() {
        if (data == null || (data.worldSeed == null && data.structureSeed == null)) {
            chat("No seed found yet.");
            return;
        }
        long s = data.worldSeed != null ? data.worldSeed : data.structureSeed;
        mc.keyboardHandler.setClipboard(String.valueOf(s));
        chat("Copied " + (data.worldSeed != null ? "the seed" : "the structure seed") + " " + s + ".");
    }

    private void forget() {
        if (data == null) return;
        data.structures = new ArrayList<>(); // everything, including the ones set aside as misread
        data.structureSeed = null;
        data.worldSeed = null;
        attemptedWith = -1;
        data.save();
        chat("Forgot everything recorded for " + data.key() + ".");
    }
}
