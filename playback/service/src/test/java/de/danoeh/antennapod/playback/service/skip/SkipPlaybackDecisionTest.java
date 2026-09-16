package de.danoeh.antennapod.playback.service.skip;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SkipPlaybackDecisionTest {
    @Test
    public void lateOccurrenceIsSuppressedUntilPlaybackLeavesIt() {
        SkipPlaybackDecision.State state = new SkipPlaybackDecision.State();
        List<SkipOccurrence> occurrences = Collections.singletonList(occurrence("rule", 1_000, 5_000));

        SkipPlaybackDecision.updateOccurrences(occurrences, 2_000, state);
        assertEquals(SkipPlaybackDecision.Action.NONE,
                SkipPlaybackDecision.decide(occurrences, 1_900, 2_000, state).action);
        SkipPlaybackDecision.decide(occurrences, 2_000, 5_100, state);
        assertEquals(SkipPlaybackDecision.Action.SEEK_TO,
                SkipPlaybackDecision.decide(occurrences, 500, 1_100, state).action);
    }

    @Test
    public void manualSeekSuppressesAllOverlappingOccurrences() {
        SkipPlaybackDecision.State state = new SkipPlaybackDecision.State();
        List<SkipOccurrence> occurrences = Arrays.asList(
                occurrence("first", 1_000, 5_000), occurrence("second", 2_000, 6_000));
        SkipPlaybackDecision.updateOccurrences(occurrences, 0, state);

        SkipPlaybackDecision.onUserSeek(occurrences, 3_000, state);

        assertEquals(SkipPlaybackDecision.Action.NONE,
                SkipPlaybackDecision.decide(occurrences, 2_900, 3_100, state).action);
        assertEquals(SkipPlaybackDecision.Action.NONE,
                SkipPlaybackDecision.decide(occurrences, 3_100, 5_500, state).action);
    }

    @Test
    public void automaticResumeRewindDoesNotSuppressOccurrence() {
        SkipPlaybackDecision.State state = new SkipPlaybackDecision.State();
        List<SkipOccurrence> occurrences = Collections.singletonList(occurrence("rule", 1_000, 5_000));
        SkipPlaybackDecision.updateOccurrences(occurrences, 6_000, state);

        SkipPlaybackDecision.Decision decision = SkipPlaybackDecision.decide(
                occurrences, 6_000, 2_000, state);

        assertEquals(SkipPlaybackDecision.Action.SEEK_TO, decision.action);
        assertEquals(5_000, decision.targetMs);
    }

    @Test
    public void finishTypeUsesMarkerDespiteUnequalDurations() {
        SkipPlaybackDecision.State state = new SkipPlaybackDecision.State();
        List<SkipOccurrence> occurrences = Collections.singletonList(finishOccurrence("rule", 8_000, 9_000));
        SkipPlaybackDecision.updateOccurrences(occurrences, 7_000, state);

        assertEquals(SkipPlaybackDecision.Action.FINISH,
                SkipPlaybackDecision.decide(occurrences, 7_900, 8_100, state).action);
    }

    @Test
    public void ordinaryRangeAtPlayerDurationRemainsSeek() {
        SkipPlaybackDecision.State state = new SkipPlaybackDecision.State();
        List<SkipOccurrence> occurrences = Collections.singletonList(occurrence("rule", 8_000, 9_500));
        SkipPlaybackDecision.updateOccurrences(occurrences, 7_000, state);

        SkipPlaybackDecision.Decision decision = SkipPlaybackDecision.decide(
                occurrences, 7_900, 8_100, state);

        assertEquals(SkipPlaybackDecision.Action.SEEK_TO, decision.action);
        assertEquals(9_500, decision.targetMs);
    }

    @Test
    public void crossedFinishOccurrenceStillFinishesEpisode() {
        SkipPlaybackDecision.State state = new SkipPlaybackDecision.State();
        List<SkipOccurrence> occurrences = Collections.singletonList(finishOccurrence("rule", 9_000, 9_500));
        SkipPlaybackDecision.updateOccurrences(occurrences, 8_000, state);

        assertEquals(SkipPlaybackDecision.Action.FINISH,
                SkipPlaybackDecision.decide(occurrences, 8_900, 10_000, state).action);
    }

    @Test
    public void finishTypeWinsWhenSeekRangeOverlapsMarker() {
        SkipPlaybackDecision.State state = new SkipPlaybackDecision.State();
        List<SkipOccurrence> occurrences = Arrays.asList(
                occurrence("ad", 1_000, 4_000), finishOccurrence("outro", 3_000, 3_500));
        SkipPlaybackDecision.updateOccurrences(occurrences, 900, state);

        assertEquals(SkipPlaybackDecision.Action.FINISH,
                SkipPlaybackDecision.decide(occurrences, 900, 1_100, state).action);
    }

    @Test
    public void manualSeekAfterFinishSampleSuppressesFinishAndFixedOutro() {
        SkipPlaybackDecision.State state = new SkipPlaybackDecision.State();
        List<SkipOccurrence> occurrences = Collections.singletonList(
                finishOccurrence("outro", 8_000, 8_500));
        SkipPlaybackDecision.updateOccurrences(occurrences, 7_000, state);

        SkipPlaybackDecision.onUserSeek(occurrences, 9_000, state);

        assertEquals(SkipPlaybackDecision.Action.NONE,
                SkipPlaybackDecision.decide(occurrences, 8_900, 9_100, state).action);
        assertTrue(SkipPlaybackDecision.isSuppressed(occurrences, 9_100, state));
    }

    @Test
    public void lateFinishDiscoveryAfterMarkerDoesNotFinishEpisode() {
        SkipPlaybackDecision.State state = new SkipPlaybackDecision.State();
        List<SkipOccurrence> occurrences = Collections.singletonList(
                finishOccurrence("outro", 8_000, 8_500));

        SkipPlaybackDecision.updateOccurrences(occurrences, 9_000, state);

        assertEquals(SkipPlaybackDecision.Action.NONE,
                SkipPlaybackDecision.decide(occurrences, 8_900, 9_100, state).action);
        assertTrue(SkipPlaybackDecision.isSuppressed(occurrences, 9_100, state));
    }

    @Test
    public void clearedOccurrencesDoNotLeakSuppressionToNextEpisode() {
        SkipPlaybackDecision.State state = new SkipPlaybackDecision.State();
        List<SkipOccurrence> occurrences = Collections.singletonList(occurrence("rule", 1_000, 2_000));
        SkipPlaybackDecision.updateOccurrences(occurrences, 1_500, state);

        SkipPlaybackDecision.updateOccurrences(Collections.emptyList(), -1, state);
        SkipPlaybackDecision.updateOccurrences(occurrences, 500, state);

        assertEquals(SkipPlaybackDecision.Action.SEEK_TO,
                SkipPlaybackDecision.decide(occurrences, 900, 1_100, state).action);
    }

    @Test
    public void highSpeedCrossingSkipsAndNeverSeeksBackward() {
        SkipPlaybackDecision.State state = new SkipPlaybackDecision.State();
        List<SkipOccurrence> occurrences = Collections.singletonList(occurrence("rule", 1_000, 2_000));
        SkipPlaybackDecision.updateOccurrences(occurrences, 900, state);

        SkipPlaybackDecision.Decision crossing = SkipPlaybackDecision.decide(
                occurrences, 900, 1_700, state);
        assertEquals(SkipPlaybackDecision.Action.SEEK_TO, crossing.action);
        assertEquals(2_000, crossing.targetMs);
        assertEquals(SkipPlaybackDecision.Action.NONE,
                SkipPlaybackDecision.decide(occurrences, 900, 2_100, new SkipPlaybackDecision.State())
                        .action);
    }

    @Test
    public void overlappingOccurrencesProduceSingleMonotonicSkip() {
        SkipPlaybackDecision.State state = new SkipPlaybackDecision.State();
        List<SkipOccurrence> occurrences = Arrays.asList(
                occurrence("first", 1_000, 3_000), occurrence("second", 2_500, 5_000));
        SkipPlaybackDecision.updateOccurrences(occurrences, 900, state);

        SkipPlaybackDecision.Decision decision = SkipPlaybackDecision.decide(
                occurrences, 900, 1_100, state);

        assertEquals(SkipPlaybackDecision.Action.SEEK_TO, decision.action);
        assertEquals(5_000, decision.targetMs);
        assertEquals(SkipPlaybackDecision.Action.NONE,
                SkipPlaybackDecision.decide(occurrences, 1_100, 5_000, state).action);
    }

    private static SkipOccurrence occurrence(String ruleId, long startMs, long endMs) {
        return new SkipOccurrence(ruleId, startMs, endMs, 1);
    }

    private static SkipOccurrence finishOccurrence(String ruleId, long startMs, long endMs) {
        return new SkipOccurrence(ruleId, startMs, endMs, 1, SkipRule.Type.FINISH);
    }
}
