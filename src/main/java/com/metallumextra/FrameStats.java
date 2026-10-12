package com.metallumextra;

/**
 * Frame intervals in milliseconds, kept as a histogram: exact to 0.05 ms up to 100 ms, and a fixed amount of memory however many frames go in. Used by the
 * run log for FPS and percentiles per window and per settings combination.
 */
public final class FrameStats {
    static final double BIN_MS = 0.05;
    static final int BINS = 2000;

    private final long[] bins = new long[BINS];
    private long count;
    private long overflow;
    private double sumMs;
    private double maxMs;

    /** Records one frame that took {@code ms} from the previous one. */
    public void add(final double ms) {
        count++;
        sumMs += ms;
        if (ms > maxMs) {
            maxMs = ms;
        }
        int bin = (int) (ms / BIN_MS);
        if (bin >= BINS) {
            overflow++;
        } else if (bin >= 0) {
            bins[bin]++;
        }
    }

    public long count() {
        return count;
    }

    /** Frames per second over the frames recorded (frames divided by their total time). */
    public double fps() {
        return sumMs <= 0 ? 0 : count * 1000.0 / sumMs;
    }

    public double meanMs() {
        return count == 0 ? 0 : sumMs / count;
    }

    public double maxMs() {
        return maxMs;
    }

    /** The frame time that {@code fraction} (0 to 1) of the frames are at or under: 0.5 is the median, 0.99 the 99th percentile. */
    public double percentileMs(final double fraction) {
        if (count == 0) {
            return 0;
        }
        long target = Math.max(1, (long) Math.ceil(fraction * count));
        long seen = 0;
        for (int i = 0; i < BINS; i++) {
            seen += bins[i];
            if (seen >= target) {
                return (i + 1) * BIN_MS;
            }
        }
        return maxMs;
    }

    /** Frames per second at the 99th-percentile frame time: the usual "1% low". */
    public double onePercentLowFps() {
        double p99 = percentileMs(0.99);
        return p99 <= 0 ? 0 : 1000.0 / p99;
    }

    public void reset() {
        java.util.Arrays.fill(bins, 0);
        count = 0;
        overflow = 0;
        sumMs = 0;
        maxMs = 0;
    }
}
