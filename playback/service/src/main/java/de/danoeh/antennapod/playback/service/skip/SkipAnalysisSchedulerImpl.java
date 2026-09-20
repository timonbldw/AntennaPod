package de.danoeh.antennapod.playback.service.skip;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import de.danoeh.antennapod.playback.base.SkipAnalysisScheduler;

import java.util.concurrent.TimeUnit;

public class SkipAnalysisSchedulerImpl extends SkipAnalysisScheduler {
    private static final String WORK_NAME_PREFIX = "audioSkipAnalysis:";

    @Override
    public void enqueue(Context context, long mediaId) {
        SkipAnalysisWorker.clearRetryCounts(context, mediaId);
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SkipAnalysisWorker.class)
                .setInputData(new Data.Builder()
                        .putLong(SkipAnalysisWorker.WORK_DATA_MEDIA_ID, mediaId)
                        .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME_PREFIX + mediaId,
                ExistingWorkPolicy.REPLACE, request);
    }
}
