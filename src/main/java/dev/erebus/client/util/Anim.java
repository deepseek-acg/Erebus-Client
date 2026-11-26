package dev.erebus.client.util;

/**
 * Frame-rate independent exponential easing toward a target value.
 * Call {@link #update(float)} once per frame with the frame time in seconds.
 */
public final class Anim {
    private float value;
    private float target;
    private final float speed;

    public Anim(float initial, float speed) {
        this.value = initial;
        this.target = initial;
        this.speed = speed;
    }

    public Anim(float initial) {
        this(initial, 18f);
    }

    public float update(float dt) {
        if (!Animations.enabled()) {
            value = target;
            return value;
        }
        float k = 1f - (float) Math.exp(-speed * dt);
        value += (target - value) * k;
        if (Math.abs(target - value) < 0.0005f) value = target;
        return value;
    }

    public void target(float t) {
        this.target = t;
    }

    public void snap(float v) {
        this.value = v;
        this.target = v;
    }

    public float get() {
        return value;
    }

    public float target() {
        return target;
    }

    /** Global switch, driven by the theme's "Animations" option. */
    public static final class Animations {
        private static boolean enabled = true;

        private Animations() {
        }

        public static boolean enabled() {
            return enabled;
        }

        public static void set(boolean on) {
            enabled = on;
        }
    }
}
