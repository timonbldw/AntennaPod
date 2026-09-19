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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
        AudioDecoderShadow.calls.set(0);
        AudioDecoderShadow.firstWindowCalls.set(0);
        AudioDecoderShadow.sessions.set(0);
        AudioDecoderShadow.closedSessions.set(0);
        AudioDecoderShadow.requests = Collections.synchronizedList(new ArrayList<>());
        AudioDecoderShadow.decoderClosed = new CountDownLatch(1);
        AudioDecoderShadow.incompleteFirstDecode = false;
        AudioDecoderShadow.incompleteAllDecodes = false;
        AudioDecoderShadow.incompleteLaterWindow = false;
        AudioDecoderShadow.incompleteLaterWindowRemaining = 0;
        AudioDecoderShadow.blockSecondDecode = false;
        AudioDecoderShadow.secondDecodeEntered = null;
        AudioDecoderShadow.secondDecodeRelease = null;
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
                assertEquals(1, AudioDecoderShadow.sessions.get());
                assertTrue(AudioDecoderShadow.decoderClosed.await(5, TimeUnit.SECONDS));
                assertEquals(1, AudioDecoderShadow.closedSessions.get());
                assertTrue(task.isDone());
            } finally {
                task.cancel();
            }
        }
    }

    @Test
    public void localPlaybackAnalysisKeepsMarkerOverlap() throws Exception {
        String feedId = UUID.randomUUID().toString();
        manager.saveRule(feedId, rule);
        CountDownLatch completed = new CountDownLatch(1);
        try (SkipSubscription subscription = manager.observe(feedId, "local-playback", snapshot -> {
            if (snapshot.status == SkipAnalysisStatus.READY) {
                completed.countDown();
            }
        })) {
            manager.analyzeForPlayback(feedId, "local-playback", Uri.parse("/skip-tests/local-playback.mp3"),
                    60_000, 40_000, SkipPriority.CURRENT_PLAYBACK);
            assertTrue(completed.await(10, TimeUnit.SECONDS));
            assertTrue(AudioDecoderShadow.requests.get(0) < 35_000);
        }
    }

    @Test
    public void continuesAfterIncompleteLocalDecoderWindow() throws Exception {
        String feedId = UUID.randomUUID().toString();
        manager.saveRule(feedId, rule);
        AudioDecoderShadow.incompleteFirstDecode = true;
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<SkipAnalysisSnapshot> result = new AtomicReference<>();
        AtomicReference<SkipAnalysisSnapshot> partial = new AtomicReference<>();
        try (SkipSubscription subscription = manager.observe(feedId, "episode", snapshot -> {
            if (snapshot.status == SkipAnalysisStatus.ANALYZING && !snapshot.coverage.isEmpty()) {
                partial.compareAndSet(null, snapshot);
            }
            if (snapshot.status == SkipAnalysisStatus.READY || snapshot.status == SkipAnalysisStatus.ERROR) {
                result.set(snapshot);
                completed.countDown();
            }
        })) {
            SkipTask task = manager.analyze(feedId, "episode", Uri.parse("/skip-tests/incomplete-file.mp3"),
                    60_000, 0, SkipPriority.CURRENT_PLAYBACK);
            assertTrue(completed.await(10, TimeUnit.SECONDS));
            assertEquals(SkipAnalysisStatus.READY, result.get().status);
            assertTrue(partial.get() != null);
            assertEquals(25_000, partial.get().coverage.get(0).endMs);
            assertEquals(2, AudioDecoderShadow.firstWindowCalls.get());
            task.cancel();
        }
    }

    @Test
    public void repeatedIncompleteLocalDecoderWindowBecomesError() throws Exception {
        String feedId = UUID.randomUUID().toString();
        manager.saveRule(feedId, rule);
        AudioDecoderShadow.incompleteAllDecodes = true;
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<SkipAnalysisSnapshot> result = new AtomicReference<>();
        try (SkipSubscription subscription = manager.observe(feedId, "episode", snapshot -> {
            if (snapshot.status == SkipAnalysisStatus.READY || snapshot.status == SkipAnalysisStatus.ERROR) {
                result.set(snapshot);
                completed.countDown();
            }
        })) {
            SkipTask task = manager.analyze(feedId, "episode", Uri.parse("/skip-tests/incomplete-file.mp3"),
                    60_000, 0, SkipPriority.CURRENT_PLAYBACK);
            assertTrue(completed.await(10, TimeUnit.SECONDS));
            assertEquals(SkipAnalysisStatus.ERROR, result.get().status);
            assertTrue(result.get().occurrences.isEmpty());
            assertTrue(result.get().coverage.isEmpty());
            assertEquals(2, AudioDecoderShadow.firstWindowCalls.get());
            assertEquals(2, AudioDecoderShadow.calls.get());
            assertTrue(task.isDone());
        }
    }

    @Test
    public void continuesAfterIncompleteLaterLocalDecoderWindow() throws Exception {
        String feedId = UUID.randomUUID().toString();
        manager.saveRule(feedId, rule);
        AudioDecoderShadow.incompleteLaterWindow = true;
        AudioDecoderShadow.incompleteLaterWindowRemaining = 2;
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<SkipAnalysisSnapshot> result = new AtomicReference<>();
        try (SkipSubscription subscription = manager.observe(feedId, "episode", snapshot -> {
            if (snapshot.status == SkipAnalysisStatus.READY || snapshot.status == SkipAnalysisStatus.ERROR) {
                result.set(snapshot);
                completed.countDown();
            }
        })) {
            SkipTask task = manager.analyze(feedId, "episode", Uri.parse("/skip-tests/incomplete-later.mp3"),
                    60_000, 0, SkipPriority.CURRENT_PLAYBACK);
            assertTrue(completed.await(10, TimeUnit.SECONDS));
            assertEquals(SkipAnalysisStatus.READY, result.get().status);
            assertEquals(2, result.get().occurrences.size());
            assertTrue(result.get().coverage.get(0).endMs >= 60_000);
            assertTrue(AudioDecoderShadow.calls.get() > 2);
            task.cancel();
        }
    }

    @Test
    public void completedSearchWindowSurvivesCancellation() throws Exception {
        String feedId = UUID.randomUUID().toString();
        manager.saveRule(feedId, rule);
        Uri source = Uri.parse("/skip-tests/cancelled-file.mp3");
        CountDownLatch partial = new CountDownLatch(1);
        CountDownLatch secondDecode = new CountDownLatch(1);
        CountDownLatch releaseSecondDecode = new CountDownLatch(1);
        AudioDecoderShadow.blockSecondDecode = true;
        AudioDecoderShadow.secondDecodeEntered = secondDecode;
        AudioDecoderShadow.secondDecodeRelease = releaseSecondDecode;
        try (SkipSubscription subscription = manager.observe(feedId, "episode", snapshot -> {
            if (snapshot.status == SkipAnalysisStatus.ANALYZING && snapshot.occurrences.size() == 1) {
                partial.countDown();
            }
        })) {
            SkipTask task = manager.analyze(feedId, "episode", source, 60_000, 0,
                    SkipPriority.CURRENT_PLAYBACK);
            assertTrue(partial.await(5, TimeUnit.SECONDS));
            assertTrue(secondDecode.await(5, TimeUnit.SECONDS));
            task.cancel();
            releaseSecondDecode.countDown();
            assertTrue(task.isCancelled());
            assertTrue(AudioDecoderShadow.decoderClosed.await(5, TimeUnit.SECONDS));
            assertEquals(1, AudioDecoderShadow.closedSessions.get());
        } finally {
            AudioDecoderShadow.blockSecondDecode = false;
            AudioDecoderShadow.secondDecodeEntered = null;
            AudioDecoderShadow.secondDecodeRelease = null;
            releaseSecondDecode.countDown();
        }

        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<SkipAnalysisSnapshot> result = new AtomicReference<>();
        try (SkipSubscription subscription = manager.observe(feedId, "episode", snapshot -> {
            if (snapshot.status == SkipAnalysisStatus.READY) {
                result.set(snapshot);
                completed.countDown();
            }
        })) {
            manager.analyze(feedId, "episode", source, 60_000, 0, SkipPriority.CURRENT_PLAYBACK);
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(2, result.get().occurrences.size());
            assertEquals(1, AudioDecoderShadow.firstWindowCalls.get());
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
        private static final AtomicInteger calls = new AtomicInteger();
        private static final AtomicInteger firstWindowCalls = new AtomicInteger();
        private static final AtomicInteger sessions = new AtomicInteger();
        private static final AtomicInteger closedSessions = new AtomicInteger();
        private static volatile boolean incompleteFirstDecode;
        private static volatile boolean incompleteAllDecodes;
        private static volatile boolean incompleteLaterWindow;
        private static volatile int incompleteLaterWindowRemaining;
        private static volatile boolean blockSecondDecode;
        private static volatile CountDownLatch secondDecodeEntered;
        private static volatile CountDownLatch secondDecodeRelease;
        private static volatile CountDownLatch decoderClosed;
        private static volatile List<Long> requests;

        @Implementation
        protected static SkipAudioDecoder.Session openSession(Context context, Uri uri, boolean fetchMissing,
                                                               boolean analysisFetch) {
            sessions.incrementAndGet();
            return new SkipAudioDecoder.Session(startMs -> new SkipAudioDecoder.Decoder() {
                @Override
                public boolean canDecode(long requestedStartMs) {
                    return true;
                }

                @Override
                public SkipAudioDecoder.DecodedAudio decode(long requestedStartMs, long requestedEndMs)
                        throws IOException, InterruptedException {
                    return decodeWindow(requestedStartMs, requestedEndMs);
                }

                @Override
                public void close() {
                    closedSessions.incrementAndGet();
                    decoderClosed.countDown();
                }
            });
        }

        private static SkipAudioDecoder.DecodedAudio decodeWindow(long startMs, long endMs)
                throws InterruptedException {
            int call = calls.getAndIncrement();
            requests.add(startMs);
            if (startMs == 0) {
                firstWindowCalls.incrementAndGet();
            }
            if (incompleteAllDecodes) {
                int decodedEndMs = (int) startMs + 100;
                return new SkipAudioDecoder.DecodedAudio(startMs,
                        Arrays.copyOfRange(audio, (int) startMs * 8, decodedEndMs * 8), 100, false);
            }
            if (incompleteLaterWindow && startMs > 0 && incompleteLaterWindowRemaining > 0) {
                incompleteLaterWindowRemaining--;
                int decodedEndMs = Math.max((int) startMs, (int) endMs - 5_000);
                return new SkipAudioDecoder.DecodedAudio(startMs,
                        Arrays.copyOfRange(audio, (int) startMs * 8, decodedEndMs * 8), decodedEndMs - startMs,
                        false);
            }
            if (incompleteFirstDecode && call < 2) {
                int decodedEndMs = Math.max((int) startMs, (int) endMs - 5_000);
                return new SkipAudioDecoder.DecodedAudio(startMs,
                        Arrays.copyOfRange(audio, (int) startMs * 8, decodedEndMs * 8), decodedEndMs - startMs,
                        false);
            }
            if (blockSecondDecode && call == 1) {
                CountDownLatch entered = secondDecodeEntered;
                CountDownLatch release = secondDecodeRelease;
                if (entered != null) {
                    entered.countDown();
                }
                if (release != null) {
                    release.await();
                }
            }
            return new SkipAudioDecoder.DecodedAudio(startMs,
                    Arrays.copyOfRange(audio, (int) startMs * 8, (int) endMs * 8), endMs - startMs, true);
        }
    }
}
