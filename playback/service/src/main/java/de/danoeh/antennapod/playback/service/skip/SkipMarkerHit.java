package de.danoeh.antennapod.playback.service.skip;

public final class SkipMarkerHit {
    public final String ruleId;
    public final String sampleId;
    public final SkipMarker marker;
    public final long timeMs;
    public final float score;

    public SkipMarkerHit(String ruleId, String sampleId, SkipMarker marker, long timeMs, float score) {
        if (ruleId == null || sampleId == null || marker == null || timeMs < 0
                || Float.isNaN(score) || score < 0 || score > 1) {
            throw new IllegalArgumentException("Invalid marker hit");
        }
        this.ruleId = ruleId;
        this.sampleId = sampleId;
        this.marker = marker;
        this.timeMs = timeMs;
        this.score = score;
    }
}
