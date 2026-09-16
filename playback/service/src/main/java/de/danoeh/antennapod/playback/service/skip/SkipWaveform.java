package de.danoeh.antennapod.playback.service.skip;

import java.util.Arrays;

public final class SkipWaveform {
    public final long startMs;
    public final long endMs;
    public final float[] minimum;
    public final float[] maximum;

    public SkipWaveform(long startMs, long endMs, float[] minimum, float[] maximum) {
        if (startMs < 0 || endMs <= startMs || minimum == null || maximum == null
                || minimum.length == 0 || minimum.length != maximum.length) {
            throw new IllegalArgumentException("Invalid waveform");
        }
        this.startMs = startMs;
        this.endMs = endMs;
        this.minimum = Arrays.copyOf(minimum, minimum.length);
        this.maximum = Arrays.copyOf(maximum, maximum.length);
    }
}
