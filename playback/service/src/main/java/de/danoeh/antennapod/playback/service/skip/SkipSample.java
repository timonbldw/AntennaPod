package de.danoeh.antennapod.playback.service.skip;

public final class SkipSample {
    public final String id;
    public final SkipMarker marker;
    public final long durationMs;
    public final long markerOffsetMs;
    public final long sourcePositionMs;
    public final AudioFingerprint fingerprint;

    public SkipSample(String id, SkipMarker marker, long durationMs, long markerOffsetMs,
                      AudioFingerprint fingerprint) {
        this(id, marker, durationMs, markerOffsetMs, -1, fingerprint);
    }

    public SkipSample(String id, SkipMarker marker, long durationMs, long markerOffsetMs,
                      long sourcePositionMs, AudioFingerprint fingerprint) {
        if (id == null || id.isEmpty() || marker == null || durationMs < 500 || durationMs > 30_000
                || markerOffsetMs < 0 || markerOffsetMs > durationMs || sourcePositionMs < -1
                || fingerprint == null) {
            throw new IllegalArgumentException("Invalid skip sample");
        }
        this.id = id;
        this.marker = marker;
        this.durationMs = durationMs;
        this.markerOffsetMs = markerOffsetMs;
        this.sourcePositionMs = sourcePositionMs;
        this.fingerprint = fingerprint;
    }
}
