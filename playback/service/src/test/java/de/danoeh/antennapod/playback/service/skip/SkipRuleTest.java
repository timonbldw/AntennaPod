package de.danoeh.antennapod.playback.service.skip;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class SkipRuleTest {
    @Test
    public void unnamedDraftCannotPassValidation() {
        int[] hashes = new int[61];
        Arrays.fill(hashes, 0x456789ab);
        SkipSample sample = new SkipSample("sample", SkipMarker.START, 2_000, 0,
                new AudioFingerprint(8_000, 64, 32, hashes));
        for (String name : Arrays.asList("", " \t\n")) {
            SkipRule rule = new SkipRule("id", name, true, SkipRule.Type.FIXED, 0, 0,
                    SkipRule.MissingEndBehavior.UNTOUCHED, 0, 5_000, 0, 0, Collections.singletonList(sample));
            try {
                rule.validate();
                fail("Unnamed draft passed validation");
            } catch (IllegalArgumentException error) {
                assertTrue(error.getMessage().contains("name is required"));
            }
        }
    }

    @Test
    public void draftCannotBeSavedWithoutStartSample() {
        SkipRule rule = new SkipRule("id", "Intro", true, SkipRule.Type.FIXED, 0, 0,
                SkipRule.MissingEndBehavior.UNTOUCHED, 0, 5_000, 0, 0, Collections.emptyList());
        try {
            rule.validate();
            fail("Empty draft passed validation");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage().contains("start sample"));
        }
    }

    @Test
    public void requiresAtLeastEightAudibleFingerprintFrames() {
        int[] hashes = new int[61];
        Arrays.fill(hashes, 0, 7, 0x456789ab);
        SkipSample sample = new SkipSample("sample", SkipMarker.START, 2_000, 0,
                new AudioFingerprint(8_000, 64, 32, hashes));
        SkipRule rule = new SkipRule("id", "Intro", true, SkipRule.Type.FIXED, 0, 0,
                SkipRule.MissingEndBehavior.UNTOUCHED, 0, 5_000, 0, 0, Collections.singletonList(sample));
        try {
            rule.validate();
            fail("Seven audible frames passed validation");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage().contains("too little audible audio"));
        }
        hashes[7] = 0x456789ab;
        SkipSample usable = new SkipSample("sample", SkipMarker.START, 2_000, 0,
                new AudioFingerprint(8_000, 64, 32, hashes));
        rule.withSamples(Collections.singletonList(usable)).validate();
    }

    @Test
    public void fixedDurationMustBePositive() {
        int[] hashes = new int[61];
        Arrays.fill(hashes, 0x456789ab);
        SkipSample sample = new SkipSample("sample", SkipMarker.START, 2_000, 0,
                new AudioFingerprint(8_000, 64, 32, hashes));
        SkipRule rule = new SkipRule("id", "Intro", true, SkipRule.Type.FIXED, 0, 0,
                SkipRule.MissingEndBehavior.UNTOUCHED, 0, 0, 0, 0, Collections.singletonList(sample));
        try {
            rule.validate();
            fail("Zero fixed duration passed validation");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage().contains("duration"));
        }
    }

    @Test
    public void sharedStartModeDoesNotRequireEndAndSurvivesCopies() {
        int[] hashes = new int[61];
        Arrays.fill(hashes, 0x456789ab);
        SkipSample sample = new SkipSample("sample", SkipMarker.START, 2_000, 0,
                new AudioFingerprint(8_000, 64, 32, hashes));
        SkipRule rule = new SkipRule("id", "Ads", true, SkipRule.Type.BETWEEN, 0, 30_000,
                SkipRule.MissingEndBehavior.UNTOUCHED, 0, 0, 0, 0,
                Collections.singletonList(sample)).withUseStartAsEnd(true);
        rule.validate();
        assertFalse(new SkipRule("old", "Ads", true, SkipRule.Type.BETWEEN, 0, 30_000,
                SkipRule.MissingEndBehavior.UNTOUCHED, 0, 0, 0, 0,
                Arrays.asList(sample, new SkipSample("end", SkipMarker.END, 2_000, 0,
                        sample.fingerprint))).useStartAsEnd);
        assertTrue(rule.withSamples(Collections.singletonList(sample)).useStartAsEnd);
        assertTrue(rule.withEnabled(false).useStartAsEnd);
        assertFalse(rule.withUseStartAsEnd(false).useStartAsEnd);
    }
}
