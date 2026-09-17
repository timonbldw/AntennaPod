package de.danoeh.antennapod.playback.service.skip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SkipResolver {
    private static final long SAME_OCCURRENCE_MS = 256;

    private SkipResolver() {
    }

    public static List<SkipOccurrence> resolve(SkipRule rule, List<SkipMarkerHit> hits,
                                               long episodeDurationMs, boolean fullWindowAnalyzed,
                                               long analyzedUntilMs) {
        return resolve(rule, hits, episodeDurationMs,
                fullWindowAnalyzed ? Collections.singletonList(new SkipCoverage(0, analyzedUntilMs))
                        : Collections.emptyList());
    }

    public static List<SkipOccurrence> resolve(SkipRule rule, List<SkipMarkerHit> hits,
                                               long episodeDurationMs,
                                               List<SkipCoverage> coverage) {
        return resolveResult(rule, hits, episodeDurationMs, coverage).occurrences;
    }

    public static List<SkipDetection> resolveDetections(SkipRule rule, List<SkipMarkerHit> hits,
                                                        long episodeDurationMs,
                                                        boolean fullWindowAnalyzed,
                                                        long analyzedUntilMs) {
        return resolveDetections(rule, hits, episodeDurationMs,
                fullWindowAnalyzed ? Collections.singletonList(new SkipCoverage(0, analyzedUntilMs))
                        : Collections.emptyList());
    }

    public static List<SkipDetection> resolveDetections(SkipRule rule, List<SkipMarkerHit> hits,
                                                        long episodeDurationMs,
                                                        List<SkipCoverage> coverage) {
        return resolveResult(rule, hits, episodeDurationMs, coverage).detections;
    }

    static Resolution resolveResult(SkipRule rule, List<SkipMarkerHit> hits, long episodeDurationMs,
                                    List<SkipCoverage> coverage) {
        if (rule == null || !rule.enabled || episodeDurationMs <= 0 || hits == null) {
            return new Resolution(Collections.emptyList(), Collections.emptyList());
        }
        List<SkipOccurrence> occurrences = new ArrayList<>();
        List<SkipDetection> detections = new ArrayList<>();
        if (rule.type != SkipRule.Type.BETWEEN) {
            for (SkipMarkerHit start : distinctHits(rule, hits, SkipMarker.START, episodeDurationMs)) {
                if (rule.type == SkipRule.Type.FINISH) {
                    addOccurrence(occurrences, rule, start.timeMs, episodeDurationMs, start.score);
                } else if (rule.fixedDurationMs > 0) {
                    long end = start.timeMs + Math.min(rule.fixedDurationMs,
                            episodeDurationMs - start.timeMs);
                    addOccurrence(occurrences, rule, start.timeMs, end, start.score);
                }
            }
            return new Resolution(deduplicateOccurrences(occurrences), detections);
        }

        SkipMarkerHit openStart = null;
        for (HitOccurrence hit : hitOccurrences(rule, hits, episodeDurationMs)) {
            if (openStart == null) {
                if (hit.start != null) {
                    openStart = hit.start;
                } else {
                    addMissingStart(detections, rule, hit.end, episodeDurationMs, coverage);
                }
                continue;
            }
            if (hit.end != null && hit.end.timeMs > openStart.timeMs) {
                long endMs = sampleEnd(rule, hit.end);
                long intervalMs = endMs - openStart.timeMs;
                long windowEnd = relevantWindowEnd(rule, openStart.timeMs, episodeDurationMs);
                boolean maximumEndsWindow = rule.maxDurationMs > 0
                        && windowEnd == openStart.timeMs + Math.min(rule.maxDurationMs,
                        episodeDurationMs - openStart.timeMs);
                if (endMs > windowEnd && !maximumEndsWindow) {
                    addMissingEnd(occurrences, detections, rule, openStart, episodeDurationMs, coverage);
                    addMissingStart(detections, rule, hit.end, episodeDurationMs, coverage);
                    openStart = hit.start;
                    continue;
                }
                if (intervalMs < rule.minDurationMs) {
                    if (hit.start == null) {
                        SkipDetection.Reason reason = isCovered(coverage, openStart.timeMs, hit.end.timeMs)
                                ? SkipDetection.Reason.TOO_SHORT : SkipDetection.Reason.PENDING;
                        detections.add(detection(rule, hit.end, reason));
                    }
                    continue;
                } else if (rule.maxDurationMs > 0 && intervalMs > rule.maxDurationMs) {
                    if (isCovered(coverage, openStart.timeMs,
                            relevantWindowEnd(rule, openStart.timeMs, episodeDurationMs))) {
                        boolean fallback = rule.missingEndBehavior == SkipRule.MissingEndBehavior.FIXED
                                && rule.missingEndDurationMs > 0;
                        if (!fallback) {
                            detections.add(detection(rule, openStart, SkipDetection.Reason.TOO_LONG));
                        }
                        detections.add(detection(rule, hit.end, SkipDetection.Reason.TOO_LONG));
                        if (fallback) {
                            long duration = Math.min(rule.missingEndDurationMs, rule.maxDurationMs);
                            addFallback(occurrences, detections, rule, openStart,
                                    openStart.timeMs + Math.min(duration,
                                            episodeDurationMs - openStart.timeMs));
                        }
                    } else {
                        detections.add(detection(rule, openStart, SkipDetection.Reason.PENDING));
                        detections.add(detection(rule, hit.end, SkipDetection.Reason.PENDING));
                    }
                    openStart = hit.start;
                    continue;
                } else if (!isCovered(coverage, openStart.timeMs, hit.end.timeMs)) {
                    detections.add(detection(rule, openStart, SkipDetection.Reason.PENDING));
                    detections.add(detection(rule, hit.end, SkipDetection.Reason.PENDING));
                    openStart = hit.start;
                    continue;
                } else {
                    addOccurrence(occurrences, rule, openStart.timeMs, endMs,
                            Math.min(openStart.score, hit.end.score));
                    openStart = null;
                    continue;
                }
            }
            if (hit.start != null && hit.start.timeMs > openStart.timeMs) {
                replaceStart(occurrences, detections, rule, openStart, hit.start.timeMs,
                        episodeDurationMs, coverage);
                openStart = hit.start;
            }
        }
        if (openStart != null) {
            addMissingEnd(occurrences, detections, rule, openStart, episodeDurationMs, coverage);
        }
        occurrences = deduplicateOccurrences(occurrences);
        detections.sort(Comparator.comparingLong((SkipDetection item) -> item.timeMs)
                .thenComparing(item -> item.marker));
        return new Resolution(occurrences, detections);
    }

    private static long regionStart(SkipRule rule, long timeMs, long episodeDurationMs) {
        if (rule.lastRegionMs > 0 && timeMs >= episodeDurationMs - rule.lastRegionMs
                && (rule.firstRegionMs == 0 || timeMs > rule.firstRegionMs)) {
            return Math.max(0, episodeDurationMs - rule.lastRegionMs);
        }
        return 0;
    }

    private static List<HitOccurrence> hitOccurrences(SkipRule rule, List<SkipMarkerHit> hits,
                                                       long episodeDurationMs) {
        List<SkipMarkerHit> matching = new ArrayList<>();
        for (SkipMarkerHit hit : hits) {
            if (rule.id.equals(hit.ruleId) && hit.timeMs < episodeDurationMs
                    && isInRegion(rule, hit.timeMs, episodeDurationMs)
                    && (hit.marker == SkipMarker.START || !rule.useStartAsEnd)) {
                matching.add(hit);
            }
        }
        matching.sort(Comparator.comparingLong(hit -> hit.timeMs));
        List<HitOccurrence> result = new ArrayList<>();
        long clusterStartMs = -1;
        for (SkipMarkerHit hit : matching) {
            HitOccurrence previous = result.isEmpty() ? null : result.get(result.size() - 1);
            if (previous == null || hit.timeMs - clusterStartMs > SAME_OCCURRENCE_MS
                    && !nearExistingRole(previous, hit)
                    && !tooCloseSimilarOppositeMarker(rule, previous, hit)) {
                result.add(new HitOccurrence());
                clusterStartMs = hit.timeMs;
            }
            HitOccurrence occurrence = result.get(result.size() - 1);
            if (hit.marker == SkipMarker.START) {
                occurrence.start = better(occurrence.start, hit);
                if (rule.useStartAsEnd) {
                    occurrence.end = better(occurrence.end, new SkipMarkerHit(hit.ruleId,
                            hit.sampleId, SkipMarker.END, hit.timeMs, hit.score));
                }
            } else {
                occurrence.end = better(occurrence.end, hit);
            }
        }
        return result;
    }

    private static boolean nearExistingRole(HitOccurrence occurrence, SkipMarkerHit hit) {
        SkipMarkerHit existing = hit.marker == SkipMarker.START ? occurrence.start : occurrence.end;
        return existing != null && hit.timeMs - existing.timeMs <= SAME_OCCURRENCE_MS;
    }

    private static boolean tooCloseSimilarOppositeMarker(SkipRule rule, HitOccurrence occurrence,
                                                          SkipMarkerHit hit) {
        if (rule.minDurationMs <= 0) {
            return false;
        }
        if (hit.marker == SkipMarker.END && occurrence.start != null && occurrence.end == null) {
            return sampleEnd(rule, hit) - occurrence.start.timeMs < rule.minDurationMs
                    && samplesSimilar(rule, occurrence.start, hit);
        }
        return hit.marker == SkipMarker.START && occurrence.end != null && occurrence.start == null
                && hit.timeMs - occurrence.end.timeMs < rule.minDurationMs
                && samplesSimilar(rule, occurrence.end, hit);
    }

    private static boolean samplesSimilar(SkipRule rule, SkipMarkerHit first, SkipMarkerHit second) {
        SkipSample firstSample = sample(rule, first.sampleId);
        SkipSample secondSample = sample(rule, second.sampleId);
        return firstSample != null && secondSample != null
                && SkipFingerprint.similarity(firstSample.fingerprint, secondSample.fingerprint) >= 0.82f;
    }

    private static SkipSample sample(SkipRule rule, String sampleId) {
        for (SkipSample sample : rule.samples) {
            if (sample.id.equals(sampleId)) {
                return sample;
            }
        }
        return null;
    }

    private static SkipMarkerHit better(SkipMarkerHit existing, SkipMarkerHit candidate) {
        return existing == null || candidate.score > existing.score ? candidate : existing;
    }

    private static void addMissingStart(List<SkipDetection> detections, SkipRule rule,
                                        SkipMarkerHit end, long episodeDurationMs,
                                        List<SkipCoverage> coverage) {
        long endMs = sampleEnd(rule, end);
        long searchStart = regionStart(rule, end.timeMs, episodeDurationMs);
        if (rule.maxDurationMs > 0) {
            searchStart = Math.max(searchStart, endMs - rule.maxDurationMs);
        }
        SkipDetection.Reason reason = isCovered(coverage, searchStart, end.timeMs)
                ? SkipDetection.Reason.MISSING_START : SkipDetection.Reason.PENDING;
        detections.add(detection(rule, end, reason));
    }

    private static void addMissingEnd(List<SkipOccurrence> occurrences, List<SkipDetection> detections,
                                      SkipRule rule, SkipMarkerHit start, long episodeDurationMs,
                                      List<SkipCoverage> coverage) {
        long windowEnd = relevantWindowEnd(rule, start.timeMs, episodeDurationMs);
        if (!isCovered(coverage, start.timeMs, windowEnd)) {
            detections.add(detection(rule, start, SkipDetection.Reason.PENDING));
            return;
        }
        if (rule.missingEndBehavior != SkipRule.MissingEndBehavior.FIXED
                || rule.missingEndDurationMs <= 0) {
            detections.add(detection(rule, start, SkipDetection.Reason.MISSING_END));
            return;
        }
        long duration = rule.missingEndDurationMs;
        if (rule.maxDurationMs > 0) {
            duration = Math.min(duration, rule.maxDurationMs);
        }
        long endMs = start.timeMs + Math.min(duration, episodeDurationMs - start.timeMs);
        addFallback(occurrences, detections, rule, start, endMs);
    }

    private static void replaceStart(List<SkipOccurrence> occurrences, List<SkipDetection> detections,
                                     SkipRule rule, SkipMarkerHit start, long nextStartMs,
                                     long episodeDurationMs, List<SkipCoverage> coverage) {
        long searchEnd = Math.min(nextStartMs, relevantWindowEnd(rule, start.timeMs, episodeDurationMs));
        if (!isCovered(coverage, start.timeMs, searchEnd)) {
            detections.add(detection(rule, start, SkipDetection.Reason.PENDING));
            return;
        }
        if (rule.missingEndBehavior != SkipRule.MissingEndBehavior.FIXED
                || rule.missingEndDurationMs <= 0) {
            detections.add(detection(rule, start, SkipDetection.Reason.REPLACED_START));
            return;
        }
        long duration = rule.missingEndDurationMs;
        if (rule.maxDurationMs > 0) {
            duration = Math.min(duration, rule.maxDurationMs);
        }
        long endMs = Math.min(nextStartMs, start.timeMs
                + Math.min(duration, episodeDurationMs - start.timeMs));
        addFallback(occurrences, detections, rule, start, endMs);
    }

    private static void addFallback(List<SkipOccurrence> occurrences, List<SkipDetection> detections,
                                    SkipRule rule, SkipMarkerHit start, long endMs) {
        if (endMs - start.timeMs < rule.minDurationMs) {
            detections.add(detection(rule, start, SkipDetection.Reason.TOO_SHORT));
            return;
        }
        addOccurrence(occurrences, rule, start.timeMs, endMs, start.score);
        detections.add(new SkipDetection(rule.id, SkipMarker.START, start.timeMs, endMs,
                SkipDetection.Reason.FALLBACK));
    }

    private static SkipDetection detection(SkipRule rule, SkipMarkerHit hit, SkipDetection.Reason reason) {
        return new SkipDetection(rule.id, hit.marker, hit.timeMs, hit.timeMs, reason);
    }

    private static boolean isCovered(List<SkipCoverage> coverage, long startMs, long endMs) {
        if (coverage == null || endMs < startMs) {
            return false;
        }
        if (endMs == startMs) {
            return true;
        }
        List<SkipCoverage> sorted = new ArrayList<>(coverage);
        sorted.sort(Comparator.comparingLong(item -> item.startMs));
        long coveredUntil = startMs;
        for (SkipCoverage item : sorted) {
            if (item.endMs <= coveredUntil) {
                continue;
            }
            if (item.startMs > coveredUntil) {
                return false;
            }
            coveredUntil = Math.max(coveredUntil, item.endMs);
            if (coveredUntil >= endMs) {
                return true;
            }
        }
        return false;
    }

    private static List<SkipMarkerHit> distinctHits(SkipRule rule, List<SkipMarkerHit> hits,
                                                     SkipMarker marker, long episodeDurationMs) {
        List<SkipMarkerHit> matching = new ArrayList<>();
        for (SkipMarkerHit hit : hits) {
            if (rule.id.equals(hit.ruleId) && hit.marker == marker
                    && hit.timeMs < episodeDurationMs && isInRegion(rule, hit.timeMs, episodeDurationMs)) {
                matching.add(hit);
            }
        }
        matching.sort(Comparator.comparingLong(hit -> hit.timeMs));
        List<SkipMarkerHit> distinct = new ArrayList<>();
        long clusterStartMs = -1;
        for (SkipMarkerHit hit : matching) {
            if (distinct.isEmpty() || hit.timeMs - clusterStartMs > SAME_OCCURRENCE_MS) {
                distinct.add(hit);
                clusterStartMs = hit.timeMs;
            } else if (hit.score > distinct.get(distinct.size() - 1).score) {
                distinct.set(distinct.size() - 1, hit);
            }
        }
        return distinct;
    }

    private static boolean isInRegion(SkipRule rule, long timeMs, long episodeDurationMs) {
        boolean inFirst = rule.firstRegionMs > 0 && timeMs <= rule.firstRegionMs;
        boolean inLast = rule.lastRegionMs > 0
                && timeMs >= Math.max(0, episodeDurationMs - rule.lastRegionMs);
        return (rule.firstRegionMs == 0 && rule.lastRegionMs == 0) || inFirst || inLast;
    }

    private static long sampleEnd(SkipRule rule, SkipMarkerHit hit) {
        for (SkipSample sample : rule.samples) {
            if (sample.id.equals(hit.sampleId)) {
                return hit.timeMs + sample.durationMs;
            }
        }
        return hit.timeMs;
    }

    private static void addOccurrence(List<SkipOccurrence> result, SkipRule rule, long startMs,
                                      long endMs, float score) {
        if (startMs < 0 || endMs <= startMs) {
            return;
        }
        long duration = endMs - startMs;
        if (rule.type == SkipRule.Type.BETWEEN && (duration < rule.minDurationMs
                || (rule.maxDurationMs > 0 && duration > rule.maxDurationMs))) {
            return;
        }
        result.add(new SkipOccurrence(rule.id, startMs, endMs, score, rule.type));
    }

    private static long relevantWindowEnd(SkipRule rule, long startMs, long episodeDurationMs) {
        long end = episodeDurationMs;
        if (rule.maxDurationMs > 0) {
            end = startMs + Math.min(rule.maxDurationMs, episodeDurationMs - startMs);
        }
        if (rule.firstRegionMs > 0 && startMs <= rule.firstRegionMs
                && (rule.lastRegionMs == 0 || rule.firstRegionMs < episodeDurationMs - rule.lastRegionMs)) {
            end = Math.min(end, rule.firstRegionMs);
        }
        return end;
    }

    private static List<SkipOccurrence> deduplicateOccurrences(List<SkipOccurrence> occurrences) {
        Map<String, SkipOccurrence> byRange = new HashMap<>();
        for (SkipOccurrence occurrence : occurrences) {
            String key = occurrence.startMs + ":" + occurrence.endMs;
            SkipOccurrence existing = byRange.get(key);
            if (existing == null || occurrence.score > existing.score) {
                byRange.put(key, occurrence);
            }
        }
        List<SkipOccurrence> result = new ArrayList<>(byRange.values());
        result.sort(Comparator.comparingLong(occurrence -> occurrence.startMs));
        return result;
    }

    static final class Resolution {
        final List<SkipOccurrence> occurrences;
        final List<SkipDetection> detections;

        Resolution(List<SkipOccurrence> occurrences, List<SkipDetection> detections) {
            this.occurrences = Collections.unmodifiableList(new ArrayList<>(occurrences));
            this.detections = Collections.unmodifiableList(new ArrayList<>(detections));
        }
    }

    private static final class HitOccurrence {
        private SkipMarkerHit start;
        private SkipMarkerHit end;
    }
}
