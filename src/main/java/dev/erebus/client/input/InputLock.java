package dev.erebus.client.input;

/**
 * Who is driving the simulated input right now. The camera, the hotbar and the use and attack keys are one set
 * of hands: two modules clicking at once would swap slots under each other and turn the camera two ways. A module
 * that starts a sequence of clicks takes the lock, others wait for it, and a module the player triggered by hand
 * may take it over.
 */
public final class InputLock {
    private static Object owner;

    private InputLock() {
    }

    /** Takes the lock if it's free (or already ours). */
    public static boolean tryClaim(Object who) {
        if (owner != null && owner != who) return false;
        owner = who;
        return true;
    }

    /** Takes the lock whoever holds it; the previous holder sees {@link #lost(Object)} and drops what it was doing. */
    public static void take(Object who) {
        owner = who;
    }

    public static void release(Object who) {
        if (owner == who) owner = null;
    }

    /** True if someone else holds the lock. */
    public static boolean heldByOther(Object who) {
        return owner != null && owner != who;
    }

    /** True if {@code who} thinks it holds the lock but someone else has it now. */
    public static boolean lost(Object who) {
        return owner != who;
    }
}
