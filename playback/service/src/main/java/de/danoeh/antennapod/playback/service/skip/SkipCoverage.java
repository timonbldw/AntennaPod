package de.danoeh.antennapod.playback.service.skip;

public final class SkipCoverage {
    public final long startMs;
    public final long endMs;

    public SkipCoverage(long startMs, long endMs) {
        if (startMs < 0 || endMs <= startMs) {
            throw new IllegalArgumentException("Invalid analysis coverage");
        }
        this.startMs = startMs;
        this.endMs = endMs;
    }

    public boolean contains(long start, long end) {
        return start >= startMs && end <= endMs;
    }
}
