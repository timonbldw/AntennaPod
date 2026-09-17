package de.danoeh.antennapod.playback.service.skip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SkipAnalysisSnapshot {
    public final String feedId;
    public final String episodeId;
    public final SkipAnalysisStatus status;
    public final long rulesRevision;
    public final String sourceIdentity;
    public final long durationMs;
    public final List<SkipCoverage> coverage;
    public final List<SkipOccurrence> occurrences;
    public final List<SkipDetection> detections;
    public final String error;
    public final long updatedAtMs;

    public SkipAnalysisSnapshot(String feedId, String episodeId, SkipAnalysisStatus status,
                                long rulesRevision, String sourceIdentity, long durationMs,
                                List<SkipCoverage> coverage, List<SkipOccurrence> occurrences,
                                String error, long updatedAtMs) {
        this(feedId, episodeId, status, rulesRevision, sourceIdentity, durationMs, coverage,
                occurrences, Collections.emptyList(), error, updatedAtMs);
    }

    public SkipAnalysisSnapshot(String feedId, String episodeId, SkipAnalysisStatus status,
                                long rulesRevision, String sourceIdentity, long durationMs,
                                List<SkipCoverage> coverage, List<SkipOccurrence> occurrences,
                                List<SkipDetection> detections, String error, long updatedAtMs) {
        this.feedId = feedId;
        this.episodeId = episodeId;
        this.status = status;
        this.rulesRevision = rulesRevision;
        this.sourceIdentity = sourceIdentity;
        this.durationMs = durationMs;
        this.coverage = Collections.unmodifiableList(new ArrayList<>(coverage));
        this.occurrences = Collections.unmodifiableList(new ArrayList<>(occurrences));
        this.detections = Collections.unmodifiableList(new ArrayList<>(detections));
        this.error = error;
        this.updatedAtMs = updatedAtMs;
    }

    public static SkipAnalysisSnapshot notAnalyzed(String feedId, String episodeId) {
        return new SkipAnalysisSnapshot(feedId, episodeId, SkipAnalysisStatus.NOT_ANALYZED,
                0, null, 0, Collections.emptyList(), Collections.emptyList(), null,
                System.currentTimeMillis());
    }
}
