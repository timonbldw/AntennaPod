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
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
        AudioDecoderShadow.incompleteAudio = false;
        AudioDecoderShadow.audio = null;
        AudioDecoderShadow.calls = 0;
        AudioDecoderShadow.fetchMissing = false;
        AudioDecoderShadow.cacheOnlyDecode = null;
        AudioDecoderShadow.minimumStartMs = 0;
        AudioDecoderShadow.partialAudioEndMs = -1;
        AudioDecoderShadow.partialAudioUri = null;
        AudioDecoderShadow.partialAudioStarts = Collections.emptySet();
        AudioDecoderShadow.unavailableStarts = Collections.emptySet();
        AudioDecoderShadow.unavailableStartsUri = null;
        AudioDecoderShadow.unavailableUri = null;
        AudioDecoderShadow.starts = Collections.synchronizedList(new ArrayList<>());
        AudioDecoderShadow.requests = Collections.synchronizedList(new ArrayList<>());
        AudioDecoderShadow.decodeStarted = null;
        AudioDecoderShadow.continueDecode = null;
        AudioDecoderShadow.blockedUri = null;
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
    public void playbackAnalysisDoesNotFetchMissingAudio() throws Exception {
        Uri source = Uri.parse("skip-cache://playback");
        CountDownLatch waiting = new CountDownLatch(1);
        AudioDecoderShadow.cacheOnlyDecode = new CountDownLatch(1);
        AudioDecoderShadow.unavailable = true;
        try (SkipSubscription subscription = manager.observe(feedId, "playback", snapshot -> {
            if (snapshot.status == SkipAnalysisStatus.WAITING_FOR_AUDIO) {
                waiting.countDown();
            }
        })) {
            SkipTask initial = manager.analyze(feedId, "playback", source, 180_000, 20_000,
                    SkipPriority.CURRENT_PLAYBACK);
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            AudioDecoderShadow.unavailable = false;
            SkipTask task = manager.analyzeForPlayback(feedId, "playback", source, 180_000, 20_000,
                    SkipPriority.CURRENT_PLAYBACK);
            assertNotSame(initial, task);
            assertTrue(initial.isCancelled());
            assertTrue(AudioDecoderShadow.cacheOnlyDecode.await(5, TimeUnit.SECONDS));
            assertFalse(AudioDecoderShadow.fetchMissing);
        }
    }

    @Test
    public void playbackAnalysisStartsAtCurrentPositionWithoutLookbehind() throws Exception {
        Uri source = Uri.parse("skip-cache://playback-boundary");
        CountDownLatch ready = new CountDownLatch(1);
        AudioDecoderShadow.minimumStartMs = 20_000;
        try (SkipSubscription subscription = manager.observe(feedId, "playback-boundary", snapshot -> {
            if (snapshot.status == SkipAnalysisStatus.WINDOW_READY) {
                ready.countDown();
            }
        })) {
            SkipTask task = manager.analyzeForPlayback(feedId, "playback-boundary", source, 180_000, 20_000,
                    SkipPriority.CURRENT_PLAYBACK);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertFalse(task.isCancelled());
        }
    }

    @Test
    public void playbackAnalysisCatchesUpToBufferedPosition() throws Exception {
        Uri source = Uri.parse("skip-cache://buffered-catch-up");
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<SkipAnalysisSnapshot> result = new AtomicReference<>();
        try (SkipSubscription subscription = manager.observe(feedId, "buffered-catch-up", snapshot -> {
            result.set(snapshot);
            if (snapshot.status == SkipAnalysisStatus.WINDOW_READY) {
                ready.countDown();
            }
        })) {
            SkipTask task = manager.analyzeForPlayback(feedId, "buffered-catch-up", source, 180_000,
                    20_000, 120_000, SkipPriority.CURRENT_PLAYBACK);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertFalse(task.isDone());
            assertTrue(result.get().coverage.get(0).endMs >= 120_000);
        }
    }

    @Test
    public void bufferedPositionUpdateWakesWaitingAnalysis() throws Exception {
        Uri source = Uri.parse("skip-cache://buffered-wakeup");
        CountDownLatch firstReady = new CountDownLatch(1);
        CountDownLatch secondReady = new CountDownLatch(1);
        AtomicReference<SkipAnalysisSnapshot> result = new AtomicReference<>();
        try (SkipSubscription subscription = manager.observe(feedId, "buffered-wakeup", snapshot -> {
            result.set(snapshot);
            if (snapshot.status == SkipAnalysisStatus.WINDOW_READY) {
                if (snapshot.coverage.get(snapshot.coverage.size() - 1).endMs < 100_000) {
                    firstReady.countDown();
                } else {
                    secondReady.countDown();
                }
            }
        })) {
            SkipTask task = manager.analyzeForPlayback(feedId, "buffered-wakeup", source, 180_000,
                    20_000, 50_000, SkipPriority.CURRENT_PLAYBACK);
            assertTrue(firstReady.await(5, TimeUnit.SECONDS));
            assertTrue(manager.updateStreamingPosition(feedId, "buffered-wakeup", 20_000, 1, 120_000));
            assertTrue(secondReady.await(5, TimeUnit.SECONDS));
            assertFalse(task.isDone());
            assertTrue(result.get().coverage.get(result.get().coverage.size() - 1).endMs >= 120_000);
        }
    }

    @Test
    public void playbackAnalysisFinishesWithNoMatchesAtEpisodeEnd() throws Exception {
        Uri source = Uri.parse("skip-cache://buffered-episode-end");
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<SkipAnalysisSnapshot> result = new AtomicReference<>();
        try (SkipSubscription subscription = manager.observe(feedId, "buffered-episode-end", snapshot -> {
            result.set(snapshot);
            if (snapshot.status == SkipAnalysisStatus.NO_MATCHES) {
                complete.countDown();
            }
        })) {
            SkipTask task = manager.analyzeForPlayback(feedId, "buffered-episode-end", source, 60_000,
                    0, 60_000, SkipPriority.CURRENT_PLAYBACK);
            assertTrue(complete.await(5, TimeUnit.SECONDS));
            assertEquals(SkipAnalysisStatus.NO_MATCHES, result.get().status);
            assertTrue(task.isDone());
            assertTrue(result.get().coverage.get(0).endMs >= 60_000);
        }
    }

    @Test
    public void playbackAnalysisKeepsPartialCoverageAndRetriesRemainingWindow() throws Exception {
        Uri source = Uri.parse("skip-cache://partial-window");
        CountDownLatch partial = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<SkipAnalysisSnapshot> result = new AtomicReference<>();
        AudioDecoderShadow.partialAudioEndMs = 40_000;
        try (SkipSubscription subscription = manager.observe(feedId, "partial-window", snapshot -> {
            result.set(snapshot);
            if (snapshot.status == SkipAnalysisStatus.WAITING_FOR_AUDIO && !snapshot.coverage.isEmpty()) {
                partial.countDown();
            } else if (snapshot.status == SkipAnalysisStatus.WINDOW_READY) {
                ready.countDown();
            }
        })) {
            SkipTask task = manager.analyzeForPlayback(feedId, "partial-window", source, 180_000, 20_000,
                    SkipPriority.CURRENT_PLAYBACK);
            assertTrue(partial.await(5, TimeUnit.SECONDS));
            assertEquals(1, result.get().coverage.size());
            assertTrue(result.get().coverage.get(0).endMs < 50_000);
            AudioDecoderShadow.partialAudioEndMs = -1;
            assertTrue(manager.updateStreamingPosition(feedId, "partial-window", 20_000, 1));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertTrue(result.get().coverage.get(0).endMs >= 50_000);
            assertFalse(task.isDone());
        }
    }

    @Test
    public void playbackAnalysisSearchesLaterCachedWindowsAfterFirstCacheMiss() throws Exception {
        Uri source = Uri.parse("skip-cache://later-window");
        AudioDecoderShadow.unavailableStartsUri = source.toString();
        AudioDecoderShadow.unavailableStarts = Collections.singleton(20_000L);
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicReference<SkipAnalysisSnapshot> result = new AtomicReference<>();
        try (SkipSubscription subscription = manager.observe(feedId, "later-window", snapshot -> {
            result.set(snapshot);
            if (snapshot.status == SkipAnalysisStatus.WAITING_FOR_AUDIO) {
                waiting.countDown();
            }
        })) {
            SkipTask task = manager.analyzeForPlayback(feedId, "later-window", source, 180_000,
                    20_000, 120_000, SkipPriority.CURRENT_PLAYBACK);
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            assertFalse(task.isDone());
            List<Long> starts = requestsFor("skip-cache://later-window");
            assertEquals(4, starts.size());
            assertEquals(20_000, starts.get(0).longValue());
            assertEquals(50_000, starts.get(1).longValue(), SkipFingerprint.HOP_MS);
            assertEquals(1, result.get().coverage.size());
            assertTrue(result.get().coverage.get(0).startMs >= 49_000);
            assertTrue(result.get().coverage.get(0).endMs >= 120_000);
        }
    }

    @Test
    public void playbackAnalysisStopsAfterOneFinitePassWhenAllWindowsMiss() throws Exception {
        Uri source = Uri.parse("skip-cache://all-missing");
        AudioDecoderShadow.unavailableUri = "skip-cache://all-missing";
        CountDownLatch waiting = new CountDownLatch(1);
        try (SkipSubscription subscription = manager.observe(feedId, "all-missing", snapshot -> {
            if (snapshot.status == SkipAnalysisStatus.WAITING_FOR_AUDIO) {
                waiting.countDown();
            }
        })) {
            SkipTask task = manager.analyzeForPlayback(feedId, "all-missing", source, 180_000,
                    20_000, 120_000, SkipPriority.CURRENT_PLAYBACK);
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            assertFalse(task.isDone());
            assertEquals(4, requestsFor("skip-cache://all-missing").size());
            Thread.sleep(100);
            assertEquals(4, requestsFor("skip-cache://all-missing").size());
        }
    }

    @Test
    public void unchangedWakeDoesNotEmitAnalyzingWhileAllWindowsStillMiss() throws Exception {
        String episodeId = "silent-retry";
        String source = "skip-cache://silent-retry";
        AudioDecoderShadow.unavailableUri = source;
        CountDownLatch firstWaiting = new CountDownLatch(1);
        CountDownLatch secondWaiting = new CountDownLatch(1);
        AtomicBoolean retrying = new AtomicBoolean();
        List<SkipAnalysisStatus> retryStatuses = Collections.synchronizedList(new ArrayList<>());
        try (SkipSubscription subscription = manager.observe(feedId, episodeId, snapshot -> {
            if (retrying.get()) {
                retryStatuses.add(snapshot.status);
                if (snapshot.status == SkipAnalysisStatus.WAITING_FOR_AUDIO) {
                    secondWaiting.countDown();
                }
            } else if (snapshot.status == SkipAnalysisStatus.WAITING_FOR_AUDIO) {
                firstWaiting.countDown();
            }
        })) {
            manager.analyzeForPlayback(feedId, episodeId, Uri.parse(source), 180_000,
                    20_000, 120_000, SkipPriority.CURRENT_PLAYBACK);
            assertTrue(firstWaiting.await(5, TimeUnit.SECONDS));
            retrying.set(true);
            assertTrue(manager.updateStreamingPosition(feedId, episodeId, 20_000, 1, 120_000));
            assertTrue(secondWaiting.await(5, TimeUnit.SECONDS));
            assertEquals(Collections.singletonList(SkipAnalysisStatus.WAITING_FOR_AUDIO), retryStatuses);
        }
    }

    @Test
    public void unchangedStreamingUpdateRetriesAndFillsDeferredGap() throws Exception {
        Uri source = Uri.parse("skip-cache://retry-gap");
        AudioDecoderShadow.unavailableStartsUri = source.toString();
        AudioDecoderShadow.unavailableStarts = Collections.singleton(20_000L);
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<SkipAnalysisSnapshot> result = new AtomicReference<>();
        try (SkipSubscription subscription = manager.observe(feedId, "retry-gap", snapshot -> {
            result.set(snapshot);
            if (snapshot.status == SkipAnalysisStatus.WAITING_FOR_AUDIO) {
                waiting.countDown();
            } else if (snapshot.status == SkipAnalysisStatus.WINDOW_READY) {
                ready.countDown();
            }
        })) {
            manager.analyzeForPlayback(feedId, "retry-gap", source, 180_000,
                    20_000, 120_000, SkipPriority.CURRENT_PLAYBACK);
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            AudioDecoderShadow.unavailableStarts = Collections.emptySet();
            assertTrue(manager.updateStreamingPosition(feedId, "retry-gap", 20_000, 1, 120_000));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertEquals(1, result.get().coverage.size());
            assertTrue(result.get().coverage.get(0).startMs <= 20_000);
            assertTrue(result.get().coverage.get(0).endMs >= 120_000);
        }
    }

    @Test
    public void partialWindowSearchesForwardAndRetainsLaterHitUntilCompletion() throws Exception {
        String partialFeedId = UUID.randomUUID().toString();
        float[] marker = randomAudio(4_000, 113);
        manager.saveRule(partialFeedId, fixedRule(marker));
        AudioDecoderShadow.audio = new float[90_000 * 8];
        System.arraycopy(marker, 0, AudioDecoderShadow.audio, 65_000 * 8, marker.length);
        AudioDecoderShadow.partialAudioEndMs = 20_000;
        AudioDecoderShadow.partialAudioUri = "skip-cache://partial-forward";
        AudioDecoderShadow.partialAudioStarts = Collections.singleton(0L);
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<SkipAnalysisSnapshot> result = new AtomicReference<>();
        try (SkipSubscription subscription = manager.observe(partialFeedId, "partial-forward", snapshot -> {
            result.set(snapshot);
            if (snapshot.status == SkipAnalysisStatus.WAITING_FOR_AUDIO && !snapshot.occurrences.isEmpty()) {
                waiting.countDown();
            } else if (snapshot.status == SkipAnalysisStatus.READY) {
                ready.countDown();
            }
        })) {
            SkipTask task = manager.analyzeForPlayback(partialFeedId, "partial-forward",
                    Uri.parse("skip-cache://partial-forward"), 90_000, 0, 90_000,
                    SkipPriority.CURRENT_PLAYBACK);
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            assertEquals(2, result.get().coverage.size());
            assertEquals(65_000, result.get().occurrences.get(0).startMs, SkipFingerprint.HOP_MS);
            AudioDecoderShadow.partialAudioEndMs = -1;
            AudioDecoderShadow.partialAudioUri = null;
            AudioDecoderShadow.partialAudioStarts = Collections.emptySet();
            assertTrue(manager.updateStreamingPosition(partialFeedId, "partial-forward", 0, 1, 90_000));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertTrue(task.isDone());
            assertEquals(65_000, result.get().occurrences.get(0).startMs, SkipFingerprint.HOP_MS);
            assertEquals(1, result.get().coverage.size());
            assertTrue(result.get().coverage.get(0).endMs >= 90_000);
        }
    }

    @Test
    public void nonAlignedBufferedEndCompletesWithoutRedundantDecode() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<SkipAnalysisSnapshot> result = new AtomicReference<>();
        try (SkipSubscription subscription = manager.observe(feedId, "non-aligned-end", snapshot -> {
            result.set(snapshot);
            if (snapshot.status == SkipAnalysisStatus.WINDOW_READY) {
                ready.countDown();
            }
        })) {
            manager.analyzeForPlayback(feedId, "non-aligned-end", Uri.parse("skip-cache://non-aligned-end"),
                    180_000, 0, 17, SkipPriority.CURRENT_PLAYBACK);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertEquals(Collections.singletonList("skip-cache://non-aligned-end:0"),
                    AudioDecoderShadow.requests);
            assertEquals(17, result.get().coverage.get(0).endMs);
        }
    }

    @Test
    public void inFlightSeekRestartsPassAtNewPosition() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AudioDecoderShadow.decodeStarted = new CountDownLatch(1);
        AudioDecoderShadow.continueDecode = new CountDownLatch(1);
        AudioDecoderShadow.blockedUri = "skip-cache://in-flight-seek";
        try (SkipSubscription subscription = manager.observe(feedId, "in-flight-seek", snapshot -> {
            if (snapshot.status == SkipAnalysisStatus.WINDOW_READY
                    && snapshot.coverage.get(snapshot.coverage.size() - 1).endMs >= 90_000) {
                ready.countDown();
            }
        })) {
            manager.analyzeForPlayback(feedId, "in-flight-seek", Uri.parse("skip-cache://in-flight-seek"),
                    180_000, 0, 90_000, SkipPriority.CURRENT_PLAYBACK);
            assertTrue(AudioDecoderShadow.decodeStarted.await(5, TimeUnit.SECONDS));
            assertTrue(manager.updateStreamingPosition(feedId, "in-flight-seek", 60_000, 1, 90_000, true));
            AudioDecoderShadow.continueDecode.countDown();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertTrue(AudioDecoderShadow.requests.contains("skip-cache://in-flight-seek:0"));
            assertTrue(AudioDecoderShadow.requests.contains("skip-cache://in-flight-seek:60000"));
        } finally {
            AudioDecoderShadow.continueDecode.countDown();
        }
    }

    @Test
    public void unchangedPartialWindowWaitsForAnotherPositionUpdate() throws Exception {
        Uri source = Uri.parse("skip-cache://partial-stall");
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicReference<SkipAnalysisSnapshot> result = new AtomicReference<>();
        AudioDecoderShadow.partialAudioEndMs = 40_010;
        try (SkipSubscription subscription = manager.observe(feedId, "partial-stall", snapshot -> {
            result.set(snapshot);
            if (snapshot.status == SkipAnalysisStatus.WAITING_FOR_AUDIO) {
                waiting.countDown();
            }
        })) {
            SkipTask task = manager.analyzeForPlayback(feedId, "partial-stall", source, 180_000, 20_000,
                    SkipPriority.CURRENT_PLAYBACK);
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            assertFalse(task.isDone());
            assertEquals(SkipAnalysisStatus.WAITING_FOR_AUDIO, result.get().status);
            assertTrue(manager.updateStreamingPosition(feedId, "partial-stall", 20_000, 1));
        }
    }

    @Test
    public void incompleteDecoderWindowRetainsJobAndRetriesOnPositionPoll() throws Exception {
        Uri source = Uri.parse("skip-cache://incomplete-window");
        AudioDecoderShadow.incompleteAudio = true;
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<SkipAnalysisSnapshot> result = new AtomicReference<>();
        try (SkipSubscription subscription = manager.observe(feedId, "incomplete-window", snapshot -> {
            result.set(snapshot);
            if (snapshot.status == SkipAnalysisStatus.WAITING_FOR_AUDIO) {
                waiting.countDown();
            } else if (snapshot.status == SkipAnalysisStatus.WINDOW_READY) {
                ready.countDown();
            }
        })) {
            SkipTask task = manager.analyze(feedId, "incomplete-window", source, 180_000, 20_000,
                    SkipPriority.CURRENT_PLAYBACK);
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            assertFalse(task.isDone());
            AudioDecoderShadow.incompleteAudio = false;
            assertTrue(manager.updateStreamingPosition(feedId, "incomplete-window", 20_000, 1));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertFalse(task.isDone());
            assertEquals(SkipAnalysisStatus.WINDOW_READY, result.get().status);
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
    public void searchedCoverageSurvivesCancelledStreamingJob() throws Exception {
        Uri source = Uri.parse("skip-cache://persisted-coverage");
        String episodeId = "persisted-coverage";
        CountDownLatch firstReady = new CountDownLatch(1);
        CountDownLatch restartedReady = new CountDownLatch(1);
        AtomicBoolean restarting = new AtomicBoolean();
        AtomicReference<SkipAnalysisSnapshot> restartedSnapshot = new AtomicReference<>();
        try (SkipSubscription subscription = manager.observe(feedId, episodeId, snapshot -> {
            if (snapshot.status != SkipAnalysisStatus.WINDOW_READY) {
                return;
            } else if (restarting.get()) {
                restartedSnapshot.set(snapshot);
                restartedReady.countDown();
            } else {
                firstReady.countDown();
            }
        })) {
            SkipTask first = manager.analyze(feedId, episodeId, source, 180_000, 20_000,
                    SkipPriority.CURRENT_PLAYBACK);
            assertTrue(firstReady.await(5, TimeUnit.SECONDS));
            assertFalse(first.isDone());
            int calls = AudioDecoderShadow.calls;
            restarting.set(true);
            first.cancel();
            assertTrue(first.isCancelled());
            SkipTask restarted = manager.analyze(feedId, episodeId, source, 180_000, 20_000,
                    SkipPriority.CURRENT_PLAYBACK);
            assertTrue(restartedReady.await(5, TimeUnit.SECONDS));
            assertFalse(restarted.isDone());
            assertEquals(1, restartedSnapshot.get().coverage.size());
            assertEquals(9_984, restartedSnapshot.get().coverage.get(0).startMs, 32);
            assertEquals(50_000, restartedSnapshot.get().coverage.get(0).endMs, 32);
            assertEquals(calls, AudioDecoderShadow.calls);
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
            if (snapshot.status == SkipAnalysisStatus.READY && !snapshot.occurrences.isEmpty()) {
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

    private static SkipRule fixedRule(float[] audio) {
        SkipSample sample = new SkipSample("sample", SkipMarker.START, audio.length / 8, 0,
                SkipFingerprint.fromPcm(audio, 8_000));
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

    private static List<Long> requestsFor(String uri) {
        List<Long> starts = new ArrayList<>();
        synchronized (AudioDecoderShadow.requests) {
            for (String request : AudioDecoderShadow.requests) {
                if (request.startsWith(uri + ":")) {
                    starts.add(Long.parseLong(request.substring(uri.length() + 1)));
                }
            }
        }
        return starts;
    }

    @Implements(SkipAudioDecoder.class)
    public static class AudioDecoderShadow {
        private static volatile boolean unavailable;
        private static volatile boolean incompleteAudio;
        private static volatile float[] audio;
        private static volatile int calls;
        private static volatile boolean fetchMissing;
        private static volatile CountDownLatch cacheOnlyDecode;
        private static volatile long minimumStartMs;
        private static volatile long partialAudioEndMs;
        private static volatile String partialAudioUri;
        private static volatile Set<Long> partialAudioStarts;
        private static volatile Set<Long> unavailableStarts;
        private static volatile String unavailableStartsUri;
        private static volatile String unavailableUri;
        private static volatile List<Long> starts;
        private static volatile List<String> requests;
        private static volatile CountDownLatch decodeStarted;
        private static volatile CountDownLatch continueDecode;
        private static volatile String blockedUri;
        private static volatile int unavailableAfterCalls;

        @Implementation
        protected static SkipAudioDecoder.DecodedAudio decode(Context context, Uri uri, long startMs, long endMs)
                throws IOException {
            return decode(context, uri, startMs, endMs, false);
        }

        @Implementation
        protected static SkipAudioDecoder.DecodedAudio decode(Context context, Uri uri, long startMs, long endMs,
                                                               boolean shouldFetchMissing)
                throws IOException {
            if (startMs < minimumStartMs) {
                throw new SkipStreamingSource.UnavailableException("cache miss before playback position");
            }
            fetchMissing = shouldFetchMissing;
            if (!shouldFetchMissing && cacheOnlyDecode != null) {
                cacheOnlyDecode.countDown();
            }
            int call = calls++;
            starts.add(startMs);
            requests.add(uri + ":" + startMs);
            CountDownLatch started = decodeStarted;
            if (started != null && uri.toString().equals(blockedUri)) {
                decodeStarted = null;
                started.countDown();
                try {
                    continueDecode.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException();
                }
            }
            if (incompleteAudio) {
                return new SkipAudioDecoder.DecodedAudio(startMs,
                        new float[(int) (endMs - startMs) * 8], endMs - startMs - 1, false);
            }
            if (unavailable) {
                throw new SkipStreamingSource.UnavailableException("cache miss");
            }
            if (uri.toString().equals(unavailableUri)
                    || uri.toString().equals(unavailableStartsUri) && unavailableStarts.contains(startMs)
                    || call >= unavailableAfterCalls) {
                throw new SkipStreamingSource.UnavailableException("cache miss");
            }
            long actualEndMs = audio == null ? endMs : Math.min(endMs, audio.length / 8L);
            boolean partial = partialAudioEndMs >= 0
                    && (partialAudioUri == null || uri.toString().equals(partialAudioUri))
                    && (partialAudioStarts.isEmpty() || partialAudioStarts.contains(startMs));
            if (partial) {
                actualEndMs = Math.min(actualEndMs, partialAudioEndMs);
            }
            float[] samples = audio == null ? new float[(int) (endMs - startMs) * 8]
                    : Arrays.copyOfRange(audio, (int) startMs * 8, (int) actualEndMs * 8);
            if (partial && actualEndMs < endMs) {
                return new SkipAudioDecoder.DecodedAudio(startMs, samples,
                        actualEndMs - startMs, false).withCacheMiss();
            }
            return new SkipAudioDecoder.DecodedAudio(startMs, samples,
                    actualEndMs - startMs, true, false);
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
