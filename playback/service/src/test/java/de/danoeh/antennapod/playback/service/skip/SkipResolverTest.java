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
    public void doubleMarkedJinglesPairSuccessiveOccurrencesWithoutRestartingClosingJingle() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 40_000, 0, 0, 0);
        List<SkipMarkerHit> hits = Arrays.asList(start(5_000), end(5_000), start(20_000), end(20_000),
                start(40_000), end(40_000), start(55_000), end(55_000));
        List<SkipOccurrence> result = resolve(rule, hits, COMPLETE);
        assertEquals(2, result.size());
        assertEquals(5_000, result.get(0).startMs);
        assertEquals(22_000, result.get(0).endMs);
        assertEquals(40_000, result.get(1).startMs);
        assertEquals(57_000, result.get(1).endMs);
        assertTrue(SkipResolver.resolveDetections(rule, hits, 120_000, COMPLETE).isEmpty());
    }

    @Test
    public void nearOffsetDoubleMarkedJinglesAreSingleOccurrences() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 40_000, 0, 0, 0);
        List<SkipMarkerHit> hits = Arrays.asList(start(5_000), end(5_192), start(20_128), end(20_000));
        List<SkipOccurrence> result = resolve(rule, hits, COMPLETE);
        assertEquals(1, result.size());
        assertEquals(5_000, result.get(0).startMs);
        assertEquals(22_000, result.get(0).endMs);
    }

    @Test
    public void oppositeMarkersCloserThanMinimumPairWithNextOccurrence() {
        SkipRule rule = similarMarkerRule();
        List<SkipMarkerHit> hits = Arrays.asList(start(5_000), end(8_000), start(20_000), end(23_000));
        List<SkipOccurrence> result = resolve(rule, hits, COMPLETE);
        assertEquals(1, result.size());
        assertEquals(5_000, result.get(0).startMs);
        assertEquals(25_000, result.get(0).endMs);
        assertTrue(SkipResolver.resolveDetections(rule, hits, 120_000, COMPLETE).isEmpty());
    }

    @Test
    public void shortGapBetweenDistinctSectionsDoesNotMergeMarkers() {
        AudioFingerprint startFingerprint = new AudioFingerprint(8_000, 64, 32,
                new int[] {1, 1, 1, 1, 1, 1, 1, 1});
        AudioFingerprint endFingerprint = new AudioFingerprint(8_000, 64, 32,
                new int[] {-1, -1, -1, -1, -1, -1, -1, -1});
        SkipRule rule = new SkipRule("rule", "Advertisement", true, SkipRule.Type.BETWEEN,
                10_000, 40_000, SkipRule.MissingEndBehavior.UNTOUCHED, 0, 0, 0,
                Arrays.asList(new SkipSample("start", SkipMarker.START, 2_000, 0, startFingerprint),
                        new SkipSample("end", SkipMarker.END, 2_000, 0, endFingerprint)));
        List<SkipOccurrence> result = resolve(rule,
                Arrays.asList(start(5_000), end(20_000), start(25_000), end(40_000)), COMPLETE);
        assertEquals(2, result.size());
        assertEquals(5_000, result.get(0).startMs);
        assertEquals(22_000, result.get(0).endMs);
        assertEquals(25_000, result.get(1).startMs);
        assertEquals(42_000, result.get(1).endMs);
    }

    @Test
    public void alternateEndSamplesStayInExpandedOccurrence() {
        SkipRule rule = similarMarkerRule();
        List<SkipMarkerHit> hits = Arrays.asList(start(5_000), end(8_000),
                new SkipMarkerHit("rule", "end-variant", SkipMarker.END, 8_064, 0.95f),
                start(20_000), end(23_000));
        assertEquals(1, resolve(rule, hits, COMPLETE).size());
        assertTrue(SkipResolver.resolveDetections(rule, hits, 120_000, COMPLETE).isEmpty());
    }

    @Test
    public void similarEndBeforeStartMatchesAreSingleOccurrences() {
        SkipRule rule = similarMarkerRule();
        List<SkipMarkerHit> hits = Arrays.asList(end(5_000), start(8_000),
                end(20_000), start(23_000), end(40_000), start(43_000), end(55_000), start(58_000));
        List<SkipOccurrence> result = resolve(rule, hits, COMPLETE);
        assertEquals(2, result.size());
        assertEquals(8_000, result.get(0).startMs);
        assertEquals(22_000, result.get(0).endMs);
        assertEquals(43_000, result.get(1).startMs);
        assertEquals(57_000, result.get(1).endMs);
        assertTrue(SkipResolver.resolveDetections(rule, hits, 120_000, COMPLETE).isEmpty());
    }

    @Test
    public void markerDiscoveryOrderDoesNotAffectChronologicalPairing() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 40_000, 0, 0, 0);
        List<SkipMarkerHit> hits = Arrays.asList(end(20_000), start(5_000));
        List<SkipOccurrence> result = resolve(rule, hits, COMPLETE);
        assertEquals(1, result.size());
        assertEquals(5_000, result.get(0).startMs);
        assertEquals(22_000, result.get(0).endMs);
    }

    @Test
    public void sharedMarkerDiscoveryOrderReconcilesEarlierStart() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 40_000, 0, 0, 0).withUseStartAsEnd(true);
        List<SkipMarkerHit> hits = Arrays.asList(start(20_000), start(5_000));
        List<SkipOccurrence> result = resolve(rule, hits, COMPLETE);
        assertEquals(1, result.size());
        assertEquals(5_000, result.get(0).startMs);
        assertEquals(22_000, result.get(0).endMs);
    }

    @Test
    public void sharedStartSamplesPairSuccessiveOccurrencesAndIgnoreStoredEnds() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 40_000, 0, 0, 0).withUseStartAsEnd(true);
        List<SkipMarkerHit> hits = Arrays.asList(start(5_000), end(10_000), start(20_000),
                end(30_000), start(40_000), start(55_000));
        List<SkipOccurrence> result = resolve(rule, hits, COMPLETE);
        assertEquals(2, result.size());
        assertEquals(5_000, result.get(0).startMs);
        assertEquals(22_000, result.get(0).endMs);
        assertEquals(40_000, result.get(1).startMs);
        assertEquals(57_000, result.get(1).endMs);
    }

    @Test
    public void sharedMarkersStartAtFirstVisibleOccurrenceWithoutLookbehind() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 40_000, 0, 0, 0).withUseStartAsEnd(true);
        List<SkipMarkerHit> hits = Arrays.asList(start(20_000), start(40_000));
        List<SkipCoverage> partial = Collections.singletonList(new SkipCoverage(10_000, 50_000));
        List<SkipOccurrence> result = resolve(rule, hits, partial);
        assertEquals(1, result.size());
        assertEquals(20_000, result.get(0).startMs);
        assertEquals(42_000, result.get(0).endMs);
        assertTrue(SkipResolver.resolveDetections(rule, hits, 120_000, partial).isEmpty());
    }

    @Test
    public void sharedMarkerDuplicateAcrossCoverageGapDoesNotRestartClosingOccurrence() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 40_000, 0, 0, 0).withUseStartAsEnd(true);
        List<SkipMarkerHit> hits = Arrays.asList(start(5_000), start(20_000), start(20_320),
                start(40_000), start(55_000));
        List<SkipCoverage> coverage = Arrays.asList(new SkipCoverage(0, 20_200),
                new SkipCoverage(20_250, 120_000));
        List<SkipOccurrence> result = resolve(rule, hits, coverage);
        assertEquals(2, result.size());
        assertEquals(5_000, result.get(0).startMs);
        assertEquals(22_000, result.get(0).endMs);
        assertEquals(40_000, result.get(1).startMs);
        assertEquals(57_000, result.get(1).endMs);
        assertTrue(SkipResolver.resolveDetections(rule, hits, 120_000, coverage).isEmpty());
    }

    @Test
    public void distinctSharedMarkerAfterClosingOccurrenceStartsNextPair() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 40_000, 0, 0, 0).withUseStartAsEnd(true);
        List<SkipOccurrence> result = resolve(rule,
                Arrays.asList(start(5_000), start(20_000), start(21_100), start(30_000)), COMPLETE);
        assertEquals(2, result.size());
        assertEquals(5_000, result.get(0).startMs);
        assertEquals(22_000, result.get(0).endMs);
        assertEquals(21_100, result.get(1).startMs);
        assertEquals(32_000, result.get(1).endMs);
    }

    @Test
    public void oversizedSharedCloseRemainsNextOpening() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 30_000, 0, 0, 0).withUseStartAsEnd(true);
        List<SkipMarkerHit> hits = Arrays.asList(start(5_000), start(60_000), start(75_000));
        List<SkipOccurrence> result = resolve(rule, hits, COMPLETE);
        assertEquals(1, result.size());
        assertEquals(60_000, result.get(0).startMs);
        assertEquals(77_000, result.get(0).endMs);
        List<SkipDetection> detections = SkipResolver.resolveDetections(rule, hits, 120_000, COMPLETE);
        assertEquals(2, detections.size());
        assertEquals(SkipMarker.START, detections.get(0).marker);
        assertEquals(SkipMarker.END, detections.get(1).marker);
        assertEquals(SkipDetection.Reason.TOO_LONG, detections.get(0).reason);
        assertEquals(SkipDetection.Reason.TOO_LONG, detections.get(1).reason);
    }

    @Test
    public void regionRejectedSharedCloseRemainsOpening() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 0, 0, 20_000, 20_000).withUseStartAsEnd(true);
        List<SkipMarkerHit> hits = Arrays.asList(start(5_000), start(105_000), start(115_000));
        List<SkipOccurrence> result = resolve(rule, hits, COMPLETE);
        assertEquals(1, result.size());
        assertEquals(105_000, result.get(0).startMs);
        assertEquals(117_000, result.get(0).endMs);
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
    public void replacementFallbackNeedsCoverageOnlyThroughMaximumWindow() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 40_000, 10_000, 0, 0);
        List<SkipCoverage> coverage = Arrays.asList(new SkipCoverage(0, 45_000),
                new SkipCoverage(100_000, 101_000));
        List<SkipOccurrence> result = resolve(rule, Arrays.asList(start(5_000), start(100_000)), coverage);
        assertEquals(1, result.size());
        assertEquals(5_000, result.get(0).startMs);
        assertEquals(15_000, result.get(0).endMs);
        assertEquals(SkipDetection.Reason.FALLBACK, SkipResolver.resolveDetections(rule,
                Arrays.asList(start(5_000), start(100_000)), 120_000, coverage).get(0).reason);
    }

    @Test
    public void sharedMarkerFallbackStartsAtFirstVisibleOccurrence() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 40_000, 10_000, 0, 0).withUseStartAsEnd(true);
        List<SkipCoverage> coverage = Arrays.asList(new SkipCoverage(10_000, 60_000),
                new SkipCoverage(100_000, 101_000));
        List<SkipOccurrence> result = resolve(rule, Arrays.asList(start(20_000), start(100_000)), coverage);
        assertEquals(1, result.size());
        assertEquals(20_000, result.get(0).startMs);
        assertEquals(30_000, result.get(0).endMs);
        List<SkipDetection> detections = SkipResolver.resolveDetections(rule,
                Arrays.asList(start(20_000), start(100_000)), 120_000, coverage);
        assertEquals(SkipDetection.Reason.FALLBACK, detections.get(0).reason);
        assertEquals(SkipDetection.Reason.PENDING, detections.get(1).reason);
    }

    @Test
    public void incompleteDualMarkerPairReportsBothUnusedEndpoints() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 40_000, 0, 0, 0);
        List<SkipDetection> detections = SkipResolver.resolveDetections(rule,
                Arrays.asList(start(5_000), start(20_000), end(20_000)), 120_000,
                Arrays.asList(new SkipCoverage(0, 10_000), new SkipCoverage(19_000, 30_000)));
        assertEquals(3, detections.size());
        assertEquals(SkipMarker.START, detections.get(0).marker);
        assertEquals(5_000, detections.get(0).timeMs);
        assertEquals(SkipMarker.START, detections.get(1).marker);
        assertEquals(20_000, detections.get(1).timeMs);
        assertEquals(SkipMarker.END, detections.get(2).marker);
        assertEquals(20_000, detections.get(2).timeMs);
        for (SkipDetection detection : detections) {
            assertEquals(SkipDetection.Reason.PENDING, detection.reason);
        }
    }

    @Test
    public void incompleteEarlierCoverageDoesNotPublishPrematurePair() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 40_000, 0, 0, 0);
        List<SkipMarkerHit> hits = Arrays.asList(start(5_000), end(30_000));
        assertTrue(resolve(rule, hits, Arrays.asList(new SkipCoverage(0, 10_000),
                new SkipCoverage(25_000, 40_000))).isEmpty());
        assertEquals(SkipDetection.Reason.PENDING, SkipResolver.resolveDetections(rule, hits, 120_000,
                Arrays.asList(new SkipCoverage(0, 10_000), new SkipCoverage(25_000, 40_000))).get(0).reason);
    }

    @Test
    public void sharedMarkerInsideMaximumWindowRemainsEndpointWhenCoveragePending() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 40_000, 0, 0, 0).withUseStartAsEnd(true);
        List<SkipCoverage> coverage = Arrays.asList(new SkipCoverage(10_000, 30_000),
                new SkipCoverage(34_000, 40_000));
        List<SkipOccurrence> result = resolve(rule,
                Arrays.asList(start(20_000), start(40_000)), coverage);
        assertTrue(result.isEmpty());
        List<SkipDetection> detections = SkipResolver.resolveDetections(rule,
                Arrays.asList(start(20_000), start(40_000)), 120_000, coverage);
        assertEquals(2, detections.size());
        assertEquals(20_000, detections.get(0).timeMs);
        assertEquals(40_000, detections.get(1).timeMs);
        assertEquals(SkipMarker.START, detections.get(0).marker);
        assertEquals(SkipMarker.END, detections.get(1).marker);
        assertEquals(SkipDetection.Reason.PENDING, detections.get(0).reason);
        assertEquals(SkipDetection.Reason.PENDING, detections.get(1).reason);
    }

    @Test
    public void sharedMarkerAtMaximumBoundaryIsNotReopenedAfterSampleOverhang() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 30_000, 0, 0, 0).withUseStartAsEnd(true);
        List<SkipMarkerHit> hits = Arrays.asList(start(5_000), start(35_000), start(50_000));
        List<SkipOccurrence> result = resolve(rule, hits, COMPLETE);
        assertEquals(0, result.size());
        List<SkipDetection> detections = SkipResolver.resolveDetections(rule, hits, 120_000, COMPLETE);
        assertEquals(3, detections.size());
        assertEquals(5_000, detections.get(0).timeMs);
        assertEquals(SkipMarker.START, detections.get(0).marker);
        assertEquals(35_000, detections.get(1).timeMs);
        assertEquals(SkipMarker.END, detections.get(1).marker);
        assertEquals(50_000, detections.get(2).timeMs);
        assertEquals(SkipMarker.START, detections.get(2).marker);
        assertEquals(SkipDetection.Reason.MISSING_END, detections.get(2).reason);
    }

    @Test
    public void diagnosticsDescribeIntervalFailuresMissingMarkersAndFallback() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 10_000, 20_000, 0, 0, 0);
        List<SkipDetection> detections = SkipResolver.resolveDetections(rule,
                Arrays.asList(end(2_000), start(10_000), end(11_000), end(35_000), start(70_000)),
                120_000, COMPLETE);
        assertEquals(SkipDetection.Reason.MISSING_START, detections.get(0).reason);
        assertEquals(SkipDetection.Reason.TOO_LONG, detections.get(1).reason);
        assertEquals(SkipMarker.START, detections.get(1).marker);
        assertEquals(SkipDetection.Reason.TOO_SHORT, detections.get(2).reason);
        assertEquals(SkipDetection.Reason.TOO_LONG, detections.get(3).reason);
        assertEquals(SkipMarker.END, detections.get(3).marker);
        assertEquals(SkipDetection.Reason.MISSING_END, detections.get(4).reason);

        SkipRule regionRule = rule(SkipRule.Type.BETWEEN, 0, 0, 0, 20_000, 20_000);
        List<SkipDetection> regionDetections = SkipResolver.resolveDetections(regionRule,
                Arrays.asList(start(5_000), end(30_000), end(105_000)), 120_000, COMPLETE);
        assertEquals(SkipDetection.Reason.MISSING_END, regionDetections.get(0).reason);
        assertEquals(SkipDetection.Reason.MISSING_START, regionDetections.get(1).reason);

        SkipRule fallback = rule(SkipRule.Type.BETWEEN, 0, 20_000, 5_000, 0, 0);
        SkipDetection fallbackDetection = SkipResolver.resolveDetections(fallback,
                Collections.singletonList(start(70_000)), 120_000, COMPLETE).get(0);
        assertEquals(SkipDetection.Reason.FALLBACK, fallbackDetection.reason);
        assertEquals(75_000, fallbackDetection.endMs);

        List<SkipDetection> tooLongFallback = SkipResolver.resolveDetections(fallback,
                Arrays.asList(start(10_000), end(40_000)), 120_000, COMPLETE);
        assertEquals(SkipDetection.Reason.FALLBACK, tooLongFallback.get(0).reason);
        assertEquals(SkipDetection.Reason.TOO_LONG, tooLongFallback.get(1).reason);
    }

    @Test
    public void endOnlyDetectionWaitsForRelevantBackwardCoverage() {
        SkipRule rule = rule(SkipRule.Type.BETWEEN, 0, 20_000, 0, 0, 0);
        assertEquals(SkipDetection.Reason.PENDING, SkipResolver.resolveDetections(rule,
                Collections.singletonList(end(30_000)), 120_000,
                Collections.singletonList(new SkipCoverage(20_000, 40_000))).get(0).reason);
        assertEquals(SkipDetection.Reason.MISSING_START, SkipResolver.resolveDetections(rule,
                Collections.singletonList(end(30_000)), 120_000,
                Collections.singletonList(new SkipCoverage(12_000, 40_000))).get(0).reason);
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

    private static SkipRule similarMarkerRule() {
        int[] hashes = new int[61];
        Arrays.fill(hashes, 0x456789ab);
        AudioFingerprint fingerprint = new AudioFingerprint(8_000, 64, 32, hashes);
        return new SkipRule("rule", "Advertisement", true, SkipRule.Type.BETWEEN,
                10_000, 40_000, SkipRule.MissingEndBehavior.UNTOUCHED, 0, 0, 0,
                Arrays.asList(new SkipSample("start", SkipMarker.START, 2_000, 0, fingerprint),
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
