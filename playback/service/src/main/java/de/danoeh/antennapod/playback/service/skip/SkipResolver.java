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
        if (rule == null || !rule.enabled || episodeDurationMs <= 0 || hits == null) {
            return Collections.emptyList();
        }
        List<SkipMarkerHit> starts = distinctHits(rule, hits, SkipMarker.START, episodeDurationMs);
        List<SkipMarkerHit> ends = distinctHits(rule, hits, SkipMarker.END, episodeDurationMs);
        List<SkipOccurrence> result = new ArrayList<>();
        if (rule.type == SkipRule.Type.FINISH) {
            for (SkipMarkerHit start : starts) {
                addOccurrence(result, rule, start.timeMs, episodeDurationMs, start.score);
            }
            return result;
        }
        if (rule.type == SkipRule.Type.FIXED) {
            for (SkipMarkerHit start : starts) {
                long end = start.timeMs + Math.min(rule.fixedDurationMs, episodeDurationMs - start.timeMs);
                if (rule.fixedDurationMs > 0) {
                    addOccurrence(result, rule, start.timeMs, end, start.score);
                }
            }
            return result;
        }
        for (int index = 0; index < starts.size(); index++) {
            SkipMarkerHit start = starts.get(index);
            long nextStart = index + 1 < starts.size() ? starts.get(index + 1).timeMs : episodeDurationMs;
            long windowEnd = Math.min(nextStart, relevantWindowEnd(rule, start.timeMs, episodeDurationMs));
            SkipMarkerHit end = firstEndAfter(rule, ends, start.timeMs, windowEnd, nextStart);
            if (end != null && isCovered(coverage, start.timeMs, end.timeMs)) {
                addBetween(result, rule, start, end);
            } else if (end == null && isCovered(coverage, start.timeMs, windowEnd)) {
                addMissingEnd(result, rule, start, nextStart, episodeDurationMs);
            }
        }
        return deduplicateOccurrences(result);
    }

    private static boolean isCovered(List<SkipCoverage> coverage, long startMs, long endMs) {
        if (coverage == null || endMs <= startMs) {
            return false;
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

    private static SkipMarkerHit firstEndAfter(SkipRule rule, List<SkipMarkerHit> ends,
                                              long startMs, long windowEndMs, long nextStartMs) {
        for (SkipMarkerHit end : ends) {
            long endMs = sampleEnd(rule, end);
            if (end.timeMs > startMs && end.timeMs < nextStartMs && endMs <= windowEndMs
                    && endMs - startMs >= rule.minDurationMs) {
                return end;
            }
        }
        return null;
    }

    private static void addBetween(List<SkipOccurrence> result, SkipRule rule,
                                   SkipMarkerHit start, SkipMarkerHit end) {
        addOccurrence(result, rule, start.timeMs, sampleEnd(rule, end), Math.min(start.score, end.score));
    }

    private static long sampleEnd(SkipRule rule, SkipMarkerHit hit) {
        for (SkipSample sample : rule.samples) {
            if (sample.id.equals(hit.sampleId)) {
                return hit.timeMs + sample.durationMs;
            }
        }
        return hit.timeMs;
    }

    private static void addMissingEnd(List<SkipOccurrence> result, SkipRule rule,
                                      SkipMarkerHit start, long nextDistinctStart,
                                      long episodeDurationMs) {
        if (rule.missingEndBehavior != SkipRule.MissingEndBehavior.FIXED
                || rule.missingEndDurationMs <= 0) {
            return;
        }
        long duration = rule.missingEndDurationMs;
        if (rule.maxDurationMs > 0) {
            duration = Math.min(duration, rule.maxDurationMs);
        }
        long end = start.timeMs + Math.min(duration, episodeDurationMs - start.timeMs);
        if (nextDistinctStart > start.timeMs) {
            end = Math.min(end, nextDistinctStart);
        }
        addOccurrence(result, rule, start.timeMs, Math.min(end, episodeDurationMs), start.score);
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
}
