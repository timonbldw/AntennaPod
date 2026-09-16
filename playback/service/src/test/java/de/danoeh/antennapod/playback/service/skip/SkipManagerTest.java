package de.danoeh.antennapod.playback.service.skip;

import android.content.Context;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class SkipManagerTest {
    @Test
    public void draftDoesNotPersistInvalidPlaceholder() throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        SkipManager manager = SkipManager.getInstance(context);
        String feedId = UUID.randomUUID().toString();
        SkipRule draft = manager.createRule(feedId, "Intro", SkipRule.Type.FIXED);
        assertEquals("Intro", draft.name);
        assertTrue(manager.getRulesOrThrow(feedId).isEmpty());
    }

    @Test
    public void ruleInvalidationNotifiesObserverAndAllowsSynchronousRestart() throws Exception {
        assertInvalidationRestart(Uri.parse("/missing-but-unused-for-disabled-rules.mp3"));
    }

    @Test
    public void contentRuleInvalidationRestartsWithoutStartupInvalidationLoop() throws Exception {
        assertInvalidationRestart(Uri.parse("content://skip-tests/disabled-episode"));
    }

    private static void assertInvalidationRestart(Uri source) throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SkipManager manager = SkipManager.getInstance(context);
        String feedId = UUID.randomUUID().toString();
        SkipRule rule = disabledRule();
        manager.saveRule(feedId, rule);
        CountDownLatch firstReady = new CountDownLatch(1);
        CountDownLatch restartedReady = new CountDownLatch(1);
        CountDownLatch deletedReady = new CountDownLatch(1);
        AtomicInteger invalidations = new AtomicInteger();
        AtomicReference<SkipTask> restarted = new AtomicReference<>();
        try (SkipSubscription subscription = manager.observe(feedId, "episode", snapshot -> {
            if (snapshot.status == SkipAnalysisStatus.NOT_ANALYZED) {
                invalidations.incrementAndGet();
                SkipTask previous = restarted.get();
                if (previous != null) {
                    previous.cancel();
                }
                restarted.set(manager.analyze(feedId, "episode", source, 60_000, 0, SkipPriority.HIGH));
            } else if (snapshot.status == SkipAnalysisStatus.NO_MATCHES) {
                if (snapshot.rulesRevision == 1) {
                    firstReady.countDown();
                } else if (snapshot.rulesRevision == 2) {
                    restartedReady.countDown();
                } else if (snapshot.rulesRevision == 3) {
                    deletedReady.countDown();
                }
            }
        })) {
            SkipTask first = manager.analyze(feedId, "episode", source, 60_000, 0, SkipPriority.HIGH);
            assertTrue(firstReady.await(5, TimeUnit.SECONDS));
            assertEquals(0, invalidations.get());
            assertSame(first, manager.analyze(feedId, "episode", source, 60_000, 20_000, SkipPriority.HIGH));
            manager.saveRule(feedId, rule);
            assertTrue(first.isCancelled());
            assertNotSame(first, restarted.get());
            assertTrue(restartedReady.await(5, TimeUnit.SECONDS));
            assertEquals(2, manager.getSnapshot(feedId, "episode").rulesRevision);
            assertEquals(1, invalidations.get());
            manager.deleteRule(feedId, rule.id);
            assertTrue(deletedReady.await(5, TimeUnit.SECONDS));
            assertEquals(2, invalidations.get());
            assertSame(restarted.get(), manager.analyze(feedId, "episode", source, 60_000, 40_000,
                    SkipPriority.CURRENT_PLAYBACK));
        }
    }

    @Test
    public void contentReplacementFromCallbackDoesNotInterruptReplacementOrPublishOldCompletion() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SkipManager manager = SkipManager.getInstance(context);
        String feedId = UUID.randomUUID().toString();
        manager.saveRule(feedId, disabledRule());
        Uri source = Uri.parse("content://skip-tests/replacement");
        AtomicBoolean replace = new AtomicBoolean(true);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicInteger completions = new AtomicInteger();
        AtomicReference<SkipTask> replacement = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        try (SkipSubscription subscription = manager.observe(feedId, "episode", snapshot -> {
            if (snapshot.status == SkipAnalysisStatus.ANALYZING && replace.compareAndSet(true, false)) {
                replacement.set(manager.analyze(feedId, "episode", source, 60_000, 0, SkipPriority.HIGH));
                interrupted.set(Thread.currentThread().isInterrupted());
            } else if (snapshot.status == SkipAnalysisStatus.NO_MATCHES) {
                completions.incrementAndGet();
                ready.countDown();
            }
        })) {
            SkipTask first = manager.analyze(feedId, "episode", source, 60_000, 0, SkipPriority.HIGH);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertTrue(first.isCancelled());
            assertNotSame(first, replacement.get());
            assertFalse(interrupted.get());
            assertFalse(replacement.get().isCancelled());
            assertTrue(replacement.get().isDone());
            assertEquals(1, completions.get());
            first.cancel();
            assertFalse(replacement.get().isCancelled());
        }
    }

    @Test
    public void cancellationDuringPublicationStopsRemainingCallbacks() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SkipManager manager = SkipManager.getInstance(context);
        String feedId = UUID.randomUUID().toString();
        AtomicReference<SkipTask> task = new AtomicReference<>();
        AtomicInteger laterCallbacks = new AtomicInteger();
        CountDownLatch cancelled = new CountDownLatch(1);
        try (SkipSubscription first = manager.observe(feedId, "episode", snapshot -> {
            task.get().cancel();
            cancelled.countDown();
        }); SkipSubscription second = manager.observe(feedId, "episode", snapshot -> {
            laterCallbacks.incrementAndGet();
        })) {
            synchronized (manager) {
                task.set(manager.analyze(feedId, "episode", Uri.parse("content://skip-tests/cancelled"),
                        60_000, 0, SkipPriority.HIGH));
                assertEquals(SkipAnalysisStatus.ANALYZING, manager.getSnapshot(feedId, "episode").status);
            }
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            synchronized (manager) {
                assertEquals(0, laterCallbacks.get());
                assertTrue(task.get().isCancelled());
            }
        }
    }

    @Test
    public void activeFileRequestsShareTaskWithoutReentrantStartupCallback() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SkipManager manager = SkipManager.getInstance(context);
        String feedId = UUID.randomUUID().toString();
        Uri source = Uri.parse("/skip-tests/shared.mp3");
        CountDownLatch ready = new CountDownLatch(1);
        try (SkipSubscription subscription = manager.observe(feedId, "episode", snapshot -> {
            if (snapshot.status == SkipAnalysisStatus.NO_MATCHES) {
                ready.countDown();
            }
        })) {
            SkipTask first;
            synchronized (manager) {
                first = manager.analyze(feedId, "episode", source, 60_000, 0, SkipPriority.HIGH);
                assertSame(first, manager.analyze(feedId, "episode", source, 60_000, 25_000,
                        SkipPriority.CURRENT_PLAYBACK));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertSame(first, manager.analyze(feedId, "episode", source, 60_000, 40_000,
                    SkipPriority.CURRENT_PLAYBACK));
        }
    }

    @Test
    public void queuedContentReplacementDropsOldJobCallbacks() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SkipManager manager = SkipManager.getInstance(context);
        String feedId = UUID.randomUUID().toString();
        Uri source = Uri.parse("content://skip-tests/queued-replacement");
        CountDownLatch ready = new CountDownLatch(1);
        AtomicInteger analyzing = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        try (SkipSubscription subscription = manager.observe(feedId, "episode", snapshot -> {
            if (snapshot.status == SkipAnalysisStatus.ANALYZING) {
                analyzing.incrementAndGet();
            } else if (snapshot.status == SkipAnalysisStatus.NO_MATCHES) {
                completed.incrementAndGet();
                ready.countDown();
            }
        })) {
            SkipTask replacement;
            synchronized (manager) {
                SkipTask original = manager.analyze(feedId, "episode", source, 60_000, 0, SkipPriority.HIGH);
                replacement = manager.analyze(feedId, "episode", source, 60_000, 0, SkipPriority.HIGH);
                assertNotSame(original, replacement);
                assertTrue(original.isCancelled());
                original.cancel();
                assertFalse(replacement.isCancelled());
                assertEquals(0, analyzing.get());
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertEquals(1, analyzing.get());
            assertEquals(1, completed.get());
            assertTrue(replacement.isDone());
        }
    }

    private static SkipRule disabledRule() {
        int[] hashes = new int[61];
        Arrays.fill(hashes, 0x456789ab);
        SkipSample sample = new SkipSample("sample", SkipMarker.START, 2_000, 0,
                new AudioFingerprint(8_000, 64, 32, hashes));
        return new SkipRule("rule", "Intro", false, SkipRule.Type.FIXED, 0, 0,
                SkipRule.MissingEndBehavior.UNTOUCHED, 0, 5_000, 0, 0, Collections.singletonList(sample));
    }
}
