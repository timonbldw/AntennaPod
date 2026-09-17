package de.danoeh.antennapod.playback.service.skip;

public final class SkipDetection {
    public enum Reason {
        PENDING,
        MISSING_END,
        MISSING_START,
        TOO_SHORT,
        TOO_LONG,
        REPLACED_START,
        FALLBACK
    }

    public final String ruleId;
    public final SkipMarker marker;
    public final long timeMs;
    public final long endMs;
    public final Reason reason;

    public SkipDetection(String ruleId, SkipMarker marker, long timeMs, long endMs, Reason reason) {
        if (ruleId == null || ruleId.isEmpty() || marker == null || timeMs < 0 || endMs < timeMs
                || reason == null || reason != Reason.FALLBACK && endMs != timeMs) {
            throw new IllegalArgumentException("Invalid skip detection");
        }
        this.ruleId = ruleId;
        this.marker = marker;
        this.timeMs = timeMs;
        this.endMs = endMs;
        this.reason = reason;
    }
}
