package de.danoeh.antennapod.playback.service.skip;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SkipTask {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Future<?> future;
    private volatile Runnable cancellationListener;
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
        if (cancelled.compareAndSet(false, true)) {
            Runnable listener = cancellationListener;
            if (listener != null) {
                listener.run();
            }
        }
        Future<?> taskFuture = future;
        return taskFuture == null || taskFuture.cancel(true);
    }

    void setCancellationListener(Runnable listener) {
        cancellationListener = listener;
        if (cancelled.get()) {
            listener.run();
        }
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

    void retry() {
        complete = false;
        future = null;
    }
}
