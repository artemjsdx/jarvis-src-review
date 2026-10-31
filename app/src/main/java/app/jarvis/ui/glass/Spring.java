package app.jarvis.ui.glass;

/**
 * One-dimensional damped spring, semi-implicit Euler with fixed sub-steps. Pure math (no Android) so it is unit-tested on the JVM.
 * Damping ratio 0.85 is near-critical: it reaches the target in about 0.35 s and overshoots by well under 1 %, so the drop
 * settles without visible ringing.
 */
public final class Spring {
    public static final float DAMPING = 0.85f;
    /** Natural frequency (rad/s). 14 rad/s gives a ~0.35 s settle. */
    public static final float OMEGA = 14f;
    /** Below these the spring snaps to its target (px, px/s). */
    static final float REST_DISTANCE = 0.25f, REST_SPEED = 2f;
    private static final float MAX_SUBSTEP = 1f / 240f;
    private static final float MAX_FRAME = 0.1f;

    private final float omega;
    private float value, velocity, target;

    public Spring() { this(OMEGA); }

    public Spring(float omega) { this.omega = omega; }

    public void snap(float v) { value = target = v; velocity = 0f; }

    public void setTarget(float t) { target = t; }

    public float value() { return value; }

    public float velocity() { return velocity; }

    public boolean atRest() {
        return Math.abs(value - target) < REST_DISTANCE && Math.abs(velocity) < REST_SPEED;
    }

    /** Advances the spring by dtSeconds (a stall longer than 100 ms is clamped so the spring never explodes). Returns true while moving. */
    public boolean step(float dtSeconds) {
        float remaining = Math.min(Math.max(dtSeconds, 0f), MAX_FRAME);
        while (remaining > 0f) {
            float h = Math.min(remaining, MAX_SUBSTEP);
            float acceleration = -omega * omega * (value - target) - 2f * DAMPING * omega * velocity;
            velocity += acceleration * h;
            value += velocity * h;
            remaining -= h;
        }
        if (atRest()) { value = target; velocity = 0f; return false; }
        return true;
    }
}
