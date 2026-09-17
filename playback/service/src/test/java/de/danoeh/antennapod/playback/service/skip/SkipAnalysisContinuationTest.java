package de.danoeh.antennapod.playback.service.skip;

import android.content.Context;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = SkipAnalysisContinuationTest.AudioDecoderShadow.class)
public class SkipAnalysisContinuationTest {
    private SkipManager manager;
    private SkipRule rule;
    private final Uri source = Uri.parse("content://skip-tests/repeated-markers");

    @Before
    public void setUp() {
        manager = SkipManager.getInstance(ApplicationProvider.getApplicationContext());
        float[] sample = new float[16_384];
        Random random = new Random(17);
        for (int index = 0; index < sample.length; index++) {
            sample[index] = random.nextFloat() - 0.5f;
        }
        AudioDecoderShadow.audio = new float[60_000 * 8];
        System.arraycopy(sample, 0, AudioDecoderShadow.audio, 8_000 * 8, sample.length);
        System.arraycopy(sample, 0, AudioDecoderShadow.audio, 40_000 * 8, sample.length);
        SkipSample marker = new SkipSample("sample", SkipMarker.START, 2_048, 0,
                SkipFingerprint.fromPcm(sample, 8_000));
        rule = new SkipRule("rule", "Promotion", true, SkipRule.Type.FIXED, 0, 0,
                SkipRule.MissingEndBehavior.UNTOUCHED, 0, 5_000, 0, 0, Collections.singletonList(marker));
    }

    @Test
    public void playbackContinuesAfterFirstOccurrenceUntilEpisodeIsCovered() throws Exception {
        String feedId = UUID.randomUUID().toString();
        manager.saveRule(feedId, rule);
        AtomicBoolean partialMatch = new AtomicBoolean();
        AtomicReference<SkipAnalysisSnapshot> result = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        try (SkipSubscription subscription = manager.observe(feedId, "episode", snapshot -> {
            if (snapshot.status == SkipAnalysisStatus.ANALYZING && snapshot.occurrences.size() == 1) {
                partialMatch.set(true);
            }
            if (snapshot.status != SkipAnalysisStatus.ANALYZING) {
                result.set(snapshot);
                completed.countDown();
            }
        })) {
            SkipTask task = manager.analyze(feedId, "episode", source, 60_000, 0, SkipPriority.CURRENT_PLAYBACK);
            try {
                assertTrue(completed.await(10, TimeUnit.SECONDS));
                assertEquals(SkipAnalysisStatus.READY, result.get().status);
                assertTrue(partialMatch.get());
                assertEquals(2, result.get().occurrences.size());
                assertEquals(8_000, result.get().occurrences.get(0).startMs, 32);
                assertEquals(40_000, result.get().occurrences.get(1).startMs, 32);
                assertEquals(1, result.get().coverage.size());
                assertEquals(0, result.get().coverage.get(0).startMs);
                assertEquals(60_000, result.get().coverage.get(0).endMs);
                assertTrue(task.isDone());
            } finally {
                task.cancel();
            }
        }
    }

    @Test
    public void ruleTestStopsAfterFirstOccurrence() throws Exception {
        assertRuleTestStopsEarly(false);
    }

    @Test
    public void boundedRuleTestStopsAfterFirstOccurrence() throws Exception {
        assertRuleTestStopsEarly(true);
    }

    private void assertRuleTestStopsEarly(boolean bounded) throws Exception {
        AtomicReference<SkipAnalysisSnapshot> result = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        SkipAnalysisCallback callback = snapshot -> {
            if (snapshot.status != SkipAnalysisStatus.ANALYZING) {
                result.set(snapshot);
                completed.countDown();
            }
        };
        SkipTask task = bounded ? manager.testRule(rule, source, 60_000, 8_000, callback)
                : manager.testRule(rule, source, 60_000, callback);
        try {
            assertTrue(completed.await(10, TimeUnit.SECONDS));
            assertEquals(SkipAnalysisStatus.READY, result.get().status);
            assertEquals(1, result.get().occurrences.size());
            assertEquals(8_000, result.get().occurrences.get(0).startMs, 32);
            assertEquals(1, result.get().coverage.size());
            assertTrue(result.get().coverage.get(0).endMs < 40_000);
            assertTrue(task.isDone());
        } finally {
            task.cancel();
        }
    }

    @Implements(SkipAudioDecoder.class)
    public static class AudioDecoderShadow {
        private static float[] audio;

        @Implementation
        protected static SkipAudioDecoder.DecodedAudio decode(Context context, Uri uri, long startMs, long endMs) {
            return new SkipAudioDecoder.DecodedAudio(startMs,
                    Arrays.copyOfRange(audio, (int) startMs * 8, (int) endMs * 8), endMs - startMs, true);
        }
    }
}
