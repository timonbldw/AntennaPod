package de.danoeh.antennapod.playback.service.skip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public final class SkipRule {
    public enum Type {
        BETWEEN,
        FIXED,
        FINISH
    }

    public enum MissingEndBehavior {
        UNTOUCHED,
        FIXED
    }

    public final String id;
    public final String name;
    public final boolean enabled;
    public final Type type;
    public final long minDurationMs;
    public final long maxDurationMs;
    public final MissingEndBehavior missingEndBehavior;
    public final long missingEndDurationMs;
    public final long fixedDurationMs;
    public final long firstRegionMs;
    public final long lastRegionMs;
    public final List<SkipSample> samples;

    public SkipRule(String id, String name, boolean enabled, Type type, long minDurationMs,
                    long maxDurationMs, MissingEndBehavior missingEndBehavior,
                    long missingEndDurationMs, long firstRegionMs, long lastRegionMs,
                    List<SkipSample> samples) {
        this(id, name, enabled, type, minDurationMs, maxDurationMs, missingEndBehavior,
                missingEndDurationMs, missingEndDurationMs, firstRegionMs, lastRegionMs, samples);
    }

    public SkipRule(String id, String name, boolean enabled, Type type, long minDurationMs,
                    long maxDurationMs, MissingEndBehavior missingEndBehavior,
                    long missingEndDurationMs, long fixedDurationMs, long firstRegionMs,
                    long lastRegionMs, List<SkipSample> samples) {
        if (id == null || id.isEmpty() || name == null || type == null
                || missingEndBehavior == null || minDurationMs < 0 || maxDurationMs < 0
                || missingEndDurationMs < 0 || fixedDurationMs < 0 || firstRegionMs < 0
                || lastRegionMs < 0
                || (maxDurationMs > 0 && minDurationMs > maxDurationMs)) {
            throw new IllegalArgumentException("Invalid skip rule");
        }
        this.id = id;
        this.name = name;
        this.enabled = enabled;
        this.type = type;
        this.minDurationMs = minDurationMs;
        this.maxDurationMs = maxDurationMs;
        this.missingEndBehavior = missingEndBehavior;
        this.missingEndDurationMs = missingEndDurationMs;
        this.fixedDurationMs = fixedDurationMs;
        this.firstRegionMs = firstRegionMs;
        this.lastRegionMs = lastRegionMs;
        this.samples = Collections.unmodifiableList(new ArrayList<>(samples == null
                ? Collections.emptyList() : samples));
    }

    public SkipRule withSamples(List<SkipSample> newSamples) {
        return new SkipRule(id, name, enabled, type, minDurationMs, maxDurationMs,
                missingEndBehavior, missingEndDurationMs, fixedDurationMs, firstRegionMs,
                lastRegionMs, newSamples);
    }

    public void validate() {
        if (name.trim().isEmpty()) {
            throw new IllegalArgumentException("Rule name is required");
        }
        boolean hasStart = false;
        boolean hasEnd = false;
        Set<String> sampleIds = new HashSet<>();
        for (SkipSample sample : samples) {
            if (sample == null) {
                throw new IllegalArgumentException("Rule contains an invalid sample");
            }
            if (!sampleIds.add(sample.id)) {
                throw new IllegalArgumentException("Sample IDs must be unique within a rule");
            }
            AudioFingerprint fingerprint = sample.fingerprint;
            if (fingerprint.sampleRate != SkipFingerprint.SAMPLE_RATE
                    || fingerprint.frameMs != SkipFingerprint.FRAME_MS
                    || fingerprint.hopMs != SkipFingerprint.HOP_MS
                    || Math.abs(fingerprint.durationMs() - sample.durationMs) > fingerprint.hopMs) {
                throw new IllegalArgumentException("Sample fingerprint format or duration is invalid");
            }
            if (!SkipFingerprint.isUsable(fingerprint)) {
                throw new IllegalArgumentException("Sample fingerprint contains too little audible audio");
            }
            hasStart |= sample.marker == SkipMarker.START;
            hasEnd |= sample.marker == SkipMarker.END;
        }
        if (!hasStart) {
            throw new IllegalArgumentException("A start sample is required");
        }
        if (type == Type.BETWEEN && !hasEnd) {
            throw new IllegalArgumentException("An end sample is required for a between rule");
        }
        if (type == Type.FIXED && fixedDurationMs <= 0) {
            throw new IllegalArgumentException("Fixed skip duration must be positive");
        }
        if (type == Type.BETWEEN && missingEndBehavior == MissingEndBehavior.FIXED
                && missingEndDurationMs <= 0) {
            throw new IllegalArgumentException("Missing-end fallback duration must be positive");
        }
    }

    public SkipRule withEnabled(boolean newEnabled) {
        return new SkipRule(id, name, newEnabled, type, minDurationMs, maxDurationMs,
                missingEndBehavior, missingEndDurationMs, fixedDurationMs, firstRegionMs,
                lastRegionMs, samples);
    }
}
