package de.danoeh.antennapod.playback.service.skip;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SkipTask {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Future<?> future;
    private volatile boolean complete;

    void attach(Future<?> taskFuture) {
        future = taskFuture;
        if (cancelled.get()) {
            taskFuture.cancel(true);
        }
    }

    boolean isCancellationRequested() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    public boolean cancel() {
        cancelled.set(true);
        Future<?> taskFuture = future;
        return taskFuture == null || taskFuture.cancel(true);
    }

    public boolean isCancelled() {
        return cancelled.get() || (future != null && future.isCancelled());
    }

    public boolean isDone() {
        return complete || isCancelled();
    }

    void complete() {
        complete = true;
    }
}
