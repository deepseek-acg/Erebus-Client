package dev.erebus.client.seed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongUnaryOperator;
import java.util.stream.IntStream;

/**
 * Recovers a world seed from where structures generated. Pure Java, no Minecraft classes, so it can be tested on
 * its own.
 *
 * <p>Random-spread structures (temples, huts, villages...) pick their chunk inside a region with Java's 48-bit LCG
 * seeded from {@code seed + regionX * 341873128712 + regionZ * 132897987541 + salt}: {@code nextInt(spacing -
 * separation)} for x, then for z. That only involves the low 48 bits of the seed (the "structure seed"), found in
 * two passes:
 * <ol>
 *   <li>Low bits. When {@code spacing - separation} is divisible by {@code 2^b}, the low b bits of each offset
 *       are bits 17..17+b of the LCG state, and those depend only on the low 17+b bits of the seed. All 2^(17+b)
 *       of those are tried against every structure; a temple (24 = 8 * 3) pins 6 bits, so a handful leave one.</li>
 *   <li>High bits. For each surviving low part, the remaining 2^(48-17-b) values are checked against every
 *       structure in full (in parallel).</li>
 * </ol>
 * Pillager outposts also passed a 1-in-5 roll, buried treasure a 1-in-100 one: checked in pass 2. The End's pillars
 * fix 16 bits that depend on the low 32 bits of the seed only, so with them pass 1 carries on, a bit at a time, to
 * 32 bits, and pass 2 only has 16 left.
 *
 * <p>The top 16 bits never affect structure placement. They're found by trying all 65536 against the hashed seed the
 * server sends (the first 8 bytes of SHA-256 of the seed, which the client uses for biome blending).
 */
public final class SeedSolver {
    private static final long MUL = 0x5DEECE66DL;
    private static final long ADD = 0xBL;
    private static final long MASK48 = (1L << 48) - 1;
    private static final long REGION_X = 341873128712L;
    private static final long REGION_Z = 132897987541L;
    /** Salt of the "legacy type 2" frequency check (buried treasure). */
    private static final int LEGACY_SALT = 10387320;

    private SeedSolver() {
    }

    /** One structure seen in the world, reduced to what its placement says about the seed. */
    public sealed interface Clue permits Spread, Chance, OutpostRoll, PillarKeys {
    }

    /**
     * A random-spread structure whose start chunk is {@code (chunkX, chunkZ)}.
     */
    public record Spread(int spacing, int separation, int salt, int chunkX, int chunkZ) implements Clue {
        int limit() {
            return spacing - separation;
        }

        int regionX() {
            return Math.floorDiv(chunkX, spacing);
        }

        int regionZ() {
            return Math.floorDiv(chunkZ, spacing);
        }

        int offsetX() {
            return chunkX - regionX() * spacing;
        }

        int offsetZ() {
            return chunkZ - regionZ() * spacing;
        }

        long key() {
            return (long) regionX() * REGION_X + (long) regionZ() * REGION_Z + salt;
        }

        boolean valid() {
            return offsetX() < limit() && offsetZ() < limit();
        }
    }

    /** A structure that generates in a chunk with some probability ("legacy type 2", e.g. buried treasure). */
    public record Chance(int chunkX, int chunkZ, float frequency) implements Clue {
        long key() {
            return (long) chunkX * REGION_X + (long) chunkZ * REGION_Z + LEGACY_SALT;
        }
    }

    /**
     * A pillager outpost's extra roll ("legacy type 1" frequency reduction): its start chunk only gets one if
     * {@code nextInt(5) == 0} for a generator seeded from the seed and the chunk's coordinates divided by 16.
     */
    public record OutpostRoll(int chunkX, int chunkZ) implements Clue {
        long key() {
            return (long) ((chunkX >> 4) ^ (chunkZ >> 4) << 4);
        }
    }

    /**
     * The End's obsidian pillars: the game shuffles their heights with {@code new Random(seed).nextLong() & 0xFFFF},
     * and {@code keys} (sorted) are the values that fit what was seen. That number only depends on the low 32 bits
     * of the seed, a bit at a time, so it narrows the low bits down further before the high bits are searched.
     */
    public record PillarKeys(int[] keys) implements Clue {
        boolean allows(int key) {
            return java.util.Arrays.binarySearch(keys, key) >= 0;
        }

        /** Information it adds, in bits. */
        double bits() {
            return keys.length == 0 ? 0 : 16 - Math.log(keys.length) / Math.log(2);
        }

        /** For each length 1..16, which values of that many low bits some key has. */
        java.util.BitSet[] prefixes() {
            java.util.BitSet[] out = new java.util.BitSet[17];
            for (int len = 1; len <= 16; len++) {
                out[len] = new java.util.BitSet(1 << len);
                for (int k : keys) out[len].set(k & ((1 << len) - 1));
            }
            return out;
        }
    }

    // =====================================================================================================
    // Forward: what a seed generates (also used to check results)
    // =====================================================================================================

    private static long scramble(long seed) {
        return (seed ^ MUL) & MASK48;
    }

    private static long step(long state) {
        return (state * MUL + ADD) & MASK48;
    }

    /** java.util.Random#nextInt(bound) on a raw 48-bit state; returns {value, newState} packed: value in [0,bound). */
    private static long nextInt(long[] state, int bound) {
        if ((bound & -bound) == bound) {
            state[0] = step(state[0]);
            return (int) ((bound * (state[0] >>> 17)) >> 31);
        }
        int bits, val;
        do {
            state[0] = step(state[0]);
            bits = (int) (state[0] >>> 17);
            val = bits % bound;
        } while (bits - val + (bound - 1) < 0);
        return val;
    }

    /** The chunk a random-spread structure picks in the region containing (chunkX, chunkZ). */
    public static int[] spreadChunk(long seed, int spacing, int separation, int salt, int chunkX, int chunkZ) {
        int rx = Math.floorDiv(chunkX, spacing), rz = Math.floorDiv(chunkZ, spacing);
        long[] s = {scramble((long) rx * REGION_X + (long) rz * REGION_Z + seed + salt)};
        int limit = spacing - separation;
        int ox = (int) nextInt(s, limit);
        int oz = (int) nextInt(s, limit);
        return new int[]{rx * spacing + ox, rz * spacing + oz};
    }

    /** Whether a "legacy type 2" structure (buried treasure) generates in this chunk. */
    public static boolean chanceHits(long seed, int chunkX, int chunkZ, float frequency) {
        long state = step(scramble((long) chunkX * REGION_X + (long) chunkZ * REGION_Z + seed + LEGACY_SALT));
        return (int) (state >>> 24) * 5.9604645E-8F < frequency;
    }

    /** Whether a pillager outpost's start chunk passes its extra roll. */
    public static boolean outpostRoll(long seed, int chunkX, int chunkZ) {
        long[] st = {scramble(new OutpostRoll(chunkX, chunkZ).key() ^ seed)};
        st[0] = step(st[0]); // a nextInt() that's thrown away
        return nextInt(st, 5) == 0;
    }

    /** The number the End's pillar heights are shuffled with: {@code new Random(seed).nextLong() & 0xFFFF}. */
    public static int pillarKey(long seed) {
        long s2 = step(step(scramble(seed)));
        return (int) ((s2 >>> 16) & 0xFFFF); // the low half of nextLong is the second next(32)
    }

    /** Whether {@code seed} (its low 48 bits) explains every clue. */
    public static boolean fits(long seed, List<Clue> clues) {
        for (Clue c : clues) {
            if (c instanceof Spread s) {
                int[] p = spreadChunk(seed, s.spacing(), s.separation(), s.salt(), s.chunkX(), s.chunkZ());
                if (p[0] != s.chunkX() || p[1] != s.chunkZ()) return false;
            } else if (c instanceof Chance ch) {
                if (!chanceHits(seed, ch.chunkX(), ch.chunkZ(), ch.frequency())) return false;
            } else if (c instanceof OutpostRoll o) {
                if (!outpostRoll(seed, o.chunkX(), o.chunkZ())) return false;
            } else if (c instanceof PillarKeys pk) {
                if (!pk.allows(pillarKey(seed))) return false;
            }
        }
        return true;
    }

    // =====================================================================================================
    // Backward: the structure seed
    // =====================================================================================================

    /**
     * Roughly how much the clues say about the 48 bits structures depend on, in bits. Not a plain sum: the
     * structures' low-bit offsets and the End pillars both describe the low bits (20 and 32 of them at most), and
     * what different structures say about the high bits partly overlaps, so that part is discounted (by a factor
     * measured on test seeds).
     */
    public static double information(List<Clue> clues) {
        double low = 0, pillar = 0, high = 0;
        for (Clue c : clues) {
            if (c instanceof Spread s && s.valid()) {
                int b = lowBits(s.limit());
                low += 2 * b;
                high += 2 * Math.log((double) s.limit() / (1 << b)) / Math.log(2);
            } else if (c instanceof Chance ch) {
                high += -Math.log(ch.frequency()) / Math.log(2);
            } else if (c instanceof OutpostRoll) {
                high += Math.log(5) / Math.log(2);
            } else if (c instanceof PillarKeys pk) {
                pillar += pk.bits();
            }
        }
        return Math.min(32, Math.min(20, low) + pillar) + 0.75 * high;
    }

    /**
     * Why cracking can't start yet, or null if the clues are enough to try. Two things are needed: enough
     * information overall (by {@link #information}, 34 bits usually leave a few thousand structure seeds at most
     * for the hashed seed to choose from), and enough of it in the low bits that the search over the high bits
     * stays short.
     */
    public static String notEnough(List<Clue> clues) {
        int spreads = 0;
        for (Clue c : clues) if (c instanceof Spread s && s.valid()) spreads++;
        if (spreads < 2) return "need at least 2 structures (have " + spreads + ")";
        double info = information(clues);
        if (info < 34) return "need more structures (have about " + (int) info + " of the 34 bits needed)";
        if (searchSize(clues) > (1L << 31)) return "need another temple, hut, igloo or outpost (they pin down the low bits)";
        return null;
    }

    /** Roughly how many high-bit values the second pass will try. */
    static long searchSize(List<Clue> clues) {
        int b = 0, lift = 0;
        PillarKeys pillars = null;
        for (Clue c : clues) {
            if (c instanceof Spread s && s.valid()) {
                b = Math.max(b, lowBits(s.limit()));
                lift += 2 * lowBits(s.limit());
            } else if (c instanceof PillarKeys pk && pk.keys().length > 0) {
                pillars = pk;
            }
        }
        int width = 17 + b;
        double survivors = Math.max(1, Math.pow(2, width - Math.min(lift, width)));
        if (pillars != null) {
            survivors = Math.max(1, survivors * Math.pow(2, 32 - width) * pillars.keys().length / 65536.0);
            width = 32;
        }
        return (long) Math.min(Long.MAX_VALUE / 2, survivors * Math.pow(2, 48 - width));
    }

    private static int lowBits(int limit) {
        if ((limit & -limit) == limit) return 0; // a power of two takes the top bits, not the low ones
        return Math.min(3, Integer.numberOfTrailingZeros(limit));
    }

    /**
     * Every structure seed (low 48 bits) that explains all the clues, up to {@code max}. {@code progress} goes from 0
     * to 1_000_000 as the search runs.
     */
    public static List<Long> structureSeeds(List<Clue> clues, int max, AtomicLong progress) {
        List<Long> out = new ArrayList<>();
        for (Candidate c : structureSeeds(clues, max, progress, 0)) out.add(c.seed());
        return out;
    }

    /** A structure seed and how many clues it doesn't explain. */
    public record Candidate(long seed, int misses) {
    }

    /**
     * Like {@link #structureSeeds(List, int, AtomicLong)}, but a seed may leave up to {@code maxMisses} clues
     * unexplained: a few misread structures then don't hide the real seed. Results are sorted by how many clues they
     * miss, fewest first.
     */
    public static List<Candidate> structureSeeds(List<Clue> clues, int max, AtomicLong progress, int maxMisses) {
        List<Spread> spreads = new ArrayList<>();
        List<Chance> chances = new ArrayList<>();
        List<OutpostRoll> rolls = new ArrayList<>();
        PillarKeys pillars = null;
        for (Clue c : clues) {
            if (c instanceof OutpostRoll o) {
                rolls.add(o);
                continue;
            }
            if (c instanceof PillarKeys pk) {
                if (pk.keys().length > 0) pillars = pk;
                continue;
            }
            if (c instanceof Spread s) {
                if (!s.valid()) { // an offset outside the region's range can't be a real start chunk
                    if (maxMisses == 0) return List.of();
                    maxMisses--;
                    continue;
                }
                spreads.add(s);
            } else if (c instanceof Chance ch) {
                chances.add(ch);
            }
        }
        int b = 0;
        for (Spread s : spreads) b = Math.max(b, lowBits(s.limit()));
        int lowBits = 17 + b;
        long lowMask = (1L << lowBits) - 1;

        // pass 1: the low bits
        final int allowed = maxMisses;
        List<Long> lows = new ArrayList<>();
        for (long low = 0; low <= lowMask; low++) {
            int misses = 0;
            for (Spread s : spreads) {
                int sb = lowBits(s.limit());
                if (sb == 0) continue;
                int m = (1 << sb) - 1;
                long st = ((low + s.key()) ^ MUL) & lowMask;
                st = (st * MUL + ADD) & lowMask;
                boolean ok = ((int) (st >>> 17) & m) == (s.offsetX() & m);
                st = (st * MUL + ADD) & lowMask;
                ok &= ((int) (st >>> 17) & m) == (s.offsetZ() & m);
                if (!ok && ++misses > allowed) break;
            }
            if (misses <= allowed) lows.add(low);
        }
        if (lows.isEmpty()) return List.of();

        // the End's pillars: one more bit of their key comes into reach with each further low bit of the seed
        if (pillars != null) {
            java.util.BitSet[] keyPrefixes = pillars.prefixes();
            for (int w = lowBits; w <= 32; w++) {
                int len = w - 16; // key bits that only need the low w bits of the seed
                List<Long> next = new ArrayList<>();
                for (long low : lows) {
                    for (int bit = 0; bit <= (w == lowBits ? 0 : 1); bit++) {
                        long cand = w == lowBits ? low : low | ((long) bit << (w - 1));
                        long m = (1L << w) - 1;
                        long st = ((cand ^ MUL) & m);
                        st = (st * MUL + ADD) & m;
                        st = (st * MUL + ADD) & m;
                        if (len <= 0 || keyPrefixes[Math.min(16, len)].get((int) ((st >>> 16) & ((1L << Math.min(16, len)) - 1)))) next.add(cand);
                    }
                }
                lows = next;
                if (lows.isEmpty()) return List.of();
            }
            lowBits = 32;
        }

        // pass 2: the high bits, checking the most selective structures first
        spreads.sort((x, y) -> Integer.compare(y.limit(), x.limit()));
        final int lb = lowBits;
        int highBits = 48 - lb;
        AtomicLong done = new AtomicLong();
        List<Candidate> found = Collections.synchronizedList(new ArrayList<>());
        Spread[] sp = spreads.toArray(new Spread[0]);
        Chance[] ch = chances.toArray(new Chance[0]);
        OutpostRoll[] ro = rolls.toArray(new OutpostRoll[0]);
        // each work item: one low part and a slice of its high parts
        int slices = highBits >= 24 ? 1 << 12 : 1;
        long perSlice = (1L << highBits) / slices;
        long[] lowArr = lows.stream().mapToLong(Long::longValue).toArray();
        long total = (long) slices * lowArr.length;
        java.util.stream.LongStream.range(0, total).parallel().forEach(item -> {
            if (found.size() >= max) return;
            long low = lowArr[(int) (item / slices)];
            long start = (item % slices) * perSlice, end = start + perSlice;
            long[] st = new long[1];
            outer:
            for (long high = start; high < end; high++) {
                long seed = (high << lb) | low;
                int misses = 0;
                for (Spread s : sp) {
                    st[0] = ((seed + s.key()) ^ MUL) & MASK48;
                    if (nextInt(st, s.limit()) != s.offsetX() || nextInt(st, s.limit()) != s.offsetZ()) {
                        if (++misses > allowed) continue outer;
                    }
                }
                for (Chance c : ch) {
                    long state = step(((seed + c.key()) ^ MUL) & MASK48);
                    if (!((int) (state >>> 24) * 5.9604645E-8F < c.frequency()) && ++misses > allowed) continue outer;
                }
                for (OutpostRoll o : ro) {
                    if (!outpostRoll(seed, o.chunkX(), o.chunkZ()) && ++misses > allowed) continue outer;
                }
                found.add(new Candidate(seed, misses));
                if (found.size() >= max) return;
            }
            if (progress != null) progress.set(done.incrementAndGet() * 1_000_000L / total);
        });
        List<Candidate> out = new ArrayList<>(found);
        out.sort((p, q) -> p.misses() != q.misses() ? Integer.compare(p.misses(), q.misses()) : Long.compare(p.seed(), q.seed()));
        return out;
    }

    /** The clues a seed doesn't explain (structures that were probably misread). */
    public static List<Clue> misfits(long seed, List<Clue> clues) {
        List<Clue> out = new ArrayList<>();
        for (Clue c : clues) {
            if (!fits(seed, List.of(c))) out.add(c);
        }
        return out;
    }

    // =====================================================================================================
    // The top 16 bits
    // =====================================================================================================

    /**
     * The full world seeds whose low 48 bits are one of {@code structureSeeds} and whose hash matches
     * {@code hashedSeed}, using {@code hash} (the game's own seed obfuscation).
     */
    public static List<Long> worldSeeds(List<Long> structureSeeds, long hashedSeed, LongUnaryOperator hash) {
        List<Long> out = Collections.synchronizedList(new ArrayList<>());
        for (long s : structureSeeds) {
            IntStream.range(0, 1 << 16).parallel().forEach(upper -> {
                long seed = ((long) upper << 48) | s;
                if (hash.applyAsLong(seed) == hashedSeed) out.add(seed);
            });
        }
        List<Long> sorted = new ArrayList<>(out);
        Collections.sort(sorted);
        return sorted;
    }

    /**
     * The first full world seed (checking candidates in order, fewest misses first) whose hash matches
     * {@code hashedSeed}, or null.
     */
    public static Candidate firstWorldSeed(List<Candidate> candidates, long hashedSeed, LongUnaryOperator hash) {
        for (Candidate c : candidates) {
            long s48 = c.seed() & MASK48;
            java.util.OptionalLong found = IntStream.range(0, 1 << 16).parallel()
                    .mapToLong(upper -> ((long) upper << 48) | s48)
                    .filter(seed -> hash.applyAsLong(seed) == hashedSeed)
                    .findAny();
            if (found.isPresent()) return new Candidate(found.getAsLong(), c.misses());
        }
        return null;
    }
}
