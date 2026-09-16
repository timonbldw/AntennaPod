package de.danoeh.antennapod.playback.service.skip;

import java.util.Arrays;

public final class AudioFingerprint {
    public final int sampleRate;
    public final int frameMs;
    public final int hopMs;
    public final int[] hashes;

    public AudioFingerprint(int sampleRate, int frameMs, int hopMs, int[] hashes) {
        if (sampleRate <= 0 || frameMs <= 0 || hopMs <= 0 || hashes == null || hashes.length == 0
                || (long) frameMs + (long) (hashes.length - 1) * hopMs > 120_000) {
            throw new IllegalArgumentException("Invalid audio fingerprint");
        }
        this.sampleRate = sampleRate;
        this.frameMs = frameMs;
        this.hopMs = hopMs;
        this.hashes = Arrays.copyOf(hashes, hashes.length);
    }

    public int durationMs() {
        return frameMs + Math.max(0, hashes.length - 1) * hopMs;
    }

    public int frameCount() {
        return hashes.length;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof AudioFingerprint)) {
            return false;
        }
        AudioFingerprint other = (AudioFingerprint) object;
        return sampleRate == other.sampleRate && frameMs == other.frameMs && hopMs == other.hopMs
                && Arrays.equals(hashes, other.hashes);
    }

    @Override
    public int hashCode() {
        int result = sampleRate;
        result = 31 * result + frameMs;
        result = 31 * result + hopMs;
        return 31 * result + Arrays.hashCode(hashes);
    }
}
