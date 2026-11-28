package dev.erebus.client.util;

/**
 * A damped spring toward a target: unlike {@link Anim} it carries momentum, so with less damping it overshoots a
 * little and settles, which reads as "springy" (menus opening, toasts landing, highlights gliding into place).
 */
public final class Spring {
    private float value;
    private float velocity;
    private float target;
    private final float stiffness;
    private final float damping;

    /** {@code stiffness} sets how fast it moves (~150-400), {@code damping} how much it overshoots (2*sqrt(stiffness) = none). */
    public Spring(float initial, float stiffness, float damping) {
        this.value = initial;
        this.target = initial;
        this.stiffness = stiffness;
        this.damping = damping;
    }

    public float update(float dt) {
        if (!Anim.Animations.enabled()) {
            value = target;
            velocity = 0;
            return value;
        }
        // a few small steps keep it stable at low frame rates
        float left = Math.min(dt, 0.1f);
        while (left > 0) {
            float h = Math.min(left, 1 / 240f);
            float a = stiffness * (target - value) - damping * velocity;
            velocity += a * h;
            value += velocity * h;
            left -= h;
        }
        if (Math.abs(target - value) < 0.0005f && Math.abs(velocity) < 0.005f) {
            value = target;
            velocity = 0;
        }
        return value;
    }

    public void target(float t) {
        target = t;
    }

    public void snap(float v) {
        value = v;
        target = v;
        velocity = 0;
    }

    public float get() {
        return value;
    }

    public float target() {
        return target;
    }

    public boolean settled() {
        return value == target && velocity == 0;
    }
}
