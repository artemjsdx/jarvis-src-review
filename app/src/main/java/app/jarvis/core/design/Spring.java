package app.jarvis.core.design;

/**
 * Damped spring, unit step response: a mass pulled to 1 from rest with stiffness omega^2 and damping ratio zeta.
 * Used as an easing curve: progress(p) is the response at time p*settle, rescaled so that progress(1) == 1.
 */
public final class Spring {
    private final double zeta, omega, omegaD;

    /** @param zeta damping ratio in (0,1): below 1 the spring overshoots, the lower the more bounce. @param omega undamped angular frequency, rad/s. */
    public Spring(double zeta, double omega) {
        if (zeta <= 0 || zeta >= 1) throw new IllegalArgumentException("zeta must be in (0,1): " + zeta);
        if (omega <= 0) throw new IllegalArgumentException("omega must be positive: " + omega);
        this.zeta = zeta;
        this.omega = omega;
        this.omegaD = omega * Math.sqrt(1 - zeta * zeta);
    }

    /** Position at time t (seconds) for a spring released from 0 towards 1. */
    public double response(double t) {
        if (t <= 0) return 0;
        double decay = Math.exp(-zeta * omega * t);
        return 1 - decay * (Math.cos(omegaD * t) + (zeta * omega / omegaD) * Math.sin(omegaD * t));
    }

    /** Time (s) after which the response stays within 1% of the target. */
    public double settleTime() { return -Math.log(0.01) / (zeta * omega); }

    /** Eased progress for linear progress p in [0,1] over the settle time; exactly 0 at p=0 and 1 at p=1. */
    public double progress(double p) {
        if (p <= 0) return 0;
        if (p >= 1) return 1;
        double t = settleTime();
        return response(p * t) / response(t);
    }

    /** Largest overshoot above the target, as a fraction (0.0 for critical or over-damped springs). */
    public double overshoot() { return Math.exp(-Math.PI * zeta / Math.sqrt(1 - zeta * zeta)); }
}
