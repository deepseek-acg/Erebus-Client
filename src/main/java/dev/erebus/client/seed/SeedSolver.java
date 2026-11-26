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
 * The top 16 bits never affect structure placement. They're found by trying all 65536 against the hashed seed the
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
    public sealed interface Clue permits Spread, Chance {
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

    /** Whether {@code seed} (its low 48 bits) explains every clue. */
    public static boolean fits(long seed, List<Clue> clues) {
        for (Clue c : clues) {
            if (c instanceof Spread s) {
                int[] p = spreadChunk(seed, s.spacing(), s.separation(), s.salt(), s.chunkX(), s.chunkZ());
                if (p[0] != s.chunkX() || p[1] != s.chunkZ()) return false;
            } else if (c instanceof Chance ch) {
                if (!chanceHits(seed, ch.chunkX(), ch.chunkZ(), ch.frequency())) return false;
            }
        }
        return true;
    }

    // =====================================================================================================
    // Backward: the structure seed
    // =====================================================================================================

    /** Why cracking can't start yet, or null if the clues are enough to try. */
    public static String notEnough(List<Clue> clues) {
        int bits = 0, spreads = 0;
        for (Clue c : clues) {
            if (c instanceof Spread s && s.valid()) {
                spreads++;
                bits += 2 * lowBits(s.limit());
            }
        }
        if (spreads < 3) return "need at least 3 temples or huts (have " + spreads + ")";
        if (bits < 18) return "need more temples or huts";
        return null;
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
        List<Spread> spreads = new ArrayList<>();
        List<Chance> chances = new ArrayList<>();
        for (Clue c : clues) {
            if (c instanceof Spread s) {
                if (!s.valid()) return List.of(); // an offset outside the region's range: not a real start chunk
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
        List<Long> lows = new ArrayList<>();
        for (long low = 0; low <= lowMask; low++) {
            boolean ok = true;
            for (Spread s : spreads) {
                int sb = lowBits(s.limit());
                if (sb == 0) continue;
                int m = (1 << sb) - 1;
                long st = ((low + s.key()) ^ MUL) & lowMask;
                st = (st * MUL + ADD) & lowMask;
                if (((int) (st >>> 17) & m) != (s.offsetX() & m)) {
                    ok = false;
                    break;
                }
                st = (st * MUL + ADD) & lowMask;
                if (((int) (st >>> 17) & m) != (s.offsetZ() & m)) {
                    ok = false;
                    break;
                }
            }
            if (ok) lows.add(low);
        }
        if (lows.isEmpty()) return List.of();

        // pass 2: the high bits, checking the most selective structures first
        spreads.sort((x, y) -> Integer.compare(y.limit(), x.limit()));
        int highBits = 48 - lowBits;
        int slices = 1 << Math.min(12, highBits);
        long perSlice = (1L << highBits) / slices;
        long totalSlices = (long) slices * lows.size();
        AtomicLong done = new AtomicLong();
        List<Long> found = Collections.synchronizedList(new ArrayList<>());
        Spread[] sp = spreads.toArray(new Spread[0]);
        Chance[] ch = chances.toArray(new Chance[0]);
        for (long low : lows) {
            IntStream.range(0, slices).parallel().forEach(slice -> {
                if (found.size() >= max) return;
                long[] st = new long[1];
                long start = slice * perSlice, end = start + perSlice;
                outer:
                for (long high = start; high < end; high++) {
                    long seed = (high << lowBits) | low;
                    for (Spread s : sp) {
                        st[0] = ((seed + s.key()) ^ MUL) & MASK48;
                        if (nextInt(st, s.limit()) != s.offsetX()) continue outer;
                        if (nextInt(st, s.limit()) != s.offsetZ()) continue outer;
                    }
                    for (Chance c : ch) {
                        long state = step(((seed + c.key()) ^ MUL) & MASK48);
                        if (!((int) (state >>> 24) * 5.9604645E-8F < c.frequency())) continue outer;
                    }
                    found.add(seed);
                    if (found.size() >= max) return;
                }
                if (progress != null) progress.set(done.incrementAndGet() * 1_000_000L / totalSlices);
            });
            if (found.size() >= max) break;
        }
        List<Long> out = new ArrayList<>(found);
        Collections.sort(out);
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
}
