package de.danoeh.antennapod.playback.service.skip;

public final class SkipOccurrence {
    public final String ruleId;
    public final long startMs;
    public final long endMs;
    public final float score;
    public final SkipRule.Type type;

    public SkipOccurrence(String ruleId, long startMs, long endMs, float score) {
        this(ruleId, startMs, endMs, score, SkipRule.Type.BETWEEN);
    }

    public SkipOccurrence(String ruleId, long startMs, long endMs, float score, SkipRule.Type type) {
        if (ruleId == null || ruleId.isEmpty() || startMs < 0 || endMs <= startMs
                || Float.isNaN(score) || score < 0 || score > 1 || type == null) {
            throw new IllegalArgumentException("Invalid skip occurrence");
        }
        this.ruleId = ruleId;
        this.startMs = startMs;
        this.endMs = endMs;
        this.score = score;
        this.type = type;
    }
}
