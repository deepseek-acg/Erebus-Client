package dev.erebus.client.seed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongUnaryOperator;

/**
 * Recovers the seed on a server that changed its structure salts: Spigot's {@code seed-village}, {@code seed-desert},
 * {@code seed-igloo}... (and Paper's {@code seed-buriedtreasure} and {@code seed-mineshaft}). Spigot swaps the salt in
 * each structure type's placement for the configured number, so where a type's structures land only says something
 * about {@code seed + salt} for that type, not the seed itself. Pure Java, no Minecraft classes.
 *
 * <p>What those settings never touch:
 * <ul>
 *   <li>the End's pillars ({@code new Random(seed)});</li>
 *   <li>how each structure's first piece came out (its generator is {@code setLargeFeatureSeed(seed, x, z)}, with no
 *       salt);</li>
 *   <li>a pillager outpost's extra 1-in-5 roll (seeded from the seed and its chunk only);</li>
 *   <li>a mineshaft's start room size, and, unless Paper's {@code seed-mineshaft} is set, whether it's there at all.</li>
 * </ul>
 * Those are the anchors. The seed is found in three steps:
 * <ol>
 *   <li>Each type with enough finds is cracked on its own, as if its salt were 0: that gives {@code seed + salt} for
 *       the type (a "group value"), since the salt only shifts the generator's seed.</li>
 *   <li>Candidate seeds are listed from what pins them down most cheaply: the End pillars fix 16 of the seed's low 32
 *       bits; a group value fixes the seed up to the salt, a 32-bit number. With both, each pillar-allowed low half
 *       gives exactly one seed (about 65,536 in all); with one of them, about 2^32.</li>
 *   <li>Each candidate is checked against the anchors (and against other cracked types: their salt must fit in 32
 *       bits too). Of the few left, the server's hashed seed picks the full seed, as usual.</li>
 * </ol>
 * Then each type's salt is worked out from the seed and that type's finds.
 */
public final class SaltSolver {
    private static final long MASK48 = (1L << 48) - 1;
    private static final long MASK32 = 0xFFFFFFFFL;
    /** Most candidate seeds to list and check (about 20 seconds on four cores). */
    public static final long MAX_LIST = 1L << 33;
    /** Most candidates left after the anchors that the hashed seed is still asked to choose from. */
    public static final int MAX_LEFT = 4096;
    /** Longest search to crack one type's group value. */
    public static final long MAX_GROUP_SEARCH = 1L << 32;

    private SaltSolver() {
    }

    /**
     * The finds of one structure type, as {@link SeedSolver.Spread}s with the vanilla salt (which is ignored here),
     * and the setting Spigot reads its salt from.
     */
    public record Group(String name, String setting, int vanillaSalt, List<SeedSolver.Spread> spreads) {
        List<SeedSolver.Clue> unsalted() {
            List<SeedSolver.Clue> out = new ArrayList<>();
            for (SeedSolver.Spread s : spreads) {
                if (s.valid()) out.add(new SeedSolver.Spread(s.spacing(), s.separation(), 0, s.chunkX(), s.chunkZ()));
            }
            return out;
        }
    }

    /** What a type's salt turned out to be. */
    public record Salt(String name, String setting, int vanillaSalt, Integer salt, int finds, String note) {
        public boolean custom() {
            return salt != null && salt != vanillaSalt;
        }
    }

    /**
     * The result. {@code structureSeed} (low 48 bits) and {@code worldSeed} are null when not found; {@code message}
     * says why (or adds something worth knowing). {@code shaftRolls}: whether mineshafts' positions fit the seed
     * (false when only their room sizes did, so the server changed its mineshaft salt too).
     */
    public record Outcome(Long worldSeed, Long structureSeed, List<Salt> salts, boolean shaftRolls, String message, int left) {
    }

    // =====================================================================================================
    // Anchors
    // =====================================================================================================

    /** The anchors, sorted by kind for fast checking. */
    static final class Anchors {
        final SeedSolver.PillarKeys pillars;
        final SeedSolver.Mineshaft[] shafts;
        final SeedSolver.StartRoll[] starts;
        final SeedSolver.OutpostRoll[] outposts;
        final boolean shaftRolls;

        Anchors(List<SeedSolver.Clue> clues, boolean shaftRolls) {
            SeedSolver.PillarKeys pk = null;
            List<SeedSolver.Mineshaft> ms = new ArrayList<>();
            List<SeedSolver.StartRoll> sr = new ArrayList<>();
            List<SeedSolver.OutpostRoll> ro = new ArrayList<>();
            for (SeedSolver.Clue c : clues) {
                if (c instanceof SeedSolver.PillarKeys p && p.keys().length > 0) pk = p;
                else if (c instanceof SeedSolver.Mineshaft m && (shaftRolls || m.sizeX() >= 0 || m.sizeZ() >= 0)) ms.add(m);
                else if (c instanceof SeedSolver.StartRoll r) sr.add(r);
                else if (c instanceof SeedSolver.OutpostRoll o) ro.add(o);
            }
            pillars = pk;
            // the most telling first, so most candidates are dropped after one check
            ms.sort((a, b) -> Double.compare(shaftBits(b, shaftRolls), shaftBits(a, shaftRolls)));
            sr.sort((a, b) -> Double.compare(b.bits(), a.bits()));
            shafts = ms.toArray(new SeedSolver.Mineshaft[0]);
            starts = sr.toArray(new SeedSolver.StartRoll[0]);
            outposts = ro.toArray(new SeedSolver.OutpostRoll[0]);
            this.shaftRolls = shaftRolls;
        }

        /** How much the anchors other than the pillars say about the seed, in bits. */
        double bits() {
            double b = 0;
            for (SeedSolver.Mineshaft m : shafts) b += shaftBits(m, shaftRolls);
            for (SeedSolver.StartRoll r : starts) b += r.bits();
            b += outposts.length * Math.log(5) / Math.log(2);
            return b;
        }

        double pillarBits() {
            return pillars == null ? 0 : pillars.bits();
        }
    }

    private static double shaftBits(SeedSolver.Mineshaft m, boolean rolls) {
        double six = Math.log(6) / Math.log(2);
        return (rolls ? Math.log(250) / Math.log(2) : 0) + (m.sizeX() >= 0 ? six : 0) + (m.sizeZ() >= 0 ? six : 0);
    }

    /** Whether {@code s} (low 48 bits) explains the anchors (the pillars too, unless {@code pillarsDone}). */
    static boolean anchorsFit(long s, Anchors a, boolean pillarsDone) {
        if (a.pillars != null && !pillarsDone && !a.pillars.allows(SeedSolver.pillarKey(s))) return false;
        if (a.shafts.length == 0 && a.starts.length == 0 && a.outposts.length == 0) return true;
        // setLargeFeatureSeed's two multipliers only depend on the seed: worked out once for all the anchors
        long st = SeedSolver.scramble(s);
        st = SeedSolver.step(st);
        int a1 = (int) (st >>> 16);
        st = SeedSolver.step(st);
        int a2 = (int) (st >>> 16);
        long ma = ((long) a1 << 32) + a2;
        st = SeedSolver.step(st);
        int b1 = (int) (st >>> 16);
        st = SeedSolver.step(st);
        int b2 = (int) (st >>> 16);
        long mb = ((long) b1 << 32) + b2;
        long[] r = new long[1];
        for (SeedSolver.Mineshaft m : a.shafts) {
            long s0 = SeedSolver.scramble((long) m.chunkX() * ma ^ (long) m.chunkZ() * mb ^ s);
            long s1 = SeedSolver.step(s0);
            long s2 = SeedSolver.step(s1);
            if (a.shaftRolls && !((((s1 >>> 22) << 27) + (s2 >>> 21)) * (double) 1.110223E-16F < 0.004)) return false;
            if (m.sizeX() < 0 && m.sizeZ() < 0) continue;
            r[0] = s2;
            int sx = 8 + (int) SeedSolver.nextInt(r, 6);
            SeedSolver.nextInt(r, 6);
            int sz = 8 + (int) SeedSolver.nextInt(r, 6);
            if ((m.sizeX() >= 0 && sx != m.sizeX()) || (m.sizeZ() >= 0 && sz != m.sizeZ())) return false;
        }
        for (SeedSolver.StartRoll sr : a.starts) {
            r[0] = SeedSolver.scramble((long) sr.chunkX() * ma ^ (long) sr.chunkZ() * mb ^ s);
            if (SeedSolver.nextInt(r, 4) != sr.rotation()) return false;
            if (sr.poolSize() <= 1 || sr.ranges().length == 0) continue;
            long v = SeedSolver.nextInt(r, sr.poolSize());
            boolean in = false;
            for (int i = 0; i + 1 < sr.ranges().length && !in; i += 2) in = v >= sr.ranges()[i] && v < sr.ranges()[i + 1];
            if (!in) return false;
        }
        for (SeedSolver.OutpostRoll o : a.outposts) {
            if (!SeedSolver.outpostRoll(s, o.chunkX(), o.chunkZ())) return false;
        }
        return true;
    }

    /** Whether {@code u - s} fits in an int: a salt that could turn seed {@code s} into group value {@code u}. */
    static boolean saltFits(long u, long s) {
        long d = ((u - s) & MASK48) << 16 >> 16; // as a signed 48-bit number
        return d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE;
    }

    // =====================================================================================================
    // Solving
    // =====================================================================================================

    /** A group cracked on its own: its possible group values. */
    private record Cracked(Group group, long[] values) {
    }

    /**
     * Why there isn't enough to try yet, or null. An estimate of what {@link #solve} will face: how many candidate
     * seeds it would list (from the pillars and the best type that can be cracked on its own), and how many the
     * anchors would leave. {@link #solve} still has the last word.
     */
    public static String notEnough(List<SeedSolver.Clue> anchors, List<Group> groups) {
        Anchors a = new Anchors(anchors, true);
        boolean pillars = a.pillars != null;
        // roughly how many group values the best crackable type leaves (log2): 34 bits of information leave about 2^12
        double bestU = Double.MAX_VALUE;
        for (Group g : groups) {
            List<SeedSolver.Clue> c = g.unsalted();
            if (c.size() < 3) continue;
            long size = SeedSolver.exactSearchSize(c, 0);
            if (size == 0 || size > MAX_GROUP_SEARCH) continue;
            // the top 13 or so bits of a type's value are only weakly pinned down, whatever the number of finds
            // (positions barely change between values 3 * 2^35 apart for nextInt(24), 13 * 2^35 for nextInt(26)),
            // so a few dozen values are normal: fine with the pillars, too many without
            double info = SeedSolver.information(c);
            double u = Math.max(6, 46 - info);
            if (pillars ? u <= 12 : info >= 70) bestU = Math.min(bestU, pillars ? u : 2);
        }
        double logN;
        if (pillars && bestU != Double.MAX_VALUE) logN = 32 - a.pillarBits() + bestU;
        else if (pillars) logN = 48 - a.pillarBits();
        else if (bestU != Double.MAX_VALUE) logN = 32 + bestU;
        else return "read the End pillars: with each kind of structure on its own salt, they're what ties the structures to the seed";
        if (logN > 33) return "too many seeds to check: read the End pillars, or find more of one kind of structure";
        double left = logN - a.bits();
        if (left > 12) return "find a few more mineshafts, villages, igloos or outposts (they're checked against the seed itself; about "
                + (int) Math.ceil(left - 12) + " more bits needed, a mineshaft with its room size gives 13, an igloo or village 2 to 4)";
        return null;
    }

    /**
     * Finds the seed and each type's salt. {@code anchors}: the clues salts don't touch (End pillars, start rolls,
     * outpost rolls, mineshafts). {@code hashedSeed}: what the server sent (0 if nothing), checked with {@code hash}.
     */
    public static Outcome solve(List<SeedSolver.Clue> anchors, List<Group> groups, long hashedSeed, LongUnaryOperator hash, AtomicLong progress) {
        // 1. the types with enough finds, cracked on their own
        Anchors withRolls = new Anchors(anchors, true);
        int maxValues = withRolls.pillars != null ? 4096 : 4;
        List<Cracked> cracked = new ArrayList<>();
        for (Group g : groups) {
            List<SeedSolver.Clue> c = g.unsalted();
            if (c.size() < 3) continue;
            long size = SeedSolver.exactSearchSize(c, 0);
            if (size == 0 || size > MAX_GROUP_SEARCH) continue;
            List<Long> values = SeedSolver.structureSeeds(c, maxValues + 1, progress);
            if (values.isEmpty() || values.size() > maxValues) continue;
            cracked.add(new Cracked(g, values.stream().mapToLong(Long::longValue).toArray()));
        }
        cracked.sort((x, y) -> Integer.compare(x.values().length, y.values().length));

        // 2 and 3: list and check candidates, with the mineshafts' rolls; then with only their sizes (Paper's seed-mineshaft)
        String why = null;
        for (boolean rolls : new boolean[]{true, false}) {
            Anchors a = rolls ? withRolls : new Anchors(anchors, false);
            if (!rolls && withRolls.shafts.length == 0) break;
            Listed listed = list(a, cracked, progress);
            if (listed.why() != null) {
                why = listed.why();
                if (listed.left() == null) return new Outcome(null, null, List.of(), rolls, why, 0);
            }
            List<Long> left = listed.left();
            if (left == null || left.isEmpty()) continue;
            if (left.size() > MAX_LEFT) return new Outcome(null, null, List.of(), rolls,
                    left.size() + "+ seeds still fit; find a few more mineshafts, villages, igloos or outposts.", left.size());
            Long world = null, structure = null;
            if (hashedSeed != 0) {
                List<SeedSolver.Candidate> cands = new ArrayList<>();
                for (long s : left) cands.add(new SeedSolver.Candidate(s, 0));
                SeedSolver.Candidate w = SeedSolver.firstWorldSeed(cands, hashedSeed, hash);
                if (w != null) {
                    world = w.seed();
                    structure = world & MASK48;
                }
            }
            String msg = null;
            if (structure == null) {
                if (left.size() == 1) structure = left.get(0);
                msg = (hashedSeed == 0 ? "The server doesn't send a hashed seed"
                        : "None of the " + left.size() + " seeds that fit matches the server's hashed seed (it may send a fake one)")
                        + (left.size() == 1 ? ", so only the structure seed (low 48 bits) is known." : "; find a few more structures.");
            }
            List<Salt> salts = structure == null ? List.of() : salts(structure, groups, cracked);
            return new Outcome(world, structure, salts, rolls, msg, left.size());
        }
        return new Outcome(null, null, List.of(), true, why != null ? why : "No seed fits, even with every structure type on its own salt. "
                + "Some structures may have been misread, or this server's world generation isn't vanilla.", 0);
    }

    private record Listed(List<Long> left, String why) {
    }

    /** Lists the candidates the cheapest way available and keeps those the anchors allow. */
    private static Listed list(Anchors a, List<Cracked> cracked, AtomicLong progress) {
        List<Long> pillarLows = null;
        if (a.pillars != null) {
            SeedSolver.Lows lp = SeedSolver.lowParts(List.of(), a.pillars, 0);
            pillarLows = lp == null ? List.of() : lp.lows();
            if (pillarLows.isEmpty()) return new Listed(List.of(), null);
        }
        Cracked base = cracked.isEmpty() ? null : cracked.get(0);
        List<Cracked> others = base == null ? List.of() : cracked.subList(1, cracked.size());
        long n;
        if (pillarLows != null && base != null) n = (long) pillarLows.size() * base.values().length;
        else if (pillarLows != null) n = (long) pillarLows.size() << 16;
        else if (base != null) n = (long) base.values().length << 32;
        else return new Listed(null, "Not enough yet: read the End pillars. With each kind of structure on its own salt, they're what ties the "
                + "structures to the seed.");
        if (n > MAX_LIST) return new Listed(null, "Not enough yet: too many seeds to check (2^" + (int) Math.round(Math.log(n) / Math.log(2))
                + "). Read the End pillars, or find more of one kind of structure.");
        // expected survivors, roughly: not worth listing billions of seeds to be left with thousands
        double bits = a.bits() + others.size() * 16;
        if (Math.log(n) / Math.log(2) - bits > 14) return new Listed(null, "Not enough yet: find a few more mineshafts, villages, igloos or "
                + "outposts (they're checked against the seed itself).");

        List<Long> left = Collections.synchronizedList(new ArrayList<>());
        AtomicLong done = new AtomicLong();
        final List<Long> lows = pillarLows;
        if (lows != null && base != null) {
            long[] vals = base.values();
            java.util.stream.IntStream.range(0, lows.size()).parallel().forEach(i -> {
                if (left.size() > MAX_LEFT) return;
                long low = lows.get(i);
                for (long u : vals) {
                    int salt = (int) ((u - low) & MASK32);
                    long s = (u - salt) & MASK48;
                    if (anchorsFit(s, a, true) && othersFit(s, others)) left.add(s);
                }
                if (progress != null && (i & 1023) == 0) progress.set(done.addAndGet(1024) * 1_000_000L / lows.size());
            });
        } else if (lows != null) {
            java.util.stream.IntStream.range(0, lows.size()).parallel().forEach(i -> {
                if (left.size() > MAX_LEFT) return;
                long low = lows.get(i);
                for (long high = 0; high < (1L << 16); high++) {
                    long s = (high << 32) | low;
                    if (anchorsFit(s, a, true)) left.add(s);
                }
                if (progress != null) progress.set(done.incrementAndGet() * 1_000_000L / lows.size());
            });
        } else {
            long[] vals = base.values();
            int slices = 1 << 12;
            long total = (long) vals.length * slices;
            java.util.stream.LongStream.range(0, total).parallel().forEach(item -> {
                if (left.size() > MAX_LEFT) return;
                long u = vals[(int) (item / slices)];
                long from = (item % slices) << 20;
                for (long k = from; k < from + (1L << 20); k++) {
                    int salt = (int) k;
                    long s = (u - salt) & MASK48;
                    if (anchorsFit(s, a, false) && othersFit(s, others)) left.add(s);
                }
                if (progress != null) progress.set(done.incrementAndGet() * 1_000_000L / total);
            });
        }
        List<Long> out = new ArrayList<>(new java.util.TreeSet<>(left));
        return new Listed(out, null);
    }

    /** Every other cracked type has a group value its salt (32 bits) can reach from this seed. */
    private static boolean othersFit(long s, List<Cracked> others) {
        for (Cracked c : others) {
            boolean any = false;
            for (long u : c.values()) {
                if (saltFits(u, s)) {
                    any = true;
                    break;
                }
            }
            if (!any) return false;
        }
        return true;
    }

    /** Each type's salt, now that the seed is known. */
    private static List<Salt> salts(long seed, List<Group> groups, List<Cracked> cracked) {
        Map<String, long[]> values = new LinkedHashMap<>();
        for (Cracked c : cracked) values.put(c.group().name(), c.values());
        List<Salt> out = new ArrayList<>();
        for (Group g : groups) {
            List<SeedSolver.Clue> c = g.unsalted();
            if (c.isEmpty()) continue;
            List<Integer> found = new ArrayList<>();
            long[] vals = values.get(g.name());
            if (vals != null) {
                for (long u : vals) if (saltFits(u, seed)) found.add((int) (((u - seed) & MASK48) << 16 >> 16));
            } else {
                found = saltsFor(seed, c, 2);
            }
            boolean vanillaFits = SeedSolver.fits((seed + g.vanillaSalt()) & MASK48, c);
            String note = null;
            Integer salt = null;
            if (found.size() == 1) salt = found.get(0);
            else if (found.isEmpty()) note = "its finds don't fit any salt with this seed (probably misread)";
            else if (vanillaFits) {
                salt = g.vanillaSalt(); // with few finds many salts fit; the default is by far the likeliest of them
                note = "probably the default (only " + c.size() + " found)";
            } else note = "not enough found to tell (" + c.size() + ")";
            out.add(new Salt(g.name(), g.setting(), g.vanillaSalt(), salt, c.size(), note));
        }
        return out;
    }

    /** The salt of this kind of structure with this seed, if its finds pin it down to one; null otherwise. */
    public static Integer saltOf(long seed, Group g) {
        List<SeedSolver.Clue> c = g.unsalted();
        if (c.isEmpty()) return null;
        List<Integer> l = saltsFor(seed & MASK48, c, 2);
        return l.size() == 1 ? l.get(0) : null;
    }

    /** The salts (up to {@code max}) that make {@code seed} explain these finds. */
    static List<Integer> saltsFor(long seed, List<SeedSolver.Clue> unsalted, int max) {
        List<SeedSolver.Spread> sp = new ArrayList<>();
        for (SeedSolver.Clue c : unsalted) sp.add((SeedSolver.Spread) c);
        List<Integer> out = new ArrayList<>();
        SeedSolver.Lows lp = SeedSolver.lowParts(sp, null, 0);
        if (lp == null) return out;
        int lb = lp.bits();
        if (lb >= 32) return out;
        long m = (1L << lb) - 1;
        for (long ulow : lp.lows()) {
            long slow = (ulow - seed) & m;
            for (long k = 0; k < (1L << (32 - lb)); k++) {
                int salt = (int) (slow | (k << lb));
                if (SeedSolver.fits((seed + salt) & MASK48, unsalted)) {
                    out.add(salt);
                    if (out.size() >= max) return out;
                }
            }
        }
        return out;
    }
}
