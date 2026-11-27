package dev.erebus.client.module.world;

import dev.erebus.client.mixin.BiomeManagerAccessor;
import dev.erebus.client.module.Category;
import dev.erebus.client.module.Module;
import dev.erebus.client.notify.Notifications;
import dev.erebus.client.seed.SaltSolver;
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
 * <p>It watches loaded overworld chunks for desert pyramids, jungle temples, swamp huts, igloos, villages, pillager
 * outposts, mineshafts and buried treasure, reads the End's pillars, keeps everything it finds per server, and once there's
 * enough, recovers the low 48 bits of the seed from them and the top 16 bits from the hashed seed the server sends. See {@link SeedSolver} for
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
    private final BoolSetting outposts;
    private final BoolSetting mineshafts;
    private final BoolSetting endPillars;
    private final BoolSetting autoCrack;
    private final BoolSetting announce;
    private final BoolSetting tolerate;
    private final BoolSetting customSalts;
    private final dev.erebus.client.setting.TextSetting knownSeed;

    private SeedData data;
    private final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
    private volatile boolean cracking;
    private final AtomicLong progress = new AtomicLong();
    private int attemptedWith = -1;

    public SeedCracker() {
        super("Seed Cracker", "Works out the world seed from desert pyramids, jungle temples, swamp huts, igloos, villages, pillager "
                + "outposts, mineshafts, buried treasure and the End's pillars you come across, also on Spigot and Paper servers that set their "
                + "own structure salts. Needs terrain from Minecraft's own world generator.", Category.WORLD);
        group("Collect");
        pyramids = add(new BoolSetting("Desert pyramids", "Record desert pyramids.", true));
        jungleTemples = add(new BoolSetting("Jungle temples", "Record jungle temples.", true));
        swampHuts = add(new BoolSetting("Swamp huts", "Record swamp huts.", true));
        igloos = add(new BoolSetting("Igloos", "Record igloos (matched against the game's igloo template, at the spot its start chunk puts it).", true));
        villages = add(new BoolSetting("Villages", "Record villages, from their meeting point (bell, well or fountain), matched against the "
                + "game's templates at the spot its start chunk puts it. Each says less than a temple, so it takes more of them, but "
                + "they're common.", true));
        outposts = add(new BoolSetting("Pillager outposts", "Record pillager outposts, from their watchtower, matched against the game's "
                + "template at the spot its start chunk puts it. Says as much as a temple, plus a little from the outpost's extra 1-in-5 roll.",
                true));
        mineshafts = add(new BoolSetting("Mineshafts", "Record mineshafts, from their start room: the game puts its corner exactly 2 "
                + "blocks into the start chunk. Read from the chunks the server sends, so you don't need to go underground. Each one is "
                + "worth about as much as a village, and they're everywhere (and follow the same rule in chunks from 1.12 on).", true));
        endPillars = add(new BoolSetting("End pillars", "In the End, read the obsidian pillars around the main island: the game shuffles their "
                + "heights with a number from the seed. Uses the bedrock each one was topped with (it stays even when the obsidian is "
                + "mined) or, failing that, its width under the island. Together they're worth about two temples.", true));
        treasure = add(new BoolSetting("Buried treasure", "Record buried treasure chests (a chest at the same spot in a chunk, under sand). "
                + "Speeds up cracking; ignored automatically if one turns out to be a player's chest.", true));
        group("Cracking");
        autoCrack = add(new BoolSetting("Crack automatically", "Start cracking as soon as there are enough structures.", true));
        announce = add(new BoolSetting("Chat messages", "Say in chat when a structure is recorded and when the seed is found.", true));
        tolerate = add(new BoolSetting("Allow misreads", "If no seed fits every structure, look for one that fits all but up to 3 of them, "
                + "checked against the server's hashed seed, and ignore the ones that don't fit. Needs a few more structures than "
                + "an exact fit.", true));
        customSalts = add(new BoolSetting("Custom structure salts", "If no seed fits with the game's own structure settings, try "
                + "again assuming the server gave each kind of structure its own salt (Spigot's and Paper's seed-village, seed-desert... "
                + "settings, a common way to protect a seed). That needs the End pillars, plus mineshafts, villages, igloos or outposts, "
                + "which are checked against the seed itself. Each kind's salt is worked out too, so predicted structures stay right.", true));
        knownSeed = add(new dev.erebus.client.setting.TextSetting("Known seed", "If you already know this server's seed, enter it here: "
                + "it's used for predicted structures on the map instead of cracking. Leave empty to crack it.", "", 24));
        instance = this;
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            SeedCracker m = instance;
            if (m != null && m.isEnabled() && level.dimension() == Level.OVERWORLD) m.queue.add(chunk.getPos());
        });
        addVisuals("Outlines the start chunk of each structure it recorded nearby, with what it is, and labels each End pillar with what it read.");
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
            cachedData = SeedData.load(key);
            cachedSeed = cachedData.worldSeed;
        }
        return cachedSeed;
    }

    private static String cachedKey;
    private static Long cachedSeed;
    private static SeedData cachedData;

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
        if (hashed != data.hashedSeed && mc.level.dimension() == Level.OVERWORLD) { // the End or Nether may have their own seed on some servers
            data.hashedSeed = hashed;
            data.save();
        }
        if (mc.level.dimension() != Level.OVERWORLD && (!queue.isEmpty() || !templateQueued.isEmpty())) clearQueues();
        if (mc.level.dimension() == Level.END && endPillars.get() && data.worldSeed == null && ++pillarTicks >= 40) {
            pillarTicks = 0;
            readPillars();
        }
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
        if (autoCrack.get() && !cracking && data.worldSeed == null && knownSeed.get().isBlank() && attemptKey() != attemptedWith
                && (data.salted() && customSalts.get() ? saltedReady() : SeedSolver.notEnough(data.clues(true)) == null)) {
            crack(false);
        }
        info = status();
    }

    private int saltCheckedWith = -1;
    private boolean saltReady;

    /** Whether there's enough to try with each kind of structure on its own salt (worked out again only when something new is recorded). */
    private boolean saltedReady() {
        int key = attemptKey();
        if (key != saltCheckedWith) {
            saltCheckedWith = key;
            saltReady = SaltSolver.notEnough(data.saltAnchors(), data.saltGroups()) == null;
        }
        return saltReady;
    }

    private static final StructureScanner.Kind[] TEMPLATE_KINDS = {StructureScanner.Kind.IGLOO, StructureScanner.Kind.VILLAGE,
            StructureScanner.Kind.OUTPOST, StructureScanner.Kind.MINESHAFT};
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
            var at = dev.erebus.client.seed.TemplateStarts.startPiece(mc.level, k, cx, cz);
            if (at != null) record(new StructureScanner.Found(k, cx, cz, at.pos(), at.template(), at.rotation()));
        }
        if (templateQueue.size() > 50_000 || templateWaiting.size() > 5_000) {
            templateQueue.clear();
            templateWaiting.clear();
            templateQueued.clear();
        }
    }

    // =====================================================================================================
    // Better visuals
    // =====================================================================================================

    private static int kindColor(StructureScanner.Kind k) {
        return switch (k) {
            case DESERT_PYRAMID -> 0xFFFDE68A;
            case JUNGLE_TEMPLE -> 0xFF84CC16;
            case SWAMP_HUT -> 0xFF4ADE80;
            case IGLOO -> 0xFFBAE6FD;
            case VILLAGE -> 0xFFA3E635;
            case OUTPOST -> 0xFFCBD5E1;
            case MINESHAFT -> 0xFFD6A35C;
            case BURIED_TREASURE -> 0xFFFACC15;
        };
    }

    /** End pillar i's centre, as the game places them (42 blocks out, ten around the island). */
    private static int[] pillarAt(int i) {
        double angle = 2.0 * (-Math.PI + 0.3141592653589793 * i);
        return new int[]{net.minecraft.util.Mth.floor(42.0 * Math.cos(angle)), net.minecraft.util.Mth.floor(42.0 * Math.sin(angle))};
    }

    @Override
    public void onRenderWorld(dev.erebus.client.module.WorldRender ctx) {
        if (!visuals() || data == null || mc.player == null || mc.level.dimension() != Level.OVERWORLD) return;
        net.minecraft.world.phys.Vec3 me = mc.player.position();
        for (SeedData.Entry e : data.structures) {
            StructureScanner.Kind k = e.kindOrNull();
            if (k == null) continue;
            double x0 = e.chunkX() << 4, z0 = e.chunkZ() << 4, y = e.y() + 0.05;
            if (me.distanceToSqr(x0 + 8, me.y, z0 + 8) > 256 * 256) continue;
            int c = e.rejected() ? 0xFFF87171 : kindColor(k);
            // the start chunk's outline at the structure's height, with posts at its corners
            net.minecraft.world.phys.Vec3[] corners = {new net.minecraft.world.phys.Vec3(x0, y, z0), new net.minecraft.world.phys.Vec3(x0 + 16, y, z0),
                    new net.minecraft.world.phys.Vec3(x0 + 16, y, z0 + 16), new net.minecraft.world.phys.Vec3(x0, y, z0 + 16)};
            for (int i = 0; i < 4; i++) {
                net.minecraft.gizmos.Gizmos.line(corners[i], corners[(i + 1) % 4], c, 2f);
                net.minecraft.gizmos.Gizmos.line(corners[i], corners[i].add(0, 3, 0), dev.erebus.client.render.Visuals.fade(c, 0.6f), 2f);
            }
            dev.erebus.client.render.Visuals.ripple(new net.minecraft.world.phys.Vec3(x0 + 8, y, z0 + 8), 7, c, 1.5f, 3.0, (e.chunkX() + e.chunkZ()) * 0.17);
        }
    }

    @Override
    public void onRenderOverlay(dev.erebus.client.gui.Ui ui) {
        if (!visuals() || data == null || mc.player == null || mc.options.hideGui) return;
        net.minecraft.world.phys.Vec3 me = mc.player.position();
        if (mc.level.dimension() == Level.END && data.pillars != null && data.pillars.length == 10) {
            for (int i = 0; i < 10; i++) { // what was read off each pillar
                int v = data.pillars[i];
                if (v < 0) continue;
                int[] p = pillarAt(i);
                double top = v < 10 ? 76 + 3 * v + 2.5 : 70;
                String what = v < 10 ? "slot " + v : "slots " + 3 * (v - 10) + "-" + Math.min(9, 3 * (v - 10) + 2);
                dev.erebus.client.render.Visuals.label(ui, new net.minecraft.world.phys.Vec3(p[0] + 0.5, top, p[1] + 0.5), dev.erebus.client.render.font.Icon.INFO,
                        "Pillar " + (i + 1), what + (v < 10 ? " (bedrock)" : " (width)"), 0xFFC4B5FD);
            }
            return;
        }
        if (mc.level.dimension() != Level.OVERWORLD) return;
        for (SeedData.Entry e : data.structures) {
            StructureScanner.Kind k = e.kindOrNull();
            if (k == null) continue;
            net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3((e.chunkX() << 4) + 8, e.y() + 4, (e.chunkZ() << 4) + 8);
            if (me.distanceTo(at) > 128) continue;
            String detail = e.rejected() ? "doesn't fit the seed" : e.piece() != null && !e.piece().contains("-")
                    ? e.piece().replaceAll(".*/", "").replace(".nbt", "").replace('_', ' ') : "start chunk";
            dev.erebus.client.render.Visuals.label(ui, at, dev.erebus.client.render.font.Icon.GLOBE, k.label, detail, e.rejected() ? 0xFFF87171 : kindColor(k));
        }
    }

    /** Changes whenever there's something new to crack with. */
    private int attemptKey() {
        return data.structures.size() * 100_003 + (data.pillarKeys == null ? 0 : data.pillarKeys.length + 1);
    }

    // =====================================================================================================
    // The End's pillars
    // =====================================================================================================

    private int pillarTicks;

    /**
     * Reads the End's ten obsidian pillars. The game gives pillar i the slot {@code l} from a shuffle of 0-9 seeded
     * with {@code new Random(seed).nextLong() & 0xFFFF}: it's {@code 76 + 3l} blocks tall, topped with bedrock at
     * that height, and {@code 2 + l/3} wide. The bedrock survives when the obsidian under it is mined (and the
     * dragon's respawn rebuilds the pillars exactly), so it gives the slot; without it, the width under the island
     * still gives {@code l/3}.
     */
    private void readPillars() {
        if (!(mc.level.getChunkSource() instanceof net.minecraft.client.multiplayer.ClientChunkCache cache)) return;
        int[] seen = data.pillars != null && data.pillars.length == 10 ? data.pillars.clone() : new int[]{-1, -1, -1, -1, -1, -1, -1, -1, -1, -1};
        boolean changed = false;
        for (int i = 0; i < 10; i++) {
            if (seen[i] >= 0 && seen[i] < 10) continue; // already exact
            double angle = 2.0 * (-Math.PI + 0.3141592653589793 * i);
            int cx = net.minecraft.util.Mth.floor(42.0 * Math.cos(angle)), cz = net.minecraft.util.Mth.floor(42.0 * Math.sin(angle));
            if (cache.getChunk(cx >> 4, cz >> 4, ChunkStatus.FULL, false) == null) continue;
            int obs = -1;
            for (int l = 0; l < 10 && obs < 0; l++) {
                if (mc.level.getBlockState(new net.minecraft.core.BlockPos(cx, 76 + 3 * l, cz)).is(net.minecraft.world.level.block.Blocks.BEDROCK)) obs = l;
            }
            if (obs < 0) {
                int r = -1;
                for (int y = 20; y <= 70; y += 10) {
                    if (!obsidian(cx, y, cz)) continue;
                    int d = 0;
                    while (d < 6 && obsidian(cx + d + 1, y, cz) && obsidian(cx - d - 1, y, cz) && obsidian(cx, y, cz + d + 1) && obsidian(cx, y, cz - d - 1)) d++;
                    r = Math.max(r, d);
                }
                if (r >= 2 && r <= 5) obs = 10 + (r - 2);
            }
            if (obs >= 0 && obs != seen[i]) {
                seen[i] = obs;
                changed = true;
            }
        }
        if (!changed) return;
        data.pillars = seen;
        int[] keys = pillarKeys(seen);
        data.pillarKeys = keys.length == 0 ? null : keys;
        data.save();
        int exact = 0, wide = 0;
        for (int v : seen) {
            if (v >= 0 && v < 10) exact++;
            else if (v >= 10) wide++;
        }
        String what = exact + " by the bedrock on top" + (wide > 0 ? ", " + wide + " only by width" : "");
        if (keys.length == 0) chat("End pillars (" + what + ") don't match any arrangement the game makes, so they're ignored. Rebuilt by players?");
        else if (announce.get()) chat(String.format(java.util.Locale.ROOT, "Read the End pillars (%s): %.1f bits about the seed.", what,
                16 - Math.log(keys.length) / Math.log(2)));
    }

    private boolean obsidian(int x, int y, int z) {
        return mc.level.getBlockState(new net.minecraft.core.BlockPos(x, y, z)).is(net.minecraft.world.level.block.Blocks.OBSIDIAN);
    }

    /** The shuffle keys (0-65535) whose pillar arrangement fits what was seen, using the game's own shuffle. */
    private static int[] pillarKeys(int[] seen) {
        java.util.stream.IntStream.Builder out = java.util.stream.IntStream.builder();
        for (int key = 0; key < 65536; key++) {
            var slots = net.minecraft.util.Util.toShuffledList(java.util.stream.IntStream.range(0, 10), net.minecraft.util.RandomSource.create(key));
            boolean ok = true;
            for (int i = 0; i < 10 && ok; i++) {
                int v = seen[i];
                if (v < 0) continue;
                int slot = slots.getInt(i);
                ok = v < 10 ? slot == v : slot / 3 == v - 10;
            }
            if (ok) out.add(key);
        }
        return out.build().toArray();
    }

    private Set<StructureScanner.Kind> kinds() {
        Set<StructureScanner.Kind> k = EnumSet.noneOf(StructureScanner.Kind.class);
        if (pyramids.get()) k.add(StructureScanner.Kind.DESERT_PYRAMID);
        if (jungleTemples.get()) k.add(StructureScanner.Kind.JUNGLE_TEMPLE);
        if (swampHuts.get()) k.add(StructureScanner.Kind.SWAMP_HUT);
        if (treasure.get()) k.add(StructureScanner.Kind.BURIED_TREASURE);
        if (igloos.get()) k.add(StructureScanner.Kind.IGLOO);
        if (villages.get()) k.add(StructureScanner.Kind.VILLAGE);
        if (outposts.get()) k.add(StructureScanner.Kind.OUTPOST);
        if (mineshafts.get()) k.add(StructureScanner.Kind.MINESHAFT);
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
        SeedData.Entry entry = new SeedData.Entry(f.kind().name(), f.chunkX(), f.chunkZ(), m.getX(), m.getY(), m.getZ(), false, f.piece(), f.rotation());
        boolean misfit = data.worldSeed != null && !SeedSolver.fits(data.worldSeed, data.fitClues(entry));
        data.structures.add(entry.withRejected(misfit));
        data.save();
        if (misfit) {
            if (announce.get()) chat(f.kind().label + " at " + m.getX() + ", " + m.getZ() + " doesn't fit the seed found, so it was probably "
                    + "misread; ignored.");
            return;
        }
        if (data.worldSeed != null && data.salted() && f.kind().spread() && (data.salts == null || !data.salts.containsKey(f.kind().name()))) {
            learnSalt(data, f.kind());
        }
        int spreads = (int) data.structures.stream().filter(e -> !e.rejected() && !e.kind().equals(StructureScanner.Kind.BURIED_TREASURE.name())).count();
        String piece = f.piece() == null ? ""
                : f.kind() == StructureScanner.Kind.VILLAGE ? " (" + f.piece().replaceAll(".*/", "").replace(".nbt", "").replace('_', ' ') + ")"
                : f.kind() == StructureScanner.Kind.MINESHAFT && !f.piece().contains("-") ? " (" + f.piece() + ")" : "";
        String detail = f.kind().label + piece + " at " + m.getX() + ", " + m.getZ() + " (" + spreads + " structures, "
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

    /** More structure seeds than this left over take too long to check against the hashed seed (65,536 hashes each). */
    private static final int MAX_CANDIDATES = 16_384;
    /** Most high-bit values to try in one search (about a minute on four cores). */
    private static final long MAX_SEARCH = 1L << 33;

    private void crack(boolean byHand) {
        if (data == null || cracking) return;
        // a server already known to use its own salts goes straight to that
        boolean saltsOnly = data.salted() && customSalts.get();
        String why = saltsOnly ? SaltSolver.notEnough(data.saltAnchors(), data.saltGroups()) : SeedSolver.notEnough(data.clues(true));
        if (why != null) {
            if (byHand) chat("Not enough structures yet: " + why + ".");
            return;
        }
        attemptedWith = attemptKey();
        cracking = true;
        progress.set(0);
        SeedData d = data;
        List<SeedSolver.Clue> all = d.clues(true);
        List<SeedSolver.Clue> anchors = d.saltAnchors();
        List<SaltSolver.Group> groups = d.saltGroups();
        boolean trySalts = customSalts.get();
        long hashed = d.hashedSeed;
        if (announce.get() || byHand) chat("Cracking with " + d.structures.size() + " structures"
                + (saltsOnly ? ", each kind on its own salt" : "") + "...");
        boolean allowMisreads = tolerate.get();
        boolean hasPillars = all.stream().anyMatch(c -> c instanceof SeedSolver.PillarKeys);
        Thread t = new Thread(() -> {
            String message = null;
            Long world = null, structure = null;
            List<SeedSolver.Clue> misfits = List.of();
            boolean usedPillars = false;
            boolean anyFit = false, refused = false;
            if (!saltsOnly) try {
                // with the End's pillars first; if nothing fits, without them (a server's End can have its own seed)
                List<List<SeedSolver.Clue>> tries = new ArrayList<>();
                tries.add(all);
                if (hasPillars) tries.add(all.stream().filter(c -> !(c instanceof SeedSolver.PillarKeys)).toList());
                for (int attempt = 0; attempt < tries.size() && world == null; attempt++) {
                    List<SeedSolver.Clue> clues = tries.get(attempt);
                    if (attempt > 0 && SeedSolver.notEnough(clues) != null) break;
                    usedPillars = attempt == 0 && hasPillars;
                    int spreads = (int) clues.stream().filter(c -> c instanceof SeedSolver.Spread).count();
                    // misreads are only allowed with the hashed seed to confirm the result, and enough structures left over
                    int maxMisses = allowMisreads && hashed != 0 ? Math.min(3, Math.max(0, spreads - 4)) : 0;
                    String failed = "No seed fits all " + d.counted() + " structures" + (hasPillars ? " and the End pillars" : "")
                            + ". One may have been misread (turn on Allow misreads and find a few more), or this server changed its structure "
                            + "settings.";
                    if (message == null) message = failed;
                    // misread mineshafts (and other clues that only check seeds) are cheap to allow for: up to 2, as long as
                    // enough is left; then misread structures, which need pass 1 redone
                    int checks = (int) clues.stream().filter(c -> c instanceof SeedSolver.Mineshaft || c instanceof SeedSolver.Chance
                            || c instanceof SeedSolver.OutpostRoll || c instanceof SeedSolver.StartRoll).count();
                    double info = SeedSolver.information(clues);
                    List<int[]> tolerances = new ArrayList<>();
                    tolerances.add(new int[]{0, 0});
                    if (allowMisreads && hashed != 0) {
                        for (int c = 1; c <= Math.min(2, checks / 3) && info - 10 * c >= 34; c++) tolerances.add(new int[]{0, c});
                    }
                    for (int k = 1; k <= maxMisses; k++) tolerances.add(new int[]{k, 0});
                    for (int[] tol : tolerances) {
                        int k = tol[0], c = tol[1];
                        // some sets of positions pin down the low bits much less than usual: don't grind for minutes
                        if (SeedSolver.exactSearchSize(clues, k) > MAX_SEARCH) {
                            if (k == 0 && c == 0) refused = true; // an exact fit can't be ruled out
                            if (k == 0) message = "Not enough to crack quickly yet: these structures pin down the seed's low bits less than "
                                    + "usual. Find another temple, hut, igloo or outpost (or read the End pillars).";
                            break;
                        }
                        List<SeedSolver.Candidate> found = SeedSolver.structureSeeds(clues, MAX_CANDIDATES, progress, k, c);
                        if (found.isEmpty()) continue;
                        anyFit = true;
                        if (hashed == 0) {
                            if (found.size() == 1) structure = found.get(0).seed();
                            message = "The server doesn't send a hashed seed, so only the structure seed (low 48 bits) can be found: "
                                    + (found.size() == 1 ? found.get(0).seed() : found.size() + " candidates left, find more structures") + ".";
                            break;
                        }
                        if (found.size() >= MAX_CANDIDATES) {
                            message = "Too many possible seeds still; find a few more structures.";
                            break;
                        }
                        SeedSolver.Candidate w = SeedSolver.firstWorldSeed(found, hashed, BiomeManager::obfuscateSeed);
                        if (w != null) {
                            world = w.seed();
                            structure = world & ((1L << 48) - 1);
                            misfits = SeedSolver.misfits(world, clues);
                            message = attempt > 0 ? "The End pillars don't fit this seed, so they were left out (the End may have its own "
                                    + "seed here)." : null;
                            break;
                        }
                        message = found.size() + " structure seeds fit, but none matches the server's hashed seed. Find a few more structures "
                                + "(if this keeps happening, the server may send a fake hashed seed).";
                    }
                }
            } catch (Throwable e) {
                message = "Cracking failed: " + e;
            }
            // nothing fits the game's own salts: try each kind of structure on its own salt (Spigot's seed-village...)
            // (on a server with the game's own salts the real seed always fits, so seeds that fit without matching the hashed
            // seed point the same way)
            if (saltsOnly || (trySalts && world == null && !refused && (!anyFit || hashed != 0))) {
                String saltWhy = SaltSolver.notEnough(anchors, groups);
                if (saltWhy == null) {
                    progress.set(0);
                    if (!saltsOnly) mc.execute(() -> {
                        if (announce.get() || byHand) chat("No seed fits with the game's own structure settings; trying each kind of structure "
                                + "on its own salt (as Spigot's seed-village, seed-desert... settings do)...");
                    });
                    SaltSolver.Outcome o;
                    try {
                        o = SaltSolver.solve(anchors, groups, hashed, BiomeManager::obfuscateSeed, progress);
                    } catch (Throwable e) {
                        o = new SaltSolver.Outcome(null, null, List.of(), true, "Cracking failed: " + e, 0);
                    }
                    SaltSolver.Outcome out = o;
                    mc.execute(() -> finishSalted(d, out, byHand));
                    return;
                }
                if (!saltsOnly && message != null) message += " If it set its own structure salts, " + saltWhy + ".";
            }
            String msg = message;
            Long w = world, s = structure;
            List<SeedSolver.Clue> bad = misfits;
            boolean pillarsUsed = usedPillars;
            mc.execute(() -> finish(d, w, s, bad, msg, byHand, pillarsUsed));
        }, "Erebus seed cracker");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void finish(SeedData d, Long world, Long structure, List<SeedSolver.Clue> misfits, String message, boolean byHand, boolean usedPillars) {
        cracking = false;
        if (structure != null) d.structureSeed = structure;
        if (world != null) {
            // set aside the structures that don't fit, then check the rest with the game's own placement code
            List<String> ignored = new java.util.ArrayList<>();
            int badClues = 0;
            for (int i = 0; i < d.structures.size(); i++) {
                SeedData.Entry e = d.structures.get(i);
                StructureScanner.Kind k = e.kindOrNull();
                if (k == null || e.rejected()) continue;
                int bad = (int) e.clues().stream().filter(c -> !SeedSolver.fits(world, List.of(c))).count();
                if (bad > 0) {
                    d.structures.set(i, e.withRejected(true));
                    ignored.add(k.label + " at " + e.x() + ", " + e.z());
                    badClues += bad;
                }
            }
            if (badClues == misfits.size() && verified(d, world, usedPillars)) {
                d.worldSeed = world;
                d.save();
                Notifications.push(name, "Seed found: " + world, Notifications.Kind.SUCCESS);
                seedMessage(world, (int) d.counted(), usedPillars);
                if (!ignored.isEmpty()) chat("Ignored " + (ignored.size() == 1 ? "1 structure that doesn't" : ignored.size() + " structures that don't")
                        + " fit the seed (probably misread): " + String.join("; ", ignored) + ".");
                if (message != null) chat(message);
                return;
            }
            for (int i = 0; i < d.structures.size(); i++) d.structures.set(i, d.structures.get(i).withRejected(false));
        }
        if (world != null) message = "Found " + world + " but it doesn't reproduce every structure; ignoring it.";
        d.save();
        if (message != null && (announce.get() || byHand)) chat(message);
    }

    private void finishSalted(SeedData d, SaltSolver.Outcome o, boolean byHand) {
        cracking = false;
        if (o.structureSeed() == null) {
            d.save();
            if (o.message() != null && (announce.get() || byHand)) chat(o.message());
            return;
        }
        Boolean was = d.customSalts;
        java.util.Map<String, Integer> wasSalts = d.salts;
        boolean wasShaft = d.shaftSalted;
        d.customSalts = true;
        d.salts = new java.util.HashMap<>();
        for (SaltSolver.Salt s : o.salts()) if (s.salt() != null) d.salts.put(s.name(), s.salt());
        d.shaftSalted = !o.shaftRolls();
        d.structureSeed = o.structureSeed();
        Long world = o.worldSeed();
        if (world != null && !verified(d, world, true)) {
            d.customSalts = was;
            d.salts = wasSalts;
            d.shaftSalted = wasShaft;
            d.save();
            chat("Found " + world + " but it doesn't reproduce every structure; ignoring it.");
            return;
        }
        if (world != null) {
            d.worldSeed = world;
            cachedKey = null; // the salts and seed the map uses changed
            Notifications.push(name, "Seed found: " + world, Notifications.Kind.SUCCESS);
            seedMessage(world, (int) d.counted(), true);
        }
        d.save();
        chat(saltReport(o));
        if (o.message() != null) chat(o.message());
    }

    /** Which kinds of structure have their own salt here, and the settings they come from. */
    private static String saltReport(SaltSolver.Outcome o) {
        List<String> custom = new ArrayList<>(), standard = new ArrayList<>(), unknown = new ArrayList<>();
        for (SaltSolver.Salt s : o.salts()) {
            StructureScanner.Kind k = StructureScanner.Kind.valueOf(s.name());
            if (s.salt() == null) unknown.add(k.label.toLowerCase(java.util.Locale.ROOT) + " (" + s.note() + ")");
            else if (s.custom()) custom.add(k.label + " " + s.salt() + " (" + s.setting() + ")");
            else standard.add(k.label.toLowerCase(java.util.Locale.ROOT) + (s.note() != null ? " (" + s.note() + ")" : ""));
        }
        StringBuilder b = new StringBuilder("This server uses its own structure salts. ");
        if (!custom.isEmpty()) b.append("Custom: ").append(String.join("; ", custom)).append(". ");
        if (!standard.isEmpty()) b.append("The game's own: ").append(String.join(", ", standard)).append(". ");
        if (!unknown.isEmpty()) b.append("Not known yet: ").append(String.join(", ", unknown))
                .append("; worked out as more are found. ");
        if (!o.shaftRolls()) b.append("Mineshafts have their own salt too (seed-mineshaft), so only their room sizes count. ");
        b.append("Predicted structures on the map only show kinds whose salt is known (and strongholds).");
        return b.toString();
    }

    private final Set<String> learning = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Works out a kind's salt in the background once enough of it is found, now that the seed is known. */
    private void learnSalt(SeedData d, StructureScanner.Kind kind) {
        if (d.worldSeed == null || !learning.add(kind.name())) return;
        long seed = d.worldSeed;
        SaltSolver.Group g = d.saltGroups().stream().filter(x -> x.name().equals(kind.name())).findFirst().orElse(null);
        if (g == null || g.spreads().size() < 2) {
            learning.remove(kind.name());
            return;
        }
        Thread t = new Thread(() -> {
            Integer salt = null;
            try {
                salt = SaltSolver.saltOf(seed, g);
            } catch (Throwable ignored) {
            }
            Integer found = salt;
            mc.execute(() -> {
                learning.remove(kind.name());
                if (found == null || d.worldSeed == null || d.worldSeed != seed) return;
                if (d.salts == null) d.salts = new java.util.HashMap<>();
                d.salts.put(kind.name(), found);
                d.save();
                cachedKey = null;
                chat("Worked out the " + kind.label.toLowerCase(java.util.Locale.ROOT) + " salt: " + found
                        + (found == kind.salt ? " (the game's own)" : " (" + kind.spigotSetting + ")") + ". Predicted "
                        + kind.label.toLowerCase(java.util.Locale.ROOT) + "s now show on the map.");
            });
        }, "Erebus salt finder");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /**
     * The structure salts for where you are, by structure set ("villages", "desert_pyramids"...), when this server
     * turned out to use its own: only the ones known. Null when it uses the game's own (or isn't known not to).
     */
    public static java.util.Map<String, Integer> knownSalts() {
        try {
            if (mc.getSingleplayerServer() != null) return null;
        } catch (Throwable ignored) {
        }
        knownSeed(); // refreshes the cached data for this server
        SeedCracker m = instance;
        SeedData d = m != null && m.data != null && SeedData.serverKey().equals(m.data.key()) ? m.data : cachedData;
        if (d == null || !d.salted()) return null;
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        if (d.salts != null) {
            for (var e : d.salts.entrySet()) {
                try {
                    out.put(StructureScanner.Kind.valueOf(e.getKey()).set, e.getValue());
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return out;
    }

    /** Checks the seed with the game's own structure placement code (and pillar shuffle), with this server's salts. */
    private static boolean verified(SeedData d, long seed, boolean pillars) {
        for (SeedData.Entry e : d.structures) {
            StructureScanner.Kind k = e.kindOrNull();
            if (k == null || e.rejected() || k == StructureScanner.Kind.BURIED_TREASURE) continue;
            if (k == StructureScanner.Kind.MINESHAFT && d.salted() && d.shaftSalted) continue;
            Integer salt = !d.salted() || !k.spread() ? Integer.valueOf(k.salt) : d.salts == null ? null : d.salts.get(k.name());
            int use = salt == null ? k.salt : salt;
            RandomSpreadStructurePlacement placement = switch (k) {
                case OUTPOST -> new RandomSpreadStructurePlacement(net.minecraft.core.Vec3i.ZERO,
                        net.minecraft.world.level.levelgen.structure.placement.StructurePlacement.FrequencyReductionMethod.LEGACY_TYPE_1, 0.2F,
                        use, java.util.Optional.empty(), k.spacing, k.separation, RandomSpreadType.LINEAR);
                case MINESHAFT -> new RandomSpreadStructurePlacement(net.minecraft.core.Vec3i.ZERO,
                        net.minecraft.world.level.levelgen.structure.placement.StructurePlacement.FrequencyReductionMethod.LEGACY_TYPE_3, 0.004F,
                        use, java.util.Optional.empty(), k.spacing, k.separation, RandomSpreadType.LINEAR);
                default -> new RandomSpreadStructurePlacement(k.spacing, k.separation, RandomSpreadType.LINEAR, use);
            };
            // a kind whose salt isn't known yet only counts for what doesn't depend on it
            if (salt != null) {
                ChunkPos p = placement.getPotentialStructureChunk(seed, e.chunkX(), e.chunkZ());
                if (p.x != e.chunkX() || p.z != e.chunkZ()) return false;
            }
            if (!placement.applyAdditionalChunkRestrictions(e.chunkX(), e.chunkZ(), seed)) return false;
        }
        if (pillars && d.pillarKeys != null) {
            int key = (int) (net.minecraft.util.RandomSource.create(seed).nextLong() & 0xFFFFL);
            if (java.util.Arrays.binarySearch(d.pillarKeys, key) < 0) return false;
        }
        return true;
    }

    private void seedMessage(long seed, int count, boolean pillars) {
        if (mc.gui == null) return;
        Component seedText = Component.literal(String.valueOf(seed)).withStyle(s -> s.withColor(TextColor.fromRgb(0x7CE0B0)).withUnderlined(true)
                .withClickEvent(new ClickEvent.CopyToClipboard(String.valueOf(seed)))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy"))));
        Chat.info("Seed found from " + count + " structures" + (pillars ? ", the End pillars" : "") + " and the server's hashed seed:");
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
        data.pillars = null;
        data.pillarKeys = null;
        data.customSalts = null;
        data.salts = null;
        data.shaftSalted = false;
        cachedKey = null;
        attemptedWith = -1;
        data.save();
        chat("Forgot everything recorded for " + data.key() + ".");
    }
}
