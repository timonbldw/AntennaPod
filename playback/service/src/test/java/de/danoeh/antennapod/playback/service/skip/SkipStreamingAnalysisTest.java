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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {SkipStreamingAnalysisTest.AudioDecoderShadow.class,
        SkipStreamingAnalysisTest.StreamingSourceShadow.class})
public class SkipStreamingAnalysisTest {
    private SkipManager manager;
    private String feedId;

    @Before
    public void setUp() throws Exception {
        manager = SkipManager.getInstance(ApplicationProvider.getApplicationContext());
        feedId = UUID.randomUUID().toString();
        manager.saveRule(feedId, fixedRule());
        AudioDecoderShadow.unavailable = false;
        AudioDecoderShadow.audio = null;
        AudioDecoderShadow.calls = 0;
        AudioDecoderShadow.fetchMissing = false;
        AudioDecoderShadow.unavailableAfterCalls = Integer.MAX_VALUE;
        StreamingSourceShadow.available = true;
    }

    @Test
    public void cacheStallRetainsJobAndRetriesOnPositionPoll() throws Exception {
        Uri source = Uri.parse("skip-cache://stall");
        AudioDecoderShadow.unavailable = true;
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<SkipAnalysisSnapshot> result = new AtomicReference<>();
        try (SkipSubscription subscription = manager.observe(feedId, "episode", snapshot -> {
            result.set(snapshot);
            if (snapshot.status == SkipAnalysisStatus.WAITING_FOR_AUDIO) {
                waiting.countDown();
            } else if (snapshot.status == SkipAnalysisStatus.WINDOW_READY) {
                ready.countDown();
            }
        })) {
            SkipTask task = manager.analyze(feedId, "episode", source, 180_000, 20_000,
                    SkipPriority.CURRENT_PLAYBACK);
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            assertFalse(task.isDone());
            AudioDecoderShadow.unavailable = false;
            assertTrue(manager.updateStreamingPosition(feedId, "episode", 20_000, 1));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertFalse(task.isDone());
            assertFalse(result.get().coverage.isEmpty());
            assertTrue(AudioDecoderShadow.fetchMissing);
        }
    }

    @Test
    public void seekWakesCoveredWindowForNewVicinity() throws Exception {
        Uri source = Uri.parse("skip-cache://seek");
        CountDownLatch firstReady = new CountDownLatch(1);
        CountDownLatch secondReady = new CountDownLatch(1);
        AtomicReference<SkipAnalysisSnapshot> result = new AtomicReference<>();
        try (SkipSubscription subscription = manager.observe(feedId, "episode", snapshot -> {
            if (snapshot.status == SkipAnalysisStatus.WINDOW_READY) {
                result.set(snapshot);
                if (snapshot.coverage.get(snapshot.coverage.size() - 1).endMs < 100_000) {
                    firstReady.countDown();
                } else {
                    secondReady.countDown();
                }
            }
        })) {
            manager.analyze(feedId, "episode", source, 180_000, 10_000, SkipPriority.CURRENT_PLAYBACK);
            assertTrue(firstReady.await(5, TimeUnit.SECONDS));
            assertTrue(manager.updateStreamingPosition(feedId, "episode", 120_000, 2));
            assertTrue(secondReady.await(5, TimeUnit.SECONDS));
            assertTrue(result.get().coverage.get(result.get().coverage.size() - 1).endMs >= 120_000);
        }
    }

    @Test
    public void replacementSessionCancelsOldJobAndOwnsSnapshot() throws Exception {
        Uri firstSource = Uri.parse("skip-cache://first");
        Uri secondSource = Uri.parse("skip-cache://second");
        AudioDecoderShadow.unavailable = true;
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch replaced = new CountDownLatch(1);
        AtomicReference<SkipAnalysisSnapshot> result = new AtomicReference<>();
        try (SkipSubscription subscription = manager.observe(feedId, "episode", snapshot -> {
            result.set(snapshot);
            if (snapshot.status == SkipAnalysisStatus.WAITING_FOR_AUDIO) {
                waiting.countDown();
            } else if (snapshot.status == SkipAnalysisStatus.WINDOW_READY
                    && secondSource.toString().equals(snapshot.sourceIdentity)) {
                replaced.countDown();
            }
        })) {
            SkipTask first = manager.analyze(feedId, "episode", firstSource, 180_000, 0,
                    SkipPriority.CURRENT_PLAYBACK);
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            AudioDecoderShadow.unavailable = false;
            SkipTask second = manager.analyze(feedId, "episode", secondSource, 180_000, 0,
                    SkipPriority.CURRENT_PLAYBACK);
            assertNotSame(first, second);
            assertTrue(first.isCancelled());
            assertTrue(replaced.await(5, TimeUnit.SECONDS));
            assertEquals(secondSource.toString(), result.get().sourceIdentity);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rawHttpAnalysisIsRejected() {
        manager.analyze(feedId, "episode", Uri.parse("https://example.com/audio.mp3"), 180_000, 0,
                SkipPriority.CURRENT_PLAYBACK);
    }

    @Test
    public void unboundedOnlyRulesRequireDownload() throws Exception {
        String unboundedFeedId = UUID.randomUUID().toString();
        manager.saveRule(unboundedFeedId, unboundedRule());
        CountDownLatch required = new CountDownLatch(1);
        try (SkipSubscription subscription = manager.observe(unboundedFeedId, "episode", snapshot -> {
            if (snapshot.status == SkipAnalysisStatus.DOWNLOAD_REQUIRED) {
                required.countDown();
            }
        })) {
            SkipTask task = manager.analyze(unboundedFeedId, "episode", Uri.parse("skip-cache://unbounded"),
                    180_000, 0, SkipPriority.CURRENT_PLAYBACK);
            assertTrue(required.await(5, TimeUnit.SECONDS));
            assertTrue(task.isDone());
        }
    }

    @Test
    public void cacheStallRetainsPendingStartForMissingEndFallback() throws Exception {
        String betweenFeedId = UUID.randomUUID().toString();
        float[] startAudio = new float[16_384];
        Random random = new Random(31);
        for (int index = 0; index < startAudio.length; index++) {
            startAudio[index] = random.nextFloat() - 0.5f;
        }
        manager.saveRule(betweenFeedId, boundedMissingEndRule(startAudio));
        AudioDecoderShadow.audio = new float[180_000 * 8];
        System.arraycopy(startAudio, 0, AudioDecoderShadow.audio, 8_000 * 8, startAudio.length);
        AudioDecoderShadow.unavailableAfterCalls = 1;
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<SkipAnalysisSnapshot> result = new AtomicReference<>();
        try (SkipSubscription subscription = manager.observe(betweenFeedId, "episode", snapshot -> {
            if (snapshot.status == SkipAnalysisStatus.WAITING_FOR_AUDIO) {
                waiting.countDown();
            } else if (snapshot.status == SkipAnalysisStatus.WINDOW_READY && !snapshot.occurrences.isEmpty()) {
                result.set(snapshot);
                ready.countDown();
            }
        })) {
            manager.analyze(betweenFeedId, "episode", Uri.parse("skip-cache://pending-start"),
                    180_000, 0, SkipPriority.CURRENT_PLAYBACK);
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            AudioDecoderShadow.unavailableAfterCalls = Integer.MAX_VALUE;
            assertTrue(manager.updateStreamingPosition(betweenFeedId, "episode", 0, 1));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertEquals(8_000, result.get().occurrences.get(0).startMs, SkipFingerprint.HOP_MS);
            assertEquals(13_000, result.get().occurrences.get(0).endMs, SkipFingerprint.HOP_MS);
        }
    }

    @Test
    public void independentlyMatchedFiveHundredMillisecondJinglesPairInPipeline() throws Exception {
        String betweenFeedId = UUID.randomUUID().toString();
        float[] startAudio = randomAudio(4_000, 71);
        float[] endAudio = randomAudio(4_000, 83);
        SkipSample start = new SkipSample("start", SkipMarker.START, 500, 0,
                SkipFingerprint.fromPcm(startAudio, 8_000));
        SkipSample end = new SkipSample("end", SkipMarker.END, 500, 0,
                SkipFingerprint.fromPcm(endAudio, 8_000));
        manager.saveRule(betweenFeedId, new SkipRule("short", "Promotion", true,
                SkipRule.Type.BETWEEN, 1_000, 30_000, SkipRule.MissingEndBehavior.UNTOUCHED,
                0, 0, 0, 0, Arrays.asList(start, end)));
        AudioDecoderShadow.audio = new float[60_000 * 8];
        System.arraycopy(startAudio, 0, AudioDecoderShadow.audio, 5_000 * 8, startAudio.length);
        System.arraycopy(endAudio, 0, AudioDecoderShadow.audio, 20_000 * 8, endAudio.length);
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<SkipAnalysisSnapshot> result = new AtomicReference<>();
        try (SkipSubscription subscription = manager.observe(betweenFeedId, "episode", snapshot -> {
            if (snapshot.status == SkipAnalysisStatus.WINDOW_READY && !snapshot.occurrences.isEmpty()) {
                result.set(snapshot);
                ready.countDown();
            }
        })) {
            manager.analyze(betweenFeedId, "episode", Uri.parse("skip-cache://short-jingles"),
                    60_000, 0, SkipPriority.CURRENT_PLAYBACK);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertEquals(5_000, result.get().occurrences.get(0).startMs, SkipFingerprint.HOP_MS);
            assertEquals(20_500, result.get().occurrences.get(0).endMs, SkipFingerprint.HOP_MS);
            assertTrue(result.get().detections.isEmpty());
        }
    }

    @Test
    public void sharedJinglesPairAfterSeekingWithoutLookbehind() throws Exception {
        String betweenFeedId = UUID.randomUUID().toString();
        float[] jingle = randomAudio(4_000, 97);
        SkipSample start = new SkipSample("start", SkipMarker.START, 500, 0,
                SkipFingerprint.fromPcm(jingle, 8_000));
        manager.saveRule(betweenFeedId, new SkipRule("shared", "Promotion", true,
                SkipRule.Type.BETWEEN, 10_000, 40_000, SkipRule.MissingEndBehavior.UNTOUCHED,
                0, 0, 0, 0, Collections.singletonList(start)).withUseStartAsEnd(true));
        AudioDecoderShadow.audio = new float[120_000 * 8];
        System.arraycopy(jingle, 0, AudioDecoderShadow.audio, 40_000 * 8, jingle.length);
        System.arraycopy(jingle, 0, AudioDecoderShadow.audio, 60_000 * 8, jingle.length);
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<SkipAnalysisSnapshot> result = new AtomicReference<>();
        try (SkipSubscription subscription = manager.observe(betweenFeedId, "episode", snapshot -> {
            if (snapshot.status == SkipAnalysisStatus.WINDOW_READY && !snapshot.occurrences.isEmpty()) {
                result.set(snapshot);
                ready.countDown();
            }
        })) {
            manager.analyze(betweenFeedId, "episode", Uri.parse("skip-cache://shared-jingles"),
                    120_000, 39_000, SkipPriority.CURRENT_PLAYBACK);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertEquals(40_000, result.get().occurrences.get(0).startMs, SkipFingerprint.HOP_MS);
            assertEquals(60_500, result.get().occurrences.get(0).endMs, SkipFingerprint.HOP_MS);
            assertTrue(result.get().detections.isEmpty());
        }
    }

    private static SkipRule fixedRule() {
        int[] hashes = new int[61];
        Arrays.fill(hashes, 0x456789ab);
        SkipSample sample = new SkipSample("sample", SkipMarker.START, 2_000, 0,
                new AudioFingerprint(8_000, 64, 32, hashes));
        return new SkipRule("rule", "Intro", true, SkipRule.Type.FIXED, 0, 0,
                SkipRule.MissingEndBehavior.UNTOUCHED, 0, 5_000, 0, 0, Collections.singletonList(sample));
    }

    private static SkipRule unboundedRule() {
        int[] hashes = new int[61];
        Arrays.fill(hashes, 0x456789ab);
        SkipSample start = new SkipSample("start", SkipMarker.START, 2_000, 0,
                new AudioFingerprint(8_000, 64, 32, hashes));
        SkipSample end = new SkipSample("end", SkipMarker.END, 2_000, 0,
                new AudioFingerprint(8_000, 64, 32, hashes));
        return new SkipRule("unbounded", "Promotion", true, SkipRule.Type.BETWEEN, 0, 0,
                SkipRule.MissingEndBehavior.UNTOUCHED, 0, 0, 0, 0, Arrays.asList(start, end));
    }

    private static SkipRule boundedMissingEndRule(float[] startAudio) {
        float[] endAudio = new float[startAudio.length];
        Random random = new Random(47);
        for (int index = 0; index < endAudio.length; index++) {
            endAudio[index] = random.nextFloat() - 0.5f;
        }
        SkipSample start = new SkipSample("start", SkipMarker.START, 2_048, 0,
                SkipFingerprint.fromPcm(startAudio, 8_000));
        SkipSample end = new SkipSample("end", SkipMarker.END, 2_048, 0,
                SkipFingerprint.fromPcm(endAudio, 8_000));
        return new SkipRule("missing-end", "Promotion", true, SkipRule.Type.BETWEEN, 0, 40_000,
                SkipRule.MissingEndBehavior.FIXED, 5_000, 0, 0, Arrays.asList(start, end));
    }

    private static float[] randomAudio(int length, long seed) {
        float[] audio = new float[length];
        Random random = new Random(seed);
        for (int index = 0; index < audio.length; index++) {
            audio[index] = random.nextFloat() - 0.5f;
        }
        return audio;
    }

    @Implements(SkipAudioDecoder.class)
    public static class AudioDecoderShadow {
        private static volatile boolean unavailable;
        private static volatile float[] audio;
        private static volatile int calls;
        private static volatile boolean fetchMissing;
        private static volatile int unavailableAfterCalls;

        @Implementation
        protected static SkipAudioDecoder.DecodedAudio decode(Context context, Uri uri, long startMs, long endMs)
                throws SkipStreamingSource.UnavailableException {
            return decode(context, uri, startMs, endMs, false);
        }

        @Implementation
        protected static SkipAudioDecoder.DecodedAudio decode(Context context, Uri uri, long startMs, long endMs,
                                                               boolean shouldFetchMissing)
                throws SkipStreamingSource.UnavailableException {
            fetchMissing = shouldFetchMissing;
            if (unavailable) {
                throw new SkipStreamingSource.UnavailableException("cache miss");
            }
            if (calls++ >= unavailableAfterCalls) {
                throw new SkipStreamingSource.UnavailableException("cache miss");
            }
            long actualEndMs = audio == null ? endMs : Math.min(endMs, audio.length / 8L);
            float[] samples = audio == null ? new float[(int) (endMs - startMs) * 8]
                    : Arrays.copyOfRange(audio, (int) startMs * 8, (int) actualEndMs * 8);
            return new SkipAudioDecoder.DecodedAudio(startMs, samples,
                    actualEndMs - startMs, true, actualEndMs < endMs);
        }
    }

    @Implements(SkipStreamingSource.class)
    public static class StreamingSourceShadow {
        private static volatile boolean available;

        @Implementation
        protected static boolean isStreaming(Uri uri) {
            return uri != null && "skip-cache".equals(uri.getScheme());
        }

        @Implementation
        protected static boolean isAvailable(Uri uri) {
            return available;
        }
    }
}
