package de.danoeh.antennapod.playback.service.skip;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SkipResolverTest {
    private static final List<SkipCoverage> COMPLETE = Collections.singletonList(new SkipCoverage(0, 120_000));

    @Test
    public void betweenIncludesEntireEndSampleAndUsesTimelineLength() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 10_000, 12_000, 0, 0, 0);
        List<SkipOccurrence> result = resolve(rule, Arrays.asList(start(5_000), end(14_000)), COMPLETE);
        assertEquals(1, result.size());
        assertEquals(5_000, result.get(0).startMs);
        assertEquals(16_000, result.get(0).endMs);
        assertTrue(resolve(rule, Arrays.asList(start(5_000), end(16_000)), COMPLETE).isEmpty());
        assertTrue(resolve(rule, Arrays.asList(start(5_000), end(6_000)), COMPLETE).isEmpty());
    }

    @Test
    public void tooEarlyEndDoesNotHideLaterValidEnd() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 10_000, 20_000, 0, 0, 0);
        List<SkipOccurrence> result = resolve(rule, Arrays.asList(start(5_000), end(6_000), end(16_000)), COMPLETE);
        assertEquals(1, result.size());
        assertEquals(18_000, result.get(0).endMs);
    }

    @Test
    public void repeatedDistinctStartClosesPreviousUnmatchedStart() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 40_000, 0, 0, 0);
        List<SkipOccurrence> result = resolve(rule, Arrays.asList(start(5_000), start(15_000), end(20_000)), COMPLETE);
        assertEquals(1, result.size());
        assertEquals(15_000, result.get(0).startMs);
        assertEquals(22_000, result.get(0).endMs);
    }

    @Test
    public void alternateSamplesDeduplicateSameOccurrence() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 40_000, 0, 0, 0);
        List<SkipMarkerHit> hits = Arrays.asList(start(5_000),
                new SkipMarkerHit("rule", "start-variant", SkipMarker.START, 5_080, 0.95f), end(20_000),
                new SkipMarkerHit("rule", "end-variant", SkipMarker.END, 20_064, 0.95f));
        List<SkipOccurrence> result = resolve(rule, hits, COMPLETE);
        assertEquals(1, result.size());
        assertEquals(5_080, result.get(0).startMs);
        assertEquals(22_064, result.get(0).endMs);
    }

    @Test
    public void fallbackWaitsForMaximumSearchWindowRatherThanFallbackDuration() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 40_000, 10_000, 0, 0);
        List<SkipMarkerHit> hits = Collections.singletonList(start(5_000));
        assertTrue(resolve(rule, hits, Collections.singletonList(new SkipCoverage(0, 15_000))).isEmpty());
        assertTrue(resolve(rule, hits, Arrays.asList(new SkipCoverage(0, 25_000),
                new SkipCoverage(26_000, 45_000))).isEmpty());
        List<SkipOccurrence> result = resolve(rule, hits, Collections.singletonList(new SkipCoverage(0, 45_000)));
        assertEquals(1, result.size());
        assertEquals(15_000, result.get(0).endMs);
        result = resolve(rule, Arrays.asList(start(5_000), end(30_000)), COMPLETE);
        assertEquals(32_000, result.get(0).endMs);
    }

    @Test
    public void unboundedFallbackWaitsForEpisodeEnd() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 0, 10_000, 0, 0);
        List<SkipMarkerHit> hits = Collections.singletonList(start(5_000));
        assertTrue(resolve(rule, hits, Collections.singletonList(new SkipCoverage(0, 119_000))).isEmpty());
        assertEquals(15_000, resolve(rule, hits, COMPLETE).get(0).endMs);
    }

    @Test
    public void fallbackCapsAtMaximumAndNextStart() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 20_000, 60_000, 0, 0);
        List<SkipOccurrence> result = resolve(rule, Arrays.asList(start(5_000), start(15_000)), COMPLETE);
        assertEquals(2, result.size());
        assertEquals(15_000, result.get(0).endMs);
        assertEquals(35_000, result.get(1).endMs);
    }

    @Test
    public void incompleteEarlierCoverageDoesNotPublishPrematurePair() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 40_000, 0, 0, 0);
        List<SkipMarkerHit> hits = Arrays.asList(start(5_000), end(30_000));
        assertTrue(resolve(rule, hits, Arrays.asList(new SkipCoverage(0, 10_000),
                new SkipCoverage(25_000, 40_000))).isEmpty());
    }

    @Test
    public void firstAndLastRegionsExcludeMiddleMatches() {
        SkipRule rule = rule(SkipRule.Type.FIXED, 0, 0, 0, 20_000, 20_000);
        List<SkipOccurrence> result = resolve(rule, Arrays.asList(start(5_000), start(50_000), start(110_000)), COMPLETE);
        assertEquals(2, result.size());
        assertEquals(5_000, result.get(0).startMs);
        assertEquals(110_000, result.get(1).startMs);
    }

    @Test
    public void regionEndClosesFallbackSearch() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 0, 10_000, 20_000, 0);
        List<SkipMarkerHit> hits = Arrays.asList(start(5_000), end(30_000));
        List<SkipOccurrence> result = resolve(rule, hits, Collections.singletonList(new SkipCoverage(0, 20_000)));
        assertEquals(1, result.size());
        assertEquals(15_000, result.get(0).endMs);
    }

    @Test
    public void fixedStartsAtMarkerBeginningAndFinishCarriesItsType() {
        SkipRule fixed = rule(SkipRule.Type.FIXED, 0, 1_000, 0, 0, 0);
        SkipOccurrence skip = resolve(fixed, Collections.singletonList(start(5_000)), COMPLETE).get(0);
        assertEquals(5_000, skip.startMs);
        assertEquals(12_000, skip.endMs);
        assertEquals(SkipRule.Type.FIXED, skip.type);
        SkipRule finish = rule(SkipRule.Type.FINISH, 0, 1_000, 0, 0, 0);
        SkipOccurrence ending = resolve(finish, Collections.singletonList(start(5_000)), COMPLETE).get(0);
        assertEquals(SkipRule.Type.FINISH, ending.type);
        assertEquals(120_000, ending.endMs);
        assertTrue(resolve(finish.withEnabled(false), Collections.singletonList(start(5_000)), COMPLETE).isEmpty());
    }

    private static List<SkipOccurrence> resolve(SkipRule rule, List<SkipMarkerHit> hits, List<SkipCoverage> coverage) {
        return SkipResolver.resolve(rule, hits, 120_000, coverage);
    }

    private static SkipRule rule(SkipRule.Type type, long min, long max, long fallback, long first, long last) {
        AudioFingerprint fingerprint = new AudioFingerprint(8_000, 64, 32, new int[61]);
        return new SkipRule("rule", "Advertisement", true, type, min, max,
                fallback == 0 ? SkipRule.MissingEndBehavior.UNTOUCHED : SkipRule.MissingEndBehavior.FIXED,
                fallback, 7_000, first, last, Arrays.asList(
                new SkipSample("start", SkipMarker.START, 2_000, 700, fingerprint),
                new SkipSample("start-variant", SkipMarker.START, 2_000, 0, fingerprint),
                new SkipSample("end", SkipMarker.END, 2_000, 0, fingerprint),
                new SkipSample("end-variant", SkipMarker.END, 2_000, 0, fingerprint)));
    }

    private static SkipMarkerHit start(long timeMs) {
        return new SkipMarkerHit("rule", "start", SkipMarker.START, timeMs, 0.9f);
    }

    private static SkipMarkerHit end(long timeMs) {
        return new SkipMarkerHit("rule", "end", SkipMarker.END, timeMs, 0.9f);
    }
}
