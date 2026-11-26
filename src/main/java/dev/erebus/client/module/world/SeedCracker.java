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
 * <p>It watches loaded overworld chunks for desert pyramids, jungle temples, swamp huts and buried treasure, keeps
 * every one it finds per server, and once there are enough, recovers the low 48 bits of the seed from where they
 * generated and the top 16 bits from the hashed seed the server sends. See {@link SeedSolver} for the maths. It
 * only reads chunks the server already sent; nothing is sent to the server.
 */
public final class SeedCracker extends Module {
    private static SeedCracker instance;

    private final BoolSetting pyramids;
    private final BoolSetting jungleTemples;
    private final BoolSetting swampHuts;
    private final BoolSetting treasure;
    private final BoolSetting autoCrack;
    private final BoolSetting announce;

    private SeedData data;
    private final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
    private volatile boolean cracking;
    private final AtomicLong progress = new AtomicLong();
    private int attemptedWith = -1;

    public SeedCracker() {
        super("Seed Cracker", "Works out the world seed from desert pyramids, jungle temples, swamp huts and buried treasure you come "
                + "across. Proof of concept: needs a server with vanilla world generation settings.", Category.WORLD);
        group("Collect");
        pyramids = add(new BoolSetting("Desert pyramids", "Record desert pyramids.", true));
        jungleTemples = add(new BoolSetting("Jungle temples", "Record jungle temples.", true));
        swampHuts = add(new BoolSetting("Swamp huts", "Record swamp huts.", true));
        treasure = add(new BoolSetting("Buried treasure", "Record buried treasure chests (a chest at the same spot in a chunk, under sand). "
                + "Speeds up cracking; ignored automatically if one turns out to be a player's chest.", true));
        group("Cracking");
        autoCrack = add(new BoolSetting("Crack automatically", "Start cracking as soon as there are enough structures.", true));
        announce = add(new BoolSetting("Chat messages", "Say in chat when a structure is recorded and when the seed is found.", true));
        instance = this;
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            SeedCracker m = instance;
            if (m != null && m.isEnabled() && level.dimension() == Level.OVERWORLD) m.queue.add(chunk.getPos());
        });
    }

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
        queue.clear();
        info = null;
    }

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) return;
        String key = SeedData.serverKey();
        if (data == null || !key.equals(data.key())) {
            data = SeedData.load(key);
            attemptedWith = -1;
            queue.clear();
            if (mc.level.dimension() == Level.OVERWORLD) queueLoaded();
        }
        long hashed = ((BiomeManagerAccessor) mc.level.getBiomeManager()).erebus$hashedSeed();
        if (hashed != data.hashedSeed) {
            data.hashedSeed = hashed;
            data.save();
        }
        if (mc.level.dimension() != Level.OVERWORLD) queue.clear();
        Set<StructureScanner.Kind> kinds = kinds();
        for (int i = 0; i < 8 && !queue.isEmpty(); i++) {
            ChunkPos p = queue.poll();
            LevelChunk chunk = mc.level.getChunkSource().getChunk(p.x, p.z, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            for (StructureScanner.Found f : StructureScanner.scan(chunk, kinds)) record(f);
        }
        if (autoCrack.get() && !cracking && data.worldSeed == null && data.structures.size() != attemptedWith
                && SeedSolver.notEnough(data.clues(true)) == null) {
            crack(false);
        }
        info = status();
    }

    private Set<StructureScanner.Kind> kinds() {
        Set<StructureScanner.Kind> k = EnumSet.noneOf(StructureScanner.Kind.class);
        if (pyramids.get()) k.add(StructureScanner.Kind.DESERT_PYRAMID);
        if (jungleTemples.get()) k.add(StructureScanner.Kind.JUNGLE_TEMPLE);
        if (swampHuts.get()) k.add(StructureScanner.Kind.SWAMP_HUT);
        if (treasure.get()) k.add(StructureScanner.Kind.BURIED_TREASURE);
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
        data.structures.add(new SeedData.Entry(f.kind().name(), f.chunkX(), f.chunkZ(), m.getX(), m.getY(), m.getZ()));
        data.save();
        int spreads = (int) data.structures.stream().filter(e -> !e.kind().equals(StructureScanner.Kind.BURIED_TREASURE.name())).count();
        String detail = f.kind().label + " at " + m.getX() + ", " + m.getZ() + " (" + spreads + " temples/huts, "
                + (data.structures.size() - spreads) + " treasures)";
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
        List<SeedSolver.Clue> noChance = d.clues(false);
        long hashed = d.hashedSeed;
        if (announce.get() || byHand) chat("Cracking with " + d.structures.size() + " structures...");
        Thread t = new Thread(() -> {
            String message;
            Long world = null, structure = null;
            try {
                List<Long> seeds = SeedSolver.structureSeeds(all, 4096, progress);
                boolean droppedTreasure = false;
                if (seeds.isEmpty() && noChance.size() < all.size() && SeedSolver.notEnough(noChance) == null) {
                    seeds = SeedSolver.structureSeeds(noChance, 4096, progress); // a "treasure" may have been a player's chest
                    droppedTreasure = true;
                }
                if (seeds.isEmpty()) {
                    message = "No seed fits all " + all.size() + " structures. One was probably misread, or this server changed its "
                            + "structure settings.";
                } else if (seeds.size() >= 4096) {
                    message = "Too many possible seeds still; find a few more structures.";
                } else if (hashed == 0) {
                    if (seeds.size() == 1) structure = seeds.get(0);
                    message = "The server doesn't send a hashed seed, so only the structure seed (low 48 bits) can be found: "
                            + (seeds.size() == 1 ? seeds.get(0) : seeds.size() + " candidates left") + ".";
                } else {
                    List<Long> worlds = SeedSolver.worldSeeds(seeds, hashed, BiomeManager::obfuscateSeed);
                    if (worlds.size() == 1) {
                        world = worlds.get(0);
                        structure = world & ((1L << 48) - 1);
                        message = null;
                    } else if (worlds.isEmpty()) {
                        message = seeds.size() + " structure seeds fit, but none matches the server's hashed seed. Find a few more structures.";
                    } else {
                        message = worlds.size() + " seeds fit; find another structure to tell them apart.";
                    }
                }
                if (droppedTreasure && (world != null || structure != null)) {
                    message = (message == null ? "" : message + " ") + "(Ignored the buried treasure: one of them wasn't real.)";
                }
            } catch (Throwable e) {
                message = "Cracking failed: " + e;
            }
            String msg = message;
            Long w = world, s = structure;
            mc.execute(() -> finish(d, w, s, msg, byHand));
        }, "Erebus seed cracker");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void finish(SeedData d, Long world, Long structure, String message, boolean byHand) {
        cracking = false;
        if (structure != null) d.structureSeed = structure;
        if (world != null && verified(d, world)) {
            d.worldSeed = world;
            d.save();
            Notifications.push(name, "Seed found: " + world, Notifications.Kind.SUCCESS);
            seedMessage(world, d.structures.size());
            return;
        }
        if (world != null) message = "Found " + world + " but it doesn't reproduce every structure; ignoring it.";
        d.save();
        if (message != null && (announce.get() || byHand)) chat(message);
    }

    /** Checks the seed with the game's own structure placement code. */
    private static boolean verified(SeedData d, long seed) {
        for (SeedData.Entry e : d.structures) {
            StructureScanner.Kind k;
            try {
                k = StructureScanner.Kind.valueOf(e.kind());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (k == StructureScanner.Kind.BURIED_TREASURE) continue;
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
        data.structures = new ArrayList<>();
        data.structureSeed = null;
        data.worldSeed = null;
        attemptedWith = -1;
        data.save();
        chat("Forgot everything recorded for " + data.key() + ".");
    }
}
