package app.jarvis.ui.glass;

import java.util.Arrays;

/**
 * Decides when AUTO glass quality must step down. Each sample is the CPU cost (ms) of the glass work done in one frame: snapshot
 * capture plus blur/lens recording. Samples are taken for 2 s after the first glass frame; if the 95th-percentile cost is above
 * 14 ms, the level steps down and a new window starts. Frame intervals are not used: at 60 Hz a smooth frame already lasts 16.7 ms.
 * Pure Java so the decision is unit-tested on the JVM.
 */
public final class FrameGovernor {
    public static final float BUDGET_MS = 14f;
    public static final double WINDOW_MS = 2000;
    static final int MIN_SAMPLES = 20;
    static final int MAX_SAMPLES = 512;

    private final float[] costs = new float[MAX_SAMPLES];
    private final float[] scratch = new float[MAX_SAMPLES];
    private int count;
    private double windowStart = -1;
    private boolean finished;

    /** Feed one glass frame: monotonic time (ms) and the glass CPU cost of that frame (ms). @return true when the level should step down. */
    public boolean onFrame(double nowMs, double costMs) {
        if (finished) return false;
        if (windowStart < 0) windowStart = nowMs;
        if (count < MAX_SAMPLES) costs[count++] = (float) costMs;
        if (nowMs - windowStart < WINDOW_MS) return false;
        boolean slow = count >= MIN_SAMPLES && p95(costs, count, scratch) > BUDGET_MS;
        if (slow) {
            windowStart = -1;
            count = 0;
        } else {
            finished = true;
        }
        return slow;
    }

    /** Nearest-rank 95th percentile of v[0..n). scratch must hold at least n floats. */
    public static float p95(float[] v, int n, float[] scratch) {
        if (n <= 0) return 0f;
        System.arraycopy(v, 0, scratch, 0, n);
        Arrays.sort(scratch, 0, n);
        int rank = (int) Math.ceil(0.95 * n) - 1;
        return scratch[Math.max(0, Math.min(n - 1, rank))];
    }
}
