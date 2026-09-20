package de.danoeh.antennapod.playback.service.skip;

import android.content.Context;
import android.net.Uri;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.database.DBReader;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class SkipAnalysisWorker extends Worker {
    public static final String WORK_DATA_MEDIA_ID = "media_id";
    private static final long RUN_BUDGET_MS = TimeUnit.MINUTES.toMillis(4);
    private static final int MAX_RETRIES = 3;
    private static final String RETRY_PREFERENCES = "audio_skip_analysis_retries";
    private static final String ERROR_RETRY_PREFIX = "error:";
    private static final String INPUT_RETRY_PREFIX = "input:";

    private final CountDownLatch completion = new CountDownLatch(1);
    private final AtomicReference<SkipAnalysisStatus> status = new AtomicReference<>();
    private volatile SkipTask task;

    public SkipAnalysisWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        long mediaId = getInputData().getLong(WORK_DATA_MEDIA_ID, 0);
        FeedMedia media = DBReader.getFeedMedia(mediaId);
        if (media == null) {
            return Result.success();
        }
        if (!media.localFileAvailable() || media.getItem() == null || media.getItem().getFeed() == null
                || media.getDuration() <= 0) {
            return retryWithCount(INPUT_RETRY_PREFIX, mediaId) ? Result.retry() : Result.failure();
        }

        String feedId = String.valueOf(media.getItem().getFeed().getId());
        String episodeId = String.valueOf(media.getItem().getId());
        SkipManager manager = SkipManager.getInstance(getApplicationContext());

        task = manager.analyze(feedId, episodeId, Uri.parse(media.getLocalFileUrl()), media.getDuration(), 0,
                SkipPriority.BACKGROUND);
        SkipSubscription subscription = manager.observe(feedId, episodeId, snapshot -> {
            if (snapshot.status == SkipAnalysisStatus.READY
                    || snapshot.status == SkipAnalysisStatus.NO_MATCHES
                    || snapshot.status == SkipAnalysisStatus.ERROR
                    || snapshot.status == SkipAnalysisStatus.DOWNLOAD_REQUIRED) {
                status.set(snapshot.status);
                completion.countDown();
            }
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RUN_BUDGET_MS);
            while (!completion.await(1, TimeUnit.SECONDS)) {
                if (isStopped() || task.isCancelled() || System.nanoTime() >= deadline) {
                    task.cancel();
                    return Result.retry();
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            if (task != null) {
                task.cancel();
            }
            return Result.retry();
        } finally {
            subscription.close();
        }

        if (isStopped() || task == null || task.isCancelled()) {
            return Result.retry();
        }
        if (status.get() != SkipAnalysisStatus.ERROR) {
            clearRetryCounts(getApplicationContext(), mediaId);
            return Result.success();
        }
        return retryWithCount(ERROR_RETRY_PREFIX, mediaId) ? Result.retry() : Result.failure();
    }

    static void clearRetryCounts(Context context, long mediaId) {
        context.getSharedPreferences(RETRY_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .remove(ERROR_RETRY_PREFIX + mediaId)
                .remove(INPUT_RETRY_PREFIX + mediaId)
                .apply();
    }

    private boolean retryWithCount(String prefix, long mediaId) {
        String key = prefix + mediaId;
        SharedPreferences preferences = getApplicationContext()
                .getSharedPreferences(RETRY_PREFERENCES, Context.MODE_PRIVATE);
        int retries = preferences.getInt(key, 0);
        if (retries >= MAX_RETRIES) {
            return false;
        }
        preferences.edit().putInt(key, retries + 1).apply();
        return true;
    }

    @Override
    public void onStopped() {
        if (task != null) {
            task.cancel();
        }
        super.onStopped();
    }
}
