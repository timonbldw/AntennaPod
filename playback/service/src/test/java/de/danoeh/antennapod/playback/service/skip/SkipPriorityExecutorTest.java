package de.danoeh.antennapod.playback.service.skip;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SkipPriorityExecutorTest {
    @Test
    public void windowContinuationYieldsToExtraction() throws InterruptedException {
        SkipPriorityExecutor executor = new SkipPriorityExecutor();
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(SkipPriority.CURRENT_PLAYBACK, () -> {
            occupied.countDown();
            await(release);
        });
        assertTrue(occupied.await(5, TimeUnit.SECONDS));
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        SkipTask analysis = new SkipTask();
        try {
            executor.execute(SkipPriority.BACKGROUND, analysis, () -> {
                order.add("window one");
                executor.execute(SkipPriority.CURRENT_PLAYBACK, () -> order.add("sample"));
                executor.execute(SkipPriority.BACKGROUND, analysis, () -> {
                    order.add("window two");
                    analysis.complete();
                    done.countDown();
                });
            });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(Arrays.asList("window one", "sample", "window two"), order);
            assertTrue(analysis.isDone());
        } finally {
            release.countDown();
        }
    }

    @Test
    public void cancellationDoesNotKillWorkerAndCancelledQueuedWorkCannotRun() throws InterruptedException {
        SkipPriorityExecutor executor = new SkipPriorityExecutor();
        CountDownLatch occupied = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Runnable blocking = () -> {
            occupied.countDown();
            await(release);
        };
        SkipTask first = executor.execute(SkipPriority.BACKGROUND, blocking);
        executor.execute(SkipPriority.BACKGROUND, blocking);
        assertTrue(occupied.await(5, TimeUnit.SECONDS));
        List<String> work = Collections.synchronizedList(new ArrayList<>());
        SkipTask cancelled = executor.execute(SkipPriority.CURRENT_PLAYBACK, () -> work.add("cancelled"));
        cancelled.cancel();
        CountDownLatch finished = new CountDownLatch(1);
        executor.execute(SkipPriority.CURRENT_PLAYBACK, () -> {
            work.add("next");
            finished.countDown();
        });
        try {
            first.cancel();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(Collections.singletonList("next"), work);
        } finally {
            release.countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
