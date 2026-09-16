package de.danoeh.antennapod.playback.service.skip;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class SkipWindowTest {
    @Test
    public void prioritizesCurrentThenUpcomingBeforeBeginning() {
        assertEquals(295_000, SkipManager.chooseWindowStart(300_000, 600_000, Collections.emptyList()));
        assertEquals(325_000, SkipManager.chooseWindowStart(300_000, 600_000,
                Collections.singletonList(new SkipCoverage(295_000, 325_000))));
        assertEquals(0, SkipManager.chooseWindowStart(300_000, 600_000,
                Collections.singletonList(new SkipCoverage(295_000, 600_000))));
    }

    @Test
    public void seekingChangesNextWindowWithoutLosingCoverage() {
        assertEquals(95_000, SkipManager.chooseWindowStart(100_000, 600_000,
                Collections.singletonList(new SkipCoverage(295_000, 325_000))));
        assertEquals(400_000, SkipManager.chooseWindowStart(300_000, 600_000,
                Arrays.asList(new SkipCoverage(325_000, 400_000), new SkipCoverage(295_000, 325_000))));
    }

    @Test
    public void coverageDoesNotBridgeUnsearchedGaps() {
        assertEquals(2, SkipManager.mergeCoverage(Collections.singletonList(new SkipCoverage(0, 30_000)),
                Collections.singletonList(new SkipCoverage(30_001, 60_000))).size());
    }
}
