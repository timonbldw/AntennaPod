package de.danoeh.antennapod.playback.service.skip;

import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

final class SkipPriorityExecutor {
    private final PriorityBlockingQueue<PrioritizedTask> queue = new PriorityBlockingQueue<>();
    private final AtomicLong sequence = new AtomicLong();

    SkipPriorityExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "skip-audio-analysis");
            thread.setDaemon(true);
            return thread;
        };
        for (int index = 0; index < 2; index++) {
            Thread worker = factory.newThread(this::run);
            worker.start();
        }
    }

    SkipTask execute(SkipPriority priority, Runnable runnable) {
        SkipTask task = new SkipTask();
        execute(priority, task, () -> {
            try {
                runnable.run();
            } finally {
                task.complete();
            }
        });
        return task;
    }

    void execute(SkipPriority priority, SkipTask task, Runnable runnable) {
        PrioritizedTask prioritized = new PrioritizedTask(priority.value, sequence.getAndIncrement(),
                task, () -> {
                    if (!task.isCancellationRequested()) {
                        runnable.run();
                    }
                });
        task.attach(prioritized);
        queue.add(prioritized);
    }

    void reprioritize(SkipTask task, SkipPriority priority) {
        for (PrioritizedTask queued : queue) {
            if (queued.owner == task && queue.remove(queued)) {
                queued.priority = priority.value;
                queue.add(queued);
            }
        }
    }

    private void run() {
        while (true) {
            try {
                Thread.interrupted();
                queue.take().run();
            } catch (InterruptedException error) {
                Thread.interrupted();
            }
        }
    }

    private static final class PrioritizedTask extends FutureTask<Void>
            implements Comparable<PrioritizedTask> {
        private int priority;
        private final long sequence;
        private final SkipTask owner;
        private volatile Thread runner;

        PrioritizedTask(int priority, long sequence, SkipTask owner, Runnable runnable) {
            super(runnable, null);
            this.priority = priority;
            this.sequence = sequence;
            this.owner = owner;
        }

        @Override
        public int compareTo(PrioritizedTask other) {
            int priorityResult = Integer.compare(priority, other.priority);
            return priorityResult != 0 ? priorityResult : Long.compare(sequence, other.sequence);
        }

        @Override
        public void run() {
            runner = Thread.currentThread();
            try {
                super.run();
            } finally {
                runner = null;
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return super.cancel(mayInterruptIfRunning && runner != Thread.currentThread());
        }

    }
}
